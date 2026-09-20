import ctypes
from unittest.mock import patch

from send_input_stub import (
    injected_all,
    injected_none,
    injected_partial,
    patch_send_input,
)
from input_controller import (
    INPUT,
    INPUT_FAILURE_LOG_INTERVAL_SEC,
    MOUSEEVENTF_HWHEEL,
    MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP,
    MOUSEEVENTF_MOVE,
    MOUSEEVENTF_RIGHTDOWN,
    MOUSEEVENTF_RIGHTUP,
    MOUSEEVENTF_WHEEL,
    WHEEL_DELTA,
    InputController,
)


def _signed32(value: int) -> int:
    """c_ulong 으로 실린 mouseData 를 다시 signed 로 해석."""
    return value - 0x100000000 if value >= 0x80000000 else value


def _sent_wheel_events(mock_send_input):
    """SendInput 호출 인자에서 (dwFlags, signed mouseData) 목록을 추출."""
    assert mock_send_input.call_count == 1
    count, inputs, size = mock_send_input.call_args.args
    assert size == ctypes.sizeof(INPUT)
    assert count == len(inputs)
    return [
        (inputs[i]._input.mi.dwFlags, _signed32(inputs[i]._input.mi.mouseData))
        for i in range(count)
    ]


def _sent_flags(mock_send_input):
    """SendInput 이 정확히 1회 호출됐는지 확인하고 dwFlags 순서를 추출."""
    assert mock_send_input.call_count == 1, (
        "클릭 계열은 SendInput 한 번에 원자적으로 묶여야 한다"
    )
    count, inputs, size = mock_send_input.call_args.args
    assert size == ctypes.sizeof(INPUT)
    assert count == len(inputs)
    return [inputs[i]._input.mi.dwFlags for i in range(count)]


def test_handle_move_rounds_to_int():
    controller = InputController()
    with patch.object(controller, "_move") as mock_move:
        controller.handle_event({"type": "MOVE", "dx": 2.6, "dy": -1.4})
    mock_move.assert_called_once_with(3, -1)


def test_handle_move_accepts_string_numbers():
    """UDP JSON 은 숫자가 문자열로 올 수도 있으므로 float() 변환이 유지되는지 확인."""
    controller = InputController()
    with patch.object(controller, "_move") as mock_move:
        controller.handle_event({"type": "MOVE", "dx": "3.2", "dy": "0.4"})
    mock_move.assert_called_once_with(3, 0)


def test_handle_move_missing_fields_defaults_to_zero_and_skips_send():
    controller = InputController()
    with patch.object(controller, "_move") as mock_move:
        controller.handle_event({"type": "MOVE"})
    mock_move.assert_not_called()


def test_handle_move_below_one_pixel_is_dropped():
    controller = InputController()
    with patch.object(controller, "_move") as mock_move:
        controller.handle_event({"type": "MOVE", "dx": 0.2, "dy": -0.3})
    mock_move.assert_not_called()


def test_handle_click_defaults_to_left():
    controller = InputController()
    with patch.object(controller, "_click") as mock_click:
        controller.handle_event({"type": "CLICK"})
    mock_click.assert_called_once_with("left")


def test_handle_click_right():
    controller = InputController()
    with patch.object(controller, "_click") as mock_click:
        controller.handle_event({"type": "CLICK", "button": "right"})
    mock_click.assert_called_once_with("right")


def test_handle_unknown_type_does_not_raise():
    controller = InputController()
    with patch.object(controller, "_move") as mock_move, \
            patch.object(controller, "_click") as mock_click, \
            patch.object(controller, "_scroll") as mock_scroll, \
            patch.object(controller, "_double_click") as mock_double_click, \
            patch.object(controller, "_drag_start") as mock_drag_start, \
            patch.object(controller, "_drag_end") as mock_drag_end:
        controller.handle_event({"type": "SESSION", "session": "deadbeef"})
        controller.handle_event({"type": "UNKNOWN"})
        controller.handle_event({})
    mock_move.assert_not_called()
    mock_click.assert_not_called()
    mock_scroll.assert_not_called()
    mock_double_click.assert_not_called()
    mock_drag_start.assert_not_called()
    mock_drag_end.assert_not_called()


# --- DOUBLE_CLICK -----------------------------------------------------------


def test_handle_double_click_defaults_to_left():
    controller = InputController()
    with patch.object(controller, "_double_click") as mock_double_click:
        controller.handle_event({"type": "DOUBLE_CLICK"})
    mock_double_click.assert_called_once_with("left")


