"""창 모드 통합 테스트 - `ServerSupervisor` / `run_with_gui` / `main()` 폴백 사슬.

`test_server_shutdown.py` 의 트레이 테스트와 같은 방식이다: 진짜 `ServerRuntime`
(포트 0 바인딩)과 **가짜 창/트레이**를 써서, 화면에 아무것도 띄우지 않고
"버튼을 누르면 실제로 포트가 열리고 닫히는가 / 종료 절차가 한 번만 도는가" 를 본다.
"""
import ctypes
import socket
import threading
import time
from unittest.mock import patch

import pytest

import gui
import server
import tray
from input_controller import INPUT, MOUSEEVENTF_LEFTUP, InputController
from send_input_stub import patch_send_input

LOCALHOST = "127.0.0.1"
# 이 머신에 없는 주소 - bind 가 실제로 OSError 를 던진다(모킹 없이 '기동 실패' 재현).
UNBINDABLE_HOST = "203.0.113.1"


def sent_flags(mock_send):
    flags = []
    for call in mock_send.call_args_list:
        count, inputs, size = call.args
        assert size == ctypes.sizeof(INPUT)
        flags.extend(inputs[i]._input.mi.dwFlags for i in range(count))
    return flags


def runtime_factory(controller=None, registry=None, host=LOCALHOST, pins=None, **kwargs):
    """포트 0 으로 뜨는 런타임을 만드는 팩토리 + 만들어진 런타임 목록."""
    controller = controller if controller is not None else InputController()
    registry = registry if registry is not None else server.SessionRegistry()
    made = []
    counter = {"n": 0}

    kwargs.setdefault("tcp_port", 0)
    kwargs.setdefault("udp_port", 0)
    kwargs.setdefault("accept_timeout", 0.05)

    def factory():
        counter["n"] += 1
        pin = None
        if pins is not None:
            pin = pins % counter["n"] if "%" in pins else pins
        runtime = server.ServerRuntime(
            controller, registry, host=host, expected_pin=pin, **kwargs
        )
        made.append(runtime)
        return runtime

    factory.made = made
    factory.controller = controller
    factory.registry = registry
    return factory


# ==========================================================================
# 1. ServerSupervisor - '시작/정지' 가 실제로 포트를 열고 닫는가
# ==========================================================================

def test_supervisor_starts_a_runtime_and_binds_a_real_port():
    factory = runtime_factory()
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    try:
        assert supervisor.running is False
        assert supervisor.start() is True
        assert supervisor.running is True
        port = supervisor.runtime.tcp_port
        assert port not in (None, 0)
        with socket.create_connection((LOCALHOST, port), timeout=2.0):
            pass
    finally:
        supervisor.stop()


def test_supervisor_stop_releases_the_port():
    factory = runtime_factory()
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    port = supervisor.runtime.tcp_port

    assert supervisor.stop() is True
    assert supervisor.running is False
    with pytest.raises(OSError):
        socket.create_connection((LOCALHOST, port), timeout=1.0).close()


def test_supervisor_restart_builds_a_brand_new_runtime():
    """`ServerRuntime` 은 close() 후 재사용할 수 없다 - 매번 새 인스턴스여야 한다."""
    factory = runtime_factory()
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    first = supervisor.runtime
    supervisor.stop()
    supervisor.start()
    try:
        second = supervisor.runtime
        assert second is not first
        assert len(factory.made) == 2
        assert first.tcp_socket.fileno() == -1, "the old socket must be closed"
        assert second.tcp_port not in (None, 0)
    finally:
        supervisor.stop()


def test_supervisor_gives_each_start_a_fresh_pin():
    """PIN 은 '서버 시작' 단위 - 정지 후 다시 시작하면 새 값이다."""
    factory = runtime_factory(pins="pin-%d")
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    assert supervisor.current_pin() is None
    supervisor.start()
    first = supervisor.current_pin()
    supervisor.stop()
    assert supervisor.current_pin() is None, "a stopped server has no PIN"
    supervisor.start()
    try:
        assert first == "pin-1"
        assert supervisor.current_pin() == "pin-2"
    finally:
        supervisor.stop()


