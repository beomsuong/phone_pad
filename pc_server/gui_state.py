"""GUI 창의 순수 로직 (tkinter / 디스플레이 비의존).

`tray_status.py` 가 pystray 어댑터의 순수 로직을 담는 것과 같은 자리의 tkinter
버전이다. 창에 표시되는 문자열을 **여기서만** 만들고, `gui.py` 에는 "언제 갱신하고
버튼을 누르면 무엇이 일어나는가" 만 남긴다.

표시 문자열은 트레이와 최대한 공유한다 - 접속 주소/PIN/연결 상태는
`tray_status.py` 의 함수를 그대로 재사용하므로 창과 트레이가 서로 다른 문구를
보여주는 일이 없다(DRY). 여기 새로 생기는 것은 **창에만 있는 개념**
(서버 정지 상태, 시작/정지 토글, 오류 메시지, 닫기 버튼 안내)뿐이다.

참고: 이 파일의 문자열 상수는 창에 표시되는 값이라 한글을 쓴다.
`print()` 로그는 여전히 ASCII 만 사용한다 (AGENTS.md 섹션 9).
"""
from dataclasses import dataclass
from typing import Optional

from tray_status import (
    PIN_LABEL_PREFIX,
    QUIT_TEXT,
    format_address_label,
    format_pin_label,
    tray_status,
)

# ---------------------------------------------------------------------------
# 표시 문자열
# ---------------------------------------------------------------------------
WINDOW_TITLE = "Phone Pad Server"

TOGGLE_START_TEXT = "시작"
TOGGLE_STOP_TEXT = "정지"
# 트레이 메뉴와 같은 문구를 쓴다 ("종료").
EXIT_TEXT = QUIT_TEXT

STATUS_STOPPED = "서버 정지됨"
PIN_STOPPED = f"{PIN_LABEL_PREFIX}: (서버 정지됨)"

# 창을 닫았을 때 무슨 일이 일어나는지 - 트레이 가용 여부에 따라 달라진다.
HINT_HIDE_TO_TRAY = "창을 닫으면 트레이 아이콘으로 숨습니다."
HINT_CLOSE_QUITS = "트레이를 쓸 수 없어 창을 닫으면 서버가 종료됩니다."

MESSAGE_NONE = ""
MESSAGE_START_FAILED_TEMPLATE = "시작 실패: {reason}"
MESSAGE_STOP_FAILED_TEMPLATE = "정지 실패: {reason}"
MESSAGE_SERVER_DIED = "서버가 예기치 않게 멈췄습니다. '시작'을 다시 눌러 주세요."


@dataclass(frozen=True)
class GuiLabels:
    """창의 네 줄 + 토글 버튼 문구. 한 번에 계산해 한 번에 반영한다."""

    status: str
    address: str
    pin: str
    toggle: str


def toggle_text(running) -> str:
    """토글 버튼 문구. 돌고 있으면 '정지', 멈춰 있으면 '시작'."""
    return TOGGLE_STOP_TEXT if running else TOGGLE_START_TEXT


def status_line(running, connection_count) -> str:
    """연결 상태 줄.

    돌고 있을 때의 문구는 트레이 툴팁과 **글자 그대로 같다**
    (`tray_status()` 재사용). PIN 은 창에 전용 줄이 따로 있으므로 여기엔 붙이지
    않는다(툴팁과 달리 중복 표시가 된다).
    """
    if not running:
        return STATUS_STOPPED
    return tray_status(connection_count).tooltip


def pin_line(running, pin) -> str:
    """PIN 줄. 서버가 멈춰 있으면 PIN 자체가 없다(다음 '시작'에 새로 생긴다)."""
    if not running:
        return PIN_STOPPED
    return format_pin_label(pin)


def address_line(ip, port) -> str:
    """접속 주소 줄. 트레이 메뉴와 같은 문구."""
    return format_address_label(ip, port)


def close_hint(tray_usable) -> str:
    """X 버튼을 눌렀을 때 무슨 일이 일어나는지 미리 알려주는 한 줄."""
    return HINT_HIDE_TO_TRAY if tray_usable else HINT_CLOSE_QUITS


def _reason(exc) -> str:
    """예외에서 사용자에게 보여줄 한 줄. 문구가 비어 있으면 클래스 이름으로."""
    text = str(exc).strip()
    return text if text else type(exc).__name__


def start_error_text(exc) -> str:
    """'시작' 실패(포트 점유 등) 메시지. OS 문구를 그대로 덧붙인다.

    창에 표시하는 값이라 비ASCII(한국어 Windows 의 OS 오류 문구)여도 된다 -
    ASCII 제약은 cp949 콘솔로 나가는 `print()` 에만 적용된다.
    """
    return MESSAGE_START_FAILED_TEMPLATE.format(reason=_reason(exc))


def stop_error_text(exc) -> str:
    return MESSAGE_STOP_FAILED_TEMPLATE.format(reason=_reason(exc))


def compute_labels(running, connection_count, pin, address: Optional[str] = None,
                   port=None) -> GuiLabels:
    """창의 모든 동적 문구를 한 번에 계산한다.

    `address` 는 이미 만들어 둔 주소 줄(LAN IP 조회는 비싸서 어댑터가 캐시한다).
    없으면 `port` 로 '확인 불가' 문구를 만든다.
    """
    if address is None:
        address = address_line(None, port)
    return GuiLabels(
        status=status_line(running, connection_count),
        address=address,
        pin=pin_line(running, pin),
        toggle=toggle_text(running),
    )
