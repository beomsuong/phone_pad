"""DESKTOP_SWITCH (가상 데스크톱 전환) 서버 측 처리 테스트.

와이어: `{"type":"DESKTOP_SWITCH","direction":"left"}` (TCP 9000, session 필드 없음).
`direction` 은 "전환 결과의 방향"이며 서버는 그 매핑을 모른다 - 받은 값을
Ctrl+Win+Left / Ctrl+Win+Right 로 바꿀 뿐이다.

**실제 키 주입은 절대 하지 않는다** - 전부 `patch_send_input()` 으로 모킹한다.
진짜로 나가면 이 테스트를 돌리는 데스크톱이 전환되거나, 부분 주입 시 Win 키가
눌린 채 남는다(= 이 기능의 최악 시나리오를 테스트가 직접 일으키는 셈).
"""
import ctypes
import json
import pytest
from unittest.mock import patch

import server
from send_input_stub import (
    injected_none,
    injected_partial,
    patch_send_input,
)
from input_controller import (
    INPUT,
    INPUT_KEYBOARD,
    INPUT_MOUSE,
    KEYBDINPUT,
    KEYEVENTF_EXTENDEDKEY,
    KEYEVENTF_KEYUP,
    MOUSEINPUT,
    MOUSEEVENTF_LEFTDOWN,
    VK_LCONTROL,
    VK_LEFT,
    VK_LWIN,
    VK_RIGHT,
    InputController,
    _INPUTunion,
)

LEFT_LINE = b'{"type":"DESKTOP_SWITCH","direction":"left"}\n'
RIGHT_LINE = b'{"type":"DESKTOP_SWITCH","direction":"right"}\n'


# --------------------------------------------------------------------------
# 헬퍼
# --------------------------------------------------------------------------

def _key_events(mock_send_input, call_index=0):
    """SendInput 호출 인자에서 (type, wVk, dwFlags) 목록을 순서대로 추출."""
    count, inputs, size = mock_send_input.call_args_list[call_index].args
    assert size == ctypes.sizeof(INPUT), "SendInput 의 cbSize 는 항상 sizeof(INPUT)"
    assert count == len(inputs)
    return [
        (inputs[i].type, inputs[i]._input.ki.wVk, inputs[i]._input.ki.dwFlags)
        for i in range(count)
    ]


def expected_sequence(vk_arrow):
    """Ctrl down -> Win down -> Arrow down -> Arrow up -> Win up -> Ctrl up."""
    return [
        (INPUT_KEYBOARD, VK_LCONTROL, 0),
        (INPUT_KEYBOARD, VK_LWIN, 0),
        (INPUT_KEYBOARD, vk_arrow, KEYEVENTF_EXTENDEDKEY),
        (INPUT_KEYBOARD, vk_arrow, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP),
        (INPUT_KEYBOARD, VK_LWIN, KEYEVENTF_KEYUP),
        (INPUT_KEYBOARD, VK_LCONTROL, KEYEVENTF_KEYUP),
    ]


# --------------------------------------------------------------------------
# INPUT 구조체 크기 불변 (키보드 지원 추가의 최대 위험)
# --------------------------------------------------------------------------

def test_input_struct_size_is_unchanged_by_keyboard_support():
    """`sizeof(INPUT)` 이 변하면 마우스까지 포함해 **모든** SendInput 이 실패한다.

    SendInput 의 세 번째 인자(cbSize)가 OS 가 아는 INPUT 크기와 다르면 Windows 는
    구조체 배열을 읽지 못하고 0을 반환한다. KEYBDINPUT 을 union 에 넣어도 크기가
    변하지 않아야 한다(x64 기준 40).
    """
    assert ctypes.sizeof(INPUT) == 40


def test_keyboard_input_fits_inside_the_existing_union():
    """union 크기는 가장 큰 멤버(MOUSEINPUT)가 정한다 - KEYBDINPUT 이 더 작다."""
    assert ctypes.sizeof(KEYBDINPUT) <= ctypes.sizeof(MOUSEINPUT)
    assert ctypes.sizeof(_INPUTunion) == ctypes.sizeof(MOUSEINPUT)


