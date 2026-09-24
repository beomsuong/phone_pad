"""pystray 어댑터(tray.py) 테스트 - 가짜 Icon 주입.

pystray/Pillow 가 설치돼 있지 않아도 전부 통과해야 한다(확정 설계 2). 그래서
`TrayController` 에 `icon_factory`/`image_factory`/`ip_lookup` 을 주입해
"언제 갱신하는가 / 종료가 무엇을 하는가"만 검증한다. 실제 트레이 렌더링은
이 테스트의 범위가 아니다.
"""
import threading

import pytest

import tray
from tray import (
    MENU_KEY_ADDRESS,
    MENU_KEY_PIN,
    MENU_KEY_QUIT,
    MENU_KEY_SEPARATOR,
    MENU_KEY_STATUS,
    TrayController,
)
from tray_status import ADDRESS_UNKNOWN, STATE_CONNECTED, STATE_IDLE


class FakeIcon:
    """pystray.Icon 대역. 속성 대입 이력을 남겨 '몇 번 갱신됐는지' 를 본다."""

    def __init__(self, name=None, image=None, title=None, menu=None):
        self.name = name
        self._icon = image
        self._title = title
        self.icon_history = [image]
        self.title_history = [title]
        self.menu = menu
        self.visible = False
        self.update_menu_calls = 0
        self.stop_calls = 0
        self.run_calls = 0
        # run() 진입 직후(= setup 호출 전)에 실행할 훅. 정지 요청 경합 재현용.
        self.on_run_entry = None
        # True 면 run() 이 stop() 될 때까지 블로킹한다 (실제 pystray 처럼).
        self.blocking = False
        self._released = threading.Event()

    # -- pystray 가 노출하는 속성들 --------------------------------------
    @property
    def icon(self):
        return self._icon

    @icon.setter
    def icon(self, value):
        self._icon = value
        self.icon_history.append(value)

    @property
    def title(self):
        return self._title

    @title.setter
    def title(self, value):
        self._title = value
        self.title_history.append(value)

    def update_menu(self):
        self.update_menu_calls += 1

    def run(self, setup=None):
        self.run_calls += 1
        if self.on_run_entry is not None:
            self.on_run_entry()
        if setup is not None:
            setup(self)
        if self.blocking:
            assert self._released.wait(timeout=5), "icon.run() was never released"

    def stop(self):
        self.stop_calls += 1
        self._released.set()


class Harness:
    """TrayController + 그 의존성을 한 번에 들고 있는 테스트 지그."""

    def __init__(self, count=0, ip="192.168.0.42", port=9000, on_quit=None,
                 poll_interval=0.01, pin=None):
        self.count = count
        self.icons = []
        self.quit_calls = 0

        def icon_factory(name, image, title, menu):
            icon = FakeIcon(name=name, image=image, title=title, menu=menu)
            self.icons.append(icon)
            return icon

        def default_quit():
            self.quit_calls += 1

        self.controller = TrayController(
            count_provider=lambda: self.count,
            on_quit=on_quit if on_quit is not None else default_quit,
            port=port,
            ip_lookup=lambda: ip,
            icon_factory=icon_factory,
            image_factory=lambda state: f"IMG:{state}",
            poll_interval=poll_interval,
            pin=pin,  # Phase 5: 인증이 켜져 있을 때만 값이 들어온다
        )

    @property
    def icon(self):
        return self.icons[-1] if self.icons else None

    def start(self):
        """트레이를 띄운다 (FakeIcon.run 은 기본적으로 즉시 반환)."""
        self.controller.run()
        return self.icon

    def entry(self, key):
        return next(e for e in self.controller.menu_entries() if e.key == key)


# --------------------------------------------------------------------------
# 최초 표시
# --------------------------------------------------------------------------

def test_icon_starts_in_idle_state_with_idle_tooltip():
    h = Harness(count=0)
    icon = h.start()
    assert icon.icon == f"IMG:{STATE_IDLE}"
    assert icon.title == "Phone Pad - 대기 중"
    assert icon.visible is True  # setup 에서 표시됨


def test_icon_starts_connected_when_clients_are_already_attached():
    h = Harness(count=2)
    icon = h.start()
    assert icon.icon == f"IMG:{STATE_CONNECTED}"
    assert icon.title == "Phone Pad - 연결됨 (2대)"


