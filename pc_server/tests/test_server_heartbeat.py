"""TCP heartbeat (HEARTBEAT / HEARTBEAT_ACK) 처리 테스트.

기존 test_server_udp_session.py 의 FakeConn 패턴을 따른다:
recv() 로 돌려줄 값을 미리 큐에 넣고, socket.timeout 을 넣어 무응답을 시뮬레이션한다.
실제 소켓/SendInput 호출은 전혀 하지 않는다.
"""
import json
import socket
from unittest.mock import patch

import server
from input_controller import InputController

ADDR = ("127.0.0.1", 5555)


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


def make_controller():
    return InputController()


def run_client(conn, controller=None, registry=None):
    controller = controller or make_controller()
    registry = registry or server.SessionRegistry()
    server.handle_client(conn, ADDR, controller, registry)
    return controller, registry


# --------------------------------------------------------------------------
# 상수 / 타임아웃 설정
# --------------------------------------------------------------------------

def test_heartbeat_constants_match_spec():
    assert server.HEARTBEAT_INTERVAL_S == 5.0
    assert server.HEARTBEAT_MISS_LIMIT == 3


def test_handle_client_sets_socket_timeout_after_handshake():
    conn = FakeConn()
    run_client(conn)
    assert conn.timeouts == [server.HEARTBEAT_INTERVAL_S]
    # 타임아웃은 SESSION 줄을 보낸 뒤에 걸려야 한다 (핸드셰이크는 블로킹)
    assert json.loads(conn.sent_lines()[0])["type"] == "SESSION"


def test_handshake_failure_does_not_set_timeout():
    class FailingConn(FakeConn):
        def sendall(self, data):
            raise OSError("broken pipe")

    conn = FailingConn()
    registry = server.SessionRegistry()
    run_client(conn, registry=registry)

    assert conn.timeouts == []
    assert registry.snapshot() == set()
    assert conn.closed


# --------------------------------------------------------------------------
# HEARTBEAT → HEARTBEAT_ACK
# --------------------------------------------------------------------------

def test_heartbeat_gets_ack_and_is_not_passed_to_handle_event():
    conn = FakeConn(chunks=[b'{"type":"HEARTBEAT"}\n'])
    controller = make_controller()

    with patch.object(controller, "handle_event") as mock_handle:
        run_client(conn, controller=controller)

    mock_handle.assert_not_called()
    lines = conn.sent_lines()
    assert len(lines) == 2
    assert json.loads(lines[1]) == {"type": "HEARTBEAT_ACK"}
    # newline-delimited JSON 규약 유지
    assert b"".join(conn.sent).endswith(b"\n")


def test_ack_line_is_exact_wire_format():
    assert server.HEARTBEAT_ACK_LINE == b'{"type":"HEARTBEAT_ACK"}\n'


def test_each_heartbeat_gets_its_own_ack():
    conn = FakeConn(
        chunks=[
            b'{"type":"HEARTBEAT"}\n{"type":"HEARTBEAT"}\n',
            b'{"type":"HEARTBEAT"}\n',
        ]
    )
    controller = make_controller()

    with patch.object(controller, "handle_event") as mock_handle:
        run_client(conn, controller=controller)

    mock_handle.assert_not_called()
    acks = [l for l in conn.sent_lines() if json.loads(l)["type"] == "HEARTBEAT_ACK"]
    assert len(acks) == 3


def test_heartbeat_split_across_chunks_is_acked_once():
    conn = FakeConn(chunks=[b'{"type":"HEART', b'BEAT"}\n'])
    controller = make_controller()

    with patch.object(controller, "handle_event") as mock_handle:
        run_client(conn, controller=controller)

    mock_handle.assert_not_called()
    assert [json.loads(l)["type"] for l in conn.sent_lines()] == ["SESSION", "HEARTBEAT_ACK"]


def test_non_heartbeat_events_still_reach_handle_event():
    conn = FakeConn(
        chunks=[
            b'{"type":"HEARTBEAT"}\n{"type":"CLICK","button":"left"}\n',
        ]
    )
    controller = make_controller()

    with patch.object(controller, "_click") as mock_click:
        run_client(conn, controller=controller)

    mock_click.assert_called_once_with("left")
    assert [json.loads(l)["type"] for l in conn.sent_lines()] == ["SESSION", "HEARTBEAT_ACK"]


def test_invalid_json_still_ignored_and_does_not_produce_ack():
    conn = FakeConn(chunks=[b"garbage\n", b'{"type":"HEARTBEAT"}\n'])
    controller = make_controller()

    with patch.object(controller, "handle_event") as mock_handle:
        run_client(conn, controller=controller)

    mock_handle.assert_not_called()
    assert [json.loads(l)["type"] for l in conn.sent_lines()] == ["SESSION", "HEARTBEAT_ACK"]


