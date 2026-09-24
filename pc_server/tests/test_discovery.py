"""UDP 브로드캐스트 서버 탐색(포트 9002) 테스트.

소켓을 쓰는 테스트는 전부 **127.0.0.1 + 포트 0** 이다:
  - 고정 포트(9002)를 잡으면 실제 서버가 떠 있을 때 무관하게 깨진다.
  - 0.0.0.0 으로 바인드하면 Windows 방화벽 프롬프트가 뜰 수 있다.
"""
import json
import socket
import threading
from unittest.mock import patch

import pytest

import discovery
import server
from fake_conn import AUTH_LINE
from input_controller import InputController

LOCALHOST = "127.0.0.1"
# 이 머신에 없는 주소 - bind 가 확실히 OSError 를 던진다(test_server_shutdown 과 같은 이유).
UNBINDABLE_HOST = "203.0.113.1"
DISCOVER = b'{"type":"DISCOVER"}'


# --------------------------------------------------------------------------
# 헬퍼
# --------------------------------------------------------------------------

def start_responder(**kwargs):
    kwargs.setdefault("tcp_port", 9000)
    kwargs.setdefault("host", LOCALHOST)
    kwargs.setdefault("discovery_port", 0)
    kwargs.setdefault("poll_timeout", 0.05)
    kwargs.setdefault("name", "TEST-PC")
    responder = discovery.DiscoveryResponder(**kwargs)
    assert responder.start() is True, "discovery responder failed to bind"
    return responder


def client_socket(timeout=1.0):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((LOCALHOST, 0))
    sock.settimeout(timeout)
    return sock


def ask(sock, port, payload=DISCOVER, timeout=1.0):
    """DISCOVER 를 보내고 응답 바이트열을 돌려준다. 없으면 None."""
    sock.settimeout(timeout)
    sock.sendto(payload, (LOCALHOST, port))
    try:
        data, _addr = sock.recvfrom(2048)
    except socket.timeout:
        return None
    return data


# --------------------------------------------------------------------------
# 1. 순수 함수: 정상 응답
# --------------------------------------------------------------------------

def test_discover_returns_exact_response_bytes():
    """와이어 리터럴 고정: 압축 JSON, 키 순서 type -> name -> port, 개행 없음."""
    assert discovery.handle_discovery_packet(DISCOVER, "MY-PC", 9000) == (
        b'{"type":"SERVER","name":"MY-PC","port":9000}'
    )


def test_response_has_no_trailing_newline():
    response = discovery.handle_discovery_packet(DISCOVER, "MY-PC", 9000)
    assert not response.endswith(b"\n")


def test_response_reports_the_actual_tcp_port():
    response = discovery.handle_discovery_packet(DISCOVER, "MY-PC", 54321)
    assert json.loads(response)["port"] == 54321


def test_response_never_carries_a_session_token_or_ip():
    """탐색은 인증 이전 단계다 - 같은 LAN 의 누구나 이 응답을 본다."""
    response = discovery.handle_discovery_packet(DISCOVER, "MY-PC", 9000)
    parsed = json.loads(response)
    assert set(parsed) == {"type", "name", "port"}
    assert "session" not in response.decode("utf-8").lower()
    assert "ip" not in parsed and "host" not in parsed and "address" not in parsed


def test_request_with_extra_fields_is_still_answered():
    """클라이언트가 나중에 필드를 덧붙여도 서버는 관대해야 한다."""
    packet = b'{"type":"DISCOVER","app":"phone_pad","v":2}'
    assert discovery.handle_discovery_packet(packet, "MY-PC", 9000) is not None


def test_request_with_whitespace_padding_is_answered():
    packet = b'  {"type": "DISCOVER"}  '
    assert discovery.handle_discovery_packet(packet, "MY-PC", 9000) is not None


def test_exactly_256_bytes_is_still_answered():
    padding = b" " * (256 - len(DISCOVER))
    packet = DISCOVER + padding
    assert len(packet) == 256
    assert discovery.handle_discovery_packet(packet, "MY-PC", 9000) is not None