def test_supervisor_start_raises_the_bind_error_to_the_caller():
    """창이 오류를 표시할 수 있어야 하므로 bind 실패는 **동기적으로** 올라온다."""
    factory = runtime_factory(host=UNBINDABLE_HOST)
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    with pytest.raises(OSError):
        supervisor.start()
    assert supervisor.running is False
    assert supervisor.stop() is False


def test_supervisor_can_start_again_after_a_failed_start():
    factory_bad = runtime_factory(host=UNBINDABLE_HOST)
    supervisor = server.ServerSupervisor(factory_bad, join_timeout=2.0)
    with pytest.raises(OSError):
        supervisor.start()
    supervisor._runtime_factory = runtime_factory()
    try:
        assert supervisor.start() is True
    finally:
        supervisor.stop()


def test_supervisor_start_while_running_is_a_noop():
    factory = runtime_factory()
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    try:
        assert supervisor.start() is False
        assert len(factory.made) == 1
    finally:
        supervisor.stop()


def test_supervisor_stop_is_idempotent():
    supervisor = server.ServerSupervisor(runtime_factory(), join_timeout=2.0)
    supervisor.start()
    assert supervisor.stop() is True
    assert supervisor.stop() is False


def test_supervisor_notices_a_server_thread_that_died(capsys):
    """서버가 혼자 죽으면 running 이 내려가야 한다 (창이 '정지됨' 으로 돌아간다)."""

    class ExplodingRuntime:
        def bind(self):
            pass

        def serve(self):
            raise RuntimeError("accept loop blew up")

        def stop(self):
            pass

        def close(self):
            pass

    supervisor = server.ServerSupervisor(ExplodingRuntime, join_timeout=2.0)
    supervisor.start()
    deadline = time.monotonic() + 3.0
    while supervisor.running and time.monotonic() < deadline:
        time.sleep(0.01)

    assert supervisor.running is False
    assert len(supervisor.failures) == 1
    out = capsys.readouterr().out
    assert "[!] Server stopped unexpectedly" in out
    assert out.isascii()


def test_supervisor_stop_does_not_deadlock_when_the_thread_exits_by_itself():
    """정리 코드가 같은 락을 잡으므로, join 을 락 안에서 하면 교착한다."""

    class ShortRuntime:
        def bind(self):
            pass

        def serve(self):
            time.sleep(0.05)

        def stop(self):
            pass

        def close(self):
            pass

    supervisor = server.ServerSupervisor(ShortRuntime, join_timeout=2.0)
    supervisor.start()
    done = threading.Event()

    def stopper():
        supervisor.stop()
        done.set()

    threading.Thread(target=stopper, daemon=True).start()
    assert done.wait(timeout=3.0), "stop() deadlocked"


def test_supervisor_serves_a_real_client_after_a_restart():
    """정지/시작을 거친 뒤에도 핸드셰이크가 멀쩡한지 (실소켓)."""
    from fake_conn import AUTH_LINE
    import json

    factory = runtime_factory()
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    supervisor.stop()
    supervisor.start()
    try:
        assert supervisor.runtime.ready.wait(timeout=5)
        with socket.create_connection((LOCALHOST, supervisor.runtime.tcp_port), timeout=2.0) as c:
            c.settimeout(2.0)
            c.sendall(AUTH_LINE)
            event = json.loads(c.makefile("r", encoding="utf-8").readline())
            assert event["type"] == "SESSION"
            assert factory.registry.is_active(event["session"])
    finally:
        supervisor.stop()


# ==========================================================================
# 2. run_with_gui - 가짜 창 / 가짜 트레이
# ==========================================================================

