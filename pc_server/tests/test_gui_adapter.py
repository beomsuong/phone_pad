"""tkinter 어댑터(gui.py) 테스트 - 가짜 Tk 주입.

`tray.py` 를 가짜 Icon 으로 검증하는 것과 같은 패턴이다. 디스플레이가 없어도
전부 통과해야 하므로 기본은 `fake_tk.FakeTk` 를 주입하고, 실제 위젯 배선은
맨 아래 "실제 Tk" 절에서 **창을 숨긴 채**로만 확인한다(디스플레이가 없으면 skip).
"""
import threading

import pytest

import gui
import gui_state
from fake_tk import FakeStyle, FakeTk, FakeTtk


class FakeServer:
    """`ServerSupervisor` 대역. '시작'마다 새 PIN 을 준다(실제 동작과 같다)."""

    def __init__(self):
        self.running = False
        self.pin = None
        self.starts = 0
        self.stops = 0
        self.exits = 0
        self.start_error = None
        self.stop_error = None

    def start(self):
        self.starts += 1
        if self.start_error is not None:
            raise self.start_error
        self.running = True
        self.pin = "%06d" % (483920 + self.starts)
        return True

    def stop(self):
        self.stops += 1
        if self.stop_error is not None:
            raise self.stop_error
        self.running = False
        self.pin = None
        return True

    def exit(self):
        self.exits += 1
        self.running = False
        self.pin = None

    def die(self):
        """서버가 사용자의 지시 없이 혼자 멈춘 상황."""
        self.running = False
        self.pin = None


class Harness:
    def __init__(self, tray_usable=True, ip="192.168.0.42", port=9000, count=0,
                 ttk_module=None):
        self.tk = FakeTk(ttk_module=ttk_module)
        self.server = FakeServer()
        self.tray_usable = tray_usable
        self.count = count
        self.ip_calls = 0
        self.ip = ip

        def ip_lookup():
            self.ip_calls += 1
            if isinstance(self.ip, Exception):
                raise self.ip
            return self.ip

        self.controller = gui.GuiController(
            on_start=self.server.start,
            on_stop=self.server.stop,
            on_exit=self.server.exit,
            is_running=lambda: self.server.running,
            count_provider=lambda: self.count,
            pin_provider=lambda: self.server.pin,
            port=port,
            tray_visible=lambda: self.tray_usable,
            ip_lookup=ip_lookup,
            tk_module=self.tk,
            poll_interval=0.01,
            queue_interval=0.01,
        )

    def build(self):
        self.controller.build()
        return self

    @property
    def root(self):
        return self.tk.root

    def label(self, key):
        return self.controller.label(key)

    def button(self, key):
        return self.controller._buttons[key]

    @property
    def style(self):
        return self.tk.ttk.style

    def dot_color(self):
        return self.controller._dot_label.kwargs.get("foreground")

    def close_window(self):
        """X 버튼."""
        self.root.protocols["WM_DELETE_WINDOW"]()


# --------------------------------------------------------------------------
# 1. 가용성 계약
# --------------------------------------------------------------------------

def test_gui_available_matches_the_import_result():
    assert gui.gui_available() is (gui.GUI_IMPORT_ERROR is None)


def test_unavailable_reason_is_empty_when_available():
    if gui.gui_available():
        assert gui.unavailable_reason() == ""


def test_build_without_tkinter_raises_a_clear_error():
    controller = gui.GuiController(
        on_start=lambda: None, on_stop=lambda: None, on_exit=lambda: None,
        is_running=lambda: False, count_provider=lambda: 0, tk_module=None,
    )
    controller._tk = None  # tkinter 미설치 흉내
    with pytest.raises(gui.GuiUnavailableError):
        controller.build()


# --------------------------------------------------------------------------
# 2. 창 구성
# --------------------------------------------------------------------------

def test_build_sets_the_title_and_intercepts_the_close_button():
    h = Harness().build()
    assert h.root.title_text == gui_state.WINDOW_TITLE
    assert "WM_DELETE_WINDOW" in h.root.protocols


