"""단일 인스턴스 가드 (`single_instance.py`) 테스트.

실제 Windows mutex 를 만드는 테스트는 **고유 이름**을 쓰고 반드시 닫는다 -
실행 중인 진짜 서버(`Local\\PhonePadServer`)나 다른 테스트와 충돌하면 안 된다.
"""
import os
import sys
import uuid
from unittest.mock import patch

import pytest

import server
import single_instance
from single_instance import Acquisition, MutexHandle


# --------------------------------------------------------------------------
# 주입 가능한 가짜 mutex factory
# --------------------------------------------------------------------------

def fake_factory(already_exists=False, handle=1234, unsupported=False):
    """`mutex_factory` 대역. 호출된 이름을 기록한다."""
    calls = []

    def factory(name):
        calls.append(name)
        if unsupported:
            return None
        return MutexHandle(handle=handle, already_exists=already_exists)

    factory.calls = calls
    return factory


@pytest.fixture(autouse=True)
def clean_held_handles():
    """모듈 전역 `_HELD` 가 테스트 사이에 새지 않게 한다."""
    before = dict(single_instance._HELD)
    yield
    for name in list(single_instance._HELD):
        if name not in before:
            single_instance._HELD.pop(name, None)


def unique_name(prefix="test"):
    return "Local\\PhonePadServer-%s-%s" % (prefix, uuid.uuid4().hex)


# --------------------------------------------------------------------------
# 1. acquire()
# --------------------------------------------------------------------------

def test_first_acquire_succeeds_and_holds_the_handle():
    name = unique_name()
    factory = fake_factory(already_exists=False, handle=777)

    result = single_instance.acquire(name=name, mutex_factory=factory)

    assert result == Acquisition(name=name, supported=True, acquired=True)
    assert result.already_running is False
    assert factory.calls == [name]
    # 핸들을 프로세스 수명 동안 붙들고 있어야 한다 (GC 되면 mutex 가 풀린다)
    assert single_instance._HELD[name] == 777


def test_acquire_reports_already_running_when_mutex_exists():
    name = unique_name()
    factory = fake_factory(already_exists=True)

    result = single_instance.acquire(name=name, mutex_factory=factory)

    assert result.supported is True
    assert result.acquired is False
    assert result.already_running is True
    assert name not in single_instance._HELD  # 남의 mutex 를 붙들지 않는다


def test_acquire_is_idempotent_within_the_same_process():
    """같은 프로세스에서 두 번째 호출이 자기 자신을 '중복'으로 오판하면 안 된다."""
    name = unique_name()
    factory = fake_factory(already_exists=False)

    first = single_instance.acquire(name=name, mutex_factory=factory)
    second = single_instance.acquire(name=name, mutex_factory=factory)

    assert first.acquired is True
    assert second.acquired is True
    assert factory.calls == [name], "이미 잡고 있으면 CreateMutexW 를 다시 부르지 않는다"


def test_acquire_marks_unsupported_when_factory_returns_none():
    name = unique_name()
    factory = fake_factory(unsupported=True)

    result = single_instance.acquire(name=name, mutex_factory=factory)

    assert result.supported is False
    assert result.acquired is True, "가드를 쓸 수 없으면 서버는 그대로 떠야 한다"
    assert result.already_running is False


def test_acquire_uses_module_default_name():
    factory = fake_factory()
    with patch.object(single_instance, "MUTEX_NAME", "Local\\PhonePadServer-default-test"):
        single_instance.acquire(mutex_factory=factory)
    assert factory.calls == ["Local\\PhonePadServer-default-test"]
    single_instance._HELD.pop("Local\\PhonePadServer-default-test", None)


# --------------------------------------------------------------------------
# 2. enforce() - 진입점 가드
# --------------------------------------------------------------------------

def test_enforce_returns_none_on_first_run():
    assert single_instance.enforce(
        name=unique_name(), mutex_factory=fake_factory(already_exists=False)
    ) is None


def test_enforce_exits_with_code_2_when_already_running():
    logged = []
    assert single_instance.enforce(
        name=unique_name(),
        mutex_factory=fake_factory(already_exists=True),
        log=logged.append,
    ) == 2
    assert single_instance.EXIT_ALREADY_RUNNING == 2
    assert len(logged) == 1
    assert "already running" in logged[0]


def test_enforce_log_line_is_ascii():
    """cp949 콘솔에서 UnicodeEncodeError 가 나면 안 된다 (AGENTS.md 섹션 9)."""
    logged = []
    single_instance.enforce(
        name=unique_name(), mutex_factory=fake_factory(already_exists=True), log=logged.append
    )
    assert logged[0].isascii()
    assert single_instance.ALREADY_RUNNING_LOG.isascii()


def test_console_mode_does_not_open_a_message_box():
    boxes = []
    single_instance.enforce(
        name=unique_name(),
        windowed=False,
        mutex_factory=fake_factory(already_exists=True),
        message_box=lambda text, title: boxes.append((text, title)),
        log=lambda _m: None,
    )
    assert boxes == [], "콘솔이 있으면 stderr 한 줄로 끝낸다"


def test_windowed_mode_shows_a_message_box():
    boxes = []
    code = single_instance.enforce(
        name=unique_name(),
        windowed=True,
        mutex_factory=fake_factory(already_exists=True),
        message_box=lambda text, title: boxes.append((text, title)),
        log=lambda _m: None,
    )
    assert code == 2
    assert len(boxes) == 1
    text, title = boxes[0]
    assert "이미 실행 중" in text
    assert title == single_instance.DIALOG_TITLE


