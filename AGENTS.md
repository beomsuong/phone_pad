# Phone Pad — Agent Harness

AI 코딩 에이전트가 이 프로젝트를 이어받아 작업할 수 있도록 작성된 컨텍스트 문서입니다.
이 파일 하나만 읽어도 현재 상태와 다음 할 일을 파악할 수 있도록 유지하세요.

> **Claude Code 사용 시:** Android↔서버 양쪽에 걸친 기능 작업은 `phone-pad-orchestrator` 스킬(`.claude/skills/`)이 android-dev/server-dev/protocol-qa 3인 에이전트 팀으로 처리합니다. 하네스 트리거 규칙과 변경 이력은 `CLAUDE.md` 참조.

---

## 1. 프로젝트 한 줄 요약

Android 폰 터치스크린 → 같은 WiFi의 Windows PC 마우스 커서 제어.
애플 매직 트랙패드처럼 멀티터치 제스처까지 지원하는 것이 최종 목표.

---

## 2. 저장소 구조

```
phone_pad/
├── AGENTS.md                  ← 이 파일 (에이전트 컨텍스트)
├── phone_pad_app/             ← Android 앱 (Kotlin + Jetpack Compose)
│   └── app/src/main/java/com/example/phone_pad_app/
│       ├── PhonePadApplication.kt
│       ├── MainActivity.kt
│       ├── domain/
│       │   ├── model/         TrackpadEvent.kt, ConnectionState.kt, GestureSettings.kt,
│       │   │                   ReconnectPolicy.kt
│       │   ├── repository/    TrackpadRepository.kt, SettingsRepository.kt (interface)
│       │   └── usecase/       SendEventUseCase.kt
│       ├── data/
│       │   ├── network/       TcpClient.kt, UdpClient.kt, SessionHandshake.kt
│       │   └── repository/    TrackpadRepositoryImpl.kt, DataStoreSettingsRepository.kt
│       ├── di/                AppModule.kt, DispatcherModule.kt, DataStoreModule.kt,
│       │                       ReconnectModule.kt
│       └── presentation/
│           ├── util/          GestureConfig.kt
│           ├── settings/      SettingsScreen.kt, SettingsViewModel.kt, SettingsUiState.kt,
│           │                   ScrollSpeedSlider.kt
│           └── trackpad/      TrackpadScreen.kt, TrackpadViewModel.kt, TrackpadUiState.kt,
│                               MultiTouchGestureTracker.kt, DoubleTapDetector.kt,
│                               DragHoldDetector.kt
└── pc_server/                 ← Windows Python 서버
    ├── server.py              소켓/세션/heartbeat + 정지 가능한 ServerRuntime, 콘솔/트레이 실행 모드
    ├── input_controller.py
    ├── tray_status.py         트레이 순수 로직 (연결 수→상태/툴팁, LAN IP 조회) — pystray 비의존
    ├── tray.py                pystray 어댑터 (pystray/Pillow import는 여기에서만, 선택 의존성)
    ├── requirements.txt       pystray, Pillow (트레이 전용 — 없어도 서버는 콘솔 모드로 동작)
    ├── single_instance.py     중복 실행 방지 (Windows named mutex `Local\PhonePadServer`)
    ├── logging_setup.py       --noconsole 실행 시 print() → 로그 파일
    ├── phone_pad_server.spec  PyInstaller 빌드 정의 (onefile + windowed)
    ├── build_exe.ps1          저장소 밖 임시 venv 생성 → exe 빌드 스크립트
    └── tests/                 pytest 단위 테스트 (test_single_instance.py, test_logging_setup.py 포함)
```

---

## 3. 기술 스택 & 버전

### Android
| 항목 | 값 |
|------|----|
| 언어 | Kotlin 1.8.10 |
| 빌드 | AGP 8.1.3, compileSdk/targetSdk 34, **minSdk 26** |
| UI | Jetpack Compose BOM 2023.03.00 (Compose 1.4.0) |
| Compose Compiler | 1.4.3 |
| 아키텍처 | Clean Architecture + MVVM |
| DI | Hilt 2.48 (kapt) |
| 비동기 | Coroutines 1.7.1 + Flow |
| ViewModel | lifecycle-viewmodel-compose 2.6.1 |

### Windows Server
| 항목 | 값 |
|------|----|
| 언어 | Python 3.x |
| 네트워크 | 표준 `socket` 모듈 |
| 커서 제어 | `ctypes.windll.user32.SendInput` |
| 패키징 | PyInstaller 6.x onefile + windowed (빌드 전용 — `requirements.txt`가 아니라 `build_exe.ps1`이 임시 venv에 설치) |

---

## 4. 통신 프로토콜

### 원칙 (변경 금지)
- **MOVE 이벤트만 UDP**, 나머지(클릭·스크롤·드래그·heartbeat 등) **전부 TCP**
- "이동 좌표인가?" 한 가지 기준으로 채널 결정. 애매하면 TCP.

### 현재 구현 (하이브리드 — Phase 2·3 완료)

**TCP 9000** — newline-delimited JSON (한 줄 = 한 이벤트, `\n` 종료)

세션 핸드셰이크: 클라이언트가 TCP 연결하면 서버가 다른 어떤 이벤트보다도 먼저 세션 토큰 한 줄을 보낸다.
```jsonc
// 서버 → 클라이언트, 연결 직후 1회, 반드시 첫 줄
{"type":"SESSION","session":"0123456789abcdef0123456789abcdef"}  // uuid4().hex, 32자리 hex
```
클라이언트는 이 줄을 받아야 `ConnectionState.Connected`로 전환한다 (`SESSION_HANDSHAKE_TIMEOUT_MS` 내 미수신 시 `Error`). 핸드셰이크 직후부터 서버는 해당 소켓에 `HEARTBEAT_INTERVAL_S` 초 `recv` 타임아웃을 걸고, **연속 `HEARTBEAT_MISS_LIMIT`(3)회 동안 상향 데이터가 전혀 없으면(≈15초) 세션을 회수하고 연결을 끊는다.** 여기서 "데이터"는 HEARTBEAT뿐 아니라 CLICK 등 어떤 상향 이벤트든 해당되며, 매번 카운터가 0으로 리셋된다.

```jsonc
// 좌클릭
{"type":"CLICK","button":"left"}

// 우클릭 — ✅ 구현됨. 새 이벤트 타입이 아니라 기존 CLICK을 button="right"로 재사용.
// 서버 input_controller.py의 _click(button)이 Phase 1부터 "left"가 아니면 전부
// 우클릭으로 처리하고 있어서 서버 변경은 없었다.
{"type":"CLICK","button":"right"}

// 더블클릭 — ✅ 구현됨 (Phase 3). 1손가락 탭이 끝나도 즉시 CLICK을 보내지 않고
// DOUBLE_TAP_INTERVAL_MS(300ms)만큼 기다렸다가, 그 안에 같은 자리(DOUBLE_TAP_DISTANCE_PX
// 이내)에서 두 번째 탭이 오면 CLICK 두 개 대신 DOUBLE_CLICK 하나만 보낸다. 서버는 커서를
// 전혀 움직이지 않고 down-up-down-up 4개를 SendInput 1회로 원자적으로 보낸다(Windows
// 네이티브 더블클릭 판정 사각형이 아주 좁아서, 두 CLICK을 따로 보내면 그 사이 커서가
// 미세하게 움직여 더블클릭으로 인식되지 않을 수 있기 때문). **트레이드오프**: 이 방식은
// 모든 1손가락 CLICK에 300ms 지연을 준다 — 더블클릭을 지원하는 구조에서 피할 수 없다.
{"type":"DOUBLE_CLICK","button":"left"}

// 스크롤 — ✅ 구현됨. dx/dy는 픽셀이 아니라 정수 휠 스텝(노치). px→스텝 변환과 잔차
// 누적은 Android(MultiTouchGestureTracker)가 끝낸 뒤 보낸다. 서버는 steps×WHEEL_DELTA(120)만
// 곱한다. 부호: dy 양수 = 손가락이 아래로, dx 양수 = 오른쪽. session 필드 없음.
{"type":"SCROLL","dx":0,"dy":-3}

// 탭홀드 드래그 — ✅ 구현됨 (Phase 3). 필드 없음, session 없음. 1손가락으로 제자리
// (TAP_MAX_DISTANCE_PX 이내)를 DRAG_HOLD_THRESHOLD_MS(=TAP_MAX_DURATION_MS, 200ms) 이상
// 버티면 DRAG_START(마우스 왼쪽 버튼을 누른 채 유지) 전송. 이후 이동은 새 이벤트가 아니라
// 기존 MOVE(UDP)를 그대로 쓴다 — 버튼이 눌린 채로 커서만 움직이면 자연히 드래그가 된다.
// 손을 떼면 DRAG_END(버튼 뗌). **서버 안전장치**: DRAG_END가 유실되면 PC 마우스 버튼이
// 영원히 눌린 채로 멈추므로, TCP 연결이 어떤 이유로든(정상 종료/heartbeat 타임아웃/예외)
// 끊기면 서버가 드래그 활성 여부를 확인해 강제로 버튼을 놓는다. 양쪽 다 멱등(중복
// DRAG_START/DRAG_END 무해).
{"type":"DRAG_START"}
{"type":"DRAG_END"}

// heartbeat — ✅ 구현됨. 클라이언트 → 서버, HEARTBEAT_INTERVAL_MS(5000ms)마다.
// 연결 유지 신호일 뿐 마우스 명령이 아니므로 InputController.handle_event로
// 넘기지 않는다. 서버는 항상 클라이언트 주도로만 반응한다(서버가 먼저 보내지 않음).
{"type":"HEARTBEAT"}

// heartbeat ack — ✅ 구현됨. 서버 → 클라이언트, HEARTBEAT 수신 즉시 응답.
// 클라이언트는 내용을 파싱하지 않고 "아무 줄이나 수신"으로만 취급해 미응답
// 카운터를 리셋한다 — 서버가 클라이언트에 보내는 유일한 하향 트래픽이므로
// 사실상 이 ACK가 클라이언트 쪽 리셋의 유일한 수단이다(서버 쪽은 CLICK 등
// 어떤 상향 데이터로도 리셋되어 비대칭).
{"type":"HEARTBEAT_ACK"}
```