class FakeGui:
    """`gui.GuiController` 대역. run() 은 destroy() 될 때까지 블로킹한다."""

    instances = []
    RUN_TIMEOUT_S = 10.0

    def __init__(self, on_start, on_stop, on_exit, is_running, count_provider,
                 pin_provider=None, port=0, tray_visible=None, **kwargs):
        self.on_start = on_start
        self.on_stop = on_stop
        self.on_exit = on_exit
        self.is_running = is_running
        self.count_provider = count_provider
        self.pin_provider = pin_provider
        self.port = port
        self.tray_visible = tray_visible if tray_visible is not None else (lambda: False)
        self.kwargs = kwargs
        self.run_calls = 0
        self.destroy_calls = 0
        self.start_errors = []
        self.shows = 0
        self.on_run_entry = None
        self._queue = []
        self._lock = threading.Lock()
        self._released = threading.Event()
        FakeGui.instances.append(self)

    # -- run_with_gui 가 실제로 부르는 것들 --------------------------------
    def request(self, func):
        with self._lock:
            self._queue.append(func)
        return True

    def request_show(self):
        return self.request(self._show)

    def request_destroy(self):
        return self.request(self.destroy)

    def _show(self):
        self.shows += 1

    def start_server(self):
        try:
            self.on_start()
        except Exception as e:
            self.start_errors.append(e)
            return False
        return True

    def stop_server(self):
        self.on_stop()
        return True

    def exit_clicked(self):
        try:
            self.on_exit()
        finally:
            self.destroy()

    def destroy(self):
        self.destroy_calls += 1
        self._released.set()

    def drain(self):
        with self._lock:
            pending, self._queue = self._queue, []
        for func in pending:
            func()
        return len(pending)

    def run(self):
        self.run_calls += 1
        self.drain()  # mainloop 진입 직후 첫 tick (자동 시작이 여기서 돈다)
        if self.on_run_entry is not None:
            self.on_run_entry(self)
        deadline = time.monotonic() + self.RUN_TIMEOUT_S
        while not self._released.wait(0.005):
            self.drain()
            if time.monotonic() > deadline:
                raise AssertionError("fake gui was never destroyed")
        self.drain()


class FakeTray:
    """`tray.TrayController` 대역 (백그라운드 스레드에서 돈다)."""

    instances = []

    def __init__(self, count_provider, on_quit, port, pin=None, on_show=None):
        self.count_provider = count_provider
        self.on_quit = on_quit
        self.port = port
        self.pin = pin
        self.on_show = on_show
        self.run_calls = 0
        self.stop_calls = 0
        self.on_run_entry = None
        self._released = threading.Event()
        self.started = threading.Event()
        FakeTray.instances.append(self)

    def run(self):
        self.run_calls += 1
        self.started.set()
        if self.on_run_entry is not None:
            self.on_run_entry(self)
        assert self._released.wait(timeout=10), "fake tray was never stopped"

    def stop(self):
        self.stop_calls += 1
        self._released.set()

    def quit(self):
        try:
            self.on_quit()
        finally:
            self.stop()


@pytest.fixture(autouse=True)
def _clear_fakes():
    FakeGui.instances.clear()
    FakeTray.instances.clear()
    yield
    FakeGui.instances.clear()
    FakeTray.instances.clear()


def gui_factory(**kwargs):
    return FakeGui(**kwargs)


def exiting_gui_factory(**kwargs):
    """창이 뜨자마자 '종료' 를 누른다."""
    controller = FakeGui(**kwargs)
    controller.on_run_entry = lambda c: c.exit_clicked()
    return controller


def tray_factory(**kwargs):
    return FakeTray(**kwargs)


def run_gui(factory, gui_maker=exiting_gui_factory, tray_maker=None, **kwargs):
    return server.run_with_gui(
        factory.controller, factory.registry, factory,
        gui_factory=gui_maker, tray_factory=tray_maker, join_timeout=2.0,
        port=9000, **kwargs
    )


def test_gui_autostarts_the_server_before_the_user_touches_anything():
    factory = runtime_factory()
    started = {}

    def probing_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            # 자동 시작은 mainloop 진입 직후 첫 tick 에서 이미 끝나 있어야 한다.
            started["running"] = c.is_running()
            started["port"] = factory.made[0].tcp_port
            c.exit_clicked()

        controller.on_run_entry = on_entry
        return controller

    assert run_gui(factory, gui_maker=probing_factory) == 0
    assert started["running"] is True
    assert started["port"] not in (None, 0)
    assert len(factory.made) == 1


