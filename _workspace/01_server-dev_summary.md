# server-dev 작업 요약 — PC 트레이 아이콘 (Phase 4)

작업 위치: `C:\Github\phone_pad\.claude\worktrees\tray-icon` (워크트리 밖은 읽기·수정 안 함)
와이어 프로토콜 변경: **없음** (이벤트 `type`/필드/채널/세션 형식 전부 그대로 — 교차 경계면 전환 불필요)

---

## 1. 변경/추가 파일

| 파일 | 상태 | 내용 |
|------|------|------|
| `pc_server/tray_status.py` | 신규 | 순수 로직: 연결 수 → 상태/툴팁, LAN IP 조회(소켓 주입 가능), 주소 라벨 포맷. 표준 라이브러리만 사용 |
| `pc_server/tray.py` | 신규 | pystray 어댑터. `pystray`/`Pillow` import 는 이 파일에만. `TrayController`(아이콘 수명·갱신·종료), `create_icon_image`(Pillow 로 원 그리기) |
| `pc_server/server.py` | 수정 | `ServerRuntime`(정지 가능한 accept 루프), `release_drag`, `run_console`, `run_with_tray`, `parse_args`, `main(argv)` 추가. **기존 함수는 한 줄도 안 건드림** |
| `pc_server/requirements.txt` | 신규 | `pystray>=0.19.5`, `Pillow>=11.0.0` (하한 근거 주석 포함) |
| `pc_server/tests/test_tray_status.py` | 신규 | 순수 로직 33건 |
| `pc_server/tests/test_tray_adapter.py` | 신규 | 가짜 Icon 주입 26건 |
| `pc_server/tests/test_server_shutdown.py` | 신규 | 정지/종료/사망/폴백 31건 |

`AGENTS.md` / `CLAUDE.md` 미수정, 커밋 안 함. 기존 테스트 파일 4개는 **한 글자도 수정하지 않았다.**

---

## 2. 설계 결정과 근거

### 2.1 순수 로직 / 어댑터 분리
`tray_status.py` 는 pystray·Pillow·디스플레이를 전혀 모른다. 그래서 두 패키지가 없는 현재 환경에서도 상태 계산·문자열·IP 조회가 전부 실제로 테스트된다. `tray.py` 의 `TrayController` 는 `icon_factory`/`image_factory`/`ip_lookup` 을 전부 주입받으므로, pystray 없이도 "언제 갱신하는가 / 종료가 무엇을 하는가"를 가짜 `Icon` 으로 끝까지 검증할 수 있다.

메뉴도 pystray 타입에 묶지 않았다. `TrayController.menu_entries()` 가 `MenuEntry(key, text, enabled, action)` 목록을 돌려주고, `_build_menu()` 가 그것을 pystray 객체로 번역만 한다. 테스트는 `key` 로 항목을 찾아 `action()` 을 직접 호출한다.

### 2.2 종료 순서 (확정 설계 3)
트레이 "종료" 클릭 시:

```
quit() → on_quit()  ┌ runtime.stop()                 (stop_event set)
                    ├ server_thread.join(timeout=3s)  (accept 루프가 ≤0.5s 안에 빠져나옴,
                    │                                  그 finally 에서 TCP/UDP 소켓 close)
                    └ release_drag(controller, "tray quit")
                  → tray_controller.stop()            (아이콘 제거)
                  → icon.run() 반환 → run_with_tray finally
                    (runtime.stop / join / release_drag 한 번 더 — 전부 멱등)
                  → main() 이 정수 반환 → sys.exit(code)
```

`os._exit`/`sys.exit` 남발 없음. **드래그 해제를 아이콘 제거보다 먼저** 두는 이유: 아이콘이 사라진 뒤 버튼이 눌린 채 남으면 사용자가 원인을 찾을 단서가 전혀 없다.