def test_message_box_failure_still_exits_with_code_2():
    logged = []

    def exploding_box(_text, _title):
        raise OSError("user32 unavailable")

    code = single_instance.enforce(
        name=unique_name(),
        windowed=True,
        mutex_factory=fake_factory(already_exists=True),
        message_box=exploding_box,
        log=logged.append,
    )
    assert code == 2
    assert any("Failed to show" in line for line in logged)


def test_enforce_skips_guard_when_windll_is_unavailable():
    """비Windows / ctypes.windll 부재 - 가드를 건너뛰고 서버를 띄운다."""
    boxes = []
    assert single_instance.enforce(
        name=unique_name(),
        windowed=True,
        mutex_factory=fake_factory(unsupported=True),
        message_box=lambda text, title: boxes.append(1),
    ) is None
    assert boxes == []


def test_allow_multiple_skips_the_guard_entirely():
    factory = fake_factory(already_exists=True)
    assert single_instance.enforce(
        allow_multiple=True, name=unique_name(), mutex_factory=factory
    ) is None
    assert factory.calls == [], "--allow-multiple 이면 mutex 를 만들지도 않는다"


def test_default_log_goes_to_stderr(capsys):
    single_instance.enforce(
        name=unique_name(), mutex_factory=fake_factory(already_exists=True)
    )
    captured = capsys.readouterr()
    assert "already running" in captured.err
    assert captured.out == ""


# --------------------------------------------------------------------------
# 3. 실제 Win32 경로 (고유 이름 + 정리)
# --------------------------------------------------------------------------

@pytest.mark.skipif(os.name != "nt", reason="Windows named mutex 전용")
def test_real_create_mutex_detects_an_existing_name():
    """진짜 CreateMutexW 가 ERROR_ALREADY_EXISTS 를 보고하는지 확인한다.

    같은 프로세스에서 두 번 만들어도 Windows 는 커널 오브젝트 기준으로 판정하므로
    두 번째는 `already_exists=True` 가 된다 (프로세스 간 판정과 같은 신호).
    프로세스 간 실동작은 빌드된 exe 를 두 번 실행해 별도로 실측했다.
    """
    name = unique_name("real")
    first = single_instance.create_windows_mutex(name)
    assert first is not None
    try:
        assert first.already_exists is False
        second = single_instance.create_windows_mutex(name)
        assert second is not None
        try:
            assert second.already_exists is True
        finally:
            single_instance.close_handle(second.handle)
    finally:
        single_instance.close_handle(first.handle)


@pytest.mark.skipif(os.name != "nt", reason="Windows named mutex 전용")
def test_release_closes_a_held_handle():
    name = unique_name("release")
    result = single_instance.acquire(name=name)
    assert result.acquired is True
    assert single_instance.release(name) is True
    assert name not in single_instance._HELD
    assert single_instance.release(name) is False  # 두 번 놓아도 예외 없음


def test_create_windows_mutex_returns_none_without_windll():
    with patch.object(single_instance, "_load_kernel32", return_value=(None, None)):
        assert single_instance.create_windows_mutex("Local\\whatever") is None
        assert single_instance.close_handle(1) is False


def test_show_message_box_returns_false_without_windll():
    fake_ctypes = type("FakeCtypes", (), {})()  # windll 속성 없음
    with patch.dict(sys.modules, {"ctypes": fake_ctypes}):
        assert single_instance.show_message_box("t", "T") is False


# --------------------------------------------------------------------------
# 4. server.main() 결합
# --------------------------------------------------------------------------

def test_parse_args_defaults_to_single_instance_guard():
    assert server.parse_args([]).allow_multiple is False


def test_parse_args_accepts_allow_multiple():
    assert server.parse_args(["--allow-multiple"]).allow_multiple is True


def test_main_exits_with_2_and_never_starts_the_server_when_already_running():
    with patch("server.run_console") as console, \
            patch("server.run_with_tray") as with_tray, \
            patch("server.atexit.register"), \
            patch("server.single_instance.enforce", return_value=2) as enforce:
        assert server.main(["--no-tray"]) == 2
    enforce.assert_called_once()
    console.assert_not_called()
    with_tray.assert_not_called()


def test_main_passes_windowed_flag_from_logging_setup_to_the_guard():
    """windowed 판정은 로그 리다이렉트 *이전* 값이어야 한다 - 안내 방식이 갈린다."""
    setup = server.logging_setup.StdioSetup(windowed=True, redirected=True, path="x")
    with patch("server.run_console", return_value=0), \
            patch("server.atexit.register"), \
            patch("server.logging_setup.configure_stdio", return_value=setup), \
            patch("server.single_instance.enforce", return_value=None) as enforce:
        assert server.main(["--no-tray"]) == 0
    assert enforce.call_args.kwargs["windowed"] is True


def test_main_forwards_allow_multiple_to_the_guard():
    with patch("server.run_console", return_value=0), \
            patch("server.atexit.register"), \
            patch("server.single_instance.enforce", return_value=None) as enforce:
        assert server.main(["--no-tray", "--allow-multiple"]) == 0
    assert enforce.call_args.kwargs["allow_multiple"] is True


def test_main_runs_the_real_guard_and_starts_the_server():
    """가드를 모킹하지 않고 실제 경로를 탄다 (conftest 가 이름만 격리한다)."""
    with patch("server.run_console", return_value=0) as console, \
            patch("server.atexit.register"):
        assert server.main(["--no-tray"]) == 0
    console.assert_called_once()
