"""PIN 코드 인증 (Phase 5) - 순수 로직.

와이어 (AGENTS.md 섹션 4 / request.md, TCP 9000):

    클라이언트 -> 서버 (연결 직후, 다른 어떤 것보다 먼저 보내는 한 줄)
        {"type":"AUTH","pin":"483920"}
    성공: 서버 -> 클라이언트 (기존 SESSION 줄 그대로, 형식 무변경)
        {"type":"SESSION","session":"0123456789abcdef0123456789abcdef"}
    실패(PIN 불일치): 서버 -> 클라이언트, 보낸 뒤 즉시 연결을 닫는다
        {"type":"AUTH_FAIL","reason":"invalid_pin"}

확정 설계:
  - **AUTH 는 인증이 꺼져 있어도 항상 보낸다.** 클라이언트는 서버가 PIN 을
    요구하는지 미리 알 수 없으므로 와이어 형식이 서버 설정에 따라 갈라지면
    안 된다. 인증이 꺼져 있으면 서버는 `pin` 값과 무관하게 통과시킨다.
  - **형식이 틀린 첫 줄은 조용히 닫는다** (타임아웃 / EOF / 깨진 JSON /
    dict 가 아닌 값 / `type != "AUTH"` / `pin` 이 문자열이 아님). 응답을 만들
    정보가 없거나, 실패를 알리는 것 자체가 무의미한 경우다.
    `AUTH_FAIL` 은 **형식은 맞았는데 값이 틀린 경우에만** 보낸다.
  - **PIN 비교는 `hmac.compare_digest`** (타이밍 공격 방지 - 6자리 숫자라 큰
    위협은 아니지만 비용이 없다).
  - **브루트포스 방어**: 발신 IP 별 AUTH 실패를 세어 60초 안에 5회 실패하면
    남은 창 동안 그 IP 의 새 연결은 AUTH 줄을 읽지도 않고 조용히 닫는다.
    "형식 오류로 조용히 닫음" 과 구분되지 않게 하는 게 핵심이다 - 잠금 상태를
    공격자에게 알리지 않는다.
  - **PIN 값 자체는 로그에 절대 남기지 않는다.** 콘솔 시작 메시지 한 줄
    (`server.main()`) 만이 의도된 노출이다.
  - **탐색 응답(UDP 9002)에는 PIN 을 넣지 않는다** - 인증 없는 채널이라 PIN 을
    실어 보내면 인증 자체가 무의미해진다 (`discovery.py` 무변경).
  - 로그는 ASCII 만 쓴다 (AGENTS.md 섹션 9 - cp949 콘솔의 UnicodeEncodeError).

표준 라이브러리만 쓰고 소켓/시간에 의존하지 않는다 (`AuthAttemptLimiter` 는
시간 소스를 주입받으므로 sleep 없이 테스트된다 - `discovery.ResponseRateLimiter`
와 같은 패턴).
"""
import collections
import hmac
import json
import secrets
import threading
import time

# ---------------------------------------------------------------------------
# PIN
# ---------------------------------------------------------------------------
PIN_DIGITS = 6
PIN_RANGE = 10 ** PIN_DIGITS

# ---------------------------------------------------------------------------
# 와이어 상수
# ---------------------------------------------------------------------------
AUTH_TYPE = "AUTH"
AUTH_FAIL_TYPE = "AUTH_FAIL"
AUTH_FAIL_REASON = "invalid_pin"
# 공백 없는 압축 JSON + 개행 (HEARTBEAT_ACK_LINE 과 같은 방식)
AUTH_FAIL_LINE = (
    json.dumps({"type": AUTH_FAIL_TYPE, "reason": AUTH_FAIL_REASON}, separators=(",", ":"))
    + "\n"
).encode("utf-8")

# ---------------------------------------------------------------------------
# 브루트포스 방어
# ---------------------------------------------------------------------------
# 같은 IP 에서 FAILURE_WINDOW_S 안에 FAILURE_LIMIT 회 실패하면 잠근다.
FAILURE_LIMIT = 5
FAILURE_WINDOW_S = 60.0
# 발신자 테이블 상한 (주소를 위조한 연결 폭주로 메모리가 자라지 않게).
MAX_TRACKED_SOURCES = 512
# 같은 종류의 로그는 이 간격에 한 줄만 (`input_controller._note_input_failure`
# / `discovery._log_throttled` 와 같은 방식 - 폭주 방지).
LOG_INTERVAL_S = 5.0


def generate_pin() -> str:
    """이번 세션용 6자리 PIN. 0-패딩된 문자열이다 (예: "004821").

    `random` 이 아니라 `secrets` 를 쓴다 - 이 값이 접근 제어의 전부다.
    """
    return f"{secrets.randbelow(PIN_RANGE):0{PIN_DIGITS}d}"


def parse_auth_message(data) -> str:
    """AUTH 메시지에서 `pin` 문자열을 뽑는다. 형식이 안 맞으면 `None`.

    받는 값: UTF-8 바이트열 한 줄 / 문자열 한 줄 / 이미 파싱된 dict.
    **어떤 입력에도 예외를 던지지 않는다** (인증 이전 단계라 아무 바이트나 온다).
    소켓·시간에 의존하지 않는 순수 함수다.
    """
    if isinstance(data, (bytes, bytearray)):
        try:
            data = bytes(data).decode("utf-8")
        except UnicodeDecodeError:
            return None
    if isinstance(data, str):
        text = data.strip()
        if not text:
            return None  # 빈 줄
        try:
            data = json.loads(text)
        except ValueError:  # JSONDecodeError 포함
            return None
    if not isinstance(data, dict):
        return None
    if data.get("type") != AUTH_TYPE:
        return None
    pin = data.get("pin")
    if not isinstance(pin, str):
        return None  # 누락 / 숫자 / None / 리스트 등
    return pin


