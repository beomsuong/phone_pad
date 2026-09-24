"""PIN 코드 인증 테스트 (pin_auth.py + server.py 의 AUTH 단계).

구성:
  1. `generate_pin` / `parse_auth_message` / `pins_match` / `source_key` (순수 함수)
  2. `AuthAttemptLimiter` (시간 주입 - sleep 없이 창 경과를 재현한다)
  3. `handle_client` TCP end-to-end (`FakeConn` 으로 실제 구동, 소켓/SendInput 없음)
  4. `ServerRuntime` / `parse_args` / `main` 통합 (기본값은 "인증 없음")

**PIN 값이 로그에 새지 않는지**도 여기서 고정한다 (콘솔 시작 메시지 한 줄만 예외).
"""
import io
import json
import socket
import threading
from unittest.mock import MagicMock, patch

import pytest

import pin_auth
import server
from fake_conn import AUTH_LINE, FakeConn, make_auth_line
from input_controller import InputController
from send_input_stub import patch_send_input

PIN = "483920"
WRONG_PIN = "000000"
ADDR = ("10.0.0.7", 5555)
OTHER_ADDR = ("10.0.0.8", 5555)

CLICK_LINE = b'{"type":"CLICK","button":"left"}\n'


class FakeClock:
    """주입용 monotonic 시계. `advance()` 로 시간을 밀어준다."""

    def __init__(self, start: float = 1000.0):
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float):
        self.now += seconds


def run_client(conn, controller=None, registry=None, expected_pin=None,
               auth_limiter=None, addr=ADDR):
    controller = controller if controller is not None else InputController()
    registry = registry if registry is not None else server.SessionRegistry()
    server.handle_client(conn, addr, controller, registry, expected_pin, auth_limiter)
    return controller, registry


def raw_conn(chunks):
    """AUTH 줄 자동 공급을 끈 FakeConn (AUTH 단계 자체를 검증할 때)."""
    return FakeConn(chunks=chunks, auth_line=None)


# ==========================================================================
# 1. 순수 함수
# ==========================================================================

def test_generate_pin_is_six_digit_string():
    pin = pin_auth.generate_pin()
    assert isinstance(pin, str)
    assert len(pin) == pin_auth.PIN_DIGITS == 6
    assert pin.isdigit()


def test_generate_pin_many_calls_all_keep_the_format():
    pins = [pin_auth.generate_pin() for _ in range(300)]
    assert all(len(p) == 6 and p.isdigit() for p in pins)


def test_generate_pin_is_not_constant():
    """분포 확인 - 300회 뽑아 같은 값만 나오면 무작위성이 깨진 것이다."""
    assert len({pin_auth.generate_pin() for _ in range(300)}) > 10


@pytest.mark.parametrize("value,expected", [
    (0, "000000"),
    (7, "000007"),
    (42, "000042"),
    (999_999, "999999"),
])
def test_generate_pin_zero_pads_small_numbers(value, expected):
    with patch.object(pin_auth.secrets, "randbelow", return_value=value):
        assert pin_auth.generate_pin() == expected


def test_generate_pin_draws_from_the_full_six_digit_range_with_secrets():
    """`random` 이 아니라 `secrets` 를 써야 한다 - 이 값이 접근 제어의 전부다."""
    with patch.object(pin_auth.secrets, "randbelow", return_value=1) as randbelow:
        pin_auth.generate_pin()
    randbelow.assert_called_once_with(1_000_000)


# -- parse_auth_message -----------------------------------------------------

def test_parse_auth_message_accepts_wire_bytes():
    assert pin_auth.parse_auth_message(b'{"type":"AUTH","pin":"483920"}') == PIN


def test_parse_auth_message_accepts_str():
    assert pin_auth.parse_auth_message('{"type":"AUTH","pin":"483920"}') == PIN


def test_parse_auth_message_accepts_already_parsed_dict():
    assert pin_auth.parse_auth_message({"type": "AUTH", "pin": PIN}) == PIN


def test_parse_auth_message_accepts_bytearray():
    assert pin_auth.parse_auth_message(bytearray(b'{"type":"AUTH","pin":"1"}')) == "1"


def test_parse_auth_message_tolerates_surrounding_whitespace_and_newline():
    assert pin_auth.parse_auth_message(b'  {"type":"AUTH","pin":"1"}\r\n') == "1"


def test_parse_auth_message_keeps_leading_zeros():
    assert pin_auth.parse_auth_message(b'{"type":"AUTH","pin":"000042"}') == "000042"


def test_parse_auth_message_accepts_empty_pin_value():
    """인증이 꺼진 서버를 상대할 때 클라이언트가 쓰는 값 - 형식은 유효하다."""
    assert pin_auth.parse_auth_message(b'{"type":"AUTH","pin":""}') == ""


def test_parse_auth_message_ignores_extra_fields():
    assert pin_auth.parse_auth_message(
        b'{"type":"AUTH","pin":"1","session":"x","extra":[1,2]}'
    ) == "1"


