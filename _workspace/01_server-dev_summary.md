# server-dev 요약 — PC 서버 tkinter GUI 창 (기본 실행 모드로 승격)

단일 사이드(서버). 와이어 프로토콜 무변경 — `phone_pad_app/`, `AGENTS.md`, `CLAUDE.md`, `.claude/worktrees/` 무수정, 커밋 없음, `git stash`/`checkout`/`reset` 사용 안 함.

## 테스트 결과 (실제 실행 수치)

| 항목 | 값 |
|------|-----|
| 기준선 | `585 passed, 1 skipped` |
| 최종 | **`702 passed, 1 skipped`** (`cd pc_server && python -m pytest`, 약 12.6초) |
| 회귀 | **0** (skip 1건은 기존과 동일 — Pillow 미설치 경로) |
| 신규 테스트 | **117건** |
| 실기 검증 | 실제 Tk + 실제 pystray 데스크톱 프로브 **23/23 PASS** (3회 반복, 매번 전부 통과) |

전역 Python 무변경(`pystray`/`Pillow` 미설치 유지). 실기 검증은 저장소 밖 임시 venv(`%TEMP%\claude\...\scratchpad\trayvenv`)에서 돌렸다 — 프로젝트 관례 그대로.

## 변경 파일

### 신규
| 파일 | 내용 |
|------|------|
| `pc_server/gui_state.py` | 창 문구의 **순수 로직**(Tk 비의존). 상태/주소/PIN 줄은 `tray_status.py` 함수를 그대로 재사용하고, 창에만 있는 개념(정지 상태, 시작/정지 토글, 오류 문구, 닫기 안내)만 새로 만든다 |
| `pc_server/gui.py` | tkinter 어댑터 `GuiController` (`tray.py`가 pystray에 대해 하는 일과 같은 자리). `tk_module` 주입 가능 |
| `pc_server/tests/fake_tk.py` | tkinter 대역(위젯 트리 + `after` 스케줄러 + 가짜 `mainloop`). `tests/fake_conn.py`와 같은 역할 |
| `pc_server/tests/test_gui_state.py` | 순수 로직 24건 (+ PyInstaller spec 가드 1건) |
| `pc_server/tests/test_gui_adapter.py` | 어댑터 39건 (가짜 Tk 35 + 실제 Tk 4) |
| `pc_server/tests/test_server_gui.py` | `ServerSupervisor`/`run_with_gui`/폴백 사슬/정지 시 클라이언트 해제 44건 |

### 수정
| 파일 | 내용 |
|------|------|
| `pc_server/server.py` | `ServerSupervisor`(런타임 팩토리 기반 시작/정지), `disconnect_active_client()`, `run_with_gui()`, `main()` 폴백 사슬 + PIN을 런타임 팩토리 안으로 이동 |
| `pc_server/tray.py` | `pin`이 **호출 가능 객체**도 될 수 있게(시작마다 PIN이 바뀜), `on_show` 콜백 + `창 열기` 메뉴 항목(`default=True` → 아이콘 클릭 시 창 복원), `MenuEntry.default` |
| `pc_server/tray_status.py` | `SHOW_WINDOW_TEXT = "창 열기"` 추가 (+2줄) |
| `pc_server/single_client.py` | `disconnect()` 분리(알림 없이 clean EOF로 끊기). `evict()`는 그것을 호출하도록 정리 — 동작 동일 |
| `pc_server/phone_pad_server.spec` | `excludes`에서 **`tkinter` 제거** (아래 "미해결" 참조) |
| `pc_server/tests/test_server_shutdown.py` | 기존 테스트 2건 수정 (**스펙 이탈** — 아래 참조) |
| `pc_server/tests/test_tray_adapter.py` | 트레이 신규 기능 테스트 10건 추가, `Harness`에 `on_show` 인자 |

## 아키텍처 — 요청서의 스레드 모델을 그대로 적용했고, 실측으로 확인됨

