"""`handle_client` 를 실제로 구동하는 TCP end-to-end 테스트용 socket 대역.

Phase 5 의 PIN 인증부터 **클라이언트가 먼저 AUTH 한 줄을 보내야** 서버가
SESSION 을 돌려준다(파괴적 변경). 기존 테스트들(heartbeat / 드래그 / 데스크톱
전환 / 세션 수명)은 인증 자체가 관심사가 아니므로, 이 대역이 **AUTH 줄을 자동으로
먼저 흘려준다**:

  - `recv_calls` / `timeouts` 는 **AUTH 단계를 세지 않는다**. 그 단정들의 의미는
    "이벤트 루프에서 몇 번 recv 했는가 / heartbeat 타임아웃이 언제 걸렸는가"
    이고, 인증이 끼어들어도 그 의미는 그대로여야 한다. AUTH 단계의 값은
    `auth_recv_calls` / `auth_timeouts` 로 따로 노출한다.
  - AUTH 단계 자체를 검증하는 테스트(`test_pin_auth.py`)는 `auth_line=None` 으로
    만들어 이 자동 공급을 끄고 원본 소켓처럼 쓴다.

한 곳만 고치면 여러 파일에 반영되도록 모든 TCP end-to-end 테스트가 이 클래스를
공유한다 (이전에는 파일마다 FakeConn 을 복사해 두고 있었다).
"""
import json


def make_auth_line(pin: str = "") -> bytes:
    """`{"type":"AUTH","pin":"..."}` 한 줄 (개행 포함)."""
    return (
        json.dumps({"type": "AUTH", "pin": pin}, separators=(",", ":")) + "\n"
    ).encode("utf-8")


# 인증이 꺼진 서버를 상대할 때 쓰는 기본 AUTH 줄 - pin 값은 무관하다.
AUTH_LINE = make_auth_line("")


class FakeConn:
    """socket.socket 대역. `chunks` 의 각 원소는 bytes 이거나 예외 인스턴스다.

    예외 인스턴스는 그 recv 호출에서 그대로 raise 된다 (`socket.timeout` 으로
    heartbeat 미응답을, `ConnectionResetError` 로 연결 소실을 재현한다).
    청크가 다 소진되면 `b""`(EOF)를 돌려준다.
    """

    def __init__(self, chunks=None, auth_line: bytes = AUTH_LINE):
        self._chunks = list(chunks or [])
        self._serving_auth = auth_line is not None
        if self._serving_auth:
            self._chunks.insert(0, auth_line)
        self.sent = []
        self.closed = False
        self.shutdowns = []      # shutdown(how) 호출 인자 기록
        self.call_order = []     # "shutdown"/"close" 호출 순서 (RST 방지 검증용)
        self.timeouts = []       # SESSION 이후(heartbeat) 타임아웃만
        self.auth_timeouts = []  # AUTH 단계 타임아웃
        self.recv_calls = 0      # SESSION 이후 recv 만
        self.auth_recv_calls = 0

    # -- socket 인터페이스 -------------------------------------------------

    def settimeout(self, value):
        if self._serving_auth:
            self.auth_timeouts.append(value)
        else:
            self.timeouts.append(value)

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, size=4096):
        if self._serving_auth:
            self.auth_recv_calls += 1
            self._serving_auth = False
            return self._chunks.pop(0)
        self.recv_calls += 1
        return self._recv_event(size)

    def shutdown(self, how):
        self.shutdowns.append(how)
        self.call_order.append("shutdown")

    def close(self):
        self.closed = True
        self.call_order.append("close")

    # -- 하위 클래스 훅 ----------------------------------------------------

    def _recv_event(self, _size):
        """SESSION 이후의 recv. 하위 클래스는 `recv` 대신 이쪽을 덮어쓴다
        (그래야 AUTH 단계는 정상적으로 지나간다)."""
        if self._chunks:
            chunk = self._chunks.pop(0)
            if isinstance(chunk, BaseException):
                raise chunk
            return chunk
        return b""  # 클라이언트가 연결을 닫음

    # -- 검사 헬퍼 ---------------------------------------------------------

    def sent_lines(self):
        joined = b"".join(self.sent).decode("utf-8")
        return [line for line in joined.split("\n") if line]

    def sent_types(self):
        return [json.loads(line)["type"] for line in self.sent_lines()]
