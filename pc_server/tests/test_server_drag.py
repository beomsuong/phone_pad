"""DRAG_START / DRAG_END 의 서버 측 경로 + 연결 종료 시 강제 해제 안전장치 테스트.

핵심: DRAG_END 가 유실된 채 연결이 끊기면 PC 마우스 왼쪽 버튼이 영원히 눌린 채
멈춘다. handle_client 의 finally 는 정상 종료(EOF) / heartbeat 타임아웃 / 예외
전부를 지나므로, 그 경로마다 LEFTUP 이 실제로 나가는지 FakeConn 으로 구동해 확인한다.

test_server_heartbeat.py 의 FakeConn 패턴을 그대로 쓴다 — 실제 소켓/SendInput 호출 없음.
"""
import ctypes
import json
import socket
from unittest.mock import patch

import server
from send_input_stub import patch_send_input
from input_controller import (
    INPUT,
    MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP,
    InputController,
)

ADDR = ("127.0.0.1", 5555)

DRAG_START_LINE = b'{"type":"DRAG_START"}\n'
DRAG_END_LINE = b'{"type":"DRAG_END"}\n'


class FakeConn:
    """socket.socket 대역. chunks 의 각 원소는 bytes 이거나 예외 인스턴스."""

    def __init__(self, chunks=None):
        self._chunks = list(chunks or [])
        self.sent = []
        self.closed = False
        self.timeouts = []
        self.recv_calls = 0

    def settimeout(self, value):
        self.timeouts.append(value)

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, _size):
        self.recv_calls += 1
        if self._chunks:
            chunk = self._chunks.pop(0)
            if isinstance(chunk, BaseException):
                raise chunk
            return chunk
        return b""  # 클라이언트가 연결을 닫음

    def close(self):
        self.closed = True

    def sent_lines(self):
        joined = b"".join(self.sent).decode("utf-8")
        return [line for line in joined.split("\n") if line]


def timeout():
    return socket.timeout("timed out")


def run_client(conn, controller=None, registry=None):
    controller = controller or InputController()
    registry = registry or server.SessionRegistry()
    server.handle_client(conn, ADDR, controller, registry)
    return controller, registry


def sent_flags(mock_send):
    """SendInput 호출들에서 dwFlags 를 시간 순으로 평탄화."""
    flags = []
    for call in mock_send.call_args_list:
        count, inputs, size = call.args
        assert size == ctypes.sizeof(INPUT)
        flags.extend(inputs[i]._input.mi.dwFlags for i in range(count))
    return flags


# --------------------------------------------------------------------------
# TCP 경로: 와이어 포맷 그대로 파싱되어 handle_event 로 가는지
# --------------------------------------------------------------------------

def test_drag_start_wire_line_reaches_drag_start():
    conn = FakeConn(chunks=[DRAG_START_LINE])
    controller = InputController()

    with patch.object(controller, "_drag_start") as mock_start:
        run_client(conn, controller=controller)

    mock_start.assert_called_once_with()


def test_drag_end_wire_line_releases_button_before_disconnect():
    """DRAG_END 줄이 도착한 시점에 이미 버튼이 놓여야 한다(연결 종료 대기 금지).

    finally 의 안전장치도 같은 `_drag_end` 를 호출하므로 호출 횟수 대신
    "EOF 를 읽기 전에 해제됐는가"를 본다.
    """
    released_at_recv = []
    conn = FakeConn(chunks=[DRAG_START_LINE, DRAG_END_LINE])
    controller = InputController()

    with patch_send_input() as mock_send:
        def record_and_inject(count, inputs, size):
            released_at_recv.append(conn.recv_calls)
            return count  # 실제 SendInput 계약: 주입한 이벤트 수

        mock_send.side_effect = record_and_inject
        run_client(conn, controller=controller)

    # 1번째 recv = DRAG_START, 2번째 recv = DRAG_END. 3번째(EOF) 전에 둘 다 나가야 한다.
    assert released_at_recv == [1, 2]


def test_full_drag_sequence_over_tcp_sends_down_then_up():
    """DRAG_START → (MOVE 는 UDP) → DRAG_END 가 한 연결 안에서 down/up 으로 나간다."""
    conn = FakeConn(chunks=[DRAG_START_LINE, DRAG_END_LINE])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]
    assert controller._drag_active is False


def test_drag_lines_split_across_chunks_are_parsed():
    conn = FakeConn(chunks=[b'{"type":"DRAG_ST', b'ART"}\n{"type":"DRAG_END"}\n'])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]


def test_drag_events_do_not_produce_downstream_traffic():
    """서버의 유일한 하향 트래픽은 SESSION 과 HEARTBEAT_ACK 뿐이다."""
    conn = FakeConn(chunks=[DRAG_START_LINE, DRAG_END_LINE])

    with patch_send_input():
        run_client(conn)

    assert [json.loads(l)["type"] for l in conn.sent_lines()] == ["SESSION"]