**UDP 9001** — MOVE 전용. 패킷 하나 = 이벤트 하나, 개행 없음
```json
{"session":"0123456789abcdef0123456789abcdef","type":"MOVE","dx":2.5,"dy":-1.0}
```
서버는 `session`이 TCP로 발급된 활성 목록에 없으면(문자열이 아니거나, 미등록이거나, 연결이 이미 끊겼으면) 조용히 무시한다 — 크래시하지 않는다.

---

## 5. 제스처 설계

제스처 해석은 **Android 앱에서** 수행. 서버로는 "해석된 이벤트"만 전송.

| 제스처 | 이벤트 | 단계 | 구현 상태 |
|--------|--------|------|-----------|
| 1손가락 드래그 | MOVE(dx, dy) | Phase 1 | ✅ 완료 |
| 1손가락 탭 | CLICK(left) | Phase 1 | ✅ 완료 |
| 1손가락 더블탭 | DOUBLE_CLICK | Phase 3 | ✅ 완료 |
| 2손가락 탭 | CLICK(right) | Phase 2 | ✅ 완료 |
| 2손가락 상하좌우 드래그 | SCROLL(dx, dy) | Phase 2 | ✅ 완료 |
| 탭홀드 + 드래그 | DRAG_START → MOVE → DRAG_END | Phase 3 | ✅ 완료 |
| 3손가락 스와이프 | 가상 데스크톱 전환 등 | Phase 4 | ⬜ 미구현 |

