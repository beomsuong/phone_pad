"""pystray 어댑터 - 시스템 트레이 아이콘 (연결 상태 표시 + 종료).

이 모듈이 `pystray`/`Pillow` 를 import 하는 유일한 곳이다. 둘 다 선택적 의존성이라
미설치 상태에서도 import 자체는 성공하고 `tray_available()` 가 False 를 반환한다
(서버 기능이 트레이 의존성 때문에 막히면 안 된다 - 확정 설계 4).

표시 문자열·상태 계산·LAN IP 조회 같은 순수 로직은 전부 `tray_status.py` 에 있고,
여기에는 "언제 아이콘을 갱신하는가 / 종료를 누르면 무엇이 일어나는가" 만 남긴다.
`TrayController` 는 `icon_factory`/`image_factory` 주입을 받으므로, pystray 가
설치돼 있지 않아도 가짜 Icon 으로 동작 전체를 테스트할 수 있다.
"""
import threading
from dataclasses import dataclass, field
from typing import Callable, Optional

from tray_status import (
    QUIT_TEXT,
    STATE_CONNECTED,
    TrayStatus,
    detect_lan_ip,
    format_address_label,
    format_pin_label,
    normalize_pin,
    tray_status,
)

try:  # pragma: no cover - 설치 여부에 따라 갈리는 분기
    import pystray
    from PIL import Image, ImageDraw

    TRAY_IMPORT_ERROR = None
except Exception as _import_error:  # ImportError 외에 DLL 로드 실패 등도 포함
    pystray = None
    Image = None
    ImageDraw = None
    TRAY_IMPORT_ERROR = _import_error


ICON_SIZE_PX = 64
COLOR_IDLE = (128, 128, 128, 255)      # 회색 - 대기
COLOR_CONNECTED = (46, 160, 67, 255)   # 초록 - 연결됨

DEFAULT_POLL_INTERVAL_S = 1.0

# 메뉴 항목 키 (테스트가 문구가 아니라 키로 항목을 찾을 수 있게 한다)
MENU_KEY_STATUS = "status"
MENU_KEY_ADDRESS = "address"
MENU_KEY_PIN = "pin"
MENU_KEY_SEPARATOR = "separator"
MENU_KEY_QUIT = "quit"


class TrayUnavailableError(RuntimeError):
    """pystray/Pillow 가 없는데 실제 트레이 기능을 요구했을 때."""


def tray_available() -> bool:
    return TRAY_IMPORT_ERROR is None


def unavailable_reason() -> str:
    return "" if TRAY_IMPORT_ERROR is None else str(TRAY_IMPORT_ERROR)


@dataclass(frozen=True)
class MenuEntry:
    """pystray 비의존 메뉴 항목 기술(記述).

    어댑터는 이 목록을 pystray 객체로 번역할 뿐이고, 테스트는 이 목록을 직접
    검사하거나 `action` 을 호출해 동작을 확인한다.
    """

    key: str
    text: str
    enabled: bool = True
    action: Optional[Callable[[], None]] = field(default=None)

    @property
    def is_separator(self) -> bool:
        return self.key == MENU_KEY_SEPARATOR