@pytest.mark.parametrize("payload", [
    b"",                                  # 빈 바이트열
    b"   ",                               # 공백만
    b"\n",                                # 빈 줄
    b"garbage",                           # JSON 아님
    b'{"type":"AUTH","pin":"1"',          # 잘린 JSON
    b"[1,2,3]",                           # dict 아님 (리스트)
    b"42",                                # dict 아님 (숫자)
    b'"AUTH"',                            # dict 아님 (문자열)
    b"true",                              # dict 아님 (bool)
    b"null",                              # dict 아님 (null)
    b"{}",                                # type 없음
    b'{"pin":"483920"}',                  # type 없음
    b'{"type":"auth","pin":"1"}',         # 소문자 type
    b'{"type":"SESSION","pin":"1"}',      # 다른 type
    b'{"type":"HEARTBEAT"}',              # 이벤트가 먼저 옴
    b'{"type":"AUTH"}',                   # pin 누락
    b'{"type":"AUTH","pin":483920}',      # pin 이 숫자
    b'{"type":"AUTH","pin":null}',        # pin 이 null
    b'{"type":"AUTH","pin":true}',        # pin 이 bool
    b'{"type":"AUTH","pin":["1"]}',       # pin 이 리스트
    b'{"type":"AUTH","pin":{"a":1}}',     # pin 이 dict
    b"\xff\xfe\x00",                      # UTF-8 아님
])
def test_parse_auth_message_rejects_malformed_input(payload):
    assert pin_auth.parse_auth_message(payload) is None


@pytest.mark.parametrize("value", [None, 42, 3.5, object(), [1, 2], ("a",), {1: 2}])
def test_parse_auth_message_never_raises_on_weird_objects(value):
    assert pin_auth.parse_auth_message(value) is None


# -- pins_match -------------------------------------------------------------

def test_pins_match_equal_strings():
    assert pin_auth.pins_match("483920", "483920") is True


def test_pins_match_uses_constant_time_comparison():
    """구현이 `==` 로 되돌아가지 않도록 compare_digest 사용을 고정한다."""
    with patch.object(pin_auth.hmac, "compare_digest", return_value=True) as cmp_:
        assert pin_auth.pins_match("1", "2") is True
    cmp_.assert_called_once_with(b"1", b"2")


@pytest.mark.parametrize("actual,expected", [
    ("483920", "483921"),
    ("48392", "483920"),      # 길이가 짧음
    ("4839201", "483920"),    # 길이가 김
    ("", "483920"),
    ("483920", ""),
    (" 483920", "483920"),    # 공백도 다른 값이다
])
def test_pins_match_rejects_different_values(actual, expected):
    assert pin_auth.pins_match(actual, expected) is False


def test_pins_match_empty_against_empty():
    assert pin_auth.pins_match("", "") is True


@pytest.mark.parametrize("actual,expected", [
    (None, "483920"),
    ("483920", None),
    (483920, "483920"),
    (b"483920", "483920"),
    (["4"], "483920"),
])
def test_pins_match_rejects_non_strings(actual, expected):
    assert pin_auth.pins_match(actual, expected) is False


def test_pins_match_handles_non_ascii_without_raising():
    """`compare_digest` 는 non-ASCII str 에 TypeError 를 던진다 - 바이트로 비교한다."""
    assert pin_auth.pins_match("비밀", "비밀") is True
    assert pin_auth.pins_match("비밀", "483920") is False


# -- source_key -------------------------------------------------------------

@pytest.mark.parametrize("addr,expected", [
    (("10.0.0.7", 5555), "10.0.0.7"),
    (("10.0.0.7", 1), "10.0.0.7"),
    ("10.0.0.7", "10.0.0.7"),
    ((), ()),
    (None, None),
])
def test_source_key_is_ip_only(addr, expected):
    assert pin_auth.source_key(addr) == expected


def test_source_key_ignores_port_so_lockout_actually_works():
    """포트까지 키에 넣으면 연결마다 키가 바뀌어 브루트포스 방어가 무력해진다."""
    assert pin_auth.source_key(("10.0.0.7", 1)) == pin_auth.source_key(("10.0.0.7", 2))


# -- 와이어 상수 ------------------------------------------------------------

def test_auth_fail_line_is_exact_wire_format():
    assert pin_auth.AUTH_FAIL_LINE == b'{"type":"AUTH_FAIL","reason":"invalid_pin"}\n'


def test_auth_constants_match_spec():
    assert pin_auth.AUTH_TYPE == "AUTH"
    assert pin_auth.FAILURE_LIMIT == 5
    assert pin_auth.FAILURE_WINDOW_S == 60.0
    assert server.AUTH_TIMEOUT_S == 3.0


# ==========================================================================
# 2. AuthAttemptLimiter
# ==========================================================================

def make_limiter(clock=None, log=None, **kwargs):
    return pin_auth.AuthAttemptLimiter(
        monotonic=clock if clock is not None else FakeClock(),
        log=log if log is not None else (lambda _msg: None),
        **kwargs,
    )


def test_fresh_source_is_not_locked_out():
    assert make_limiter().is_locked_out("10.0.0.7") is False