### 엣지 케이스 (구현 시 주의)
- 드래그 도중 손가락 개수 변화(1→2) → 현재 제스처 취소 후 새 제스처로 재시작 (`MultiTouchGestureTracker`가 구간 단위로 구현)
- **탭홀드 드래그는 손가락 개수가 바뀌면 재무장하지 않는다**: 드래그 홀드 중 두 번째 손가락이 닿으면 즉시 `DRAG_END`로 종료하고, 이후 손가락이 다시 1개로 줄어도 새로 드래그 홀드를 시작하지 않는다. 재무장을 허용하면 2손가락 스크롤 후 손가락을 어긋나게 떼는 `2→1→0` 꼬리(위 항목)의 그 1손가락이 "제자리 유지"로 오인되어 스크롤 직후 드래그가 오발동한다 — 대가로 "2손가락에서 1손가락으로 줄인 뒤 홀드 드래그 시작"은 지원하지 않는다(범위 밖)
- 탭홀드 드래그 중 화면 밖으로 나가거나 제스처가 취소되는 경우 → `DRAG_END` 전송 (Compose `awaitEachGesture`의 종료 경로 전체에서 멱등하게 호출됨)
- 두 손가락을 `DRAG_HOLD_THRESHOLD_MS` 이상 벌려서 내려놓으면(2손가락 탭 판정 직전에 각 손가락이 잠깐 1손가락처럼 보이는 구간에서 승격 조건을 스치듯 만족) 우클릭 직전에 좌클릭이 하나 샐 수 있다 — 스펙상 논리적 귀결이며 영향은 낮음
- 2손가락 탭 종료 시 "동시에 손가락 떼기"는 물리적으로 불가능해서 실제로는 `2→1→0` 순으로 이벤트가 들어옴 → 마지막 구간(짧은 1손가락 꼬리)만 보면 우클릭이 좌클릭으로 뒤집힌다. `MULTI_TOUCH_RELEASE_GRACE_MS`(50ms) 안에 끝난 "개수 감소로 시작된" 짧은 꼬리는 무시하고 직전(더 많은 손가락) 구간 기준으로 판정한다 — 이 값은 반드시 `TAP_MAX_DURATION_MS`보다 충분히 작아야 함(안 그러면 "손가락 하나 떼고 남은 손가락으로 탭"하는 정상 동작까지 삼킴)
- **스크롤 뒤 클릭 오발동 방지**: 2손가락 구간은 `isDrag`(탭 한계 초과) 이후부터만 SCROLL을 방출하며, `isDrag`는 구간 내내 sticky해서 우클릭 배제 조건과 그대로 겹치므로 탭/스크롤 사각지대가 없다. 단, **직전 구간이 드래그(스크롤)였다면 그 뒤에 붙는 어떤 짧은 꼬리도 탭으로 재해석하지 않는다** — 꼬리 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`)의 유예 시간 안이든 밖이든 무조건 클릭 없음. 이게 없으면 "스크롤하고 손을 뗐을 뿐인데 커서 위치가 클릭되는" 사고가 난다
- **더블탭 지연이 모든 1손가락 클릭에 적용됨**: 탭이 끝나도 `DOUBLE_TAP_INTERVAL_MS`(300ms) 동안 즉시 CLICK을 보내지 않고 두 번째 탭을 기다린다 — 더블클릭을 지원하는 이상 피할 수 없는 트레이드오프다. 이 대기 중에 다른 종류의 제스처(드래그/스크롤/우클릭)가 시작되면 대기 중인 클릭을 **취소하지 않고 즉시 내보낸다("flush")** — 취소하면 사용자가 실제로 한 클릭이 사라지고, 그대로 두면 드래그로 커서가 옮겨간 뒤 엉뚱한 위치에서 클릭이 나가거나 우클릭 컨텍스트 메뉴가 뜬 직후 클릭이 도착해 메뉴 항목을 눌러버릴 수 있다. 우클릭 시에는 더블탭 감지기도 함께 리셋해, 우클릭 앞뒤의 무관한 좌탭 두 개가 우연히 더블탭으로 묶이지 않게 한다
- 정확히 `DOUBLE_TAP_INTERVAL_MS` 경계에서 두 번째 탭이 오면(판정은 `System.currentTimeMillis()`, 발사는 코루틴 `delay`라 시간축이 미세하게 다름) 아주 드물게 CLICK과 DOUBLE_CLICK이 둘 다 나갈 수 있음 — 영향이 미미해(실제 창은 한 프레임 수준) 현재는 허용

### 감도 상수 (`GestureConfig.kt`)

> **사용자 조정 가능한 값은 `MOVE_SENSITIVITY`와 `SCROLL_SENSITIVITY_PX_PER_STEP` 두 개뿐**이다(설정 화면 → DataStore 영속화, `GestureSettings`). 이 상수들은 **기본값의 단일 출처**로 유지되며 "기본값으로 복원"은 저장된 키를 지워 이 값을 다시 따라가게 한다. 나머지 상수(탭 시간/거리, 더블탭 간격, 드래그 홀드 시간, 해제 유예)는 서로 얽힌 불변식(`DRAG_HOLD_THRESHOLD_MS == TAP_MAX_DURATION_MS`, `MULTI_TOUCH_RELEASE_GRACE_MS << TAP_MAX_DURATION_MS`, `SCROLL_PX_PER_STEP_MIN > TAP_MAX_DISTANCE_PX`)이 있어 사용자에게 열지 않는다. 설정값은 `MultiTouchGestureTracker` 생성자로 주입되며(트래커는 값의 출처를 모르는 순수 Kotlin), 설정 화면은 연결 전(Disconnected/Error) 화면에서만 진입한다.

```kotlin
MOVE_SENSITIVITY_MIN / MAX = 0.5f / 4.0f      // 설정 슬라이더 범위 (이동 배율)
SCROLL_PX_PER_STEP_MIN / MAX = 25f / 100f     // 설정 슬라이더 범위. MIN은 반드시 TAP_MAX_DISTANCE_PX(20)보다 커야 함(사각지대 방지) — GestureConfigTest가 강제
MOVE_SENSITIVITY       = 1.5f   // 이동 배율
TAP_MAX_DISTANCE_PX    = 20f    // 탭 판정 최대 이동 거리
TAP_MAX_DURATION_MS    = 200L   // 탭 판정 최대 지속 시간
MOVE_MIN_DISTANCE_PX   = 5f     // 커서 이동 최소 거리 (떨림 억제)
SINGLE_POINTER_COUNT   = 1      // 1손가락 구간 판정 기준 (탭 → 좌클릭, MOVE 방출)
DOUBLE_POINTER_COUNT   = 2      // 2손가락 구간 판정 기준 (탭 → 우클릭)
MULTI_TOUCH_RELEASE_GRACE_MS = 50L  // 손가락 어긋나게 떼기 보정 유예 시간
SCROLL_SENSITIVITY_PX_PER_STEP = 40f  // 휠 1스텝에 해당하는 centroid 이동 거리. 반드시 TAP_MAX_DISTANCE_PX보다 커야 함(사각지대 방지)
DOUBLE_TAP_INTERVAL_MS = 300L    // 두 탭을 하나의 더블탭으로 묶을 최대 간격 (모든 좌클릭이 겪는 지연이기도 함)
DOUBLE_TAP_DISTANCE_PX = 40f     // 두 탭 중심 좌표 사이 최대 허용 거리 (TAP_MAX_DISTANCE_PX의 2배)
DRAG_HOLD_THRESHOLD_MS = TAP_MAX_DURATION_MS  // 탭홀드 드래그 승격까지 제자리 유지해야 하는 시간 — 탭이 아니게 되는 시점과 정확히 일치시켜 사각지대 제거
DEFAULT_PORT           = 9000
UDP_PORT               = 9001   // MOVE 전용 UDP 포트
SESSION_HANDSHAKE_TIMEOUT_MS = 3000  // TCP 연결 후 SESSION 줄 대기 최대 시간
HEARTBEAT_INTERVAL_MS  = 5000L  // heartbeat 전송 주기 (서버 HEARTBEAT_INTERVAL_S=5.0과 반드시 동시 갱신)
HEARTBEAT_MISS_LIMIT   = 3      // 연속 미응답 한계 (서버 HEARTBEAT_MISS_LIMIT=3과 반드시 동시 갱신)
RECONNECT_MAX_ATTEMPTS = 8      // 자동 재연결 총 시도 횟수 (Android 전용 — 서버와 동기화 불필요)
RECONNECT_BASE_DELAY_MS = 1000L // 1회차 시도 전 대기. 이후 2배씩: 1→2→4→8→10s 상한 (총 ≈55초)
RECONNECT_MAX_DELAY_MS = 10_000L // 백오프 상한
```

---

## 6. 개발 로드맵

### ✅ Phase 1 — MVP (완료)
- [x] Clean Architecture 뼈대 (domain / data / presentation)
- [x] Hilt DI 설정
- [x] TCP 단일 채널 연결
- [x] 1손가락 이동(MOVE) + 1손가락 탭(CLICK)
- [x] Python 서버: TCP 수신 + SendInput 커서/클릭 제어
- [x] Android 연결 UI (IP 입력 → 연결 중 → 트랙패드 서페이스)

### ✅ Phase 2 — 하이브리드 통신 + 추가 제스처 (완료)
- [x] MOVE를 UDP(9001)로 분리, 세션 토큰 기반 매칭
- [x] Python 서버에 UDP 소켓 추가 (TCP 세션과 매핑) — `SessionRegistry` (threading.Lock 보호)
- [x] TCP heartbeat (주기: 5초, 미응답 3회 → 연결 해제) — 카운터 기반, 양쪽 5초 창 × 3회로 판정. Android가 핸드셰이크 이후 TCP를 읽지 않던 공백이 해소되어, 서버가 세션을 회수하면 앱도 `Error`로 전환된다
- [x] Android: `PointerInfo` 기반 멀티터치 제스처 감지 — 손가락 개수 변화 시 진행 중이던 구간을 취소하고 새로 시작(AGENTS.md 섹션 5 엣지 케이스)
- [x] 2손가락 탭 → 우클릭 — Android 단일 사이드로 완료. 서버는 Phase 1부터 `button != "left"`를 전부 우클릭으로 처리하고 있어 변경 없음. 손가락을 어긋나게 떼는 실기기 특성 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`) 포함
- [x] 2손가락 드래그 → 스크롤 — TCP + 정수 스텝. px→스텝 변환과 잔차 누적은 Android가 전담하고 서버는 `steps × WHEEL_DELTA(120)`만 수행. 감도 40px/스텝(탭 한계 20px의 2배로 사각지대 제거)

**Phase 2 구현 시 핵심 파일:**
- `data/network/TcpClient.kt` — 세션 핸드셰이크 + heartbeat 수신용 `readLine()`/`applyHeartbeatTimeout()` 완료
- `data/network/UdpClient.kt` — 완료
- `data/network/SessionHandshake.kt` — 완료 (세션 라인 파서)
- `data/repository/TrackpadRepositoryImpl.kt` — UDP 채널 분기 + heartbeat sender/watchdog 루프 완료. `TcpClient`는 한 줄 읽기만 제공하고, 루프 자체(전송 주기·미응답 판정)는 이 클래스가 소유하는 책임 분리 구조. MOVE/SCROLL은 고빈도라 전송 실패 시 연결 상태를 덮어쓰지 않는다(CLICK/DOUBLE_CLICK/DRAG_START/DRAG_END는 저빈도라 실패를 연결 유실로 취급 — Phase 4부터는 `reportConnectionLost`와 같은 경로로 합류해 자동 재연결을 트리거한다. 자세한 내용은 아래 Phase 4 항목)
- `di/DispatcherModule.kt` — heartbeat 루프용 `@IoDispatcher` 제공(테스트에서 가상 시간 디스패처로 교체 가능)
- `presentation/trackpad/MultiTouchGestureTracker.kt` — 좌/우클릭 + 스크롤 판정까지 완료. Compose에 의존하지 않는 순수 판정기(구간 기반 상태 머신). `TrackpadScreen.kt`는 이 트래커를 호출하는 얇은 어댑터
- `pc_server/server.py` — UDP 소켓 + 세션 매핑 + heartbeat 판정 완료. TCP 이벤트 처리(`handle_event` 호출)는 이벤트 단위로 예외를 격리해 필드값이 깨진 이벤트 하나가 세션 전체를 끊지 않는다
- `pc_server/input_controller.py` — `_move`에 한정된 sub-pixel 잔차 누적 이슈(느린 정밀 이동 시 델타 소실) — 별도 이슈로 개선 권장. SCROLL은 Android가 잔차를 완전히 처리해 보내므로 해당 없음

