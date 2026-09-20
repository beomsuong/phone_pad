"""서버 중복 실행 방지 - Windows named mutex 기반 단일 인스턴스 가드.

왜 소켓이 아니라 mutex 인가 (AGENTS.md 섹션 10 "서버 중복 실행"):
리슨 소켓에 `SO_REUSEADDR` 가 걸려 있는데 Windows 에서는 이 옵션이 **이미 점유된
포트에도 bind 를 성공시킨다**(실측). 그래서 서버를 두 번 띄우면 트레이 아이콘이
2개 뜨고 한쪽만 트래픽을 받는다. 소켓 옵션을 바꾸면 재시작 시 TIME_WAIT 재바인드
같은 기존 동작이 함께 바뀌므로, **소켓은 그대로 두고 프로세스 단위로** 막는다.

이름은 `Local\\PhonePadServer` - `Local\\` 네임스페이스라 로그인 세션 안에서만
유효하다. 다른 사용자가 자기 세션에서 띄운 서버와는 충돌하지 않는다.

`ctypes.windll` 이 없는 환경(리눅스 CI 등)에서는 가드를 통째로 건너뛴다.
"""
import sys
from dataclasses import dataclass
from typing import Optional

MUTEX_NAME = "Local\\PhonePadServer"

#: 이미 다른 인스턴스가 떠 있을 때의 프로세스 종료 코드.
EXIT_ALREADY_RUNNING = 2

ERROR_ALREADY_EXISTS = 183

MB_OK = 0x00000000
MB_ICONWARNING = 0x00000030
MB_SETFOREGROUND = 0x00010000

DIALOG_TITLE = "Phone Pad"
DIALOG_TEXT = (
    "Phone Pad 서버가 이미 실행 중입니다.\n\n"
    "작업 표시줄의 트레이 아이콘을 확인하세요."
)

# 로그는 ASCII 만 (AGENTS.md 섹션 9 - cp949 콘솔에서 UnicodeEncodeError 전례).
# 다이얼로그 문구는 MessageBoxW(와이드 문자 API)로만 나가므로 한글이어도 안전하다.
ALREADY_RUNNING_LOG = (
    "[!] Another Phone Pad server instance is already running - exiting (code %d)"
    % EXIT_ALREADY_RUNNING
)

# 획득한 mutex 핸들을 프로세스 수명 동안 붙들어 둔다.
# 지역 변수로 두면 GC 후 핸들이 닫히면서 mutex 가 풀려 가드가 무력화된다.
_HELD = {}


@dataclass(frozen=True)
class MutexHandle:
    """`mutex_factory` 가 돌려주는 값. `already_exists` 는 `ERROR_ALREADY_EXISTS`."""

    handle: object
    already_exists: bool


@dataclass(frozen=True)
class Acquisition:
    name: str
    supported: bool  # Windows named mutex 를 실제로 쓸 수 있었는가
    acquired: bool   # 이 프로세스가 단독 인스턴스로 판정됐는가

    @property
    def already_running(self) -> bool:
        return self.supported and not self.acquired


def _load_kernel32():
    """`(ctypes, kernel32)` 또는 쓸 수 없으면 `(None, None)`."""
    try:
        import ctypes
    except ImportError:  # pragma: no cover - 표준 라이브러리라 사실상 불가
        return None, None
    if not hasattr(ctypes, "windll"):
        return None, None  # 비Windows
    try:
        # use_last_error=True 여야 ctypes.get_last_error() 가 이 호출의 결과를 본다.
        # 그냥 ctypes.windll.kernel32 를 쓰면 ctypes 내부 호출이 끼어들어 값이 흐려질 수 있다.
        return ctypes, ctypes.WinDLL("kernel32", use_last_error=True)
    except (OSError, AttributeError):  # pragma: no cover
        return None, None


def create_windows_mutex(name: str) -> Optional[MutexHandle]:
    """named mutex 를 만든다. Windows 가 아니거나 만들 수 없으면 None.

    None 은 "가드를 쓸 수 없다"는 뜻이지 "중복이다"가 아니다 - 호출자는 서버를
    그대로 띄운다. 가드가 서버를 못 뜨게 만드는 실패 방향은 피한다.
    """
    ctypes, kernel32 = _load_kernel32()
    if kernel32 is None:
        return None
    try:
        kernel32.CreateMutexW.argtypes = (ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p)
        kernel32.CreateMutexW.restype = ctypes.c_void_p
        handle = kernel32.CreateMutexW(None, 0, name)
        last_error = ctypes.get_last_error()
    except (OSError, AttributeError, ValueError):  # pragma: no cover - 방어적
        return None
    if not handle:
        # 예: 같은 이름의 커널 오브젝트에 접근 권한이 없다. 가드를 건너뛴다.
        return None
    return MutexHandle(handle=handle, already_exists=(last_error == ERROR_ALREADY_EXISTS))


