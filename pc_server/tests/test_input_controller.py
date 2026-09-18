import ctypes
from unittest.mock import patch

from input_controller import (
    INPUT,
    MOUSEEVENTF_HWHEEL,
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
            patch.object(controller, "_scroll") as mock_scroll:
        controller.handle_event({"type": "SESSION", "session": "deadbeef"})
        controller.handle_event({"type": "UNKNOWN"})
        controller.handle_event({})
    mock_move.assert_not_called()
    mock_click.assert_not_called()
    mock_scroll.assert_not_called()


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
