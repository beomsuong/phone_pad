import ctypes

INPUT_MOUSE = 0
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010


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

    def _move(self, dx: int, dy: int):
        inp = INPUT(
            type=INPUT_MOUSE,
            _input=_INPUTunion(
                mi=MOUSEINPUT(dx=dx, dy=dy, dwFlags=MOUSEEVENTF_MOVE)
            ),
        )
        ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

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