def test_autostart_can_be_turned_off():
    factory = runtime_factory()
    assert run_gui(factory, autostart=False) == 0
    assert factory.made == []


def test_exit_stops_the_server_and_closes_the_sockets():
    factory = runtime_factory()
    assert run_gui(factory) == 0

    runtime = factory.made[0]
    assert runtime.stop_event.is_set()
    assert runtime.tcp_socket.fileno() == -1
    assert runtime.udp_socket.fileno() == -1


def test_exit_while_dragging_releases_the_button():
    factory = runtime_factory()
    controller = factory.controller
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        assert controller.drag_active is True
        mock_send.reset_mock()
        run_gui(factory)

    assert controller.drag_active is False
    assert MOUSEEVENTF_LEFTUP in sent_flags(mock_send)


def test_stop_button_releases_the_port_and_start_binds_a_new_one():
    factory = runtime_factory()
    seen = {}

    def toggling_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            seen["first_port"] = factory.made[0].tcp_port
            c.stop_server()
            seen["running_after_stop"] = c.is_running()
            c.start_server()
            seen["second_port"] = factory.made[1].tcp_port
            seen["running_after_start"] = c.is_running()
            c.exit_clicked()

        controller.on_run_entry = on_entry
        return controller

    assert run_gui(factory, gui_maker=toggling_factory) == 0
    assert seen["running_after_stop"] is False
    assert seen["running_after_start"] is True
    assert len(factory.made) == 2
    assert factory.made[0] is not factory.made[1]
    # 첫 런타임의 포트는 반납됐고, 두 번째는 새로 열렸다.
    with pytest.raises(OSError):
        socket.create_connection((LOCALHOST, seen["first_port"]), timeout=1.0).close()
    assert factory.made[1].tcp_socket.fileno() == -1  # 종료 후엔 이쪽도 닫혀 있다


def test_a_failing_start_does_not_kill_the_window():
    factory = runtime_factory(host=UNBINDABLE_HOST)
    code = run_gui(factory)

    assert code == 0, "the window closed normally - a bind failure is shown, not fatal"
    controller = FakeGui.instances[0]
    assert len(controller.start_errors) == 1
    assert isinstance(controller.start_errors[0], OSError)
    assert controller.destroy_calls == 1


def test_gui_reports_the_live_connection_count_and_pin():
    factory = runtime_factory(pins="pin-%d")
    seen = {}

    def probing_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            seen["pin"] = c.pin_provider()
            seen["count0"] = c.count_provider()
            token = factory.registry.issue()
            seen["count1"] = c.count_provider()
            factory.registry.remove(token)
            seen["count2"] = c.count_provider()
            c.exit_clicked()

        controller.on_run_entry = on_entry
        return controller

    run_gui(factory, gui_maker=probing_factory)
    assert seen["pin"] == "pin-1"
    assert (seen["count0"], seen["count1"], seen["count2"]) == (0, 1, 0)


def test_gui_gets_the_configured_port():
    factory = runtime_factory()
    run_gui(factory)
    assert FakeGui.instances[0].port == 9000


# -- 트레이와의 공존 --------------------------------------------------------

def test_tray_runs_in_a_background_thread_next_to_the_window():
    factory = runtime_factory()
    seen = {}

    def probing_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            assert FakeTray.instances[0].started.wait(timeout=5)
            seen["tray_visible"] = c.tray_visible()
            seen["main_thread"] = threading.current_thread() is threading.main_thread()
            c.exit_clicked()

        controller.on_run_entry = on_entry
        return controller

    assert run_gui(factory, gui_maker=probing_factory, tray_maker=tray_factory) == 0
    assert seen["main_thread"] is True, "the window must own the main thread"
    assert seen["tray_visible"] is True
    assert FakeTray.instances[0].run_calls == 1
    assert FakeTray.instances[0].stop_calls >= 1, "the tray must be torn down on exit"


