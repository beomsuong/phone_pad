"""tkinter 어댑터 - 서버 상태 창 (PIN / 접속 주소 / 연결 상태 + 시작·정지 + 종료).

이 모듈이 `tkinter` 를 import 하는 유일한 곳이다. `tray.py` 가 pystray 에 대해
하는 일과 같다: 표시 문구 계산 같은 순수 로직은 전부 `gui_state.py` 에 있고,
여기에는 "위젯을 어떻게 짓는가 / 버튼을 누르면 무엇이 일어나는가 / 언제
갱신하는가" 만 남긴다. `tk_module` 을 주입할 수 있으므로 디스플레이가 없는
환경에서도 동작 전체를 테스트할 수 있다.

스레드 모델 (중요):
  - `run()` 은 **메인 스레드**에서 `mainloop()` 를 돌린다. Tkinter 의 강한 제약이다.
  - 그래서 pystray 트레이가 백그라운드 스레드로 내려간다 (`server.run_with_gui`).
  - **tkinter 위젯은 절대 다른 스레드에서 건드리지 않는다.** CPython 의 `_tkinter`
    는 mainloop 를 소유한 스레드가 아닌 곳에서 호출하면
    `RuntimeError: main thread is not in main loop` 를 던질 수 있어서
    `root.after()` 조차 안전하다고 볼 수 없다. 대신 `queue.Queue` 에 작업을
    넣고, 메인 스레드에서 도는 주기 tick 이 그것을 꺼내 실행한다
    (`request()` / `_drain_commands()`).
"""
import queue
import threading
from typing import Callable, Optional

import gui_state
from tray_status import TOOLTIP_CONNECTED_TEMPLATE, detect_lan_ip

try:  # pragma: no cover - 설치 여부에 따라 갈리는 분기
    import tkinter as tk
    # `ttk` 는 표준 라이브러리다(새 pip 의존성이 아니다). 여기서 한 번 import 해
    # 두면 `tk.ttk` 로 접근할 수 있고, 어댑터는 주입된 `tk_module.ttk` 만 본다.
    from tkinter import ttk as _ttk  # noqa: F401

    GUI_IMPORT_ERROR = None
except Exception as _import_error:  # tcl/tk 미포함 설치, DISPLAY 없음 등
    tk = None
    GUI_IMPORT_ERROR = _import_error


# 연결 상태 갱신 주기. 트레이 폴링과 같은 간격을 쓴다(둘이 따로 놀면 창과 트레이가
# 서로 다른 연결 수를 보여주는 순간이 생긴다).
DEFAULT_POLL_INTERVAL_S = 1.0
# 다른 스레드가 맡긴 작업(트레이의 '창 열기' 등)을 꺼내는 주기. 사람이 클릭에
# 대한 반응으로 느끼는 한계보다 짧아야 한다.
DEFAULT_QUEUE_INTERVAL_S = 0.1

# ---------------------------------------------------------------------------
# 겉모습 (여기 있는 값은 전부 표시 전용이다 - 하나도 동작에 관여하지 않는다)
# ---------------------------------------------------------------------------
# Windows 네이티브 테마. 클래식 `tk` 위젯(회색 베벨 버튼)과 달리 Aero 스타일로
# 그려진다. 다른 플랫폼/구버전 Tcl 에는 없을 수 있어 있을 때만 적용한다.
PREFERRED_THEME = "vista"

PAD_X = 24
PAD_Y = 20
SECTION_GAP = 14   # 섹션(상태 / PIN / 안내 / 버튼) 사이
ROW_GAP = 4        # 같은 섹션 안의 줄 사이
DOT_GAP = 8        # 상태 점과 상태 문구 사이
WRAP_PX = 340      # 오류 문구 줄바꿈 폭
# 창은 내용에 맞춰 자동으로 커지는데, PIN 줄이 28pt(숫자)와 11pt(안내 문구) 사이를
# 오가므로 시작/정지 때마다 폭이 튄다. 최소 폭을 박아 그 흔들림을 없앤다.
MIN_WIDTH_PX = 380

