import ctypes
from unittest.mock import patch

from input_controller import (
    INPUT,
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
            patch.object(controller, "_double_click") as mock_double_click:
        controller.handle_event({"type": "SESSION", "session": "deadbeef"})
        controller.handle_event({"type": "UNKNOWN"})
        controller.handle_event({})
    mock_move.assert_not_called()
    mock_click.assert_not_called()
    mock_scroll.assert_not_called()
    mock_double_click.assert_not_called()


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
            patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller.handle_event({"type": "DOUBLE_CLICK", "button": "left"})
    mock_click.assert_not_called()
    assert mock_send.call_count == 1


def test_double_click_sends_down_up_down_up_in_single_send_input():
    controller = InputController()
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
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
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
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
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
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
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
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
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller._click("left")
    assert _sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]


def test_single_right_click_still_sends_exactly_two_inputs():
    controller = InputController()
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
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
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller._scroll(0, -3)
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_WHEEL, -3 * WHEEL_DELTA)]


def test_scroll_vertical_positive_step():
    controller = InputController()
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller._scroll(0, 1)
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_WHEEL, 120)]


def test_scroll_horizontal_sends_hwheel_only():
    controller = InputController()
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller._scroll(2, 0)
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_HWHEEL, 2 * WHEEL_DELTA)]


def test_scroll_both_axes_sends_two_inputs():
    controller = InputController()
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller._scroll(1, -2)
    assert _sent_wheel_events(mock_send) == [
        (MOUSEEVENTF_WHEEL, -2 * WHEEL_DELTA),
        (MOUSEEVENTF_HWHEEL, 1 * WHEEL_DELTA),
    ]


def test_scroll_zero_does_not_call_send_input():
    controller = InputController()
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller._scroll(0, 0)
    mock_send.assert_not_called()


def test_handle_scroll_end_to_end_mouse_data():
    """handle_event → _scroll → SendInput 전 구간에서 mouseData 가 스텝×120 인지."""
    controller = InputController()
    with patch("input_controller.ctypes.windll.user32.SendInput") as mock_send:
        controller.handle_event({"type": "SCROLL", "dx": 0, "dy": -3})
    assert _sent_wheel_events(mock_send) == [(MOUSEEVENTF_WHEEL, -360)]
