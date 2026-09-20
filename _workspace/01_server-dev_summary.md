# server-dev 작업 요약 — PyInstaller 단일 exe + 중복 실행 방지 + --noconsole 로그 보존

**범위:** 서버 단일 사이드. 와이어 프로토콜 무변경(이벤트 타입·필드 추가/수정 없음).
**테스트:** `cd pc_server && python -m pytest` → **227 passed, 1 skipped** (기준선 184 passed, 1 skipped → +43).
전역 Python은 변경하지 않았다(pystray/Pillow 미설치 상태 그대로). 빌드용 venv는 스크래치패드에 만들고 검증 후 삭제했다.

---

## 1. 변경/신규 파일

### 신규
| 파일 | 내용 |
|------|------|
| `pc_server/single_instance.py` | Windows named mutex 기반 단일 인스턴스 가드 (`Local\PhonePadServer`) |
| `pc_server/logging_setup.py` | windowed 실행 시 `sys.stdout`/`sys.stderr` → 로그 파일 리다이렉트 |
| `pc_server/phone_pad_server.spec` | PyInstaller 빌드 정의 (onefile + windowed) |
| `pc_server/build_exe.ps1` | 저장소 밖 venv 생성 → 의존성 설치 → 빌드 스크립트 |
| `pc_server/tests/test_single_instance.py` | 가드 테스트 25건 |
| `pc_server/tests/test_logging_setup.py` | 로그 설정 테스트 18건 |

### 수정 (최소·국소 — `server.py`는 **+20줄, import 2줄 + `parse_args` + `main()` 진입부만**)
| 파일 | 변경 |
|------|------|
| `pc_server/server.py` | `import logging_setup` / `import single_instance`, `--allow-multiple` 인자, `main()` 첫머리에서 `configure_stdio()` → `enforce()` 호출 후 종료 코드 2면 즉시 반환. **`ServerRuntime`/`handle_client`/`udp_listener`/`SessionRegistry`/소켓 옵션은 전부 무변경** |
| `pc_server/tests/conftest.py` | autouse fixture로 mutex 이름을 pytest 프로세스 전용(`...-pytest-<pid>`)으로 격리 (아래 "함정 3") |
| `pc_server/.gitignore` | `build/`, `dist/` 추가 (`.spec`·`build_exe.ps1`은 추적) |

`discovery` worktree와의 병합 충돌 면적: `server.py`의 import 블록 2줄 + `parse_args` 끝 + `main()` 첫 3줄. 새 로직은 전부 신규 모듈에 있다.

---

## 2. 구현 내용

### A. 단일 인스턴스 가드 (`single_instance.py`)
- `CreateMutexW(NULL, FALSE, "Local\PhonePadServer")` + `GetLastError() == ERROR_ALREADY_EXISTS(183)`.
  `ctypes.WinDLL("kernel32", use_last_error=True)`를 쓴다 — `ctypes.windll.kernel32`(use_last_error 없음)로는 ctypes 내부 호출이 끼어들어 LastError가 흐려질 수 있다.
- **소켓은 손대지 않았다.** `SO_REUSEADDR`, TIME_WAIT 재바인드 동작 전부 그대로.
- 핸들은 모듈 전역 `_HELD` dict에 보관 — 지역 변수면 GC 시 mutex가 풀려 가드가 무력화된다.
- `_HELD` 덕분에 **같은 프로세스 안에서는 멱등**하다. Windows는 자기 프로세스가 만든 mutex에도 `ERROR_ALREADY_EXISTS`를 돌려주므로 이 방어가 없으면 `main()`을 두 번 부르는 테스트가 자기 자신을 중복으로 오판한다.
- 이미 실행 중이면:
  - 항상 ASCII 한 줄을 stderr로: `[!] Another Phone Pad server instance is already running - exiting (code 2)`
  - windowed면 추가로 `MessageBoxW`("Phone Pad 서버가 이미 실행 중입니다…"). MessageBox 문구만 한글 — 와이드 문자 API라 cp949 문제가 없다. 로그 라인은 ASCII 규칙 준수.
  - 종료 코드 **2** (`EXIT_ALREADY_RUNNING`).