# --------------------------------------------------------------------------
# 안전장치: 연결 종료 시 강제 해제
# --------------------------------------------------------------------------

def test_drag_active_at_normal_disconnect_is_force_released():
    """DRAG_START 후 DRAG_END 없이 클라이언트가 연결을 닫으면(EOF) 서버가 놓는다."""
    conn = FakeConn(chunks=[DRAG_START_LINE, b""])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]
    assert controller._drag_active is False
    assert conn.closed


def test_drag_active_at_heartbeat_timeout_is_force_released():
    """heartbeat 타임아웃(3회 연속 무응답)으로 끊길 때도 버튼을 놓는다."""
    conn = FakeConn(chunks=[DRAG_START_LINE, timeout(), timeout(), timeout()])
    controller = InputController()
    registry = server.SessionRegistry()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller, registry=registry)

    assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]
    assert controller._drag_active is False
    assert registry.snapshot() == set()  # 세션 회수도 그대로 동작
    assert conn.closed


def test_drag_active_at_socket_exception_is_force_released():
    """recv 가 예외를 던져 바깥 except 로 빠지는 경로에서도 놓는다."""
    conn = FakeConn(chunks=[DRAG_START_LINE, ConnectionResetError("client vanished")])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]
    assert controller._drag_active is False


def test_no_extra_leftup_when_drag_ended_normally():
    """DRAG_END 를 받고 끊긴 경우 finally 에서 LEFTUP 이 또 나가면 안 된다."""
    conn = FakeConn(chunks=[DRAG_START_LINE, DRAG_END_LINE, b""])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]


def test_no_drag_means_no_send_input_at_disconnect():
    """드래그를 쓰지 않은 평범한 연결은 종료 시 아무것도 보내지 않는다."""
    conn = FakeConn(chunks=[b'{"type":"CLICK","button":"left"}\n', b""])
    controller = InputController()

    with patch.object(controller, "_click"), \
            patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    mock_send.assert_not_called()


def test_force_release_failure_does_not_break_disconnect_cleanup():
    """강제 해제 중 예외가 나도 세션 회수/소켓 종료는 그대로 진행돼야 한다."""
    conn = FakeConn(chunks=[DRAG_START_LINE, b""])
    controller = InputController()
    registry = server.SessionRegistry()

    with patch_send_input() as mock_send:
        # 1 = LEFTDOWN 성공(주입 1개), 그다음 강제 해제에서 예외
        mock_send.side_effect = [1, OSError("SendInput failed")]
        run_client(conn, controller=controller, registry=registry)

    assert registry.snapshot() == set()
    assert conn.closed


def test_drag_survives_across_reconnect_and_is_released_by_second_connection():
    """InputController 는 프로세스 전역이므로, 끊긴 연결에서 이미 해제됐다면
    다음 연결 종료에서 중복 LEFTUP 이 나가지 않는다."""
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(FakeConn(chunks=[DRAG_START_LINE, b""]), controller=controller)
        assert controller._drag_active is False
        mock_send.reset_mock()
        run_client(FakeConn(chunks=[b""]), controller=controller)

    mock_send.assert_not_called()


# --------------------------------------------------------------------------
# 회귀: 드래그 이벤트가 기존 경로를 건드리지 않는지
# --------------------------------------------------------------------------

def test_drag_events_do_not_reach_udp_path():
    """UDP 는 MOVE 전용 — DRAG_* 패킷이 UDP 로 와도 무시된다."""
    controller = InputController()
    registry = server.SessionRegistry()
    token = registry.issue()

    with patch.object(controller, "handle_event") as mock_handle:
        handled = server.handle_udp_packet(
            json.dumps({"type": "DRAG_START", "session": token}).encode("utf-8"),
            controller,
            registry,
        )

    assert handled is False
    mock_handle.assert_not_called()


def test_other_events_still_work_in_same_connection_as_drag():
    conn = FakeConn(
        chunks=[
            DRAG_START_LINE,
            b'{"type":"HEARTBEAT"}\n',
            b'{"type":"SCROLL","dx":0,"dy":-3}\n',
            DRAG_END_LINE,
            b'{"type":"DOUBLE_CLICK","button":"left"}\n',
        ]
    )
    controller = InputController()

    with patch.object(controller, "_scroll") as mock_scroll, \
            patch.object(controller, "_double_click") as mock_double, \
            patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    mock_scroll.assert_called_once_with(0, -3)
    mock_double.assert_called_once_with("left")
    assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]
    acks = [l for l in conn.sent_lines() if json.loads(l)["type"] == "HEARTBEAT_ACK"]
    assert len(acks) == 1