### ✅ Phase 3 — 제스처 확장 (완료)
- [x] 1손가락 더블탭 → DOUBLE_CLICK — 지연 후 확정 방식(`DOUBLE_TAP_INTERVAL_MS`=300ms 안에 두 번째 탭이 오면 CLICK 두 개 대신 DOUBLE_CLICK 하나). 서버는 down-up-down-up 4개를 `SendInput` 1회로 원자적으로 전송(커서 이동 없음). 다른 제스처(드래그/스크롤/우클릭) 시작 시 대기 중인 클릭을 취소가 아니라 즉시 발사(flush)해 클릭 유실·오발동을 막음
- [x] 탭홀드(200ms↑) + 드래그 → DRAG_START / DRAG_END — 승격 판정은 `DragHoldDetector`(순수 클래스)로 분리해 단위 테스트 가능. 승격 후 이동은 여전히 UDP MOVE 그대로 사용(새 이벤트 없음). 서버는 연결 종료 시(정상/heartbeat 타임아웃/예외 전부) 드래그가 활성 상태면 강제로 버튼을 놓는 안전장치를 가짐
- [x] 감도 설정 화면 (Android Settings Screen) — 조정 가능한 값은 포인터 속도(0.5~4.0배)와 스크롤 속도(25~100px/스텝) 두 개뿐. 스크롤 슬라이더는 방향을 뒤집어 "오른쪽 = 빠름"으로 보여준다(`ScrollSpeedSlider`). 연결 전(Disconnected/Error) 화면의 "감도 설정" 버튼으로만 진입 — 연결 후 화면은 전체가 제스처 표면이라 버튼을 두지 않는다. Navigation 의존성 없이 상태 기반 전환 + `BackHandler`
- [x] `GestureConfig`를 DataStore로 영속화 — `androidx.datastore:datastore-preferences:1.0.0`. 키 `move_sensitivity`/`scroll_px_per_step`, 읽기·쓰기 양쪽에서 `GestureSettings.sanitized()`(범위 clamp, NaN/Infinity는 기본값)를 통과. 슬라이더는 `onValueChangeFinished`에서만 저장. "기본값으로 복원"은 기본값을 쓰지 않고 키를 삭제

**Phase 3 구현 시 핵심 파일:**
- `presentation/trackpad/DoubleTapDetector.kt` — 완료. Compose 비의존 순수 상태 머신("직전 탭 1개"만 기억), `MultiTouchGestureTracker`와 같은 설계 원칙
- `presentation/trackpad/DragHoldDetector.kt` — 완료. 탭홀드 드래그 승격 판정(경과 시간 + 누적 이동 거리)을 담당하는 순수 클래스. `DragHoldSignal`(None/Start/End)을 반환
- `presentation/trackpad/MultiTouchGestureTracker.kt` — `GestureEndDecision`에 탭 위치(x, y) 추가(더블탭 거리 판정용). 탭/드래그/우클릭 판정 로직 자체는 무변경
- `presentation/trackpad/TrackpadScreen.kt` — `pointerInput` 블록을 `coroutineScope`로 감싸 제스처 하나보다 오래 사는 스코프에서 지연 클릭 job, `flushPendingClick()`, 드래그 홀드 타이머 경합(`AwaitPointerEventScope.withTimeoutOrNull`)을 관리. 코루틴 타이밍 자체는 여전히 자동 테스트 없음(Compose 의존) — 승격 판정 로직만 `DragHoldDetector`로 분리해 부분적으로 해소됨
- `domain/model/TrackpadEvent.kt` — `DoubleClick(button = "left")`, `DragStart`/`DragEnd`(필드 없는 `object`) 추가. 전부 CLICK과 같은 등급(저빈도)이라 전송 실패 시 `Error`로 알림(MOVE/SCROLL처럼 조용히 버리지 않음)
- `data/network/TcpClient.kt` — `send()` 전용 단일 스레드 디스패처(`Dispatchers.IO.limitedParallelism(1)`) 도입. 이벤트별로 별도 코루틴에서 보내는 구조상 공용 IO 풀(병렬도 64)에서는 순서가 뒤바뀔 수 있어(DRAG_START 직후 DRAG_END처럼 순서가 의미를 갖는 쌍에서 특히 위험), 전송 순서를 호출 순서에 맞춰 직렬화
- `domain/model/GestureSettings.kt` — 조정 가능한 두 값(`moveSensitivity`, `scrollPxPerStep`)과 clamp 로직(`sanitized()`)을 가진 순수 data class. `coerceIn`은 NaN을 그대로 통과시키므로 유한성 검사를 먼저 한다(NaN 배율이 판정기에 들어가면 모든 델타가 NaN이 되어 커서가 영구 정지). 기본값은 `GestureConfig` 상수 참조. 주의: domain이 presentation.util의 `GestureConfig`를 import하는 계층 방향 역전이 있음(기존 `TrackpadUiState`와 동일 패턴) — 정리하려면 `GestureConfig`를 domain으로 옮겨야 함
- `data/repository/DataStoreSettingsRepository.kt` + `di/DataStoreModule.kt` — DataStore(Preferences) 구현과 Hilt 제공. `IOException`은 빈 Preferences로 복구(설정을 못 읽는다고 트랙패드가 멈추면 안 됨), 그 외 예외는 그대로 올림
- `presentation/settings/` — `SettingsScreen`/`SettingsViewModel`/`SettingsUiState`/`ScrollSpeedSlider`. 저장 시 `uiState`가 아니라 `settings.first()`로 최신 저장값을 읽어 한 필드만 바꾼다(`WhileSubscribed`라 구독자가 없으면 `uiState`가 멈춰 있을 수 있음)
- `presentation/trackpad/MultiTouchGestureTracker.kt` — 이동 배율·스크롤 px/스텝을 생성자 파라미터로 주입(기본값 = `GestureConfig` 상수, `GestureSettings` 편의 생성자 포함). 기존 판정 로직/테스트 무변경
- `presentation/trackpad/TrackpadScreen.kt` — 트래커 생성 시 현재 설정값을 넘기고 `pointerInput` key에 설정값을 포함(안 넣으면 블록 안에 캡처된 낡은 값이 남는다)
- `pc_server/input_controller.py` — `_double_click(button)`, `_drag_start()`/`_drag_end()`/`force_release_drag()` 완료. 드래그 상태는 프로세스 전역(현재 컨트롤러가 프로세스당 하나)
- `pc_server/server.py` — `handle_client`의 `finally`에서 연결 종료 시 드래그 강제 해제 호출

### 🔶 Phase 4 — 완성도 (재연결·트레이·패키징 완료, UDP 탐색·예외 처리 강화 남음)
- [x] PC 트레이 아이콘 (`pystray`) — 연결 상태 표시 + 종료. 서버 단일 사이드(와이어 프로토콜 무변경). 아이콘은 Pillow로 코드에서 생성(대기 회색/연결됨 초록), 메뉴는 상태 라벨·접속 주소(LAN IP:9000)·종료. `--no-tray` 옵션과 미설치 시 콘솔 모드 폴백. 정지 가능한 `ServerRuntime`으로 정상 종료 경로를 만들고 `atexit` 드래그 해제 안전장치를 추가해 섹션 10의 "서버 프로세스 강제 종료 시 드래그 상태" 항목을 해소
- [ ] UDP 브로드캐스트 자동 서버 탐색 (수동 IP 입력은 fallback 유지)
- [x] 재연결 로직 (연결 끊김 감지 → 자동 재시도) — Android 단일 사이드(프로토콜/서버 무변경: 재연결은 기존 핸드셰이크를 그대로 다시 수행할 뿐이라 서버에는 "새 클라이언트 접속"과 구분되지 않음). **"Connected였던 세션이 유실됐을 때만"** 재시도하며, 첫 `connect()` 실패는 기존처럼 `Error`로 남긴다(틀린 IP에 55초씩 매달리지 않기 위해). 유실은 `Error`를 거치지 않고 곧바로 `ConnectionState.Reconnecting(host, attempt, maxAttempts)`로 가고, 성공하면 `Connected`, 8회 소진 시 `Error("Reconnect failed: <마지막 원인>")`. 재연결 화면에는 "취소" 버튼(= 수동 `disconnect()`)
- [ ] 예외 처리 강화 (네트워크 오류, 권한 오류 등)
- [x] PyInstaller로 단일 exe 패키징 — 서버 단일 사이드(와이어 프로토콜 무변경). onefile + windowed(`--noconsole`), `build_exe.ps1`이 **저장소 밖 임시 venv**에서 빌드해 전역 Python을 바꾸지 않는다(pytest 기준선이 pystray 미설치 상태라서). 결과 `pc_server/dist/PhonePadServer.exe` 15.6MB, 빌드 약 40초. 함께 해결: 서버 중복 실행(named mutex, 종료 코드 2)과 windowed 로그 소실(`%LOCALAPPDATA%\PhonePad\server.log`). 실제로 exe를 빌드·실행해 TCP 9000/UDP 9001·중복 실행 가드·로그 기록·exe 안에서 pystray 트레이 윈도 생성까지 확인