def pins_match(actual, expected) -> bool:
    """PIN 두 개가 같은지 상수 시간으로 비교한다.

    문자열이 아니면 곧바로 False (계약 위반은 실패로 본다). 길이가 달라도
    안전하다 - `compare_digest` 는 길이 차이에서만 조기 반환한다.
    """
    if not isinstance(actual, str) or not isinstance(expected, str):
        return False
    try:
        return hmac.compare_digest(actual.encode("utf-8"), expected.encode("utf-8"))
    except (TypeError, ValueError):  # pragma: no cover - 방어적
        return False


def source_key(addr):
    """실패 집계의 기준이 되는 발신자 키 (포트가 아니라 IP 단위).

    `discovery.sender_key` 와 같은 규칙이다 - 포트는 연결마다 바뀌므로 포트까지
    키에 넣으면 브루트포스 방어가 사실상 동작하지 않는다.
    """
    if isinstance(addr, tuple) and addr:
        return addr[0]
    return addr


class AuthAttemptLimiter:
    """발신 IP 별 "창당 N 회 실패면 잠금".

    시간 소스를 주입받아(`monotonic`) 테스트가 sleep 없이 창 경과를 재현할 수
    있다 (`discovery.ResponseRateLimiter` 와 같은 패턴). 성공한 인증은 세지
    않는다 - "실패를 몇 번 했는가" 기준이다.

    로그에는 PIN 값도, 시도한 값도 남기지 않는다 (IP 와 횟수만).
    """

    def __init__(self, failure_limit: int = FAILURE_LIMIT,
                 window: float = FAILURE_WINDOW_S,
                 max_sources: int = MAX_TRACKED_SOURCES,
                 monotonic=time.monotonic, log=print,
                 log_interval: float = LOG_INTERVAL_S):
        self._failure_limit = failure_limit
        self._window = window
        self._max_sources = max_sources
        self._monotonic = monotonic
        self._log = log
        self._log_interval = log_interval
        self._lock = threading.Lock()
        self._failures = {}  # source key -> deque[실패한 monotonic 시각]
        self._logged_at = {}  # kind -> 마지막으로 로그한 monotonic 시각

    # -- 집계 -------------------------------------------------------------

    def record_failure(self, key) -> bool:
        """실패 1회를 기록한다. 그 결과로 잠금 상태가 되었으면 True."""
        now = self._monotonic()
        cutoff = now - self._window
        with self._lock:
            self._prune(cutoff)
            hits = self._failures.get(key)
            if hits is None:
                hits = collections.deque()
                self._failures[key] = hits
            while hits and hits[0] <= cutoff:
                hits.popleft()
            hits.append(now)
            locked = len(hits) >= self._failure_limit
        if locked:
            self._log_throttled(
                "lockout",
                "[!] Auth lockout for %s: %d failures within %ds - new connections dropped"
                % (key, self._failure_limit, int(self._window)),
            )
        return locked

    def is_locked_out(self, key) -> bool:
        """이 발신자가 지금 잠겨 있는지 (창을 벗어난 실패는 자동으로 빠진다)."""
        now = self._monotonic()
        cutoff = now - self._window
        with self._lock:
            hits = self._failures.get(key)
            if hits is None:
                return False
            while hits and hits[0] <= cutoff:
                hits.popleft()
            if not hits:
                del self._failures[key]
                return False
            return len(hits) >= self._failure_limit

    def note_blocked(self, key):
        """잠금 때문에 연결을 즉시 닫았음을 (창당 한 줄만) 로그한다.

        와이어 동작은 "조용히 닫기" 그대로다 - 로그는 서버 운영자용이다.
        """
        self._log_throttled("blocked", "[!] Auth locked out - dropped connection from %s" % (key,))

    def failure_count(self, key) -> int:
        """창 안에 남아 있는 실패 횟수 (테스트/진단용)."""
        now = self._monotonic()
        cutoff = now - self._window
        with self._lock:
            hits = self._failures.get(key)
            if hits is None:
                return 0
            while hits and hits[0] <= cutoff:
                hits.popleft()
            return len(hits)

    def tracked_sources(self) -> int:
        """테이블 크기 (테스트/진단용)."""
        with self._lock:
            return len(self._failures)

    # -- 내부 -------------------------------------------------------------

    def _prune(self, cutoff: float):
        """창을 벗어난 발신자를 지우고, 그래도 상한을 넘으면 오래된 순으로 버린다."""
        stale = [key for key, hits in self._failures.items() if not hits or hits[-1] <= cutoff]
        for key in stale:
            del self._failures[key]
        overflow = len(self._failures) - self._max_sources
        if overflow > 0:
            oldest = sorted(self._failures, key=lambda key: self._failures[key][-1])[:overflow]
            for key in oldest:
                del self._failures[key]

    def _log_throttled(self, kind: str, message: str):
        """같은 종류의 로그는 창당 한 줄만 (실패 폭주 시 로그 폭주 방지)."""
        now = self._monotonic()
        last = self._logged_at.get(kind)
        if last is not None and (now - last) < self._log_interval:
            return
        self._logged_at[kind] = now
        try:
            self._log(message)
        except (OSError, ValueError):
            # 로그 스트림이 닫혔어도 인증 처리는 계속돼야 한다.
            pass