def test_build_shows_the_stopped_state_before_the_server_starts():
    h = Harness().build()
    assert h.label("status") == gui_state.STATUS_STOPPED
    assert h.label("pin") == gui_state.PIN_STOPPED
    assert h.label("toggle") == "시작"
    assert h.label("message") == ""


def test_build_shows_the_lan_address_and_caches_the_lookup():
    h = Harness().build()
    assert h.label("address") == "접속 주소: 192.168.0.42:9000"
    for _ in range(5):
        h.controller.refresh()
    assert h.ip_calls == 1, "LAN IP lookup must be cached (it opens a socket)"


def test_address_survives_a_failing_ip_lookup():
    h = Harness(ip=OSError("no route")).build()
    assert "확인 불가" in h.label("address")


def test_hint_tells_what_the_close_button_does():
    assert Harness(tray_usable=True).build().label("hint") == gui_state.HINT_HIDE_TO_TRAY
    assert Harness(tray_usable=False).build().label("hint") == gui_state.HINT_CLOSE_QUITS


def test_hint_follows_the_tray_when_it_disappears():
    """트레이 스레드가 죽으면 X 버튼의 의미가 바뀐다 - 안내도 따라가야 한다."""
    h = Harness(tray_usable=True).build()
    h.tray_usable = False
    h.controller.refresh()
    assert h.label("hint") == gui_state.HINT_CLOSE_QUITS


def test_two_buttons_exist_with_the_expected_wiring():
    h = Harness().build()
    assert h.button("exit").kwargs["text"] == gui_state.EXIT_TEXT
    assert h.button("toggle").kwargs["command"] == h.controller.on_toggle
    assert h.button("exit").kwargs["command"] == h.controller.exit_clicked


# --------------------------------------------------------------------------
# 2b. 겉모습 (동작이 아니라 '어떻게 보이는가'. 여기서 깨지면 창이 레트로로 돌아간다)
# --------------------------------------------------------------------------

def test_every_widget_is_themed_and_the_native_theme_is_applied():
    """클래식 `tk.Label/Button`(회색 베벨)으로 되돌아가면 여기서 잡힌다."""
    h = Harness().build()
    widgets = h.tk.ttk.widgets
    assert widgets, "the window must be built out of ttk widgets"
    assert all(w.themed for w in widgets)
    assert h.style.theme_use() == gui.PREFERRED_THEME


def test_theme_falls_back_silently_when_vista_is_missing():
    """다른 플랫폼/구버전 Tcl 에는 vista 가 없다 - 창은 그래도 떠야 한다."""
    ttk_module = FakeTtk(style_factory=lambda master: FakeStyle(
        master, themes=("clam", "default")))
    h = Harness(ttk_module=ttk_module).build()
    assert h.style.theme_uses == [], "an absent theme must not be requested"
    assert h.root.state == "normal"


def test_build_survives_a_style_setup_failure(capsys):
    """스타일은 보기 좋으라고 있는 것이다 - 실패해도 서버를 못 켜게 하면 안 된다."""
    class ExplodingTtk(FakeTtk):
        def Style(self, master=None):
            raise RuntimeError("no style engine")

    h = Harness(ttk_module=ExplodingTtk()).build()
    assert h.label("status") == gui_state.STATUS_STOPPED
    assert h.button("toggle").kwargs["style"] == gui.STYLE_BUTTON
    assert capsys.readouterr().out.isascii()


def test_the_start_button_gets_an_emphasis_style():
    """이 환경의 Tk 8.6.15 에는 Accent 가 없어 직접 만든 스타일로 내려간다."""
    h = Harness().build()
    assert h.button("toggle").kwargs["style"] == gui.STYLE_PRIMARY_BUTTON
    assert gui.STYLE_PRIMARY_BUTTON in h.style.configured
    assert "style" not in h.button("exit").kwargs, "the exit button stays secondary"