def test_handle_double_click_passes_explicit_button():
    """Android 는 항상 button="left" 를 보내지만(AGENTS.md 섹션 4) 필드를 그대로 전달한다."""
    controller = InputController()
    with patch.object(controller, "_double_click") as mock_double_click:
        controller.handle_event({"type": "DOUBLE_CLICK", "button": "left"})
    mock_double_click.assert_called_once_with("left")


def test_handle_double_click_does_not_reuse_click():
    """DOUBLE_CLICK 은 _click 을 두 번 호출하는 방식이 아니어야 한다(SendInput 1회 원칙)."""
    controller = InputController()
    with patch.object(controller, "_click") as mock_click, \
            patch_send_input() as mock_send:
        controller.handle_event({"type": "DOUBLE_CLICK", "button": "left"})
    mock_click.assert_not_called()
    assert mock_send.call_count == 1


def test_double_click_sends_down_up_down_up_in_single_send_input():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._double_click("left")
    assert _sent_flags(mock_send) == [
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
    ]


def test_double_click_right_button_uses_right_flags():
    """이번 범위(1손가락 더블탭)는 left 전용이지만 버튼 분기 재사용성을 고정한다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._double_click("right")
    assert _sent_flags(mock_send) == [
        MOUSEEVENTF_RIGHTDOWN,
        MOUSEEVENTF_RIGHTUP,
        MOUSEEVENTF_RIGHTDOWN,
        MOUSEEVENTF_RIGHTUP,
    ]


def test_double_click_does_not_move_cursor():
    """커서를 움직이는 플래그가 하나도 섞이지 않아야 한다.

    Windows 네이티브 더블클릭 판정은 두 클릭이 좁은 사각형(기본 4px) 안에서
    일어나야 성립한다. MOUSEEVENTF_MOVE 나 mouseData 가 끼면 그 전제가 깨진다.
    """
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._double_click("left")
    count, inputs, _ = mock_send.call_args.args
    for i in range(count):
        mi = inputs[i]._input.mi
        assert mi.dx == 0 and mi.dy == 0
        assert mi.mouseData == 0
        assert mi.dwFlags & MOUSEEVENTF_MOVE == 0


def test_handle_double_click_end_to_end_flags():
    """handle_event → _double_click → SendInput 전 구간 검증."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DOUBLE_CLICK", "button": "left"})
    assert _sent_flags(mock_send) == [
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
    ]


# --- CLICK 회귀 (DOUBLE_CLICK 추가가 단일 클릭을 건드리지 않았는지) -------------


def test_single_click_still_sends_exactly_two_inputs():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._click("left")
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]


def test_single_right_click_still_sends_exactly_two_inputs():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._click("right")
    assert _sent_flags(mock_send) == [MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP]


# --- SCROLL -----------------------------------------------------------------


def test_handle_scroll_passes_integer_steps():
    controller = InputController()
    with patch.object(controller, "_scroll") as mock_scroll:
        controller.handle_event({"type": "SCROLL", "dx": 0, "dy": -3})
    mock_scroll.assert_called_once_with(0, -3)


def test_handle_scroll_horizontal():
    controller = InputController()
    with patch.object(controller, "_scroll") as mock_scroll:
        controller.handle_event({"type": "SCROLL", "dx": 2, "dy": 0})
    mock_scroll.assert_called_once_with(2, 0)


def test_handle_scroll_accepts_float_and_string_numbers():
    """스펙상 정수지만 JSON 파싱 결과가 float/문자열이어도 크래시하지 않아야 한다."""
    controller = InputController()
    with patch.object(controller, "_scroll") as mock_scroll:
        controller.handle_event({"type": "SCROLL", "dx": "1", "dy": -2.0})
    mock_scroll.assert_called_once_with(1, -2)


def test_handle_scroll_zero_does_not_call_scroll():
    controller = InputController()
    with patch.object(controller, "_scroll") as mock_scroll:
        controller.handle_event({"type": "SCROLL", "dx": 0, "dy": 0})
    mock_scroll.assert_not_called()


def test_handle_scroll_missing_fields_defaults_to_zero():
    controller = InputController()
    with patch.object(controller, "_scroll") as mock_scroll:
        controller.handle_event({"type": "SCROLL"})
    mock_scroll.assert_not_called()


def test_scroll_vertical_sends_wheel_with_step_times_wheel_delta():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._scroll(0, -3)
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_WHEEL, -3 * WHEEL_DELTA)]


def test_scroll_vertical_positive_step():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._scroll(0, 1)
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_WHEEL, 120)]


def test_scroll_horizontal_sends_hwheel_only():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._scroll(2, 0)
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_HWHEEL, 2 * WHEEL_DELTA)]


