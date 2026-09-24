"""단일 클라이언트 정책 (Phase 5) — `SingleClientGuard` + `handle_client` 통합 테스트.

정책: 새 연결이 **AUTH 를 통과하면** 기존 활성 연결에 `{"type":"SESSION_REPLACED"}`
를 보내고 강제로 닫는다. AUTH 를 통과하지 못한 시도(PIN 오류 / 형식 오류 /
타임아웃 / 브루트포스 잠금)는 기존 연결에 **어떤 영향도 주지 않는다**.

검증 층위:
  1. 순수 `SingleClientGuard` (소켓 없음) — 교체/identity release
  2. `handle_client` end-to-end (`fake_conn.FakeConn`) — 세션 회수, 드래그 강제
     해제 안전장치가 밀려난 연결에서도 도는지
  3. 실소켓 — `SESSION_REPLACED` 줄이 실제로 도달하고 그 뒤 **clean EOF**(RST 아님)
     로 끝나는지 (PIN 브루트포스 잠금에서 겪은 함정의 재발 방지)
  4. 회귀 — guard 를 안 넘기면(기본값) 지금까지와 완전히 동일
"""
import ctypes
import json
import socket
import threading
import time
from unittest.mock import patch

import pin_auth
import server
import single_client
from fake_conn import AUTH_LINE, FakeConn, make_auth_line
from send_input_stub import patch_send_input
from input_controller import (
    INPUT,
    MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP,
    InputController,
)

ADDR1 = ("127.0.0.1", 5551)
ADDR2 = ("127.0.0.1", 5552)
PIN = "483920"
WRONG_PIN = "000000"

DRAG_START_LINE = b'{"type":"DRAG_START"}\n'


def sent_flags(mock_send):
    flags = []
    for call in mock_send.call_args_list:
        count, inputs, size = call.args
        assert size == ctypes.sizeof(INPUT)
        flags.extend(inputs[i]._input.mi.dwFlags for i in range(count))
    return flags


def wait_until(predicate, timeout=5.0, what="condition"):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.005)
    raise AssertionError(f"timed out waiting for {what}")


# --------------------------------------------------------------------------
# 1. 순수 SingleClientGuard
# --------------------------------------------------------------------------

def test_first_take_over_has_nothing_to_evict():
    guard = single_client.SingleClientGuard()
    assert guard.take_over("conn-a", ADDR1, "tok-a") is None
    assert guard.current() == ("conn-a", ADDR1, "tok-a")


def test_second_take_over_returns_the_first_connection():
    guard = single_client.SingleClientGuard()
    guard.take_over("conn-a", ADDR1, "tok-a")

    evicted = guard.take_over("conn-b", ADDR2, "tok-b")

    assert evicted == ("conn-a", ADDR1)
    assert guard.current() == ("conn-b", ADDR2, "tok-b")


def test_take_over_returns_only_conn_and_addr_not_the_session():
    """세션 토큰은 슬롯에만 보관한다 - 밀어내기 I/O 에는 필요 없다."""
    guard = single_client.SingleClientGuard()
    guard.take_over("conn-a", ADDR1, "tok-a")
    assert len(guard.take_over("conn-b", ADDR2, "tok-b")) == 2


def test_release_clears_the_slot_when_it_is_still_this_connection():
    guard = single_client.SingleClientGuard()
    guard.take_over("conn-a", ADDR1, "tok-a")

    assert guard.release("conn-a") is True
    assert guard.current() is None


def test_release_after_being_evicted_does_not_clear_the_new_client():
    """밀려난 연결의 뒤늦은 정리가 새 활성 클라이언트의 슬롯을 지우면 안 된다."""
    guard = single_client.SingleClientGuard()
    guard.take_over("conn-a", ADDR1, "tok-a")
    guard.take_over("conn-b", ADDR2, "tok-b")

    assert guard.release("conn-a") is False
    assert guard.current() == ("conn-b", ADDR2, "tok-b")


