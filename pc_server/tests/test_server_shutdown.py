"""정지 가능한 서버 + 트레이 연동 종료 경로 테스트.

여기서 쓰는 소켓은 전부 **포트 0 바인딩**이다 - 고정 포트(9000/9001)를 쓰면
다른 프로세스가 그 포트를 잡고 있을 때 테스트가 무관하게 깨진다.
"""
import ctypes
import json
import socket
import threading
from unittest.mock import patch

import pytest

import server
import tray
from send_input_stub import patch_send_input
from input_controller import INPUT, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, InputController

LOCALHOST = "127.0.0.1"
# 이 머신에 없는 주소 - bind 가 실제로 OSError 를 던진다(모킹 없이 '기동 실패' 재현).
# Windows 에서는 SO_REUSEADDR 때문에 "이미 점유된 포트"로는 bind 가 실패하지 않아
# 포트 충돌 대신 이 방법을 쓴다.
UNBINDABLE_HOST = "203.0.113.1"


def make_runtime(controller=None, registry=None, host=LOCALHOST, **kwargs):
    kwargs.setdefault("tcp_port", 0)
    kwargs.setdefault("udp_port", 0)
    kwargs.setdefault("accept_timeout", 0.05)
    return server.ServerRuntime(
        controller if controller is not None else InputController(),
        registry if registry is not None else server.SessionRegistry(),
        host=host,
        **kwargs,
    )


def start(runtime):
    """runtime 을 백그라운드에서 띄우고 accept 루프 진입까지 기다린다."""
    thread = threading.Thread(target=runtime.run, daemon=True)
    thread.start()
    assert runtime.ready.wait(timeout=5), "server never became ready"
    return thread


def sent_flags(mock_send):
    flags = []
    for call in mock_send.call_args_list:
        count, inputs, size = call.args
        assert size == ctypes.sizeof(INPUT)
        flags.extend(inputs[i]._input.mi.dwFlags for i in range(count))
    return flags


# --------------------------------------------------------------------------
# 3. 정지 가능한 서버
# --------------------------------------------------------------------------

def test_runtime_binds_ephemeral_ports_and_reports_them():
    runtime = make_runtime()
    runtime.bind()
    try:
        assert runtime.tcp_port not in (None, 0)
        assert runtime.udp_port not in (None, 0)
        assert runtime.tcp_port != runtime.udp_port
    finally:
        runtime.close()


def test_stop_returns_from_accept_loop_quickly():
    runtime = make_runtime(accept_timeout=0.05)
    thread = start(runtime)

    runtime.stop()
    thread.join(timeout=2.0)

    assert not thread.is_alive(), "accept loop did not return after stop"


def test_stop_closes_listening_and_udp_sockets():
    runtime = make_runtime()
    thread = start(runtime)
    runtime.stop()
    thread.join(timeout=2.0)

    assert runtime.tcp_socket.fileno() == -1
    assert runtime.udp_socket.fileno() == -1


def test_port_is_released_after_stop():
    runtime = make_runtime()
    thread = start(runtime)
    port = runtime.tcp_port
    runtime.stop()
    thread.join(timeout=2.0)

    with pytest.raises(OSError):
        socket.create_connection((LOCALHOST, port), timeout=1.0).close()


def test_same_port_can_be_bound_again_after_stop():
    runtime = make_runtime()
    thread = start(runtime)
    tcp_port, udp_port = runtime.tcp_port, runtime.udp_port
    runtime.stop()
    thread.join(timeout=2.0)

    again = make_runtime(tcp_port=tcp_port, udp_port=udp_port)
    again.bind()  # 예외가 나면 포트가 안 풀린 것
    try:
        assert again.tcp_port == tcp_port
    finally:
        again.close()


def test_running_server_serves_a_real_client_then_stops():
    """정지 가능하게 바꾼 뒤에도 기존 핸드셰이크가 그대로 동작하는지."""
    registry = server.SessionRegistry()
    runtime = make_runtime(registry=registry)
    thread = start(runtime)
    try:
        with socket.create_connection((LOCALHOST, runtime.tcp_port), timeout=2.0) as client:
            client.settimeout(2.0)
            line = client.makefile("r", encoding="utf-8").readline()
            event = json.loads(line)
            assert event["type"] == "SESSION"
            assert registry.is_active(event["session"])
    finally:
        runtime.stop()
        thread.join(timeout=2.0)
    assert not thread.is_alive()