def test_scroll_both_axes_sends_two_inputs():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._scroll(1, -2)
    assert _sent_wheel_events(mock_send) == [
        (MOUSEEVENTF_WHEEL, -2 * WHEEL_DELTA),
        (MOUSEEVENTF_HWHEEL, 1 * WHEEL_DELTA),
    ]


def test_scroll_zero_does_not_call_send_input():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller._scroll(0, 0)
    mock_send.assert_not_called()


def test_handle_scroll_end_to_end_mouse_data():
    """handle_event → _scroll → SendInput 전 구간에서 mouseData 가 스텝×120 인지."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "SCROLL", "dx": 0, "dy": -3})
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_WHEEL, -360)]


# --- DRAG_START / DRAG_END --------------------------------------------------


def test_new_controller_starts_with_drag_inactive():
    controller = InputController()
    assert controller._drag_active is False
    assert controller.drag_active is False


def test_drag_start_sends_single_leftdown_and_activates():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
    # LEFTDOWN 1개짜리 INPUT — UP 이 따라붙으면 안 된다(버튼을 누른 채 유지해야 하므로)
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN]
    assert controller._drag_active is True


def test_drag_start_does_not_move_cursor():
    """드래그 시작이 커서를 건드리면 안 된다 — 이동은 기존 MOVE(UDP)가 담당."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
    count, inputs, _ = mock_send.call_args.args
    assert count == 1
    mi = inputs[0]._input.mi
    assert mi.dx == 0 and mi.dy == 0
    assert mi.mouseData == 0
    assert mi.dwFlags & MOUSEEVENTF_MOVE == 0


def test_repeated_drag_start_is_idempotent():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        controller.handle_event({"type": "DRAG_START"})
        controller.handle_event({"type": "DRAG_START"})
    # 중복 LEFTDOWN 방지: 첫 번째만 나간다
    assert mock_send.call_count == 1
    assert controller._drag_active is True


def test_drag_end_sends_single_leftup_and_deactivates():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.reset_mock()
        controller.handle_event({"type": "DRAG_END"})
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTUP]
    assert controller._drag_active is False


def test_drag_end_without_drag_start_does_nothing():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_END"})  # 크래시 없어야 함
    mock_send.assert_not_called()
    assert controller._drag_active is False


def test_repeated_drag_end_is_idempotent():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.reset_mock()
        controller.handle_event({"type": "DRAG_END"})
        controller.handle_event({"type": "DRAG_END"})
    assert mock_send.call_count == 1
    assert controller._drag_active is False


def test_drag_cycle_can_repeat():
    """놓은 뒤 다시 누를 수 있어야 한다(상태가 한 번 쓰고 끝나면 안 됨)."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        controller.handle_event({"type": "DRAG_END"})
        controller.handle_event({"type": "DRAG_START"})
        assert controller._drag_active is True
        controller.handle_event({"type": "DRAG_END"})
    assert mock_send.call_count == 4
    flags = [call.args[1][0]._input.mi.dwFlags for call in mock_send.call_args_list]
    assert flags == [
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
        MOUSEEVENTF_LEFTDOWN,
        MOUSEEVENTF_LEFTUP,
    ]
    assert controller._drag_active is False


def test_drag_start_ignores_extra_fields():
    """와이어 포맷상 필드가 없지만, 있더라도 무시하고 정상 동작해야 한다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START", "button": "right", "dx": 5})
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN]


def test_move_while_dragging_does_not_change_drag_state():
    """드래그 중 MOVE 는 커서만 움직이고 버튼 상태를 건드리지 않는다."""
    controller = InputController()
    with patch_send_input():
        controller.handle_event({"type": "DRAG_START"})
        with patch.object(controller, "_move") as mock_move:
            controller.handle_event({"type": "MOVE", "dx": 2.6, "dy": -1.4})
        mock_move.assert_called_once_with(3, -1)
        assert controller._drag_active is True


def test_force_release_drag_releases_when_active():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.reset_mock()
        released = controller.force_release_drag()
    assert released is True
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTUP]
    assert controller._drag_active is False


def test_force_release_drag_is_noop_when_inactive():
    controller = InputController()
    with patch_send_input() as mock_send:
        released = controller.force_release_drag()
    assert released is False
    mock_send.assert_not_called()


def test_force_release_after_drag_end_sends_nothing():
    """정상 종료(DRAG_END 수신)한 뒤의 연결 종료에서 LEFTUP 이 또 나가면 안 된다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        controller.handle_event({"type": "DRAG_END"})
        mock_send.reset_mock()
        assert controller.force_release_drag() is False
    mock_send.assert_not_called()


def test_drag_stays_active_if_send_input_fails_on_release():
    """LEFTUP 전송이 실패하면 상태를 활성으로 남겨 안전장치가 재시도할 수 있어야 한다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.side_effect = OSError("SendInput failed")
        try:
            controller.handle_event({"type": "DRAG_END"})
        except OSError:
            pass
        assert controller._drag_active is True
        # 재시도는 성공
        mock_send.side_effect = injected_all
        assert controller.force_release_drag() is True
    assert controller._drag_active is False