def test_mouse_events_still_use_the_same_struct_size():
    """회귀: 키보드 지원 추가 후에도 마우스 경로의 cbSize 가 그대로인지."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "CLICK", "button": "left"})

    _, inputs, size = mock_send.call_args.args
    assert size == ctypes.sizeof(INPUT)
    assert inputs[0].type == INPUT_MOUSE
    assert inputs[0]._input.mi.dwFlags == MOUSEEVENTF_LEFTDOWN


# --------------------------------------------------------------------------
# handle_event 라우팅
# --------------------------------------------------------------------------

def test_handle_event_routes_left_to_desktop_switch():
    controller = InputController()
    with patch.object(controller, "_desktop_switch") as mock_switch:
        controller.handle_event({"type": "DESKTOP_SWITCH", "direction": "left"})

    mock_switch.assert_called_once_with("left")


def test_handle_event_routes_right_to_desktop_switch():
    controller = InputController()
    with patch.object(controller, "_desktop_switch") as mock_switch:
        controller.handle_event({"type": "DESKTOP_SWITCH", "direction": "right"})

    mock_switch.assert_called_once_with("right")


def test_handle_event_passes_direction_verbatim_without_translating():
    """서버는 손가락 방향 -> 와이어 방향 매핑을 **모른다**(Android 한 곳에만 있다).

    받은 문자열을 뒤집거나 정규화하지 않고 그대로 넘겨야 한다 - 두 사이드가 같이
    뒤집으면 원위치된다(AGENTS.md 섹션 10 "스크롤 방향 규약"과 같은 원칙).
    """
    controller = InputController()
    with patch.object(controller, "_desktop_switch") as mock_switch:
        controller.handle_event({"type": "DESKTOP_SWITCH", "direction": "LEFT"})

    mock_switch.assert_called_once_with("LEFT")


def test_handle_event_with_missing_direction_passes_none():
    controller = InputController()
    with patch.object(controller, "_desktop_switch") as mock_switch:
        controller.handle_event({"type": "DESKTOP_SWITCH"})

    mock_switch.assert_called_once_with(None)


# --------------------------------------------------------------------------
# 키 시퀀스 (정상 경로)
# --------------------------------------------------------------------------

def test_left_sends_ctrl_win_left_in_one_send_input():
    controller = InputController()
    with patch_send_input() as mock_send:
        assert controller._desktop_switch("left") is True

    assert mock_send.call_count == 1, "6개 INPUT 은 원자적으로 한 번에 나가야 한다"
    count, _, _ = mock_send.call_args.args
    assert count == 6
    assert _key_events(mock_send) == expected_sequence(VK_LEFT)


def test_right_sends_ctrl_win_right_in_one_send_input():
    controller = InputController()
    with patch_send_input() as mock_send:
        assert controller._desktop_switch("right") is True

    assert mock_send.call_count == 1
    assert _key_events(mock_send) == expected_sequence(VK_RIGHT)


def test_only_the_arrow_key_is_flagged_extended():
    """화살표는 확장 키(EXTENDEDKEY 필수), Ctrl/Win 에는 붙이면 안 된다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._desktop_switch("left")

    events = _key_events(mock_send)
    extended = [vk for _, vk, flags in events if flags & KEYEVENTF_EXTENDEDKEY]
    assert extended == [VK_LEFT, VK_LEFT]
    for _, vk, flags in events:
        if vk in (VK_LCONTROL, VK_LWIN):
            assert not flags & KEYEVENTF_EXTENDEDKEY


