"""트레이 순수 로직 테스트 (tray_status.py).

이 모듈은 pystray/Pillow 를 전혀 import 하지 않으므로, 두 패키지가 설치돼 있지
않은 환경에서도 그대로 돈다.
"""
import pytest

from tray_status import (
    ADDRESS_UNKNOWN,
    PIN_DISABLED,
    ROUTE_PROBE_ADDRESS,
    STATE_CONNECTED,
    STATE_IDLE,
    TOOLTIP_IDLE,
    detect_lan_ip,
    format_address_label,
    format_pin_label,
    is_usable_lan_ip,
    normalize_pin,
    tray_status,
)


class FakeProbeSocket:
    """detect_lan_ip 가 쓰는 UDP 소켓 대역."""

    def __init__(self, local_ip="192.168.0.42", fail_on=None):
        self.local_ip = local_ip
        self.fail_on = fail_on  # "connect" | "getsockname" | None
        self.closed = False
        self.timeout = None
        self.connected_to = None

    def settimeout(self, value):
        self.timeout = value

    def connect(self, address):
        if self.fail_on == "connect":
            raise OSError("network is unreachable")
        self.connected_to = address

    def getsockname(self):
        if self.fail_on == "getsockname":
            raise OSError("not bound")
        return (self.local_ip, 54321)

    def close(self):
        self.closed = True


# --------------------------------------------------------------------------
# 연결 수 -> 상태 / 툴팁 (경계: 0, 1, 2)
# --------------------------------------------------------------------------

def test_zero_connections_is_idle():
    status = tray_status(0)
    assert status.state == STATE_IDLE
    assert status.tooltip == "Phone Pad - 대기 중"
    assert status.connected is False


def test_one_connection_is_connected_with_count():
    status = tray_status(1)
    assert status.state == STATE_CONNECTED
    assert status.tooltip == "Phone Pad - 연결됨 (1대)"
    assert status.connected is True


def test_two_connections_report_the_count():
    assert tray_status(2).tooltip == "Phone Pad - 연결됨 (2대)"


def test_many_connections_report_the_count():
    assert tray_status(7).tooltip == "Phone Pad - 연결됨 (7대)"


def test_status_is_value_comparable():
    """refresh() 가 '변화 없음'을 판정하는 근거 - 같은 수는 같은 값이어야 한다."""
    assert tray_status(3) == tray_status(3)
    assert tray_status(3) != tray_status(4)
    assert tray_status(0) != tray_status(1)


@pytest.mark.parametrize("bogus", [-1, -5, None, "oops", object()])
def test_bogus_counts_fall_back_to_idle(bogus):
    """트레이 표시 계산이 서버를 죽이는 일은 없어야 한다."""
    status = tray_status(bogus)
    assert status.state == STATE_IDLE
    assert status.tooltip == TOOLTIP_IDLE


# --------------------------------------------------------------------------
# LAN IP 조회
# --------------------------------------------------------------------------

def test_detect_lan_ip_returns_routed_interface_address():
    sock = FakeProbeSocket(local_ip="192.168.1.77")
    assert detect_lan_ip(socket_factory=lambda: sock) == "192.168.1.77"
    assert sock.connected_to == ROUTE_PROBE_ADDRESS
    assert sock.closed is True


@pytest.mark.parametrize("fail_on", ["connect", "getsockname"])
def test_detect_lan_ip_returns_none_on_socket_failure(fail_on):
    sock = FakeProbeSocket(fail_on=fail_on)
    assert detect_lan_ip(socket_factory=lambda: sock) is None
    assert sock.closed is True  # 실패해도 소켓은 닫힌다


def test_detect_lan_ip_returns_none_when_socket_creation_fails():
    def boom():
        raise OSError("no socket for you")

    assert detect_lan_ip(socket_factory=boom) is None


def test_detect_lan_ip_never_raises_even_on_weird_failures():
    """'이 함수는 예외를 밖으로 던지지 않는다'(확정 설계 1)."""

    class Exploding:
        def settimeout(self, _v):
            raise RuntimeError("boom")

        def close(self):
            raise RuntimeError("boom again")

    assert detect_lan_ip(socket_factory=Exploding) is None


