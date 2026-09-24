"""tkinter 대역 - 디스플레이 없이 `gui.GuiController` 를 통째로 돌린다.

`tests/fake_conn.py` 가 소켓에 대해 하는 일과 같다. 실제 Tk 는
(1) 데스크톱에 창을 띄워 pytest 실행을 어지럽히고,
(2) `mainloop()` 안에서 일어나는 일을 테스트가 통제하기 어렵다.
그래서 위젯 트리와 `after()` 스케줄러를 아주 얇게 흉내 낸다 - `gui.py` 가 실제로
쓰는 API 만 구현한다(모자라면 AttributeError 로 바로 드러난다).
"""
import time


class TclError(Exception):
    """tkinter.TclError 대역."""


class FakeVar:
    def __init__(self, master=None, value=""):
        self._value = value
        self.history = [value]

    def get(self):
        return self._value

    def set(self, value):
        self._value = value
        self.history.append(value)


class FakeWidget:
    def __init__(self, master=None, **kwargs):
        self.master = master
        self.kwargs = kwargs
        self.children = []
        self.pack_kwargs = None
        if master is not None and hasattr(master, "children"):
            master.children.append(self)

    # -- 배치 (값은 검사하지 않고 호출만 받아 준다) ------------------------
    def pack(self, **kwargs):
        self.pack_kwargs = kwargs
        return self

    def invoke(self):
        """Button.invoke() 대역 - 실제 Tk 버튼처럼 command 를 실행한다."""
        command = self.kwargs.get("command")
        if command is None:
            raise TclError("widget has no command")
        return command()

    def descendants(self):
        out = []
        for child in self.children:
            out.append(child)
            out.extend(child.descendants())
        return out


class FakeRoot(FakeWidget):
    """Tk() 대역. `after()` 는 실행하지 않고 쌓아 두고, 테스트가 진행시킨다."""

    # mainloop 가 이 시간 안에 끝나지 않으면 테스트를 매달지 않고 실패시킨다.
    MAINLOOP_TIMEOUT_S = 10.0

    def __init__(self, **kwargs):
        super().__init__(None, **kwargs)
        self.title_text = None
        self.protocols = {}
        self.resizable_calls = []
        self.state = "normal"       # normal | withdrawn | destroyed
        self.lift_calls = 0
        self.focus_calls = 0
        self.destroy_calls = 0
        self.mainloop_calls = 0
        self.scheduled = []          # [(delay_ms, func)]
        self._running = False
        # mainloop 한 바퀴마다 호출된다 (테스트가 버튼을 누르는 지점).
        self.on_iteration = None
        self.iterations = 0

    # -- 창 속성 ----------------------------------------------------------
    def title(self, text):
        self.title_text = text

    def protocol(self, name, func):
        self.protocols[name] = func

    def resizable(self, width, height):
        self.resizable_calls.append((width, height))

    def withdraw(self):
        self._require_alive()
        self.state = "withdrawn"

    def deiconify(self):
        self._require_alive()
        self.state = "normal"

    def lift(self):
        self._require_alive()
        self.lift_calls += 1

    def focus_force(self):
        self._require_alive()
        self.focus_calls += 1

    def destroy(self):
        self.destroy_calls += 1
        self.state = "destroyed"
        self._running = False
        self.scheduled.clear()

    def _require_alive(self):
        if self.state == "destroyed":
            raise TclError("application has been destroyed")

    # -- 스케줄러 ----------------------------------------------------------
    def after(self, delay_ms, func):
        self._require_alive()
        self.scheduled.append((delay_ms, func))
        return f"after#{len(self.scheduled)}"

    def run_pending(self) -> int:
        """지금 예약된 콜백을 한 세대만 실행한다 (재예약분은 다음 세대로)."""
        due, self.scheduled = self.scheduled, []
        for _delay, func in due:
            if self.state == "destroyed":
                break
            func()
        return len(due)

    def mainloop(self):
        """`destroy()` 될 때까지 예약된 콜백을 계속 돌린다."""
        self.mainloop_calls += 1
        self._running = True
        deadline = time.monotonic() + self.MAINLOOP_TIMEOUT_S
        while self._running:
            self.iterations += 1
            if self.on_iteration is not None:
                self.on_iteration(self)
            if not self._running:
                break
            self.run_pending()
            if time.monotonic() > deadline:
                raise AssertionError("fake mainloop never ended (destroy() was not called)")
            # 트레이 스레드가 큐에 넣는 작업을 받아 줄 여유를 준다.
            time.sleep(0.001)


class FakeTk:
    """`gui.GuiController(tk_module=...)` 에 넣는 tkinter 모듈 대역."""

    TclError = TclError

    def __init__(self):
        self.roots = []

    def Tk(self, **kwargs):
        root = FakeRoot(**kwargs)
        self.roots.append(root)
        return root

    def StringVar(self, master=None, value=""):
        return FakeVar(master, value)

    def Frame(self, master=None, **kwargs):
        return FakeWidget(master, **kwargs)

    def Label(self, master=None, **kwargs):
        return FakeWidget(master, **kwargs)

    def Button(self, master=None, **kwargs):
        return FakeWidget(master, **kwargs)

    # -- 편의 ------------------------------------------------------------
    @property
    def root(self) -> FakeRoot:
        assert self.roots, "no Tk() was created"
        return self.roots[-1]