def test_without_a_tray_the_window_knows_it_cannot_hide():
    factory = runtime_factory()
    seen = {}

    def probing_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            seen["tray_visible"] = c.tray_visible()
            c.exit_clicked()

        controller.on_run_entry = on_entry
        return controller

    with patch.object(tray, "TRAY_IMPORT_ERROR", ImportError("no pystray")):
        run_gui(factory, gui_maker=probing_factory)
    assert seen["tray_visible"] is False
    assert FakeTray.instances == []


def test_tray_show_menu_is_wired_to_the_windows_thread_safe_entry_point():
    factory = runtime_factory()

    def probing_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            icon = FakeTray.instances[0]
            assert icon.started.wait(timeout=5)
            # 트레이 스레드에서 '창 열기' 를 누른 것과 같다.
            worker = threading.Thread(target=icon.on_show)
            worker.start()
            worker.join(timeout=2)
            deadline = time.monotonic() + 3
            while c.shows == 0 and time.monotonic() < deadline:
                time.sleep(0.01)
            c.exit_clicked()

        controller.on_run_entry = on_entry
        return controller

    run_gui(factory, gui_maker=probing_factory, tray_maker=tray_factory)
    assert FakeGui.instances[0].shows == 1


def test_tray_quit_shuts_the_server_down_and_closes_the_window():
    factory = runtime_factory()

    def probing_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            icon = FakeTray.instances[0]
            assert icon.started.wait(timeout=5)
            threading.Thread(target=icon.quit).start()
            # 창은 트레이의 요청(큐)으로 닫힌다 - 여기서는 아무것도 하지 않는다.

        controller.on_run_entry = on_entry
        return controller

    assert run_gui(factory, gui_maker=probing_factory, tray_maker=tray_factory) == 0
    assert factory.made[0].stop_event.is_set()
    assert factory.made[0].tcp_socket.fileno() == -1
    assert FakeGui.instances[0].destroy_calls == 1


def test_shutdown_runs_exactly_once_when_both_paths_fire():
    """트레이 '종료' 와 창 '종료' 가 겹쳐도 절차는 한 번만."""
    factory = runtime_factory()
    controller = factory.controller
    stops = []
    original_stop = server.ServerSupervisor.stop

    def counting_stop(self):
        result = original_stop(self)
        stops.append(result)
        return result

    def probing_factory(**kwargs):
        fake = FakeGui(**kwargs)

        def on_entry(c):
            icon = FakeTray.instances[0]
            assert icon.started.wait(timeout=5)
            t = threading.Thread(target=icon.quit)
            t.start()
            t.join(timeout=3)
            c.exit_clicked()

        fake.on_run_entry = on_entry
        return fake

    with patch.object(server.ServerSupervisor, "stop", counting_stop), \
            patch_send_input():
        controller.handle_event({"type": "DRAG_START"})
        run_gui(factory, gui_maker=probing_factory, tray_maker=tray_factory)

    assert stops.count(True) == 1, "the runtime must be stopped exactly once"


def test_a_crashing_tray_thread_does_not_take_the_window_down(capsys):
    factory = runtime_factory()

    def exploding_tray(**kwargs):
        icon = FakeTray(**kwargs)

        def boom(_i):
            raise RuntimeError("tray backend failed")

        icon.on_run_entry = boom
        return icon

    seen = {}

    def probing_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def on_entry(c):
            icon = FakeTray.instances[0]
            assert icon.started.wait(timeout=5)
            deadline = time.monotonic() + 3
            while c.tray_visible() and time.monotonic() < deadline:
                time.sleep(0.01)
            seen["tray_visible"] = c.tray_visible()
            seen["running"] = c.is_running()
            c.exit_clicked()

        controller.on_run_entry = on_entry
        return controller

    assert run_gui(factory, gui_maker=probing_factory, tray_maker=exploding_tray) == 0
    assert seen["tray_visible"] is False, "a dead tray must not be offered as a hiding place"
    assert seen["running"] is True, "the server keeps running when only the tray died"
    out = capsys.readouterr().out
    assert "[!] Tray icon stopped unexpectedly" in out
    assert out.isascii()


