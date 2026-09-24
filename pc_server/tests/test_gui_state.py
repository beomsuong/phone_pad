"""GUI 창의 순수 로직(gui_state.py) 테스트 - tkinter / 디스플레이 비의존.

이 파일은 `tkinter` 를 import 하지 않는다. 창 문구가 tray 와 어긋나지 않는지,
정지 상태가 실행 상태와 확실히 구분되는지를 고정한다.
"""
import gui_state
import tray_status


# --------------------------------------------------------------------------
# 1. 토글 버튼
# --------------------------------------------------------------------------

def test_toggle_says_stop_while_running():
    assert gui_state.toggle_text(True) == "정지"


def test_toggle_says_start_while_stopped():
    assert gui_state.toggle_text(False) == "시작"


def test_toggle_accepts_truthy_values():
    assert gui_state.toggle_text(1) == gui_state.TOGGLE_STOP_TEXT
    assert gui_state.toggle_text(None) == gui_state.TOGGLE_START_TEXT


def test_exit_button_reuses_the_tray_wording():
    """창의 '종료'와 트레이 메뉴의 '종료'가 다른 단어면 안 된다."""
    assert gui_state.EXIT_TEXT == tray_status.QUIT_TEXT


# --------------------------------------------------------------------------
# 2. 연결 상태 줄
# --------------------------------------------------------------------------

def test_status_line_while_stopped_says_stopped_regardless_of_count():
    assert gui_state.status_line(False, 0) == gui_state.STATUS_STOPPED
    assert gui_state.status_line(False, 3) == gui_state.STATUS_STOPPED


def test_status_line_matches_the_tray_tooltip_when_idle():
    assert gui_state.status_line(True, 0) == tray_status.TOOLTIP_IDLE


def test_status_line_matches_the_tray_tooltip_when_connected():
    assert gui_state.status_line(True, 2) == tray_status.tray_status(2).tooltip
    assert gui_state.status_line(True, 2) == "Phone Pad - 연결됨 (2대)"


def test_status_line_does_not_repeat_the_pin():
    """PIN 은 전용 줄이 따로 있다 - 상태 줄에 또 붙으면 중복이다."""
    assert "483920" not in gui_state.status_line(True, 1)


def test_status_line_tolerates_garbage_counts():
    assert gui_state.status_line(True, None) == tray_status.TOOLTIP_IDLE
    assert gui_state.status_line(True, -5) == tray_status.TOOLTIP_IDLE


# --------------------------------------------------------------------------
# 3. PIN 줄
# --------------------------------------------------------------------------

def test_pin_line_shows_the_pin_while_running():
    assert gui_state.pin_line(True, "483920") == "PIN: 483920"
    assert gui_state.pin_line(True, "483920") == tray_status.format_pin_label("483920")


def test_pin_line_says_disabled_when_authentication_is_off():
    assert gui_state.pin_line(True, None) == tray_status.format_pin_label(None)
    assert "사용 안 함" in gui_state.pin_line(True, None)


def test_pin_line_while_stopped_is_neither_a_pin_nor_disabled():
    """정지 중에는 PIN 이 아예 없다 (다음 '시작'에 새로 생긴다).

    '사용 안 함'(= 인증 꺼짐)으로 보이면 사용자가 인증 설정을 오해한다.
    """
    line = gui_state.pin_line(False, "483920")
    assert line == gui_state.PIN_STOPPED
    assert "483920" not in line
    assert "사용 안 함" not in line


# --------------------------------------------------------------------------
# 4. 접속 주소 줄
# --------------------------------------------------------------------------

def test_address_line_reuses_the_tray_format():
    assert gui_state.address_line("192.168.0.42", 9000) == "접속 주소: 192.168.0.42:9000"
    assert gui_state.address_line("192.168.0.42", 9000) == tray_status.format_address_label(
        "192.168.0.42", 9000
    )


