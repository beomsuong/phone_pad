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
│       │   ├── model/         TrackpadEvent.kt, ConnectionState.kt
│       │   ├── repository/    TrackpadRepository.kt (interface)
│       │   └── usecase/       SendEventUseCase.kt
│       ├── data/
│       │   ├── network/       TcpClient.kt, UdpClient.kt, SessionHandshake.kt
│       │   └── repository/    TrackpadRepositoryImpl.kt
│       ├── di/                AppModule.kt, DispatcherModule.kt
│       └── presentation/
│           ├── util/          GestureConfig.kt
│           └── trackpad/      TrackpadScreen.kt, TrackpadViewModel.kt, TrackpadUiState.kt,
│                               MultiTouchGestureTracker.kt
└── pc_server/                 ← Windows Python 서버
    ├── server.py
    ├── input_controller.py
    └── tests/                 pytest 단위 테스트
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
| 패키징 | 추후 PyInstaller 단일 exe 고려 |

---

## 4. 통신 프로토콜

### 원칙 (변경 금지)
- **MOVE 이벤트만 UDP**, 나머지(클릭·스크롤·드래그·heartbeat 등) **전부 TCP**
- "이동 좌표인가?" 한 가지 기준으로 채널 결정. 애매하면 TCP.

### 현재 구현 (Phase 2 — 하이브리드, MOVE UDP 분리 완료)

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

// 더블클릭 (Phase 2, 미구현)
{"type":"DOUBLE_CLICK","button":"left"}

// 스크롤 (Phase 2, 미구현)
{"type":"SCROLL","dx":0,"dy":-3}

// 드래그 (Phase 3, 미구현)
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
| 1손가락 더블탭 | DOUBLE_CLICK | Phase 2 | ⬜ 미구현 |
| 2손가락 탭 | CLICK(right) | Phase 2 | ✅ 완료 |
| 2손가락 상하좌우 드래그 | SCROLL(dx, dy) | Phase 2 | ⬜ 미구현 |
| 탭홀드 + 드래그 | DRAG_START → MOVE → DRAG_END | Phase 3 | ⬜ 미구현 |
| 3손가락 스와이프 | 가상 데스크톱 전환 등 | Phase 4 | ⬜ 미구현 |

### 엣지 케이스 (구현 시 주의)
- 드래그 도중 손가락 개수 변화(1→2) → 현재 제스처 취소 후 새 제스처로 재시작 (`MultiTouchGestureTracker`가 구간 단위로 구현)
- 2손가락 탭 종료 시 "동시에 손가락 떼기"는 물리적으로 불가능해서 실제로는 `2→1→0` 순으로 이벤트가 들어옴 → 마지막 구간(짧은 1손가락 꼬리)만 보면 우클릭이 좌클릭으로 뒤집힌다. `MULTI_TOUCH_RELEASE_GRACE_MS`(50ms) 안에 끝난 "개수 감소로 시작된" 짧은 꼬리는 무시하고 직전(더 많은 손가락) 구간 기준으로 판정한다 — 이 값은 반드시 `TAP_MAX_DURATION_MS`보다 충분히 작아야 함(안 그러면 "손가락 하나 떼고 남은 손가락으로 탭"하는 정상 동작까지 삼킴)
- 화면 밖으로 나간 손가락 → pointerInfo 변화 감지 후 DRAG_END 전송

