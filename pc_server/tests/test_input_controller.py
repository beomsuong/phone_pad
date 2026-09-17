from unittest.mock import patch

from input_controller import InputController


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
            patch.object(controller, "_click") as mock_click:
        controller.handle_event({"type": "SESSION", "session": "deadbeef"})
        controller.handle_event({"type": "UNKNOWN"})
        controller.handle_event({})
    mock_move.assert_not_called()
    mock_click.assert_not_called()