def test_window_exception_still_brings_the_server_down():
    factory = runtime_factory()

    def exploding_factory(**kwargs):
        controller = FakeGui(**kwargs)

        def boom(_c):
            raise RuntimeError("tk backend failed")

        controller.on_run_entry = boom
        return controller

    with pytest.raises(RuntimeError):
        run_gui(factory, gui_maker=exploding_factory)

    assert factory.made[0].stop_event.is_set()
    assert factory.made[0].tcp_socket.fileno() == -1


# ==========================================================================
# 3. main() 의 폴백 사슬: 창 -> 트레이 -> 콘솔
# ==========================================================================

def test_default_mode_is_the_window():
    with patch("server.run_with_gui", return_value=0) as with_gui, \
            patch("server.run_with_tray") as with_tray, \
            patch("server.run_console") as console, \
            patch("server.atexit.register"):
        assert server.main([]) == 0

    with_gui.assert_called_once()
    with_tray.assert_not_called()
    console.assert_not_called()
    assert with_gui.call_args.kwargs["port"] == server.TCP_PORT


def test_window_mode_falls_back_to_the_tray_without_tkinter(capsys):
    with patch("server.run_with_gui") as with_gui, \
            patch("server.run_with_tray", return_value=0) as with_tray, \
            patch("server.run_console") as console, \
            patch.object(gui, "GUI_IMPORT_ERROR", ImportError("No module named 'tkinter'")), \
            patch.object(tray, "TRAY_IMPORT_ERROR", None), \
            patch("server.atexit.register"):
        assert server.main([]) == 0

    with_gui.assert_not_called()
    with_tray.assert_called_once()
    console.assert_not_called()
    out = capsys.readouterr().out
    assert "tkinter not available" in out
    assert out.isascii()


def test_without_tkinter_and_without_pystray_it_is_console_mode(capsys):
    with patch("server.run_with_gui") as with_gui, \
            patch("server.run_with_tray") as with_tray, \
            patch("server.run_console", return_value=0) as console, \
            patch.object(gui, "GUI_IMPORT_ERROR", ImportError("no tkinter")), \
            patch.object(tray, "TRAY_IMPORT_ERROR", ImportError("no pystray")), \
            patch("server.atexit.register"):
        assert server.main([]) == 0

    with_gui.assert_not_called()
    with_tray.assert_not_called()
    console.assert_called_once()
    out = capsys.readouterr().out
    assert "tkinter not available" in out
    assert "pystray/Pillow not available" in out
    assert out.isascii()


def test_window_mode_without_pystray_warns_that_closing_quits(capsys):
    with patch("server.run_with_gui", return_value=0) as with_gui, \
            patch.object(gui, "GUI_IMPORT_ERROR", None), \
            patch.object(tray, "TRAY_IMPORT_ERROR", ImportError("no pystray")), \
            patch("server.atexit.register"):
        assert server.main([]) == 0

    with_gui.assert_called_once()
    out = capsys.readouterr().out
    assert "pystray/Pillow not available" in out
    assert "quit the server" in out
    assert out.isascii()


def test_no_tray_flag_still_means_plain_console_mode():
    """`--no-tray` 의 뜻이 '그래픽 UI 전부 끄기' 로 넓어졌다."""
    with patch("server.run_with_gui") as with_gui, \
            patch("server.run_with_tray") as with_tray, \
            patch("server.run_console", return_value=0) as console, \
            patch("server.atexit.register"):
        assert server.main(["--no-tray"]) == 0

    with_gui.assert_not_called()
    with_tray.assert_not_called()
    console.assert_called_once()


def test_no_new_cli_flag_was_added():
    args = server.parse_args([])
    assert not hasattr(args, "no_gui")
    assert not hasattr(args, "gui")
    assert (args.no_tray, args.allow_multiple, args.no_discovery) == (False, False, False)


# -- main() 이 넘기는 팩토리 -------------------------------------------------

def captured_factory(argv):
    with patch("server.run_with_gui", return_value=0) as with_gui, \
            patch("server.atexit.register"):
        assert server.main(argv) == 0
    return with_gui.call_args.args[2]


