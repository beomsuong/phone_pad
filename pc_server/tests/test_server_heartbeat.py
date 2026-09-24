"""TCP heartbeat (HEARTBEAT / HEARTBEAT_ACK) 처리 테스트.

공용 `fake_conn.FakeConn` 을 쓴다: recv() 로 돌려줄 값을 미리 큐에 넣고,
socket.timeout 을 넣어 무응답을 시뮬레이션한다. 실제 소켓/SendInput 호출은
전혀 하지 않는다.

Phase 5(PIN 인증)부터 클라이언트가 AUTH 줄을 먼저 보내야 SESSION 이 오므로
FakeConn 이 그 줄을 자동으로 흘려준다. `recv_calls` / `timeouts` 는 여전히
SESSION 이후(=heartbeat 루프)만 센다 - 아래 단정들의 의미가 보존된다.
"""
import json
import socket
from unittest.mock import patch

import server
from fake_conn import FakeConn
from input_controller import InputController

ADDR = ("127.0.0.1", 5555)


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
    # AUTH 줄을 읽는 동안에는 별개의(더 짧은) 타임아웃이 걸린다
    assert conn.auth_timeouts == [server.AUTH_TIMEOUT_S]
    # heartbeat 타임아웃은 SESSION 줄을 보낸 뒤에 걸려야 한다
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


def test_handle_event_error_does_not_drop_the_connection():
    # F-4: dx/dy가 숫자로 변환되지 않는 값(예: "abc")이면 InputController.handle_event가
    # float()에서 ValueError를 던진다. 예전에는 이게 handle_client의 바깥 except까지
    # 전파되어 finally에서 세션을 통째로 끊었다 — 이벤트 하나 때문에 TCP 연결 전체가
    # 죽으면 안 되므로(UDP 경로는 이미 이렇게 격리돼 있음), 이 이벤트만 무시하고
    # 이후 이벤트는 계속 처리되어야 한다.
    conn = FakeConn(chunks=[
        b'{"type":"MOVE","dx":"abc","dy":0}\n',
        b'{"type":"SCROLL","dx":"abc","dy":0}\n',
        b'{"type":"CLICK","button":"left"}\n',
    ])
    controller = make_controller()  # 실제 InputController

    with patch.object(controller, "_click") as mock_click, \
         patch.object(controller, "_move") as mock_move, \
         patch.object(controller, "_scroll") as mock_scroll:
        run_client(conn, controller=controller)

    mock_move.assert_not_called()
    mock_scroll.assert_not_called()
    mock_click.assert_called_once_with("left")
    assert [json.loads(l)["type"] for l in conn.sent_lines()] == ["SESSION"]


def test_handle_client_processes_android_scroll_wire_literal():
    # O-2: Android 테스트는 문자열만, 서버 테스트는 dict만 고정하고 있어 그 사이(문자열 →
    # json.loads → dict) 연결 고리를 검증하는 테스트가 없었다. TrackpadRepositoryImplTest.kt의
    # "Scroll 이벤트는 정수 스텝을 담아 TCP로 전송된다" 테스트가 고정한 리터럴을 그대로
    # 하드코딩해서 handle_client의 실제 파싱 경로(버퍼 분리 → json.loads → handle_event)에
    # 통과시킨다 — 한쪽이 키 이름/철자를 바꿔도 이 테스트가 잡아낸다.
    android_wire_literal = b'{"type":"SCROLL","dx":0,"dy":-3}\n'
    conn = FakeConn(chunks=[android_wire_literal])
    controller = make_controller()

    with patch.object(controller, "_scroll") as mock_scroll:
        run_client(conn, controller=controller)

    mock_scroll.assert_called_once_with(0, -3)


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