# --------------------------------------------------------------------------
# 연결 수 변화 -> 갱신
# --------------------------------------------------------------------------

def test_first_connection_switches_icon_and_tooltip():
    h = Harness(count=0)
    icon = h.start()
    h.count = 1

    assert h.controller.refresh() is True
    assert icon.icon == f"IMG:{STATE_CONNECTED}"
    assert icon.title == "Phone Pad - 연결됨 (1대)"
    assert icon.update_menu_calls == 1


def test_last_disconnect_switches_back_to_idle():
    h = Harness(count=1)
    icon = h.start()
    h.count = 0

    assert h.controller.refresh() is True
    assert icon.icon == f"IMG:{STATE_IDLE}"
    assert icon.title == "Phone Pad - 대기 중"


def test_unchanged_count_does_not_touch_the_icon():
    """같은 상태에서는 불필요한 갱신을 하지 않는다."""
    h = Harness(count=1)
    icon = h.start()
    icons_before = len(icon.icon_history)
    titles_before = len(icon.title_history)

    assert h.controller.refresh() is False
    assert h.controller.refresh() is False

    assert len(icon.icon_history) == icons_before
    assert len(icon.title_history) == titles_before
    assert icon.update_menu_calls == 0


def test_count_change_within_same_state_updates_tooltip_but_not_image():
    """1대 -> 2대: 상태 종류는 그대로라 이미지는 다시 만들지 않는다."""
    h = Harness(count=1)
    icon = h.start()
    icons_before = len(icon.icon_history)

    h.count = 2
    assert h.controller.refresh() is True

    assert len(icon.icon_history) == icons_before  # 이미지 교체 없음
    assert icon.title == "Phone Pad - 연결됨 (2대)"
    assert icon.update_menu_calls == 1


def test_refresh_before_run_only_tracks_state():
    """아이콘이 아직 없을 때 refresh 해도 크래시하지 않는다."""
    h = Harness(count=0)
    h.count = 3
    assert h.controller.refresh() is True
    assert h.controller.status.state == STATE_CONNECTED
    assert h.icons == []


def test_failing_image_factory_does_not_break_refresh():
    h = Harness(count=0)
    icon = h.start()

    def boom(_state):
        raise RuntimeError("no Pillow here")

    h.controller._image_factory = boom
    h.count = 1
    assert h.controller.refresh() is True
    assert icon.title == "Phone Pad - 연결됨 (1대)"  # 툴팁은 그래도 갱신된다


# --------------------------------------------------------------------------
# 메뉴
# --------------------------------------------------------------------------

def test_menu_layout_is_status_address_separator_quit():
    h = Harness(count=0)
    h.start()
    entries = h.controller.menu_entries()

    assert [e.key for e in entries] == [
        MENU_KEY_STATUS,
        MENU_KEY_ADDRESS,
        MENU_KEY_SEPARATOR,
        MENU_KEY_QUIT,
    ]
    assert [e.enabled for e in entries] == [False, False, False, True]


def test_status_menu_label_matches_tooltip():
    h = Harness(count=2)
    icon = h.start()
    assert h.entry(MENU_KEY_STATUS).text == icon.title


def test_status_menu_label_follows_connection_count():
    h = Harness(count=0)
    h.start()
    assert h.entry(MENU_KEY_STATUS).text == "Phone Pad - 대기 중"
    h.count = 3
    h.controller.refresh()
    assert h.entry(MENU_KEY_STATUS).text == "Phone Pad - 연결됨 (3대)"


def test_address_menu_label_shows_lan_ip_and_tcp_port():
    h = Harness(ip="192.168.0.42", port=9000)
    h.start()
    assert h.entry(MENU_KEY_ADDRESS).text == "접속 주소: 192.168.0.42:9000"


def test_address_menu_label_says_unknown_when_lookup_fails():
    h = Harness(ip=None)
    h.start()
    assert h.entry(MENU_KEY_ADDRESS).text == f"접속 주소: {ADDRESS_UNKNOWN}"


def test_address_lookup_failure_exception_is_contained():
    h = Harness()
    h.controller._ip_lookup = lambda: (_ for _ in ()).throw(OSError("nope"))
    h.start()
    assert h.entry(MENU_KEY_ADDRESS).text == f"접속 주소: {ADDRESS_UNKNOWN}"