`ACCEPT_TIMEOUT_S = 0.5` 는 TCP `accept()` 와 UDP `recvfrom()` 양쪽에 건다(`bind()` 에서 두 소켓 모두 `settimeout`). `udp_listener` 는 이미 `stop_event` 를 받고 `socket.timeout` 을 `continue` 하도록 되어 있어 **시그니처·본문 수정 없이** 그대로 재사용했다.

### 2.3 스레드 모델
pystray 는 Windows 에서 메시지 루프를 메인 스레드가 소유해야 하므로 **트레이가 메인, 서버가 백그라운드 스레드**로 뒤집힌다. 연결 수 폴링은 별도 데몬 스레드(기본 1초)가 돈다 — `SessionRegistry` 에 콜백을 다는 대신 폴링을 고른 이유는 `SessionRegistry` 의 공개 API·동작을 한 글자도 바꾸지 말라는 요구 때문이고, 1초 지연은 트레이 툴팁 용도로 충분하다.

`--no-tray`/미설치 콘솔 모드에서는 **기존처럼 메인 스레드가 서버를 돈다**(스레드 모델 변화 없음).

#### stop() 과 pystray 루프 기동의 경합
서버 스레드가 즉시 죽으면 `tray_controller.stop()` 이 `icon.run()` 보다 먼저 호출될 수 있고, 그 시점의 `icon.stop()` 은 메시지 루프가 없어 유실된다 → 아이콘이 영원히 남는다. 이를 Dekker 식으로 막았다:
- `stop()`: `_stopped` 를 세운 **뒤** `_loop_ready` 를 본다
- `_on_loop_ready()`(pystray `setup` 콜백, 루프가 뜬 뒤 별도 스레드에서 실행): `_loop_ready` 를 세운 **뒤** `_stopped` 를 본다

어느 순서로 엇갈려도 최소 한쪽이 상대의 쓰기를 보므로 아이콘이 남지 않는다. 둘 다 도달하는 경우를 위해 `icon.stop()` 은 lock 기반 test-and-set 으로 정확히 한 번만 보낸다. 이 경합 경로를 `test_stop_arriving_before_message_loop_is_not_lost` 로 고정했다.

### 2.4 서버 스레드 사망 처리 (확정 설계 4)
`server_main()` 은 `BaseException` 까지 잡아 ① 콘솔에 `[!] Server stopped unexpectedly: ...` 로그 ② `runtime.stop()` ③ `tray_controller.stop()` 을 하고, `run_with_tray` 는 **1** 을 반환한다 → `sys.exit(1)`. 아이콘만 남은 좀비가 생기지 않는다.

반대 방향도 막았다: 트레이가 예외로 죽으면 `finally` 에서 `runtime.stop()` + join 이 돌아 서버 소켓이 닫힌다(`test_tray_exception_also_brings_the_server_down`).

**포트 점유로 bind 실패를 재현하지 못한 점**: Windows 에서는 기존 코드가 쓰는 `SO_REUSEADDR` 때문에 이미 점유된 포트에도 bind 가 **성공한다**(실측 확인). 그래서 사망 경로 테스트는 모킹 대신 **이 머신에 없는 주소(`203.0.113.1`)에 bind** 해 진짜 `OSError [WinError 10049]` 를 발생시킨다 — 실제 소켓, 모킹 없음.

### 2.5 드래그 해제 + atexit (AGENTS.md 섹션 10 항목 해소)
`release_drag(controller, reason)` 하나로 통일했다. `InputController._drag_end()` 가 이미 lock 안에서 `if not self._drag_active: return False` 를 하므로 **멱등**이고, 정상 종료 경로와 `atexit` 가 둘 다 불러도 `LEFTUP` 은 한 번만 나간다(`test_release_drag_is_idempotent` 로 고정). 예외는 밖으로 안 나간다.

`main()` 이 `atexit.register(release_drag, controller, "atexit")` 를 등록한다. 실제 자식 프로세스로 검증했다(아래 3.3).