def test_a_builtin_accent_style_is_preferred_when_tcl_provides_one():
    ttk_module = FakeTtk(style_factory=lambda master: FakeStyle(
        master, builtin={gui.STYLE_ACCENT_BUTTON: {"background": "#0067c0"}}))
    h = Harness(ttk_module=ttk_module).build()
    assert h.button("toggle").kwargs["style"] == gui.STYLE_ACCENT_BUTTON


def test_pin_is_large_only_while_there_is_a_real_pin():
    h = Harness().build()
    assert h.controller._pin_label.kwargs["style"] == gui.STYLE_PIN_MUTED

    h.controller.start_server()
    assert h.label("pin") == "PIN: 483921"
    assert h.controller._pin_label.kwargs["style"] == gui.STYLE_PIN

    h.controller.stop_server()
    assert h.controller._pin_label.kwargs["style"] == gui.STYLE_PIN_MUTED


def test_status_dot_and_window_icon_follow_the_tray_color_rule():
    """회색 = 대기, 초록 = 연결됨 (트레이 아이콘과 같은 의미)."""
    h = Harness().build()
    h.controller.start_server()
    assert h.dot_color() == gui.COLOR_IDLE
    assert h.root.icon_photos[-1][1] is h.controller._icons[gui.COLOR_IDLE]

    h.count = 1
    h.controller.refresh()
    assert h.label("status") == "Phone Pad - 연결됨 (1대)"
    assert h.dot_color() == gui.COLOR_CONNECTED
    assert h.root.icon_photos[-1][1] is h.controller._icons[gui.COLOR_CONNECTED]

    h.count = 0
    h.controller.refresh()
    assert h.dot_color() == gui.COLOR_IDLE


def test_an_unchanged_status_does_not_repaint_the_dot():
    """Windows 에서 아이콘을 매초 갈아 끼우면 깜빡인다 (트레이와 같은 규칙)."""
    h = Harness().build()
    before = len(h.root.icon_photos)
    for _ in range(5):
        h.controller.refresh()
    assert len(h.root.icon_photos) == before
    assert h.controller._dot_label.configure_calls == [{"foreground": gui.COLOR_IDLE}]


def test_the_window_icon_is_drawn_without_pillow():
    """Pillow 는 선택 의존성이다 - 창 아이콘은 표준 `PhotoImage` 로만 그린다."""
    h = Harness().build()
    assert len(h.tk.images) == 2
    for image in h.tk.images:
        assert (image.width, image.height) == (gui.ICON_SIZE_PX, gui.ICON_SIZE_PX)
        assert image.puts, "the dot must actually be painted"


def test_minimum_width_keeps_the_window_from_jumping():
    h = Harness().build()
    assert h.root.minsize_calls == [(gui.MIN_WIDTH_PX, 0)]


def test_status_color_only_turns_green_for_the_connected_line():
    assert gui.status_color(gui_state.STATUS_STOPPED) == gui.COLOR_IDLE
    assert gui.status_color("Phone Pad - 대기 중") == gui.COLOR_IDLE
    assert gui.status_color("Phone Pad - 연결됨 (3대)") == gui.COLOR_CONNECTED
    assert gui.status_color(None) == gui.COLOR_IDLE


def test_pin_style_picks_the_big_font_only_for_digits():
    assert gui.pin_style("PIN: 483920") == gui.STYLE_PIN
    assert gui.pin_style(gui_state.PIN_STOPPED) == gui.STYLE_PIN_MUTED
    assert gui.pin_style("PIN: (사용 안 함)") == gui.STYLE_PIN_MUTED
    assert gui.pin_style(None) == gui.STYLE_PIN_MUTED


# --------------------------------------------------------------------------
# 3. 시작 / 정지 토글
# --------------------------------------------------------------------------

def test_start_button_starts_the_server_and_updates_every_label():
    h = Harness().build()
    h.button("toggle").invoke()

    assert h.server.starts == 1
    assert h.label("status") == "Phone Pad - 대기 중"
    assert h.label("pin") == "PIN: 483921"
    assert h.label("toggle") == "정지"
    assert h.label("message") == ""