**재연결 구현 시 핵심 파일/설계:**
- `domain/model/ReconnectPolicy.kt` — 순수 클래스(시간·코루틴 비의존). `delayBeforeAttempt(attempt)`(1-based, 1→2→4→8→10s 상한, shift 폭 31 제한으로 Long 오버플로 방지 — 음수 지연은 `delay()`를 즉시 반환시켜 재시도 폭주가 된다), `shouldAttempt`. `Disabled` 인스턴스를 테스트에 주입해 재연결 이전의 "유실 → `Error`" 동작을 그대로 재현 — 기존 heartbeat/직렬화 테스트는 로직을 고치지 않고 이 정책만 끼웠다
- `data/repository/TrackpadRepositoryImpl.kt` — `openConnection()`으로 연결 1회를 추출해 수동 연결과 재연결이 같은 코드를 탄다. 경합은 4중으로 막는다: `connectionMutex`(접속 1회 직렬화) + `generation` CAS(연결 단위 중복 유실 보고 차단) + `reconnectEpoch`(재시도 묶음 유효성 — 연결 세대는 재연결 성공마다 올라가서 이 역할을 못 한다) + `reconnectJob.cancel()`. **취소는 뮤텍스 밖에서 먼저 한다**(락을 잡은 뒤 취소하면 진행 중인 접속 시도가 끝날 때까지 기다려 이중 접속이 된다). `openConnection`은 `CancellationException`을 따로 rethrow — 일반 `catch (e: Exception)`에 삼켜지면 취소가 `Error`로 둔갑한다. 재연결 루프는 `keepAliveScope`가 아닌 별도 `reconnectScope`에서 돈다(유실을 보고하는 heartbeat 루프가 곧바로 자기 스코프를 취소하므로). 첫 `Reconnecting`은 동기적으로 써서 `Error` 깜빡임이 구조적으로 불가능하다. `reportConnectionLost`는 `state !is Connected`면 무시 — 수동 disconnect 뒤 뒤늦게 도착한 전송 실패가 연결을 되살리거나, 재연결 중 좀비 전송 실패가 재시도를 `Error`로 깨는 것을 막는다
- 전송 실패 합류: `CLICK`/`DOUBLE_CLICK`/`DRAG_START`/`DRAG_END`의 TCP 전송 실패는 이전에는 `Error`만 세팅하고 소켓 정리·세대 무효화·keep-alive 중단을 하지 않아, 소켓은 죽었는데 heartbeat 루프가 최대 5초 더 돌았다. 이제 `sendOverTcp()` → `reportConnectionLost`로 합류해 즉시 정리 + 재연결한다. **`MOVE`/`SCROLL`의 조용한 실패는 그대로**(F-1/F-2 의도 — 고빈도 이벤트가 유실 원인 메시지를 덮어쓰지 않게)
- `presentation/trackpad/TrackpadScreen.kt` — `ConnectingPanel`을 첫 연결/재연결이 공유(재연결일 때만 대상 호스트 표시 + "취소" 버튼). 설정 화면은 여전히 Disconnected/Error에서만 열린다
- 테스트 함정: `runTest`는 본문이 끝난 뒤에도 가상 시간을 계속 진행시킨다. heartbeat/재연결 루프를 살려 둔 채 테스트를 끝내면 "5초마다 전송 → 유실 → 재연결"이 무한히 돌면서 MockK가 호출을 기록하다 힙을 소진한다(실제로 `OutOfMemoryError`가 나 같은 JVM의 무관한 테스트 30개까지 무너졌다). 이 계층의 새 테스트는 반드시 `repository.disconnect()` 또는 `Error` 종료로 끝낼 것

**트레이 아이콘 구현 시 핵심 파일/설계:**
- `pc_server/tray_status.py` — pystray 비의존 순수 로직(표준 라이브러리만): 연결 수 → 상태/툴팁, `detect_lan_ip()`(소켓 주입 가능, 실패 시 `127.0.0.1`이 아니라 "(확인 불가)" — 로컬호스트를 안내하면 사용자를 오도한다), 주소 라벨 포맷
- `pc_server/tray.py` — pystray 어댑터. `pystray`/`Pillow` import는 이 파일에서만, `ImportError`뿐 아니라 DLL 로드 실패까지 잡아 `tray_available()`로 노출. `icon_factory`/`image_factory` 주입으로 가짜 `Icon`을 넣어 미설치 환경에서도 동작 로직 전체를 테스트한다. 연결 수 갱신은 1초 폴링 스레드(`SessionRegistry` 공개 API/동작은 그대로), 상태가 그대로면 아이콘을 건드리지 않고(Windows 깜빡임 방지) 이미지는 상태 **종류**가 바뀔 때만 교체
- `pc_server/server.py` — `ServerRuntime`(stop Event + accept timeout 0.5s, 포트 0 바인딩·실제 포트 노출), `run_console`/`run_with_tray`, `release_drag()`(멱등, 예외 비전파), `main(argv)`. **기존 `handle_client`/`udp_listener`/`handle_udp_packet`/`SessionRegistry`는 무변경**
- 스레드 모델: pystray가 Windows에서 메시지 루프를 메인 스레드가 소유해야 하므로 **트레이가 메인 스레드, 서버가 백그라운드 스레드**(콘솔 모드는 기존처럼 메인 스레드가 서버). 종료 순서: `stop 설정 → 서버 스레드 join(3s) → release_drag → 아이콘 제거 → 정수 반환` — 드래그 해제를 아이콘 제거보다 먼저 둔다(아이콘이 사라진 뒤 버튼이 눌린 채 남으면 단서가 없다). `os._exit`/`sys.exit` 남발 없음
- 서버 스레드 사망 처리: 서버 스레드가 `BaseException`을 잡아 로그하고 트레이를 내려 종료 코드 1로 끝난다(아이콘만 남는 좀비 방지). `stop()`과 pystray 루프 기동의 경합은 `_stopped`/`_loop_ready` 이중 확인 + lock 기반 test-and-set으로 막고 전용 테스트로 고정
- 테스트 함정: `pystray.MenuItem`은 콜백의 `action.__code__.co_argcount`로 인자 수를 세므로 `lambda _icon, _item, act=action: act()`처럼 기본 인자 바인딩을 쓰면 3개로 계산해 `ValueError`를 던진다. **pystray 미설치 환경에서는 `_build_menu()`의 pystray 경로가 실행되지 않아 이 결함이 영원히 드러나지 않는다**(실제 venv에서 실행해서 22건 실패로 발견) → `_wrap_action()`으로 수정. 어댑터를 고칠 때는 미설치 환경 테스트만 믿지 말고 venv에 실제 pystray를 설치해 한 번 더 돌릴 것

**패키징 구현 시 핵심 파일/설계:**
- `pc_server/single_instance.py` — `Local\PhonePadServer` named mutex. **소켓 옵션은 무변경**(`SO_REUSEADDR` 유지 — 재시작 시 TIME_WAIT 재바인드 동작을 지키기 위해). 획득한 핸들을 모듈 전역에 붙들어 둔다(지역 변수면 GC 후 mutex가 풀려 가드가 무력화됨). 같은 프로세스 안에서는 멱등, `ctypes.windll`이 없는 환경이나 mutex 생성 실패 시에는 **서버를 그대로 띄운다**(가드가 서버 기동을 막는 방향의 실패는 피함), `--allow-multiple`로 해제. 중복이면 ASCII 한 줄 + (windowed일 때만) `MessageBoxW` 후 종료 코드 2. `GetLastError` 신뢰성을 위해 `ctypes.WinDLL("kernel32", use_last_error=True)` 사용
- `pc_server/logging_setup.py` — `sys.stdout`/`sys.stderr`가 `None`일 때만 로그 파일로 교체. 코드 전반의 `print()`는 한 줄도 고치지 않았다(스트림만 바꿈). 줄 단위 flush, 시작 시 1MB 초과면 `.1`로 1세대 회전, 열기 실패 시 `os.devnull` 폴백. 콘솔이 있는 일반 실행은 완전 무변경
- `pc_server/server.py` — 변경은 **+20줄**(import 2줄, `--allow-multiple`, `main()` 진입부의 `configure_stdio()` + `enforce()` 호출뿐). `ServerRuntime`/`handle_client`/소켓 옵션은 무변경
- `pc_server/tests/conftest.py` — mutex 이름을 pytest 프로세스 전용으로 격리(가드를 모킹하지 않고 이름만 바꿔 실제 `CreateMutexW` 경로는 그대로 실행). 이게 없으면 **서버가 트레이에 떠 있을 때 `server.main()`을 호출하는 기존 테스트가 종료 코드 2로 깨진다**
- 빌드 함정: (1) onefile exe는 **프로세스가 2개**(부트로더 + Python 자식)라 로그의 pid가 `Start-Process` pid와 다르고 종료할 때 둘 다 잡아야 한다, (2) windowed 판정은 부모가 결정한다(아래 섹션 10), (3) pystray는 Windows 백엔드를 `importlib`로 동적 선택해 정적 분석에 안 잡히므로 `pystray._win32`를 `hiddenimports`로 명시해야 한다