def test_release_of_an_unknown_connection_is_a_no_op():
    guard = single_client.SingleClientGuard()
    assert guard.release("never-registered") is False
    guard.take_over("conn-a", ADDR1, "tok-a")
    assert guard.release("conn-z") is False
    assert guard.current() == ("conn-a", ADDR1, "tok-a")


def test_release_compares_by_identity_not_equality():
    """`==` 로 비교하면 '같아 보이는' 다른 소켓이 남의 슬롯을 지울 수 있다."""

    class AlwaysEqual:
        def __eq__(self, other):
            return True

        def __hash__(self):
            return 0

    a, b = AlwaysEqual(), AlwaysEqual()
    assert a == b  # 전제 확인
    guard = single_client.SingleClientGuard()
    guard.take_over(a, ADDR1, "tok-a")

    assert guard.release(b) is False
    assert guard.current()[0] is a


def test_release_is_idempotent():
    guard = single_client.SingleClientGuard()
    guard.take_over("conn-a", ADDR1, "tok-a")
    assert guard.release("conn-a") is True
    assert guard.release("conn-a") is False


def test_take_over_is_serialized_under_concurrency():
    """동시에 밀고 들어와도 밀려난 연결이 정확히 (N-1) 개 나와야 한다
    (하나도 빠짐없이, 중복 없이)."""
    guard = single_client.SingleClientGuard()
    conns = [f"conn-{i}" for i in range(24)]
    evicted = []
    lock = threading.Lock()
    start = threading.Event()

    def worker(c):
        start.wait()
        result = guard.take_over(c, ADDR1, c)
        with lock:
            evicted.append(result)

    threads = [threading.Thread(target=worker, args=(c,)) for c in conns]
    for t in threads:
        t.start()
    start.set()
    for t in threads:
        t.join(timeout=5)

    names = sorted(e[0] for e in evicted if e is not None)
    survivor = guard.current()[0]
    assert evicted.count(None) == 1               # 처음 들어온 하나만 밀 게 없었다
    assert names == sorted(c for c in conns if c != survivor)


# --------------------------------------------------------------------------
# 2. evict() 의 I/O 순서
# --------------------------------------------------------------------------

def test_evict_sends_the_notice_then_shuts_down_then_closes():
    conn = FakeConn()
    single_client.evict(conn, ADDR1)

    assert conn.sent == [b'{"type":"SESSION_REPLACED"}\n']
    assert conn.shutdowns == [socket.SHUT_RDWR]
    assert conn.call_order == ["shutdown", "close"]
    assert conn.closed


def test_evict_drains_the_receive_queue_before_closing():
    """미판독 바이트가 남은 채 닫으면 커널이 RST 를 보내 `SESSION_REPLACED`
    까지 날아간다 (실측). 닫기 전에 예산 안에서 큐를 비워야 한다."""
    leftovers = [b'{"type":"CLICK","button":"left"}\n', b"x" * 4096]
    read = []

    class BacklogConn(FakeConn):
        def _recv_event(self, size):
            if leftovers:
                chunk = leftovers.pop(0)
                read.append(chunk)
                return chunk
            raise socket.timeout("timed out")

    conn = BacklogConn()
    single_client.evict(conn, ADDR1)

    assert len(read) == 2  # 큐가 빌 때까지 읽었다
    assert conn.call_order == ["shutdown", "close"]


def test_evict_drain_stops_at_the_time_budget_when_the_peer_floods():
    """계속 밀어넣는 상대가 새 클라이언트의 SESSION 발급을 붙잡지 못하게 한다."""

    class FloodConn(FakeConn):
        def _recv_event(self, size):
            return b"x" * 65536

    conn = FloodConn()
    started_at = time.monotonic()
    single_client.evict(conn, ADDR1)
    elapsed = time.monotonic() - started_at

    assert elapsed < 2.0, f"drain took {elapsed:.2f}s"
    assert conn.closed


def test_evict_drain_stops_at_eof():
    class EofConn(FakeConn):
        def __init__(self):
            super().__init__()
            self.event_recvs = 0

        def _recv_event(self, size):
            self.event_recvs += 1
            return b""

    conn = EofConn()
    single_client.evict(conn, ADDR1)

    assert conn.event_recvs == 1  # EOF 를 보면 곧바로 멈춘다
    assert conn.closed