def test_non_dict_json_line_is_ignored_not_passed_to_handle_event():
    # F-5: 이전에는 리스트/문자열 JSON이 그대로 handle_event(실제 InputController)로
    # 넘어가 event.get("type")에서 AttributeError를 내며 연결이 통째로 끊겼다.
    # UDP 경로(handle_udp_packet)와 동일하게 dict가 아니면 무시하도록 맞췄다.
    conn = FakeConn(chunks=[
        b"[1,2,3]\n",
        b'"HEARTBEAT"\n',
        b'{"type":"CLICK","button":"left"}\n',
    ])
    controller = make_controller()  # 실제 InputController — mock으로 가리지 않는다

    with patch.object(controller, "_click") as mock_click:
        run_client(conn, controller=controller)

    # 비-dict 줄 2개는 무시되고, 연결이 끊기지 않아 뒤의 CLICK은 정상 처리된다
    mock_click.assert_called_once_with("left")
    assert [json.loads(l)["type"] for l in conn.sent_lines()] == ["SESSION"]


# --------------------------------------------------------------------------
# 미응답 카운트 / 연결 해제
# --------------------------------------------------------------------------

def test_three_consecutive_timeouts_revoke_session_and_close_socket():
    conn = FakeConn(chunks=[timeout(), timeout(), timeout()])
    registry = server.SessionRegistry()

    run_client(conn, registry=registry)

    token = json.loads(conn.sent_lines()[0])["session"]
    assert not registry.is_active(token)
    assert registry.snapshot() == set()
    assert conn.closed
    # 3회째에 즉시 루프를 빠져나간다 (4번째 recv 없음)
    assert conn.recv_calls == 3


def test_two_timeouts_alone_do_not_close_connection():
    """2회 미응답 후 정상 데이터가 오면 연결이 유지되어 이벤트가 처리된다."""
    conn = FakeConn(
        chunks=[timeout(), timeout(), b'{"type":"CLICK","button":"left"}\n']
    )
    controller = make_controller()

    with patch.object(controller, "_click") as mock_click:
        run_client(conn, controller=controller)

    mock_click.assert_called_once_with("left")
    # timeout 3회 + 데이터 1회 + EOF 1회
    assert conn.recv_calls == 4


def test_heartbeat_resets_miss_count():
    """timeout 2회 → HEARTBEAT → timeout 2회 는 한도에 도달하지 않는다."""
    conn = FakeConn(
        chunks=[
            timeout(),
            timeout(),
            b'{"type":"HEARTBEAT"}\n',
            timeout(),
            timeout(),
            b'{"type":"CLICK","button":"left"}\n',
        ]
    )
    controller = make_controller()

    with patch.object(controller, "_click") as mock_click:
        run_client(conn, controller=controller)

    mock_click.assert_called_once_with("left")
    assert conn.recv_calls == 7  # 6개 chunk 전부 소비 + EOF
    acks = [l for l in conn.sent_lines() if json.loads(l)["type"] == "HEARTBEAT_ACK"]
    assert len(acks) == 1


def test_partial_data_without_newline_also_resets_count():
    """완전한 JSON 줄이 아니어도 recv() 가 뭔가 반환하면 카운트가 리셋된다."""
    conn = FakeConn(
        chunks=[
            timeout(),
            timeout(),
            b'{"type":"CLI',  # 개행 없는 부분 수신
            timeout(),
            timeout(),
            b'CK","button":"right"}\n',
        ]
    )
    controller = make_controller()

    with patch.object(controller, "_click") as mock_click:
        run_client(conn, controller=controller)

    mock_click.assert_called_once_with("right")
    assert conn.recv_calls == 7


def test_timeouts_after_reset_still_eventually_disconnect():
    conn = FakeConn(
        chunks=[
            timeout(),
            b'{"type":"HEARTBEAT"}\n',
            timeout(),
            timeout(),
            timeout(),
            b'{"type":"CLICK","button":"left"}\n',  # 도달하지 못해야 한다
        ]
    )
    controller = make_controller()
    registry = server.SessionRegistry()

    with patch.object(controller, "_click") as mock_click:
        run_client(conn, controller=controller, registry=registry)

    mock_click.assert_not_called()
    assert conn.recv_calls == 5
    assert registry.snapshot() == set()
    assert conn.closed


def test_eof_disconnect_still_works_with_timeout_enabled():
    conn = FakeConn(chunks=[b'{"type":"HEARTBEAT"}\n', b""])
    registry = server.SessionRegistry()

    run_client(conn, registry=registry)

    assert registry.snapshot() == set()
    assert conn.closed


def test_ack_send_failure_does_not_leak_session():
    class AckFailingConn(FakeConn):
        def sendall(self, data):
            super().sendall(data)
            if data == server.HEARTBEAT_ACK_LINE:
                raise ConnectionResetError("client vanished")

    conn = AckFailingConn(chunks=[b'{"type":"HEARTBEAT"}\n'])
    registry = server.SessionRegistry()

    run_client(conn, registry=registry)

    assert registry.snapshot() == set()
    assert conn.closed