### ⬜ Phase 5 — 선택 확장
- [ ] PIN 코드 인증 (TCP 핸드셰이크 단계에 추가)
- [ ] 3손가락 스와이프 → 가상 데스크톱 전환
- [ ] 다중 클라이언트 지원 정책 결정

---

## 7. 세션 흐름

```
Android                                          PC Server
   |                                                 |
   |-- UDP broadcast (탐색) --------------------->   |   (Phase 4 예정, 미구현 — 현재는 수동 IP 입력)
   |<-- UDP response (IP:port) -------------------   |   (Phase 4 예정, 미구현)
   |                                                 |
   |-- TCP connect ------------------------------>   |
   |<-- {"type":"SESSION","session":"<32hex>"} ---   |   ✅ 구현됨 (연결 직후 첫 줄)
   |                                                 |
   |-- TCP: CLICK (탭 종료 후 300ms 지연) --------->  |   ✅ 구현됨
   |-- TCP: DOUBLE_CLICK --------------------------->  |   ✅ 구현됨 (CLICK 2개 대신 1개, 커서 이동 없음)
   |-- TCP: SCROLL -------------------------------->  |   ✅ 구현됨 (정수 스텝, session 없음)
   |-- TCP: DRAG_START (제자리 200ms 유지 시) ------>  |   ✅ 구현됨 (버튼을 누른 채 유지)
   |-- UDP: MOVE (버튼 눌린 채로 커서만 이동) ------>  |   기존 MOVE 채널 그대로 재사용
   |-- TCP: DRAG_END (손을 떼면) -------------------->  |   ✅ 구현됨 (버튼 뗌). 연결이
   |                                                 |   끊기면 서버가 강제로 놓음(안전장치)
   |-- UDP: {session, type:MOVE, dx, dy} --------->  |   ✅ 구현됨
   |                                                 |
   |-- TCP: HEARTBEAT (첫 전송은 연결 후 5s) ----->  |   ✅ 구현됨 (이후 5초 주기)
   |<-- TCP: HEARTBEAT_ACK ------------------------  |   ✅ 구현됨 (handle_event 미경유)
```

---

## 8. 실행 방법

### PC 서버
```bash
cd pc_server
pip install -r requirements.txt   # 트레이 아이콘용 (선택 — 없으면 콘솔 모드로 동작)
python server.py                  # 시스템 트레이 아이콘과 함께 실행
python server.py --no-tray        # 트레이 없이 콘솔 모드 (Ctrl+C로 종료)
# → TCP 9000(이벤트+세션 핸드셰이크) / UDP 9001(MOVE 전용) 포트에서 대기

# 단일 exe 빌드 — 임시 venv를 저장소 밖에 만든다(전역 Python은 건드리지 않음)
./build_exe.ps1                   # → dist/PhonePadServer.exe (약 15.6MB, 빌드 약 40초)
```
**exe 실행:** 더블클릭하면 windowed(`--noconsole`)라 콘솔 창 없이 트레이 아이콘만 뜬다. 그래서 `print()` 로그는 **`%LOCALAPPDATA%\PhonePad\server.log`** 로 간다(줄 단위 flush, 1MB를 넘으면 시작 시 `server.log.1`로 1회 회전). 빌드 산출물(`build/`, `dist/`)은 커밋하지 않는다.
**트레이 모드:** 아이콘 색이 상태를 보여준다(회색 = 대기 중, 초록 = 연결됨). 툴팁은 `Phone Pad - 연결됨 (N대)`, 메뉴에는 앱에 입력할 **접속 주소(`PC의 LAN IP:9000`)** 가 표시되며 **"종료"** 로 끈다. `pystray`/`Pillow`가 설치돼 있지 않으면 경고 한 줄을 출력하고 자동으로 콘솔 모드로 동작한다(서버 기능은 트레이 의존성에 막히지 않는다). 서버가 예외로 죽으면(포트 바인드 실패 등) 트레이도 함께 내려가 프로세스가 종료 코드 1로 끝난다 — 아이콘만 남는 좀비는 생기지 않는다.
**서버를 두 번 실행하면 자동으로 차단된다:** Windows에서는 `SO_REUSEADDR` 때문에 이미 점유된 포트에도 bind가 성공해 예전에는 트레이 아이콘이 2개 뜨고 한쪽만 트래픽을 받았다. 이제 두 번째 프로세스가 named mutex(`Local\PhonePadServer`)로 이를 감지해 "이미 실행 중입니다" 안내창(windowed) 또는 stderr 한 줄(콘솔)을 띄우고 **종료 코드 2**로 끝난다 — 첫 인스턴스는 영향받지 않는다. 개발 중 일부러 두 개를 띄우려면 `--allow-multiple`. 단 다른 로그인 세션에서 띄운 서버는 감지하지 못한다(아래 섹션 10 참조).
**Windows 방화벽(exe):** `python.exe`로 허용해 둔 기존 규칙은 `PhonePadServer.exe`에 적용되지 않으므로 exe로 처음 실행하면 새 방화벽 프롬프트가 뜰 수 있다(미검증 — 이 환경에서 확인 불가). 허용 대상은 아래와 같다.

**Windows 방화벽:** UDP 9001 인바운드를 허용해야 한다 (TCP 9000만 열려 있으면 커서가 전혀 움직이지 않음 — CLICK은 되는데 MOVE만 안 되면 이 문제일 가능성이 높다).

**트러블슈팅:** 커서가 갑자기 멈추고 앱이 `Heartbeat timeout`/`Connection lost`를 띄우면 TCP 9000 경로(Wi-Fi 절전, 도즈 모드 등으로 heartbeat 전송이 지연되는 경우 포함)를 먼저 의심한다. TCP 세션이 회수되면 이미 전송 중이던 UDP MOVE도 서버가 조용히 무시하므로 함께 멈춘다.

PC 마우스 왼쪽 버튼이 눌린 채로 멈춰 있다면(뭘 클릭해도 계속 드래그처럼 동작) 탭홀드 드래그의 `DRAG_END`가 유실된 상태다 — 앱을 재연결하면 TCP 연결이 다시 맺어지면서 서버가 이전 연결 종료 시 강제로 버튼을 놓으므로 대부분 자연히 해소된다. 서버를 트레이 "종료"·Ctrl+C·예외 종료 등 인터프리터가 정상적으로 끝나는 경로로 껐다면 종료 시 드래그가 강제로 해제된다(`atexit` 안전장치). 작업 관리자로 프로세스를 강제로 죽였다면(`taskkill /F` 등 인터프리터가 정리 기회를 못 얻는 경로) 이 안전장치도 동작하지 않으므로 수동으로 마우스 좌클릭을 한 번 눌러 버튼 상태를 풀어야 할 수 있다.

### Android 앱
1. Android Studio에서 `phone_pad_app/` 열기
2. 빌드 후 기기에 설치
3. 앱 실행 → PC IP 입력 → 연결 (TCP 핸드셰이크로 세션 토큰을 받아야 Connected로 전환됨)

### 테스트 실행
```bash
cd pc_server && python -m pytest         # 서버 단위 테스트
cd phone_pad_app && ./gradlew :app:testDebugUnitTest   # Android 단위 테스트
```

---

## 9. 코딩 컨벤션

- 새 이벤트 타입 추가 시: `TrackpadEvent.kt` sealed class 확장 →
  채널 선택(이동 좌표면 UDP+session 필드 포함, 아니면 TCP) →
  `TrackpadRepositoryImpl.kt`의 `when` 직렬화 → `input_controller.py`의 `handle_event`
  (UDP로 보낼 경우 `pc_server/server.py`의 `handle_udp_packet`도 함께 확인)
  - **예외:** 연결 유지/전송 계층 전용 메시지(예: `HEARTBEAT`/`HEARTBEAT_ACK`)는 sealed
    class에 넣지 않고 data 계층(`TrackpadRepositoryImpl`) 내부 상수로만 만든다.
    제스처가 아니므로 `SendEventUseCase`를 경유해 presentation이 임의로 발사할 수
    있게 되는 것을 원치 않기 때문이다. 대신 와이어 리터럴을 고정하는 테스트를
    반드시 둔다(예: `TrackpadRepositoryHeartbeatTest`의 리터럴 검증 테스트).