# --- SendInput 반환값 검사 (주입 실패 감지) ----------------------------------
#
# 실제 SendInput 은 실패 시 예외가 아니라 "주입한 이벤트 수"를 요청보다 적게
# 돌려준다(입력 데스크톱 접근 불가: 잠금 화면 / UAC 보안 데스크톱 등).


class FakeClock:
    """주입 가능한 monotonic 시간 소스 - 실제 sleep 없이 rate limit 을 검증한다."""

    def __init__(self, now=0.0):
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float):
        self.now += seconds


def make_controller(clock=None, interval=INPUT_FAILURE_LOG_INTERVAL_SEC):
    """(controller, clock, logs) - 로그는 리스트에 모으고 시간은 수동으로 흐른다."""
    clock = FakeClock() if clock is None else clock
    logs = []
    controller = InputController(
        monotonic=clock, log=logs.append, failure_log_interval=interval
    )
    return controller, clock, logs


def test_new_controller_has_no_input_failures():
    controller, _, _ = make_controller()
    assert controller.input_failures == 0


def test_successful_events_do_not_count_as_failures():
    controller, _, logs = make_controller()
    with patch_send_input():
        controller.handle_event({"type": "MOVE", "dx": 3, "dy": 4})
        controller.handle_event({"type": "CLICK", "button": "left"})
        controller.handle_event({"type": "DOUBLE_CLICK", "button": "left"})
        controller.handle_event({"type": "SCROLL", "dx": 0, "dy": -3})
        controller.handle_event({"type": "DRAG_START"})
        controller.handle_event({"type": "DRAG_END"})
    assert controller.input_failures == 0
    assert logs == []


def test_move_injection_failure_is_counted_without_raising():
    controller, _, logs = make_controller()
    with patch_send_input(injected_none):
        controller.handle_event({"type": "MOVE", "dx": 3, "dy": 4})  # 예외 없어야 함
    assert controller.input_failures == 1
    assert len(logs) == 1


def test_click_partial_injection_is_a_failure():
    """CLICK 은 down+up 2개를 요청한다 - 1개만 들어갔으면 실패다."""
    controller, _, _ = make_controller()
    with patch_send_input(injected_partial(1)) as mock_send:
        controller.handle_event({"type": "CLICK", "button": "left"})
    assert mock_send.call_args.args[0] == 2  # 요청 개수는 그대로
    assert controller.input_failures == 1


def test_scroll_and_double_click_failures_are_counted():
    controller, _, _ = make_controller()
    with patch_send_input(injected_none):
        controller.handle_event({"type": "SCROLL", "dx": 0, "dy": -3})
        controller.handle_event({"type": "DOUBLE_CLICK", "button": "left"})
    assert controller.input_failures == 2


def test_failure_counter_accumulates_across_kinds():
    controller, clock, _ = make_controller()
    with patch_send_input(injected_none):
        for _ in range(3):
            controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})
        controller.handle_event({"type": "CLICK"})
    assert controller.input_failures == 4
    assert clock.now == 0.0  # 시간 소스는 테스트가 통제한다


def test_failure_counter_is_thread_safe():
    """TCP 클라이언트 스레드가 여럿일 수 있으므로 카운터 증가가 유실되면 안 된다."""
    import threading

    controller, _, _ = make_controller()
    barrier = threading.Barrier(4)

    def hammer():
        barrier.wait()
        for _ in range(50):
            controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})

    with patch_send_input(injected_none):
        threads = [threading.Thread(target=hammer) for _ in range(4)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
    assert controller.input_failures == 200


# --- 드래그 상태가 주입 실패와 어긋나지 않는지 --------------------------------


def test_drag_start_failure_keeps_drag_inactive():
    """LEFTDOWN 이 주입되지 않았으면 눌린 적이 없으므로 상태를 바꾸면 안 된다."""
    controller, _, _ = make_controller()
    with patch_send_input(injected_none):
        controller.handle_event({"type": "DRAG_START"})
    assert controller._drag_active is False
    assert controller.drag_active is False
    assert controller.input_failures == 1


def test_drag_start_failure_returns_false():
    controller, _, _ = make_controller()
    with patch_send_input(injected_none):
        assert controller._drag_start() is False


def test_drag_end_after_failed_drag_start_sends_nothing():
    """눌린 적 없는 버튼을 놓으려 하면 안 된다(유령 LEFTUP 방지)."""
    controller, _, _ = make_controller()
    with patch_send_input(injected_none) as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.reset_mock()
        controller.handle_event({"type": "DRAG_END"})
    mock_send.assert_not_called()


def test_drag_start_can_succeed_after_a_failed_attempt():
    """실패로 상태가 잠기면 안 된다 - 다음 시도는 정상적으로 눌려야 한다."""
    controller, _, _ = make_controller()
    with patch_send_input(injected_none) as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.side_effect = injected_all
        mock_send.reset_mock()
        controller.handle_event({"type": "DRAG_START"})
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN]
    assert controller._drag_active is True