COLOR_SURFACE = "#ffffff"
COLOR_TEXT = "#1f1f23"
COLOR_MUTED = "#6e6e78"
COLOR_FAINT = "#9a9aa4"
COLOR_ERROR = "#b00020"
# 트레이 아이콘과 **같은 색 의미**를 쓴다 (tray.COLOR_IDLE / tray.COLOR_CONNECTED).
# 창과 트레이가 같은 순간에 다른 색으로 상태를 말하면 안 된다.
COLOR_IDLE = "#808080"
COLOR_CONNECTED = "#2ea043"

FONT_FAMILY = "Segoe UI"
FONT_MONO = "Consolas"   # PIN 은 숫자를 한 자씩 읽어야 해서 등폭으로 둔다

STYLE_CARD = "Card.TFrame"
STYLE_STATUS = "Status.TLabel"
STYLE_DOT = "Dot.TLabel"
STYLE_PIN = "Pin.TLabel"
STYLE_PIN_MUTED = "PinMuted.TLabel"
STYLE_BODY = "Body.TLabel"
STYLE_ERROR = "Error.TLabel"
STYLE_HINT = "Hint.TLabel"
STYLE_BUTTON = "TButton"
# 최신 Tcl/Tk 일부 빌드에만 있는 강조 버튼. 없으면 아래 PRIMARY 로 내려간다.
STYLE_ACCENT_BUTTON = "Accent.TButton"
STYLE_PRIMARY_BUTTON = "Primary.TButton"

# 상태 문구 앞에 붙는 점. Canvas 로 도형을 그릴 필요 없이 글자 색만 바꾸면 된다.
STATUS_DOT = "●"
ICON_SIZE_PX = 32

# (스타일 이름, 옵션). 하나가 실패해도 나머지는 적용한다.
STYLE_TABLE = (
    (STYLE_CARD, {"background": COLOR_SURFACE}),
    (STYLE_STATUS, {"background": COLOR_SURFACE, "foreground": COLOR_TEXT,
                    "font": (FONT_FAMILY, 13, "bold")}),
    (STYLE_DOT, {"background": COLOR_SURFACE, "foreground": COLOR_IDLE,
                 "font": (FONT_FAMILY, 13)}),
    (STYLE_PIN, {"background": COLOR_SURFACE, "foreground": COLOR_TEXT,
                 "font": (FONT_MONO, 28, "bold")}),
    (STYLE_PIN_MUTED, {"background": COLOR_SURFACE, "foreground": COLOR_MUTED,
                       "font": (FONT_FAMILY, 11)}),
    (STYLE_BODY, {"background": COLOR_SURFACE, "foreground": COLOR_MUTED,
                  "font": (FONT_FAMILY, 10)}),
    (STYLE_ERROR, {"background": COLOR_SURFACE, "foreground": COLOR_ERROR,
                   "font": (FONT_FAMILY, 9)}),
    (STYLE_HINT, {"background": COLOR_SURFACE, "foreground": COLOR_FAINT,
                  "font": (FONT_FAMILY, 8)}),
    # 네이티브 버튼은 기본 여백이 1px 이라 답답하다 - 숨통만 틔워 준다.
    (STYLE_BUTTON, {"padding": (10, 6), "font": (FONT_FAMILY, 10)}),
)

# "Phone Pad - 연결됨 (" - 트레이 문구 템플릿에서 뽑으므로 문구가 바뀌어도 따라간다.
_CONNECTED_PREFIX = TOOLTIP_CONNECTED_TEMPLATE.split("{")[0]


def status_color(status_text) -> str:
    """상태 문구 -> 점 색. 트레이 아이콘과 같은 규칙(회색=대기, 초록=연결됨)."""
    if isinstance(status_text, str) and status_text.startswith(_CONNECTED_PREFIX):
        return COLOR_CONNECTED
    return COLOR_IDLE