- 제스처 판정 임계값은 반드시 `GestureConfig.kt` 상수로 분리
- ViewModel에서 직접 네트워크 호출 금지 — UseCase 경유
- 새 화면 추가 시 `presentation/<feature>/` 하위 패키지로 분리
- Python 서버: 이벤트 타입별 처리는 `InputController.handle_event`에 집중
- **서버에 "실행 진입점 동작"(중복 실행 가드·로깅 설정 등)을 추가할 때는 `server.py`가 아니라 새 모듈로 만들고 `main()`에서 몇 줄로 호출한다.** `ServerRuntime`/`handle_client`는 라이브러리처럼 import되어 테스트되므로, 진입점 전용 동작이 그 안으로 새면 테스트가 실행 환경(예: 트레이에 서버가 떠 있는지)에 끌려다닌다
- **Python 서버의 `print()` 로그 메시지는 ASCII만 사용한다** — em dash(—) 같은 비ASCII 문자가 한국어 Windows 콘솔(cp949)에서 `UnicodeEncodeError`를 던져, 정작 중요한 순간(예: 연결 종료 시 드래그 강제 해제 성공 로그)에 `except`가 이를 잡아 "실패"로 잘못 보고한 적이 있다. 일반 하이픈(-)이나 영문 기호로 대체할 것
- **새 기능/버그 수정 시 테스트 코드도 함께 작성**
  - Android: `usecase`/`repository` 등 도메인 로직은 JUnit + MockK 단위 테스트, 제스처 판정 로직(`GestureConfig` 기준값)은 별도 테스트로 검증
  - Python 서버: `input_controller.py`의 `handle_event` 등 이벤트 처리 로직은 `unittest`/`pytest`로 단위 테스트 작성
  - 테스트 없는 PR/커밋은 지양 — 최소한 핵심 로직(제스처 판정, 이벤트 직렬화/처리)은 커버
- **커밋은 파트별로 나눈다** — Android/서버 양쪽에 걸친 작업이라도 한 커밋에 몰아넣지 않고, 다음 기준으로 분리한다:
  1. `feat(android): ...` — `phone_pad_app/` 변경 (앱 코드 + 테스트)
  2. `feat(server): ...` — `pc_server/` 변경 (서버 코드 + 테스트)
  3. `docs(harness): ...` — `AGENTS.md`/`CLAUDE.md`/`.claude/` 갱신 + `_workspace/` 실행 기록
  - 커밋 메시지는 `type(scope): subject` 형식(스코프를 괄호 안에 표기)을 따르고, `type`은 `feat`/`fix`/`refactor`/`docs` 등 일반적인 컨벤션을 사용한다
  - 세 파트 중 실제로 변경이 없는 파트는 커밋을 만들지 않는다 (예: 문서만 고쳤으면 `docs(harness):` 하나만)
  - `phone-pad-orchestrator` 스킬로 진행한 작업을 커밋할 때는 항상 이 방식을 기본으로 따른다 (사용자가 다르게 요청하면 그에 따른다)

---

## 10. 미결 사항