- **tkinter `mainloop()` = 메인 스레드** (`run_with_gui`가 메인 스레드에서 `GuiController.run()`).
- **pystray = 백그라운드 스레드**(`phone-pad-tray`). 요청서의 가설대로 Windows `_win32` 백엔드는 메인 스레드를 요구하지 않는다 — **실측 확인**: 아이콘 객체 생성, `icon.visible == True`, 실제 win32 윈도 핸들(`icon._hwnd`), 백그라운드 스레드에서 실행 중, 그 스레드에서 메뉴 항목 콜백 실행까지 전부 성공.
- **서버 = 별도 백그라운드 스레드**(`phone-pad-server`), 시작마다 새 `ServerRuntime`(팩토리).
- **스레드 간 호출은 전부 `queue.Queue`를 경유**한다. `root.after()`를 다른 스레드에서 부르는 방법은 **쓰지 않았다** — CPython `_tkinter`는 mainloop를 소유하지 않은 스레드의 호출에 `RuntimeError: main thread is not in main loop`를 던질 수 있어 계약상 안전하지 않다. 트레이 스레드는 `GuiController.request(...)`로 큐에만 넣고, 메인 스레드의 100ms tick이 꺼내 실행한다(상태 갱신 tick은 트레이와 같은 1초).

### 시작/정지의 의미
- `ServerRuntime`은 재사용 불가 → GUI 계층은 **팩토리**를 받는다. '시작'마다 새 인스턴스 + **새 PIN**(팩토리가 `resolve_expected_pin()`을 다시 호출).
- `ServerSupervisor.start()`는 **호출 스레드에서 `bind()`까지** 끝낸다 → 포트 점유를 `OSError`로 즉시 돌려줘 창이 메시지를 띄울 수 있다. accept 루프(`serve()`)만 스레드로 내려간다.
- `stop()`의 `join`은 **락 밖에서** 한다(서버 스레드 정리 코드가 같은 락을 잡으므로 락 안에서 join하면 교착 — 회귀 테스트 있음).

## 작업 중 실제로 발견한 것 (요청서에 없던 것들)

1. **`ServerRuntime.stop()`만으로는 '정지'가 거짓말이 된다.** 리슨/UDP 소켓만 닫히고 **이미 맺어진 TCP 연결의 처리 스레드는 살아남는다.** 폰이 5초마다 보내는 heartbeat가 미응답 카운터를 계속 리셋하므로 그 연결은 **영원히 안 끊긴다** — 정지 후에도 CLICK/SCROLL/DRAG/DESKTOP_SWITCH가 계속 실행된다(MOVE만 UDP 소켓이 닫혀 멈춘다). 프로세스가 곧 끝나던 기존 모드에서는 드러날 수 없던 구멍이다. → `disconnect_active_client()`를 `ServerSupervisor.stop()`에 추가(가드가 없는 런타임에는 무영향이라 기존 테스트 0건 영향). **`SESSION_REPLACED`는 보내지 않는다** — 그걸 보내면 앱이 자동 재연결을 포기한다. clean EOF로 끊어 앱의 평소 연결 유실 처리(재연결 시도)를 타게 했다. 실소켓 회귀 테스트 6건.
2. **pytest가 실제 창을 띄우고 영원히 멈출 수 있다.** 기본 모드를 바꾼 직후 `test_tray_mode_is_used_when_dependencies_are_available`가 `run_with_gui`만 모킹하지 않은 채 `main([])`을 불러, 진짜 "Phone Pad Server" 창을 띄우고 `mainloop()`에서 블로킹했다(2분 타임아웃 → 강제 종료). **`main()`을 부르는 테스트는 반드시 `run_with_gui`도 패치해야 한다.**
3. **한 pytest 프로세스에서 `tkinter.Tk()`를 반복 생성하면 5회에 1회꼴로 `_tkinter.create()`가 `TclError`로 실패한다.** (단독 스크립트로 120회 반복할 때는 재현 안 됨 — pytest 안에서만.) → 실제 Tk 테스트는 **모듈당 Tcl 인터프리터 1개**를 만들고 각 테스트는 `Toplevel`을 받게 바꿨다. 8회 연속 재실행 전부 통과.
4. **PyInstaller spec이 `tkinter`를 명시적으로 excludes 하고 있었다**("서버는 GUI가 없으니 ~5MB 절약"). 그대로 두면 exe는 **아무 오류 없이 조용히** 창 없는 트레이 모드로 떨어진다. 제거하고, 다시 들어가지 않도록 spec을 읽는 가드 테스트를 넣었다.
5. 변이 검사(mutation check)로 새 테스트가 실제로 버그를 잡는지 확인: (a) X 버튼이 트레이 유무를 무시하게, (b) '시작'이 같은 런타임을 재사용하게 → **6건이 실패**(각각 1건, 5건). 두 변이 모두 되돌림.

