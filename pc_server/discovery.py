"""UDP 브로드캐스트 서버 탐색 응답자 (Phase 4 마지막 항목).

Android 앱이 서버 IP 를 손으로 입력하지 않아도 되게, 같은 LAN 에서 날아온
`{"type":"DISCOVER"}` 브로드캐스트에 서버가 자기 이름과 TCP 포트를 유니캐스트로
돌려준다.

확정 설계 (AGENTS.md 섹션 4 / request.md):
  - **전용 포트 UDP 9002.** MOVE 채널(9001)에 섞지 않는다 - "UDP 는 MOVE 만"
    원칙을 유지하기 위해서다. TCP 9000 / UDP 9001 프로토콜은 무변경.
  - **응답에 서버 IP 를 넣지 않는다.** 클라이언트가 응답 패킷의 발신 주소를
    서버 주소로 쓴다 (멀티 NIC/VPN 에서 서버가 자기 IP 를 잘못 고르는 문제 회피).
  - **세션 토큰 등 비밀은 절대 넣지 않는다.** 탐색은 인증 이전 단계라 같은 LAN 의
    누구나 응답을 볼 수 있다.
  - **상태 변경 0.** 세션을 만들지도, 입력을 주입하지도 않는다.
  - 정확히 `{"type":"DISCOVER"}` 인 경우에만 응답하고 그 외(깨진 JSON, dict 가
    아닌 값, 다른 type, 256 바이트 초과, UTF-8 아님)는 **조용히** 무시한다
    - 로그도 남기지 않는다(브로드캐스트 채널이라 로그 폭주가 쉽다).
  - 탐색은 편의 기능이다. 바인딩이 실패해도 서버 기동을 막지 않는다
    (수동 IP 입력이 fallback).
  - 로그는 ASCII 만 쓴다 (AGENTS.md 섹션 9 - cp949 콘솔에서 UnicodeEncodeError).
"""
import collections
import json
import socket
import threading
import time

DISCOVERY_PORT = 9002
BIND_HOST = "0.0.0.0"

REQUEST_TYPE = "DISCOVER"
RESPONSE_TYPE = "SERVER"

# 요청으로 받아들이는 최대 크기. `{"type":"DISCOVER"}` 는 20 바이트라 넉넉하다.
MAX_REQUEST_BYTES = 256
# recvfrom 버퍼. MAX_REQUEST_BYTES 보다 커야 "256 바이트 초과" 를 실제로 볼 수 있다
# (버퍼와 같으면 잘린 패킷이 정상 크기처럼 보인다).
RECV_BUFFER_SIZE = 2048

MAX_NAME_LEN = 64
FALLBACK_NAME = "PC"

# recvfrom 이 이 주기로 깨어나 정지 신호를 확인한다 (server.ACCEPT_TIMEOUT_S 와 같은 이유).
POLL_TIMEOUT_S = 0.5
# 정지 시 수신 스레드를 기다리는 시간. 폴링 주기의 몇 배로 잡는다.
JOIN_TIMEOUT_S = 2.0

# 응답 남용 방지: 같은 발신 주소에는 RATE_LIMIT_WINDOW_S 안에서 최대 이만큼만 응답한다.
RATE_LIMIT_MAX_RESPONSES = 5
RATE_LIMIT_WINDOW_S = 1.0
# 발신자 테이블 상한 (주소를 위조한 패킷 폭주로 메모리가 자라지 않게).
MAX_TRACKED_SENDERS = 512

# 같은 종류의 오류 로그는 이 간격에 한 줄만 (input_controller 의 실패 로그와 같은 방식).
ERROR_LOG_INTERVAL_S = 5.0
# 수신이 연속으로 이만큼 실패하면 탐색만 포기한다 (소켓이 영구히 망가진 경우의 스핀 방지).
RECV_ERROR_LIMIT = 50


def normalize_name(name) -> str:
    """응답에 넣을 표시용 이름: 최대 64 자, 비어 있으면 "PC"."""
    if not isinstance(name, str):
        name = ""
    name = name[:MAX_NAME_LEN]
    return name if name else FALLBACK_NAME


def server_name() -> str:
    """이 PC 의 호스트명 (실패하면 "PC")."""
    try:
        return normalize_name(socket.gethostname())
    except OSError:
        return FALLBACK_NAME


