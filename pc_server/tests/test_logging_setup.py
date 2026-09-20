"""windowed(--noconsole) 파일 로깅 (`logging_setup.py`) 테스트.

`sys.stdout`/`sys.stderr` 를 건드리는 테스트는 **반드시 원복**한다 - 안 하면
pytest 의 캡처가 망가져 뒤따르는 테스트가 통째로 이상해진다.
"""
import io
import os
import sys
from unittest.mock import patch

import pytest

import logging_setup
import server


class FakeStdio:
    """`configure_stdio(target=...)` 대역. sys 를 직접 건드리지 않기 위한 것."""

    def __init__(self, stdout=None, stderr=None):
        self.stdout = stdout
        self.stderr = stderr


@pytest.fixture(autouse=True)
def restore_real_stdio():
    """만에 하나 테스트가 진짜 sys 스트림을 바꿨어도 되돌린다."""
    real_out, real_err = sys.stdout, sys.stderr
    held_before = len(logging_setup._HELD_STREAMS)
    yield
    sys.stdout, sys.stderr = real_out, real_err
    for stream in logging_setup._HELD_STREAMS[held_before:]:
        try:
            stream.close()
        except Exception:
            pass
    del logging_setup._HELD_STREAMS[held_before:]


# --------------------------------------------------------------------------
# 1. is_windowed / 콘솔 실행은 무변경
# --------------------------------------------------------------------------

def test_is_windowed_detects_missing_streams():
    assert logging_setup.is_windowed(FakeStdio(stdout=None, stderr=None)) is True
    assert logging_setup.is_windowed(FakeStdio(stdout=io.StringIO(), stderr=None)) is True
    assert logging_setup.is_windowed(FakeStdio(stdout=None, stderr=io.StringIO())) is True


def test_is_windowed_false_with_a_console():
    assert logging_setup.is_windowed(FakeStdio(io.StringIO(), io.StringIO())) is False


def test_console_run_is_left_completely_alone(tmp_path):
    out, err = io.StringIO(), io.StringIO()
    target = FakeStdio(out, err)
    log_file = tmp_path / "server.log"

    setup = logging_setup.configure_stdio(path=str(log_file), target=target)

    assert setup == logging_setup.StdioSetup(windowed=False, redirected=False)
    assert target.stdout is out and target.stderr is err
    assert not log_file.exists(), "콘솔 실행에서는 로그 파일을 만들지도 않는다"


# --------------------------------------------------------------------------
# 2. windowed 리다이렉트
# --------------------------------------------------------------------------

def test_windowed_run_redirects_both_streams_to_the_log_file(tmp_path):
    log_file = tmp_path / "PhonePad" / "server.log"
    target = FakeStdio(None, None)

    setup = logging_setup.configure_stdio(path=str(log_file), target=target)

    assert setup.windowed is True
    assert setup.redirected is True
    assert setup.path == str(log_file)
    assert target.stdout is setup.stream and target.stderr is setup.stream

    print("[=] hello from stdout", file=target.stdout)
    print("[!] hello from stderr", file=target.stderr)
    setup.stream.close()

    text = log_file.read_text(encoding="utf-8")
    assert logging_setup.BANNER_PREFIX in text
    assert "[=] hello from stdout" in text
    assert "[!] hello from stderr" in text


def test_missing_directory_is_created(tmp_path):
    log_file = tmp_path / "deep" / "nested" / "server.log"
    setup = logging_setup.configure_stdio(path=str(log_file), target=FakeStdio(None, None))
    setup.stream.close()
    assert log_file.exists()


def test_log_stream_flushes_every_line(tmp_path):
    """프로세스가 갑자기 죽어도 마지막 줄까지 남아야 한다 (buffering=1)."""
    log_file = tmp_path / "server.log"
    target = FakeStdio(None, None)
    setup = logging_setup.configure_stdio(path=str(log_file), target=target)
    try:
        print("[!] crash marker", file=target.stdout)
        # close() / flush() 없이 바로 읽는다
        assert "[!] crash marker" in log_file.read_text(encoding="utf-8")
    finally:
        setup.stream.close()


def test_banner_is_ascii(tmp_path):
    log_file = tmp_path / "server.log"
    setup = logging_setup.configure_stdio(path=str(log_file), target=FakeStdio(None, None))
    setup.stream.close()
    assert log_file.read_text(encoding="utf-8").isascii()


# --------------------------------------------------------------------------
# 3. 회전
# --------------------------------------------------------------------------

def test_oversized_log_is_rotated_once(tmp_path):
    log_file = tmp_path / "server.log"
    log_file.write_text("x" * 120, encoding="utf-8")

    assert logging_setup.rotate_if_needed(str(log_file), max_bytes=100) is True

    rotated = tmp_path / ("server.log" + logging_setup.ROTATED_SUFFIX)
    assert rotated.read_text(encoding="utf-8") == "x" * 120
    assert not log_file.exists()


