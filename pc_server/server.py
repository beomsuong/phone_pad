import json
import socket
import threading
import uuid

from input_controller import InputController

HOST = "0.0.0.0"
TCP_PORT = 9000
UDP_PORT = 9001
UDP_BUFFER_SIZE = 2048


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
    except OSError as e:
        print(f"[!] Failed to send session to {addr}: {e}")
        registry.remove(session)
        conn.close()
        return

    buffer = ""
    try:
        while True:
            data = conn.recv(4096)
            if not data:
                break
            buffer += data.decode("utf-8")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                    controller.handle_event(event)
                except json.JSONDecodeError:
                    print(f"[!] Invalid JSON: {line!r}")
    except Exception as e:
        print(f"[!] Error from {addr}: {e}")
    finally:
        registry.remove(session)
        print(f"[=] Session revoked: {session}")
        conn.close()
        print(f"[-] Disconnected: {addr}")


def main():
    controller = InputController()
    registry = SessionRegistry()

    udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    udp_sock.bind((HOST, UDP_PORT))
    threading.Thread(
        target=udp_listener, args=(udp_sock, controller, registry), daemon=True
    ).start()
    print(f"Phone Pad Server listening on UDP port {UDP_PORT} (MOVE only) ...")

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as srv:
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((HOST, TCP_PORT))
        srv.listen()
        print(f"Phone Pad Server listening on TCP port {TCP_PORT} ...")
        while True:
            conn, addr = srv.accept()
            t = threading.Thread(
                target=handle_client,
                args=(conn, addr, controller, registry),
                daemon=True,
            )
            t.start()


if __name__ == "__main__":
    main()
