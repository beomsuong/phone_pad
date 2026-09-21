import argparse
import atexit
import json
import socket
import sys
import threading
import uuid

import discovery
import logging_setup
import single_instance
import tray
from input_controller import InputController

HOST = "0.0.0.0"
TCP_PORT = 9000
UDP_PORT = 9001
UDP_BUFFER_SIZE = 2048

# accept()/recvfrom() 이 이 주기로 깨어나 정지 신호를 확인한다.
# 짧을수록 종료가 빠르지만 idle CPU 를 쓴다 - 0.5s 는 사용자가 "종료"를 누르고
# 즉시 사라진다고 느끼는 범위 안이다.
ACCEPT_TIMEOUT_S = 0.5
# 종료 시 서버 스레드를 기다리는 시간. accept 타임아웃의 몇 배로 잡는다.
SHUTDOWN_JOIN_TIMEOUT_S = 3.0

# TCP heartbeat (AGENTS.md 섹션 4 / Phase 2)
# 클라이언트는 HEARTBEAT_INTERVAL_S 마다 {"type":"HEARTBEAT"} 를 보내고,
# 서버는 recv() 타임아웃이 연속 HEARTBEAT_MISS_LIMIT 회 발생하면 연결을 끊는다 (≈15초).
HEARTBEAT_INTERVAL_S = 5.0
HEARTBEAT_MISS_LIMIT = 3
HEARTBEAT_ACK_LINE = (
    json.dumps({"type": "HEARTBEAT_ACK"}, separators=(",", ":")) + "\n"
).encode("utf-8")