def test_bytearray_input_is_accepted():
    assert discovery.handle_discovery_packet(bytearray(DISCOVER), "MY-PC", 9000) is not None


# --------------------------------------------------------------------------
# 2. 순수 함수: 이름 정규화
# --------------------------------------------------------------------------

def test_long_name_is_truncated_to_64_chars():
    response = discovery.handle_discovery_packet(DISCOVER, "N" * 200, 9000)
    assert json.loads(response)["name"] == "N" * 64


def test_name_of_exactly_64_chars_is_untouched():
    response = discovery.handle_discovery_packet(DISCOVER, "N" * 64, 9000)
    assert json.loads(response)["name"] == "N" * 64


def test_empty_name_falls_back_to_pc():
    response = discovery.handle_discovery_packet(DISCOVER, "", 9000)
    assert json.loads(response)["name"] == "PC"


def test_non_string_name_falls_back_to_pc():
    for bogus in (None, 123, ["a"], {"a": 1}):
        response = discovery.handle_discovery_packet(DISCOVER, bogus, 9000)
        assert json.loads(response)["name"] == "PC"


def test_non_ascii_hostname_survives_utf8_roundtrip():
    """한국어 PC 이름도 깨지지 않아야 한다(로그가 아니라 와이어 데이터다)."""
    response = discovery.handle_discovery_packet(DISCOVER, "내-PC", 9000)
    assert json.loads(response.decode("utf-8"))["name"] == "내-PC"


def test_server_name_helper_returns_non_empty_ascii_or_hostname():
    name = discovery.server_name()
    assert isinstance(name, str) and name
    assert len(name) <= discovery.MAX_NAME_LEN


# --------------------------------------------------------------------------
# 3. 순수 함수: 무시해야 하는 입력 (조용히 None)
# --------------------------------------------------------------------------

IGNORED_PACKETS = [
    pytest.param(b"", id="empty"),
    pytest.param(b"not-json{{{", id="garbage"),
    pytest.param(b"{", id="truncated-json"),
    pytest.param(b"{}", id="no-type"),
    pytest.param(b"[]", id="empty-array"),
    pytest.param(b"[1,2,3]", id="array"),
    pytest.param(b'["DISCOVER"]', id="array-of-type"),
    pytest.param(b'"DISCOVER"', id="bare-string"),
    pytest.param(b"123", id="number"),
    pytest.param(b"null", id="null"),
    pytest.param(b"true", id="bool"),
    pytest.param(b'{"type":"discover"}', id="lowercase-type"),
    pytest.param(b'{"type":"Discover"}', id="mixed-case-type"),
    pytest.param(b'{"type":"DISCOVER "}', id="type-trailing-space"),
    pytest.param(b'{"TYPE":"DISCOVER"}', id="uppercase-key"),
    pytest.param(b'{"type":"MOVE","dx":1,"dy":2}', id="move-event"),
    pytest.param(b'{"type":"HEARTBEAT"}', id="heartbeat"),
    pytest.param(b'{"type":"SERVER","name":"x","port":9000}', id="own-response-echo"),
    pytest.param(b'{"type":null}', id="null-type"),
    pytest.param(b'{"type":123}', id="numeric-type"),
    pytest.param(b'{"type":true}', id="bool-type"),
    pytest.param(b'{"type":["DISCOVER"]}', id="list-type"),
    pytest.param(b"\xff\xfe\xfd", id="not-utf8"),
    pytest.param(b"\x00" * 10, id="nul-bytes"),
    pytest.param(DISCOVER + b"\n" + DISCOVER, id="two-messages"),
    pytest.param(DISCOVER + b" " * (257 - len(DISCOVER)), id="257-bytes"),
    pytest.param(DISCOVER + b" " * 2000, id="way-too-big"),
    pytest.param(None, id="not-bytes-none"),
    pytest.param('{"type":"DISCOVER"}', id="not-bytes-str"),
]


