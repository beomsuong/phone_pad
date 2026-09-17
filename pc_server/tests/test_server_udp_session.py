import json
import socket
import threading
import time
from unittest.mock import patch

import server
from input_controller import InputController


class FakeConn:
    """socket.socket 대역: recv 로 돌려줄 바이트열을 미리 넣어둔다."""

    def __init__(self, chunks=None):
        self._chunks = list(chunks or [])
        self.sent = []
        self.closed = False

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, _size):
        if self._chunks:
            return self._chunks.pop(0)
        return b""  # 연결 종료

    def close(self):
        self.closed = True

    def sent_lines(self):
        joined = b"".join(self.sent).decode("utf-8")
        return [line for line in joined.split("\n") if line]


def make_controller():
    """SendInput 을 절대 호출하지 않는 InputController."""
    controller = InputController()
    return controller


# --------------------------------------------------------------------------
# SessionRegistry
# --------------------------------------------------------------------------

def test_issue_returns_32_hex_token_and_registers_it():
    registry = server.SessionRegistry()
    token = registry.issue()
    assert len(token) == 32
    int(token, 16)  # hex 여야 한다
    assert registry.is_active(token)


def test_issue_returns_unique_tokens():
    registry = server.SessionRegistry()
    tokens = {registry.issue() for _ in range(50)}
    assert len(tokens) == 50


def test_remove_makes_session_inactive():
    registry = server.SessionRegistry()
    token = registry.issue()
    registry.remove(token)
    assert not registry.is_active(token)
    registry.remove(token)  # 중복 제거도 예외 없이


def test_is_active_rejects_non_string_and_empty():
    registry = server.SessionRegistry()
    assert not registry.is_active(None)
    assert not registry.is_active("")
    assert not registry.is_active(123)
    assert not registry.is_active("unknown-token")


# --------------------------------------------------------------------------
# UDP MOVE 패킷 처리
# --------------------------------------------------------------------------

def test_udp_move_with_active_session_calls_move():
    registry = server.SessionRegistry()
    controller = make_controller()
    token = registry.issue()
    packet = json.dumps({"session": token, "type": "MOVE", "dx": 2.5, "dy": -1.0}).encode("utf-8")

    with patch.object(controller, "_move") as mock_move:
        handled = server.handle_udp_packet(packet, controller, registry)

    assert handled is True
    # round() 는 banker's rounding: 2.5 -> 2 (input_controller 기존 동작)
    mock_move.assert_called_once_with(2, -1)


def test_udp_move_with_unknown_session_is_ignored():
    registry = server.SessionRegistry()
    controller = make_controller()
    registry.issue()  # 다른 세션은 활성이지만 패킷의 토큰은 미등록
    packet = json.dumps({"session": "0" * 32, "type": "MOVE", "dx": 5, "dy": 5}).encode("utf-8")

    with patch.object(controller, "_move") as mock_move:
        handled = server.handle_udp_packet(packet, controller, registry)

    assert handled is False
    mock_move.assert_not_called()


def test_udp_move_without_session_field_is_ignored():
    registry = server.SessionRegistry()
    controller = make_controller()
    registry.issue()
    packet = json.dumps({"type": "MOVE", "dx": 5, "dy": 5}).encode("utf-8")

    with patch.object(controller, "_move") as mock_move:
        assert server.handle_udp_packet(packet, controller, registry) is False
    mock_move.assert_not_called()


def test_udp_move_after_session_removed_is_ignored():
    registry = server.SessionRegistry()
    controller = make_controller()
    token = registry.issue()
    packet = json.dumps({"session": token, "type": "MOVE", "dx": 4, "dy": 4}).encode("utf-8")

    registry.remove(token)
    with patch.object(controller, "_move") as mock_move:
        assert server.handle_udp_packet(packet, controller, registry) is False
    mock_move.assert_not_called()


def test_udp_invalid_json_is_ignored_without_raising():
    registry = server.SessionRegistry()
    controller = make_controller()
    registry.issue()

    with patch.object(controller, "_move") as mock_move:
        assert server.handle_udp_packet(b"not-json{{{", controller, registry) is False
        assert server.handle_udp_packet(b"", controller, registry) is False
        assert server.handle_udp_packet(b"\xff\xfe\xfd", controller, registry) is False
        assert server.handle_udp_packet(b"[1,2,3]", controller, registry) is False
    mock_move.assert_not_called()


def test_udp_non_move_type_is_ignored_on_udp_channel():
    """CLICK 등은 TCP 전용 — UDP 로 와도 처리하지 않는다."""
    registry = server.SessionRegistry()
    controller = make_controller()
    token = registry.issue()
    packet = json.dumps({"session": token, "type": "CLICK", "button": "left"}).encode("utf-8")

    with patch.object(controller, "_click") as mock_click:
        assert server.handle_udp_packet(packet, controller, registry) is False
    mock_click.assert_not_called()


def test_udp_move_missing_delta_defaults_to_zero():
    registry = server.SessionRegistry()
    controller = make_controller()
    token = registry.issue()
    packet = json.dumps({"session": token, "type": "MOVE"}).encode("utf-8")

    with patch.object(controller, "_move") as mock_move:
        assert server.handle_udp_packet(packet, controller, registry) is True
    mock_move.assert_not_called()  # dx=dy=0 이면 SendInput 생략