def test_toggle_stops_a_running_server():
    h = Harness().build()
    h.button("toggle").invoke()
    h.button("toggle").invoke()

    assert (h.server.starts, h.server.stops) == (1, 1)
    assert h.label("status") == gui_state.STATUS_STOPPED
    assert h.label("pin") == gui_state.PIN_STOPPED
    assert h.label("toggle") == "시작"


def test_restart_creates_a_new_runtime_and_a_new_pin():
    """`ServerRuntime` 은 재사용 불가 - '시작'마다 새로 만들어져야 한다."""
    h = Harness().build()
    h.controller.start_server()
    first = h.label("pin")
    h.controller.stop_server()
    h.controller.start_server()

    assert h.server.starts == 2, "each start must build a fresh runtime"
    assert h.label("pin") != first
    assert h.label("pin") == "PIN: 483922"


def test_many_toggles_stay_consistent():
    h = Harness().build()
    for _ in range(5):
        h.controller.on_toggle()
        h.controller.on_toggle()
    assert (h.server.starts, h.server.stops) == (5, 5)
    assert h.server.running is False
    assert h.label("toggle") == "시작"


def test_start_failure_shows_the_error_and_keeps_the_window_alive():
    h = Harness().build()
    h.server.start_error = OSError("address already in use")

    assert h.controller.start_server() is False
    assert "시작 실패" in h.label("message")
    assert "address already in use" in h.label("message")
    assert h.label("status") == gui_state.STATUS_STOPPED
    assert h.label("toggle") == "시작", "a failed start must stay startable"
    assert h.root.state == "normal", "the window must not be destroyed"


def test_start_error_is_cleared_by_a_later_success():
    h = Harness().build()
    h.server.start_error = OSError("busy")
    h.controller.start_server()
    h.server.start_error = None
    h.controller.start_server()
    assert h.label("message") == ""


def test_start_failure_log_line_is_ascii(capsys):
    h = Harness().build()
    h.server.start_error = OSError("simulated")
    h.controller.start_server()
    out = capsys.readouterr().out
    assert "[!] Server failed to start" in out
    assert out.isascii(), "console logs must survive a cp949 console"


def test_stop_failure_is_reported_without_crashing():
    h = Harness().build()
    h.controller.start_server()
    h.server.stop_error = RuntimeError("stuck thread")

    assert h.controller.stop_server() is False
    assert "정지 실패" in h.label("message")
    assert h.root.state == "normal"


# --------------------------------------------------------------------------
# 4. 서버가 혼자 죽었을 때
# --------------------------------------------------------------------------

def test_refresh_warns_when_the_server_dies_on_its_own():
    h = Harness().build()
    h.controller.start_server()
    h.server.die()
    h.controller.refresh()

    assert h.label("message") == gui_state.MESSAGE_SERVER_DIED
    assert h.label("toggle") == "시작", "the user must be able to start it again"


def test_a_user_requested_stop_is_not_reported_as_a_crash():
    h = Harness().build()
    h.controller.start_server()
    h.controller.stop_server()
    h.controller.refresh()
    assert h.label("message") == ""


def test_refresh_reports_a_changed_connection_count():
    h = Harness().build()
    h.controller.start_server()
    h.count = 2
    assert h.controller.refresh() is True
    assert h.label("status") == "Phone Pad - 연결됨 (2대)"
    assert h.controller.refresh() is False, "an unchanged state must not touch the widgets"


def test_refresh_survives_a_broken_state_callback(capsys):
    h = Harness().build()
    h.controller._is_running = lambda: (_ for _ in ()).throw(RuntimeError("boom"))
    h.controller.refresh()  # 예외가 새면 창이 먹통이 된다
    assert h.label("status") == gui_state.STATUS_STOPPED
    assert capsys.readouterr().out.isascii()


# --------------------------------------------------------------------------
# 5. X 버튼 / 종료
# --------------------------------------------------------------------------

def test_close_button_hides_to_the_tray_when_the_tray_is_up():
    h = Harness(tray_usable=True).build()
    h.close_window()

    assert h.root.state == "withdrawn"
    assert h.root.destroy_calls == 0
    assert h.server.exits == 0, "hiding must not shut the server down"