- 비Windows / `ctypes.windll` 부재 / `CreateMutexW` 실패 → **가드를 건너뛰고 서버를 그대로 띄운다**. 가드가 서버를 못 뜨게 만드는 실패 방향은 피했다.
- `--allow-multiple`이면 mutex를 만들지도 않는다.
- `message_box` / `mutex_factory` / `log` 주입 가능 — 테스트에서 실제 창이 뜨지 않는다.

### B. windowed 파일 로깅 (`logging_setup.py`)
- `sys.stdout is None or sys.stderr is None`일 때만 동작. 콘솔 실행은 **로그 파일을 만들지도 않는다**.
- `%LOCALAPPDATA%\PhonePad\server.log`, UTF-8, `buffering=1`(줄 단위 flush), 디렉터리 자동 생성.
- 시작 시 1MB 초과면 `server.log.1`로 `os.replace` 1회 회전(세대 1개).
- 열기 실패 → 조용히 `os.devnull` 폴백, devnull조차 실패하면 리다이렉트 없이 진행. **어느 쪽이든 서버는 뜬다.**
- 기존 `print()` 호출은 **한 줄도 고치지 않았다** (스트림 자체를 바꾸는 방식).
- 배너 포함 모든 로그가 ASCII.

### C. 패키징
- `phone_pad_server.spec`: onefile, `console=False`, hidden-import `pystray._win32`(pystray가 백엔드를 `importlib`로 고름) / `PIL.Image` / `PIL.ImageDraw`. excludes로 `tkinter`, `PIL.ImageTk/ImageQt/ImageShow`, numpy/scipy/pandas/matplotlib, Qt 바인딩, pytest 등 제거.
- `build_exe.ps1`: venv 경로가 저장소 안이면 `throw`로 막는다. `pip install -r requirements.txt pyinstaller>=6.0` — **requirements.txt에 pyinstaller를 넣지 않았다**(빌드 전용이라 런타임 의존성 목록을 오염시키지 않음).

---

## 3. 실제 빌드·검증 결과 (스펙 D)

환경: Windows 11 Pro 26200, Python 3.13.1, PyInstaller 6.22.3, pystray 0.19.5, Pillow 12.3.0.

| 항목 | 결과 |
|------|------|
| **빌드** | 성공. `dist/PhonePadServer.exe` **15.6 MB**. venv 생성+pip install+빌드 **전체 37.1초**, PyInstaller 단계만 **약 11초** |
| **스모크 (D.2)** | TCP 9000 첫 줄 = `{"type": "SESSION", "session": "41a6e3ddee134e6cbc79756cfd71686f"}` (32 hex) ✅ / UDP 9001은 `SO_EXCLUSIVEADDRUSE` 프로브 bind가 **WSAEADDRINUSE(10048)** 로 실패 = 실제 점유 확인 ✅ / 세션 토큰을 실은 MOVE 수신 OK |
| **중복 실행 — 콘솔 경로 (D.3)** | 표준 스트림을 물려준 실행: **종료 코드 2**, stderr에 `[!] Another Phone Pad server instance is already running - exiting (code 2)` 한 줄, stdout 비어 있음 |
| **중복 실행 — windowed 경로 (D.3)** | `Start-Process`(핸들 미상속) 실행: 0.7초 만에 `#32770` 클래스 / 캡션 `Phone Pad` 다이얼로그 생성 확인, WM_CLOSE 후 **종료 코드 2**, 로그 파일에 배너 + 중복 경고 기록 |
| **첫 인스턴스 무영향 (D.3)** | 중복 시도 3회 후에도 첫 인스턴스가 계속 서비스 — 새 세션 `ade635fe…` 정상 발급, 포트 유지 |
| **windowed 로그 (D.4)** | `%LOCALAPPDATA%\PhonePad\server.log`에 실시간 기록 확인. 3개 프로세스가 같은 파일에 append 했는데 줄 깨짐 없음 |
| **exe 안에서 pystray 동작** | 첫 인스턴스가 `phone_pad1571823007600SystemTrayIcon` 클래스의 트레이 윈도를 2개 생성 = pystray win32 백엔드가 패키징된 exe 안에서 정상 로드됨. 로그에 `pystray/Pillow not available` 폴백 경고 **없음** (hidden-import가 맞았다는 증거) |
| **venv 실 pystray 테스트** | AGENTS.md 규칙대로 미설치 환경만으로 끝내지 않고, pystray/Pillow가 **실제로 설치된** venv에서도 `227 passed, 1 skipped` 확인 |
| **정리** | 서버 프로세스 전부 종료, 포트 9000/9001 해제 확인, `_MEI*` 임시 디렉터리 삭제, `build/`·`dist/`·venv 삭제. 저장소에 잔여물 없음 |