@pytest.mark.parametrize("packet", IGNORED_PACKETS)
def test_invalid_packets_are_ignored_without_raising(packet):
    assert discovery.handle_discovery_packet(packet, "MY-PC", 9000) is None


def test_ignored_packet_count_covers_the_spec_minimum():
    assert len(IGNORED_PACKETS) >= 18


# --------------------------------------------------------------------------
# 4. 실제 루프백 UDP 왕복
# --------------------------------------------------------------------------

def test_real_udp_roundtrip_answers_discover():
    responder = start_responder(tcp_port=9000, name="MY-PC")
    try:
        assert responder.port not in (None, 0)
        with client_socket() as client:
            data = ask(client, responder.port)
        assert data is not None, "no discovery response"
        assert data == b'{"type":"SERVER","name":"MY-PC","port":9000}'
    finally:
        responder.stop()


def test_real_udp_roundtrip_ignores_non_discover_packets():
    responder = start_responder()
    try:
        with client_socket(timeout=0.3) as client:
            assert ask(client, responder.port, b'{"type":"MOVE"}', timeout=0.3) is None
            assert ask(client, responder.port, b"garbage", timeout=0.3) is None
    finally:
        responder.stop()


def test_responder_thread_survives_a_storm_of_bad_packets():
    """깨진/거대 패킷 뒤에도 스레드가 살아 정상 요청에 응답해야 한다."""
    responder = start_responder()
    try:
        with client_socket(timeout=0.2) as client:
            for bogus in (b"", b"garbage", b"\xff\xfe", b"[1,2,3]", b"x" * 4096):
                try:
                    client.sendto(bogus, (LOCALHOST, responder.port))
                except OSError:
                    pass  # 로컬 전송 제한 - 이 테스트의 관심사가 아니다
            data = ask(client, responder.port, timeout=1.0)
        assert data is not None, "responder stopped answering after bad packets"
        assert responder.running
    finally:
        responder.stop()


def test_stop_releases_the_port_and_stops_the_thread():
    responder = start_responder()
    port = responder.port
    thread = responder._thread
    responder.stop()

    assert thread is not None
    thread.join(timeout=2.0)
    assert not thread.is_alive()
    assert not responder.running
    # SO_REUSEADDR 를 쓰지 않으므로, 이 bind 가 성공한다는 건 포트가 실제로 풀렸다는 뜻이다.
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.bind((LOCALHOST, port))
    finally:
        probe.close()


def test_stop_returns_only_after_the_thread_is_gone():
    """stop() 이 join 하지 않으면 종료 직후에도 스레드가 소켓을 쥐고 있을 수 있다."""
    responder = start_responder()
    thread = responder._thread
    responder.stop()
    assert not thread.is_alive(), "stop() must join the receive thread"


def test_stop_is_idempotent():
    responder = start_responder()
    responder.stop()
    responder.stop()  # 예외 없이


def test_start_twice_keeps_one_socket():
    responder = start_responder()
    try:
        port = responder.port
        assert responder.start() is True
        assert responder.port == port
    finally:
        responder.stop()


def test_responder_does_not_set_so_reuseaddr():
    """Windows 에서 SO_REUSEADDR 는 이미 점유된 포트에도 bind 를 성공시킨다(섹션 10)."""
    responder = start_responder()
    try:
        value = responder._socket.getsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR)
        assert value == 0
    finally:
        responder.stop()


# --------------------------------------------------------------------------
# 5. 응답 남용 제한
# --------------------------------------------------------------------------

def test_rate_limiter_allows_five_then_denies():
    limiter = discovery.ResponseRateLimiter(monotonic=lambda: 100.0)
    assert [limiter.allow("1.2.3.4") for _ in range(6)] == [True] * 5 + [False]


def test_rate_limiter_allows_again_after_the_window():
    now = [100.0]
    limiter = discovery.ResponseRateLimiter(monotonic=lambda: now[0])
    for _ in range(5):
        assert limiter.allow("1.2.3.4") is True
    assert limiter.allow("1.2.3.4") is False
    now[0] = 100.0 + discovery.RATE_LIMIT_WINDOW_S
    assert limiter.allow("1.2.3.4") is True