### 2.6 LAN IP
UDP 소켓 `connect(("10.255.255.255", 1))` 로 라우팅만 조회(패킷 전송 없음). 실패하거나 **루프백/0.0.0.0 이 나오면 `None`** 이고 라벨은 `접속 주소: (확인 불가)` 가 된다 — 이때 `:9000` 도 붙이지 않는다(IP 없는 포트 표시는 더 헷갈린다). 사용자가 이 값을 폰에 손으로 입력하므로 `127.0.0.1` 안내는 "서버는 멀쩡한데 폰만 못 붙는" 오해를 만든다. 이 함수는 어떤 예외도 밖으로 던지지 않는다.

조회는 **기동 시 1회만** 하고 캐시한다(`test_lan_ip_is_looked_up_only_once`).

### 2.7 불필요한 갱신 억제
`refresh()` 는 `TrayStatus`(frozen dataclass) 값 비교로 변화가 없으면 아무것도 건드리지 않고 `False` 를 반환한다. 이미지 교체는 **상태 종류가 바뀔 때만** — 1대→2대는 툴팁만 갱신되고 아이콘 이미지는 다시 만들지 않는다.

### 2.8 기존 API 호환
`handle_client` / `udp_listener` / `handle_udp_packet` / `SessionRegistry` / `InputController` 의 시그니처·본문 **무변경**. `test_existing_entry_points_keep_their_signatures` 로 `inspect.signature` 를 고정했고, `TCP_PORT=9000`/`UDP_PORT=9001`/`HOST="0.0.0.0"` 기본값도 테스트로 박았다. 기존 테스트 95건은 손대지 않은 채 전부 통과한다.

---

## 3. 검증 결과 (실제 출력 기준)

### 3.1 pytest — pystray/Pillow **미설치** (기본 환경, 시스템 Python 3.13.1)
```
cd pc_server && PYTHONIOENCODING=utf-8 python -X utf8 -m pytest -q
184 passed, 1 skipped in 1.78s
```
기준선(작업 전) `95 passed` → **신규 90건**. skip 1건은 `test_create_icon_image_produces_distinct_colors_when_pillow_present`(Pillow 없음).

> 인코딩: 테스트 이름과 트레이 문구에 한글이 있어 cp949 콘솔에서 출력이 깨질 것을 우려해 `PYTHONIOENCODING=utf-8` + `-X utf8` 을 붙였지만, **우회 없이 `python -m pytest -q` 만으로도 동일하게 `184 passed, 1 skipped`** 로 통과함을 확인했다(별도 실행으로 검증). 서버의 `print()` 로그는 여전히 ASCII 전용이고, 이를 `test_missing_pystray_falls_back_to_console_with_one_warning` 의 `out.isascii()` 로 강제한다.

### 3.2 pytest — pystray/Pillow **설치** (venv, `C:\Users\membe\.claude\jobs\1cfaabb7\tmp\venv`)
```
184 passed, 1 skipped in 1.92s
```
(여기서는 반대로 "Pillow 없을 때 명확한 에러" 테스트가 skip)

**이 실행이 실제 버그를 잡았다.** 처음엔 `pystray.MenuItem(text, lambda _icon, _item, act=action: act())` 로 썼는데, pystray 는 `action.__code__.co_argcount` 로 인자 수를 세므로 **기본 인자까지 3개로 계산해 `ValueError`** 를 던진다(22건 실패). 미설치 환경에서는 `_build_menu()` 가 pystray 경로를 안 타서 절대 드러나지 않았을 결함이다. `_wrap_action()` 정적 메서드로 `def invoke(icon=None, item=None)` 를 만들어 해결.

### 3.3 실제 스모크 (venv, 저장소 밖 스크립트)
전역 Python 에는 아무것도 설치하지 않았다. venv 에 `pystray 0.19.5 / Pillow 12.3.0 / six 1.17.0` 만 설치했다.