def test_evict_survives_a_socket_that_cannot_be_drained():
    class NoRecvConn(FakeConn):
        def settimeout(self, value):
            raise OSError("socket already closed")

    conn = NoRecvConn()
    single_client.evict(conn, ADDR1)  # 예외가 새면 안 된다

    assert conn.closed


def test_evict_closes_even_when_the_notice_cannot_be_sent():
    """이미 죽은 소켓일 수 있으므로 전송은 best-effort 다."""

    class DeadConn(FakeConn):
        def sendall(self, data):
            raise ConnectionResetError("peer is gone")

    conn = DeadConn()
    single_client.evict(conn, ADDR1)  # 예외가 새면 안 된다

    assert conn.closed


def test_evict_closes_even_when_shutdown_fails():
    class NoShutdownConn(FakeConn):
        def shutdown(self, how):
            raise OSError("not connected")

    conn = NoShutdownConn()
    single_client.evict(conn, ADDR1)

    assert conn.closed


def test_session_replaced_line_has_no_extra_fields():
    """와이어 스펙: 필드 없음 (session 도 없다 - 이 연결은 곧 끊긴다)."""
    payload = json.loads(single_client.SESSION_REPLACED_LINE.decode("utf-8"))
    assert payload == {"type": "SESSION_REPLACED"}
    assert single_client.SESSION_REPLACED_LINE.endswith(b"\n")


# --------------------------------------------------------------------------
# 3. handle_client end-to-end (FakeConn)
# --------------------------------------------------------------------------

class BlockingConn(FakeConn):
    """청크를 다 쓰면 '다른 스레드가 닫을 때까지' recv 가 블록하는 대역.

    실제 소켓에서 밀려나는 쪽 연결이 겪는 것을 재현한다: 그 연결의 스레드는
    `recv()` 에 들어가 있고, 밀어내는 스레드가 소켓을 닫으면 `recv()` 가 예외로
    풀려나 기존 `except Exception`/`finally` 경로를 탄다.
    """

    def __init__(self, chunks=None, auth_line=AUTH_LINE):
        super().__init__(chunks=chunks, auth_line=auth_line)
        self._released = threading.Event()
        self._short_timeout = False

    def settimeout(self, value):
        super().settimeout(value)
        # `evict()` 의 드레인이 거는 아주 짧은 타임아웃. 실제 소켓이라면 큐가
        # 비어 있을 때 곧바로 `socket.timeout` 을 던진다 - 블록하면 안 된다.
        self._short_timeout = value is not None and value <= 0.5

    def _recv_event(self, size):
        if self._chunks:
            return super()._recv_event(size)
        if self._short_timeout:
            raise socket.timeout("timed out")
        if not self._released.wait(timeout=5.0):
            raise AssertionError("connection was never closed by the evictor")
        raise ConnectionResetError("socket closed by another thread")

    def shutdown(self, how):
        super().shutdown(how)
        self._released.set()

    def close(self):
        super().close()
        self._released.set()


def start_client(conn, addr, controller, registry, guard, expected_pin=None,
                 auth_limiter=None):
    thread = threading.Thread(
        target=server.handle_client,
        args=(conn, addr, controller, registry, expected_pin, auth_limiter, guard),
        daemon=True,
    )
    thread.start()
    return thread


def wait_for_session(conn):
    wait_until(lambda: any(b"SESSION" in chunk for chunk in conn.sent),
               what="SESSION to be issued")