def test_rate_limiter_separates_senders():
    limiter = discovery.ResponseRateLimiter(monotonic=lambda: 100.0)
    for _ in range(5):
        assert limiter.allow("1.2.3.4") is True
    assert limiter.allow("1.2.3.4") is False
    assert limiter.allow("5.6.7.8") is True


def test_rate_limiter_forgets_idle_senders():
    now = [100.0]
    limiter = discovery.ResponseRateLimiter(monotonic=lambda: now[0])
    for i in range(50):
        limiter.allow("10.0.0.%d" % i)
    assert limiter.tracked_senders() == 50
    now[0] = 100.0 + 5 * discovery.RATE_LIMIT_WINDOW_S
    limiter.allow("10.0.0.0")
    assert limiter.tracked_senders() == 1, "stale senders must be dropped"


def test_rate_limiter_table_stays_bounded_under_spoofed_senders():
    limiter = discovery.ResponseRateLimiter(max_senders=8, monotonic=lambda: 100.0)
    for i in range(200):
        limiter.allow("10.0.%d.%d" % (i // 256, i % 256))
    assert limiter.tracked_senders() <= 9  # 상한 + 방금 추가된 1개


def test_denied_requests_do_not_extend_the_window():
    now = [100.0]
    limiter = discovery.ResponseRateLimiter(monotonic=lambda: now[0])
    for _ in range(5):
        limiter.allow("1.2.3.4")
    now[0] = 100.5
    for _ in range(10):
        assert limiter.allow("1.2.3.4") is False  # 거부는 기록되지 않는다
    now[0] = 100.0 + discovery.RATE_LIMIT_WINDOW_S
    assert limiter.allow("1.2.3.4") is True


def test_sender_key_uses_ip_not_port():
    assert discovery.sender_key(("192.168.0.5", 41234)) == "192.168.0.5"
    assert discovery.sender_key(("192.168.0.5", 5)) == discovery.sender_key(
        ("192.168.0.5", 6)
    )


def test_responder_stops_answering_after_five_replies_in_a_window():
    """실제 소켓 + 고정 시계: 같은 발신자의 6번째 요청은 응답이 없다."""
    responder = start_responder(monotonic=lambda: 100.0)
    try:
        with client_socket(timeout=1.0) as client:
            answered = sum(1 for _ in range(5) if ask(client, responder.port) is not None)
            assert answered == 5
            assert ask(client, responder.port, timeout=0.3) is None
    finally:
        responder.stop()


# --------------------------------------------------------------------------
# 6. 바인딩 실패 - 탐색만 꺼지고 서버는 계속
# --------------------------------------------------------------------------

def test_start_returns_false_when_the_port_is_taken(capsys):
    holder = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    holder.bind((LOCALHOST, 0))
    taken = holder.getsockname()[1]
    try:
        responder = discovery.DiscoveryResponder(
            tcp_port=9000, host=LOCALHOST, discovery_port=taken
        )
        assert responder.start() is False
        assert not responder.running
        assert responder.port is None
        responder.stop()  # 실패 후 stop 도 안전해야 한다
    finally:
        holder.close()

    out = capsys.readouterr().out
    # OS 가 준 오류 문구는 한글일 수 있다(WinError 10048). 서버 **자체** 문구만 ASCII 계약이다
    # - test_server_shutdown.test_server_bind_failure_is_logged_in_ascii 와 같은 방식.
    ascii_only = "".join(ch for ch in out if ch.isascii())
    assert "[!] Discovery disabled:" in ascii_only


def test_discovery_disabled_log_is_ascii_when_the_error_is(capsys):
    """우리가 붙이는 문구에 비ASCII 를 섞지 않는다 (cp949 콘솔 UnicodeEncodeError)."""
    with patch.object(discovery.socket, "socket") as socket_cls:
        socket_cls.return_value.bind.side_effect = OSError("address already in use")
        responder = discovery.DiscoveryResponder(tcp_port=9000, discovery_port=9002)
        assert responder.start() is False

    out = capsys.readouterr().out
    assert out.strip() == "[!] Discovery disabled: address already in use"
    assert out.isascii()


def test_second_responder_on_the_same_port_stays_off(capsys):
    """서버가 두 번 떠도 탐색 응답이 두 개 나가지 않는다 (SO_REUSEADDR 를 안 쓰는 효과)."""
    first = start_responder()
    try:
        second = discovery.DiscoveryResponder(
            tcp_port=9000, host=LOCALHOST, discovery_port=first.port
        )
        assert second.start() is False
        assert not second.running
    finally:
        first.stop()


def test_start_returns_false_on_unbindable_host(capsys):
    responder = discovery.DiscoveryResponder(
        tcp_port=9000, host=UNBINDABLE_HOST, discovery_port=0
    )
    assert responder.start() is False
    assert capsys.readouterr().out.startswith("[!] Discovery disabled:")


def test_server_keeps_serving_when_discovery_cannot_bind(capsys):
    """탐색은 편의 기능이다 - 바인딩 실패가 서버 기동을 막으면 안 된다."""
    holder = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    holder.bind((LOCALHOST, 0))
    taken = holder.getsockname()[1]
    registry = server.SessionRegistry()
    runtime = server.ServerRuntime(
        InputController(), registry, host=LOCALHOST, tcp_port=0, udp_port=0,
        accept_timeout=0.05, discovery_port=taken,
    )
    thread = threading.Thread(target=runtime.run, daemon=True)
    thread.start()
    try:
        assert runtime.ready.wait(timeout=5), "server never became ready"
        assert runtime.discovery is not None and not runtime.discovery.running
        with socket.create_connection((LOCALHOST, runtime.tcp_port), timeout=2.0) as client:
            client.settimeout(2.0)
            # Phase 5: 클라이언트가 AUTH 줄을 먼저 보내야 SESSION 이 온다
            # (이 런타임은 인증이 꺼져 있으므로 pin 값은 무관하다)
            client.sendall(AUTH_LINE)
            event = json.loads(client.makefile("r", encoding="utf-8").readline())
            assert event["type"] == "SESSION"
    finally:
        runtime.stop()
        thread.join(timeout=3.0)
        holder.close()

    out = capsys.readouterr().out
    ascii_only = "".join(ch for ch in out if ch.isascii())
    assert "[!] Discovery disabled:" in ascii_only
    assert "Phone Pad Server listening on TCP port" in ascii_only


# --------------------------------------------------------------------------
# 7. ServerRuntime 통합
# --------------------------------------------------------------------------

def make_runtime(registry=None, **kwargs):
    kwargs.setdefault("host", LOCALHOST)
    kwargs.setdefault("tcp_port", 0)
    kwargs.setdefault("udp_port", 0)
    kwargs.setdefault("accept_timeout", 0.05)
    return server.ServerRuntime(
        InputController(),
        registry if registry is not None else server.SessionRegistry(),
        **kwargs,
    )


def run_runtime(runtime):
    thread = threading.Thread(target=runtime.run, daemon=True)
    thread.start()
    assert runtime.ready.wait(timeout=5), "server never became ready"
    return thread


def test_runtime_defaults_to_discovery_disabled():
    """기본값이 9002 면 ServerRuntime 을 만드는 모든 테스트가 실포트를 잡는다."""
    runtime = make_runtime()
    assert runtime.requested_discovery_port is None
    assert runtime.discovery is None


def test_runtime_never_binds_discovery_by_default():
    runtime = make_runtime()
    with patch.object(discovery, "DiscoveryResponder") as responder_cls:
        thread = run_runtime(runtime)
        runtime.stop()
        thread.join(timeout=3.0)
    responder_cls.assert_not_called()
    assert runtime.discovery is None


def test_runtime_with_discovery_port_answers_and_reports_tcp_port():
    registry = server.SessionRegistry()
    runtime = make_runtime(registry=registry, discovery_port=0)
    thread = run_runtime(runtime)
    try:
        assert runtime.discovery is not None and runtime.discovery.running
        with client_socket() as client:
            data = ask(client, runtime.discovery.port)
        assert data is not None
        parsed = json.loads(data)
        assert parsed["type"] == "SERVER"
        assert parsed["port"] == runtime.tcp_port  # 실제 바인드된 TCP 포트
        assert parsed["name"] == discovery.server_name()
        # 탐색은 상태를 바꾸지 않는다 - 세션도 만들지 않는다.
        assert registry.snapshot() == set()
    finally:
        runtime.stop()
        thread.join(timeout=3.0)


def test_runtime_stop_releases_the_discovery_port():
    runtime = make_runtime(discovery_port=0)
    thread = run_runtime(runtime)
    port = runtime.discovery.port
    runtime.stop()
    thread.join(timeout=3.0)

    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.bind((LOCALHOST, port))  # 안 풀렸으면 OSError
    finally:
        probe.close()


def test_runtime_close_alone_also_stops_discovery():
    """stop() 을 거치지 않는 종료 경로(close() 직접 호출)에서도 응답자가 내려가야 한다."""
    runtime = make_runtime(discovery_port=0)
    thread = run_runtime(runtime)
    responder = runtime.discovery
    port = responder.port
    try:
        runtime.close()
        assert not responder.running
        assert runtime.discovery is None
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            probe.bind((LOCALHOST, port))  # 안 풀렸으면 OSError
        finally:
            probe.close()
    finally:
        runtime.stop()
        thread.join(timeout=3.0)


def test_runtime_close_without_serve_is_safe():
    runtime = make_runtime(discovery_port=0)
    runtime.close()  # serve() 를 거치지 않은 경우에도 예외 없이
    assert runtime.discovery is None


# --------------------------------------------------------------------------
# 8. CLI / 상수 / 회귀
# --------------------------------------------------------------------------

def test_discovery_port_matches_protocol_spec():
    assert discovery.DISCOVERY_PORT == 9002
    # MOVE 채널(9001)/TCP(9000)와 섞이지 않아야 한다
    assert discovery.DISCOVERY_PORT not in (server.TCP_PORT, server.UDP_PORT)


def test_parse_args_enables_discovery_by_default():
    assert server.parse_args([]).no_discovery is False


def test_parse_args_accepts_no_discovery():
    assert server.parse_args(["--no-discovery"]).no_discovery is True


def test_main_passes_the_discovery_port_to_the_runtime():
    captured = {}

    def fake_console(controller, registry, runtime=None):
        captured["runtime"] = runtime
        return 0

    with patch("server.run_console", side_effect=fake_console), \
            patch("server.run_with_tray"), \
            patch("server.atexit.register"):
        assert server.main(["--no-tray"]) == 0
    assert captured["runtime"].requested_discovery_port == discovery.DISCOVERY_PORT


def test_main_with_no_discovery_disables_it():
    captured = {}

    def fake_console(controller, registry, runtime=None):
        captured["runtime"] = runtime
        return 0

    with patch("server.run_console", side_effect=fake_console), \
            patch("server.run_with_tray"), \
            patch("server.atexit.register"):
        assert server.main(["--no-tray", "--no-discovery"]) == 0
    assert captured["runtime"].requested_discovery_port is None


def test_existing_udp_move_channel_is_untouched():
    """탐색 추가가 MOVE 채널(9001) 규칙을 건드리지 않았는지."""
    registry = server.SessionRegistry()
    controller = InputController()
    token = registry.issue()
    packet = json.dumps({"session": token, "type": "MOVE", "dx": 1, "dy": 1}).encode("utf-8")
    with patch.object(controller, "_move") as mock_move:
        assert server.handle_udp_packet(packet, controller, registry) is True
    mock_move.assert_called_once_with(1, 1)
    # DISCOVER 는 MOVE 채널에서 아무 일도 하지 않는다
    assert server.handle_udp_packet(DISCOVER, controller, registry) is False