## 실기 검증 (개발자의 실제 Windows 데스크톱)

### A. 프로덕션 `run_with_gui()` 프로브 — 실제 Tk + 실제 pystray, 대체 포트 9100/9101, **23/23 PASS**
창 생성/화면 표시, mainloop가 메인 스레드 소유, 트레이 아이콘 객체 + `visible=True` + 실제 hwnd, 트레이가 백그라운드 스레드, 자동 시작 후 TCP 접속 성공, PIN 표시, **X 버튼 → `withdrawn`(서버는 계속 동작)**, **트레이 `창 열기` 항목을 트레이 스레드에서 호출 → 창 복원**, **정지 → 포트 반납 / 시작 → 재바인딩**, **재시작 시 새 PIN + 트레이 툴팁도 따라감**, 종료 시 코드 0 + 포트 반납 + 잔여 스레드 0.

### B. 실제 엔트리포인트 `python server.py` (venv, `--allow-multiple --no-discovery`)
- 프로세스의 top-level 윈도 열거 결과: `TkTopLevel` "Phone Pad Server" **visible=True** + `phone_pad...SystemTrayIcon` 윈도 — **창과 트레이가 한 프로세스에 동시에 존재**.
- TCP 9000 LISTENING 확인.
- 그 창에 **실제 `WM_CLOSE`(= X 버튼이 보내는 바로 그 메시지)** 를 보냄 → 창 `visible=False`(파괴 아님, 숨김), 프로세스 생존, 9000 계속 LISTENING. **X = 트레이로 축소가 실제로 동작**.
- 검증 후 프로세스 종료, 포트 반납 확인.

### 정리 상태
내가 띄운 프로세스/포트는 전부 회수했다(9100/9101 잔여 없음). **다만 내 세션 시작 전(18:49)부터 떠 있던 외부 프로세스 하나가 남아 있다**: `C:\Python313\python.exe server.py --no-tray` (pid 27452, TCP 9000 / UDP 9001 / UDP 9002 점유). 내가 띄운 게 아니라서 건드리지 않았다 — **리더가 확인 후 정리할 것**. 이것 때문에 기본 포트(9000)로는 `python server.py`를 그대로 띄울 수 없어 `--allow-multiple`을 썼다(Windows `SO_REUSEADDR` 때문에 bind는 성공한다).
(무관하지만 눈에 띈 것: 저장소 루트에 내가 만들지 않은 `READEME.md`(오타)가 18:59에 생겼다. 다른 세션 것으로 보임.)

## 스펙 이탈 / 판단이 필요한 결정