### 발견한 함정
1. **PyInstaller onefile은 프로세스가 2개다.** 부트로더(`runw.exe`)가 자기 자신을 자식으로 다시 띄우고, Python 코드는 자식에서만 돈다(로그의 pid ≠ `Start-Process`가 돌려준 pid). 종료 코드는 부트로더가 그대로 전파한다. mutex는 자식만 만들므로 가드 판정에는 영향 없지만, **프로세스를 죽일 때 둘 다 잡아야 한다.**
2. **하드 킬 시 `%TEMP%\_MEI*`(약 15MB)가 남는다.** `taskkill /F`/`Stop-Process -Force`면 부트로더가 정리 기회를 못 얻는다. AGENTS.md 섹션 10의 "하드 킬 시 드래그 상태"와 같은 뿌리 — 정상 종료(트레이 "종료")로는 정리된다.
3. **windowed 판정은 부모가 결정한다.** `subprocess.Popen([exe])`처럼 부모의 콘솔 핸들을 물려주면 GUI 서브시스템 exe여도 `sys.stdout`이 살아 있어 **콘솔 모드로 동작한다**(검증 중 실제로 한 번 헛돌았다). `Start-Process`(ShellExecute)는 핸들을 안 물려줘 진짜 windowed가 된다. 실사용(더블클릭)은 후자라 문제 없고, 오히려 배치 스크립트에서 exe를 돌리면 로그가 콘솔로 나오는 유용한 성질이다.
4. **가드를 실제 이름으로 두면 pytest가 서버 실행 여부에 따라 깨진다.** 트레이에 서버가 떠 있는 상태로 `python -m pytest`를 돌리면 `server.main()`을 호출하는 기존 테스트 4건이 종료 코드 2를 받아 실패한다. `tests/conftest.py`의 autouse fixture로 **이름만** pytest 프로세스 전용으로 바꿔 해결했다 — 가드를 모킹해 죽이지 않으므로 실제 `CreateMutexW` 경로는 그대로 실행된다. **실제로 서버를 띄워 둔 채 pytest를 돌려 227 passed를 확인했다.**
5. `ctypes.windll.kernel32`(use_last_error 미설정)로는 `GetLastError`가 신뢰할 수 없다 → `ctypes.WinDLL("kernel32", use_last_error=True)` 사용.

### 미검증 (이 환경에서 불가)
- **폰 실기기 연동** — exe로 띄운 서버에 실제 Android 앱을 붙여 MOVE/CLICK/스크롤/드래그가 도는지. 프로토콜·`InputController`·`ServerRuntime` 무변경이라 회귀는 없어야 한다.
- **Windows 방화벽 프롬프트** — exe로 처음 실행할 때 `python.exe` 대신 `PhonePadServer.exe` 이름으로 새 방화벽 규칙을 물어볼 가능성이 높다. 기존에 python.exe로 허용해 둔 규칙은 적용되지 않는다.
- **exe에서 트레이 메뉴 "종료"** — 우클릭 팝업 메뉴 선택을 스크립트로 재현할 수 없어 수행하지 못했다. pystray가 exe 안에서 로드되고 트레이 윈도를 만드는 것까지는 확인했고, 종료 경로 자체는 단위 테스트 + 이전 세션의 실 venv 검증으로 커버된다(패키징이 이 코드를 바꾸지 않는다).
- 아이콘 시각 품질, 다이얼로그의 한글 폰트/줄바꿈 렌더링(창이 뜨고 캡션이 맞는 것만 확인).

---

## 4. 리더가 AGENTS.md에 반영할 내용

### 섹션 2 (저장소 구조 트리)
`pc_server/` 밑에 4개 추가:
```
├── single_instance.py       # 중복 실행 방지 (Windows named mutex)
├── logging_setup.py         # --noconsole 실행 시 print() -> 로그 파일
├── phone_pad_server.spec    # PyInstaller 빌드 정의 (onefile + windowed)
└── build_exe.ps1            # 임시 venv 생성 -> 빌드 스크립트
```
`tests/` 밑에 `test_single_instance.py`, `test_logging_setup.py` 추가.