def test_close_button_quits_when_there_is_no_tray():
    h = Harness(tray_usable=False).build()
    h.close_window()

    assert h.server.exits == 1
    assert h.root.destroy_calls == 1


def test_exit_button_runs_the_shutdown_once_and_destroys_the_window():
    h = Harness().build()
    h.button("exit").invoke()
    h.button("exit").invoke()
    h.controller.exit_clicked()

    assert h.server.exits == 1, "the shutdown procedure must run exactly once"
    assert h.root.destroy_calls == 1


def test_exit_destroys_the_window_even_if_the_shutdown_handler_raises(capsys):
    h = Harness().build()
    h.controller._on_exit = lambda: (_ for _ in ()).throw(RuntimeError("shutdown blew up"))
    h.controller.exit_clicked()

    assert h.root.destroy_calls == 1, "a failing shutdown must not leave the window stuck"
    assert "[!] GUI exit handler failed" in capsys.readouterr().out


def test_destroy_is_idempotent():
    h = Harness().build()
    h.controller.destroy()
    h.controller.destroy()
    assert h.root.destroy_calls == 1
    assert h.controller.closed is True


# --------------------------------------------------------------------------
# 6. 스레드 간 호출 (트레이 -> 창)
# --------------------------------------------------------------------------

def test_request_does_not_touch_widgets_from_the_calling_thread():
    """tkinter 는 스레드 세이프하지 않다 - 큐에 넣기만 해야 한다."""
    h = Harness().build()
    h.controller.hide_window()

    done = threading.Event()

    def from_another_thread():
        h.controller.request_show()
        done.set()

    t = threading.Thread(target=from_another_thread)
    t.start()
    t.join(timeout=2)
    assert done.is_set()
    assert h.root.state == "withdrawn", "the widget must not move before the main thread runs"

    h.controller.drain_commands()
    assert h.root.state == "normal"
    assert h.root.lift_calls == 1


def test_show_window_also_raises_the_window_above_others():
    h = Harness().build()
    h.controller.hide_window()
    h.controller.show_window()
    assert (h.root.state, h.root.lift_calls, h.root.focus_calls) == ("normal", 1, 1)


def test_request_is_refused_once_the_window_is_gone():
    h = Harness().build()
    h.controller.destroy()
    assert h.controller.request_show() is False


def test_drain_swallows_a_failing_command(capsys):
    h = Harness().build()
    h.controller.request(lambda: (_ for _ in ()).throw(RuntimeError("bad command")))
    h.controller.request(h.controller.hide_window)
    assert h.controller.drain_commands() == 2
    assert h.root.state == "withdrawn", "one bad command must not block the next"
    assert "[!] GUI command failed" in capsys.readouterr().out


def test_request_destroy_ends_the_window_from_another_thread():
    h = Harness().build()
    t = threading.Thread(target=h.controller.stop)
    t.start()
    t.join(timeout=2)
    assert h.root.destroy_calls == 0
    h.controller.drain_commands()
    assert h.root.destroy_calls == 1


def test_show_window_after_destroy_is_a_noop():
    h = Harness().build()
    h.controller.destroy()
    h.controller.show_window()  # 예외 없이 무시
    h.controller.hide_window()


# --------------------------------------------------------------------------
# 7. run() 수명 주기 (가짜 mainloop)
# --------------------------------------------------------------------------

def test_run_polls_the_status_and_drains_the_queue_until_destroyed():
    h = Harness()
    # mainloop 진입 전에 예약된 작업(= 자동 시작)이 첫 tick 에서 실행돼야 한다.
    h.controller.request(h.controller.start_server)
    state = {"ticks": 0}

    def on_iteration(root):
        state["ticks"] += 1
        if state["ticks"] == 3:
            h.count = 4
        if state["ticks"] == 6:
            # 다른 스레드에서 온 종료 요청
            t = threading.Thread(target=h.controller.stop)
            t.start()
            t.join(timeout=2)

    original_tk = h.tk.Tk

    def tk_with_hook(**kwargs):
        root = original_tk(**kwargs)
        root.on_iteration = on_iteration
        return root

    h.tk.Tk = tk_with_hook

    h.controller.run()

    assert h.server.starts == 1, "the queued autostart must run on the main thread"
    assert h.tk.root.mainloop_calls == 1
    assert h.tk.root.destroy_calls == 1
    assert h.controller.closed is True