def create_icon_image(state: str, size: int = ICON_SIZE_PX):
    """상태별 아이콘 이미지를 Pillow 로 즉석 생성한다 (에셋 파일 없음).

    대기 = 회색 원, 연결됨 = 초록 원.
    """
    if Image is None or ImageDraw is None:
        raise TrayUnavailableError(f"Pillow is not available: {unavailable_reason()}")
    color = COLOR_CONNECTED if state == STATE_CONNECTED else COLOR_IDLE
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    margin = max(1, size // 8)
    draw.ellipse((margin, margin, size - margin - 1, size - margin - 1), fill=color)
    return image


def _default_icon_factory(name, image, title, menu):
    if pystray is None:
        raise TrayUnavailableError(f"pystray is not available: {unavailable_reason()}")
    return pystray.Icon(name, icon=image, title=title, menu=menu)


class TrayController:
    """트레이 아이콘의 수명 주기와 갱신을 담당한다.

    스레드 모델: `run()` 은 메인 스레드에서 블로킹한다 (pystray 는 Windows 에서
    메시지 루프를 소유해야 한다). 연결 수 폴링은 별도 데몬 스레드가 돈다.
    `stop()` 은 아무 스레드에서나 호출해도 안전하다.
    """

    def __init__(
        self,
        count_provider: Callable[[], int],
        on_quit: Callable[[], None],
        port: int,
        ip_lookup: Callable[[], Optional[str]] = None,
        icon_factory: Callable[..., object] = None,
        image_factory: Callable[[str], object] = None,
        poll_interval: float = DEFAULT_POLL_INTERVAL_S,
        name: str = "phone_pad",
        pin: str = None,
    ):
        self._count_provider = count_provider
        self._on_quit = on_quit
        self._port = port
        # 인증이 꺼져 있으면 None - 툴팁/메뉴가 기존과 완전히 같아진다.
        self._pin = normalize_pin(pin)
        self._ip_lookup = ip_lookup if ip_lookup is not None else detect_lan_ip
        self._icon_factory = icon_factory if icon_factory is not None else _default_icon_factory
        self._image_factory = image_factory if image_factory is not None else create_icon_image
        self._poll_interval = poll_interval
        self._name = name

        self._icon = None
        self._status: Optional[TrayStatus] = None
        self._address_label: Optional[str] = None
        self._poll_thread: Optional[threading.Thread] = None
        # 정지 요청 플래그. 서버 스레드가 죽었을 때도 외부에서 세워진다.
        self._stopped = threading.Event()
        # pystray 메시지 루프가 실제로 돌기 시작했는지 (stop() 과의 경합 방지용)
        self._loop_ready = threading.Event()
        # icon.stop() 을 딱 한 번만 보내기 위한 test-and-set
        self._stop_lock = threading.Lock()
        self._icon_stop_sent = False

    # -- 표시용 값 ---------------------------------------------------------

    @property
    def icon(self):
        return self._icon

    @property
    def status(self) -> Optional[TrayStatus]:
        return self._status

    def current_status(self) -> TrayStatus:
        return tray_status(self._count_provider(), self._pin)

    def status_text(self) -> str:
        status = self._status if self._status is not None else self.current_status()
        return status.tooltip

    def _status_menu_text(self, item=None) -> str:
        """pystray 의 callable text 훅. 메뉴를 열 때마다 현재 문구를 계산한다."""
        return self.status_text()

    def address_label(self) -> str:
        """접속 주소 라벨. LAN IP 는 한 번만 조회해 캐시한다.

        매 폴링마다 소켓을 열 이유가 없고, PC 의 LAN IP 가 서버 구동 중에 바뀌는
        경우는 드물다 (바뀌면 재시작이 필요하다 - summary 의 미해결 이슈 참조).
        """
        if self._address_label is None:
            try:
                ip = self._ip_lookup()
            except Exception:
                ip = None
            self._address_label = format_address_label(ip, self._port)
        return self._address_label

    # -- 메뉴 -------------------------------------------------------------

    def pin_label(self) -> str:
        """PIN 라벨 (표시 전용). 인증이 꺼져 있으면 항목 자체가 없다."""
        return format_pin_label(self._pin)

    def menu_entries(self):
        """메뉴 구성 (위 -> 아래). pystray 없이도 만들어지고 검사할 수 있다.

        PIN 항목은 **인증이 켜져 있을 때만** 들어간다 - 꺼져 있으면 메뉴 구성이
        기존(상태/주소/구분선/종료)과 완전히 같다.
        """
        entries = [
            MenuEntry(MENU_KEY_STATUS, self.status_text(), enabled=False),
            MenuEntry(MENU_KEY_ADDRESS, self.address_label(), enabled=False),
        ]
        if self._pin is not None:
            entries.append(MenuEntry(MENU_KEY_PIN, self.pin_label(), enabled=False))
        entries.extend([
            MenuEntry(MENU_KEY_SEPARATOR, "", enabled=False),
            MenuEntry(MENU_KEY_QUIT, QUIT_TEXT, enabled=True, action=self.quit),
        ])
        return entries

    @staticmethod
    def _wrap_action(action):
        """인자 없는 콜백을 pystray 가 요구하는 `(icon, item)` 형태로 감싼다.

        `lambda _icon, _item, act=action: act()` 같은 기본 인자 바인딩을 쓰면
        안 된다 - pystray 는 `co_argcount` 로 인자 수를 세므로 기본 인자까지
        3개로 계산해 `ValueError` 를 던진다.
        """

        def invoke(icon=None, item=None):
            action()

        return invoke

    def _build_menu(self):
        """`menu_entries()` 를 pystray 객체로 번역한다.

        pystray 가 없으면 기술 목록을 그대로 돌려준다 - 가짜 Icon 은 이것을
        받아도 아무 문제가 없다.
        """
        entries = self.menu_entries()
        if pystray is None:
            return entries
        items = []
        for entry in entries:
            if entry.is_separator:
                items.append(pystray.Menu.SEPARATOR)
            elif entry.action is None:
                # 상태/주소 라벨은 비활성. 상태 라벨만 매번 다시 계산한다
                # (pystray 는 callable text 를 `text(item)` 으로 호출한다).
                if entry.key == MENU_KEY_STATUS:
                    text = self._status_menu_text
                else:
                    text = entry.text
                items.append(pystray.MenuItem(text, None, enabled=False))
            else:
                items.append(
                    pystray.MenuItem(entry.text, self._wrap_action(entry.action), enabled=True)
                )
        return pystray.Menu(*items)

    # -- 갱신 -------------------------------------------------------------

    def refresh(self) -> bool:
        """연결 수를 다시 읽어 아이콘/툴팁/메뉴를 갱신한다.

        상태가 그대로면 아무것도 건드리지 않고 False 를 반환한다 (불필요한
        트레이 갱신은 Windows 에서 아이콘 깜빡임을 유발한다). 이미지 교체는
        '상태 종류'가 바뀔 때만 - 연결 수만 1 -> 2 로 바뀌면 툴팁만 갱신된다.
        """
        status = self.current_status()
        previous = self._status
        if previous is not None and status == previous:
            return False
        self._status = status

        icon = self._icon
        if icon is not None:
            if previous is None or previous.state != status.state:
                try:
                    icon.icon = self._image_factory(status.state)
                except Exception as e:
                    print(f"[!] Tray icon image update failed: {e}")
            icon.title = status.tooltip
            update_menu = getattr(icon, "update_menu", None)
            if callable(update_menu):
                try:
                    update_menu()
                except Exception as e:
                    print(f"[!] Tray menu update failed: {e}")
        return True

    def _poll_loop(self):
        while not self._stopped.wait(self._poll_interval):
            try:
                self.refresh()
            except Exception as e:  # 폴링 오류로 스레드가 죽지 않게
                print(f"[!] Tray refresh failed: {e}")

    # -- 수명 주기 ---------------------------------------------------------

    def _on_loop_ready(self, icon=None):
        """pystray 가 메시지 루프를 띄운 직후 별도 스레드에서 호출한다.

        `stop()` 과의 경합 처리: stop() 은 `_stopped` 를 세운 뒤 `_loop_ready` 를
        보고, 여기서는 `_loop_ready` 를 세운 뒤 `_stopped` 를 본다. 어느 순서로
        엇갈려도 최소 한쪽은 상대의 쓰기를 보게 되므로, 루프가 시작하기 전에
        정지 요청이 와도 아이콘이 영원히 떠 있는 일은 없다.
        """
        target = icon if icon is not None else self._icon
        if target is not None:
            try:
                target.visible = True
            except Exception:
                pass
        self._loop_ready.set()
        if self._stopped.is_set() and target is not None:
            self._safe_icon_stop(target)

    def _safe_icon_stop(self, icon):
        """icon.stop() 을 한 번만, 예외 없이 보낸다.

        `stop()` 과 `_on_loop_ready` 가 경합해 둘 다 도달할 수 있으므로
        test-and-set 으로 중복 전송을 막는다.
        """
        with self._stop_lock:
            if self._icon_stop_sent:
                return
            self._icon_stop_sent = True
        try:
            icon.stop()
        except Exception as e:
            print(f"[!] Tray icon stop failed: {e}")

    def run(self) -> None:
        """메인 스레드에서 트레이를 띄우고 메시지 루프가 끝날 때까지 블로킹한다."""
        if self._stopped.is_set():
            return
        status = self.current_status()
        self._status = status
        self._icon = self._icon_factory(
            name=self._name,
            image=self._image_factory(status.state),
            title=status.tooltip,
            menu=self._build_menu(),
        )
        self._poll_thread = threading.Thread(
            target=self._poll_loop, name="phone-pad-tray-poll", daemon=True
        )
        self._poll_thread.start()
        try:
            self._icon.run(setup=self._on_loop_ready)
        finally:
            self._stopped.set()
            if self._poll_thread is not None:
                self._poll_thread.join(timeout=self._poll_interval * 2 + 1.0)

    def stop(self) -> None:
        """트레이를 내린다. 아무 스레드에서나, 몇 번이든 호출해도 안전하다."""
        self._stopped.set()
        icon = self._icon
        # 루프가 아직 안 떴으면 여기서 stop() 을 불러봐야 메시지가 유실된다.
        # 그 경우는 `_on_loop_ready` 가 대신 처리한다.
        if icon is not None and self._loop_ready.is_set():
            self._safe_icon_stop(icon)

    def quit(self) -> None:
        """메뉴의 '종료'. 서버를 먼저 정리하고 나서 아이콘을 내린다."""
        try:
            self._on_quit()
        except Exception as e:
            print(f"[!] Tray quit handler failed: {e}")
        finally:
            self.stop()
