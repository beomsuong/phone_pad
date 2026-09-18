import ctypes

INPUT_MOUSE = 0
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_WHEEL = 0x0800
MOUSEEVENTF_HWHEEL = 0x1000

# 휠 한 노치(클릭) 단위. Windows 표준값.
WHEEL_DELTA = 120


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", ctypes.c_long),
        ("dy", ctypes.c_long),
        ("mouseData", ctypes.c_ulong),
        ("dwFlags", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("dwExtraInfo", ctypes.c_size_t),
    ]


class _INPUTunion(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [
        ("type", ctypes.c_ulong),
        ("_input", _INPUTunion),
    ]


class InputController:
    def handle_event(self, event: dict):
        t = event.get("type")
        if t == "MOVE":
            dx = int(round(float(event.get("dx", 0))))
            dy = int(round(float(event.get("dy", 0))))
            if dx != 0 or dy != 0:
                self._move(dx, dy)
        elif t == "CLICK":
            self._click(event.get("button", "left"))
        elif t == "DOUBLE_CLICK":
            self._double_click(event.get("button", "left"))
        elif t == "SCROLL":
            # dx/dy 는 정수 스크롤 스텝(휠 노치 개수). 픽셀 값이 아니다.
            # Android 가 Int 로 보내지만 JSON 파싱 결과가 float/문자열일 수 있어
            # MOVE 와 동일하게 방어적으로 변환한다.
            dx = int(round(float(event.get("dx", 0))))
            dy = int(round(float(event.get("dy", 0))))
            if dx != 0 or dy != 0:
                self._scroll(dx, dy)

    def _move(self, dx: int, dy: int):
        inp = INPUT(
            type=INPUT_MOUSE,
            _input=_INPUTunion(
                mi=MOUSEINPUT(dx=dx, dy=dy, dwFlags=MOUSEEVENTF_MOVE)
            ),
        )
        ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    def _scroll(self, dx_steps: int, dy_steps: int):
        """휠 스크롤 이벤트 발생.

        부호 규약은 이 함수 한 곳에만 존재한다 — 실기기 미검증이므로 방향이
        사용자 기대와 다르면 여기서 부호만 뒤집으면 된다(호출부 수정 불필요).

        현재 규약(Windows 표준 그대로, 추가 반전 없음):
          - dy_steps 양수 → MOUSEEVENTF_WHEEL 양수 delta = 휠을 앞으로 굴림
          - dx_steps 양수 → MOUSEEVENTF_HWHEEL 양수 delta = 오른쪽

        dx/dy 가 0인 축은 INPUT 을 만들지 않으며, 둘 다 0이면 SendInput 자체를
        호출하지 않는다.
        """
        deltas = []
        if dy_steps != 0:
            deltas.append((MOUSEEVENTF_WHEEL, dy_steps * WHEEL_DELTA))
        if dx_steps != 0:
            deltas.append((MOUSEEVENTF_HWHEEL, dx_steps * WHEEL_DELTA))
        if not deltas:
            return

        inputs = (INPUT * len(deltas))(
            *[
                INPUT(
                    type=INPUT_MOUSE,
                    _input=_INPUTunion(
                        # mouseData 는 DWORD(c_ulong)지만 휠에서는 signed 로
                        # 해석되므로 2의 보수로 실어 보낸다.
                        mi=MOUSEINPUT(mouseData=delta & 0xFFFFFFFF, dwFlags=flag)
                    ),
                )
                for flag, delta in deltas
            ]
        )
        ctypes.windll.user32.SendInput(len(deltas), inputs, ctypes.sizeof(INPUT))

    def _click(self, button: str):
        down_flag = MOUSEEVENTF_LEFTDOWN if button == "left" else MOUSEEVENTF_RIGHTDOWN
        up_flag = MOUSEEVENTF_LEFTUP if button == "left" else MOUSEEVENTF_RIGHTUP

        inputs = (INPUT * 2)(
            INPUT(
                type=INPUT_MOUSE,
                _input=_INPUTunion(mi=MOUSEINPUT(dwFlags=down_flag)),
            ),
            INPUT(
                type=INPUT_MOUSE,
                _input=_INPUTunion(mi=MOUSEINPUT(dwFlags=up_flag)),
            ),
        )
        ctypes.windll.user32.SendInput(2, inputs, ctypes.sizeof(INPUT))

    def _double_click(self, button: str):
        """더블클릭: down-up-down-up 4개 INPUT 을 SendInput 1회로 원자적으로 전송.

        `_click` 을 두 번 호출하면 SendInput 이 두 번 나가고 그 사이에 다른
        입력(특히 MOVE)이 끼어들 수 있다. Windows 의 네이티브 더블클릭 판정은
        두 클릭이 좁은 사각형(기본 4px) 안에서 일어나야 성립하므로, 커서를
        움직이는 요소가 전혀 없는 4-INPUT 시퀀스를 한 번에 밀어넣는다.

        클릭 간 간격(delay)을 따로 주지 않는 이유: SendInput 은 큐에 동시 주입
        하므로 두 클릭의 시각차가 사실상 0이며, 이는 시스템 더블클릭 시간
        (GetDoubleClickTime, 기본 500ms) 안에 항상 들어간다.
        """
        down_flag = MOUSEEVENTF_LEFTDOWN if button == "left" else MOUSEEVENTF_RIGHTDOWN
        up_flag = MOUSEEVENTF_LEFTUP if button == "left" else MOUSEEVENTF_RIGHTUP

        flags = (down_flag, up_flag, down_flag, up_flag)
        inputs = (INPUT * 4)(
            *[
                INPUT(
                    type=INPUT_MOUSE,
                    _input=_INPUTunion(mi=MOUSEINPUT(dwFlags=flag)),
                )
                for flag in flags
            ]
        )
        ctypes.windll.user32.SendInput(4, inputs, ctypes.sizeof(INPUT))