def test_run_stops_scheduling_after_the_window_closes():
    h = Harness()

    def on_iteration(root):
        h.controller.destroy()

    original_tk = h.tk.Tk

    def tk_with_hook(**kwargs):
        root = original_tk(**kwargs)
        root.on_iteration = on_iteration
        return root

    h.tk.Tk = tk_with_hook
    h.controller.run()

    assert h.tk.root.scheduled == [], "no callback may stay scheduled on a dead window"


# --------------------------------------------------------------------------
# 8. 실제 Tk (디스플레이가 있을 때만) - 창은 끝까지 숨긴 채로 둔다
# --------------------------------------------------------------------------

@pytest.fixture(scope="module")
def real_tk():
    """실제 tkinter 모듈 대역. 창은 끝까지 숨긴 채로만 쓴다.

    **Tcl 인터프리터(`Tk()`)는 모듈당 하나만 만든다.** 테스트마다
    `tkinter.Tk()` 를 새로 만들고 부수면 이 Windows 환경에서 5회에 1회꼴로
    `_tkinter.create()` 가 `TclError` 로 실패했다(단독 스크립트로 120회 반복할
    때는 재현되지 않고 pytest 안에서만 나온다 - 원인 미상). `Toplevel` 은
    `GuiController` 가 쓰는 API(title/protocol/withdraw/deiconify/state/after/
    mainloop/destroy)를 모두 갖고 있어 검증 내용은 그대로다.
    """
    tkinter = pytest.importorskip("tkinter")
    try:
        root = tkinter.Tk()
        root.withdraw()
    except Exception as e:  # 디스플레이 없음(CI) / tcl 미설치
        pytest.skip(f"no usable display: {e}")

    class HiddenTk:
        TclError = tkinter.TclError

        def __getattr__(self, name):
            return getattr(tkinter, name)

        def Tk(self, **kwargs):
            window = tkinter.Toplevel(root, **kwargs)
            window.withdraw()
            return window

    try:
        yield HiddenTk()
    finally:
        try:
            root.destroy()
        except Exception:
            pass


def _real_harness(real_tk):
    h = Harness()
    h.controller._tk = real_tk
    return h


def _rgb(value):
    """`PhotoImage.get()` 는 Tk/Python 버전에 따라 튜플 또는 '46 160 67' 문자열."""
    if isinstance(value, str):
        value = tuple(int(part) for part in value.split())
    return tuple(value)[:3]


def test_real_tk_builds_the_window_and_wires_the_buttons(real_tk):
    h = _real_harness(real_tk)
    h.controller.build()
    try:
        assert h.controller.root.title() == gui_state.WINDOW_TITLE
        h.controller._buttons["toggle"].invoke()   # 실제 Tk Button.invoke()
        assert h.server.starts == 1
        assert h.controller.label("toggle") == "정지"
        h.controller._buttons["toggle"].invoke()
        assert h.server.stops == 1
    finally:
        h.controller.destroy()


def test_real_tk_actually_applies_the_custom_styles(real_tk):
    """가짜 Style 은 옵션 이름이 틀려도 받아 준다 - 실제 Tk 로 한 번 확인한다.

    `_setup_style()` 은 실패를 조용히 삼키므로(창이 안 뜨면 안 되니까) 여기서
    확인하지 않으면 오타 하나로 '레트로' 로 돌아가도 모른다.
    """
    h = _real_harness(real_tk)
    h.controller.build()
    try:
        style = h.controller._style
        assert style is not None
        assert gui.FONT_MONO in str(style.lookup(gui.STYLE_PIN, "font"))
        assert style.lookup(gui.STYLE_STATUS, "foreground") == gui.COLOR_TEXT
        assert style.lookup(gui.STYLE_CARD, "background") == gui.COLOR_SURFACE
        # 강조 버튼은 실제로 존재하는 스타일 이름이어야 한다.
        assert style.configure(h.controller._primary_style)
        # 실제 위젯도 그 스타일을 달고 있어야 한다.
        assert h.controller._pin_label.cget("style") == gui.STYLE_PIN_MUTED
        assert h.controller._buttons["toggle"].cget("style") == h.controller._primary_style
    finally:
        h.controller.destroy()