def test_small_log_is_not_rotated(tmp_path):
    log_file = tmp_path / "server.log"
    log_file.write_text("x" * 10, encoding="utf-8")
    assert logging_setup.rotate_if_needed(str(log_file), max_bytes=100) is False
    assert log_file.exists()


def test_rotation_replaces_the_previous_generation(tmp_path):
    log_file = tmp_path / "server.log"
    rotated = tmp_path / ("server.log" + logging_setup.ROTATED_SUFFIX)
    rotated.write_text("old generation", encoding="utf-8")
    log_file.write_text("y" * 120, encoding="utf-8")

    assert logging_setup.rotate_if_needed(str(log_file), max_bytes=100) is True
    assert rotated.read_text(encoding="utf-8") == "y" * 120


def test_missing_file_rotation_is_a_noop(tmp_path):
    assert logging_setup.rotate_if_needed(str(tmp_path / "nope.log")) is False


def test_configure_stdio_rotates_before_appending(tmp_path):
    log_file = tmp_path / "server.log"
    log_file.write_text("z" * 200, encoding="utf-8")
    target = FakeStdio(None, None)

    setup = logging_setup.configure_stdio(
        path=str(log_file), target=target,
        stream_opener=lambda p, max_bytes=None: logging_setup.open_log_stream(p, max_bytes=100),
    )
    setup.stream.close()

    assert (tmp_path / ("server.log" + logging_setup.ROTATED_SUFFIX)).exists()
    assert "z" not in log_file.read_text(encoding="utf-8")


# --------------------------------------------------------------------------
# 4. 열기 실패 폴백 - 서버가 죽으면 안 된다
# --------------------------------------------------------------------------

def test_unopenable_path_falls_back_to_devnull(tmp_path):
    target = FakeStdio(None, None)
    # 디렉터리 이름으로 파일을 열려고 하면 실패한다
    setup = logging_setup.configure_stdio(path=str(tmp_path), target=target)
    try:
        assert setup.windowed is True
        assert setup.redirected is True
        assert setup.path is None, "폴백이면 로그 경로를 보고하지 않는다"
        print("[!] swallowed", file=target.stdout)  # 예외가 나면 안 된다
    finally:
        if setup.stream is not None:
            setup.stream.close()


def test_devnull_failure_leaves_streams_untouched():
    target = FakeStdio(None, None)
    setup = logging_setup.configure_stdio(
        path="ignored", target=target, stream_opener=lambda p, **kw: (None, None)
    )
    assert setup == logging_setup.StdioSetup(windowed=True, redirected=False)
    assert target.stdout is None and target.stderr is None


def test_open_log_stream_reports_devnull_fallback(tmp_path):
    stream, path = logging_setup.open_log_stream(str(tmp_path))  # 디렉터리 = 열기 실패
    try:
        assert stream is not None
        assert path is None
        stream.write("discarded\n")  # 예외 없이 버려진다
    finally:
        stream.close()


# --------------------------------------------------------------------------
# 5. 기본 경로
# --------------------------------------------------------------------------

def test_default_path_uses_localappdata():
    path = logging_setup.default_log_path({"LOCALAPPDATA": r"C:\Users\x\AppData\Local"})
    assert path == os.path.join(r"C:\Users\x\AppData\Local", "PhonePad", "server.log")


def test_default_path_falls_back_to_appdata_then_temp():
    assert logging_setup.default_log_path({"APPDATA": r"C:\roaming"}) == os.path.join(
        r"C:\roaming", "PhonePad", "server.log")
    fallback = logging_setup.default_log_path({})
    assert fallback.endswith(os.path.join("PhonePad", "server.log"))


# --------------------------------------------------------------------------
# 6. server.main() 결합
# --------------------------------------------------------------------------

def test_main_configures_stdio_before_anything_else():
    order = []
    setup = logging_setup.StdioSetup(windowed=False, redirected=False)
    with patch("server.logging_setup.configure_stdio",
               side_effect=lambda *a, **k: (order.append("stdio"), setup)[1]), \
            patch("server.single_instance.enforce",
                  side_effect=lambda **k: order.append("guard")), \
            patch("server.run_console", side_effect=lambda *a, **k: order.append("run") or 0), \
            patch("server.atexit.register"):
        assert server.main(["--no-tray"]) == 0
    assert order == ["stdio", "guard", "run"]


def test_main_does_not_touch_stdio_in_console_mode(capsys):
    """pytest 실행(콘솔 있음)에서 main() 이 스트림을 바꾸면 안 된다."""
    before_out, before_err = sys.stdout, sys.stderr
    with patch("server.run_console", return_value=0), patch("server.atexit.register"):
        assert server.main(["--no-tray"]) == 0
    assert sys.stdout is before_out and sys.stderr is before_err