def test_every_pressed_key_is_released_within_the_sequence():
    """성공 경로 자체가 수정 키를 남기지 않는지 - down 개수 == up 개수, 키별로도."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._desktop_switch("right")

    downs, ups = {}, {}
    for _, vk, flags in _key_events(mock_send):
        bucket = ups if flags & KEYEVENTF_KEYUP else downs
        bucket[vk] = bucket.get(vk, 0) + 1
    assert downs == ups == {VK_LCONTROL: 1, VK_LWIN: 1, VK_RIGHT: 1}


def test_all_inputs_are_keyboard_type():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._desktop_switch("left")

    assert all(t == INPUT_KEYBOARD for t, _, _ in _key_events(mock_send))


def test_success_path_does_not_send_cleanup():
    """성공했으면 정리 호출을 하지 않는다(= SendInput 총 1회)."""
    controller = InputController()
    with patch_send_input() as mock_send:
        assert controller._desktop_switch("left") is True

    assert mock_send.call_count == 1
    assert controller.input_failures == 0


def test_end_to_end_through_handle_event():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DESKTOP_SWITCH", "direction": "right"})

    assert _key_events(mock_send) == expected_sequence(VK_RIGHT)


# --------------------------------------------------------------------------
# 잘못된 direction: 아무 키도 보내지 않고 조용히 무시
# --------------------------------------------------------------------------

BAD_DIRECTIONS = [
    None,
    "",
    "LEFT",      # 대문자 - 소문자 정확 일치만 허용
    "Right",
    " left",     # 공백 trim 하지 않는다
    "left ",
    "up",
    "down",
    0,
    1,
    -1,
    1.5,
    True,        # bool 은 str 이 아니다
    [],
    ["left"],
    {},
    {"direction": "left"},
    object(),
]


def test_bad_directions_send_nothing_and_return_false():
    for bad in BAD_DIRECTIONS:
        controller = InputController()
        with patch_send_input() as mock_send:
            assert controller._desktop_switch(bad) is False, bad
        mock_send.assert_not_called()
        assert controller.input_failures == 0, bad


def test_bad_direction_events_do_not_raise_from_handle_event():
    """이벤트 하나가 TCP 세션을 끊으면 안 된다 - 예외 전파 금지."""
    controller = InputController()
    with patch_send_input() as mock_send:
        for bad in BAD_DIRECTIONS:
            controller.handle_event({"type": "DESKTOP_SWITCH", "direction": bad})
        controller.handle_event({"type": "DESKTOP_SWITCH"})  # 필드 누락

    mock_send.assert_not_called()


def test_extra_fields_are_ignored():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event(
            {"type": "DESKTOP_SWITCH", "direction": "left", "session": "x", "dx": 3}
        )

    assert _key_events(mock_send) == expected_sequence(VK_LEFT)


# --------------------------------------------------------------------------
# 수정 키 고착 방지 (부분/전체 주입 실패 시 정리)
# --------------------------------------------------------------------------

def test_total_injection_failure_sends_cleanup_key_ups():
    controller = InputController()
    with patch_send_input(injected_none) as mock_send:
        assert controller._desktop_switch("left") is False

    assert mock_send.call_count == 2, "본 시퀀스 1회 + 정리 1회"
    count, _, _ = mock_send.call_args_list[1].args
    assert count == 3
    assert _key_events(mock_send, call_index=1) == [
        (INPUT_KEYBOARD, VK_LEFT, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP),
        (INPUT_KEYBOARD, VK_LWIN, KEYEVENTF_KEYUP),
        (INPUT_KEYBOARD, VK_LCONTROL, KEYEVENTF_KEYUP),
    ]


def test_cleanup_releases_the_right_arrow_for_right_direction():
    controller = InputController()
    with patch_send_input(injected_none) as mock_send:
        controller._desktop_switch("right")

    assert _key_events(mock_send, call_index=1)[0] == (
        INPUT_KEYBOARD,
        VK_RIGHT,
        KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP,
    )


def test_cleanup_only_sends_key_ups():
    """정리 시퀀스에 key down 이 하나라도 섞이면 고착을 오히려 만든다."""
    controller = InputController()
    with patch_send_input(injected_none) as mock_send:
        controller._desktop_switch("left")

    for _, _, flags in _key_events(mock_send, call_index=1):
        assert flags & KEYEVENTF_KEYUP


def test_partial_injection_is_treated_as_failure_and_cleaned_up():
    """3개만 주입 = Ctrl/Win 이 눌린 채 남은 최악 상태 -> 반드시 정리."""
    controller = InputController()
    with patch_send_input(injected_partial(3)) as mock_send:
        assert controller._desktop_switch("left") is False

    assert mock_send.call_count == 2
    assert _key_events(mock_send, call_index=1)[-1] == (
        INPUT_KEYBOARD,
        VK_LCONTROL,
        KEYEVENTF_KEYUP,
    )


def test_partial_injection_of_five_is_still_a_failure():
    """5/6 = 마지막 Ctrl up 이 빠진 상태. '거의 성공'도 성공이 아니다."""
    controller = InputController()
    with patch_send_input(injected_partial(5)) as mock_send:
        assert controller._desktop_switch("right") is False

    assert mock_send.call_count == 2


def test_injection_failure_is_counted():
    controller = InputController()
    with patch_send_input(injected_none):
        controller._desktop_switch("left")

    # 본 시퀀스 + 정리 호출 둘 다 실패로 집계된다(정리도 `_send_input` 창구를 지난다).
    assert controller.input_failures == 2


def test_cleanup_is_not_retried_recursively():
    """정리 호출이 또 실패해도 재귀/재시도로 번지지 않는다(총 2회에서 멈춤)."""
    controller = InputController()
    with patch_send_input(injected_none) as mock_send:
        controller._desktop_switch("left")

    assert mock_send.call_count == 2


def test_cleanup_exception_is_swallowed():
    """정리 호출 자체가 예외를 던져도 원래 실패 처리(False)를 흔들지 않는다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        mock_send.side_effect = [0, OSError("SendInput exploded")]
        assert controller._desktop_switch("left") is False

    assert mock_send.call_count == 2


