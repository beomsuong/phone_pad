"""단일 클라이언트 정책 (AGENTS.md 섹션 4 / Phase 5).

서버는 **활성 클라이언트를 항상 최대 1개**로 유지한다. 새 연결이 AUTH 를
통과하는 순간, 그 시점에 활성 상태이던 연결(있다면)에게 `SESSION_REPLACED`
한 줄을 보내고 강제로 닫는다. 그 다음에야 새 연결에 SESSION 을 발급한다 -
반대로 하면 순간적으로 활성 클라이언트가 2개가 되는 창이 생긴다.

이 정책에는 끄는 옵션이 없다(`--pin`/`--no-auth`/`--no-discovery`/`--no-tray`/
`--allow-multiple` 과 달리 개발 편의 토글이 아니라 제품 정책이다). 다만
`handle_client`/`ServerRuntime` 파라미터의 **기본값은 비활성**이다 - 기존 단위
테스트가 guard 없이 호출하면 지금까지와 동일하게 동작해야 하기 때문
(discovery_port / expected_pin 과 같은 이유). 실제 서버는 `main()` 이 항상
`SingleClientGuard()` 를 만들어 넘긴다.

밀려난 기기는 **자동 재연결하지 않는다**(Android 쪽 규칙). 재연결하면 자신이
방금 밀려난 자리를 다시 빼앗아 상대를 밀어내는 무한 핑퐁이 된다.
"""
import json
import socket
import threading
import time

# 밀려난 연결을 닫기 전에 수신 큐를 비우는 데 쓰는 예산.
# Windows 에서 **미판독 바이트가 남은 채 close() 하면 커널이 FIN 대신 RST 를
# 보내고, 이미 보낸 `SESSION_REPLACED` 까지 클라이언트 버퍼에서 날아간다**
# (실측: `shutdown(SHUT_RDWR)` 을 먼저 불러도 막히지 않는다 - 비우는 것만이
# 막는다. PIN 브루트포스 잠금에서 겪은 것과 같은 함정이다).
# 이 드레인은 새 클라이언트의 SESSION 발급을 그만큼 늦추므로 짧게 묶어둔다 -
# TCP 채널은 저빈도 이벤트(클릭/스크롤/드래그)만 흐르므로 밀린 양은 보통 0 이다.
DRAIN_BUDGET_S = 0.1
DRAIN_POLL_TIMEOUT_S = 0.02
DRAIN_MAX_BYTES = 1 << 20

# 서버 -> 클라이언트. 필드 없음. session 필드도 없다 - 이 연결 자체가 곧 끊긴다.
SESSION_REPLACED_LINE = (
    json.dumps({"type": "SESSION_REPLACED"}, separators=(",", ":")) + "\n"
).encode("utf-8")