### 감도 상수 (`GestureConfig.kt`)
```kotlin
MOVE_SENSITIVITY       = 1.5f   // 이동 배율
TAP_MAX_DISTANCE_PX    = 20f    // 탭 판정 최대 이동 거리
TAP_MAX_DURATION_MS    = 200L   // 탭 판정 최대 지속 시간
MOVE_MIN_DISTANCE_PX   = 5f     // 커서 이동 최소 거리 (떨림 억제)
SINGLE_POINTER_COUNT   = 1      // 1손가락 구간 판정 기준 (탭 → 좌클릭, MOVE 방출)
DOUBLE_POINTER_COUNT   = 2      // 2손가락 구간 판정 기준 (탭 → 우클릭)
MULTI_TOUCH_RELEASE_GRACE_MS = 50L  // 손가락 어긋나게 떼기 보정 유예 시간
DEFAULT_PORT           = 9000
UDP_PORT               = 9001   // MOVE 전용 UDP 포트
SESSION_HANDSHAKE_TIMEOUT_MS = 3000  // TCP 연결 후 SESSION 줄 대기 최대 시간
HEARTBEAT_INTERVAL_MS  = 5000L  // heartbeat 전송 주기 (서버 HEARTBEAT_INTERVAL_S=5.0과 반드시 동시 갱신)
HEARTBEAT_MISS_LIMIT   = 3      // 연속 미응답 한계 (서버 HEARTBEAT_MISS_LIMIT=3과 반드시 동시 갱신)
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

### 🔶 Phase 2 — 하이브리드 통신 + 추가 제스처 (일부 완료)
- [x] MOVE를 UDP(9001)로 분리, 세션 토큰 기반 매칭
- [x] Python 서버에 UDP 소켓 추가 (TCP 세션과 매핑) — `SessionRegistry` (threading.Lock 보호)
- [x] TCP heartbeat (주기: 5초, 미응답 3회 → 연결 해제) — 카운터 기반, 양쪽 5초 창 × 3회로 판정. Android가 핸드셰이크 이후 TCP를 읽지 않던 공백이 해소되어, 서버가 세션을 회수하면 앱도 `Error`로 전환된다
- [x] Android: `PointerInfo` 기반 멀티터치 제스처 감지 — **기반만 구축, 우클릭/스크롤 연결은 아직**. 손가락 개수 변화 시 진행 중이던 구간을 취소하고 새로 시작(AGENTS.md 섹션 5 엣지 케이스), 1손가락 구간만 MOVE/CLICK 방출, 2손가락 이상은 추적만 하고 아무것도 방출하지 않음
- [x] 2손가락 탭 → 우클릭 — Android 단일 사이드로 완료. 서버는 Phase 1부터 `button != "left"`를 전부 우클릭으로 처리하고 있어 변경 없음. 손가락을 어긋나게 떼는 실기기 특성 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`) 포함
- [ ] 2손가락 드래그 → 스크롤 — `MultiTouchGestureTracker.onPointerEvent()`의 2손가락 분기에서 centroid 델타를 계산은 해두고 버리는 중이라 `GestureDecision`에 `scroll` 필드만 추가하면 되지만, SCROLL은 TCP+정수 스텝(`{"type":"SCROLL","dx":0,"dy":-3}`)이라 px→스텝 변환/잔차 누적 설계와 서버 구현이 함께 필요한 **교차 경계면 작업** — 단일 사이드로 진행하지 말 것. 스크롤 시작 이동 임계값이 `TAP_MAX_DISTANCE_PX`(20px)보다 크면 탭/스크롤 사이 사각지대가 생기므로 함께 설계할 것

**Phase 2 구현 시 핵심 파일:**
- `data/network/TcpClient.kt` — 세션 핸드셰이크 + heartbeat 수신용 `readLine()`/`applyHeartbeatTimeout()` 완료
- `data/network/UdpClient.kt` — 완료
- `data/network/SessionHandshake.kt` — 완료 (세션 라인 파서)
- `data/repository/TrackpadRepositoryImpl.kt` — UDP 채널 분기 + heartbeat sender/watchdog 루프 완료. `TcpClient`는 한 줄 읽기만 제공하고, 루프 자체(전송 주기·미응답 판정)는 이 클래스가 소유하는 책임 분리 구조
- `di/DispatcherModule.kt` — heartbeat 루프용 `@IoDispatcher` 제공(테스트에서 가상 시간 디스패처로 교체 가능)
- `presentation/trackpad/MultiTouchGestureTracker.kt` — 완료(좌/우클릭 판정 포함). Compose에 의존하지 않는 순수 판정기(구간 기반 상태 머신). `TrackpadScreen.kt`는 이 트래커를 호출하는 얇은 어댑터. 스크롤(`GestureDecision`에 필드 추가)이 다음 확장 지점
- `pc_server/server.py` — UDP 소켓 + 세션 매핑 + heartbeat 판정 완료
- `pc_server/input_controller.py` — sub-pixel 잔차 누적 없음(느린 정밀 이동 시 델타 소실) — 별도 이슈로 개선 권장