def test_cleanup_exception_does_not_escape_handle_event():
    controller = InputController()
    with patch_send_input() as mock_send:
        mock_send.side_effect = [0, RuntimeError("boom")]
        controller.handle_event({"type": "DESKTOP_SWITCH", "direction": "right"})

    assert mock_send.call_count == 2


def test_cleanup_runs_when_send_input_itself_raises():
    """QA F-1: SendInput 이 예외를 던져도(부분 주입 후일 수 있다) 정리는 건너뛰지 않는다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        mock_send.side_effect = [OSError("SendInput exploded"), 3]
        with pytest.raises(OSError):
            controller._desktop_switch("left")

    assert mock_send.call_count == 2, "본 시퀀스 예외 뒤에도 정리 1회가 나가야 한다"
    assert _key_events(mock_send, call_index=1)[-1] == (
        INPUT_KEYBOARD,
        VK_LCONTROL,
        KEYEVENTF_KEYUP,
    )


def test_cleanup_runs_when_failure_bookkeeping_raises_after_partial_injection():
    """QA F-1 경로 B: 3개가 실제로 주입된 뒤 실패 기록(_monotonic)이 터져도 정리는 나간다."""
    def broken_clock():
        raise RuntimeError("clock exploded")

    controller = InputController(monotonic=broken_clock)
    with patch_send_input(injected_partial(3)) as mock_send:
        with pytest.raises(RuntimeError):
            controller._desktop_switch("right")

    assert mock_send.call_count == 2
    assert _key_events(mock_send, call_index=1)[0] == (
        INPUT_KEYBOARD,
        VK_RIGHT,
        KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP,
    )


def test_failure_log_is_ascii_only():
    """cp949 콘솔에서 UnicodeEncodeError 가 나면 안 된다(AGENTS.md 섹션 9)."""
    lines = []
    controller = InputController(log=lines.append)
    with patch_send_input(injected_none):
        controller._desktop_switch("left")

    assert lines, "실패는 로그를 남겨야 한다"
    for line in lines:
        line.encode("ascii")  # 비ASCII 가 있으면 여기서 터진다
    assert any("DESKTOP_SWITCH" in line for line in lines)


def test_switch_can_succeed_after_a_failed_attempt():
    """실패가 상태를 오염시키지 않는다(전환은 상태 없는 동작)."""
    controller = InputController()
    with patch_send_input(injected_none):
        assert controller._desktop_switch("left") is False
    with patch_send_input() as mock_send:
        assert controller._desktop_switch("left") is True

    assert mock_send.call_count == 1


# --------------------------------------------------------------------------
# 드래그 상태 불변
# --------------------------------------------------------------------------

def test_desktop_switch_does_not_touch_drag_state_when_idle():
    controller = InputController()
    with patch_send_input():
        controller.handle_event({"type": "DESKTOP_SWITCH", "direction": "left"})

    assert controller.drag_active is False


def test_desktop_switch_during_active_drag_is_not_rejected_and_keeps_state():
    """Android 가 3번째 손가락에서 이미 DRAG_END 를 보내므로 정상 흐름엔 안 겹친다.

    그래도 서버는 거부하지 않고 키를 보내되, 드래그 상태는 건드리지 않는다.
    """
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        assert controller.drag_active is True
        mock_send.reset_mock()

        assert controller._desktop_switch("right") is True
        assert controller.drag_active is True

        assert _key_events(mock_send) == expected_sequence(VK_RIGHT)

        controller.handle_event({"type": "DRAG_END"})
        assert controller.drag_active is False


def test_failed_desktop_switch_during_drag_keeps_drag_active():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.side_effect = injected_none
        controller.handle_event({"type": "DESKTOP_SWITCH", "direction": "left"})

    assert controller.drag_active is True


# --------------------------------------------------------------------------
# TCP end-to-end (server.py 무변경 - 기존 handle_event 위임 경로를 그대로 탄다)
# --------------------------------------------------------------------------

class FakeConn:
    """socket.socket 대역 (test_server_drag.py 와 동일 패턴)."""

    def __init__(self, chunks=None):
        self._chunks = list(chunks or [])
        self.sent = []
        self.closed = False

    def settimeout(self, value):
        pass

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, _size):
        if self._chunks:
            chunk = self._chunks.pop(0)
            if isinstance(chunk, BaseException):
                raise chunk
            return chunk
        return b""

    def close(self):
        self.closed = True

    def sent_lines(self):
        joined = b"".join(self.sent).decode("utf-8")
        return [line for line in joined.split("\n") if line]


def run_client(conn, controller=None, registry=None):
    controller = controller or InputController()
    registry = registry or server.SessionRegistry()
    server.handle_client(conn, ("127.0.0.1", 5555), controller, registry)
    return controller, registry


def test_wire_line_reaches_desktop_switch_over_tcp():
    """와이어 리터럴이 server.py 변경 없이 handle_event 까지 도달하는지."""
    conn = FakeConn(chunks=[LEFT_LINE])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert _key_events(mock_send) == expected_sequence(VK_LEFT)


def test_both_directions_over_tcp_in_one_connection():
    conn = FakeConn(chunks=[LEFT_LINE, RIGHT_LINE])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert mock_send.call_count == 2
    assert _key_events(mock_send, 0) == expected_sequence(VK_LEFT)
    assert _key_events(mock_send, 1) == expected_sequence(VK_RIGHT)


def test_desktop_switch_line_split_across_chunks_is_parsed():
    conn = FakeConn(chunks=[b'{"type":"DESKTOP_SWI', b'TCH","direction":"right"}\n'])
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert _key_events(mock_send) == expected_sequence(VK_RIGHT)


def test_desktop_switch_produces_no_downstream_traffic():
    """서버의 하향 트래픽은 SESSION 과 HEARTBEAT_ACK 뿐이다."""
    conn = FakeConn(chunks=[LEFT_LINE, RIGHT_LINE])

    with patch_send_input():
        run_client(conn)

    assert [json.loads(l)["type"] for l in conn.sent_lines()] == ["SESSION"]


def test_bad_direction_over_tcp_keeps_session_alive():
    """잘못된 direction 한 줄이 세션을 끊으면 안 된다 - 뒤 이벤트가 그대로 처리된다."""
    conn = FakeConn(
        chunks=[
            b'{"type":"DESKTOP_SWITCH","direction":"LEFT"}\n',
            b'{"type":"DESKTOP_SWITCH","direction":42}\n',
            b'{"type":"DESKTOP_SWITCH"}\n',
            RIGHT_LINE,
        ]
    )
    controller = InputController()

    with patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    assert mock_send.call_count == 1, "유효한 마지막 한 줄만 주입된다"
    assert _key_events(mock_send) == expected_sequence(VK_RIGHT)


def test_desktop_switch_does_not_reach_udp_path():
    """UDP 는 MOVE 전용 - DESKTOP_SWITCH 패킷이 와도 무시된다."""
    controller = InputController()
    registry = server.SessionRegistry()
    token = registry.issue()

    with patch.object(controller, "handle_event") as mock_handle:
        handled = server.handle_udp_packet(
            json.dumps(
                {"type": "DESKTOP_SWITCH", "direction": "left", "session": token}
            ).encode("utf-8"),
            controller,
            registry,
        )

    assert handled is False
    mock_handle.assert_not_called()


def test_drag_safety_net_still_runs_after_a_desktop_switch():
    """회귀: 새 이벤트가 연결 종료 시 드래그 강제 해제 경로를 망가뜨리지 않는다."""
    conn = FakeConn(chunks=[b'{"type":"DRAG_START"}\n', LEFT_LINE, b""])
    controller = InputController()

    with patch_send_input():
        run_client(conn, controller=controller)

    assert controller.drag_active is False
    assert conn.closed


def test_other_events_still_work_alongside_desktop_switch():
    conn = FakeConn(
        chunks=[
            LEFT_LINE,
            b'{"type":"HEARTBEAT"}\n',
            b'{"type":"CLICK","button":"left"}\n',
            b'{"type":"SCROLL","dx":0,"dy":-3}\n',
        ]
    )
    controller = InputController()

    with patch.object(controller, "_click") as mock_click, \
            patch.object(controller, "_scroll") as mock_scroll, \
            patch_send_input() as mock_send:
        run_client(conn, controller=controller)

    mock_click.assert_called_once_with("left")
    mock_scroll.assert_called_once_with(0, -3)
    assert _key_events(mock_send) == expected_sequence(VK_LEFT)
    acks = [l for l in conn.sent_lines() if json.loads(l)["type"] == "HEARTBEAT_ACK"]
    assert len(acks) == 1