1. **[이탈] 기존 테스트 2건을 수정했다** — 요청서 "테스트 4. 기존 테스트 회귀 없음(무수정 통과)"과 "구현 지침 3(기본 모드를 창으로 승격)"이 서로 충돌한다. `test_server_shutdown.py`의 `test_tray_mode_is_used_when_dependencies_are_available` / `test_missing_pystray_falls_back_to_console_with_one_warning`은 **"기본 = 트레이"** 를 고정하고 있어서, 기본 모드를 바꾸면 반드시 깨진다. 두 테스트의 **의도(트레이 모드 선택 / pystray 없을 때 콘솔 폴백)를 보존**하도록 "tkinter를 못 쓰는 상황"을 함께 흉내 내게 고쳤다(각 1줄 추가 + 사유 주석). 그 외 기존 테스트는 전부 무수정 통과.
2. **[추가] `phone_pad_server.spec`의 `tkinter` 제외를 풀었다.** 요청서는 "PyInstaller 재빌드는 범위 밖"이라 했고 재빌드는 하지 않았지만, 빌드 정의를 그대로 두면 **exe에는 이번 기능이 아예 없다**(조용한 폴백이라 사람이 눈치채기 어려움). 파일이 `pc_server/` 안이라 범위 내로 판단.
3. **[결정] `run_with_gui`는 창을 정상적으로 닫으면 항상 0을 반환한다.** `run_with_tray`는 서버 스레드가 죽으면 1을 반환하지만(좀비 아이콘 방지), 창 모드에는 좀비가 없다 — 오류를 창에 띄우고 사용자가 '시작'을 다시 누를 수 있는 것이 이 모드의 계약이다. 요청서가 반환값을 규정하지 않아 이렇게 정했다.
4. **[결정] 트레이 PIN을 호출 가능 객체로 받게 `tray.py`를 확장**했다. '시작'마다 PIN이 바뀌는데 기존 `TrayController`는 생성 시점의 문자열을 붙들고 있어 창과 트레이가 서로 다른 PIN을 보여주게 된다. 고정 문자열/None을 넘기는 기존 경로는 **한 글자도 달라지지 않는다**(테스트로 고정). 단, 창 모드에서는 PIN 메뉴 항목이 **항상** 존재한다(pystray는 메뉴 생성 후 항목 추가가 안 되므로) — `--no-auth`면 "PIN: (사용 안 함)"으로 보인다.
5. **[결정] 자동 시작을 GUI 명령 큐로 넣었다**(`request(start_server)`). 위젯이 생긴 뒤 실행돼야 포트 점유 오류를 창에 띄울 수 있다.

## 미해결 이슈 / 한계

1. **exe 재빌드·검증 안 함**(요청서상 범위 밖). spec에서 `tkinter` 제외를 풀었으므로 **재빌드가 필요하고 exe가 ~5MB 커진다**. windowed(`--noconsole`) 빌드에서 tkinter 창이 제대로 뜨는지는 **미검증**.
2. **사람 눈 검증이 남은 항목**: 알림 영역에 아이콘이 실제로 그려지는 모습, 아이콘 **더블클릭**으로 창이 열리는지(`default=True`로 지정했고 메뉴 항목 콜백 자체는 실측했지만, pystray의 더블클릭 디스패치는 사람이 눌러 봐야 한다), 창 레이아웃/폰트 미관, 고DPI 배율.
3. **'정지' 버튼을 누르는 순간 창이 최대 3초 멈출 수 있다**(`SHUTDOWN_JOIN_TIMEOUT_S`만큼 서버 스레드 join을 메인 스레드에서 기다림). 실제로는 `accept_timeout`이 0.5초라 보통 그 이하다. 진행 표시는 없다.
4. **Windows `SO_REUSEADDR` 때문에 '시작 실패'가 실전에서 잘 안 나타난다.** 다른 프로세스가 9000을 점유해도 bind가 성공해 버려, 창은 "시작됨"으로 보이지만 트래픽은 못 받을 수 있다(기존부터 있던 문제, 섹션 10). 프로세스 중복은 named mutex가 막으므로 실사용 경로는 대부분 걸러진다. 시작 실패 경로 자체는 바인드 불가 주소(`203.0.113.1`)로 실측 검증했다.
5. **`disconnect_active_client`는 `SingleClientGuard`가 아는 연결 1개만 끊는다.** 단일 클라이언트 정책상 그게 전부지만, 가드를 넘기지 않는 런타임(단위 테스트/직접 호출)에서는 아무 일도 하지 않는다. 또 AUTH 진행 중인 연결은 대상이 아니다(세션 발급 전이라 3초 안에 스스로 끝난다).
6. **LAN IP는 여전히 한 번만 조회해 캐시한다**(트레이와 동일). 서버 구동 중 PC의 IP가 바뀌면 창의 접속 주소가 옛 값으로 남는다 — '정지/시작'으로도 갱신되지 않는다(캐시가 `GuiController` 수명 단위).
7. **창을 숨긴 상태에서도 1초 폴링이 계속 돈다**(트레이 폴링과 동일 수준의 비용). 최적화하지 않았다.
8. `logging_setup`은 손대지 않았다. 콘솔 없는 실행에서 창이 뜨더라도 `print()` 로그는 기존대로 파일로 간다.