def test_detect_lan_ip_rejects_loopback():
    """루프백을 안내하면 사용자가 폰에 127.0.0.1 을 입력하게 된다."""
    sock = FakeProbeSocket(local_ip="127.0.0.1")
    assert detect_lan_ip(socket_factory=lambda: sock) is None


def test_detect_lan_ip_on_real_socket_is_none_or_usable():
    """실제 소켓 경로 - 네트워크 유무와 무관하게 계약만 검증한다."""
    ip = detect_lan_ip()
    assert ip is None or is_usable_lan_ip(ip)


@pytest.mark.parametrize(
    "ip,expected",
    [
        ("192.168.0.2", True),
        ("10.0.0.5", True),
        ("172.20.1.1", True),
        ("127.0.0.1", False),
        ("127.5.5.5", False),
        ("0.0.0.0", False),
        ("", False),
        (None, False),
        (12345, False),
    ],
)
def test_is_usable_lan_ip(ip, expected):
    assert is_usable_lan_ip(ip) is expected


# --------------------------------------------------------------------------
# 접속 주소 라벨
# --------------------------------------------------------------------------

def test_address_label_shows_ip_and_port():
    assert format_address_label("192.168.0.42", 9000) == "접속 주소: 192.168.0.42:9000"


def test_address_label_without_ip_says_unknown_and_omits_port():
    """IP 를 모르는데 ':9000' 만 보여주면 더 헷갈린다."""
    label = format_address_label(None, 9000)
    assert label == f"접속 주소: {ADDRESS_UNKNOWN}"
    assert "9000" not in label


def test_address_label_never_advertises_loopback():
    assert format_address_label("127.0.0.1", 9000) == f"접속 주소: {ADDRESS_UNKNOWN}"


def test_address_label_uses_given_port():
    assert format_address_label("10.1.2.3", 9100).endswith(":9100")


# --------------------------------------------------------------------------
# PIN 표시 (Phase 5) - 인증이 켜져 있을 때만 붙는다
# --------------------------------------------------------------------------

def test_tooltip_is_unchanged_when_authentication_is_off():
    assert tray_status(0).tooltip == "Phone Pad - 대기 중"
    assert tray_status(0, None).tooltip == "Phone Pad - 대기 중"
    assert tray_status(2, None).tooltip == "Phone Pad - 연결됨 (2대)"


def test_idle_tooltip_appends_the_pin():
    assert tray_status(0, "483920").tooltip == "Phone Pad - 대기 중 - PIN: 483920"


def test_connected_tooltip_appends_the_pin():
    assert tray_status(2, "483920").tooltip == "Phone Pad - 연결됨 (2대) - PIN: 483920"


def test_pin_does_not_change_the_icon_state():
    assert tray_status(0, "483920").state == STATE_IDLE
    assert tray_status(1, "483920").state == STATE_CONNECTED


def test_pin_is_part_of_the_status_value_for_change_detection():
    assert tray_status(1, "483920") == tray_status(1, "483920")
    assert tray_status(1, "483920") != tray_status(1, "112233")
    assert tray_status(1, "483920") != tray_status(1)


@pytest.mark.parametrize("bogus", ["", "   ", None, 483920, object(), ["4"]])
def test_bogus_pins_are_shown_as_no_pin_at_all(bogus):
    """트레이 표시 계산이 서버를 죽이는 일은 없어야 한다 (연결 수와 같은 규칙)."""
    assert normalize_pin(bogus) is None
    assert tray_status(0, bogus).tooltip == TOOLTIP_IDLE


def test_normalize_pin_trims_surrounding_whitespace():
    assert normalize_pin("  483920 ") == "483920"


def test_pin_menu_label():
    assert format_pin_label("483920") == "PIN: 483920"


def test_pin_menu_label_when_authentication_is_off():
    assert format_pin_label(None) == f"PIN: {PIN_DISABLED}"