**① 트레이 렌더링 — 검증됨(미검증 아님)**
```
tray_available: True
images: (64, 64) RGBA idle px (128, 128, 128, 255) connected px (46, 160, 67, 255)
menu type: Menu
  item text='Phone Pad - 대기 중'            enabled=False separator=False
  item text='접속 주소: 192.168.0.13:9000'   enabled=False separator=False
  item text='- - - -'                        enabled=True  separator=True
  item text='종료'                            enabled=True  separator=False
-> count=2, waiting for tray refresh
tray title now: Phone Pad - 연결됨 (2대)
-> invoking the Quit menu entry
tray.run() returned after 3.0s
on_quit called: True
```
실제 Windows 트레이에 아이콘이 떴고, 폴링으로 툴팁이 갱신됐고, 메뉴 콜백으로 `run()` 이 풀렸다. LAN IP 도 실제 값(192.168.0.13)이 나왔다.

**② 서버+트레이 전 구간 (포트 19000/19001, 실제 소켓·실제 클라이언트)**
```
Phone Pad Server listening on UDP port 19001 (MOVE only) ...
Phone Pad Server listening on TCP port 19000 ...
initial title: Phone Pad - 대기 중
handshake: {"type": "SESSION", "session": "ad0deb88..."}
title with 1 client: Phone Pad - 연결됨 (1대)
title after disconnect: Phone Pad - 대기 중
-> clicking Quit
run_with_tray returned 0 after 2.5s
drag_active after quit: False
tcp socket closed: True / udp socket closed: True
port released
```

**③ `atexit` 안전장치 — 별도 자식 프로세스로 실측**
정상 종료(exit 0)와 예외 종료(exit 3) 양쪽에서:
```
child: drag_active = True
[!] Drag was active at atexit - left button released
child: sends at end of interpreter = [2, 4]     # LEFTDOWN, LEFTUP
child: LEFTUP released by atexit = True
```

**④ 콘솔 폴백 (시스템 Python, pystray 없음)**
```
$ python server.py
[!] pystray/Pillow not available - running in console mode (No module named 'pystray'). Install with: pip install -r requirements.txt
Phone Pad Server listening on UDP port 9001 (MOVE only) ...
Phone Pad Server listening on TCP port 9000 ...

$ python server.py --no-tray
Phone Pad Server listening on UDP port 9001 (MOVE only) ...      # 경고 없음
Phone Pad Server listening on TCP port 9000 ...
```
경고는 **한 줄**, ASCII, 그리고 서버는 기존과 동일하게 뜬다.

### 3.4 테스트 요구사항 1~7 대응
| 요구 | 대응 | 위치 |
|---|---|---|
| 1 상태/툴팁/라벨(0,1,2), 주소 포맷, IP 성공·실패 | 33건 | `test_tray_status.py` |
| 2 가짜 Icon: 갱신/불필요 갱신 억제/종료 콜백 | 26건 | `test_tray_adapter.py` |
| 3 정지 가능 서버(실제 소켓·포트 0), 재바인딩 | 8건 | `test_server_shutdown.py` |
| 4 종료 시 드래그 해제 + atexit + 중복 안전 | 6건 + 실측 ③ | 〃 |
| 5 서버 스레드 사망 → 로그·트레이 정지·비정상 코드 | 4건 | 〃 |
| 6 `--no-tray` / 미설치 폴백 | 7건 + 실측 ④ | 〃 |
| 7 기존 pytest 전부 통과 | 95건 무수정 통과 | 기존 파일 |

테스트에서 쓰는 소켓은 **전부 포트 0 바인딩**이라 다른 프로세스의 9000/9001 사용 여부에 좌우되지 않는다.

---

## 4. 미해결 이슈

1. **`SO_REUSEADDR` 때문에 서버 이중 실행이 조용히 성공한다 (신규 발견, 이번 범위 밖)**
   Windows 에서 `SO_REUSEADDR` 는 이미 점유된 포트에도 bind 를 허용한다(실측). 즉 사용자가 실행 파일을 두 번 띄우면 **트레이 아이콘이 두 개 뜨고**, 둘 다 "대기 중"인데 TCP 연결은 한쪽만 받는다. 트레이가 생기면서 이 실수가 눈에 보이게 됐다. 해결하려면 TCP 소켓에 `SO_EXCLUSIVEADDRUSE` 를 쓰거나 named mutex 로 단일 인스턴스를 강제해야 하는데, 둘 다 기존 소켓 동작 변경이라 리더 판단이 필요하다. PyInstaller 패키징 항목과 함께 다루는 게 자연스럽다.