def close_handle(handle) -> bool:
    """`create_windows_mutex` 가 돌려준 핸들을 닫는다 (주로 테스트 정리용)."""
    ctypes, kernel32 = _load_kernel32()
    if kernel32 is None or not handle:
        return False
    try:
        kernel32.CloseHandle.argtypes = (ctypes.c_void_p,)
        kernel32.CloseHandle.restype = ctypes.c_int
        return bool(kernel32.CloseHandle(ctypes.c_void_p(handle)))
    except (OSError, AttributeError, ValueError):  # pragma: no cover
        return False


def acquire(name: str = None, mutex_factory=None) -> Acquisition:
    """단일 인스턴스 여부를 판정하고, 단독이면 mutex 를 붙들어 둔다."""
    name = MUTEX_NAME if name is None else name

    if name in _HELD:
        # 같은 프로세스가 이미 잡고 있다. 여기서 CreateMutexW 를 다시 부르면 자기 자신
        # 때문에 ERROR_ALREADY_EXISTS 가 나오므로, 그 전에 걸러낸다(멱등).
        return Acquisition(name=name, supported=True, acquired=True)

    factory = create_windows_mutex if mutex_factory is None else mutex_factory
    result = factory(name)
    if result is None:
        return Acquisition(name=name, supported=False, acquired=True)
    if result.already_exists:
        # 방금 받은 핸들은 기존 mutex 를 가리킨다. 호출자가 곧 종료하므로 그대로 둔다
        # (여기서 닫아버리면 "이미 실행 중"이라는 사실만 잃고 얻는 게 없다).
        return Acquisition(name=name, supported=True, acquired=False)
    _HELD[name] = result.handle
    return Acquisition(name=name, supported=True, acquired=True)


def release(name: str = None) -> bool:
    """붙들고 있던 mutex 를 놓는다. 정상 동작 중에는 쓰지 않는다(테스트 정리용)."""
    name = MUTEX_NAME if name is None else name
    handle = _HELD.pop(name, None)
    if handle is None:
        return False
    return close_handle(handle)


def show_message_box(text: str = DIALOG_TEXT, title: str = DIALOG_TITLE) -> bool:
    """MessageBoxW 안내창. 콘솔이 없는 windowed 실행에서만 호출된다."""
    try:
        import ctypes
    except ImportError:  # pragma: no cover
        return False
    if not hasattr(ctypes, "windll"):
        return False
    try:
        ctypes.windll.user32.MessageBoxW(
            None, text, title, MB_OK | MB_ICONWARNING | MB_SETFOREGROUND
        )
    except (OSError, AttributeError):  # pragma: no cover
        return False
    return True


def _log_to_stderr(message: str):
    # windowed 실행에서는 logging_setup 이 sys.stderr 를 로그 파일로 바꿔 둔 상태라
    # 같은 호출이 파일에 기록된다. 콘솔 실행에서는 그대로 stderr 한 줄.
    print(message, file=sys.stderr)


def enforce(allow_multiple: bool = False, windowed: bool = False, name: str = None,
            mutex_factory=None, message_box=None, log=_log_to_stderr) -> Optional[int]:
    """진입점 가드. 계속 진행해도 되면 None, 종료해야 하면 종료 코드를 반환한다.

    `main()` 에서만 호출한다 - `ServerRuntime`/`handle_client` 같은 기존 로직은
    이 가드와 무관하게 동작해야 한다(라이브러리로 임포트하는 테스트 포함).
    """
    if allow_multiple:
        return None

    result = acquire(name=name, mutex_factory=mutex_factory)
    if not result.supported:
        return None  # 비Windows / windll 없음 - 조용히 건너뛴다
    if result.acquired:
        return None

    log(ALREADY_RUNNING_LOG)
    if windowed:
        # 콘솔이 없으면 stderr 한 줄을 사용자가 볼 방법이 없다. 창으로 알린다.
        box = show_message_box if message_box is None else message_box
        try:
            box(DIALOG_TEXT, DIALOG_TITLE)
        except Exception as e:  # noqa: BLE001 - 안내 실패가 종료를 막으면 안 된다
            log("[!] Failed to show the already-running dialog: %s" % e)
    return EXIT_ALREADY_RUNNING