def test_address_line_hides_the_port_when_the_ip_is_unknown():
    assert gui_state.address_line(None, 9000) == f"접속 주소: {tray_status.ADDRESS_UNKNOWN}"
    assert gui_state.address_line("127.0.0.1", 9000) == f"접속 주소: {tray_status.ADDRESS_UNKNOWN}"


# --------------------------------------------------------------------------
# 5. 닫기 안내 / 오류 문구
# --------------------------------------------------------------------------

def test_close_hint_differs_by_tray_availability():
    assert gui_state.close_hint(True) == gui_state.HINT_HIDE_TO_TRAY
    assert gui_state.close_hint(False) == gui_state.HINT_CLOSE_QUITS
    assert gui_state.close_hint(True) != gui_state.close_hint(False)


def test_start_error_text_keeps_the_os_reason():
    text = gui_state.start_error_text(OSError("address already in use"))
    assert "시작 실패" in text
    assert "address already in use" in text


def test_error_text_falls_back_to_the_exception_type():
    assert "OSError" in gui_state.start_error_text(OSError())
    assert "RuntimeError" in gui_state.stop_error_text(RuntimeError("   "))


def test_stop_error_text_is_distinct_from_start():
    assert gui_state.stop_error_text(OSError("x")) != gui_state.start_error_text(OSError("x"))


# --------------------------------------------------------------------------
# 6. compute_labels - 창의 모든 줄을 한 번에
# --------------------------------------------------------------------------

def test_compute_labels_while_running():
    labels = gui_state.compute_labels(True, 1, "483920", "접속 주소: 10.0.0.2:9000")
    assert labels.status == "Phone Pad - 연결됨 (1대)"
    assert labels.address == "접속 주소: 10.0.0.2:9000"
    assert labels.pin == "PIN: 483920"
    assert labels.toggle == "정지"


def test_compute_labels_while_stopped():
    labels = gui_state.compute_labels(False, 0, None, "접속 주소: 10.0.0.2:9000")
    assert labels.status == gui_state.STATUS_STOPPED
    assert labels.pin == gui_state.PIN_STOPPED
    assert labels.toggle == "시작"
    # 주소는 서버 상태와 무관하다 (LAN IP 는 그대로다)
    assert labels.address == "접속 주소: 10.0.0.2:9000"


def test_compute_labels_builds_the_address_from_the_port_when_not_given():
    labels = gui_state.compute_labels(True, 0, None, port=9000)
    assert labels.address == f"접속 주소: {tray_status.ADDRESS_UNKNOWN}"


def test_labels_are_frozen():
    labels = gui_state.compute_labels(True, 0, None, "a")
    try:
        labels.status = "x"
    except Exception:
        return
    raise AssertionError("GuiLabels must be immutable")


# --------------------------------------------------------------------------
# 7. 모듈 계약
# --------------------------------------------------------------------------

def test_gui_state_does_not_import_tkinter():
    """순수 로직 모듈은 디스플레이 없는 환경에서도 import 되어야 한다."""
    import inspect

    source = inspect.getsource(gui_state)
    assert "import tkinter" not in source
    assert "import tk" not in source


# --------------------------------------------------------------------------
# 8. 패키징 (PyInstaller) - 창이 기본 모드이므로 tkinter 를 빼면 안 된다
# --------------------------------------------------------------------------

def test_pyinstaller_spec_does_not_exclude_tkinter():
    """exe 가 tkinter 없이 빌드되면 창 없이 조용히 트레이 모드로 떨어진다.

    실행 중에는 '폴백'이라 아무 오류도 안 나므로 사람이 눈치채기 어렵다 -
    빌드 정의에서 고정해 둔다.
    """
    import os
    import re

    spec_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "phone_pad_server.spec",
    )
    with open(spec_path, encoding="utf-8") as f:
        source = f.read()
    excludes = re.search(r"excludes=\[(.*?)\]", source, re.S)
    assert excludes is not None, "the spec must still declare excludes"
    entries = re.findall(r"^\s*\"([^\"]+)\",", excludes.group(1), re.M)
    assert "tkinter" not in entries
    assert "_tkinter" not in entries
