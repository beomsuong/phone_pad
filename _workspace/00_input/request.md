# 요청: PyInstaller 단일 exe 패키징 (AGENTS.md Phase 4) + 서버 중복 실행 방지 + --noconsole 로그 보존

**범위 판단:** 단일 사이드 (서버만). 와이어 프로토콜 변경 없음. → server-dev 1명, protocol-qa 생략.
**주의(병렬 작업):** 다른 세션이 `discovery` worktree에서 UDP 자동 탐색(서버 응답기 추가)을 작업 중이라 `server.py`를 건드릴 수 있다. **`server.py` 변경은 최소·국소적으로**(진입부 `main()` 근처 몇 줄) 유지하고, 새 로직은 가능한 한 **새 모듈**로 분리해 병합 충돌을 줄일 것.

## 배경 (AGENTS.md 섹션 10에 이 세 가지가 "PyInstaller 항목과 함께 다룰 것"으로 남아 있다)
1. **서버 중복 실행:** 기존 코드가 리슨 소켓에 `SO_REUSEADDR`를 쓰는데 Windows에서는 이미 점유된 포트에도 bind가 성공해, 서버를 두 번 띄우면 트레이 아이콘이 2개 뜨고 한쪽만 트래픽을 받는다. **소켓 동작(재시작 시 TIME_WAIT 재바인드 등)은 바꾸지 말 것** — 대신 프로세스 단위 단일 인스턴스 보장(Windows named mutex, `ctypes` `CreateMutexW` + `GetLastError() == ERROR_ALREADY_EXISTS`)으로 해결한다.
2. **`--noconsole` 로그 소실:** windowed exe에서는 `sys.stdout is None`이라 `print()`가 조용히 no-op이 되어 트레이 폴백 경고·서버 사망 로그가 아무 데도 안 보인다. → 파일 로깅으로 대체.
3. **패키징 자체:** 단일 exe.

## 확정 스펙

### A. 단일 인스턴스 가드 (신규 모듈, 예: `single_instance.py`)
- named mutex 이름은 고정 상수 (예: `Local\\PhonePadServer`). 세션 로컬(`Local\\`)로 — 다른 로그인 세션의 서버와 충돌하지 않게.
- 이미 실행 중이면: **새 프로세스는 조용히 죽지 말고** 사용자가 볼 수 있게 알린다 — 콘솔 모드면 stderr에 한 줄 + 종료 코드 2, windowed(콘솔 없음)면 로그 파일에 기록 + `MessageBoxW`로 "Phone Pad 서버가 이미 실행 중입니다" 안내 후 종료 코드 2. (MessageBox는 windowed일 때만; 테스트에서 실제 창이 뜨면 안 되므로 주입 가능하게)
- Windows가 아닌 환경/`ctypes.windll` 부재 시에는 가드를 건너뛴다(테스트·리눅스 CI가 깨지지 않게).
- 핸들은 프로세스 수명 동안 유지(가비지 컬렉션되어 mutex가 풀리면 안 됨).
- **가드는 `main()`(실행 진입)에서만** 적용. `ServerRuntime`/`handle_client` 등 기존 로직과 테스트는 영향받으면 안 된다. 옵션 `--allow-multiple`(개발/디버깅용)로 끌 수 있게.

### B. windowed 실행 시 파일 로깅 (신규 모듈, 예: `logging_setup.py`)
- `sys.stdout`/`sys.stderr`가 `None`이면(=`--noconsole` exe) 로그 파일을 열어 `sys.stdout`/`sys.stderr`에 대입해서 **기존 `print()` 호출이 그대로 파일로 가게** 한다(코드 전반의 `print` 호출을 logging으로 갈아엎지 말 것 — 범위 밖).
- 위치: `%LOCALAPPDATA%\PhonePad\server.log` (디렉터리 없으면 생성). UTF-8, 줄 단위 flush(프로세스가 죽어도 마지막 줄까지 남게), 무한 성장 방지(예: 시작 시 1MB 초과면 `server.log.1`로 회전하는 정도의 단순한 처리면 충분 — 과설계 금지).
- 콘솔이 있는 일반 실행에서는 아무것도 바꾸지 않는다.
- 로그 파일 경로를 열 수 없는 경우(권한 등)에도 서버가 죽으면 안 된다 — 조용히 `os.devnull`로 폴백.
- **로그 메시지는 ASCII만** (AGENTS.md 섹션 9 — cp949 콘솔 인코딩 문제 전례).