### 섹션 3 (기술 스택)
빌드 도구 행 추가: `PyInstaller 6.22.3 (빌드 전용, requirements.txt 아님 — build_exe.ps1이 임시 venv에 설치)`.

### 섹션 6 Phase 4 체크박스
- `- [ ] PyInstaller로 단일 exe 패키징` → **`- [x]`** 로 바꾸고 설명 추가:
  > onefile + windowed(`--noconsole`), `build_exe.ps1`이 **저장소 밖 임시 venv**에서 빌드(전역 Python 불변 — pytest 기준선이 pystray 미설치 상태라서). 결과 `dist/PhonePadServer.exe` 15.6MB. 함께 해결: 서버 중복 실행(named mutex, 종료 코드 2)과 windowed 로그 소실(`%LOCALAPPDATA%\PhonePad\server.log`).
- Phase 4 헤더 상태를 갱신(남은 항목은 UDP 자동 탐색 + 예외 처리 강화). **주의: UDP 자동 탐색은 다른 세션이 `discovery` worktree에서 작업 중이라 체크박스가 충돌할 수 있다.**
- "Phase 4 구현 시 핵심 파일/설계"에 추가할 항목:
  - `pc_server/single_instance.py` — `Local\PhonePadServer` named mutex. **소켓 옵션은 무변경**(`SO_REUSEADDR` 유지). 핸들을 모듈 전역에 붙들어 둠(GC되면 mutex가 풀린다), 같은 프로세스에서는 멱등, 비Windows면 건너뜀, `--allow-multiple`로 해제. 중복이면 ASCII 한 줄 + (windowed일 때만) `MessageBoxW` 후 종료 코드 2
  - `pc_server/logging_setup.py` — `sys.stdout`/`sys.stderr`가 `None`일 때만 로그 파일로 교체. 코드 전반의 `print()`는 한 줄도 안 고쳤다. 줄 단위 flush, 1MB 1세대 회전, 열기 실패 시 devnull 폴백
  - `pc_server/tests/conftest.py` — mutex 이름을 pytest 프로세스 전용으로 격리(가드를 모킹하지 않음). 이게 없으면 **서버가 떠 있을 때 pytest가 깨진다**

### 섹션 8 (실행 방법) — PC 서버 항목에 추가
```powershell
# 단일 exe 빌드 (임시 venv를 저장소 밖에 만든다. 전역 Python은 건드리지 않음)
cd pc_server
./build_exe.ps1            # -> dist/PhonePadServer.exe (약 15.6MB, 빌드 ~40초)
```
설명 문단:
> exe는 windowed(`--noconsole`)라 콘솔 창이 뜨지 않는다. 그래서 `print()` 로그는 **`%LOCALAPPDATA%\PhonePad\server.log`** 로 간다(줄 단위 flush, 1MB 넘으면 `server.log.1`로 1회 회전). 트레이 아이콘·종료·연결 상태 표시는 그대로 동작한다.
> **서버를 두 번 띄우면** 두 번째 프로세스가 named mutex로 감지해 "이미 실행 중입니다" 안내창을 띄우고 종료 코드 2로 끝난다 — 첫 인스턴스는 영향받지 않는다. 개발 중 일부러 두 개를 띄우려면 `--allow-multiple`.
> **방화벽:** exe로 처음 실행하면 `python.exe`로 허용해 둔 기존 규칙이 적용되지 않아 `PhonePadServer.exe`에 대한 프롬프트가 새로 뜰 수 있다(TCP 9000 + UDP 9001 인바운드 허용).

섹션 8의 기존 경고 문장 **"같은 PC에서 서버를 두 번 실행하지 말 것"** 은 이제 사실이 아니다 — "두 번째 실행은 자동으로 차단된다"로 교체.