def test_four_failures_do_not_lock_out():
    limiter = make_limiter()
    for _ in range(4):
        assert limiter.record_failure("10.0.0.7") is False
    assert limiter.is_locked_out("10.0.0.7") is False
    assert limiter.failure_count("10.0.0.7") == 4


def test_fifth_failure_locks_out():
    limiter = make_limiter()
    for _ in range(4):
        limiter.record_failure("10.0.0.7")
    assert limiter.record_failure("10.0.0.7") is True
    assert limiter.is_locked_out("10.0.0.7") is True


def test_lockout_persists_for_further_attempts_within_the_window():
    clock = FakeClock()
    limiter = make_limiter(clock)
    for _ in range(5):
        limiter.record_failure("10.0.0.7")
    clock.advance(59.0)
    assert limiter.is_locked_out("10.0.0.7") is True


def test_lockout_is_released_after_the_window_passes():
    clock = FakeClock()
    limiter = make_limiter(clock)
    for _ in range(5):
        limiter.record_failure("10.0.0.7")
    clock.advance(60.1)
    assert limiter.is_locked_out("10.0.0.7") is False
    assert limiter.failure_count("10.0.0.7") == 0


def test_failures_spread_wider_than_the_window_never_lock_out():
    clock = FakeClock()
    limiter = make_limiter(clock)
    for _ in range(10):
        assert limiter.record_failure("10.0.0.7") is False
        clock.advance(20.0)  # 창(60s)에 최대 3회만 남는다
    assert limiter.is_locked_out("10.0.0.7") is False


def test_old_failures_fall_out_of_the_window_one_by_one():
    clock = FakeClock()
    limiter = make_limiter(clock)
    for _ in range(4):
        limiter.record_failure("10.0.0.7")
    clock.advance(59.9)
    assert limiter.record_failure("10.0.0.7") is True  # 5회 모두 창 안
    clock.advance(0.2)  # 처음 4회가 창을 벗어난다
    assert limiter.is_locked_out("10.0.0.7") is False
    assert limiter.failure_count("10.0.0.7") == 1


def test_lockout_is_per_source():
    limiter = make_limiter()
    for _ in range(5):
        limiter.record_failure("10.0.0.7")
    assert limiter.is_locked_out("10.0.0.7") is True
    assert limiter.is_locked_out("10.0.0.8") is False


def test_limiter_accepts_addr_tuples_via_source_key():
    limiter = make_limiter()
    for port in range(1, 6):  # 연결마다 포트가 바뀌어도 같은 IP 로 집계된다
        limiter.record_failure(pin_auth.source_key(("10.0.0.7", port)))
    assert limiter.is_locked_out(pin_auth.source_key(("10.0.0.7", 99))) is True


def test_sender_table_is_capped():
    clock = FakeClock()
    limiter = make_limiter(clock, max_sources=4)
    for i in range(40):
        limiter.record_failure("10.0.0.%d" % i)
        clock.advance(0.001)
    assert limiter.tracked_sources() <= 5  # 상한 + 방금 추가된 1개


def test_expired_sources_are_pruned_from_the_table():
    clock = FakeClock()
    limiter = make_limiter(clock)
    for i in range(5):
        limiter.record_failure("10.0.0.%d" % i)
    assert limiter.tracked_sources() == 5
    clock.advance(61.0)
    limiter.record_failure("10.0.1.1")
    assert limiter.tracked_sources() == 1


def test_concurrent_failures_are_all_counted():
    limiter = make_limiter()
    barrier = threading.Barrier(8)

    def hammer():
        barrier.wait()
        limiter.record_failure("10.0.0.7")

    threads = [threading.Thread(target=hammer) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=5)
    assert limiter.failure_count("10.0.0.7") == 8
    assert limiter.is_locked_out("10.0.0.7") is True


# -- 로그 -------------------------------------------------------------------

def test_lockout_is_logged_once_per_interval_in_ascii():
    clock = FakeClock()
    lines = []
    limiter = make_limiter(clock, log=lines.append)
    for _ in range(8):  # 5회째부터 매번 잠금 상태지만 로그는 창당 한 줄
        limiter.record_failure("10.0.0.7")
    assert len(lines) == 1
    assert lines[0].startswith("[!] Auth lockout for 10.0.0.7")
    assert lines[0].isascii(), "cp949 콘솔에서 UnicodeEncodeError 를 내면 안 된다"


def test_lockout_log_appears_again_after_the_log_interval():
    clock = FakeClock()
    lines = []
    limiter = make_limiter(clock, log=lines.append)
    for _ in range(5):
        limiter.record_failure("10.0.0.7")
    clock.advance(pin_auth.LOG_INTERVAL_S + 0.1)
    limiter.record_failure("10.0.0.7")
    assert len(lines) == 2


def test_blocked_connections_are_logged_once_per_interval():
    clock = FakeClock()
    lines = []
    limiter = make_limiter(clock, log=lines.append)
    for _ in range(50):
        limiter.note_blocked("10.0.0.7")
    assert len(lines) == 1
    assert lines[0].isascii()
    assert "10.0.0.7" in lines[0]


