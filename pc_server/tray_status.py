"""트레이 아이콘의 순수 로직 (pystray / Pillow / 디스플레이 비의존).

pystray 어댑터(`tray.py`)를 얇게 유지하기 위해, 단위 테스트가 가능한 부분 -
상태 계산, 표시 문자열 포맷, LAN IP 조회 - 를 전부 이 모듈에 모은다.
표준 라이브러리만 사용하므로 pystray/Pillow 가 설치돼 있지 않아도 import 되고
테스트된다.

참고: 이 파일의 문자열 상수는 트레이 UI 에 표시되는 값이라 한글을 쓴다.
`print()` 로그는 여전히 ASCII 만 사용한다 (AGENTS.md 섹션 9).
"""
import socket
from dataclasses import dataclass

# ---------------------------------------------------------------------------
# 상태 종류 (아이콘 이미지 선택 키)
# ---------------------------------------------------------------------------
STATE_IDLE = "idle"
STATE_CONNECTED = "connected"

# ---------------------------------------------------------------------------
# 표시 문자열
# ---------------------------------------------------------------------------
TOOLTIP_IDLE = "Phone Pad - 대기 중"
TOOLTIP_CONNECTED_TEMPLATE = "Phone Pad - 연결됨 ({count}대)"
# 인증이 켜져 있을 때만 툴팁 끝에 붙는다 (꺼져 있으면 기존 문구 그대로).
TOOLTIP_PIN_SUFFIX_TEMPLATE = " - PIN: {pin}"

ADDRESS_LABEL_PREFIX = "접속 주소"
ADDRESS_UNKNOWN = "(확인 불가)"

PIN_LABEL_PREFIX = "PIN"
PIN_DISABLED = "(사용 안 함)"

QUIT_TEXT = "종료"

# ---------------------------------------------------------------------------
# LAN IP 조회
# ---------------------------------------------------------------------------
# UDP 소켓의 connect() 는 패킷을 실제로 보내지 않고 라우팅 테이블만 조회한다.
# 도달 불가능한 주소를 골라 "이 PC 가 외부로 나갈 때 쓰는 인터페이스의 IP" 를 얻는다.
ROUTE_PROBE_ADDRESS = ("10.255.255.255", 1)
ROUTE_PROBE_TIMEOUT_S = 0.2


@dataclass(frozen=True)
class TrayStatus:
    """연결 수에서 파생되는 트레이 표시 상태.

    `state` 는 아이콘 이미지 선택용 키, `tooltip` 은 트레이 툴팁이자 메뉴의
    상태 라벨 문구다 (확정 설계 1: 둘은 같은 문구).
    """

    state: str
    tooltip: str

    @property
    def connected(self) -> bool:
        return self.state == STATE_CONNECTED


def normalize_pin(pin):
    """표시용 PIN. 문자열이 아니거나 비어 있으면 `None` (= 인증 꺼짐과 같게 표시).

    트레이는 PIN 을 **보여주기만** 한다 - 판정은 서버(`pin_auth`)가 한다.
    """
    if not isinstance(pin, str):
        return None
    pin = pin.strip()
    return pin or None


def tray_status(connection_count, pin=None) -> TrayStatus:
    """활성 연결 수(+PIN) -> (상태 종류, 툴팁/상태 라벨 문구).

    음수나 숫자가 아닌 값이 들어와도 예외를 던지지 않고 '대기 중'으로 본다 -
    트레이 표시가 서버를 죽이는 일은 없어야 한다.

    `pin` 이 주어지면(= 인증이 켜져 있으면) 툴팁 끝에 PIN 을 덧붙인다. 폰에
    입력해야 하는 값이라 사용자가 트레이에서 바로 확인할 수 있어야 한다.
    """
    try:
        count = int(connection_count)
    except (TypeError, ValueError):
        count = 0
    if count <= 0:
        tooltip = TOOLTIP_IDLE
        state = STATE_IDLE
    else:
        tooltip = TOOLTIP_CONNECTED_TEMPLATE.format(count=count)
        state = STATE_CONNECTED
    shown = normalize_pin(pin)
    if shown is not None:
        tooltip += TOOLTIP_PIN_SUFFIX_TEMPLATE.format(pin=shown)
    return TrayStatus(state, tooltip)


def is_usable_lan_ip(ip) -> bool:
    """앱에 손으로 입력해서 실제로 닿을 수 있는 주소인지.

    루프백(127.x)은 폰에서 닿지 않으므로 '조회 실패'와 동급으로 본다.
    """
    if not isinstance(ip, str) or not ip:
        return False
    if ip.startswith("127.") or ip == "0.0.0.0":
        return False
    return True


def _default_probe_socket() -> socket.socket:
    return socket.socket(socket.AF_INET, socket.SOCK_DGRAM)


def detect_lan_ip(socket_factory=None):
    """이 PC 의 LAN IP 를 반환한다. 알아낼 수 없으면 `None`.

    절대 예외를 밖으로 던지지 않는다 (확정 설계 1). `socket_factory` 를 주입하면
    실제 소켓 없이 성공/실패 경로를 테스트할 수 있다.

    실패 시 `127.0.0.1` 을 반환하지 않는 이유: 사용자는 이 값을 폰에 손으로
    입력한다. 루프백 주소를 안내하면 "서버는 멀쩡한데 폰만 못 붙는" 상황을
    사용자가 영원히 오해하게 된다.
    """
    factory = socket_factory if socket_factory is not None else _default_probe_socket
    sock = None
    try:
        sock = factory()
        sock.settimeout(ROUTE_PROBE_TIMEOUT_S)
        sock.connect(ROUTE_PROBE_ADDRESS)
        ip = sock.getsockname()[0]
    except Exception:
        return None
    finally:
        if sock is not None:
            try:
                sock.close()
            except Exception:
                pass
    return ip if is_usable_lan_ip(ip) else None


def format_address_label(ip, port) -> str:
    """메뉴의 접속 주소 라벨. IP 가 없으면 포트도 붙이지 않는다."""
    if not is_usable_lan_ip(ip):
        return f"{ADDRESS_LABEL_PREFIX}: {ADDRESS_UNKNOWN}"
    return f"{ADDRESS_LABEL_PREFIX}: {ip}:{port}"


def format_pin_label(pin) -> str:
    """메뉴의 PIN 라벨 (표시 전용, 클릭 불가). 인증이 꺼져 있으면 '사용 안 함'."""
    shown = normalize_pin(pin)
    if shown is None:
        return f"{PIN_LABEL_PREFIX}: {PIN_DISABLED}"
    return f"{PIN_LABEL_PREFIX}: {shown}"