def test_second_client_evicts_the_first_one():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, guard)
    wait_for_session(first)
    first_session = json.loads(first.sent_lines()[0])["session"]
    assert registry.is_active(first_session)

    second = FakeConn(chunks=[b""])
    server.handle_client(second, ADDR2, controller, registry, None, None, guard)
    t1.join(timeout=5)
    assert not t1.is_alive()

    # 첫 번째: SESSION 다음에 SESSION_REPLACED 한 줄, 그리고 닫힘
    assert first.sent_types() == ["SESSION", "SESSION_REPLACED"]
    assert first.closed
    assert first.shutdowns == [socket.SHUT_RDWR]
    # 세션 토큰 회수 (finally 가 실제로 돌았다는 증거)
    assert not registry.is_active(first_session)
    # 두 번째는 정상적으로 SESSION 을 받았고, 자기 SESSION_REPLACED 는 없다
    assert second.sent_types() == ["SESSION"]
    assert registry.snapshot() == set()  # 두 번째도 끝나면서 회수됨


def test_evicted_client_gets_exactly_one_notice():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, guard)
    wait_for_session(first)

    server.handle_client(FakeConn(chunks=[b""]), ADDR2, controller, registry, None, None, guard)
    t1.join(timeout=5)

    assert first.sent_types().count("SESSION_REPLACED") == 1


def test_third_client_evicts_the_second_not_the_first_again():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, guard)
    wait_for_session(first)

    second = BlockingConn()
    t2 = start_client(second, ADDR2, controller, registry, guard)
    wait_for_session(second)
    t1.join(timeout=5)

    third = FakeConn(chunks=[b""])
    server.handle_client(third, ADDR1, controller, registry, None, None, guard)
    t2.join(timeout=5)

    assert first.sent_types() == ["SESSION", "SESSION_REPLACED"]
    assert second.sent_types() == ["SESSION", "SESSION_REPLACED"]
    assert third.sent_types() == ["SESSION"]
    assert registry.snapshot() == set()


def test_drag_active_on_the_evicted_client_is_force_released():
    """밀려난 연결에서도 드래그 안전장치(finally)가 그대로 돌아야 한다 -
    안 돌면 PC 왼쪽 버튼이 영원히 눌린 채 남는다."""
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    with patch_send_input() as mock_send:
        first = BlockingConn(chunks=[DRAG_START_LINE])
        t1 = start_client(first, ADDR1, controller, registry, guard)
        wait_until(lambda: controller._drag_active, what="drag to start")

        server.handle_client(FakeConn(chunks=[b""]), ADDR2, controller, registry,
                             None, None, guard)
        t1.join(timeout=5)

        assert sent_flags(mock_send) == [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP]

    assert controller._drag_active is False


def test_guard_slot_holds_only_the_newest_client():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, guard)
    wait_for_session(first)

    second = BlockingConn()
    t2 = start_client(second, ADDR2, controller, registry, guard)
    wait_for_session(second)
    t1.join(timeout=5)

    assert guard.current()[0] is second

    second.close()  # 두 번째가 스스로 끝난다
    t2.join(timeout=5)
    assert guard.current() is None


def test_eviction_happens_before_the_new_session_line_goes_out():
    """밀어내기 -> 새 SESSION 발급 순서. 반대면 순간적으로 활성 2개가 된다."""
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, guard)
    wait_for_session(first)

    order = []

    class RecordingConn(FakeConn):
        def sendall(self, data):
            order.append("second-session")
            super().sendall(data)

    original_evict = single_client.evict

    def recording_evict(conn, addr):
        order.append("evict")
        original_evict(conn, addr)

    with patch.object(single_client, "evict", recording_evict):
        server.handle_client(RecordingConn(chunks=[b""]), ADDR2, controller,
                             registry, None, None, guard)
    t1.join(timeout=5)

    assert order == ["evict", "second-session"]


# --------------------------------------------------------------------------
# 4. AUTH 를 통과하지 못한 시도는 기존 연결을 건드리지 않는다
# --------------------------------------------------------------------------

def make_limiter():
    return pin_auth.AuthAttemptLimiter()