def test_lan_ip_is_looked_up_only_once():
    calls = []

    def lookup():
        calls.append(1)
        return "10.0.0.9"

    h = Harness()
    h.controller._ip_lookup = lookup
    h.start()
    for _ in range(5):
        h.controller.menu_entries()
    assert len(calls) == 1


def test_only_quit_entry_is_actionable():
    h = Harness()
    h.start()
    actionable = [e.key for e in h.controller.menu_entries() if e.action is not None]
    assert actionable == [MENU_KEY_QUIT]


# --------------------------------------------------------------------------
# PIN 표시 (Phase 5)
# --------------------------------------------------------------------------

def test_no_pin_entry_when_authentication_is_off():
    """인증이 꺼져 있으면 메뉴 구성이 기존과 완전히 같아야 한다."""
    h = Harness(pin=None)
    h.start()
    assert MENU_KEY_PIN not in [e.key for e in h.controller.menu_entries()]


def test_pin_entry_sits_after_the_address_and_is_display_only():
    h = Harness(pin="483920")
    h.start()
    entries = h.controller.menu_entries()

    assert [e.key for e in entries] == [
        MENU_KEY_STATUS,
        MENU_KEY_ADDRESS,
        MENU_KEY_PIN,
        MENU_KEY_SEPARATOR,
        MENU_KEY_QUIT,
    ]
    assert h.entry(MENU_KEY_PIN).text == "PIN: 483920"
    assert h.entry(MENU_KEY_PIN).enabled is False
    assert h.entry(MENU_KEY_PIN).action is None


def test_tooltip_shows_the_pin_so_the_user_can_type_it_on_the_phone():
    h = Harness(count=0, pin="483920")
    icon = h.start()
    assert icon.title == "Phone Pad - 대기 중 - PIN: 483920"


def test_pin_stays_in_the_tooltip_after_a_connection_arrives():
    h = Harness(count=0, pin="483920")
    icon = h.start()
    h.count = 1
    assert h.controller.refresh() is True
    assert icon.title == "Phone Pad - 연결됨 (1대) - PIN: 483920"
    assert h.entry(MENU_KEY_STATUS).text == icon.title


# --------------------------------------------------------------------------
# 종료
# --------------------------------------------------------------------------

def test_quit_entry_runs_shutdown_callback_and_removes_icon():
    stop_signal = threading.Event()
    h = Harness(on_quit=stop_signal.set)
    icon = h.start()

    h.entry(MENU_KEY_QUIT).action()

    assert stop_signal.is_set()
    assert icon.stop_calls == 1


def test_quit_still_removes_icon_when_shutdown_callback_raises():
    """서버 정리가 실패해도 아이콘은 사라져야 한다 (좀비 아이콘 금지)."""
    def boom():
        raise RuntimeError("server refused to stop")

    h = Harness(on_quit=boom)
    icon = h.start()

    h.controller.quit()  # 예외가 밖으로 새지 않는다

    assert icon.stop_calls == 1


def test_stop_is_idempotent():
    h = Harness()
    icon = h.start()
    h.controller.stop()
    h.controller.stop()
    h.controller.stop()
    assert icon.stop_calls == 1


def test_stop_before_run_never_creates_an_icon():
    """서버 스레드가 즉시 죽어 트레이가 뜨기도 전에 정지될 수 있다."""
    h = Harness()
    h.controller.stop()
    h.controller.run()
    assert h.icons == []


def test_stop_arriving_before_message_loop_is_not_lost():
    """정지 요청이 pystray 루프 기동과 겹쳐도 아이콘이 남지 않는다.

    FakeIcon.run 진입 직후(= setup 이 돌기 전) 정지시켜, `_on_loop_ready` 쪽
    경합 처리가 실제로 icon.stop() 을 부르는지 확인한다.
    """
    h = Harness()
    created = {}

    def icon_factory(name, image, title, menu):
        icon = FakeIcon(name=name, image=image, title=title, menu=menu)
        icon.blocking = True
        icon.on_run_entry = h.controller.stop  # 루프가 뜨기 전에 정지 요청
        created["icon"] = icon
        h.icons.append(icon)
        return icon

    h.controller._icon_factory = icon_factory
    h.controller.run()  # 블로킹하면 테스트가 5초 뒤 실패한다

    assert created["icon"].stop_calls >= 1