def test_limiter_log_never_contains_a_pin_value():
    """실패 로그에는 기대값도, 받은 값도 남기지 않는다."""
    lines = []
    limiter = make_limiter(log=lines.append)
    for _ in range(5):
        limiter.record_failure("10.0.0.7")
    limiter.note_blocked("10.0.0.7")
    joined = " ".join(lines)
    assert PIN not in joined
    assert WRONG_PIN not in joined


def test_failing_log_stream_does_not_break_counting():
    def boom(_msg):
        raise ValueError("I/O operation on closed file")

    limiter = make_limiter(log=boom)
    for _ in range(5):
        limiter.record_failure("10.0.0.7")
    assert limiter.is_locked_out("10.0.0.7") is True


# ==========================================================================
# 3. handle_client TCP end-to-end
# ==========================================================================

# -- 인증 없음 (기본값) -----------------------------------------------------

def test_auth_disabled_still_requires_the_auth_line_and_then_issues_session():
    conn = FakeConn()  # AUTH 줄 자동 공급
    _controller, registry = run_client(conn)

    assert conn.sent_types() == ["SESSION"]
    assert registry.snapshot() == set()  # 연결 종료 후 회수
    assert conn.closed


@pytest.mark.parametrize("pin_value", ["", "483920", "whatever", "0" * 100])
def test_auth_disabled_accepts_any_pin_value(pin_value):
    conn = raw_conn([make_auth_line(pin_value)])
    run_client(conn, expected_pin=None)
    assert conn.sent_types() == ["SESSION"]


def test_auth_disabled_never_consults_the_limiter():
    """인증이 꺼져 있으면 브루트포스 추적 자체를 하지 않는다."""
    limiter = MagicMock()
    conn = raw_conn([AUTH_LINE])
    run_client(conn, expected_pin=None, auth_limiter=limiter)

    assert conn.sent_types() == ["SESSION"]
    limiter.is_locked_out.assert_not_called()
    limiter.record_failure.assert_not_called()


# -- 인증 켜짐: 정답 --------------------------------------------------------

def test_correct_pin_gets_the_unchanged_session_line():
    registry = server.SessionRegistry()
    tokens = []

    class Probe(FakeConn):
        def _recv_event(self, size):
            tokens.append(registry.snapshot())
            return super()._recv_event(size)

    conn = Probe(chunks=[make_auth_line(PIN)], auth_line=None)
    run_client(conn, registry=registry, expected_pin=PIN)

    lines = conn.sent_lines()
    assert len(lines) == 1
    message = json.loads(lines[0])
    assert message["type"] == "SESSION"
    assert len(message["session"]) == 32
    # 세션은 연결 유지 중 활성이었고, 끊긴 뒤 회수된다 (기존 동작 무변경).
    # tokens[0] 은 AUTH 줄을 읽던 시점이라 아직 비어 있다 (세션은 그 뒤에 발급된다).
    assert tokens[0] == set()
    assert tokens[-1] == {message["session"]}
    assert registry.snapshot() == set()


def test_correct_pin_then_events_are_processed_as_before():
    conn = raw_conn([make_auth_line(PIN), CLICK_LINE])
    controller = InputController()

    with patch.object(controller, "_click") as click:
        run_client(conn, controller=controller, expected_pin=PIN)

    click.assert_called_once_with("left")


def test_correct_pin_clears_nothing_but_still_records_no_failure():
    limiter = make_limiter()
    conn = raw_conn([make_auth_line(PIN)])
    run_client(conn, expected_pin=PIN, auth_limiter=limiter)
    assert limiter.failure_count("10.0.0.7") == 0


def test_events_pipelined_with_the_auth_line_are_not_lost():
    """AUTH 줄과 같은 청크에 이벤트가 붙어 와도 처리돼야 한다."""
    conn = raw_conn([make_auth_line(PIN) + CLICK_LINE])
    controller = InputController()

    with patch.object(controller, "_click") as click:
        run_client(conn, controller=controller, expected_pin=PIN)

    click.assert_called_once_with("left")


def test_auth_line_split_across_chunks_is_accepted():
    conn = raw_conn([b'{"type":"AUTH",', b'"pin":"483920"}\n'])
    run_client(conn, expected_pin=PIN)
    assert conn.sent_types() == ["SESSION"]


def test_server_accepts_the_android_auth_wire_literal():
    """Android `AuthHandshakeTest` 가 고정한 리터럴을 그대로 통과시켜야 한다.

    한쪽이 키 이름/철자/키 순서를 바꾸면 이 테스트가 잡는다 (O-2 와 같은 방식 -
    `test_handle_client_processes_android_scroll_wire_literal` 참조).
    """
    android_literal = b'{"type":"AUTH","pin":"483920"}\n'
    conn = raw_conn([android_literal])
    run_client(conn, expected_pin="483920")
    assert conn.sent_types() == ["SESSION"]


def test_server_accepts_the_android_empty_pin_literal_when_auth_is_off():
    conn = raw_conn([b'{"type":"AUTH","pin":""}\n'])
    run_client(conn, expected_pin=None)
    assert conn.sent_types() == ["SESSION"]