def test_stop_before_serve_exits_immediately():
    runtime = make_runtime()
    runtime.bind()
    runtime.stop()
    thread = threading.Thread(target=runtime.serve, daemon=True)
    thread.start()
    thread.join(timeout=2.0)
    assert not thread.is_alive()
    assert runtime.tcp_socket.fileno() == -1


def test_close_is_idempotent():
    runtime = make_runtime()
    runtime.bind()
    runtime.close()
    runtime.close()  # 두 번째 호출이 터지면 안 된다
    assert runtime.tcp_socket.fileno() == -1


def test_bind_failure_leaves_no_open_udp_socket():
    """TCP 바인드가 실패하면 먼저 연 UDP 소켓도 닫아야 한다."""
    runtime = make_runtime(host=LOCALHOST)
    original_bind = socket.socket.bind
    calls = []

    def flaky_bind(self, address):
        calls.append(self.type)
        if self.type == socket.SOCK_STREAM:
            raise OSError("simulated TCP bind failure")
        return original_bind(self, address)

    with patch.object(socket.socket, "bind", flaky_bind):
        with pytest.raises(OSError):
            runtime.bind()

    assert socket.SOCK_DGRAM in calls
    assert runtime.tcp_socket is None and runtime.udp_socket is None


# --------------------------------------------------------------------------
# 4. 종료 시 드래그 해제 + atexit 안전장치
# --------------------------------------------------------------------------

def test_release_drag_releases_an_active_drag():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        assert controller.drag_active is True
        assert server.release_drag(controller, "shutdown") is True
        assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]
    assert controller.drag_active is False


def test_release_drag_is_idempotent():
    """정상 종료 경로와 atexit 가 둘 다 호출해도 LEFTUP 은 한 번만 나간다."""
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.reset_mock()
        assert server.release_drag(controller, "shutdown") is True
        assert server.release_drag(controller, "atexit") is False
        assert server.release_drag(controller, "atexit") is False
        assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTUP]


def test_release_drag_without_active_drag_sends_nothing():
    controller = InputController()
    with patch_send_input() as mock_send:
        assert server.release_drag(controller) is False
    mock_send.assert_not_called()


def test_release_drag_swallows_send_input_failure():
    controller = InputController()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.side_effect = OSError("SendInput failed")
        assert server.release_drag(controller, "shutdown") is False  # 예외가 새지 않는다


def test_main_registers_atexit_drag_release():
    with patch("server.atexit.register") as mock_register, \
            patch("server.run_console", return_value=0):
        assert server.main(["--no-tray"]) == 0

    registered = [c for c in mock_register.call_args_list if c.args[0] is server.release_drag]
    assert len(registered) == 1
    controller = registered[0].args[1]
    assert isinstance(controller, InputController)


def test_atexit_registered_callable_actually_releases_the_drag():
    """등록된 그 callable 을 그대로 실행하면 버튼이 놓인다."""
    with patch("server.atexit.register") as mock_register, \
            patch("server.run_console", return_value=0):
        server.main(["--no-tray"])

    call = next(c for c in mock_register.call_args_list if c.args[0] is server.release_drag)
    func, controller, reason = call.args[0], call.args[1], call.args[2]

    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.reset_mock()
        func(controller, reason)
        assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTUP]


# --------------------------------------------------------------------------
# 트레이 종료 경로 (가짜 트레이 컨트롤러)
# --------------------------------------------------------------------------

class FakeTrayController:
    """tray.TrayController 대역. run() 은 stop() 될 때까지 블로킹한다."""

    instances = []

    def __init__(self, count_provider, on_quit, port):
        self.count_provider = count_provider
        self.on_quit = on_quit
        self.port = port
        self.run_calls = 0
        self.stop_calls = 0
        self.on_run_entry = None
        self._released = threading.Event()
        FakeTrayController.instances.append(self)

    def run(self):
        self.run_calls += 1
        if self.on_run_entry is not None:
            self.on_run_entry(self)
        assert self._released.wait(timeout=10), "fake tray was never stopped"

    def stop(self):
        self.stop_calls += 1
        self._released.set()

    def quit(self):
        """메뉴의 '종료' 와 같은 동작."""
        try:
            self.on_quit()
        finally:
            self.stop()


@pytest.fixture(autouse=True)
def _clear_fake_trays():
    FakeTrayController.instances.clear()
    yield
    FakeTrayController.instances.clear()


def tray_factory(**kwargs):
    return FakeTrayController(**kwargs)


def quitting_tray_factory(**kwargs):
    """생성되자마자 run() 안에서 '종료'를 누르는 트레이."""
    ctl = FakeTrayController(**kwargs)
    ctl.on_run_entry = lambda c: c.quit()
    return ctl