def test_real_tk_paints_the_status_dot_and_window_icon(real_tk):
    h = _real_harness(real_tk)
    h.controller.build()
    try:
        # 이 Tcl/Tk 버전은 `cget("foreground")`가 평범한 str이 아니라 색 전용
        # 래퍼 객체를 돌려준다(repr은 같은 값을 보여주지만 `==`이 str과 바로
        # 성립하지 않는다) - str()로 감싸서 비교한다.
        assert str(h.controller._dot_label.cget("foreground")) == gui.COLOR_IDLE
        assert set(h.controller._icons) == {gui.COLOR_IDLE, gui.COLOR_CONNECTED}
        image = h.controller._icons[gui.COLOR_CONNECTED]
        assert (image.width(), image.height()) == (gui.ICON_SIZE_PX, gui.ICON_SIZE_PX)
        # 가운데는 칠해져 있고 모서리는 투명해야 '원' 이다.
        middle = gui.ICON_SIZE_PX // 2
        assert _rgb(image.get(middle, middle)) == (46, 160, 67)
        assert image.transparency_get(0, 0) is True
        # `image`는 `PhotoImage`(Tcl 인터프리터에 매인 객체)를 가리키는 이 함수
        # 지역 변수다. 지우지 않으면 함수가 끝날 때(= finally의 destroy() 이후)
        # 참조 카운트가 0이 되어 `__del__`이 이미 파괴된 인터프리터를 호출해
        # "main thread is not in main loop"를 던진다(pytest가 실패로 잡는다).
        # destroy()는 `self._icons`를 root.destroy() 전에 비워 정상적으로 정리하므로,
        # 여기서 이 지역 참조만 먼저 없애면 destroy() 시점에 참조가 그것 하나만 남는다.
        del image

        h.controller.start_server()
        h.count = 2
        h.controller.refresh()
        assert str(h.controller._dot_label.cget("foreground")) == gui.COLOR_CONNECTED
    finally:
        h.controller.destroy()


def test_real_tk_close_protocol_is_registered(real_tk):
    h = _real_harness(real_tk)
    root = h.controller.build()
    try:
        # Tk 는 등록된 콜백 이름을 돌려준다 - 비어 있으면 X 가 기본 동작(파괴)이 된다.
        assert root.protocol("WM_DELETE_WINDOW") != ""
    finally:
        h.controller.destroy()


def test_real_tk_hide_and_restore_change_the_window_state(real_tk):
    h = _real_harness(real_tk)
    root = h.controller.build()
    try:
        h.controller.hide_window()
        assert root.state() == "withdrawn"
        h.controller.show_window()
        assert root.state() == "normal"
        # 검증이 끝나면 화면에서 다시 치운다.
        h.controller.hide_window()
    finally:
        h.controller.destroy()


def test_real_tk_after_runs_the_command_queue_on_the_main_thread(real_tk):
    """실제 Tk 에서도 '다른 스레드 -> 큐 -> after tick' 경로가 동작하는지."""
    h = _real_harness(real_tk)
    root = h.controller.build()
    try:
        h.controller.hide_window()
        t = threading.Thread(target=h.controller.request_show)
        t.start()
        t.join(timeout=2)
        assert root.state() == "withdrawn", "the other thread must not touch the widget"

        h.controller._after(0.001, h.controller._queue_tick)
        root.update()          # 예약된 after 콜백을 실제로 실행시킨다
        root.after(20, root.quit)
        root.mainloop()
        assert root.state() == "normal"
        h.controller.hide_window()
    finally:
        h.controller.destroy()