### 섹션 10 (미결 사항) — 두 행 해소
- **"서버 중복 실행 (Windows `SO_REUSEADDR`)"** → 해소로 정리. 대체 문구 제안:
  > **해소(Phase 4 패키징).** `SO_REUSEADDR`가 Windows에서 점유된 포트에도 bind를 성공시키는 동작 **자체는 그대로 두고**(재시작 시 TIME_WAIT 재바인드 유지), 프로세스 단위 named mutex(`Local\PhonePadServer`)로 막는다. 두 번째 인스턴스는 안내 후 종료 코드 2. 빌드된 exe를 두 번 실행해 실측. **남은 경계:** 다른 로그인 세션(`Local\` 네임스페이스 밖)에서 띄운 서버는 감지하지 못하며 포트 경합은 그대로다.
  > 참고: 서버 사망 경로 테스트가 포트 충돌 대신 `203.0.113.1` bind로 `OSError [WinError 10049]`를 만드는 이유는 그대로 유효하다(소켓 동작을 바꾸지 않았으므로).
- **"PyInstaller `--noconsole` 시 로그 소실"** → 해소로 정리:
  > **해소(Phase 4 패키징).** `logging_setup.configure_stdio()`가 `sys.stdout`/`sys.stderr`가 `None`일 때만 `%LOCALAPPDATA%\PhonePad\server.log`로 교체한다. 기존 `print()` 호출은 그대로 두고 스트림만 바꿨다. 콘솔 실행은 완전 무변경.
- **"PC 서버 배포" 행** (`PyInstaller — Phase 4에서`) → `PyInstaller onefile+windowed 완료 (build_exe.ps1). 코드 서명·설치 관리자·자동 시작 등록은 범위 밖`.

### 섹션 10에 **새로 추가**할 행
| 항목 | 내용 |
|------|------|
| exe 하드 킬 시 `_MEI` 잔여 | PyInstaller onefile은 부트로더가 `%TEMP%\_MEI<랜덤>`에 15MB 가량을 풀고 자식 프로세스로 Python을 돌린다. `taskkill /F`로 죽이면 이 디렉터리가 남는다(정상 종료는 정리됨). 섹션 10 "서버 프로세스 강제 종료 시 드래그 상태"와 같은 뿌리 |
| exe의 windowed 판정은 부모가 결정 | GUI 서브시스템 exe여도 부모가 콘솔 핸들을 물려주면(`subprocess.Popen` 등) `sys.stdout`이 살아 있어 로그가 파일이 아니라 부모 콘솔로 간다. 더블클릭/`Start-Process`는 핸들을 안 물려주므로 파일 로깅이 동작한다. 배치 스크립트로 exe를 돌릴 때 로그가 "안 남는" 게 아니라 콘솔로 가는 것 |
| exe 방화벽 규칙 재등록 | 실행 파일 이름이 `python.exe` → `PhonePadServer.exe`로 바뀌어 기존 허용 규칙이 적용되지 않는다. 미검증 |

### 섹션 9 (코딩 컨벤션) — 선택
> 서버에 **실행 진입점 동작**(가드·로깅 설정 등)을 추가할 때는 `server.py`가 아니라 **새 모듈**로 만들고 `main()`에서 몇 줄로 호출한다. `ServerRuntime`/`handle_client`는 라이브러리처럼 import되어 테스트되므로, 진입점 전용 동작이 그 안으로 새면 테스트가 실행 환경에 끌려다닌다.

---

## 5. 남은 이슈 / 후속 권장
1. **트레이 "종료"로 exe를 끄는 경로가 미검증** (자동화 불가). 다음에 사람이 손으로 한 번 확인 권장 — 확인되면 `_MEI` 정리도 함께 검증된다.
2. **다른 로그인 세션의 중복 실행은 막지 못한다.** `Local\` 네임스페이스를 쓴 의도된 선택(스펙 A)이지만, 그 경우 포트 9000/9001 경합은 여전히 `SO_REUSEADDR` 때문에 조용히 일어난다. 필요해지면 `Global\` + 권한 처리로 확장.
3. **로그 회전이 시작 시 1회뿐**이라 한 프로세스를 아주 오래 켜 두면 `server.log`가 1MB를 넘어 계속 자란다. 현재 로그량(연결/해제 몇 줄)에서는 문제 없어 과설계를 피했다.
4. `requirements.txt`는 손대지 않았다(pyinstaller는 빌드 전용). 필요하면 별도 `requirements-build.txt`로 분리 가능.
5. **커밋은 `feat(server):` 하나** (AGENTS.md 섹션 9). `AGENTS.md`/`CLAUDE.md`는 건드리지 않았으므로 `docs(harness):`는 리더 몫. `pc_server/build/`·`dist/`는 `.gitignore` 처리했고 실제로도 삭제해 두었다.