def test_tray_quit_stops_server_and_returns_zero():
    registry = server.SessionRegistry()
    controller = InputController()
    runtime = make_runtime(controller=controller, registry=registry)

    code = server.run_with_tray(
        controller, registry, runtime=runtime, tray_factory=quitting_tray_factory
    )

    assert code == 0
    assert runtime.stop_event.is_set()
    assert runtime.tcp_socket.fileno() == -1
    assert FakeTrayController.instances[0].stop_calls >= 1


def test_tray_quit_while_dragging_releases_the_button():
    """드래그 중 트레이 '종료' -> PC 버튼이 눌린 채 남지 않아야 한다."""
    registry = server.SessionRegistry()
    controller = InputController()
    runtime = make_runtime(controller=controller, registry=registry)

    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        assert controller.drag_active is True
        mock_send.reset_mock()
        server.run_with_tray(
            controller, registry, runtime=runtime, tray_factory=quitting_tray_factory
        )

    assert controller.drag_active is False
    assert MOUSEEVENTF_LEFTUP in sent_flags(mock_send)


def test_tray_reports_live_connection_count_from_registry():
    registry = server.SessionRegistry()
    controller = InputController()
    runtime = make_runtime(controller=controller, registry=registry)
    seen = []

    def peeking_factory(**kwargs):
        ctl = FakeTrayController(**kwargs)

        def on_entry(c):
            seen.append(c.count_provider())
            token = registry.issue()
            seen.append(c.count_provider())
            registry.remove(token)
            seen.append(c.count_provider())
            c.quit()

        ctl.on_run_entry = on_entry
        return ctl

    server.run_with_tray(controller, registry, runtime=runtime, tray_factory=peeking_factory)
    assert seen == [0, 1, 0]


def test_tray_menu_uses_the_configured_tcp_port():
    registry = server.SessionRegistry()
    controller = InputController()
    runtime = make_runtime(controller=controller, registry=registry, tcp_port=0)

    server.run_with_tray(
        controller, registry, runtime=runtime, tray_factory=quitting_tray_factory
    )
    assert FakeTrayController.instances[0].port == runtime.requested_tcp_port


# --------------------------------------------------------------------------
# 5. 서버 스레드 사망 -> 좀비 아이콘 금지 + 비정상 종료 코드
# --------------------------------------------------------------------------

def test_server_bind_failure_stops_tray_and_returns_nonzero(capsys):
    registry = server.SessionRegistry()
    controller = InputController()
    # 실제로 bind 가 실패하는 구성 (모킹 없음)
    runtime = make_runtime(controller=controller, registry=registry, host=UNBINDABLE_HOST)

    code = server.run_with_tray(
        controller, registry, runtime=runtime, tray_factory=tray_factory
    )

    assert code == 1, "a dead server thread must not exit successfully"
    tray_ctl = FakeTrayController.instances[0]
    assert tray_ctl.run_calls == 1
    assert tray_ctl.stop_calls >= 1, "tray must be torn down when the server dies"

    out = capsys.readouterr().out
    assert "Server stopped unexpectedly" in out


def test_server_bind_failure_is_logged_in_ascii(capsys):
    """cp949 콘솔에서 UnicodeEncodeError 를 내지 않도록 로그는 ASCII 만."""
    registry = server.SessionRegistry()
    controller = InputController()
    runtime = make_runtime(controller=controller, registry=registry, host=UNBINDABLE_HOST)

    server.run_with_tray(controller, registry, runtime=runtime, tray_factory=tray_factory)

    out = capsys.readouterr().out
    ascii_only = "".join(ch for ch in out if ch.isascii())
    # OS 가 준 오류 메시지(한글일 수 있음)를 뺀 서버 자체 문구는 ASCII 여야 한다
    assert "[!] Server stopped unexpectedly" in ascii_only


def test_run_console_returns_nonzero_when_bind_fails(capsys):
    controller = InputController()
    registry = server.SessionRegistry()
    runtime = make_runtime(controller=controller, registry=registry, host=UNBINDABLE_HOST)

    assert server.run_console(controller, registry, runtime=runtime) == 1
    assert "Server failed to start" in capsys.readouterr().out


def test_tray_exception_also_brings_the_server_down():
    """트레이 쪽이 터져도 서버 스레드/소켓이 남으면 안 된다."""
    registry = server.SessionRegistry()
    controller = InputController()
    runtime = make_runtime(controller=controller, registry=registry)

    def exploding_factory(**kwargs):
        ctl = FakeTrayController(**kwargs)

        def boom(_c):
            raise RuntimeError("tray backend failed")

        ctl.on_run_entry = boom
        return ctl

    with pytest.raises(RuntimeError):
        server.run_with_tray(
            controller, registry, runtime=runtime, tray_factory=exploding_factory
        )

    assert runtime.stop_event.is_set()
    assert runtime.tcp_socket.fileno() == -1