def test_udp_controller_exception_does_not_propagate():
    registry = server.SessionRegistry()
    controller = make_controller()
    token = registry.issue()
    packet = json.dumps({"session": token, "type": "MOVE", "dx": 1, "dy": 1}).encode("utf-8")

    with patch.object(controller, "_move", side_effect=RuntimeError("SendInput failed")):
        assert server.handle_udp_packet(packet, controller, registry) is False


# --------------------------------------------------------------------------
# UDP 수신 루프 (실제 소켓)
# --------------------------------------------------------------------------

def test_udp_listener_loop_processes_packet_from_real_socket():
    registry = server.SessionRegistry()
    controller = make_controller()
    token = registry.issue()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", 0))
    sock.settimeout(0.1)
    port = sock.getsockname()[1]
    stop_event = threading.Event()

    received = threading.Event()

    moved = []

    def fake_move(dx, dy):
        moved.append((dx, dy))
        received.set()

    with patch.object(controller, "_move", side_effect=fake_move):
        thread = threading.Thread(
            target=server.udp_listener,
            args=(sock, controller, registry, stop_event),
            daemon=True,
        )
        thread.start()
        try:
            client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            payload = json.dumps(
                {"session": token, "type": "MOVE", "dx": 2.5, "dy": -1.0}
            ).encode("utf-8")
            deadline = time.time() + 3.0
            while not received.is_set() and time.time() < deadline:
                client.sendto(payload, ("127.0.0.1", port))
                received.wait(0.1)
            client.close()
        finally:
            stop_event.set()
            thread.join(timeout=2.0)
            sock.close()

    assert received.is_set(), "udp_listener 가 MOVE 패킷을 처리하지 못했다"
    assert moved[0] == (2, -1)


# --------------------------------------------------------------------------
# TCP 핸드셰이크 / 세션 수명
# --------------------------------------------------------------------------

def test_handle_client_sends_session_line_first():
    registry = server.SessionRegistry()
    controller = make_controller()
    conn = FakeConn()

    server.handle_client(conn, ("127.0.0.1", 5555), controller, registry)

    lines = conn.sent_lines()
    assert len(lines) == 1
    msg = json.loads(lines[0])
    assert msg["type"] == "SESSION"
    assert len(msg["session"]) == 32
    # 개행으로 종료되어야 한다 (newline-delimited JSON)
    assert b"".join(conn.sent).endswith(b"\n")


def test_handle_client_registers_session_while_connected():
    registry = server.SessionRegistry()
    controller = make_controller()
    seen = {}

    class ProbeConn(FakeConn):
        def recv(self, size):
            # 연결 유지 중 세션이 활성인지 확인
            seen["active"] = registry.snapshot()
            return super().recv(size)

    conn = ProbeConn()
    server.handle_client(conn, ("127.0.0.1", 5555), controller, registry)

    token = json.loads(conn.sent_lines()[0])["session"]
    assert seen["active"] == {token}


def test_handle_client_removes_session_on_disconnect():
    registry = server.SessionRegistry()
    controller = make_controller()
    conn = FakeConn()

    server.handle_client(conn, ("127.0.0.1", 5555), controller, registry)

    token = json.loads(conn.sent_lines()[0])["session"]
    assert not registry.is_active(token)
    assert registry.snapshot() == set()
    assert conn.closed


def test_handle_client_removes_session_even_on_error():
    registry = server.SessionRegistry()
    controller = make_controller()

    class ExplodingConn(FakeConn):
        def recv(self, _size):
            raise ConnectionResetError("client vanished")

    conn = ExplodingConn()
    server.handle_client(conn, ("127.0.0.1", 5555), controller, registry)

    assert registry.snapshot() == set()
    assert conn.closed


def test_handle_client_still_processes_tcp_click_over_newline_json():
    """기존 TCP 처리(개행 분리 + handle_event)가 세션 추가 후에도 동작해야 한다."""
    registry = server.SessionRegistry()
    controller = make_controller()
    conn = FakeConn(
        chunks=[
            b'{"type":"CLICK","button":"left"}\n{"type":"CLI',
            b'CK","button":"right"}\n',
        ]
    )

    with patch.object(controller, "_click") as mock_click:
        server.handle_client(conn, ("127.0.0.1", 5555), controller, registry)

    assert [call.args[0] for call in mock_click.call_args_list] == ["left", "right"]


def test_handle_client_ignores_invalid_json_line():
    registry = server.SessionRegistry()
    controller = make_controller()
    conn = FakeConn(chunks=[b"garbage\n", b'{"type":"CLICK","button":"left"}\n'])

    with patch.object(controller, "_click") as mock_click:
        server.handle_client(conn, ("127.0.0.1", 5555), controller, registry)

    mock_click.assert_called_once_with("left")
    assert registry.snapshot() == set()


def test_session_tokens_are_reissued_per_connection():
    registry = server.SessionRegistry()
    controller = make_controller()

    conn1 = FakeConn()
    server.handle_client(conn1, ("127.0.0.1", 1), controller, registry)
    conn2 = FakeConn()
    server.handle_client(conn2, ("127.0.0.1", 2), controller, registry)

    t1 = json.loads(conn1.sent_lines()[0])["session"]
    t2 = json.loads(conn2.sent_lines()[0])["session"]
    assert t1 != t2


def test_ports_match_protocol_spec():
    assert server.TCP_PORT == 9000
    assert server.UDP_PORT == 9001