## 리더가 AGENTS.md에 반영할 내용 (제안)

**섹션 8 (실행 방법) — PC 서버**
- `python server.py`의 기본 동작이 **"트레이 아이콘"에서 "서버 창 + 트레이 아이콘"** 으로 바뀌었다. 창에는 연결 상태 / 접속 주소(`LAN IP:9000`) / PIN / **[정지·시작] [종료]** 두 버튼이 있다.
- **X(닫기)는 종료가 아니라 트레이로 축소**다. 트레이 메뉴의 **"창 열기"**(또는 아이콘 클릭)로 다시 연다. pystray/Pillow가 없으면 창만 뜨고, 그때는 X가 곧 종료다(콘솔에 그 취지의 경고 한 줄).
- `--no-tray`의 의미가 **"그래픽 UI 전부 끄고 콘솔 모드"** 로 넓어졌다(새 플래그 없음). 폴백 사슬: **창(+트레이) → (tkinter 없으면) 트레이만 → (pystray도 없으면) 콘솔**.
- **PIN은 이제 "서버 시작" 단위**다. 창에서 정지 후 다시 시작하면 새 PIN이 나오고 폰에 다시 입력해야 한다(`--pin` 고정 시에는 그대로).
- **"정지"는 붙어 있던 폰의 연결도 끊는다**(clean EOF). 앱은 평소의 연결 유실로 보고 자동 재연결을 시도하다 실패한다 — "시작"을 다시 누르면 붙는다.

**섹션 9 (코딩 컨벤션)** — 새로 추가할 만한 것 3개:
- **tkinter 위젯은 mainloop를 소유한 스레드에서만 건드린다.** 다른 스레드는 `GuiController.request(...)`(큐)만 쓴다. `root.after()`를 외부 스레드에서 부르는 것도 안전하지 않다(`RuntimeError: main thread is not in main loop`).
- **`server.main()`을 호출하는 테스트는 `run_with_gui`도 반드시 패치한다.** 안 하면 pytest가 진짜 창을 띄우고 `mainloop()`에서 영원히 멈춘다(실제로 겪음).
- **한 pytest 프로세스에서 `tkinter.Tk()`를 반복 생성하지 않는다.** 5회에 1회꼴로 `_tkinter.create()`가 `TclError`로 실패한다. 인터프리터는 모듈당 1개만 만들고 테스트마다 `Toplevel`을 쓴다(`tests/test_gui_adapter.py`의 `real_tk` 픽스처).
- (기존 "`ServerRuntime`의 새 선택 인자는 기본값을 비활성으로" 컨벤션을 이번에도 지켰다 — `TrayController.on_show`, `disconnect_active_client`의 가드 모두 없으면 무동작.)

**섹션 10 (미결 사항)** — 위 "미해결 이슈" 1·2·3·4·6 항목.
