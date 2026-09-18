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