def test_wrong_pin_attempt_does_not_evict_the_active_client():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()
    limiter = make_limiter()

    first = BlockingConn(auth_line=make_auth_line(PIN))
    t1 = start_client(first, ADDR1, controller, registry, guard,
                      expected_pin=PIN, auth_limiter=limiter)
    wait_for_session(first)
    first_session = json.loads(first.sent_lines()[0])["session"]

    intruder = FakeConn(auth_line=make_auth_line(WRONG_PIN))
    server.handle_client(intruder, ADDR2, controller, registry, PIN, limiter, guard)

    assert intruder.sent_types() == ["AUTH_FAIL"]
    assert intruder.closed
    # 기존 연결은 알림도 못 받고, 닫히지도 않고, 세션도 살아있다
    assert first.sent_types() == ["SESSION"]
    assert first.closed is False
    assert registry.is_active(first_session)
    assert guard.current()[0] is first

    first.close()
    t1.join(timeout=5)


def test_locked_out_attempt_does_not_evict_the_active_client():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()
    limiter = make_limiter()

    first = BlockingConn(auth_line=make_auth_line(PIN))
    t1 = start_client(first, ADDR1, controller, registry, guard,
                      expected_pin=PIN, auth_limiter=limiter)
    wait_for_session(first)

    for _ in range(5):
        limiter.record_failure(pin_auth.source_key(ADDR2))
    assert limiter.is_locked_out(pin_auth.source_key(ADDR2))

    # 잠긴 IP 는 올바른 PIN 을 보내도 통과하지 못한다
    intruder = FakeConn(auth_line=make_auth_line(PIN))
    server.handle_client(intruder, ADDR2, controller, registry, PIN, limiter, guard)

    assert intruder.sent_types() == []  # 조용히 닫힘
    assert first.sent_types() == ["SESSION"]
    assert first.closed is False
    assert guard.current()[0] is first

    first.close()
    t1.join(timeout=5)


def test_malformed_first_line_does_not_evict_the_active_client():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, guard)
    wait_for_session(first)

    intruder = FakeConn(auth_line=b"garbage\n")
    server.handle_client(intruder, ADDR2, controller, registry, None, None, guard)

    assert intruder.sent_types() == []
    assert first.sent_types() == ["SESSION"]
    assert first.closed is False
    assert guard.current()[0] is first

    first.close()
    t1.join(timeout=5)


def test_eof_before_auth_does_not_evict_the_active_client():
    controller = InputController()
    registry = server.SessionRegistry()
    guard = single_client.SingleClientGuard()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, guard)
    wait_for_session(first)

    intruder = FakeConn(auth_line=b"")  # 즉시 EOF
    server.handle_client(intruder, ADDR2, controller, registry, None, None, guard)

    assert first.sent_types() == ["SESSION"]
    assert guard.current()[0] is first

    first.close()
    t1.join(timeout=5)


# --------------------------------------------------------------------------
# 5. 회귀: guard 기본값(None)이면 지금까지와 동일
# --------------------------------------------------------------------------

def test_handle_client_without_a_guard_behaves_as_before():
    conn = FakeConn(chunks=[b'{"type":"CLICK","button":"left"}\n', b""])
    controller = InputController()
    registry = server.SessionRegistry()

    with patch.object(controller, "_click") as mock_click:
        server.handle_client(conn, ADDR1, controller, registry)

    mock_click.assert_called_once_with("left")
    assert conn.sent_types() == ["SESSION"]
    assert conn.shutdowns == []  # 밀어내기 경로를 타지 않았다
    assert registry.snapshot() == set()


def test_two_clients_coexist_without_a_guard():
    """기존 회귀: guard 가 없으면 두 연결이 동시에 살아 있고 서로 영향이 없다."""
    controller = InputController()
    registry = server.SessionRegistry()

    first = BlockingConn()
    t1 = start_client(first, ADDR1, controller, registry, None)
    wait_for_session(first)

    second = BlockingConn()
    t2 = start_client(second, ADDR2, controller, registry, None)
    wait_for_session(second)

    assert len(registry.snapshot()) == 2
    assert first.sent_types() == ["SESSION"]
    assert second.sent_types() == ["SESSION"]

    first.close()
    second.close()
    t1.join(timeout=5)
    t2.join(timeout=5)


def test_runtime_defaults_to_no_single_client_guard():
    runtime = server.ServerRuntime(InputController(), server.SessionRegistry())
    assert runtime.single_client_guard is None