def pin_style(pin_text) -> str:
    """PIN 줄에 쓸 스타일 이름.

    진짜 PIN(숫자)일 때만 '인증 코드'처럼 크게 보여준다. '(서버 정지됨)' 같은
    안내 문구에 28pt 등폭을 씌우면 창만 넓어지고 읽기도 나쁘다.
    """
    if isinstance(pin_text, str) and any(ch.isdigit() for ch in pin_text):
        return STYLE_PIN
    return STYLE_PIN_MUTED


def resolve_primary_style(style) -> str:
    """'시작/정지' 버튼에 쓸 강조 스타일 이름을 고른다.

    1) Tcl/Tk 가 `Accent.TButton` 을 제공하면(일부 최신 빌드) 그대로 쓴다.
    2) 없으면(이 환경의 Tk 8.6.15/vista 가 그렇다) 굵은 글씨 스타일을 직접 만든다.
    3) 그것마저 실패하면 기본 버튼. **어느 경우에도 예외를 밖으로 내지 않는다.**
    """
    try:
        if style.configure(STYLE_ACCENT_BUTTON):
            return STYLE_ACCENT_BUTTON
    except Exception:
        pass
    try:
        style.configure(STYLE_PRIMARY_BUTTON, font=(FONT_FAMILY, 10, "bold"),
                        padding=(10, 6))
        return STYLE_PRIMARY_BUTTON
    except Exception:
        return STYLE_BUTTON


def make_dot_image(tk_module, color, size: int = ICON_SIZE_PX):
    """색 있는 원 하나짜리 `PhotoImage` (창/작업 표시줄 아이콘용).

    `PhotoImage` 는 표준 tkinter 기능이라 **Pillow 없이** 만들 수 있다. 새 이미지는
    전부 투명하므로 원 안쪽만 칠하면 된다.
    """
    image = tk_module.PhotoImage(width=size, height=size)
    center = size / 2.0
    radius = center - 1.0
    for y in range(size):
        dy = y + 0.5 - center
        span = radius * radius - dy * dy
        if span <= 0:
            continue
        half = span ** 0.5
        x0 = max(0, int(round(center - half)))
        x1 = min(size, int(round(center + half)))
        if x1 > x0:
            image.put(color, to=(x0, y, x1, y + 1))
    return image


class GuiUnavailableError(RuntimeError):
    """tkinter 를 못 쓰는데 실제 창을 요구했을 때."""


def gui_available() -> bool:
    return GUI_IMPORT_ERROR is None


def unavailable_reason() -> str:
    return "" if GUI_IMPORT_ERROR is None else str(GUI_IMPORT_ERROR)