def test_server_decodes_json_escapes_in_the_pin():
    """Android 는 `"`/`\\` 를 이스케이프해 보낸다 - 서버는 디코딩한 값으로 비교한다."""
    conn = raw_conn([b'{"type":"AUTH","pin":"a\\"b\\\\c"}\n'])
    run_client(conn, expected_pin='a"b\\c')
    assert conn.sent_types() == ["SESSION"]


def test_auth_read_uses_its_own_shorter_timeout_then_heartbeat_timeout():
    conn = raw_conn([make_auth_line(PIN)])
    run_client(conn, expected_pin=PIN)
    assert conn.timeouts == [server.AUTH_TIMEOUT_S, server.HEARTBEAT_INTERVAL_S]


# -- 인증 켜짐: 오답 --------------------------------------------------------

def test_wrong_pin_gets_auth_fail_and_the_connection_is_closed():
    conn = raw_conn([make_auth_line(WRONG_PIN)])
    _controller, registry = run_client(conn, expected_pin=PIN)

    assert conn.sent == [pin_auth.AUTH_FAIL_LINE]
    assert conn.sent_types() == ["AUTH_FAIL"]
    assert json.loads(conn.sent_lines()[0])["reason"] == "invalid_pin"
    assert conn.closed
    assert registry.snapshot() == set()


def test_wrong_pin_issues_no_session_at_all():
    conn = raw_conn([make_auth_line(WRONG_PIN)])
    registry = server.SessionRegistry()
    with patch.object(registry, "issue", wraps=registry.issue) as issue:
        run_client(conn, registry=registry, expected_pin=PIN)
    issue.assert_not_called()


def test_wrong_pin_processes_nothing_that_follows():
    """오답 뒤에 이벤트를 붙여 보내도 SendInput 까지 가지 않는다."""
    conn = raw_conn([make_auth_line(WRONG_PIN) + CLICK_LINE, CLICK_LINE])
    controller = InputController()

    with patch_send_input() as send_input, patch.object(controller, "_click") as click:
        run_client(conn, controller=controller, expected_pin=PIN)

    click.assert_not_called()
    send_input.assert_not_called()
    # AUTH 줄을 읽은 1회가 전부 - SESSION 이후의 이벤트 루프에 들어가지 않는다
    assert conn.recv_calls == 1


def test_wrong_pin_records_a_failure_for_that_ip():
    limiter = make_limiter()
    run_client(raw_conn([make_auth_line(WRONG_PIN)]), expected_pin=PIN, auth_limiter=limiter)
    assert limiter.failure_count("10.0.0.7") == 1


def test_wrong_pin_without_a_limiter_still_sends_auth_fail():
    """집계기를 안 넘긴 호출(단위 테스트 등)도 판정 자체는 정상 동작한다."""
    conn = raw_conn([make_auth_line(WRONG_PIN)])
    run_client(conn, expected_pin=PIN, auth_limiter=None)
    assert conn.sent == [pin_auth.AUTH_FAIL_LINE]


def test_auth_fail_send_failure_does_not_raise():
    class FailingConn(FakeConn):
        def sendall(self, data):
            raise ConnectionResetError("client vanished")

    conn = FailingConn(chunks=[make_auth_line(WRONG_PIN)], auth_line=None)
    run_client(conn, expected_pin=PIN)
    assert conn.closed


# -- 형식 오류 / 타임아웃: 조용히 닫기 --------------------------------------

@pytest.mark.parametrize("first_line", [
    b"garbage\n",                          # JSON 아님
    b"[1,2,3]\n",                          # dict 아님
    b'"AUTH"\n',                           # dict 아님
    b"{}\n",                               # type 없음
    b'{"type":"auth","pin":"483920"}\n',   # 소문자 type
    b'{"type":"CLICK","button":"left"}\n',  # AUTH 가 아닌 첫 줄
    b'{"type":"HEARTBEAT"}\n',             # AUTH 가 아닌 첫 줄
    b'{"type":"AUTH"}\n',                  # pin 누락
    b'{"type":"AUTH","pin":483920}\n',     # pin 이 숫자
    b'{"type":"AUTH","pin":null}\n',       # pin 이 null
    b"\n",                                 # 빈 줄
    b"\xff\xfe\n",                         # UTF-8 아님
])
@pytest.mark.parametrize("expected_pin", [None, PIN])
def test_malformed_first_line_closes_silently(first_line, expected_pin):
    """응답을 만들 정보가 없거나 실패 통보가 무의미한 경우 - 아무것도 보내지 않는다."""
    conn = raw_conn([first_line, CLICK_LINE])
    controller = InputController()

    with patch_send_input() as send_input:
        _c, registry = run_client(
            conn, controller=controller, expected_pin=expected_pin
        )

    assert conn.sent == [], "AUTH_FAIL 은 값이 틀린 경우에만 보낸다"
    assert conn.closed
    assert registry.snapshot() == set()
    send_input.assert_not_called()


def test_auth_timeout_closes_silently():
    conn = raw_conn([socket.timeout("timed out")])
    run_client(conn, expected_pin=PIN)
    assert conn.sent == []
    assert conn.closed


def test_eof_before_the_auth_line_closes_silently():
    conn = raw_conn([b""])
    run_client(conn, expected_pin=PIN)
    assert conn.sent == []
    assert conn.closed