def test_runtime_stores_an_injected_guard():
    guard = single_client.SingleClientGuard()
    runtime = server.ServerRuntime(
        InputController(), server.SessionRegistry(), single_client_guard=guard
    )
    assert runtime.single_client_guard is guard


def test_main_always_creates_a_guard_for_the_real_server():
    """끄는 CLI 옵션이 없다 - 어떤 플래그 조합이든 실제 서버는 가드를 쓴다."""
    for argv in (["--no-tray"], ["--no-tray", "--no-auth"],
                 ["--no-tray", "--no-discovery", "--pin", PIN]):
        captured = []

        def fake_run_console(controller, registry, runtime=None):
            captured.append(runtime)
            return 0

        with patch("server.run_console", fake_run_console):
            assert server.main(argv) == 0

        assert isinstance(captured[0].single_client_guard,
                          single_client.SingleClientGuard), argv


def test_parse_args_has_no_switch_to_disable_the_policy():
    args = server.parse_args([])
    assert not hasattr(args, "allow_multiple_clients")
    assert not hasattr(args, "no_single_client")


# --------------------------------------------------------------------------
# 6. 실소켓: SESSION_REPLACED 가 실제로 도달하고 clean EOF 로 끝나는가
# --------------------------------------------------------------------------

def real_runtime(expected_pin=None, guard=None, controller=None):
    return server.ServerRuntime(
        controller if controller is not None else InputController(),
        server.SessionRegistry(), host="127.0.0.1",
        tcp_port=0, udp_port=0, accept_timeout=0.05, expected_pin=expected_pin,
        single_client_guard=guard,
    )


def started(runtime):
    thread = threading.Thread(target=runtime.run, daemon=True)
    thread.start()
    assert runtime.ready.wait(timeout=5), "server never became ready"
    return thread


def connect_and_auth(runtime, pin=""):
    client = socket.create_connection(("127.0.0.1", runtime.tcp_port), timeout=5.0)
    client.settimeout(5.0)
    client.sendall(make_auth_line(pin))
    reader = client.makefile("r", encoding="utf-8")
    session_line = reader.readline()
    assert json.loads(session_line)["type"] == "SESSION", session_line
    return client, reader


def test_real_socket_first_client_reads_session_replaced_then_clean_eof():
    """`shutdown(SHUT_RDWR)` -> `close()` 순서라 RST 없이 깨끗하게 끝나야 한다.

    RST 가 나가면 클라이언트는 `SESSION_REPLACED` 를 읽기도 전에
    `ConnectionResetError` 를 받는다 (PIN 잠금에서 실측했던 F-1 과 같은 함정).
    """
    runtime = real_runtime(guard=single_client.SingleClientGuard())
    thread = started(runtime)
    try:
        first, first_reader = connect_and_auth(runtime)
        try:
            second, _ = connect_and_auth(runtime)
            try:
                assert json.loads(first_reader.readline()) == {"type": "SESSION_REPLACED"}
                # 그 다음은 FIN(EOF) - 예외가 아니라 빈 바이트여야 한다
                assert first.recv(4096) == b""
            finally:
                second.close()
        finally:
            first.close()
    finally:
        runtime.stop()
        thread.join(timeout=5)


