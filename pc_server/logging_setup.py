"""windowed(--noconsole) 실행에서 print() 로그가 사라지지 않게 파일로 돌린다.

PyInstaller 의 `--noconsole` exe 와 `pythonw.exe` 에는 표준 스트림이 아예 없어서
`sys.stdout` / `sys.stderr` 가 `None` 이다. CPython 의 `print()` 는 이 상태에서
예외 없이 조용히 아무 일도 하지 않으므로(AGENTS.md 섹션 10 실측) 서버가 죽지는
않지만, 트레이 폴백 경고나 서버 사망 로그가 아무 데도 남지 않는다.

여기서는 코드 전반의 `print()` 호출을 `logging` 으로 갈아엎는 대신
`sys.stdout` / `sys.stderr` 자체를 로그 파일로 교체한다 - 기존 `print()` 가 그대로
파일에 쌓인다. **콘솔이 있는 일반 실행에서는 아무것도 바꾸지 않는다.**

로그 메시지는 ASCII 만 쓴다 (AGENTS.md 섹션 9).
"""
import os
import sys
import tempfile
import time
from dataclasses import dataclass
from typing import Optional

LOG_DIR_NAME = "PhonePad"
LOG_FILE_NAME = "server.log"
ROTATED_SUFFIX = ".1"

# 시작할 때 이 크기를 넘었으면 server.log -> server.log.1 로 한 번만 밀어낸다.
# 세대를 여러 개 두거나 실행 중에 크기를 감시하는 건 이 서버에 과하다.
MAX_LOG_BYTES = 1024 * 1024

BANNER_PREFIX = "[=] Phone Pad server log start"

# 교체한 스트림을 프로세스 수명 동안 붙들어 둔다(GC 로 닫히면 로그가 끊긴다).
_HELD_STREAMS = []


@dataclass(frozen=True)
class StdioSetup:
    """`configure_stdio()` 의 결과.

    `windowed` 는 "콘솔이 없는 실행이었는가"라서, 리다이렉트 성공 여부와 무관하게
    호출자가 UI 방식을 고를 때(예: 중복 실행 안내를 MessageBox 로 띄울지) 쓴다.
    """

    windowed: bool
    redirected: bool
    path: Optional[str] = None
    stream: object = None


def is_windowed(target=sys) -> bool:
    """콘솔이 없는 실행인가 (`--noconsole` exe / pythonw)."""
    return getattr(target, "stdout", None) is None or getattr(target, "stderr", None) is None


def default_log_path(env=None) -> str:
    """`%LOCALAPPDATA%\\PhonePad\\server.log`. LOCALAPPDATA 가 없으면 임시 디렉터리."""
    env = os.environ if env is None else env
    base = env.get("LOCALAPPDATA") or env.get("APPDATA") or tempfile.gettempdir()
    return os.path.join(base, LOG_DIR_NAME, LOG_FILE_NAME)


def rotate_if_needed(path: str, max_bytes: int = MAX_LOG_BYTES) -> bool:
    """로그가 너무 커졌으면 `<path>.1` 로 밀어낸다. 실패해도 예외를 던지지 않는다."""
    try:
        if os.path.getsize(path) <= max_bytes:
            return False
    except OSError:
        return False  # 파일이 없거나 볼 수 없다 - 회전할 것도 없다
    try:
        os.replace(path, path + ROTATED_SUFFIX)  # 기존 .1 은 덮어쓴다
        return True
    except OSError:
        return False


def open_log_stream(path: str, max_bytes: int = MAX_LOG_BYTES):
    """줄 단위 flush 되는 UTF-8 로그 스트림을 연다. 실패하면 os.devnull 로 폴백.

    반환: `(stream, actual_path)`. 폴백이면 `actual_path` 가 None,
    devnull 조차 못 열면 `(None, None)` - 어느 쪽이든 서버는 계속 떠야 한다.
    """
    try:
        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        rotate_if_needed(path, max_bytes)
        # buffering=1 = 줄 단위 flush. 프로세스가 갑자기 죽어도 마지막 줄까지 남는다.
        stream = open(path, "a", encoding="utf-8", errors="replace", buffering=1)
        return stream, path
    except OSError:
        pass
    try:
        return open(os.devnull, "w", encoding="utf-8"), None
    except OSError:
        return None, None


def configure_stdio(path=None, env=None, stream_opener=open_log_stream, target=sys) -> StdioSetup:
    """콘솔이 없으면 `sys.stdout`/`sys.stderr` 를 로그 파일로 교체한다.

    콘솔이 있으면 아무것도 하지 않고 `redirected=False` 를 반환한다.
    """
    if not is_windowed(target):
        return StdioSetup(windowed=False, redirected=False)

    log_path = default_log_path(env) if path is None else path
    stream, actual_path = stream_opener(log_path)
    if stream is None:
        # devnull 조차 못 열었다. 로그는 포기하고 서버는 그대로 띄운다.
        return StdioSetup(windowed=True, redirected=False)

    target.stdout = stream
    target.stderr = stream
    _HELD_STREAMS.append(stream)

    # print() 가 아니라 스트림에 직접 쓴다 - target 이 sys 가 아닐 수도 있다(테스트).
    try:
        stream.write("%s %s (pid %d)\n" % (
            BANNER_PREFIX, time.strftime("%Y-%m-%d %H:%M:%S"), os.getpid()))
    except (OSError, ValueError):
        pass

    return StdioSetup(windowed=True, redirected=True, path=actual_path, stream=stream)