def handle_discovery_packet(data: bytes, name: str, tcp_port: int) -> bytes | None:
    """탐색 패킷 하나를 판정한다. 응답할 바이트열, 무시할 거면 None.

    소켓에 의존하지 않는 순수 함수다 (경계면 규칙 전부가 여기 한 곳에 있다).
    어떤 입력에도 예외를 던지지 않는다 - 브로드캐스트 포트에는 남의 앱 패킷도
    섞여 들어온다.
    """
    if not isinstance(data, (bytes, bytearray)):
        return None
    if len(data) > MAX_REQUEST_BYTES:
        return None
    try:
        text = bytes(data).decode("utf-8")
    except UnicodeDecodeError:
        return None
    try:
        request = json.loads(text)
    except ValueError:  # JSONDecodeError 포함
        return None
    if not isinstance(request, dict):
        return None
    if request.get("type") != REQUEST_TYPE:
        return None
    payload = {
        "type": RESPONSE_TYPE,
        "name": normalize_name(name),
        "port": int(tcp_port),
    }
    # 키 순서 type -> name -> port, 공백 없는 압축 JSON, 개행 없음 (패킷 1개 = 메시지 1개)
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


class ResponseRateLimiter:
    """발신 주소별 "창당 N 회" 응답 제한.

    시간 소스를 주입받아(`monotonic`) 테스트가 sleep 없이 창 경과를 재현할 수 있다.
    거부된 요청은 창에 기록하지 않는다 - "응답을 몇 번 했는가" 기준이다.
    """

    def __init__(self, max_responses: int = RATE_LIMIT_MAX_RESPONSES,
                 window: float = RATE_LIMIT_WINDOW_S,
                 max_senders: int = MAX_TRACKED_SENDERS,
                 monotonic=time.monotonic):
        self._max_responses = max_responses
        self._window = window
        self._max_senders = max_senders
        self._monotonic = monotonic
        self._lock = threading.Lock()
        self._hits = {}  # sender key -> deque[응답한 monotonic 시각]

    def allow(self, key) -> bool:
        now = self._monotonic()
        cutoff = now - self._window
        with self._lock:
            self._prune(cutoff)
            hits = self._hits.get(key)
            if hits is None:
                hits = collections.deque()
                self._hits[key] = hits
            while hits and hits[0] <= cutoff:
                hits.popleft()
            if len(hits) >= self._max_responses:
                return False
            hits.append(now)
            return True

    def tracked_senders(self) -> int:
        """테이블 크기 (테스트/진단용)."""
        with self._lock:
            return len(self._hits)

    def _prune(self, cutoff: float):
        """창을 벗어난 발신자를 지우고, 그래도 상한을 넘으면 오래된 순으로 버린다."""
        stale = [key for key, hits in self._hits.items() if not hits or hits[-1] <= cutoff]
        for key in stale:
            del self._hits[key]
        overflow = len(self._hits) - self._max_senders
        if overflow > 0:
            oldest = sorted(self._hits, key=lambda key: self._hits[key][-1])[:overflow]
            for key in oldest:
                del self._hits[key]