def test_drag_end_failure_keeps_drag_active_for_the_safety_net():
    """LEFTUP 이 주입되지 않았으면 버튼은 아직 눌려 있다 - 상태를 True 로 남긴다."""
    controller, _, _ = make_controller()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.side_effect = injected_none
        assert controller._drag_end() is False
        assert controller._drag_active is True
        assert controller.input_failures == 1

        # 연결 종료 안전장치가 나중에 재시도해서 성공하면 그때 내려간다.
        mock_send.side_effect = injected_all
        mock_send.reset_mock()
        assert controller.force_release_drag() is True
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTUP]
    assert controller._drag_active is False


def test_force_release_failure_also_keeps_state_for_a_later_retry():
    controller, _, _ = make_controller()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.side_effect = injected_none
        assert controller.force_release_drag() is False
        assert controller.force_release_drag() is False
        assert controller._drag_active is True
    assert controller.input_failures == 2


# --- 실패 로그: rate limit + ASCII ------------------------------------------


def test_repeated_failures_of_same_kind_log_once_per_window():
    controller, clock, logs = make_controller(interval=5.0)
    with patch_send_input(injected_none):
        for _ in range(20):
            clock.advance(0.1)  # 창(5초) 안에서 계속 실패
            controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})
    assert len(logs) == 1
    assert controller.input_failures == 20  # 카운터는 전부 센다


def test_failure_logs_again_after_the_window_elapses():
    controller, clock, logs = make_controller(interval=5.0)
    with patch_send_input(injected_none):
        controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})
        clock.advance(4.9)
        controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})
        assert len(logs) == 1  # 아직 같은 창
        clock.advance(0.1)  # 정확히 경계 = 다시 로그
        controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})
    assert len(logs) == 2


def test_rate_limit_buckets_are_per_kind():
    """MOVE 가 로그를 차지했다고 해서 드래그 실패가 조용히 묻히면 안 된다."""
    controller, clock, logs = make_controller(interval=5.0)
    with patch_send_input(injected_none):
        controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})
        controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})
        controller.handle_event({"type": "DRAG_START"})
        controller.handle_event({"type": "CLICK"})
    kinds = [line.split()[2] for line in logs]  # "[!] SendInput <kind>: ..."
    assert kinds == ["MOVE:", "DRAG_START:", "CLICK:"]
    assert clock.now == 0.0


def test_failure_log_is_ascii_only_and_names_counts():
    """cp949 콘솔에서 UnicodeEncodeError 가 나면 안 된다 (AGENTS.md 섹션 9)."""
    controller, _, logs = make_controller()
    with patch_send_input(injected_partial(1)):
        controller.handle_event({"type": "DOUBLE_CLICK"})
    line = logs[0]
    line.encode("ascii")  # 비ASCII 가 섞이면 여기서 UnicodeEncodeError
    assert "DOUBLE_CLICK" in line
    assert "injected 1 of 4" in line
    assert "input desktop blocked" in line
    assert "failures 1" in line


def test_logging_failure_does_not_break_input_handling():
    """로그 스트림이 죽어도(예: 닫힌 파일) 이벤트 처리는 계속돼야 한다."""
    def broken_log(_message):
        raise OSError("log stream closed")

    controller = InputController(monotonic=FakeClock(), log=broken_log)
    with patch_send_input(injected_none):
        controller.handle_event({"type": "MOVE", "dx": 1, "dy": 1})  # 예외 없어야 함
    assert controller.input_failures == 1


def test_default_controller_uses_real_clock_and_print():
    """기본 인자(서버가 쓰는 경로)가 살아 있는지 - 주입은 테스트 전용이다."""
    import time as _time

    controller = InputController()
    assert controller._monotonic is _time.monotonic
    assert controller._log is print
    assert controller._failure_log_interval == INPUT_FAILURE_LOG_INTERVAL_SEC