| 항목 | 현황 |
|------|------|
| Android DI | Hilt 2.48 사용 중 (확정) |
| 바이너리 프로토콜 전환 | Phase 2 성능 테스트 후 결정 |
| PC 서버 배포 | PyInstaller onefile + windowed 완료(`build_exe.ps1`). 코드 서명·설치 관리자·부팅 시 자동 시작 등록은 범위 밖 |
| PIN 인증 | Phase 5 선택 사항 — UDP 세션 토큰이 평문이고 발신 IP도 검증하지 않아 동일 WiFi 내 스푸핑이 가능함. PIN 인증 설계 시 함께 재검토 |
| 다중 기기 연결 | 정책 미정 — 서버는 현재 활성 세션 전부를 동시에 처리 가능한 구조(집합 기반)라, 여러 기기가 동시에 연결하면 전부 커서를 움직일 수 있음. 드래그 상태(`_drag_active`)도 프로세스 전역이라, 기기 A가 드래그 중일 때 기기 B의 연결이 끊기면 B의 안전장치가 A의 드래그를 놓아버림(실측 확인) — 버튼이 눌린 채 멈추는 것보다 안전한 실패 방향이라 1:1 전제하에 그대로 둠, 다중 기기 지원 시 세션별 상태 분리 필요 |
| sub-pixel 이동 정밀도 | `InputController._move`에 한정된 이슈 — 정수 반올림만 하고 잔차를 누적하지 않아, 아주 느린 드래그의 미세 델타가 소실될 수 있음. SCROLL은 Android가 잔차를 완전히 처리해 보내므로 해당 없음 — 별도 이슈로 개선 검토 |
| heartbeat 리셋 비대칭 | 서버는 CLICK 등 어떤 상향 데이터로도 미응답 카운터가 리셋되지만, 서버→클라이언트 하향 트래픽은 ACK뿐이라 Android 쪽은 사실상 ACK만이 유일한 리셋 수단. 한쪽 방향만 끊기는 비대칭 시나리오가 가능함 — 자동 재연결은 앱이 유실을 감지한 뒤에만 시작되므로, 서버만 끊었고 앱은 아직 모르는 구간(최대 15초)은 그대로 남는다 |
| Android 절전/도즈 환경의 heartbeat | 화면 꺼짐·도즈·Wi-Fi 절전으로 5초 주기 전송이 지연되면 서버가 먼저 15초 타임아웃으로 끊는 오탐 가능성 — 실기기 미검증. 자동 재연결은 백오프 타이머(코루틴 `delay`)만 쓰므로 도즈 중에는 타이머 자체가 지연될 수 있고, 백그라운드·네트워크 전환(WiFi→모바일)·`ConnectivityManager` 기반 즉시 재시도는 이번 재연결 범위 밖 — wake lock과 함께 별도로 다룰 것 |
| 재연결 직후 서버 세션 2개 | 서버는 옛 연결의 종료(EOF/heartbeat 15초 타임아웃)를 감지하기 전까지 옛 세션을 유지하므로, 재연결 직후 잠깐 세션이 2개일 수 있다(서버는 다중 세션 허용 구조). 옛 연결이 회수되는 순간 서버의 드래그 강제 해제(프로세스 전역 `_drag_active`)가 **새 연결의 드래그를 놓아버릴 수 있다** — 아래 "다중 기기 연결"과 같은 뿌리. 발생 조건이 좁아(재연결 후 옛 연결 회수 전 15초 안에 새 연결에서 드래그를 시작해야 함) 지금은 허용하지만, 서버 세션별 상태 분리 시 함께 해결할 것 |
| 재연결 실기기 검증 | Reconnecting 패널 렌더·"취소" 버튼, 유실 시 `Error` 깜빡임이 실제로 없는지, 실제 WiFi 끊김에서 백오프(1→2→4→8→10s, 8회 ≈55초)의 적절성, 재연결 직후 제스처 반응은 실기기 미검증(컴파일·JVM 단위 테스트만 통과). 재연결 횟수 소진 후 사용자가 수동으로 다시 연결하면 그대로 동작한다 |
| 스크롤 방향 규약 | 현재는 "자연 스크롤"(macOS 기본, 손가락과 콘텐츠가 같은 방향)로 구현됨 — Windows 정밀 터치패드 기본값("아래로 움직이면 아래로 스크롤")과는 반대일 수 있음. 실기기 미검증. 뒤집을 경우 `input_controller.py`의 `_scroll()` 한 곳만 수정하면 됨(Android는 절대 동시 수정 금지 — 두 사이드가 같이 뒤집으면 원위치됨) |
| SCROLL_SENSITIVITY_PX_PER_STEP 체감 | 기본값 40px/스텝은 계산 근거(휠 1노치≈3줄, 400px 스와이프≈10스텝≈한 화면)는 있으나 실기기 미검증. 이제 사용자가 설정 화면에서 조정할 수 있으므로(25~100px/스텝) 기본값 자체의 튜닝 우선순위는 낮아졌다. 슬라이더 범위(포인터 0.5~4.0배, 스크롤 25~100px/스텝)의 양 끝 체감도 계산 근거만 있고 실기기 미검증 |
| 설정 영속화 실기기 검증 | 앱 완전 종료 후 재실행 시 값 유지, 회전 시 `rememberSaveable` 복원, 시스템 백 제스처로 설정→연결 화면 복귀(앱 종료로 새지 않는지), 소형 화면·키보드 노출 시 "감도 설정" 버튼 가림 여부는 실기기 미검증. `SettingsScreen`의 슬라이더 커밋 타이밍·`BackHandler`는 Compose UI 테스트가 없다(순수 로직만 분리 커버) |
| DataStore 1.0.0 + Windows JVM 테스트 제약 | DataStore 1.0.0은 임시 파일을 `File.renameTo`로 덮어쓰는데 Windows JVM에서는 대상이 있으면 실패해, **같은 파일에 두 번째로 쓰는** 실파일 테스트가 이 환경에서만 `IOException: Unable to rename`으로 실패한다(Android에서는 정상). 그래서 다중 쓰기 검증 2건(필드 독립성, reset의 키 제거)은 `DataStore<Preferences>` 계약의 in-memory 대역으로 검증하고 실파일 왕복 테스트는 단일 쓰기만 한다. DataStore 1.1+는 해결됐으나 Kotlin 1.8.10/AGP 8.1.3 유지를 위해 1.0.0 사용 — 툴체인을 올리는 시점에 함께 올릴 것 |
| 축 잠금(axis lock) 없음 | 2손가락 대각선 드래그 시 수직/수평 휠이 동시에 나감 — 실기기에서 거슬리면 별도 이슈로 축 고정 로직 검토 |
| 더블탭 300ms 경계 레이스 | 두 번째 탭이 정확히 `DOUBLE_TAP_INTERVAL_MS` 경계에 오면(판정은 `currentTimeMillis`, 발사는 코루틴 `delay`라 시간축이 미세하게 다름) 아주 드물게 CLICK과 DOUBLE_CLICK이 둘 다 나갈 수 있음. 실질 창이 한 프레임(~16ms) 수준으로 영향 미미해 현재는 허용, 완전 제거는 구조적으로 어려움 |
| `TrackpadScreen`의 지연/flush/타이머 로직에 자동 테스트 없음 | 더블탭 대기·취소·flush, 드래그 홀드 타이머 경합이 Compose `awaitEachGesture`에 묶여 있어 순수 단위 테스트가 없다(승격 판정 자체는 `DragHoldDetector`/`DoubleTapDetector`로 분리되어 부분 해소). 다음에 이 영역을 건드릴 때는 남은 코루틴 타이밍 로직도 순수 클래스로 추출해 `runTest` 가상 시간으로 회귀를 고정할 것 |
| 탭홀드 드래그 승격 민감도 | 승격 조건이 "200ms 동안 20px(TAP_MAX_DISTANCE_PX) 이내"라, 고해상도 화면에서 첫 200ms 동안 아주 천천히(약 100px/s 미만) 정밀하게 커서를 미는 동작이 의도치 않게 드래그로 승격될 수 있음. 스펙이 요구한 "탭/드래그 사각지대 없음"의 결과이므로 결함은 아니나 실기기에서 가장 먼저 체감될 항목 — 조정 시 승격 판정 전용의 더 엄격한 정지 반경(예: 8px)을 별도로 두는 방향을 검토(임계 시간 자체를 건드리면 사각지대가 재발함) |
| DRAG_START/DRAG_END 전송 순서 완전 보장 아님 | `TcpClient.send()`를 전용 단일 스레드 디스패처로 직렬화해 위험을 크게 줄였지만, `TrackpadViewModel`이 이벤트마다 별도 코루틴을 `launch`하는 구조라 그 코루틴들이 디스패처에 도달하는 순서 자체까지 수학적으로 보장하지는 않는다. 실제 발생 확률은 매우 낮고(같은 UI 스레드에서 순차 호출되므로), 서버의 멱등 처리와 연결 종료 시 강제 해제 안전장치가 최종 방어선 |
| 서버 프로세스 강제 종료 시 드래그 상태 | **대부분 해소(Phase 4 트레이).** 트레이 "종료"·Ctrl+C·예외 종료는 `atexit` 안전장치와 정상 종료 경로가 `force_release_drag()`를 호출한다(정상·예외 종료 양쪽을 별도 자식 프로세스로 실측, `LEFTUP` 발사 확인). **남은 경계:** `taskkill /F` 같은 하드 킬은 인터프리터가 정리 기회를 얻지 못해 여전히 버튼이 눌린 채 남을 수 있다 — 프로세스 안에서는 막을 수 없는 영역 |
| 서버 중복 실행 (Windows `SO_REUSEADDR`) | **해소(Phase 4 패키징).** `SO_REUSEADDR`가 Windows에서 이미 점유된 포트에도 bind를 성공시키는 동작 **자체는 그대로 두고**(재시작 시 TIME_WAIT 재바인드 유지) 프로세스 단위 named mutex(`Local\PhonePadServer`)로 막는다. 두 번째 인스턴스는 안내 후 종료 코드 2. 빌드된 exe를 두 번 실행해 콘솔·windowed 두 경로 모두 실측. **남은 경계:** `Local\` 네임스페이스라 **다른 로그인 세션**에서 띄운 서버는 감지하지 못하며 그 경우 포트 경합은 그대로다(필요해지면 `Global\` + 권한 처리로 확장). 참고: 서버 사망 경로 테스트가 포트 충돌 대신 이 머신에 없는 주소 `203.0.113.1`에 bind해 `OSError [WinError 10049]`를 만드는 이유는 소켓 동작을 바꾸지 않았으므로 그대로 유효하다 |
| 트레이 실환경 검증 잔여 | 콘솔에서의 **진짜 Ctrl+C**(자동화하면 `CTRL_C_EVENT`가 실행 셸까지 죽여서 단위 테스트로만 커버), 아이콘 시각 품질(다크 테마 대비)과 팝업 메뉴의 한글 폰트/잘림, 실기기 연동 회귀(프로토콜 무변경이라 회귀는 없어야 함). 트레이 렌더링·상태 갱신·종료는 임시 venv에서 실제 Windows 트레이로 확인함 |
| PyInstaller `--noconsole` 시 로그 소실 | **해소(Phase 4 패키징).** `logging_setup.configure_stdio()`가 `sys.stdout`/`sys.stderr`가 `None`일 때만 `%LOCALAPPDATA%\PhonePad\server.log`로 교체한다. 기존 `print()` 호출은 그대로 두고 스트림만 바꿨다. 콘솔 실행은 완전 무변경. 로그 회전은 시작 시 1회뿐이라 한 프로세스를 아주 오래 켜 두면 1MB를 넘어 계속 자랄 수 있다(현재 로그량에서는 문제 없어 과설계를 피함) |
| exe 하드 킬 시 `_MEI` 잔여 | onefile exe는 부트로더가 `%TEMP%\_MEI<랜덤>`에 약 15MB를 풀고 자식 프로세스로 Python을 돌린다. `taskkill /F`로 죽이면 이 디렉터리가 남는다(정상 종료는 정리됨). 프로세스가 **2개**라 강제 종료할 때 둘 다 잡아야 한다 |
| exe의 windowed 판정은 부모가 결정 | GUI 서브시스템 exe여도 부모가 콘솔 핸들을 물려주면(`subprocess.Popen` 등) `sys.stdout`이 살아 있어 로그가 파일이 아니라 부모 콘솔로 간다. 더블클릭/`Start-Process`는 핸들을 안 물려주므로 파일 로깅이 동작한다. 배치 스크립트로 exe를 돌릴 때 로그가 "안 남는" 게 아니라 콘솔로 가는 것 |
| exe 트레이 "종료" 경로 미검증 | 우클릭 팝업 메뉴 선택을 스크립트로 재현할 수 없어 exe에서 트레이 "종료"를 눌러 보는 검증은 못 했다(pystray가 exe 안에서 로드되어 트레이 윈도를 만드는 것까지는 확인). 사람이 한 번 손으로 확인하면 `_MEI` 정리도 함께 검증된다 |
| 트레이 LAN IP 캐시 | 접속 주소 라벨의 LAN IP는 서버 구동 중 한 번만 조회해 캐시한다(매 폴링마다 소켓을 열 이유가 없어서). Wi-Fi를 바꿔 PC의 IP가 바뀌면 서버를 재시작해야 새 주소가 보인다 |
| 앱 백그라운드 진입 시 드래그 미종료 | 드래그 홀드 중 Android 앱이 백그라운드로 가서 `TrackpadViewModel`이 파기되면 `DRAG_END`를 보낼 기회가 없다 — 서버 heartbeat 타임아웃(≈15초)이 감지해 강제로 놓을 때까지 PC 버튼이 눌린 채 유지됨. 실기기 미검증 |