class SessionRegistry:
    """TCP 스레드와 UDP 수신 스레드가 공유하는 활성 세션 집합 (Lock 보호)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._sessions = set()

    def issue(self) -> str:
        """새 세션 토큰을 발급하고 활성 목록에 등록한다."""
        token = uuid.uuid4().hex
        with self._lock:
            self._sessions.add(token)
        return token

    def add(self, token: str):
        with self._lock:
            self._sessions.add(token)

    def remove(self, token: str):
        with self._lock:
            self._sessions.discard(token)

    def is_active(self, token) -> bool:
        if not isinstance(token, str) or not token:
            return False
        with self._lock:
            return token in self._sessions

    def snapshot(self) -> set:
        with self._lock:
            return set(self._sessions)


def handle_udp_packet(data: bytes, controller: InputController, registry: SessionRegistry) -> bool:
    """UDP MOVE 패킷 하나를 처리한다. 처리했으면 True, 무시했으면 False.

    잘못된 JSON / 미등록 세션 / 알 수 없는 타입은 조용히 무시한다 (크래시 금지).
    """
    try:
        event = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return False

    if not isinstance(event, dict):
        return False

    if not registry.is_active(event.get("session")):
        return False

    if event.get("type") != "MOVE":
        return False

    try:
        controller.handle_event(
            {
                "type": "MOVE",
                "dx": event.get("dx", 0),
                "dy": event.get("dy", 0),
            }
        )
    except Exception as e:  # 단일 패킷 오류로 수신 루프가 죽지 않게 한다
        print(f"[!] UDP MOVE handling failed: {e}")
        return False
    return True


def udp_listener(sock: socket.socket, controller: InputController, registry: SessionRegistry,
                 stop_event: threading.Event = None):
    """UDP 수신 루프. MOVE 전용 채널."""
    while stop_event is None or not stop_event.is_set():
        try:
            data, _addr = sock.recvfrom(UDP_BUFFER_SIZE)
        except socket.timeout:
            continue
        except OSError:
            break
        handle_udp_packet(data, controller, registry)


def handle_client(conn: socket.socket, addr, controller: InputController,
                  registry: SessionRegistry):
    print(f"[+] Connected: {addr}")
    session = registry.issue()
    try:
        conn.sendall((json.dumps({"type": "SESSION", "session": session}) + "\n").encode("utf-8"))
        print(f"[=] Session issued to {addr}: {session}")
        # 핸드셰이크 직후부터 heartbeat 감시 시작
        conn.settimeout(HEARTBEAT_INTERVAL_S)
    except OSError as e:
        print(f"[!] Failed to send session to {addr}: {e}")
        registry.remove(session)
        conn.close()
        return

    buffer = ""
    missed = 0  # 연속 heartbeat 미응답 횟수
    try:
        while True:
            try:
                data = conn.recv(4096)
            except socket.timeout:
                missed += 1
                if missed >= HEARTBEAT_MISS_LIMIT:
                    print(f"[!] Heartbeat timeout: dropping {addr}")
                    break
                if missed >= 2:
                    # 정상 연결에서도 송신 주기와 recv 타임아웃 창이 맞물려 1회 정도는
                    # 흔히 발생한다(F-1). 노이즈를 줄이기 위해 2회부터만 로그를 남긴다.
                    print(f"[!] Heartbeat miss {missed}/{HEARTBEAT_MISS_LIMIT} from {addr}")
                continue
            if not data:
                break
            missed = 0  # 어떤 데이터든 받았으면 살아있는 것으로 본다
            buffer += data.decode("utf-8")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    print(f"[!] Invalid JSON: {line!r}")
                    continue
                if not isinstance(event, dict):
                    # 리스트/숫자/문자열 등 dict가 아닌 JSON — UDP 경로(handle_udp_packet)와
                    # 동일하게 handle_event로 넘기지 않고 무시한다 (AttributeError 방지, F-5)
                    print(f"[!] Ignoring non-dict JSON: {line!r}")
                    continue
                if event.get("type") == "HEARTBEAT":
                    # 마우스 명령이 아니므로 handle_event 로 넘기지 않고 즉시 ACK
                    conn.sendall(HEARTBEAT_ACK_LINE)
                    continue
                try:
                    controller.handle_event(event)
                except Exception as e:
                    # 필드값이 깨진 이벤트 하나(예: dx/dy가 숫자로 변환 안 되는 값) 때문에
                    # TCP 세션 전체가 끊기면 안 된다 — UDP 경로(handle_udp_packet)와
                    # 동일하게 이 이벤트만 무시하고 계속 진행한다 (F-4)
                    print(f"[!] handle_event failed for {event!r}: {e}")
    except Exception as e:
        print(f"[!] Error from {addr}: {e}")
    finally:
        registry.remove(session)
        print(f"[=] Session revoked: {session}")
        # 드래그 안전장치: DRAG_END 가 유실된 채 연결이 끊기면(정상 종료 / heartbeat
        # 타임아웃 / 예외 전부 이 블록을 지난다) PC 마우스 왼쪽 버튼이 영원히 눌린 채
        # 멈춘다. 드래그가 활성 상태였다면 서버가 강제로 놓는다.
        try:
            if controller.force_release_drag():
                print(f"[!] Drag was active on disconnect - left button released ({addr})")
        except Exception as e:
            # 강제 해제 실패가 소켓 정리를 막으면 안 된다
            print(f"[!] Failed to release drag on disconnect: {e}")
        conn.close()
        print(f"[-] Disconnected: {addr}")


def release_drag(controller: InputController, reason: str = "shutdown") -> bool:
    """종료 경로에서 드래그를 강제로 놓는다. 예외를 밖으로 던지지 않는다.

    `InputController._drag_end` 가 이미 멱등이라(드래그 중이 아니면 아무것도 하지
    않고 False) 몇 번 호출해도 안전하다 - 정상 종료 경로와 `atexit` 안전장치가
    둘 다 호출하는 것을 전제로 한다.
    """
    try:
        if controller.force_release_drag():
            print(f"[!] Drag was active at {reason} - left button released")
            return True
    except Exception as e:
        print(f"[!] Failed to release drag at {reason}: {e}")
    return False


class ServerRuntime:
    """정지 가능한 TCP accept 루프 + UDP 리스너 묶음.

    기존 `main()` 이 하던 일을 그대로 하되, `stop_event` 로 루프를 빠져나올 수
    있고 바인드된 실제 포트를 노출한다(테스트가 포트 0 으로 띄울 수 있게).
    `handle_client`/`udp_listener` 의 시그니처와 동작은 건드리지 않는다.
    """

    def __init__(
        self,
        controller: InputController,
        registry: SessionRegistry,
        host: str = HOST,
        tcp_port: int = TCP_PORT,
        udp_port: int = UDP_PORT,
        accept_timeout: float = ACCEPT_TIMEOUT_S,
        stop_event: threading.Event = None,
        discovery_port: int = None,
    ):
        self.controller = controller
        self.registry = registry
        self.host = host
        self.requested_tcp_port = tcp_port
        self.requested_udp_port = udp_port
        # 탐색 응답자는 **기본 비활성**이다. 기본값을 9002 로 두면 테스트가
        # ServerRuntime 을 만들 때마다 실제 포트를 잡으려 들어, 서버가 떠 있는
        # 동안 무관한 테스트가 깨진다. main() 만 이 값을 넘긴다.
        self.requested_discovery_port = discovery_port
        self.discovery = None
        self.accept_timeout = accept_timeout
        self.stop_event = stop_event if stop_event is not None else threading.Event()
        # 바인드가 끝나고 accept 루프에 진입했음을 알리는 신호 (테스트/기동 동기화용)
        self.ready = threading.Event()
        self.tcp_port = None
        self.udp_port = None
        self.tcp_socket = None
        self.udp_socket = None
        self._udp_thread = None

    def bind(self):
        """소켓을 만들고 바인드한다. 실패하면 예외를 그대로 올린다(포트 점유 등)."""
        udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            udp_sock.bind((self.host, self.requested_udp_port))
            # UDP 리스너도 주기적으로 stop_event 를 확인할 수 있어야 한다
            udp_sock.settimeout(self.accept_timeout)
        except OSError:
            udp_sock.close()
            raise

        tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            tcp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            tcp_sock.bind((self.host, self.requested_tcp_port))
            tcp_sock.listen()
            tcp_sock.settimeout(self.accept_timeout)
        except OSError:
            tcp_sock.close()
            udp_sock.close()
            raise

        self.udp_socket = udp_sock
        self.tcp_socket = tcp_sock
        self.udp_port = udp_sock.getsockname()[1]
        self.tcp_port = tcp_sock.getsockname()[1]

    def serve(self):
        """`bind()` 이후의 accept 루프. `stop_event` 가 서면 소켓을 닫고 반환한다."""
        self._udp_thread = threading.Thread(
            target=udp_listener,
            args=(self.udp_socket, self.controller, self.registry, self.stop_event),
            name="phone-pad-udp",
            daemon=True,
        )
        self._udp_thread.start()
        print(f"Phone Pad Server listening on UDP port {self.udp_port} (MOVE only) ...")
        print(f"Phone Pad Server listening on TCP port {self.tcp_port} ...")
        if self.requested_discovery_port is not None:
            # 바인딩 실패는 서버 기동을 막지 않는다 (응답자가 로그만 남기고 꺼진다).
            self.discovery = discovery.DiscoveryResponder(
                tcp_port=self.tcp_port,
                host=self.host,
                discovery_port=self.requested_discovery_port,
            )
            self.discovery.start()
        self.ready.set()
        try:
            while not self.stop_event.is_set():
                try:
                    conn, addr = self.tcp_socket.accept()
                except socket.timeout:
                    continue
                except OSError:
                    # 소켓이 닫혔다 (정지 경로). 조용히 빠져나간다.
                    break
                threading.Thread(
                    target=handle_client,
                    args=(conn, addr, self.controller, self.registry),
                    daemon=True,
                ).start()
        finally:
            self.close()

    def run(self):
        try:
            self.bind()
        except OSError:
            self.stop_event.set()
            raise
        self.serve()

    def stop(self):
        """정지 요청. 아무 스레드에서나 호출 가능."""
        self.stop_event.set()
        responder = self.discovery
        if responder is not None:
            responder.stop()

    def close(self):
        """리슨 소켓과 UDP 소켓을 닫는다. 여러 번 호출해도 안전하다."""
        self.ready.clear()
        responder, self.discovery = self.discovery, None
        if responder is not None:
            responder.stop()
        for sock in (self.tcp_socket, self.udp_socket):
            if sock is None:
                continue
            try:
                sock.close()
            except OSError as e:
                print(f"[!] Failed to close socket: {e}")


def run_console(controller: InputController, registry: SessionRegistry,
                runtime: ServerRuntime = None) -> int:
    """트레이 없이 메인 스레드에서 서버를 돌린다 (기존 동작). Ctrl+C 로 종료."""
    runtime = runtime if runtime is not None else ServerRuntime(controller, registry)
    try:
        runtime.run()
    except KeyboardInterrupt:
        print("[=] Interrupted - shutting down")
        runtime.stop()
    except OSError as e:
        print(f"[!] Server failed to start: {e}")
        return 1
    finally:
        runtime.close()
        release_drag(controller, "shutdown")
    return 0


def run_with_tray(controller: InputController, registry: SessionRegistry,
                  runtime: ServerRuntime = None, tray_factory=None,
                  join_timeout: float = SHUTDOWN_JOIN_TIMEOUT_S) -> int:
    """트레이를 메인 스레드에, 서버를 백그라운드 스레드에 두고 돌린다.

    스레드 모델이 뒤집히는 이유: pystray 는 Windows 에서 메시지 루프를 메인
    스레드가 소유해야 한다. 그래서 서버 쪽이 스레드로 내려간다.

    종료 순서 (확정 설계 3):
      stop 설정 -> 서버 스레드 join(타임아웃) -> force_release_drag -> 아이콘 제거.
    서버 스레드가 예외로 죽으면(포트 점유 등) 트레이를 내리고 1 을 반환한다 -
    아이콘만 남은 좀비 프로세스를 만들지 않기 위해서다 (확정 설계 4).
    """
    runtime = runtime if runtime is not None else ServerRuntime(controller, registry)
    factory = tray_factory if tray_factory is not None else tray.TrayController
    failures = []

    def on_quit():
        runtime.stop()
        if server_thread.is_alive():
            server_thread.join(timeout=join_timeout)
            if server_thread.is_alive():
                print("[!] Server thread did not stop in time - continuing shutdown")
        release_drag(controller, "tray quit")

    tray_controller = factory(
        count_provider=lambda: len(registry.snapshot()),
        on_quit=on_quit,
        port=runtime.requested_tcp_port,
    )

    def server_main():
        try:
            runtime.run()
        except BaseException as e:  # noqa: BLE001 - 어떤 실패든 좀비 아이콘을 남기면 안 된다
            failures.append(e)
            print(f"[!] Server stopped unexpectedly: {e!r}")
        finally:
            runtime.stop()
            # 서버가 죽었는데 트레이만 남아 있으면 사용자는 서버가 도는 줄 안다.
            tray_controller.stop()

    server_thread = threading.Thread(target=server_main, name="phone-pad-server", daemon=True)
    server_thread.start()

    try:
        tray_controller.run()
    finally:
        # 트레이가 (종료 메뉴든 예외든) 내려왔으면 서버도 반드시 함께 내린다.
        runtime.stop()
        server_thread.join(timeout=join_timeout)
        release_drag(controller, "shutdown")

    return 1 if failures else 0


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Phone Pad PC server")
    parser.add_argument(
        "--no-tray",
        action="store_true",
        help="run without the system tray icon (console mode, Ctrl+C to quit)",
    )
    parser.add_argument(
        "--allow-multiple",
        action="store_true",
        help="skip the single-instance guard (development/debugging only)",
    )
    parser.add_argument(
        "--no-discovery",
        action="store_true",
        help="disable UDP broadcast server discovery (port %d)" % discovery.DISCOVERY_PORT,
    )
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)

    # PyInstaller --noconsole exe 에는 표준 스트림이 없어 print() 가 조용히 사라진다.
    # 콘솔이 있는 일반 실행에서는 아무것도 바꾸지 않는다. (logging_setup 참조)
    stdio = logging_setup.configure_stdio()

    # 같은 PC 에서 서버가 두 번 뜨는 것을 프로세스 단위로 막는다.
    # 소켓 옵션(SO_REUSEADDR)은 그대로 둔다 - single_instance 모듈 주석 참조.
    guard_exit = single_instance.enforce(
        allow_multiple=args.allow_multiple, windowed=stdio.windowed
    )
    if guard_exit is not None:
        return guard_exit

    controller = InputController()
    registry = SessionRegistry()

    # 안전장치: 정상 종료 경로를 안 거치고 인터프리터가 끝나도 버튼을 놓는다.
    # `force_release_drag` 가 멱등이라 정상 경로와 중복 호출돼도 문제 없다.
    atexit.register(release_drag, controller, "atexit")

    use_tray = not args.no_tray
    if use_tray and not tray.tray_available():
        print(
            "[!] pystray/Pillow not available - running in console mode "
            f"({tray.unavailable_reason()}). Install with: pip install -r requirements.txt"
        )
        use_tray = False

    runtime = ServerRuntime(
        controller,
        registry,
        discovery_port=None if args.no_discovery else discovery.DISCOVERY_PORT,
    )

    if use_tray:
        return run_with_tray(controller, registry, runtime)
    return run_console(controller, registry, runtime)


if __name__ == "__main__":
    sys.exit(main())