def test_socket_error_during_the_auth_read_closes_silently():
    conn = raw_conn([ConnectionResetError("client vanished")])
    run_client(conn, expected_pin=PIN)
    assert conn.sent == []
    assert conn.closed


def test_partial_auth_line_then_timeout_closes_silently():
    conn = raw_conn([b'{"type":"AUTH","pin":"48', socket.timeout("timed out")])
    run_client(conn, expected_pin=PIN)
    assert conn.sent == []
    assert conn.closed


def test_oversized_first_line_is_dropped_without_reading_more():
    """개행 없이 밀어넣는 상대에게 메모리를 무한히 내주지 않는다."""
    conn = raw_conn([b"x" * (server.AUTH_MAX_LINE_CHARS + 1), CLICK_LINE])
    run_client(conn, expected_pin=PIN)
    assert conn.sent == []
    assert conn.recv_calls == 1
    assert conn.closed


def test_malformed_first_line_is_not_counted_as_a_pin_failure():
    """형식 오류로 잠금이 걸리면 버그 있는 클라이언트가 자기를 밴시킨다."""
    limiter = make_limiter()
    for _ in range(10):
        run_client(raw_conn([b"garbage\n"]), expected_pin=PIN, auth_limiter=limiter)
    assert limiter.is_locked_out("10.0.0.7") is False


# -- 브루트포스 방어 --------------------------------------------------------

def fail_five_times(limiter, expected_pin=PIN, addr=ADDR):
    for _ in range(5):
        conn = raw_conn([make_auth_line(WRONG_PIN)])
        run_client(conn, expected_pin=expected_pin, auth_limiter=limiter, addr=addr)
        assert conn.sent == [pin_auth.AUTH_FAIL_LINE]


def test_sixth_connection_after_five_failures_is_dropped_without_reading():
    limiter = make_limiter()
    fail_five_times(limiter)

    # 6번째는 **정답 PIN 을 들고 와도** 읽히지 않는다
    conn = raw_conn([make_auth_line(PIN), CLICK_LINE])
    controller = InputController()
    with patch_send_input() as send_input:
        _c, registry = run_client(
            conn, controller=controller, expected_pin=PIN, auth_limiter=limiter
        )

    # close() 전 큐를 비우려고 recv()를 정확히 1회만 시도한다(F-1 수정 —
    # 비우지 않으면 실소켓에서 RST가 나가 클라이언트가 clean EOF 대신
    # SocketException을 본다, test_real_socket_lockout_closes_cleanly_not_with_a_reset
    # 참조). AUTH 줄의 **내용**은 어차피 버려지므로 인증 로직에는 영향 없다.
    assert conn.recv_calls == 1, "close 전 큐 비우기 1회만, AUTH 내용은 안 씀"
    assert conn.sent == [], "잠금 상태를 알려주면 공격자에게 정보를 주는 것"
    assert conn.closed
    assert registry.snapshot() == set()
    send_input.assert_not_called()


def test_locked_out_connection_is_indistinguishable_from_a_format_error():
    """와이어 상으로 '조용히 닫기' 와 완전히 같아야 한다."""
    limiter = make_limiter()
    fail_five_times(limiter)

    locked = raw_conn([make_auth_line(PIN)])
    run_client(locked, expected_pin=PIN, auth_limiter=limiter)
    malformed = raw_conn([b"garbage\n"])
    run_client(malformed, expected_pin=PIN, auth_limiter=limiter, addr=OTHER_ADDR)

    assert locked.sent == malformed.sent == []
    assert locked.closed and malformed.closed


def test_lockout_only_affects_the_offending_ip():
    limiter = make_limiter()
    fail_five_times(limiter)

    other = raw_conn([make_auth_line(PIN)])
    run_client(other, expected_pin=PIN, auth_limiter=limiter, addr=OTHER_ADDR)
    assert other.sent_types() == ["SESSION"]


def test_lockout_expires_and_the_ip_can_connect_again():
    clock = FakeClock()
    limiter = make_limiter(clock)
    fail_five_times(limiter)

    clock.advance(61.0)
    conn = raw_conn([make_auth_line(PIN)])
    run_client(conn, expected_pin=PIN, auth_limiter=limiter)
    assert conn.sent_types() == ["SESSION"]


def test_four_failures_do_not_block_the_fifth_connection():
    limiter = make_limiter()
    for _ in range(4):
        run_client(raw_conn([make_auth_line(WRONG_PIN)]), expected_pin=PIN,
                   auth_limiter=limiter)

    conn = raw_conn([make_auth_line(PIN)])
    run_client(conn, expected_pin=PIN, auth_limiter=limiter)
    assert conn.sent_types() == ["SESSION"]


def test_without_a_limiter_there_is_no_lockout():
    for _ in range(10):
        conn = raw_conn([make_auth_line(WRONG_PIN)])
        run_client(conn, expected_pin=PIN, auth_limiter=None)
        assert conn.sent == [pin_auth.AUTH_FAIL_LINE]