class DiscoveryResponder:
    """UDP 탐색 요청에 응답하는 백그라운드 데몬 스레드.

    `start()` 는 바인딩에 실패해도 예외를 올리지 않고 False 를 돌려준다
    (탐색이 꺼질 뿐 서버는 정상 동작해야 한다). `stop()` 은 몇 번 불러도 안전하다.
    """

    def __init__(self, tcp_port: int, name: str = None, host: str = BIND_HOST,
                 discovery_port: int = DISCOVERY_PORT,
                 poll_timeout: float = POLL_TIMEOUT_S,
                 join_timeout: float = JOIN_TIMEOUT_S,
                 monotonic=time.monotonic, log=print,
                 max_responses_per_window: int = RATE_LIMIT_MAX_RESPONSES,
                 rate_limit_window: float = RATE_LIMIT_WINDOW_S,
                 max_senders: int = MAX_TRACKED_SENDERS):
        self.tcp_port = int(tcp_port)
        self.name = normalize_name(name) if name is not None else server_name()
        self.host = host
        self.requested_port = discovery_port
        self.poll_timeout = poll_timeout
        self.join_timeout = join_timeout
        # 실제로 바인드된 포트 (테스트가 포트 0 으로 띄울 수 있게 노출). 미기동이면 None.
        self.port = None
        self._monotonic = monotonic
        self._log = log
        self._limiter = ResponseRateLimiter(
            max_responses=max_responses_per_window,
            window=rate_limit_window,
            max_senders=max_senders,
            monotonic=monotonic,
        )
        self._socket = None
        self._thread = None
        self._stop = threading.Event()
        self._lifecycle_lock = threading.Lock()  # start/stop 이 겹쳐도 안전하게
        self._error_logged_at = {}  # kind -> 마지막으로 로그한 monotonic 시각

    @property
    def running(self) -> bool:
        thread = self._thread
        return thread is not None and thread.is_alive()

    def start(self) -> bool:
        """소켓을 바인드하고 수신 스레드를 띄운다. 성공 여부를 돌려준다.

        바인딩 실패(포트 점유 등)는 예외가 아니라 한 줄 로그 + False 다.
        탐색은 편의 기능이고 수동 IP 입력이 fallback 이기 때문이다.
        """
        with self._lifecycle_lock:
            if self._thread is not None:
                return True
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            # SO_REUSEADDR 를 **설정하지 않는다**: Windows 에서는 이 옵션이 이미 점유된
            # 포트에도 bind 를 성공시켜 버려서(AGENTS.md 섹션 10), 서버가 두 번 떠도
            # 아무도 눈치채지 못하고 탐색 응답이 두 개 나간다.
            try:
                sock.bind((self.host, self.requested_port))
                sock.settimeout(self.poll_timeout)
            except OSError as e:
                sock.close()
                self._log("[!] Discovery disabled: %s" % e)
                return False
            self._socket = sock
            self.port = sock.getsockname()[1]
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._serve, args=(sock,), name="phone-pad-discovery", daemon=True
            )
            self._thread.start()
        self._log(
            "Phone Pad Server discovery on UDP port %d (broadcast DISCOVER) ..." % self.port
        )
        return True

    def stop(self):
        """수신 스레드를 세우고 소켓을 닫는다. 여러 번 호출해도 안전하다."""
        with self._lifecycle_lock:
            self._stop.set()
            thread, self._thread = self._thread, None
            sock, self._socket = self._socket, None
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=self.join_timeout)
            if thread.is_alive():
                self._log("[!] Discovery thread did not stop in time - continuing shutdown")
        if sock is not None:
            try:
                sock.close()
            except OSError as e:
                self._log("[!] Failed to close discovery socket: %s" % e)

    # ------------------------------------------------------------------
    # 내부
    # ------------------------------------------------------------------

    def _serve(self, sock: socket.socket):
        consecutive_errors = 0
        while not self._stop.is_set():
            try:
                data, addr = sock.recvfrom(RECV_BUFFER_SIZE)
            except socket.timeout:
                continue
            except OSError as e:
                if self._stop.is_set() or sock.fileno() == -1:
                    break  # 정지 경로에서 소켓이 닫혔다
                # Windows 함정: 버퍼보다 큰 데이터그램(WSAEMSGSIZE) 이나, 응답을 받을
                # 상대가 이미 소켓을 닫아 ICMP port unreachable 이 돌아온 경우
                # (WSAECONNRESET) 에도 recvfrom 이 OSError 를 던진다. 둘 다 탐색을
                # 접을 이유가 아니므로 계속 돈다. 다만 소켓이 영구히 망가진 경우의
                # 무한 스핀을 막으려고 연속 실패에는 상한을 둔다.
                consecutive_errors += 1
                self._log_throttled("recv", "[!] Discovery recv failed: %s" % e)
                if consecutive_errors >= RECV_ERROR_LIMIT:
                    self._log(
                        "[!] Discovery stopped after %d consecutive recv errors"
                        % consecutive_errors
                    )
                    break
                continue
            consecutive_errors = 0
            try:
                self._respond(sock, data, addr)
            except Exception as e:  # 패킷 하나 때문에 탐색 스레드가 죽으면 안 된다
                self._log_throttled("handle", "[!] Discovery packet handling failed: %s" % e)

    def _respond(self, sock: socket.socket, data: bytes, addr):
        response = handle_discovery_packet(data, self.name, self.tcp_port)
        if response is None:
            return  # 조용히 무시 (로그 없음)
        if not self._limiter.allow(sender_key(addr)):
            return  # 남용 방지 - 역시 조용히
        try:
            sock.sendto(response, addr)
        except OSError as e:
            self._log_throttled("send", "[!] Discovery reply failed: %s" % e)

    def _log_throttled(self, kind: str, message: str):
        """같은 종류의 오류는 창당 한 줄만 (브로드캐스트 채널의 로그 폭주 방지)."""
        now = self._monotonic()
        last = self._error_logged_at.get(kind)
        if last is not None and (now - last) < ERROR_LOG_INTERVAL_S:
            return
        self._error_logged_at[kind] = now
        try:
            self._log(message)
        except (OSError, ValueError):
            # 로그 스트림이 닫혔어도 수신 루프는 계속돌아야 한다.
            pass


def sender_key(addr):
    """남용 제한의 기준이 되는 발신자 키 (포트가 아니라 IP 단위)."""
    if isinstance(addr, tuple) and addr:
        return addr[0]
    return addr