def test_run_blocks_until_stopped_from_another_thread():
    h = Harness()
    icons = []

    def icon_factory(name, image, title, menu):
        icon = FakeIcon(name=name, image=image, title=title, menu=menu)
        icon.blocking = True
        icons.append(icon)
        h.icons.append(icon)
        return icon

    h.controller._icon_factory = icon_factory
    thread = threading.Thread(target=h.controller.run, daemon=True)
    thread.start()

    # 아이콘이 생기고 루프가 뜰 때까지 대기
    assert h.controller._loop_ready.wait(timeout=5)
    assert thread.is_alive()

    h.controller.stop()
    thread.join(timeout=5)
    assert not thread.is_alive()
    assert icons[0].stop_calls == 1


def test_polling_thread_picks_up_connection_changes():
    h = Harness(count=0, poll_interval=0.01)
    icons = []

    def icon_factory(name, image, title, menu):
        icon = FakeIcon(name=name, image=image, title=title, menu=menu)
        icon.blocking = True
        icons.append(icon)
        h.icons.append(icon)
        return icon

    h.controller._icon_factory = icon_factory
    thread = threading.Thread(target=h.controller.run, daemon=True)
    thread.start()
    assert h.controller._loop_ready.wait(timeout=5)

    h.count = 1
    deadline = threading.Event()
    for _ in range(200):  # 최대 ~2초
        if icons[0].title == "Phone Pad - 연결됨 (1대)":
            break
        deadline.wait(0.01)

    assert icons[0].title == "Phone Pad - 연결됨 (1대)"
    assert icons[0].icon == f"IMG:{STATE_CONNECTED}"

    h.controller.stop()
    thread.join(timeout=5)


def test_polling_failure_does_not_kill_the_poll_thread():
    """연결 수 조회가 실패해도 폴링 스레드는 살아 있어야 한다."""
    h = Harness(count=0, poll_interval=0.01)

    def icon_factory(name, image, title, menu):
        icon = FakeIcon(name=name, image=image, title=title, menu=menu)
        icon.blocking = True
        h.icons.append(icon)
        return icon

    h.controller._icon_factory = icon_factory
    thread = threading.Thread(target=h.controller.run, daemon=True)
    thread.start()
    assert h.controller._loop_ready.wait(timeout=5)

    calls = []

    def boom():
        calls.append(1)
        raise RuntimeError("registry busy")

    h.controller._count_provider = boom  # 루프가 뜬 뒤에 고장 주입

    waiter = threading.Event()
    for _ in range(300):  # 최대 ~3초
        if len(calls) >= 3:
            break
        waiter.wait(0.01)

    assert len(calls) >= 3
    assert h.controller._poll_thread.is_alive()

    h.controller.stop()
    thread.join(timeout=5)


# --------------------------------------------------------------------------
# 선택적 의존성
# --------------------------------------------------------------------------

def test_module_imports_without_pystray_installed():
    """tray.py 는 pystray 유무와 무관하게 import 된다."""
    assert hasattr(tray, "tray_available")
    assert tray.tray_available() is (tray.TRAY_IMPORT_ERROR is None)


def test_create_icon_image_raises_clear_error_when_pillow_missing():
    if tray.Image is not None:
        pytest.skip("Pillow is installed - the missing-dependency path cannot be hit")
    with pytest.raises(tray.TrayUnavailableError):
        tray.create_icon_image(STATE_IDLE)


def test_create_icon_image_produces_distinct_colors_when_pillow_present():
    if tray.Image is None:
        pytest.skip("Pillow is not installed in this environment")
    idle = tray.create_icon_image(STATE_IDLE, size=32)
    connected = tray.create_icon_image(STATE_CONNECTED, size=32)
    assert idle.size == (32, 32)
    assert idle.getpixel((16, 16)) != connected.getpixel((16, 16))


def test_unavailable_reason_is_empty_when_available():
    if tray.TRAY_IMPORT_ERROR is None:
        assert tray.unavailable_reason() == ""
    else:
        assert tray.unavailable_reason() != ""