def test_lockout_check_happens_before_any_read_even_with_auth_disabled_limiter():
    """인증이 꺼져 있으면 잠금 검사도 하지 않는다 (추적 자체를 안 하므로)."""
    limiter = make_limiter()
    for _ in range(5):
        limiter.record_failure("10.0.0.7")

    conn = raw_conn([AUTH_LINE])
    run_client(conn, expected_pin=None, auth_limiter=limiter)
    assert conn.sent_types() == ["SESSION"]


# -- PIN 이 로그에 새지 않는지 ----------------------------------------------

def test_successful_auth_never_logs_the_pin(capsys):
    run_client(raw_conn([make_auth_line(PIN)]), expected_pin=PIN)
    out = capsys.readouterr().out
    assert PIN not in out
    assert out.isascii()


def test_failed_auth_logs_the_address_but_never_the_pin(capsys):
    conn = raw_conn([make_auth_line(WRONG_PIN)])
    run_client(conn, expected_pin=PIN)
    out = capsys.readouterr().out

    assert "[!] Auth failed for" in out
    assert "10.0.0.7" in out
    assert PIN not in out
    assert WRONG_PIN not in out
    assert out.isascii(), "cp949 콘솔에서 UnicodeEncodeError 를 내면 안 된다"


def test_lockout_path_never_logs_the_pin(capsys):
    limiter = pin_auth.AuthAttemptLimiter(monotonic=FakeClock())  # 기본 log=print
    fail_five_times(limiter)
    run_client(raw_conn([make_auth_line(PIN)]), expected_pin=PIN, auth_limiter=limiter)

    out = capsys.readouterr().out
    assert "Auth lockout" in out
    assert PIN not in out
    assert WRONG_PIN not in out
    assert out.isascii()


# ==========================================================================
# 4. ServerRuntime / parse_args / main
# ==========================================================================

def test_runtime_defaults_to_no_authentication():
    """기본값이 '인증 없음' 이어야 기존 테스트/호출자가 깨지지 않는다."""
    runtime = server.ServerRuntime(InputController(), server.SessionRegistry())
    assert runtime.expected_pin is None
    assert runtime.auth_limiter is None


def test_runtime_with_a_pin_creates_a_shared_limiter():
    runtime = server.ServerRuntime(
        InputController(), server.SessionRegistry(), expected_pin=PIN
    )
    assert runtime.expected_pin == PIN
    assert isinstance(runtime.auth_limiter, pin_auth.AuthAttemptLimiter)


def test_runtime_accepts_an_injected_limiter():
    limiter = make_limiter()
    runtime = server.ServerRuntime(
        InputController(), server.SessionRegistry(), expected_pin=PIN,
        auth_limiter=limiter,
    )
    assert runtime.auth_limiter is limiter


def real_runtime(expected_pin):
    return server.ServerRuntime(
        InputController(), server.SessionRegistry(), host="127.0.0.1",
        tcp_port=0, udp_port=0, accept_timeout=0.05, expected_pin=expected_pin,
    )


def exchange(runtime, auth_line):
    """실제 소켓으로 AUTH 를 보내고 서버가 돌려준 첫 줄을 읽는다 (없으면 "")."""
    thread = threading.Thread(target=runtime.run, daemon=True)
    thread.start()
    try:
        assert runtime.ready.wait(timeout=5), "server never became ready"
        with socket.create_connection(("127.0.0.1", runtime.tcp_port), timeout=3.0) as client:
            client.settimeout(3.0)
            client.sendall(auth_line)
            return client.makefile("r", encoding="utf-8").readline()
    finally:
        runtime.stop()
        thread.join(timeout=3.0)


def test_real_socket_correct_pin_gets_a_session():
    runtime = real_runtime(PIN)
    line = exchange(runtime, make_auth_line(PIN))
    assert json.loads(line)["type"] == "SESSION"


def test_real_socket_wrong_pin_gets_auth_fail_then_eof():
    runtime = real_runtime(PIN)
    line = exchange(runtime, make_auth_line(WRONG_PIN))
    assert json.loads(line) == {"type": "AUTH_FAIL", "reason": "invalid_pin"}


def test_real_socket_malformed_first_line_gets_nothing():
    runtime = real_runtime(PIN)
    assert exchange(runtime, b"garbage\n") == ""


def test_real_socket_lockout_closes_cleanly_not_with_a_reset():
    """protocol-qa F-1: 잠금 close 가 미판독 바이트를 남기면 커널이 RST 를 보내
    JVM 쪽에서 clean EOF(HANDSHAKE_FAILED) 대신 `SocketException`(UNKNOWN)으로
    보인다 - 잠금이 형식 오류와 구분되지 않아야 한다는 스펙이 바이트 수준에서
    깨진다. `authenticate_client`가 close 전에 큐를 비우므로, 실소켓에서
    `recv()`가 예외 없이 빈 바이트(EOF)를 돌려줘야 한다.
    """
    limiter = server.pin_auth.AuthAttemptLimiter()
    for _ in range(5):
        limiter.record_failure("127.0.0.1")
    runtime = server.ServerRuntime(
        InputController(), server.SessionRegistry(), host="127.0.0.1",
        tcp_port=0, udp_port=0, accept_timeout=0.05, expected_pin=PIN,
        auth_limiter=limiter,
    )
    thread = threading.Thread(target=runtime.run, daemon=True)
    thread.start()
    try:
        assert runtime.ready.wait(timeout=5), "server never became ready"
        with socket.create_connection(("127.0.0.1", runtime.tcp_port), timeout=3.0) as client:
            client.sendall(make_auth_line(PIN))
            client.settimeout(3.0)
            # recv()가 빈 바이트(EOF)를 돌려줘야 한다 - ConnectionResetError 등
            # OSError가 나면 RST 가 간 것이고 F-1이 재발한 것이다.
            assert client.recv(4096) == b""
    finally:
        runtime.stop()
        thread.join(timeout=3.0)