def test_real_socket_evicted_client_with_unread_bytes_still_gets_clean_eof():
    """RST 회귀 테스트. 미판독 바이트가 남은 채 닫히면 커널이 FIN 대신 RST 를
    보내고, 클라이언트는 **이미 도착해 있던 `SESSION_REPLACED` 까지 잃은 채**
    `ConnectionResetError` 만 본다 - 그러면 앱은 "다른 기기에 밀렸다"와 "그냥
    끊겼다"를 구분하지 못하고 자동 재연결을 시작해 핑퐁이 난다.

    실측 결과 `shutdown(SHUT_RDWR)` 을 먼저 불러도 이건 막히지 않는다. 닫기
    전에 큐를 비우는 것만이 막는다 (`single_client._drain`).

    밀린 바이트를 확실히 만들기 위해 서버 스레드를 `handle_event` 안에
    붙잡아 둔다 (실제 서버에서도 `SendInput` 이 도는 동안 같은 일이 일어난다).
    `InputController` 대역이라 실제 마우스는 움직이지 않는다.
    """
    entered = threading.Event()
    release = threading.Event()

    class StuckController:
        """`handle_event` 가 첫 이벤트에서 붙잡히는 InputController 대역."""

        def handle_event(self, event):
            entered.set()
            release.wait(timeout=10)

        def force_release_drag(self):
            return False

    runtime = real_runtime(guard=single_client.SingleClientGuard(),
                           controller=StuckController())
    thread = started(runtime)
    try:
        first, first_reader = connect_and_auth(runtime)
        try:
            # 서버 스레드를 handle_event 안에 묶어 두고 ...
            first.sendall(b'{"type":"SCROLL","dx":0,"dy":-1}\n')
            wait_until(entered.is_set, what="the server thread to block in handle_event")
            # ... 그 사이에 수신 큐를 채운다 (서버가 읽어갈 수 없다)
            first.sendall(b"x" * 500000)

            second, _ = connect_and_auth(runtime)
            try:
                line = first_reader.readline()
                assert json.loads(line) == {"type": "SESSION_REPLACED"}, line
                tail = first.recv(4096)
                assert tail == b"", f"expected clean FIN, got {tail!r}"
            finally:
                second.close()
        finally:
            release.set()
            first.close()
    finally:
        release.set()
        runtime.stop()
        thread.join(timeout=5)


def test_real_socket_session_token_of_the_evicted_client_is_revoked():
    runtime = real_runtime(guard=single_client.SingleClientGuard())
    thread = started(runtime)
    try:
        first = socket.create_connection(("127.0.0.1", runtime.tcp_port), timeout=5.0)
        first.settimeout(5.0)
        first.sendall(make_auth_line(""))
        reader = first.makefile("r", encoding="utf-8")
        first_session = json.loads(reader.readline())["session"]
        assert runtime.registry.is_active(first_session)

        second, _ = connect_and_auth(runtime)
        try:
            wait_until(lambda: not runtime.registry.is_active(first_session),
                       what="the evicted session to be revoked")
            assert len(runtime.registry.snapshot()) == 1
        finally:
            second.close()
            first.close()
    finally:
        runtime.stop()
        thread.join(timeout=5)


def test_real_socket_wrong_pin_leaves_the_active_client_untouched():
    runtime = real_runtime(expected_pin=PIN, guard=single_client.SingleClientGuard())
    thread = started(runtime)
    try:
        first, first_reader = connect_and_auth(runtime, pin=PIN)
        try:
            with socket.create_connection(("127.0.0.1", runtime.tcp_port),
                                          timeout=5.0) as intruder:
                intruder.settimeout(5.0)
                intruder.sendall(make_auth_line(WRONG_PIN))
                fail = intruder.makefile("r", encoding="utf-8").readline()
                assert json.loads(fail)["type"] == "AUTH_FAIL"

            # 기존 연결은 아무것도 받지 않고 계속 살아 있다
            first.settimeout(0.3)
            try:
                extra = first.recv(4096)
            except socket.timeout:
                extra = None
            assert extra is None, f"active client unexpectedly received {extra!r}"
            assert len(runtime.registry.snapshot()) == 1
        finally:
            first.close()
            first_reader.close()
    finally:
        runtime.stop()
        thread.join(timeout=5)


def test_real_socket_without_a_guard_both_clients_stay_connected():
    runtime = real_runtime()  # 기본값: 가드 없음
    thread = started(runtime)
    try:
        first, first_reader = connect_and_auth(runtime)
        second, _ = connect_and_auth(runtime)
        try:
            assert len(runtime.registry.snapshot()) == 2
            first.settimeout(0.3)
            try:
                extra = first.recv(4096)
            except socket.timeout:
                extra = None
            assert extra is None
        finally:
            first.close()
            second.close()
    finally:
        runtime.stop()
        thread.join(timeout=5)