# --------------------------------------------------------------------------
# 6. --no-tray / 미설치 폴백
# --------------------------------------------------------------------------

def test_parse_args_defaults_to_tray():
    assert server.parse_args([]).no_tray is False


def test_parse_args_accepts_no_tray():
    assert server.parse_args(["--no-tray"]).no_tray is True


def test_no_tray_flag_runs_console_mode():
    with patch("server.run_console", return_value=0) as console, \
            patch("server.run_with_tray") as with_tray, \
            patch("server.atexit.register"):
        assert server.main(["--no-tray"]) == 0
    console.assert_called_once()
    with_tray.assert_not_called()


def test_tray_mode_is_used_when_dependencies_are_available():
    with patch("server.run_console") as console, \
            patch("server.run_with_tray", return_value=0) as with_tray, \
            patch.object(tray, "TRAY_IMPORT_ERROR", None), \
            patch("server.atexit.register"):
        assert server.main([]) == 0
    with_tray.assert_called_once()
    console.assert_not_called()


def test_missing_pystray_falls_back_to_console_with_one_warning(capsys):
    fake_error = ImportError("No module named 'pystray'")
    with patch("server.run_console", return_value=0) as console, \
            patch("server.run_with_tray") as with_tray, \
            patch.object(tray, "TRAY_IMPORT_ERROR", fake_error), \
            patch("server.atexit.register"):
        assert server.main([]) == 0

    console.assert_called_once()
    with_tray.assert_not_called()
    out = capsys.readouterr().out
    warnings = [l for l in out.splitlines() if "pystray/Pillow not available" in l]
    assert len(warnings) == 1
    assert out.isascii(), "startup warning must stay ASCII for cp949 consoles"


def test_console_mode_serves_and_stops_without_a_tray():
    """--no-tray 경로가 기존과 동일하게 동작하는지 (실제 소켓)."""
    registry = server.SessionRegistry()
    controller = InputController()
    runtime = make_runtime(controller=controller, registry=registry)
    result = {}

    thread = threading.Thread(
        target=lambda: result.update(code=server.run_console(controller, registry, runtime)),
        daemon=True,
    )
    thread.start()
    assert runtime.ready.wait(timeout=5)

    with socket.create_connection((LOCALHOST, runtime.tcp_port), timeout=2.0) as client:
        client.settimeout(2.0)
        event = json.loads(client.makefile("r", encoding="utf-8").readline())
        assert event["type"] == "SESSION"

    runtime.stop()
    thread.join(timeout=3.0)
    assert not thread.is_alive()
    assert result.get("code") == 0


def test_console_mode_keyboard_interrupt_releases_drag(capsys):
    controller = InputController()
    registry = server.SessionRegistry()

    class InterruptingRuntime:
        stop_event = threading.Event()

        def run(self):
            raise KeyboardInterrupt()

        def stop(self):
            self.stop_event.set()

        def close(self):
            pass

    runtime = InterruptingRuntime()
    with patch_send_input() as mock_send:
        controller.handle_event({"type": "DRAG_START"})
        mock_send.reset_mock()
        assert server.run_console(controller, registry, runtime=runtime) == 0
        assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTUP]

    assert runtime.stop_event.is_set()
    assert "Interrupted" in capsys.readouterr().out


# --------------------------------------------------------------------------
# 회귀: 기존 공개 API 가 그대로인지
# --------------------------------------------------------------------------

def test_existing_entry_points_keep_their_signatures():
    import inspect

    assert list(inspect.signature(server.handle_client).parameters) == [
        "conn", "addr", "controller", "registry",
    ]
    assert list(inspect.signature(server.handle_udp_packet).parameters) == [
        "data", "controller", "registry",
    ]
    udp_params = inspect.signature(server.udp_listener).parameters
    assert list(udp_params) == ["sock", "controller", "registry", "stop_event"]
    assert udp_params["stop_event"].default is None


def test_default_ports_are_unchanged():
    assert server.TCP_PORT == 9000
    assert server.UDP_PORT == 9001
    runtime = server.ServerRuntime(InputController(), server.SessionRegistry())
    assert runtime.requested_tcp_port == 9000
    assert runtime.requested_udp_port == 9001
    assert runtime.host == "0.0.0.0"