# -- CLI -------------------------------------------------------------------

def test_parse_args_defaults_keep_auth_on_with_a_generated_pin():
    args = server.parse_args([])
    assert args.pin is None
    assert args.no_auth is False
    pin = server.resolve_expected_pin(args)
    assert len(pin) == 6 and pin.isdigit()


def test_no_auth_flag_disables_authentication():
    args = server.parse_args(["--no-auth"])
    assert args.no_auth is True
    assert server.resolve_expected_pin(args) is None


def test_pin_flag_is_used_verbatim():
    args = server.parse_args(["--pin", "112233"])
    assert args.pin == "112233"
    assert server.resolve_expected_pin(args) == "112233"


def test_no_auth_wins_over_an_explicit_pin():
    args = server.parse_args(["--pin", "112233", "--no-auth"])
    assert server.resolve_expected_pin(args) is None


def test_blank_pin_is_rejected_instead_of_becoming_the_expected_value():
    """protocol-qa F-2: `--pin ""`을 그대로 받아들이면 "인증 켜짐 + 기대값 빈
    문자열"이 되는데, 앱은 빈 PIN 전송을 막고 있어 누구도 접속할 수 없고 5회
    시도하면 자기 IP가 잠긴다 - "아무 pin이나 통과"의 정반대다. 받는 시점에
    거부해야 한다.
    """
    for blank in ("", "   "):
        args = server.parse_args(["--pin", blank])
        with pytest.raises(SystemExit):
            server.resolve_expected_pin(args)


def test_pin_flag_is_stripped_to_match_the_apps_trim():
    """W-4: 앱의 `onPinInputChange`가 trim()하므로 서버도 앞뒤 공백을 제거해야
    `--pin ' 483920 '`이 영원히 불일치하는 사고를 피한다."""
    args = server.parse_args(["--pin", " 483920 "])
    assert server.resolve_expected_pin(args) == "483920"


def test_other_flags_are_unchanged():
    args = server.parse_args([])
    assert (args.no_tray, args.allow_multiple, args.no_discovery) == (False, False, False)


def run_main(argv):
    """main() 을 콘솔 모드로 돌리되 실제 서버는 띄우지 않는다."""
    with patch("server.run_console", return_value=0) as console, \
            patch("server.atexit.register"):
        assert server.main(argv) == 0
    return console


def test_main_prints_the_generated_pin_exactly_once_and_hands_it_to_the_runtime(capsys):
    console = run_main(["--no-tray"])
    out = capsys.readouterr().out

    lines = [l for l in out.splitlines() if "PIN for this session" in l]
    assert len(lines) == 1
    assert out.isascii(), "cp949 콘솔에서 UnicodeEncodeError 를 내면 안 된다"

    pin = lines[0].split("PIN for this session: ")[1].strip()
    assert len(pin) == 6 and pin.isdigit()
    assert lines[0] == f"[Server] PIN for this session: {pin}"

    runtime = console.call_args.args[2]
    assert runtime.expected_pin == pin
    assert isinstance(runtime.auth_limiter, pin_auth.AuthAttemptLimiter)


def test_main_prints_a_fixed_pin_in_the_same_format(capsys):
    console = run_main(["--no-tray", "--pin", "112233"])
    out = capsys.readouterr().out
    assert "[Server] PIN for this session: 112233" in out
    assert console.call_args.args[2].expected_pin == "112233"


def test_main_with_no_auth_says_so_and_prints_no_pin(capsys):
    console = run_main(["--no-tray", "--no-auth"])
    out = capsys.readouterr().out

    assert "PIN authentication disabled" in out
    assert "PIN for this session" not in out
    assert out.isascii()

    runtime = console.call_args.args[2]
    assert runtime.expected_pin is None
    assert runtime.auth_limiter is None


def test_main_pin_line_survives_a_windowed_stdio_stream(capsys):
    """--noconsole exe 에서도 로그 파일에 남아야 한다 (logging_setup 경유)."""
    stream = io.StringIO()
    with patch("server.run_console", return_value=0), \
            patch("server.atexit.register"), \
            patch("server.logging_setup.configure_stdio") as configure:
        configure.return_value = type(
            "Stdio", (), {"windowed": False, "log_path": None}
        )()
        with patch("sys.stdout", stream):
            assert server.main(["--no-tray"]) == 0

    assert "PIN for this session" in stream.getvalue()