class GuiController:
    """창 하나의 수명 주기 + 갱신.

    서버를 직접 알지 못한다 - 시작/정지/종료/상태 조회를 전부 콜백으로 받는다
    (`server.run_with_gui` 가 `ServerSupervisor` 에 연결한다). 덕분에 이 클래스는
    소켓 없이, 그리고 `tk_module` 을 주입하면 디스플레이 없이 테스트된다.
    """

    def __init__(
        self,
        on_start: Callable[[], None],
        on_stop: Callable[[], None],
        on_exit: Callable[[], None],
        is_running: Callable[[], bool],
        count_provider: Callable[[], int],
        pin_provider: Callable[[], Optional[str]] = None,
        port: int = 0,
        tray_visible: Callable[[], bool] = None,
        ip_lookup: Callable[[], Optional[str]] = None,
        tk_module=None,
        poll_interval: float = DEFAULT_POLL_INTERVAL_S,
        queue_interval: float = DEFAULT_QUEUE_INTERVAL_S,
    ):
        self._on_start = on_start
        self._on_stop = on_stop
        self._on_exit = on_exit
        self._is_running = is_running
        self._count_provider = count_provider
        self._pin_provider = pin_provider if pin_provider is not None else (lambda: None)
        self._port = port
        self._tray_visible = tray_visible if tray_visible is not None else (lambda: False)
        self._ip_lookup = ip_lookup if ip_lookup is not None else detect_lan_ip
        self._tk = tk_module if tk_module is not None else tk
        self._poll_interval = poll_interval
        self._queue_interval = queue_interval

        self._root = None
        self._vars = {}
        self._buttons = {}
        # 겉모습 전용 상태. 값이 바뀔 때만 위젯을 건드린다(Windows 깜빡임 방지 -
        # 트레이 어댑터가 아이콘을 다루는 방식과 같다).
        self._style = None
        self._primary_style = STYLE_BUTTON
        self._dot_label = None
        self._pin_label = None
        self._pin_style = None
        self._status_color = None
        self._icons = {}
        self._address_label = None
        self._commands = queue.Queue()
        # 창이 (아직) 없거나 이미 파괴됐음. `request()` 가 이것을 보고 거절한다.
        self._closed = threading.Event()
        self._exiting = False
        # 마지막으로 관측한 실행 상태. "사용자가 누른 정지" 와 "서버가 혼자
        # 죽음" 을 구분하기 위한 것이다.
        self._was_running = False

    # -- 표시용 값 ---------------------------------------------------------

    @property
    def root(self):
        return self._root

    @property
    def closed(self) -> bool:
        return self._closed.is_set()

    def address_label(self) -> str:
        """접속 주소 줄. LAN IP 는 한 번만 조회해 캐시한다 (트레이와 같은 이유)."""
        if self._address_label is None:
            try:
                ip = self._ip_lookup()
            except Exception:
                ip = None
            self._address_label = gui_state.address_line(ip, self._port)
        return self._address_label

    def current_labels(self) -> gui_state.GuiLabels:
        running = self._running_now()
        try:
            count = self._count_provider()
        except Exception:
            count = 0
        return gui_state.compute_labels(running, count, self._pin_now(), self.address_label())

    def _running_now(self) -> bool:
        try:
            return bool(self._is_running())
        except Exception as e:
            print(f"[!] GUI failed to read server state: {e}")
            return False

    def _pin_now(self):
        try:
            return self._pin_provider()
        except Exception:
            return None

    # -- 위젯 --------------------------------------------------------------

    def build(self):
        """창과 위젯을 만든다. `run()` 이 부르지만 테스트는 직접 부를 수 있다."""
        if self._tk is None:
            raise GuiUnavailableError(f"tkinter is not available: {unavailable_reason()}")
        ttk = getattr(self._tk, "ttk", None)
        if ttk is None:
            raise GuiUnavailableError("tkinter.ttk is not available")
        root = self._tk.Tk()
        self._root = root
        self._closed.clear()
        root.title(gui_state.WINDOW_TITLE)
        # X 버튼을 가로챈다 - 트레이가 있으면 숨기고, 없으면 완전 종료.
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        try:
            root.resizable(False, False)
        except Exception:
            pass
        try:
            root.minsize(MIN_WIDTH_PX, 0)
        except Exception:
            pass
        try:
            # 창 여백까지 카드와 같은 색으로 (기본 회색 배경이 '레트로'의 절반이다).
            root.configure(background=COLOR_SURFACE)
        except Exception:
            pass

        self._style = self._setup_style(ttk, root)

        frame = ttk.Frame(root, style=STYLE_CARD, padding=(PAD_X, PAD_Y))
        frame.pack(fill="both", expand=True)

        labels = self.current_labels()
        self._vars = {
            "status": self._tk.StringVar(value=labels.status),
            "address": self._tk.StringVar(value=labels.address),
            "pin": self._tk.StringVar(value=labels.pin),
            "message": self._tk.StringVar(value=gui_state.MESSAGE_NONE),
            "hint": self._tk.StringVar(value=gui_state.close_hint(self._tray_visible())),
            "toggle": self._tk.StringVar(value=labels.toggle),
        }

        # 1) 상태: 색 점 + 문구 (색은 트레이 아이콘과 같은 의미)
        header = ttk.Frame(frame, style=STYLE_CARD)
        header.pack(fill="x")
        self._dot_label = ttk.Label(header, text=STATUS_DOT, style=STYLE_DOT)
        self._dot_label.pack(side="left")
        ttk.Label(
            header, textvariable=self._vars["status"], style=STYLE_STATUS, anchor="w"
        ).pack(side="left", padx=(DOT_GAP, 0))

        ttk.Separator(frame, orient="horizontal").pack(fill="x", pady=(SECTION_GAP, SECTION_GAP))

        # 2) 폰에 입력할 값: PIN 을 크게, 접속 주소를 그 아래 작게
        self._pin_style = pin_style(labels.pin)
        self._pin_label = ttk.Label(
            frame, textvariable=self._vars["pin"], style=self._pin_style, anchor="w"
        )
        self._pin_label.pack(fill="x")
        ttk.Label(
            frame, textvariable=self._vars["address"], style=STYLE_BODY, anchor="w"
        ).pack(fill="x", pady=(ROW_GAP, 0))

        # 3) 오류 / 안내
        ttk.Label(
            frame,
            textvariable=self._vars["message"],
            style=STYLE_ERROR,
            wraplength=WRAP_PX,
            justify="left",
            anchor="w",
        ).pack(fill="x", pady=(SECTION_GAP, 0))
        ttk.Label(
            frame, textvariable=self._vars["hint"], style=STYLE_HINT, anchor="w"
        ).pack(fill="x", pady=(ROW_GAP, 0))

        # 4) 버튼
        buttons = ttk.Frame(frame, style=STYLE_CARD)
        buttons.pack(fill="x", pady=(SECTION_GAP, 0))
        self._buttons["toggle"] = ttk.Button(
            buttons,
            textvariable=self._vars["toggle"],
            width=12,
            style=self._primary_style,
            command=self.on_toggle,
        )
        self._buttons["toggle"].pack(side="left")
        self._buttons["exit"] = ttk.Button(
            buttons, text=gui_state.EXIT_TEXT, width=12, command=self.exit_clicked
        )
        self._buttons["exit"].pack(side="right")

        self._icons = self._build_icons()
        self._apply_status_color(labels.status)

        self._was_running = self._running_now()
        return root

    # -- 겉모습 ------------------------------------------------------------

    def _setup_style(self, ttk, root):
        """ttk 테마와 커스텀 스타일을 준비한다.

        **어느 단계가 실패해도 창은 떠야 한다** - 스타일은 보기 좋으라고 있는
        것이지 서버를 못 켜게 만들 이유가 없다. 그래서 전부 개별로 감싼다.
        """
        try:
            style = ttk.Style(root)
        except Exception as e:
            print(f"[!] GUI style setup failed: {e}")
            self._primary_style = STYLE_BUTTON
            return None
        try:
            if PREFERRED_THEME in style.theme_names():
                style.theme_use(PREFERRED_THEME)
        except Exception:
            pass  # 기본 테마로 둔다 (보기만 달라지고 동작은 같다)
        for name, options in STYLE_TABLE:
            try:
                style.configure(name, **options)
            except Exception:
                pass
        self._primary_style = resolve_primary_style(style)
        return style

    def _build_icons(self):
        """대기/연결됨 두 색의 창 아이콘. 못 만들면 그냥 기본 Tk 아이콘을 쓴다."""
        icons = {}
        for color in (COLOR_IDLE, COLOR_CONNECTED):
            try:
                icons[color] = make_dot_image(self._tk, color)
            except Exception:
                return {}
        return icons

    def _apply_status_color(self, status_text):
        """상태 점(과 창 아이콘) 색을 상태에 맞춘다. 바뀔 때만 건드린다."""
        color = status_color(status_text)
        if color == self._status_color:
            return
        self._status_color = color
        if self._dot_label is not None:
            try:
                self._dot_label.configure(foreground=color)
            except Exception:
                pass
        image = self._icons.get(color)
        if image is not None and self._root is not None:
            try:
                self._root.iconphoto(False, image)
            except Exception:
                pass

    def _apply_pin_style(self, pin_text):
        """PIN 줄의 크기를 내용에 맞춘다(숫자면 크게, 안내 문구면 작게)."""
        want = pin_style(pin_text)
        if want == self._pin_style or self._pin_label is None:
            return
        try:
            self._pin_label.configure(style=want)
        except Exception:
            return
        self._pin_style = want

    # -- 갱신 --------------------------------------------------------------

    def set_message(self, text: str):
        var = self._vars.get("message")
        if var is not None:
            var.set(text)

    def message(self) -> str:
        var = self._vars.get("message")
        return var.get() if var is not None else gui_state.MESSAGE_NONE

    def label(self, key: str) -> str:
        var = self._vars.get(key)
        return var.get() if var is not None else ""

    def refresh(self) -> bool:
        """현재 상태를 다시 읽어 라벨을 갱신한다. 바뀐 게 있으면 True.

        서버가 (사용자가 누르지 않았는데) 혼자 멈춘 것을 여기서 발견하면 안내
        문구를 띄운다 - 창은 계속 살아 있고 사용자는 '시작'을 다시 누를 수 있다.
        """
        running = self._running_now()
        if self._was_running and not running and not self._exiting:
            self.set_message(gui_state.MESSAGE_SERVER_DIED)
        self._was_running = running

        labels = self.current_labels()
        changed = False
        for key, value in (
            ("status", labels.status),
            ("address", labels.address),
            ("pin", labels.pin),
            ("toggle", labels.toggle),
        ):
            var = self._vars.get(key)
            if var is None:
                continue
            if var.get() != value:
                var.set(value)
                changed = True
        hint = gui_state.close_hint(self._tray_visible())
        hint_var = self._vars.get("hint")
        if hint_var is not None and hint_var.get() != hint:
            hint_var.set(hint)
            changed = True
        # 겉모습만 따라 붙는다 - `changed`(= 표시 문구가 바뀌었는가)에는 넣지 않는다.
        self._apply_status_color(labels.status)
        self._apply_pin_style(labels.pin)
        return changed

    # -- 버튼 --------------------------------------------------------------

    def on_toggle(self):
        if self._running_now():
            self.stop_server()
        else:
            self.start_server()

    def start_server(self) -> bool:
        """'시작'. 실패해도 창은 멀쩡히 남고 '정지됨' 상태를 유지한다."""
        try:
            self._on_start()
        except Exception as e:
            # 포트 점유(OSError)가 대표적. 콘솔 로그는 ASCII, 창 문구는 OS 원문 포함.
            print(f"[!] Server failed to start: {e}")
            self._was_running = False
            self.set_message(gui_state.start_error_text(e))
            self.refresh()
            return False
        self._was_running = self._running_now()
        self.set_message(gui_state.MESSAGE_NONE)
        self.refresh()
        return True

    def stop_server(self) -> bool:
        """'정지'. 서버만 내리고 창은 그대로 둔다."""
        ok = True
        try:
            self._on_stop()
        except Exception as e:
            print(f"[!] Server failed to stop cleanly: {e}")
            self.set_message(gui_state.stop_error_text(e))
            ok = False
        else:
            self.set_message(gui_state.MESSAGE_NONE)
        # 사용자가 누른 정지다 - '예기치 않게 멈췄습니다' 안내가 뜨면 안 된다.
        self._was_running = False
        self.refresh()
        return ok

    def exit_clicked(self):
        """'종료' 버튼. 종료 절차를 돌리고 창을 파괴한다 (= mainloop 종료).

        몇 번 눌러도, 트레이 '종료' 와 겹쳐도 절차는 한 번만 돈다.
        """
        if self._exiting:
            return
        self._exiting = True
        try:
            self._on_exit()
        except Exception as e:
            print(f"[!] GUI exit handler failed: {e}")
        finally:
            self.destroy()

    def on_close(self):
        """X 버튼. 트레이가 떠 있으면 숨기고, 아니면 완전 종료."""
        if self._tray_visible():
            self.hide_window()
        else:
            self.exit_clicked()

    # -- 창 상태 -----------------------------------------------------------

    def hide_window(self):
        root = self._root
        if root is None:
            return
        try:
            root.withdraw()
        except Exception as e:
            print(f"[!] GUI hide failed: {e}")

    def show_window(self):
        """트레이에서 창 복원. **메인 스레드에서만** 부를 것 (`request_show` 사용)."""
        root = self._root
        if root is None:
            return
        try:
            root.deiconify()
            root.lift()
        except Exception as e:
            print(f"[!] GUI restore failed: {e}")
            return
        try:
            root.focus_force()
        except Exception:
            # 포커스 강탈은 실패해도 상관없다 (창은 이미 보인다).
            pass

    def destroy(self):
        """창을 파괴해 `mainloop()` 를 끝낸다. 여러 번 호출해도 안전하다."""
        self._closed.set()
        root, self._root = self._root, None
        # 창이 사라지면 아이콘 이미지도 의미가 없다 (Tcl 인터프리터에 매인 객체다).
        self._icons = {}
        if root is None:
            return
        try:
            root.destroy()
        except Exception as e:
            print(f"[!] GUI destroy failed: {e}")

    # -- 스레드 간 호출 ----------------------------------------------------

    def request(self, func: Callable[[], None]) -> bool:
        """다른 스레드가 메인(tkinter) 스레드에 작업을 맡긴다.

        위젯을 직접 건드리지 않고 큐에만 넣는다 - 이것이 이 모듈에서 유일하게
        스레드 세이프한 진입점이다. 창이 이미 닫혔으면 False.
        """
        if self._closed.is_set():
            return False
        self._commands.put(func)
        return True

    def request_show(self) -> bool:
        return self.request(self.show_window)

    def request_exit(self) -> bool:
        return self.request(self.exit_clicked)

    def request_destroy(self) -> bool:
        return self.request(self.destroy)

    def drain_commands(self) -> int:
        """큐에 쌓인 작업을 전부 실행한다 (메인 스레드). 실행한 개수를 반환."""
        done = 0
        while True:
            try:
                func = self._commands.get_nowait()
            except queue.Empty:
                break
            done += 1
            try:
                func()
            except Exception as e:
                print(f"[!] GUI command failed: {e}")
        return done

    # -- 주기 tick ---------------------------------------------------------

    def _after(self, seconds: float, func: Callable[[], None]):
        root = self._root
        if root is None or self._closed.is_set():
            return
        try:
            root.after(max(1, int(seconds * 1000)), func)
        except Exception:
            # 창이 이미 파괴됐다 - 더 이상 예약하지 않는다.
            pass

    def _queue_tick(self):
        self.drain_commands()
        self._after(self._queue_interval, self._queue_tick)

    def _status_tick(self):
        try:
            self.refresh()
        except Exception as e:  # 갱신 오류로 창이 먹통이 되면 안 된다
            print(f"[!] GUI refresh failed: {e}")
        self._after(self._poll_interval, self._status_tick)

    # -- 수명 주기 ---------------------------------------------------------

    def run(self):
        """메인 스레드에서 창을 띄우고 닫힐 때까지 블로킹한다."""
        if self._root is None:
            self.build()
        self._after(self._queue_interval, self._queue_tick)
        self._after(self._poll_interval, self._status_tick)
        try:
            self._root.mainloop()
        finally:
            self.destroy()

    def stop(self):
        """외부(트레이 스레드 등)에서 창을 닫는다. 아무 스레드에서나 안전하다."""
        self.request_destroy()