def test_main_passes_a_factory_that_builds_a_fresh_runtime_each_time(capsys):
    factory = captured_factory([])
    first, second = factory(), factory()
    capsys.readouterr()

    assert first is not second
    assert isinstance(first, server.ServerRuntime)
    assert first.requested_tcp_port == server.TCP_PORT
    assert first.requested_discovery_port == 9002
    assert first.single_client_guard is not None
    assert second.single_client_guard is not first.single_client_guard


def test_each_start_gets_a_new_pin_but_the_first_one_is_the_announced_one(capsys):
    factory = captured_factory([])
    announced = [
        l.split("PIN for this session: ")[1].strip()
        for l in capsys.readouterr().out.splitlines()
        if "PIN for this session" in l
    ]
    assert announced == [], "nothing is announced before the first runtime is built"

    first = factory()
    out = capsys.readouterr().out
    printed = [l for l in out.splitlines() if "PIN for this session" in l]
    assert len(printed) == 1
    assert printed[0] == f"[Server] PIN for this session: {first.expected_pin}"

    second = factory()
    capsys.readouterr()
    assert second.expected_pin != first.expected_pin
    assert len(second.expected_pin) == 6 and second.expected_pin.isdigit()


def test_a_fixed_pin_stays_the_same_across_restarts(capsys):
    factory = captured_factory(["--pin", "112233"])
    assert factory().expected_pin == "112233"
    assert factory().expected_pin == "112233"
    assert capsys.readouterr().out.count("PIN for this session: 112233") == 2


def test_no_auth_keeps_authentication_off_on_every_restart(capsys):
    factory = captured_factory(["--no-auth"])
    assert factory().expected_pin is None
    assert factory().expected_pin is None
    out = capsys.readouterr().out
    assert out.count("PIN authentication disabled") == 2
    assert "PIN for this session" not in out


def test_no_discovery_is_honoured_by_every_restart():
    factory = captured_factory(["--no-discovery"])
    assert factory().requested_discovery_port is None
    assert factory().requested_discovery_port is None


def test_a_blank_pin_is_rejected_before_any_window_opens():
    with patch("server.run_with_gui") as with_gui, \
            patch("server.run_with_tray") as with_tray, \
            patch("server.run_console") as console, \
            patch("server.atexit.register"):
        with pytest.raises(SystemExit):
            server.main(["--pin", "   "])

    with_gui.assert_not_called()
    with_tray.assert_not_called()
    console.assert_not_called()


# ==========================================================================
# 4. '정지' 는 이미 붙어 있는 클라이언트도 끊는다
# ==========================================================================
#
# `ServerRuntime.stop()` 은 리슨/UDP 소켓만 닫는다. 이미 맺어진 TCP 연결의
# 처리 스레드는 살아 있고, 폰의 heartbeat 가 미응답 카운터를 계속 리셋하므로
# 영원히 끊기지 않는다 - 그 상태에서 CLICK/DRAG 가 계속 실행되면 '정지' 버튼이
# 거짓말이 된다. 프로세스가 곧 끝나던 기존 모드에서는 드러날 수 없던 구멍이다.

def connected_client(runtime, timeout=3.0):
    """AUTH -> SESSION 까지 끝낸 실소켓 클라이언트와 세션 토큰."""
    import json
    from fake_conn import AUTH_LINE

    assert runtime.ready.wait(timeout=5)
    client = socket.create_connection((LOCALHOST, runtime.tcp_port), timeout=timeout)
    client.settimeout(timeout)
    client.sendall(AUTH_LINE)
    stream = client.makefile("r", encoding="utf-8")
    event = json.loads(stream.readline())
    assert event["type"] == "SESSION"
    return client, stream, event["session"]


def test_stop_disconnects_an_already_connected_client():
    import single_client

    factory = runtime_factory(single_client_guard=single_client.SingleClientGuard())
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    client, stream, session = connected_client(supervisor.runtime)
    try:
        assert factory.registry.is_active(session)
        supervisor.stop()
        # 앱은 '다른 기기에 밀림' 이 아니라 평범한 연결 유실(EOF)로 봐야 한다.
        assert stream.readline() == "", "the client must observe a clean EOF"
        deadline = time.monotonic() + 3.0
        while factory.registry.is_active(session) and time.monotonic() < deadline:
            time.sleep(0.01)
        assert not factory.registry.is_active(session), "the session must be revoked"
    finally:
        client.close()
        supervisor.stop()