### C. 빌드 산출물 (PyInstaller)
- `pc_server/phone_pad_server.spec` (또는 동등한 재현 가능한 빌드 정의) + `pc_server/build_exe.ps1` 스크립트. 스크립트는 **저장소 밖/임시 venv를 만들어** 의존성(`pystray`, `Pillow`, `pyinstaller`)을 설치하고 빌드한 뒤 결과를 `pc_server/dist/PhonePadServer.exe`로 남긴다.
- **전역 Python을 절대 변경하지 말 것** (`pip install`을 전역에 하지 않는다. 이 저장소의 pytest 기준선은 pystray/Pillow 미설치 상태다 — 기준선: 서버 `184 passed, 1 skipped`). 검증용 venv는 저장소 밖(스크래치패드 등)에 만들고 작업 후에도 저장소에 남기지 말 것.
- 옵션: `--onefile`, windowed(`--noconsole`), exe 이름 `PhonePadServer`. pystray의 Windows 백엔드(`pystray._win32`)와 Pillow가 정적 분석으로 안 잡히면 `hidden-import`로 명시. 불필요한 대형 모듈은 제외(exe 크기 과도하게 커지지 않게).
- `pc_server/.gitignore`에 `build/`, `dist/` 추가 (`.spec`과 `build_exe.ps1`은 추적).
- 버전 정보/아이콘 파일 같은 장식은 범위 밖.

### D. 실제 검증 (필수 — 단위 테스트만으로 "됐다"고 하지 말 것)
1. venv에서 실제로 exe를 빌드한다.
2. 빌드된 exe를 실행해 **스모크 테스트**: TCP 9000에 접속하면 `{"type": "SESSION", ...}` 첫 줄이 오는지, UDP 9001이 바인드되는지 확인. 확인 후 프로세스를 종료(트레이 종료 경로가 아니라 스크립트에서 종료해도 되지만, 남은 프로세스가 없도록 반드시 정리).
3. **같은 exe를 두 번째로 실행**해서 중복 실행 가드가 실제로 동작하는지(종료 코드 2, 로그 파일에 기록, 첫 번째 인스턴스는 영향 없음) 확인.
4. windowed exe가 로그 파일(`%LOCALAPPDATA%\PhonePad\server.log`)에 실제로 기록하는지 확인.
5. 이 단계에서 확인한 사실(exe 크기, 빌드 시간, 발견한 함정)을 요약에 기록.
- 실기기(폰) 연동, Windows 방화벽 프롬프트 동작은 이 환경에서 검증 불가 — "미검증"으로 명시할 것.
- 트레이 어댑터를 건드리게 되면 AGENTS.md에 이미 기록된 규칙대로 **미설치 환경 테스트만으로 끝내지 말고 venv에서 실제 pystray로도 확인**할 것.

## 테스트 (pytest, 미설치 기본 환경에서 통과해야 함)
- 단일 인스턴스 가드: mutex 생성/조회 함수를 주입 가능하게 만들어 (1) 첫 실행 통과 (2) 이미 존재하면 코드 2 종료 + 안내 호출 (3) 비Windows/`windll` 없음이면 건너뜀 (4) `--allow-multiple`이면 건너뜀. 실제 mutex를 만드는 통합 테스트가 있다면 테스트마다 고유 이름을 써서 다른 테스트/실행 중인 실제 서버와 충돌하지 않게 하고 반드시 정리.
- 로그 설정: `sys.stdout is None`일 때만 파일 리다이렉트, 임시 디렉터리 사용, 회전, 열기 실패 폴백. (테스트가 `sys.stdout`을 바꾸면 반드시 원복)
- 기존 테스트 전부 회귀 없이 통과 (`184 passed, 1 skipped` 이상).

## 문서
`AGENTS.md`(섹션 2 트리·섹션 3 패키징 행·섹션 6 Phase 4 체크박스·섹션 8 실행/빌드 방법·섹션 10의 해소된 두 항목 정리)는 **리더가 갱신**한다 — 수정하지 말고, 무엇을 어떻게 바꿔야 하는지 요약 파일에 적어둘 것.

## 참고
- `pc_server/tray.py`, `tray_status.py`, `server.py`(`main()`, `parse_args()`, `run_with_tray()`), `pc_server/requirements.txt`
- AGENTS.md 섹션 8(실행 방법), 섹션 10("서버 중복 실행", "PyInstaller `--noconsole` 시 로그 소실")