2. **LAN IP 는 기동 시 1회만 조회한다** — 서버가 도는 중에 Wi-Fi 를 바꾸거나 VPN 을 붙이면 메뉴의 주소가 낡은 값으로 남는다. 재시작하면 갱신된다. 폴링마다 소켓을 열 이유가 없어 캐시를 택했다.
3. **연결 수 = TCP 세션 수** (`SessionRegistry`). 재연결 직후 옛 세션이 아직 회수되지 않은 구간(최대 15초)에는 툴팁이 "연결됨 (2대)" 로 보일 수 있다 — AGENTS.md 섹션 10 "재연결 직후 서버 세션 2개" 와 같은 뿌리다. 서버의 실제 인식을 그대로 보여주는 것이므로 숨기지 않았다.
4. **폴링 주기 1초** — 연결/해제 후 최대 1초 뒤에 아이콘이 바뀐다. 체감이 느리면 `DEFAULT_POLL_INTERVAL_S` 만 줄이면 된다.
5. **`run_console` 의 `[!] Server failed to start` 문구** — 실제로는 `bind()` 실패만 이 경로를 타지만(accept 루프의 `OSError` 는 안에서 잡아 `break` 한다), 이론적으로 다른 `OSError` 가 오면 문구가 약간 오도할 수 있다.
6. **종료 시 접속 중인 클라이언트 소켓은 명시적으로 닫지 않는다** (스펙상 이번 범위 밖). 프로세스가 끝나면 OS 가 닫고 앱은 EOF/heartbeat 로 감지해 재연결 로직을 탄다.

---

## 5. 실환경에서만 확인 가능한 항목

- **콘솔에서 실제 Ctrl+C** — `KeyboardInterrupt` 경로는 주입한 runtime 으로 단위 테스트했지만(`test_console_mode_keyboard_interrupt_releases_drag`), 진짜 콘솔에서 Ctrl+C 를 눌러 드래그 해제 로그까지 나오는지는 미확인. (자동화하려면 `CTRL_C_EVENT` 를 콘솔 그룹 전체에 보내야 해서 에이전트 셸까지 같이 죽는다.)
- **트레이 아이콘의 시각적 품질** — 64×64 RGBA 원이 Windows 트레이에서 축소될 때 얼마나 또렷한지, 회색/초록 대비가 다크 테마 작업표시줄에서 충분한지. 이미지 생성 자체와 두 색의 픽셀 차이는 검증됐다.
- **메뉴의 한글 렌더링** — 문자열이 pystray `MenuItem.text` 에 정확히 들어가는 것까지는 확인했으나(스모크 ①), 실제 팝업 메뉴에서 폰트·잘림 없이 보이는지는 육안 확인 필요.
- **`Phone Pad - 연결됨 (N대)` 의 N≥2 실사용** — 다중 기기는 정책 미정(AGENTS.md 섹션 10)이라 표시만 준비돼 있다.
- **PyInstaller `--noconsole` 패키징** (별도 항목): `sys.stdout is None` 일 때 CPython 의 `print()` 는 **조용히 아무것도 안 한다**(실측 확인)—따라서 `--noconsole` 로 묶어도 `print` 때문에 죽지 않는다. 다만 그 상태에서는 위 ④의 폴백 경고와 사망 로그가 **아무 데도 안 보이므로**, 패키징 시 파일 로깅이나 트레이 알림으로 대체하는 것을 권한다.
- **실제 안드로이드 앱과의 연동** — 프로토콜을 안 건드렸으므로 회귀는 없어야 하지만, 트레이 모드로 띄운 서버에 실기기가 붙어 MOVE/CLICK 이 정상 동작하는지는 실기기 확인 대상.