### ⬜ Phase 3 — 제스처 확장
- [ ] 1손가락 더블탭 → DOUBLE_CLICK (타이머 기반 판정)
- [ ] 탭홀드(200ms↑) + 드래그 → DRAG_START / DRAG_END
  - DRAG 중 이동은 여전히 UDP MOVE 사용
- [ ] 감도 설정 화면 (Android Settings Screen)
- [ ] `GestureConfig`를 DataStore로 영속화

### ⬜ Phase 4 — 완성도
- [ ] PC 트레이 아이콘 (`pystray`) — 연결 상태 표시 + 종료
- [ ] UDP 브로드캐스트 자동 서버 탐색 (수동 IP 입력은 fallback 유지)
- [ ] 재연결 로직 (연결 끊김 감지 → 자동 재시도) — heartbeat가 만드는 `ConnectionState.Error("Heartbeat timeout")`/`Error("Connection lost")`를 재시도 트리거로 사용
- [ ] 예외 처리 강화 (네트워크 오류, 권한 오류 등)
- [ ] PyInstaller로 단일 exe 패키징

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
   |-- TCP: CLICK -------------------------------->  |   ✅ 구현됨
   |-- TCP: SCROLL -------------------------------->  |   (Phase 2 예정, 미구현)
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
python server.py
# → TCP 9000(이벤트+세션 핸드셰이크) / UDP 9001(MOVE 전용) 포트에서 대기
```
**Windows 방화벽:** UDP 9001 인바운드를 허용해야 한다 (TCP 9000만 열려 있으면 커서가 전혀 움직이지 않음 — CLICK은 되는데 MOVE만 안 되면 이 문제일 가능성이 높다).

**트러블슈팅:** 커서가 갑자기 멈추고 앱이 `Heartbeat timeout`/`Connection lost`를 띄우면 TCP 9000 경로(Wi-Fi 절전, 도즈 모드 등으로 heartbeat 전송이 지연되는 경우 포함)를 먼저 의심한다. TCP 세션이 회수되면 이미 전송 중이던 UDP MOVE도 서버가 조용히 무시하므로 함께 멈춘다.

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
| PC 서버 배포 | PyInstaller — Phase 4에서 |
| PIN 인증 | Phase 5 선택 사항 — UDP 세션 토큰이 평문이고 발신 IP도 검증하지 않아 동일 WiFi 내 스푸핑이 가능함. PIN 인증 설계 시 함께 재검토 |
| 다중 기기 연결 | 정책 미정 — 서버는 현재 활성 세션 전부를 동시에 처리 가능한 구조(집합 기반)라, 여러 기기가 동시에 연결하면 전부 커서를 움직일 수 있음 |
| sub-pixel 이동 정밀도 | `InputController`가 정수 반올림만 하고 잔차를 누적하지 않아, 아주 느린 드래그의 미세 델타가 소실될 수 있음 — 별도 이슈로 개선 검토 |
| heartbeat 리셋 비대칭 | 서버는 CLICK 등 어떤 상향 데이터로도 미응답 카운터가 리셋되지만, 서버→클라이언트 하향 트래픽은 ACK뿐이라 Android 쪽은 사실상 ACK만이 유일한 리셋 수단. 한쪽 방향만 끊기는 비대칭 시나리오가 가능하므로 Phase 4 재연결 설계 시 전제로 고려 |
| Android 절전/도즈 환경의 heartbeat | 화면 꺼짐·도즈·Wi-Fi 절전으로 5초 주기 전송이 지연되면 서버가 먼저 15초 타임아웃으로 끊는 오탐 가능성 — 실기기 미검증, Phase 4 재연결/wake lock 검토 시 함께 다룰 것 |