class SingleClientGuard:
    """현재 활성 TCP 연결 하나를 Lock 으로 보호해 들고 있는 슬롯.

    **소켓 I/O 는 이 클래스 안에서 하지 않는다.** `take_over()` 는 밀려난 연결을
    돌려주기만 하고, 전송/닫기는 호출자가 락 밖에서 한다 - 락 안에서 I/O 를 하면
    (죽은 소켓의 `sendall` 이 블록될 수 있다) 다른 연결의 인증 완료를 불필요하게
    오래 막는다.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._current = None  # (conn, addr, session) 또는 None

    def take_over(self, conn, addr, session):
        """활성 슬롯을 이 연결로 교체하고 **교체되기 전** 연결을 돌려준다.

        반환: `(evicted_conn, evicted_addr)` 또는 활성 연결이 없었으면 `None`.
        """
        with self._lock:
            previous, self._current = self._current, (conn, addr, session)
        if previous is None:
            return None
        return previous[0], previous[1]

    def release(self, conn):
        """연결이 스스로 끝날 때(정상 종료 / heartbeat 타임아웃 / 예외) 호출한다.

        `_current` 가 **여전히 이 conn 을 가리킬 때만**(identity 비교) 지운다.
        이미 다른 연결에 밀려난 뒤라면 아무것도 하지 않는다 - 안 그러면 오래된
        연결의 뒤늦은 정리가 새 활성 클라이언트의 슬롯을 지워버린다.

        반환: 실제로 지웠으면 True.
        """
        with self._lock:
            if self._current is not None and self._current[0] is conn:
                self._current = None
                return True
        return False

    def current(self):
        """현재 활성 `(conn, addr, session)` 스냅샷 (없으면 None). 진단/테스트용."""
        with self._lock:
            return self._current


def _drain(conn) -> int:
    """닫기 직전, 수신 큐에 남은 바이트를 예산 안에서 비운다. 읽어버린 바이트 수.

    예외를 밖으로 던지지 않는다 - 이미 끊긴 소켓이면 첫 `recv` 가 실패하고 그대로
    끝난다. `settimeout` 은 이 소켓을 함께 쓰던 스레드에도 영향을 주지만, 그
    소켓은 몇 밀리초 뒤 닫힌다.
    """
    try:
        conn.settimeout(DRAIN_POLL_TIMEOUT_S)
    except Exception:  # noqa: BLE001
        return 0
    deadline = time.monotonic() + DRAIN_BUDGET_S
    total = 0
    while total < DRAIN_MAX_BYTES and time.monotonic() < deadline:
        try:
            chunk = conn.recv(65536)
        except Exception:  # noqa: BLE001 - timeout(=비었음) / 이미 닫힘
            break
        if not chunk:
            break  # EOF - 상대가 이미 닫았다
        total += len(chunk)
    return total


def disconnect(conn, addr):
    """활성 연결을 clean EOF 로 끊는다 (알림 줄 없음). **락 밖에서** 호출.

    `evict()` 와 다른 점은 `SESSION_REPLACED` 를 보내지 않는다는 것뿐이다. 창
    모드의 '정지' 버튼처럼 **서버가 멈추는** 경우에 쓴다 - 끊긴 이유가 '다른
    기기에 밀렸다' 가 아니므로, 앱은 평소의 연결 유실 처리(자동 재연결 시도)를
    해야 한다. `SESSION_REPLACED` 를 보내면 앱이 재연결을 포기한다.

    드레인 -> shutdown -> close 순서와 그 이유는 `evict()` 주석 참조.
    """
    _drain(conn)
    try:
        conn.shutdown(socket.SHUT_RDWR)
    except Exception:  # noqa: BLE001 - 이미 끊긴 소켓이면 정상적으로 실패한다
        pass
    try:
        conn.close()
    except Exception as e:  # noqa: BLE001
        print(f"[!] Failed to close client {addr}: {e}")


def evict(conn, addr):
    """밀려난 연결에 `SESSION_REPLACED` 를 보내고 강제로 닫는다. **락 밖에서** 호출.

    전송은 best-effort 다 - 이미 죽은 소켓일 수 있으므로 실패를 무시한다.

    닫는 순서는 **알림 -> 드레인 -> shutdown -> close** 다.

    드레인이 핵심이다: 수신 큐에 미판독 바이트가 남은 채 `close()` 하면 커널이
    FIN 대신 RST 를 보내고, 클라이언트는 **이미 도착해 있던 `SESSION_REPLACED`
    까지 잃은 채** 소켓 예외만 본다. `shutdown(SHUT_RDWR)` 을 먼저 불러도 이건
    막히지 않는다(실측 - `_drain` 위 주석 참조). PIN 브루트포스 잠금 close 에서
    겪은 함정과 같고, 해법도 같다(닫기 전에 큐를 짧게 비운다).

    드레인이 삼킨 바이트는 어차피 버려질 이벤트다 - 이 연결은 곧 끝나고,
    `DRAG_END` 를 삼키더라도 `handle_client` 의 `finally` 에 있는 드래그 강제
    해제가 버튼을 놓는다.

    닫힌 소켓 위에서 돌던 그 연결의 스레드는 `recv()` 가 예외로 풀려나
    `handle_client` 의 기존 `except Exception` / `finally` 경로를 그대로 탄다
    (세션 토큰 회수 + 드래그 강제 해제). 새 예외 처리는 필요 없다.
    """
    try:
        conn.sendall(SESSION_REPLACED_LINE)
    except Exception as e:  # noqa: BLE001 - best-effort 알림
        print(f"[!] Failed to notify evicted client {addr}: {e}")
    disconnect(conn, addr)