def test_stop_does_not_tell_the_client_it_was_replaced():
    """`SESSION_REPLACED` 를 보내면 앱이 자동 재연결을 포기한다 - 정지는 그게 아니다."""
    import single_client

    factory = runtime_factory(single_client_guard=single_client.SingleClientGuard())
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    client, stream, _session = connected_client(supervisor.runtime)
    try:
        supervisor.stop()
        assert stream.readline() == ""
    finally:
        client.close()


def test_stop_releases_a_drag_held_by_the_disconnected_client():
    """정지 시점에 드래그 중이었으면 버튼이 눌린 채 남으면 안 된다."""
    import single_client

    controller = InputController()
    factory = runtime_factory(
        controller=controller, single_client_guard=single_client.SingleClientGuard()
    )
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    client, stream, _session = connected_client(supervisor.runtime)
    try:
        with patch_send_input() as mock_send:
            client.sendall(b'{"type":"DRAG_START"}\n')
            deadline = time.monotonic() + 3.0
            while not controller.drag_active and time.monotonic() < deadline:
                time.sleep(0.01)
            assert controller.drag_active is True
            mock_send.reset_mock()

            supervisor.stop()
            deadline = time.monotonic() + 3.0
            while controller.drag_active and time.monotonic() < deadline:
                time.sleep(0.01)
            assert controller.drag_active is False
            assert MOUSEEVENTF_LEFTUP in sent_flags(mock_send)
    finally:
        client.close()


def test_stop_without_a_guard_or_without_a_client_is_harmless():
    import single_client

    plain = runtime_factory()
    supervisor = server.ServerSupervisor(plain, join_timeout=2.0)
    supervisor.start()
    assert supervisor.stop() is True  # 가드 없는 런타임 - 기존과 동일

    guarded = runtime_factory(single_client_guard=single_client.SingleClientGuard())
    supervisor = server.ServerSupervisor(guarded, join_timeout=2.0)
    supervisor.start()
    assert supervisor.stop() is True  # 붙은 클라이언트 없음
    assert server.disconnect_active_client(guarded.made[0]) is False


def test_disconnect_active_client_survives_a_dead_socket(capsys):
    """이미 죽은 소켓이어도 정지 절차가 멈추면 안 된다."""
    import single_client

    class Broken:
        def settimeout(self, _t):
            raise OSError("socket is gone")

        def recv(self, _n):
            raise OSError("socket is gone")

        def shutdown(self, _how):
            raise OSError("socket is gone")

        def close(self):
            raise OSError("socket is gone")

    guard = single_client.SingleClientGuard()
    guard.take_over(Broken(), ("127.0.0.1", 1), "token")

    class Runtime:
        single_client_guard = guard

    assert server.disconnect_active_client(Runtime()) is True
    assert capsys.readouterr().out.isascii()


def test_a_restarted_server_accepts_the_client_again():
    """정지로 끊긴 폰은 '시작' 뒤 다시 붙을 수 있어야 한다 (재연결 경로)."""
    import single_client

    factory = runtime_factory(single_client_guard=single_client.SingleClientGuard())
    supervisor = server.ServerSupervisor(factory, join_timeout=2.0)
    supervisor.start()
    first_port = supervisor.runtime.tcp_port
    client, stream, _ = connected_client(supervisor.runtime)
    supervisor.stop()
    client.close()

    # 같은 포트로 다시 띄운다 (실제 서버는 항상 9000 이다).
    factory_again = runtime_factory(
        registry=factory.registry, tcp_port=first_port,
        single_client_guard=single_client.SingleClientGuard(),
    )
    supervisor = server.ServerSupervisor(factory_again, join_timeout=2.0)
    supervisor.start()
    try:
        client2, stream2, session2 = connected_client(supervisor.runtime)
        assert factory.registry.is_active(session2)
        client2.close()
    finally:
        supervisor.stop()
