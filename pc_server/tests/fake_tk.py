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
    # ttk 위젯인지 클래식 tk 위젯인지 (`FakeTtk` 가 만든 것만 True).
    themed = False

    def __init__(self, master=None, **kwargs):
        self.master = master
        self.kwargs = kwargs
        self.children = []
        self.pack_kwargs = None
        self.configure_calls = []
        if master is not None and hasattr(master, "children"):
            master.children.append(self)

    @property
    def style(self):
        return self.kwargs.get("style")

    # -- 배치 (값은 검사하지 않고 호출만 받아 준다) ------------------------
    def pack(self, **kwargs):
        self.pack_kwargs = kwargs
        return self

    # -- 설정 변경 ---------------------------------------------------------
    def configure(self, **kwargs):
        """`widget.configure(...)` 대역. 인자가 없으면 조회(실제 Tk 와 같다)."""
        if not kwargs:
            return dict(self.kwargs)
        self.kwargs.update(kwargs)
        self.configure_calls.append(dict(kwargs))
        return None

    config = configure

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
        self.minsize_calls = []
        self.state = "normal"       # normal | withdrawn | destroyed
        self.icon_photos = []        # iconphoto() 로 지정된 (default, image)
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

    def minsize(self, width, height):
        self.minsize_calls.append((width, height))

    def iconphoto(self, default, image):
        self._require_alive()
        self.icon_photos.append((default, image))

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


class FakePhotoImage:
    """`tk.PhotoImage` 대역 - 창 아이콘용. 칠한 영역만 기록한다."""

    def __init__(self, master=None, width=0, height=0, **kwargs):
        self.width = width
        self.height = height
        self.puts = []          # [(color, (x0, y0, x1, y1))]

    def put(self, color, to=None):
        self.puts.append((color, to))


class FakeThemedWidget(FakeWidget):
    """ttk 위젯 대역. 클래식 tk 위젯과 구분되어야 테스트가 전환을 확인할 수 있다."""

    themed = True


class FakeStyle:
    """`ttk.Style` 대역.

    실제 ttk 의 두 가지 성질을 흉내 낸다:
      - `configure(name)` (옵션 없이) 는 **조회**이고, 정의된 적 없는 스타일이면
        `None` 을 준다 (`Accent.TButton` 존재 여부 판정이 여기에 기댄다).
      - `theme_use(name)` 은 없는 테마면 `TclError`.
    """

    # 이 환경(Windows, Tk 8.6.15)의 실제 목록과 같게 둔다.
    DEFAULT_THEMES = ("winnative", "clam", "alt", "default", "classic", "vista", "xpnative")

    def __init__(self, master=None, themes=None, builtin=None):
        self.master = master
        self.themes = tuple(self.DEFAULT_THEMES if themes is None else themes)
        # 테마가 미리 정의해 둔 스타일 (기본값: 이 환경처럼 Accent 는 없다).
        self.configured = dict(builtin or {"TButton": {"padding": "1 1"}})
        self.theme = self.themes[-1] if self.themes else "default"
        self.theme_uses = []

    def theme_names(self):
        return self.themes

    def theme_use(self, name=None):
        if name is None:
            return self.theme
        if name not in self.themes:
            raise TclError(f"unknown theme {name!r}")
        self.theme = name
        self.theme_uses.append(name)
        return None

    def configure(self, style, **options):
        if not options:
            return self.configured.get(style)
        self.configured.setdefault(style, {}).update(options)
        return None

    def lookup(self, style, option):
        return self.configured.get(style, {}).get(option, "")


class FakeTtk:
    """`tkinter.ttk` 모듈 대역 (`FakeTk().ttk`)."""

    def __init__(self, style_factory=None):
        self.styles = []
        self.widgets = []
        self._style_factory = style_factory if style_factory is not None else FakeStyle

    def Style(self, master=None):
        style = self._style_factory(master)
        self.styles.append(style)
        return style

    def _widget(self, master, kwargs):
        widget = FakeThemedWidget(master, **kwargs)
        self.widgets.append(widget)
        return widget

    def Frame(self, master=None, **kwargs):
        return self._widget(master, kwargs)

    def Label(self, master=None, **kwargs):
        return self._widget(master, kwargs)

    def Button(self, master=None, **kwargs):
        return self._widget(master, kwargs)

    def Separator(self, master=None, **kwargs):
        return self._widget(master, kwargs)

    # -- 편의 ------------------------------------------------------------
    @property
    def style(self) -> FakeStyle:
        assert self.styles, "no ttk.Style() was created"
        return self.styles[-1]


class FakeTk:
    """`gui.GuiController(tk_module=...)` 에 넣는 tkinter 모듈 대역."""

    TclError = TclError

    def __init__(self, ttk_module=None):
        self.roots = []
        self.images = []
        # `gui.py` 는 `tk_module.ttk` 로 themed 위젯을 만든다 (실제 tkinter 도
        # `from tkinter import ttk` 이후에는 `tkinter.ttk` 로 접근할 수 있다).
        self.ttk = ttk_module if ttk_module is not None else FakeTtk()

    def Tk(self, **kwargs):
        root = FakeRoot(**kwargs)
        self.roots.append(root)
        return root

    def StringVar(self, master=None, value=""):
        return FakeVar(master, value)

    def PhotoImage(self, master=None, **kwargs):
        image = FakePhotoImage(master, **kwargs)
        self.images.append(image)
        return image

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
