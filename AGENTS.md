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
│       │   │                   ReconnectPolicy.kt, ConnectionErrorKind.kt,
│       │   │                   ConnectionErrorClassifier.kt, DiscoveredServer.kt, DiscoveryState.kt
│       │   ├── repository/    TrackpadRepository.kt, SettingsRepository.kt, ServerDiscoveryRepository.kt (interface)
│       │   └── usecase/       SendEventUseCase.kt, DiscoverServersUseCase.kt
│       ├── data/
│       │   ├── network/       TcpClient.kt, UdpClient.kt, SessionHandshake.kt,
│       │   │                   DiscoveryProtocol.kt, ServerDiscoveryClient.kt
│       │   └── repository/    TrackpadRepositoryImpl.kt, DataStoreSettingsRepository.kt,
│       │                       ServerDiscoveryRepositoryImpl.kt
│       ├── di/                AppModule.kt, DispatcherModule.kt, DataStoreModule.kt,
│       │                       ReconnectModule.kt
│       └── presentation/
│           ├── util/          GestureConfig.kt, ConnectionErrorMessages.kt, DiscoveryMessages.kt
│           ├── settings/      SettingsScreen.kt, SettingsViewModel.kt, SettingsUiState.kt,
│           │                   ScrollSpeedSlider.kt
│           └── trackpad/      TrackpadScreen.kt, TrackpadViewModel.kt, TrackpadUiState.kt,
│                               MultiTouchGestureTracker.kt, DoubleTapDetector.kt,
│                               DragHoldDetector.kt, ConnectionErrorSection.kt,
│                               ServerDiscoverySection.kt
└── pc_server/                 ← Windows Python 서버
    ├── server.py              소켓/세션/heartbeat + 정지 가능한 ServerRuntime, 콘솔/트레이/창 실행 모드
    ├── gui.py                 tkinter 창 어댑터 (표준 라이브러리, 기본 모드) — 닫으면 트레이로 축소
    ├── gui_state.py           창 순수 로직 (버튼 라벨 등) — tkinter 비의존
    ├── input_controller.py
    ├── discovery.py           UDP 9002 서버 탐색 응답자 (표준 라이브러리만, 기본 비활성 — main()만 켠다)
    ├── tray_status.py         트레이 순수 로직 (연결 수→상태/툴팁, LAN IP 조회) — pystray 비의존
    ├── tray.py                pystray 어댑터 (pystray/Pillow import는 여기에서만, 선택 의존성)
    ├── requirements.txt       pystray, Pillow (트레이 전용 — 없어도 서버는 창 모드로 동작, 트레이만 빠짐)
    ├── single_instance.py     중복 실행 방지 (Windows named mutex `Local\PhonePadServer`)
    ├── logging_setup.py       --noconsole 실행 시 print() → 로그 파일
    ├── phone_pad_server.spec  PyInstaller 빌드 정의 (onefile + windowed)
    ├── build_exe.ps1          저장소 밖 임시 venv 생성 → exe 빌드 스크립트
    └── tests/                 pytest 단위 테스트 (test_single_instance.py, test_logging_setup.py 포함,
                               send_input_stub.py = SendInput 모킹 헬퍼, test_desktop_switch.py, test_discovery.py,
                               fake_tk.py = tkinter 위젯 대역)
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

**세션 핸드셰이크 (✅ PIN 인증 완료, Phase 5 — 순서가 뒤집혔다).** 예전에는 서버가 연결 직후 가장 먼저 말했지만, 이제는 **클라이언트가 먼저 AUTH를 보내야** 서버가 응답한다.
```jsonc
// 클라이언트 → 서버: TCP 연결 직후, 다른 어떤 것보다도 먼저 보내는 한 줄
{"type":"AUTH","pin":"483920"}

// 성공: 서버 → 클라이언트 — 형식은 예전과 완전히 동일
{"type":"SESSION","session":"0123456789abcdef0123456789abcdef"}  // uuid4().hex, 32자리 hex

// 실패(PIN 불일치): 서버 → 클라이언트, 보낸 뒤 즉시 연결을 닫는다
{"type":"AUTH_FAIL","reason":"invalid_pin"}
```
- **AUTH는 인증이 꺼져 있어도(`--no-auth`) 항상 보낸다** — 서버 설정에 따라 와이어 형식이 갈라지면 클라이언트가 미리 알 방법이 없다. 인증이 꺼져 있으면 서버는 `pin` 값과 무관하게(빈 문자열이어도) 통과시키고 SESSION을 보낸다.
- 서버는 첫 줄을 읽을 때 **`AUTH_TIMEOUT_S`(3.0초)** 타임아웃을 건다. 다음은 **아무 응답 없이 조용히 닫는다**(응답할 정보가 없거나 알려주는 것 자체가 무의미): 타임아웃, EOF, 깨진 JSON, dict 아님, `type != "AUTH"`, `pin`이 문자열 아님, **브루트포스 잠금 중인 IP**(아래). `AUTH_FAIL`은 **형식은 맞았는데 PIN 값만 틀렸을 때만** 보낸다 — "조용히 닫기"와 확실히 구분해야 공격자에게 잠금 여부가 새지 않는다. PIN 비교는 `hmac.compare_digest`.
- **브루트포스 방어:** 발신 IP별로 AUTH 실패 횟수를 추적(`pin_auth.AuthAttemptLimiter`, 시간 주입 가능한 순수 클래스, discovery의 `ResponseRateLimiter`와 같은 패턴)해, **60초 안에 5회 실패**하면 그 IP는 남은 시간 동안 새 연결에서 AUTH 줄을 읽지도 않고 즉시 닫는다. **close 전에 큐에 남은 바이트를 짧은 타임아웃(0.05초)으로 최선을 다해 비운다** — 안 비우면 커널이 RST를 보내 클라이언트가 clean EOF(`HANDSHAKE_FAILED`) 대신 `SocketException`(`UNKNOWN`)을 보게 되어 잠금이 형식 오류와 다르게 관측된다(협의 QA F-1로 발견·수정). 인증이 꺼져 있으면 이 추적 자체를 하지 않는다.
- **PIN은 `--pin <code>`(고정, 앞뒤 공백은 서버가 제거)로 지정하거나, 지정하지 않으면 서버가 세션마다 6자리 숫자를 무작위 생성**해 콘솔에 `[Server] PIN for this session: 483920` 한 줄로, 그리고 **트레이 툴팁/메뉴에도** 표시한다(의도된 노출 지점은 이 둘뿐 — 그 외 어떤 로그에도 PIN 값은 남지 않는다). `--pin ""`처럼 빈/공백 PIN은 시작 시 거부한다(그렇지 않으면 "인증 켜짐 + 기대값 빈 문자열"이 되어 정상 PIN을 보내는 앱조차 접속할 수 없고 5회 만에 자기 IP가 잠긴다). `--no-auth`로 인증을 완전히 끌 수 있다(개발용, `--no-tray`/`--no-discovery`/`--allow-multiple`과 같은 선상).
- **PIN은 탐색(UDP 9002 `SERVER` 메시지)에 절대 넣지 않는다** — 탐색은 인증이 없는 채널이라 PIN을 실어 보내면 인증 자체가 무의미해진다.
- Android는 **PIN을 저장하지 않는다**(`hostInput`과 대칭 — `pinInput`은 ViewModel 메모리에만 있다가 화면을 벗어나면 사라진다). 서버가 재시작할 때마다 PIN이 새로 생성되므로, 저장해 봤자 대부분 다음 연결 시점엔 틀린 값이라 영속화가 오히려 해롭다.
- **재연결 루프는 `AUTH_FAILED`를 만나면 백오프를 소진하지 않고 즉시 `Error(kind=AUTH_FAILED)`로 전환한다** — 다른 실패 종류(HEARTBEAT_TIMEOUT/CONNECTION_LOST 등)는 기존처럼 계속 재시도한다. PIN 불일치는 시간이 지난다고 저절로 맞아지는 일시 장애가 아니고, 계속 두드리면 서버의 브루트포스 잠금(60초/5회)을 스스로 유발해 사용자가 올바른 PIN으로 수동 재연결해도 막히기 때문이다.
- SESSION 발급(`SessionRegistry`), heartbeat, 그 이후의 모든 이벤트 처리는 **완전히 무변경**이다 — 바뀐 것은 SESSION을 보내기 **전에** AUTH를 한 단계 거친다는 것뿐이다.

클라이언트는 SESSION 줄을 받아야 `ConnectionState.Connected`로 전환한다 (`SESSION_HANDSHAKE_TIMEOUT_MS` 내 미수신 시 `Error`). 핸드셰이크 직후부터 서버는 해당 소켓에 `HEARTBEAT_INTERVAL_S` 초 `recv` 타임아웃을 걸고, **연속 `HEARTBEAT_MISS_LIMIT`(3)회 동안 상향 데이터가 전혀 없으면(≈15초) 세션을 회수하고 연결을 끊는다.** 여기서 "데이터"는 HEARTBEAT뿐 아니라 CLICK 등 어떤 상향 이벤트든 해당되며, 매번 카운터가 0으로 리셋된다.

**단일 클라이언트 정책 (✅ 완료, Phase 5).** 서버는 활성 TCP 클라이언트를 항상 최대 1개로 유지한다 — 여러 기기 동시 지원은 미정이 아니라 **명시적으로 범위 밖**이다(사용자 결정: 서로 뺏고 뺏는 핑퐁을 피하려고 "여러 기기 공유"가 아니라 "새 기기가 이긴다"를 선택).
```jsonc
// 서버 → 클라이언트: 새 연결이 이 연결을 대체했을 때, 강제로 닫기 직전에 딱 한 번 보낸다
{"type":"SESSION_REPLACED"}
```
- 필드 없음, session 없음 — **연결 유지/전송 계층 전용 메시지**(HEARTBEAT_ACK와 같은 범주, `TrackpadEvent` sealed class 밖. 섹션 9 예외 규칙의 세 번째 사례).
- 밀어내기는 **새 연결이 AUTH를 통과한 순간에만** 일어난다: `take_over` → 기존 연결에 알림 → **닫기 전 수신 큐 드레인**(아래 컨벤션) → `shutdown(SHUT_RDWR)` → `close()` → 그 다음에야 새 연결에 SESSION 발급(반대 순서로 하면 순간적으로 활성 클라이언트가 2개인 창이 생긴다). PIN이 틀렸거나 형식 오류·타임아웃·브루트포스 잠금으로 **AUTH를 통과하지 못한 시도는 기존 연결에 어떤 영향도 주지 않는다**.
- 밀려난 연결은 기존 안전장치를 그대로 탄다 — `handle_client`의 `finally`가 세션 토큰을 회수하고, 드래그가 활성 상태였다면 강제로 놓는다(다른 스레드가 소켓을 닫아도 그 스레드의 `recv()`가 예외로 풀려나와 기존 `except`/`finally` 경로를 그대로 탐, 실측 확인).
- **정책은 TCP 입장만 제한한다** — 밀려난 기기의 UDP MOVE는 세션 토큰이 회수될 때까지(수 ms) 계속 처리된다.
- **이 정책에는 끄는 옵션이 없다**(`--pin`/`--no-auth`/`--no-discovery`/`--no-tray`/`--allow-multiple`과 달리 개발 편의 토글이 아니라 확정된 제품 정책).
- **Android는 이 알림을 받으면 자동 재연결을 시작하지 않고 곧바로 `Error(kind=SESSION_REPLACED)`로 간다** — "Connected였던 세션의 유실은 항상 재연결을 시작한다"는 기존 규칙의 **의도된 예외**(자동 재연결하면 방금 자신을 밀어낸 기기를 다시 밀어내는 핑퐁이 된다). 단, 아주 드문 3자 경합(활성 클라이언트가 되자마자 SESSION을 받기 전에 세 번째 기기에게 다시 밀려나는 경우)으로 인해 **핸드셰이크 도중** `SESSION_REPLACED`를 받는 경우는 `SessionReplacedException`으로 구분해 `HANDSHAKE_FAILED`(엉뚱한 "서버가 아님" 안내)로 새지 않게 하되, 이 경우는 재연결 루프를 중단시키지 않는다(순간적 경합이라 다음 백오프에서 재발할 가능성이 낮음 — `AUTH_FAILED`와 다른 취급).

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

// 가상 데스크톱 전환 — ✅ 구현됨 (Phase 5). 3손가락 수평 스와이프. TCP, session 필드 없음.
// direction은 "전환 결과의 방향"이며 손가락 방향이 아니다 — 손가락 왼쪽 스와이프 → "right",
// 오른쪽 스와이프 → "left"(Windows 정밀 터치패드 관례). 이 뒤집기는 Android의
// MultiTouchGestureTracker 한 곳에서만 하고 서버는 받은 값을 Ctrl+Win+Left/Right로 옮기기만 한다
// (섹션 10 "스크롤 방향 규약"과 같은 원칙 — 두 사이드가 같이 뒤집으면 원위치).
// 서버는 6개 INPUT(Ctrl↓ Win↓ 화살표↓ 화살표↑ Win↑ Ctrl↑)을 SendInput 1회로 원자적으로 보내고,
// 주입이 부분 성공이거나 예외가 나면 Ctrl/Win/화살표 키 업 3개를 best-effort로 한 번 더 보낸다
// (수정 키 고착 방지). direction이 "left"/"right" 소문자 정확 일치가 아니면 서버는 아무 키도
// 보내지 않고 조용히 무시한다(세션 유지).
{"type":"DESKTOP_SWITCH","direction":"left"}
{"type":"DESKTOP_SWITCH","direction":"right"}

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

**UDP 9002 — 서버 자동 탐색 전용** (✅ 구현됨, Phase 4). 연결 **이전** 단계라 이벤트 채널이 아니다 — "이동 좌표만 UDP" 원칙은 이벤트 채널에 대한 것이고, 그래서 9001에 섞지 않고 전용 포트로 분리했다.
```jsonc
// 클라이언트 → 서버: 브로드캐스트 UDP 패킷 1개, 개행 없음 (19바이트)
{"type":"DISCOVER"}
// 서버 → 클라이언트: 요청 발신 주소로 유니캐스트, 패킷 1개, 개행 없음. 키 순서 type→name→port, 압축 JSON
{"type":"SERVER","name":"MY-PC","port":9000}
```
- `name` = PC 호스트명(표시용, **문자 수 64자**로 절단, 비면 `"PC"`). `port` = 서버의 **TCP** 포트(실제 바인딩 값). UDP MOVE 포트는 응답에 없다 — 앱은 `GestureConfig.UDP_PORT`(9001)를 그대로 쓴다(서버가 비표준 UDP 포트로 뜨는 경우는 범위 밖).
- **서버 IP는 응답 본문에 넣지 않는다** — 클라이언트가 응답 패킷의 **발신 주소**를 쓴다(멀티 NIC/VPN에서 서버가 자기 IP를 잘못 추정하는 문제 회피 + 본문 주소 위조 차단). **세션 토큰 등 비밀은 절대 넣지 않는다**(인증 이전 단계, 같은 LAN 누구나 볼 수 있다). 응답으로 세션/입력 상태를 바꾸지 않는다.
- 서버는 정확히 `type == "DISCOVER"`인 JSON 객체에만 응답하고, 깨진 JSON·dict가 아닌 값·다른 type(`"discover"` 소문자 포함)·**256바이트 초과**·비UTF-8은 조용히 무시한다. **발신 IP당 초당 5회**까지만 응답(남용/증폭 방지). `SO_REUSEADDR`를 쓰지 않는다.
- **응답 `name`은 `ensure_ascii` 때문에 비ASCII가 유니코드 escape 시퀀스로 나간다**(실측: 호스트명 `최범서` → 59바이트). 그래서 클라이언트 파서는 escape(서로게이트 쌍 포함)를 해석해야 하고, 수신 상한은 이름 64자 최악값(비BMP 807바이트)을 넘어야 한다 — Android `MAX_RESPONSE_BYTES = 1024`. 파서는 부호 붙은 위조 escape를 거부한다. **`name`이 문자열이 아니거나 `port`가 정수 1..65535가 아니면 앱은 그 서버를 무시한다**(서버는 항상 이 형식을 보낸다).
- 앱은 자기 요청 에코·비IPv4 발신 주소도 무시한다. 한 탐색 = 0/300/600ms에 3회 × 대상 주소(255.255.255.255 + 인터페이스별 서브넷 브로드캐스트) 전송, 1.5초 수신 창, `host:port` 중복 제거, 최대 8개. 대상이 6개 이상이면 서버 응답 제한(5회/초)이 잉여 프로브를 자르지만 서버가 목록에서 사라지는 시나리오는 없다(QA 실측 — 대상 1/2/3/6개에서 응답 3/5/5/5).

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
| 3손가락 좌우 스와이프 | DESKTOP_SWITCH(direction) | Phase 5 | ✅ 완료 |

### 엣지 케이스 (구현 시 주의)
- 드래그 도중 손가락 개수 변화(1→2) → 현재 제스처 취소 후 새 제스처로 재시작 (`MultiTouchGestureTracker`가 구간 단위로 구현)
- **탭홀드 드래그는 손가락 개수가 바뀌면 재무장하지 않는다**: 드래그 홀드 중 두 번째 손가락이 닿으면 즉시 `DRAG_END`로 종료하고, 이후 손가락이 다시 1개로 줄어도 새로 드래그 홀드를 시작하지 않는다. 재무장을 허용하면 2손가락 스크롤 후 손가락을 어긋나게 떼는 `2→1→0` 꼬리(위 항목)의 그 1손가락이 "제자리 유지"로 오인되어 스크롤 직후 드래그가 오발동한다 — 대가로 "2손가락에서 1손가락으로 줄인 뒤 홀드 드래그 시작"은 지원하지 않는다(범위 밖)
- 탭홀드 드래그 중 화면 밖으로 나가거나 제스처가 취소되는 경우 → `DRAG_END` 전송 (Compose `awaitEachGesture`의 종료 경로 전체에서 멱등하게 호출됨)
- 두 손가락을 `DRAG_HOLD_THRESHOLD_MS` 이상 벌려서 내려놓으면(2손가락 탭 판정 직전에 각 손가락이 잠깐 1손가락처럼 보이는 구간에서 승격 조건을 스치듯 만족) 우클릭 직전에 좌클릭이 하나 샐 수 있다 — 스펙상 논리적 귀결이며 영향은 낮음
- 2손가락 탭 종료 시 "동시에 손가락 떼기"는 물리적으로 불가능해서 실제로는 `2→1→0` 순으로 이벤트가 들어옴 → 마지막 구간(짧은 1손가락 꼬리)만 보면 우클릭이 좌클릭으로 뒤집힌다. `MULTI_TOUCH_RELEASE_GRACE_MS`(50ms) 안에 끝난 "개수 감소로 시작된" 짧은 꼬리는 무시하고 직전(더 많은 손가락) 구간 기준으로 판정한다 — 이 값은 반드시 `TAP_MAX_DURATION_MS`보다 충분히 작아야 함(안 그러면 "손가락 하나 떼고 남은 손가락으로 탭"하는 정상 동작까지 삼킴)
- **스크롤 뒤 클릭 오발동 방지**: 2손가락 구간은 `isDrag`(탭 한계 초과) 이후부터만 SCROLL을 방출하며, `isDrag`는 구간 내내 sticky해서 우클릭 배제 조건과 그대로 겹치므로 탭/스크롤 사각지대가 없다. 단, **직전 구간이 드래그(스크롤)였다면 그 뒤에 붙는 어떤 짧은 꼬리도 탭으로 재해석하지 않는다** — 꼬리 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`)의 유예 시간 안이든 밖이든 무조건 클릭 없음. 이게 없으면 "스크롤하고 손을 뗐을 뿐인데 커서 위치가 클릭되는" 사고가 난다
- **더블탭 지연이 모든 1손가락 클릭에 적용됨**: 탭이 끝나도 `DOUBLE_TAP_INTERVAL_MS`(300ms) 동안 즉시 CLICK을 보내지 않고 두 번째 탭을 기다린다 — 더블클릭을 지원하는 이상 피할 수 없는 트레이드오프다. 이 대기 중에 다른 종류의 제스처(드래그/스크롤/우클릭)가 시작되면 대기 중인 클릭을 **취소하지 않고 즉시 내보낸다("flush")** — 취소하면 사용자가 실제로 한 클릭이 사라지고, 그대로 두면 드래그로 커서가 옮겨간 뒤 엉뚱한 위치에서 클릭이 나가거나 우클릭 컨텍스트 메뉴가 뜬 직후 클릭이 도착해 메뉴 항목을 눌러버릴 수 있다. 우클릭 시에는 더블탭 감지기도 함께 리셋해, 우클릭 앞뒤의 무관한 좌탭 두 개가 우연히 더블탭으로 묶이지 않게 한다
- 정확히 `DOUBLE_TAP_INTERVAL_MS` 경계에서 두 번째 탭이 오면(판정은 `System.currentTimeMillis()`, 발사는 코루틴 `delay`라 시간축이 미세하게 다름) 아주 드물게 CLICK과 DOUBLE_CLICK이 둘 다 나갈 수 있음 — 영향이 미미해(실제 창은 한 프레임 수준) 현재는 허용
- **3손가락 래치**: 한 제스처(첫 down ~ 모든 손가락 up) 안에서 포인터가 한 번이라도 3개 이상이 되면, 그 제스처의 나머지 동안 MOVE·SCROLL·클릭(좌/우)이 전부 억제되고 `DESKTOP_SWITCH`만 허용된다. 3손가락을 어긋나게 떼면 `3→2→1→0` 꼬리가 생기는데, 그 꼬리가 `MULTI_TOUCH_RELEASE_GRACE_MS`(50ms) **밖**이면 기존 로직이 "정상 탭"으로 취급해 스와이프 직후 좌/우클릭이 샌다. 래치는 유예 시간 튜닝에 의존하지 않고 이를 원천 차단한다(MOVE/SCROLL/탭 판정 3곳에서 차단). 드래그 홀드는 `DragHoldDetector`가 개수 변화 시 재무장하지 않으므로 추가 장치가 필요 없었다. **대가:** 2손가락 스크롤 중 세 번째 손가락이 스치면 그 제스처의 남은 스크롤이 전부 죽는다
- 데스크톱 전환은 **임계를 넘는 그 프레임에 즉시**(손을 뗄 때가 아니라) 발사되고, **한 구간에 최대 1회**다(계속 밀어도 반복 전환 없음). 3→2→3처럼 구간이 새로 시작되면 다시 1회 가능. 3번째 손가락이 닿는 순간 대기 중인 지연 클릭은 flush하고 더블탭 감지기를 리셋한다
- 3손가락 "탭"(스와이프 없이 뗌)은 아무 이벤트도 없다. 정확히 3손가락인 구간에서만 판정하며 4손가락은 범위 밖(래치 때문에 무이벤트). 수직 스와이프(작업 보기)도 범위 밖

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
THREE_POINTER_COUNT    = 3      // 3손가락 구간 판정 기준 (DESKTOP_SWITCH 전용) + 3손가락 래치 조건(이 개수 이상)
THREE_FINGER_SWIPE_MIN_DISTANCE_PX = 120f  // 구간 시작 centroid 대비 수평 이동 임계 — TAP_MAX_DISTANCE_PX의 6배(전역 동작이라 오발동 여유를 크게). GestureConfigTest가 강제
THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE = 2f  // |dx| >= 2*|dy|일 때만 수평 스와이프로 인정 (약 26.6도 이내)
MULTI_TOUCH_RELEASE_GRACE_MS = 50L  // 손가락 어긋나게 떼기 보정 유예 시간
SCROLL_SENSITIVITY_PX_PER_STEP = 40f  // 휠 1스텝에 해당하는 centroid 이동 거리. 반드시 TAP_MAX_DISTANCE_PX보다 커야 함(사각지대 방지)
DOUBLE_TAP_INTERVAL_MS = 300L    // 두 탭을 하나의 더블탭으로 묶을 최대 간격 (모든 좌클릭이 겪는 지연이기도 함)
DOUBLE_TAP_DISTANCE_PX = 40f     // 두 탭 중심 좌표 사이 최대 허용 거리 (TAP_MAX_DISTANCE_PX의 2배)
DRAG_HOLD_THRESHOLD_MS = TAP_MAX_DURATION_MS  // 탭홀드 드래그 승격까지 제자리 유지해야 하는 시간 — 탭이 아니게 되는 시점과 정확히 일치시켜 사각지대 제거
DEFAULT_PORT           = 9000
UDP_PORT               = 9001   // MOVE 전용 UDP 포트
SESSION_HANDSHAKE_TIMEOUT_MS = 3000  // TCP 연결 후 SESSION 줄 대기 최대 시간
DISCOVERY_PORT = 9002 / DISCOVERY_TIMEOUT_MS = 1500 / DISCOVERY_PROBE_COUNT = 3 / DISCOVERY_PROBE_INTERVAL_MS = 300
DISCOVERY_RECEIVE_POLL_MS = 200 / DISCOVERY_MAX_RESULTS = 8 / DISCOVERY_MAX_NAME_LENGTH = 64
                                    // 불변식은 GestureConfigTest가 강제: (PROBE_COUNT-1)*PROBE_INTERVAL < TIMEOUT-RECEIVE_POLL,
                                    // 0 < RECEIVE_POLL <= PROBE_INTERVAL, TIMEOUT < CONNECT_TIMEOUT_MS
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

### ✅ Phase 4 — 완성도 (완료: 트레이·재연결·패키징·예외 처리·UDP 자동 탐색)
- [x] PC 트레이 아이콘 (`pystray`) — 연결 상태 표시 + 종료. 서버 단일 사이드(와이어 프로토콜 무변경). 아이콘은 Pillow로 코드에서 생성(대기 회색/연결됨 초록), 메뉴는 상태 라벨·접속 주소(LAN IP:9000)·종료. `--no-tray` 옵션과 미설치 시 콘솔 모드 폴백. 정지 가능한 `ServerRuntime`으로 정상 종료 경로를 만들고 `atexit` 드래그 해제 안전장치를 추가해 섹션 10의 "서버 프로세스 강제 종료 시 드래그 상태" 항목을 해소
- [x] UDP 브로드캐스트 자동 서버 탐색 (수동 IP 입력은 fallback 유지) — **교차 경계면**(새 UDP 포트 9002 + 메시지 2종), android-dev/server-dev 병렬 + protocol-qa 검증. 연결 화면의 "서버 찾기" 버튼 → 결과 목록 → 선택하면 IP/포트 입력란만 채운다(**자동 연결 안 함** — 탐색 응답은 인증이 없어 위조 가능). 자세한 설계는 아래 "UDP 자동 탐색 구현 시 핵심 파일/설계"
- [x] 재연결 로직 (연결 끊김 감지 → 자동 재시도) — Android 단일 사이드(프로토콜/서버 무변경: 재연결은 기존 핸드셰이크를 그대로 다시 수행할 뿐이라 서버에는 "새 클라이언트 접속"과 구분되지 않음). **"Connected였던 세션이 유실됐을 때만"** 재시도하며, 첫 `connect()` 실패는 기존처럼 `Error`로 남긴다(틀린 IP에 55초씩 매달리지 않기 위해). 유실은 `Error`를 거치지 않고 곧바로 `ConnectionState.Reconnecting(host, attempt, maxAttempts)`로 가고, 성공하면 `Connected`, 8회 소진 시 `Error("Reconnect failed: <마지막 원인>")`. 재연결 화면에는 "취소" 버튼(= 수동 `disconnect()`)
- [x] 예외 처리 강화 — "범위가 모호한 항목"이라 추측으로 넓히지 않고 **코드 조사로 재현/확인된 결함만** 처리했다. 서버/Android 각각 단일 사이드(와이어 프로토콜 무변경). 서버: `SendInput` 반환값(주입된 이벤트 수)을 확인하지 않아 `_drag_start`/`_drag_end`의 "실패하면 상태를 유지한다"는 주석과 코드(무조건 상태 변경)가 어긋나 있던 버그 수정. Android: `Socket(host, port)`에 연결 타임아웃이 없고 첫 연결에는 "취소"도 없어 틀린 IP에서 OS 기본 타임아웃(수십 초) 동안 갇히던 문제, 오류가 영어 예외 원문으로 노출되던 문제 수정. **다루지 않은 것:** UIPI(관리자 권한 창) 차단 감지(불가능 — 아래), IP 형식 검증, 포트 입력 UI, 앱 백그라운드 진입 시 드래그 종료, 서버 기동 실패 안내창
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

**예외 처리 강화 구현 시 핵심 파일/설계:**
- `pc_server/input_controller.py` — 모든 `SendInput` 호출이 `_send_input(count, inputs, kind) -> bool` 한 곳을 지난다. 반환값(주입된 이벤트 수)이 `count`보다 적으면 실패로 보고 `input_failures` 카운터를 올리고, 종류별(`MOVE`/`SCROLL`/`CLICK`/`DOUBLE_CLICK`/`DRAG_START`/`DRAG_END`) 5초 rate limit으로 ASCII 로그 1줄을 남긴다(예외는 던지지 않음 — 로그 스트림이 닫혀 있어도 입력 처리는 계속). `_drag_start`는 실패 시 `_drag_active`를 올리지 않고, `_drag_end`/`force_release_drag`는 실패 시 True로 남겨 나중에 재시도한다(눌린 적 없는 버튼을 "눌림"으로 기록해 유령 LEFTUP을 쏘던 버그 수정). 시간·로그는 생성자 주입(`monotonic`/`log`/`failure_log_interval`)이라 테스트에서 sleep 없이 검증. 락 순서는 `_drag_lock` → `_failure_lock` 단방향. **ctypes 호출이 던지는 예외는 삼키지 않고 전파**한다(`server.py`의 기존 `except` 경로와 그 테스트의 검증력을 유지하기 위해)
- `pc_server/tests/send_input_stub.py` — `patch_send_input()`(성공 = 요청 개수 반환) / `injected_none` / `injected_partial(n)`. `MagicMock` 기본 반환값은 새 계약에서 "0개 주입"으로 읽혀 실패로 판정되므로 기존 patch 45곳을 전부 이걸로 교체했다(호출 횟수·플래그 시퀀스 단언은 약화 없음). `_send_input`의 반환값 검사를 무력화하면 16개 테스트가 실패함을 변이 검사로 확인
- `presentation/util/GestureConfig.kt` — `CONNECT_TIMEOUT_MS`(5초)는 `SESSION_HANDSHAKE_TIMEOUT_MS`(3초)와 **직렬**로 붙어 최악 8초. 0은 "무한 대기"라 금지(`GestureConfigTest`가 고정)
- `data/network/TcpClient.kt` — `Socket()` + `connect(InetSocketAddress, timeout)`. 소켓을 **연결 시도 전에** 필드에 등록하는 것이 취소 설계의 전제(블로킹 `connect()`는 코루틴 취소로 풀리지 않아 소켓 close가 유일한 수단). 테스트 주입점 `connectTimeoutMs`/`socketFactory`(`internal`)는 실제 5초를 기다리는 테스트를 만들지 않기 위한 것이며 프로덕션 코드는 건드리지 않는다
- `data/repository/TrackpadRepositoryImpl.kt` — 경합 장치가 **5종**: `connectionMutex` + `generation`(연결 단위) + `reconnectEpoch`(재시도 묶음) + `reconnectJob.cancel()` + **`connectEpoch`(수동 시도 단위, 신규)**. `connectEpoch`는 수동 `connect`/`cancelConnect`/`disconnect`만 올리고 **재연결 루프는 절대 올리지 않는다**(올리면 락을 기다리던 사용자의 수동 연결이 무효화된다). `cancelConnect()`는 `Connecting`일 때만 동작하며 전부 뮤텍스 **밖**에서 "무효화 → 상태 되돌림(Disconnected, Error 아님) → 소켓 close" 순서로 한다. `openConnection`은 `ConnectOutcome`(Success/**Cancelled**/Failure)을 돌려줘 실패와 취소를 구분 — 뒤늦게 도착한 성공/실패가 취소된 상태를 덮어쓰지 않는다
- `domain/model/ConnectionErrorKind.kt` / `ConnectionErrorClassifier.kt` / `presentation/util/ConnectionErrorMessages.kt` — **내부 진단 문자열과 사용자 문구를 분리한다.** `ConnectionState.Error.message`는 진단 계약(기존 리터럴 유지)이고 한국어 변환은 `kind`를 보고 표시 계층에서만 한다. 분류기는 예외 타입 → 메시지 키워드 → `ConnectException`=거부 폴백 순, 원인 체인 깊이 5. `kind`는 기본값 `UNKNOWN`이라 1-인자 생성이 가능하지만 동등성에는 포함된다. 재연결 소진은 `RECONNECT_FAILED`, heartbeat 타임아웃은 `HEARTBEAT_TIMEOUT`
- `presentation/trackpad/ConnectionErrorSection.kt` — 오류 표시 전용 컴포저블. `TrackpadScreen` 변경 면적을 줄이기 위한 분리(연결 화면을 동시에 손보는 작업과의 충돌 완화). `TrackpadScreen`은 첫 연결 중 "취소" 버튼 배선만 추가
- **QA 생략 근거:** 와이어 프로토콜이 무변경이고 양쪽이 서로의 코드를 소비하지 않아 `protocol-qa`를 돌리지 않았다. 대신 리더가 두 결과를 직접 재실행(server 246 passed/1 skipped, Android `cleanTestDebugUnitTest` 258건 0 실패)하고 취소 로직·분류기·문구를 코드로 검토

**3손가락 스와이프 구현 시 핵심 파일/설계:**
- `presentation/trackpad/MultiTouchGestureTracker.kt` — 방향 매핑(손가락 왼쪽 → `"right"`)이 `resolveDesktopSwitch()` **한 곳에만** 있다(`DIRECTION_LEFT`/`DIRECTION_RIGHT` 상수). 서버·ViewModel·Screen·Repository는 값을 그대로 전달만 한다. 판정은 구간 단위(`desktopSwitchEmittedInSegment`), 래치는 제스처 단위(`threeFingerLatched`). `GestureDecision.desktopSwitch`는 `move`/`scroll`과 구조적으로 동시에 non-null이 될 수 없다(정확히 3손가락 구간 = 정의상 래치 상태). 기존 1·2손가락 트래커 테스트 36개는 무수정 통과
- `data/repository/TrackpadRepositoryImpl.kt` — `DesktopSwitch`는 CLICK/DOUBLE_CLICK과 같은 등급: 전송 실패가 `sendOverTcp()` → `reportConnectionLost`로 합류(MOVE/SCROLL처럼 조용히 버리지 않음). 와이어 리터럴 고정 테스트 있음
- `pc_server/input_controller.py` — `KEYBDINPUT`을 `_INPUTunion`에 추가(`KEYBDINPUT` 24B < `MOUSEINPUT` 32B라 `sizeof(INPUT)`=40 불변, 테스트로 고정). `_desktop_switch()`는 6개 INPUT을 `_send_input` 창구로 1회 전송. 화살표만 `KEYEVENTF_EXTENDEDKEY`. **수정 키 고착 방지 정리(`_release_desktop_switch_keys`)는 반환값 분기가 아니라 `try/finally`로 보장한다** — QA F-1: `_send_input`이 예외를 던지는 경로(SendInput 자체의 `OSError`, 또는 부분 주입 직후 실패 기록 안의 예외)에서 정리가 건너뛰어져 실제로 Ctrl+Win이 눌린 채 남을 수 있었다(리더가 수정 + 회귀 테스트 2건). 정리 호출 자체의 예외는 삼키고 재귀/재시도는 하지 않는다
- 테스트 함정: 실제 키 주입 금지 — 부분 주입 시 Win 키 고착이나 테스트 중 데스크톱 전환이 일어난다. 전부 `patch_send_input()`으로 모킹하고, 모킹으로 검증 불가능한 "OS가 KEYBDINPUT 구조체를 읽는가"만 무해한 키 업 단독 호출로 실측했다(`VK_LCONTROL` 키 업 1개 → `injected 1 of 1`, 틀린 cbSize → `0 of 1`)
- 병렬 작업 함정: 에이전트가 검증 중 `git stash`를 써서 병렬 에이전트의 미커밋 작업까지 함께 stash된 사고가 있었다(즉시 pop해 복구). 병렬 실행 중에는 stash 금지를 프롬프트에 명시할 것

**UDP 자동 탐색 구현 시 핵심 파일/설계:**
- `pc_server/discovery.py` — 표준 라이브러리만. 순수 함수 `handle_discovery_packet(data, name, tcp_port) -> bytes | None`(소켓 비의존), `ResponseRateLimiter`(시간 주입 — sleep 없이 결정적 테스트, 발신자 테이블 상한), `DiscoveryResponder`(`0.0.0.0:9002` 데몬 스레드, 0.5s 폴링 + stop Event, 포트 0 바인딩 지원). **9002 바인딩 실패는 서버 기동을 막지 않는다**(ASCII 로그 `[!] Discovery disabled: ...` 후 탐색만 꺼짐 — 수동 IP 입력이 fallback). 수신 중 예외는 스레드를 죽이지 않는다. **Windows `recvfrom`은 정상 동작 중에도 `OSError`를 던진다**(`WSAEMSGSIZE` = 버퍼보다 큰 데이터그램, `WSAECONNRESET` = 응답 상대의 ICMP unreachable) — 그래서 "로그(throttle) + 계속, 연속 50회면 탐색만 포기"로 만들었다(`except OSError: break`면 리스너가 조용히 죽는다). 수신 버퍼(2048)는 요청 상한(256)보다 커야 초과 패킷 검사가 성립한다
- `pc_server/server.py` — 변경 +34/-2. `ServerRuntime(discovery_port=None)` **기본 비활성**(기본값이 실포트를 잡으면 서버가 떠 있는 동안 `ServerRuntime`을 만드는 무관한 테스트가 깨진다 — 트레이/단일 인스턴스 때와 같은 교훈), `serve()`에서 시작·`stop()`/`close()`에서 정지, `main()`만 `DISCOVERY_PORT`를 넘긴다, `--no-discovery`. `handle_client`/`udp_listener`/`handle_udp_packet`/`SessionRegistry`/소켓 옵션은 무변경
- `data/network/DiscoveryProtocol.kt` — 순수 파서. 어떤 입력에도 예외 없음. **서버 주소의 유일한 출처는 발신 주소**(본문의 `host`/`ip`는 무시 — 테스트로 고정). 정규식 기반 JSON 값 추출 + 직접 만든 unescape(서로게이트 쌍·따옴표/역슬래시·잘린/알 수 없는 escape·부호 붙은 hex 거부). `ServerDiscoveryClient`는 소켓/시간/대상 주소/포트/창을 `internal var`로 주입 가능(실제 1.5초를 기다리는 테스트 없음, 루프백 왕복 소수만). 블로킹 `receive`는 코루틴 취소로 안 풀리므로 **200ms 폴링 루프 + 매 회 `isActive` 확인**으로 빠져나온다(취소 후 최대 200ms 소켓이 살아 있음 — `TcpClient.connect()`의 소켓 close 방식과 달리 루프가 짧아 닫기 경쟁을 안 만들려는 선택)
- `TrackpadViewModel.startDiscovery/selectServer` — 탐색 중 재호출은 무시(버튼도 비활성), 선택하면 `hostInput`·`port`만 채운다, 사용자가 호스트를 직접 고치면 `port`가 기본값으로 돌아간다(선택한 서버의 비표준 포트가 다른 IP로 새는 것 방지), 연결 시작 시 진행 중 탐색 취소. 화면은 `ServerDiscoverySection.kt`로 분리(`TrackpadScreen.kt` +27줄, 제스처 코드 무접촉). 문구는 `DiscoveryMessages`(모든 상태가 문구를 갖는 계약을 테스트로 고정)
- 함정: ① `runCatching`이 suspend 호출의 `CancellationException`을 삼켜 **연결 시작으로 취소한 탐색이 "서버를 찾지 못했습니다"로 표시**됐다(신규 ViewModel 테스트가 잡음) → `try/catch(CancellationException){throw}` ② **KDoc·주석에 유니코드 escape 리터럴을 쓰면 kapt Java 스텁 주석으로 복사되어 `illegal unicode escape`로 `:app:kaptDebugKotlin`이 깨진다**(실측 — 코드/문자열 리터럴은 무해) ③ `runTest {}` 안에서 새 `StandardTestDispatcher`를 만들면 `Detected use of different schedulers`로 무더기 실패 → 디스패처를 필드로 두고 `runTest(dispatcher)` ④ 루프백 실소켓 테스트에서 닫힌 포트로 보내면 Windows ICMP unreachable이 다음 `recv`를 깨워 "창이 끝날 때까지 기다림"을 측정할 수 없다(받기만 하고 답하지 않는 소켓으로 재현)
- **QA 발견(리더가 수정):** F-1 — 서버가 이름을 문자 수 64자로 자르지만 `ensure_ascii` escape로 바이트가 커져 비BMP 이름은 예전 상한 512에서 수신 버퍼가 잘려 서버가 목록에서 사라질 수 있었다(Windows 컴퓨터 이름은 15자·제한 문자라 실기기 도달 불가지만 계약이 안 닫혀 있었음) → `MAX_RESPONSE_BYTES = 1024` + 최악값(비BMP 64자, 807B) 회귀 테스트. W-1 — `toIntOrNull(16)`이 부호를 허용해 위조 escape가 엉뚱한 문자가 됐다 → hex 자릿수 검사 + 테스트

**PIN 인증 구현 시 핵심 파일/설계:**
- **파괴적 변경 경고가 실제로 옳았다** — TCP 핸드셰이크 첫 줄이 바뀌므로 기존에 handshake를 흉내 내던 테스트 전부가 깨졌다. 서버는 `test_server_drag.py`/`test_server_shutdown.py`/`test_desktop_switch.py`뿐 아니라 **요청 목록에 없던 `test_discovery.py`의 실소켓 핸드셰이크**까지 grep으로 찾아 고쳤다(목록만 믿었으면 놓쳤을 1건) — **와이어 순서가 바뀌는 변경은 항상 grep으로 전수 재확인할 것**, 새 테스트 추가만으로는 부족하다.
- `pc_server/pin_auth.py` — `generate_pin()`(secrets, 6자리 0-패딩), `parse_auth_message()`(bytes/str/dict 전부 받고 무예외), `pins_match()`(`hmac.compare_digest`), `AuthAttemptLimiter`(시간 주입, 60초/5회, discovery의 `ResponseRateLimiter`와 같은 패턴)
- `pc_server/tests/fake_conn.py` — 파괴적 변경을 한 파일에서 흡수하는 **공용 `FakeConn`**(AUTH 줄을 자동 선발송, `recv_calls`/`timeouts`는 SESSION 이후만 셈). 여러 테스트 파일에 흩어진 가짜 소켓 헬퍼가 있다면 **이런 공용화가 파괴적 변경 대응 비용을 크게 줄인다**
- `pc_server/server.py` — `authenticate_client()`가 `read_auth_line()`(AUTH_TIMEOUT_S 3.0초) → `pin_auth`로 검증 → `(통과 여부, leftover)` 반환. `handle_client(..., expected_pin=None, auth_limiter=None)` **기본값은 인증 없음**(discovery_port 때와 같은 교훈 — 세 번째로 겪음). `main()`만 `resolve_expected_pin()`으로 실제 값을 결정해 넘긴다. 잠금 close는 **`recv()` 1회(0.05초 타임아웃)로 큐를 비운 뒤** 닫는다(QA F-1 — 안 비우면 RST가 나가 잠금이 형식 오류와 다르게 관측된다). `--pin`은 `strip()` 후 빈 문자열이면 `SystemExit`(QA F-2 — 안 그러면 "인증 켜짐 + 기대값 빈 문자열"이 되어 아무도 접속 못 하고 자기 IP가 잠긴다). Android의 `trim()`과 맞추기 위한 `strip()`이기도 하다.
- `pc_server/tray_status.py`/`tray.py` — 툴팁 끝에 `" - PIN: {pin}"`(인증 켜져 있을 때만), 메뉴에 표시 전용 PIN 항목 추가.
- `data/network/AuthHandshake.kt` — AUTH 줄 생성(`"`/`\`/제어문자 이스케이프, 32자 절단)과 `AUTH_FAIL` 판별 순수 object. `TcpClient.connect(host, port, pin)`이 소켓 성공 직후 **다른 무엇보다 먼저** 이 줄을 전송, 응답이 `AUTH_FAIL`이면 `AuthFailedException`을 던져 일반 handshake 실패와 구분한다.
- `domain/model/ConnectionErrorKind.AUTH_FAILED` — `ConnectionErrorClassifier`가 `AuthFailedException`을 최우선으로 매핑, `ConnectionErrorMessages`에 한국어 문구. `TrackpadRepositoryImpl`의 재연결 루프(`startReconnect()`)는 `outcome.kind == AUTH_FAILED`일 때만 `attempt += 1`을 건너뛰고 즉시 `Error`로 전환(다른 실패 종류는 기존 백오프 그대로) — 변이 검사로 이 분기가 정확히 1개 테스트로만 고정됨을 확인.
- `TrackpadViewModel`/`TrackpadUiState`/`TrackpadScreen` — `hostInput`과 대칭인 `pinInput`(영속화 없음), 연결 버튼은 둘 다 비어 있지 않아야 활성화. 서버 탐색으로 서버를 선택해도 PIN은 채워지지 않는다(탐색 응답에 없으므로).
- **QA 발견(리더가 수정) — F-1:** 잠금 close가 미판독 바이트를 남겨 RST가 나가던 것을 위 `recv()` 드레인으로 수정 + 실소켓 회귀 테스트(`test_real_socket_lockout_closes_cleanly_not_with_a_reset`, JVM 클라이언트로 `SocketException` 대신 clean EOF임을 실측). **F-2:** `--pin ""`을 그대로 기대값으로 쓰던 것을 시작 시 거부로 수정 + `strip()` 추가, 회귀 테스트 2건. **W-3(문서 정정):** PIN 노출 지점은 콘솔 한 줄뿐이 아니라 **트레이도 포함해 둘**이라고 바로잡음(서버 코드 주석도 함께 수정).
- 확인된 강점: 인증 OFF/ON 양쪽에서 기존 이벤트(CLICK/SCROLL/DRAG/DESKTOP_SWITCH/UDP MOVE/DISCOVER) 전부 회귀 없음(protocol-qa 실측). PIN에 `"`나 개행이 섞인 입력도 이스케이프되어 한 줄 유효 JSON이 되므로 이벤트 주입/줄 분할 불가.
- 미해결(섹션 10 참조): 실기기 미검증, 잠금 카운트는 프로세스 메모리 전용(재시작 시 리셋), NAT 공유 IP는 같이 잠기고 IP를 바꾸는 공격자는 우회 가능(100만 조합 + 3초 타임아웃이 실질 방어선), UDP 세션 토큰 평문 스푸핑은 이번 PIN 인증으로 해결되지 않음(별도 이슈), `--allow-multiple`로 서버를 두 개 띄우면 PIN도 두 개.

**단일 클라이언트 정책 구현 시 핵심 파일/설계:**
- `pc_server/single_client.py` — `SingleClientGuard`(락 + 현재 활성 `(conn, addr, session)`, `take_over()`는 **락 밖에서** I/O하도록 밀려난 연결 정보만 반환, `release(conn)`은 identity 비교로 이미 밀려난 연결의 뒤늦은 정리가 새 활성 클라이언트를 지우지 않게 함). `handle_client(..., guard=None)`/`ServerRuntime(..., single_client_guard=None)` **기본값은 비활성**(discovery_port/auth_limiter와 같은 세 번째 사례), `main()`만 항상 생성해 넘긴다(끄는 CLI 옵션 없음).
- **스펙 이탈(server-dev, 실측 근거로 승인):** 스펙은 `shutdown(SHUT_RDWR)` → `close()`만 요구했지만 **그것만으로는 RST를 막지 못함을 실측**(`shutdown_close` 경로도 `RST 10054`) — 미판독 바이트가 남아 있으면 커널이 RST를 보내 **이미 도착해 있던 `SESSION_REPLACED`까지 클라이언트 버퍼에서 사라진다**(PIN 브루트포스 잠금 F-1과 같은 함정). `shutdown` **앞에** 짧은 예산(0.1초/1MiB)의 recv 드레인을 추가해 clean FIN을 보장했다. **컨벤션 승격(두 번째 사례):** 마지막 줄을 보내고 닫는 모든 경로는 close 전에 수신 큐를 비운다 — `shutdown(SHUT_RDWR)`만으로는 부족하다.
- `data/network/SessionReplacedNotice.kt` — `SessionHandshake`/`AuthHandshake`와 같은 스타일의 가벼운 정규식 판별기. `TrackpadRepositoryImpl.heartbeatWatchdogLoop()`가 읽은 줄이 이거면(그 외 모든 줄은 기존처럼 카운터만 리셋) 카운터를 리셋하지 않고 곧바로 `reportConnectionLost(kind=SESSION_REPLACED)`. `reportConnectionLost()`는 이 kind일 때 재연결 정책을 확인하지 않고 곧바로 `Error`로 간다(기존 "Connected 유실 → 항상 재연결" 규칙의 의도된 예외 — 핑퐁 방지).
- `domain/model/SessionReplacedException.kt` — `TcpClient.connect()`가 핸드셰이크 응답으로 `SESSION_REPLACED`를 받으면(protocol-qa W-1: 활성 클라이언트가 되자마자 SESSION을 받기 전에 세 번째 기기에게 다시 밀려나는 극히 드문 3자 경합) 이 예외를 던져 `HANDSHAKE_FAILED`(엉뚱한 "서버가 아님" 안내)와 구분한다. `AuthFailedException`과 같은 자리에서 `ConnectionErrorClassifier`가 타입 최우선으로 매핑. 이 경우는 (AUTH_FAILED와 달리) 재연결 루프를 중단시키지 않는다 — 순간적 경합이라 다음 백오프에서 재발할 가능성이 낮기 때문(리더가 QA 발견 직후 직접 수정 + 테스트 4건 추가).
- **QA 실측 함정 주의:** 실소켓으로 이 기능을 테스트할 때 진짜 `InputController`를 쓰면 서버가 실제로 SendInput을 실행해 **개발 PC의 마우스가 실제로 클릭/이동한다**(server-dev·QA 둘 다 한 번씩 겪음) — 반드시 대역(stub)으로 구동할 것.
- 부수 효과: 재연결한 앱이 자기 자신의 유령 세션을 즉시 밀어내므로, 섹션 10의 "재연결 직후 서버 세션 2개" 창이 최대 15초에서 수 ms로 줄었다(대부분 해소, 원리적 창은 잔존).

### ⬜ Phase 5 — 선택 확장
- [x] PIN 코드 인증 (TCP 핸드셰이크 단계에 추가) — **교차 경계면**(파괴적 변경: 핸드셰이크 순서 역전), android-dev/server-dev 병렬 + protocol-qa 검증. 기본 켜짐(사용자 결정) — 서버가 세션마다 랜덤 6자리 PIN 생성, 앱 연결 화면에 PIN 입력란 필수. 자세한 설계는 아래 "PIN 인증 구현 시 핵심 파일/설계"
- [x] 3손가락 스와이프 → 가상 데스크톱 전환 — **교차 경계면**(새 이벤트 `DESKTOP_SWITCH`), android-dev/server-dev 병렬 + protocol-qa 검증. 좌/우만(수직 스와이프·4손가락은 범위 밖). 자세한 설계는 아래 "3손가락 스와이프 구현 시 핵심 파일/설계"
- [x] 다중 클라이언트 지원 정책 결정 — **교차 경계면**(새 알림 메시지 `SESSION_REPLACED`), 사용자에게 AskUserQuestion으로 확인해 **"단일 클라이언트로 제한(새 연결이 기존 연결을 끊음)"**을 선택받아 진행. android-dev/server-dev 병렬 + protocol-qa 검증. 자세한 설계는 아래 "단일 클라이언트 정책 구현 시 핵심 파일/설계"

---

## 7. 세션 흐름

```
Android                                          PC Server
   |                                                 |
   |-- UDP 9002 broadcast {type:DISCOVER} ------->   |   ✅ 구현됨 ("서버 찾기" 버튼, 수동 IP 입력은 fallback)
   |<-- UDP 9002 unicast {type:SERVER,name,port} -   |   ✅ 구현됨 (서버 주소 = 응답 발신 주소)
   |                                                 |
   |-- TCP connect ------------------------------>   |
   |-- TCP: {"type":"AUTH","pin":"483920"} ------->   |   ✅ 구현됨 (다른 무엇보다 먼저, 인증 꺼도 보냄)
   |<-- {"type":"SESSION","session":"<32hex>"} ---   |   ✅ 구현됨 (PIN 일치 시) — 불일치 시 AUTH_FAIL 후 종료
   |                                                 |
   |-- TCP: CLICK (탭 종료 후 300ms 지연) --------->  |   ✅ 구현됨
   |-- TCP: DOUBLE_CLICK --------------------------->  |   ✅ 구현됨 (CLICK 2개 대신 1개, 커서 이동 없음)
   |-- TCP: SCROLL -------------------------------->  |   ✅ 구현됨 (정수 스텝, session 없음)
   |-- TCP: DRAG_START (제자리 200ms 유지 시) ------>  |   ✅ 구현됨 (버튼을 누른 채 유지)
   |-- UDP: MOVE (버튼 눌린 채로 커서만 이동) ------>  |   기존 MOVE 채널 그대로 재사용
   |-- TCP: DESKTOP_SWITCH (3손가락 좌우 스와이프) ->  |   ✅ 구현됨 (Ctrl+Win+Left/Right)
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
python server.py                  # 창(PIN·접속 주소·연결 상태 + 시작/정지/종료 버튼) + 트레이 아이콘
python server.py --no-tray        # 그래픽 UI(창+트레이) 전부 끄고 콘솔 모드 (Ctrl+C로 종료)
python server.py --no-discovery   # UDP 9002 자동 탐색 응답 끄기 (수동 IP 입력만)
python server.py --pin 123456     # 무작위 생성 대신 고정 PIN 사용
python server.py --no-auth        # PIN 인증 완전히 끄기 (개발/디버깅용)
# → TCP 9000(이벤트+세션 핸드셰이크+PIN 인증) / UDP 9001(MOVE 전용) / UDP 9002(서버 탐색) 포트에서 대기

# 단일 exe 빌드 — 임시 venv를 저장소 밖에 만든다(전역 Python은 건드리지 않음)
./build_exe.ps1                   # → dist/PhonePadServer.exe (약 15.6MB, 빌드 약 40초)
```
**서버 창(기본 모드, ✅ 완료).** `python server.py`(또는 옵션 없이 실행한 exe)는 이제 tkinter 창이 먼저 뜬다 — PIN·접속 주소·연결 상태를 보여주고 **시작/정지** 토글 버튼(포트를 열고 닫는다 — 새로 "시작"할 때마다 PIN도 새로 생긴다)과 **종료** 버튼이 있다. 창을 X로 닫으면 **종료가 아니라 트레이로 숨는다**(pystray/Pillow가 있을 때만 — 없으면 X가 곧 종료, 콘솔에 경고 한 줄). 트레이 메뉴의 "창 열기"로 다시 연다. **그래픽 UI 폴백 사슬**: 창+트레이 → (tkinter 없으면) 트레이만 → (pystray/Pillow도 없으면) 콘솔 — `--no-tray`는 이 사슬 전체를 건너뛰고 곧장 콘솔로 간다(플래그 이름은 그대로 두고 의미만 "그래픽 UI 전부 끄기"로 넓혔다).
**exe 실행:** 더블클릭하면 windowed(`--noconsole`)라 콘솔 창 없이 서버 창(+트레이 아이콘)이 뜬다. 그래서 `print()` 로그는 **`%LOCALAPPDATA%\PhonePad\server.log`** 로 간다(줄 단위 flush, 1MB를 넘으면 시작 시 `server.log.1`로 1회 회전). 빌드 산출물(`build/`, `dist/`)은 커밋하지 않는다.
**PIN 인증(기본 켜짐):** 서버를 실행하면 콘솔에 `[Server] PIN for this session: 483920`이 한 번 출력되고 트레이 툴팁/메뉴에도 같은 값이 보인다. 앱 연결 화면의 PIN 입력란에 이 값을 그대로 입력해야 한다(수동 확인 — 탐색으로 서버를 찾아도 PIN은 자동으로 채워지지 않는다). 서버가 재시작되면 PIN도 새로 바뀐다.
**트레이 모드:** 아이콘 색이 상태를 보여준다(회색 = 대기 중, 초록 = 연결됨). 툴팁은 `Phone Pad - 연결됨 (N대) - PIN: 483920`, 메뉴에는 앱에 입력할 **접속 주소(`PC의 LAN IP:9000`)** 와 **PIN**이 표시되며 **"종료"** 로 끈다. `pystray`/`Pillow`가 설치돼 있지 않으면 경고 한 줄을 출력하고 자동으로 콘솔 모드로 동작한다(서버 기능은 트레이 의존성에 막히지 않는다). 서버가 예외로 죽으면(포트 바인드 실패 등) 트레이도 함께 내려가 프로세스가 종료 코드 1로 끝난다 — 아이콘만 남는 좀비는 생기지 않는다.
**서버를 두 번 실행하면 자동으로 차단된다:** Windows에서는 `SO_REUSEADDR` 때문에 이미 점유된 포트에도 bind가 성공해 예전에는 트레이 아이콘이 2개 뜨고 한쪽만 트래픽을 받았다. 이제 두 번째 프로세스가 named mutex(`Local\PhonePadServer`)로 이를 감지해 "이미 실행 중입니다" 안내창(windowed) 또는 stderr 한 줄(콘솔)을 띄우고 **종료 코드 2**로 끝난다 — 첫 인스턴스는 영향받지 않는다. 개발 중 일부러 두 개를 띄우려면 `--allow-multiple`. 단 다른 로그인 세션에서 띄운 서버는 감지하지 못한다(아래 섹션 10 참조).
**서버 '정지' 버튼과 이미 붙어 있던 폰:** '정지'를 누르면 리슨/UDP 소켓만 닫는 것으로는 부족하다 — 이미 연결된 폰은 heartbeat로 계속 살아있는 척해서 CLICK/DRAG가 계속 실행될 수 있었다(구멍이었다가 이번에 발견·수정됨). 이제 '정지'는 현재 연결도 clean EOF로 끊는다(`SESSION_REPLACED`는 안 보낸다 — 그러면 앱이 재연결을 포기한다). 폰은 평소의 연결 유실처럼 보고 자동 재연결을 시도하며, '시작'을 다시 누르면 붙는다.
**Windows 방화벽(exe):** `python.exe`로 허용해 둔 기존 규칙은 `PhonePadServer.exe`에 적용되지 않으므로 exe로 처음 실행하면 새 방화벽 프롬프트가 뜰 수 있다(미검증 — 이 환경에서 확인 불가). 허용 대상은 아래와 같다.

**Windows 방화벽:** UDP 9001 인바운드를 허용해야 한다 (TCP 9000만 열려 있으면 커서가 전혀 움직이지 않음 — CLICK은 되는데 MOVE만 안 되면 이 문제일 가능성이 높다). **자동 탐색을 쓰려면 UDP 9002 인바운드도 허용해야 한다**(안 열려 있으면 "서버 찾기"만 실패하고 수동 IP 연결은 그대로 동작한다).

**트러블슈팅:** 커서가 갑자기 멈추고 앱이 `Heartbeat timeout`/`Connection lost`를 띄우면 TCP 9000 경로(Wi-Fi 절전, 도즈 모드 등으로 heartbeat 전송이 지연되는 경우 포함)를 먼저 의심한다. TCP 세션이 회수되면 이미 전송 중이던 UDP MOVE도 서버가 조용히 무시하므로 함께 멈춘다.

PC 마우스 왼쪽 버튼이 눌린 채로 멈춰 있다면(뭘 클릭해도 계속 드래그처럼 동작) 탭홀드 드래그의 `DRAG_END`가 유실된 상태다 — 앱을 재연결하면 TCP 연결이 다시 맺어지면서 서버가 이전 연결 종료 시 강제로 버튼을 놓으므로 대부분 자연히 해소된다. 서버를 트레이 "종료"·Ctrl+C·예외 종료 등 인터프리터가 정상적으로 끝나는 경로로 껐다면 종료 시 드래그가 강제로 해제된다(`atexit` 안전장치). 작업 관리자로 프로세스를 강제로 죽였다면(`taskkill /F` 등 인터프리터가 정리 기회를 못 얻는 경로) 이 안전장치도 동작하지 않으므로 수동으로 마우스 좌클릭을 한 번 눌러 버튼 상태를 풀어야 할 수 있다.

### Android 앱
1. Android Studio에서 `phone_pad_app/` 열기
2. 빌드 후 기기에 설치
3. 앱 실행 → PC IP와 **PC 화면(콘솔/트레이)에 표시된 PIN** 입력 → 연결 (AUTH 통과 후 세션 토큰을 받아야 Connected로 전환됨. PIN이 틀리면 재시도 없이 바로 오류)

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
- **`pc_server`에서 `SendInput`을 직접 호출하지 않는다** — 반드시 `InputController._send_input(count, inputs, kind)`를 경유한다(반환값 검사·실패 카운터·rate limit 로그가 이 한 곳에 있다). 테스트에서 `SendInput`을 모킹할 때는 `tests/send_input_stub.py`의 `patch_send_input()`을 쓴다(`MagicMock` 기본 반환값은 "0개 주입"으로 읽혀 실패로 판정된다)
- **사용자에게 보이는 오류 문구와 내부 상태 문자열을 섞지 않는다.** `ConnectionState.Error.message`는 진단용 계약이고, 한국어 문구는 `ConnectionErrorKind` → `ConnectionErrorMessages` 경로로 표시 계층에서만 만든다. 문구 전문을 테스트로 고정하지 말고(다듬을 수 있어야 한다) "원문이 주 메시지를 점령하지 않는다", "모든 kind가 문구를 갖는다" 같은 **계약**을 고정한다
- **`ctypes.sizeof(INPUT)`은 `SendInput`의 cbSize 인자이므로 절대 변해서는 안 된다.** `_INPUTunion`에 새 구조체를 추가할 때는 그 구조체가 `MOUSEINPUT`보다 작은지 확인하고 크기를 고정하는 테스트를 함께 둔다. 이 값이 틀리면 키보드뿐 아니라 마우스 주입까지 전부 실패하며(실측: 틀린 cbSize → `injected 0 of 1`) 예외가 나지 않아 조용히 죽는다
- **키보드 수정 키(Ctrl/Win 등)를 누르는 주입은 반드시 `try/finally`로 키 업 정리를 보장한다** — 반환값 분기만으로는 예외 경로에서 키가 눌린 채 남는다
- **마지막 줄을 보내고 닫는 모든 경로는 close 전에 수신 큐를 비운다** — `shutdown(SHUT_RDWR)`만으로는 RST를 막지 못한다(미판독 바이트가 남아 있으면 커널이 RST를 보내 이미 보낸 마지막 줄까지 상대 버퍼에서 사라진다). PIN 브루트포스 잠금(F-1)과 단일 클라이언트 밀어내기, 두 번 겪은 함정
- **와이어 순서(누가 먼저 말하는지)를 바꾸는 변경은 파괴적 변경으로 취급한다** — 새 테스트 추가만으로는 부족하고, grep으로 기존 handshake 시뮬레이션 테스트를 전수 조사해 갱신해야 한다(놓친 파일이 실제로 있었다 — `test_discovery.py`). 가짜 소켓 헬퍼가 여러 파일에 흩어져 있었다면 이 기회에 공용 모듈로 합친다(`tests/fake_conn.py`)
- **소켓을 닫기 전에 미판독 바이트가 남아 있으면 커널이 RST를 보낸다** — clean EOF(정상 종료로 관측됨)와 RST(예외로 관측됨)는 클라이언트 쪽에서 다른 종류로 보인다. "형식 오류"와 "의도적 거부"를 와이어 상 구분되지 않게 하려면, close 전에 짧은 타임아웃으로 큐를 비울 것
- **서버 쪽에 새 UI/부가 기능을 추가할 때도 필수 의존성을 늘리지 않는다** — pystray/Pillow가 트레이 전용 선택 의존성이듯, 창은 표준 라이브러리 `tkinter`로 만든다(GUI 프레임워크를 새로 pip install 하지 않는다)
- **`ServerRuntime`은 재사용할 수 없다** — `close()`한 소켓은 다시 못 연다. "시작/정지"처럼 서버를 다시 띄워야 하는 기능은 인스턴스가 아니라 **인자 없이 부르면 새 인스턴스를 반환하는 factory**를 받는다(`ServerSupervisor`/`run_with_gui` 참조). PIN처럼 "매 시작마다 새로 생겨야 하는 값"도 factory 안에서 다시 계산하면 자연히 해결된다
- **tkinter `mainloop()`는 메인 스레드, pystray는 백그라운드 스레드에 둔다**(이 프로젝트는 Windows 전용이라 pystray의 `_win32` 백엔드가 자신이 만든 창이 속한 스레드에서 메시지 루프를 돌리면 되므로 가능 — macOS Cocoa 백엔드였다면 안 된다). 트레이 스레드에서 tkinter 위젯을 직접 건드리지 않는다(스레드 세이프하지 않음) — 큐 + `after()` 폴링으로 메인(=tkinter) 스레드에서 실행되게 한다. **실제로 창+트레이를 동시에 띄워 눈으로 확인할 것** — 이런 이중 이벤트 루프 설계는 문서만 보고 판단하면 안 된다
- **PyInstaller `.spec`의 `excludes` 목록은 새 기능이 그 라이브러리를 필요로 하게 되면 함께 갱신해야 한다** — `tkinter`가 "서버는 GUI가 없다"는 이유로 제외돼 있었는데, 창을 기본 모드로 추가하면서 안 지웠다면 exe는 **오류 없이 조용히** 트레이 전용 모드로 폴백했을 것이다(발견하기 어려운 종류의 회귀)
- **`ServerRuntime`의 새 선택 인자는 기본값을 "비활성"으로 둔다** — 기본값이 실제 포트를 잡으면 서버가 떠 있는 동안 무관한 테스트가 깨진다(트레이/단일 인스턴스/탐색에서 세 번 겪은 교훈). 켜는 것은 `main()`의 몫
- **Windows UDP 수신 루프에서 `except OSError: break`를 쓰지 않는다** — 정상 동작 중에도 `WSAEMSGSIZE`/`WSAECONNRESET`이 `recvfrom`에서 `OSError`로 올라와 리스너가 조용히 죽는다. 정지 신호/소켓 닫힘과 구분해서 계속 돌 것
- **KDoc·주석에 유니코드 escape 리터럴을 적지 않는다** — kapt 스텁 주석으로 복사되어 `illegal unicode escape`로 빌드가 깨진다. 말로 풀어 쓸 것
- **suspend 호출을 `runCatching`으로 감싸지 않는다** — `CancellationException`까지 삼켜 취소를 실패로 보고한다. `try/catch(CancellationException){throw}/catch(Exception)`을 쓴다
- **테스트는 하나의 `TestDispatcher`를 필드로 두고 `runTest(dispatcher)`로 넘긴다** — `runTest {}` 안에서 새 디스패처를 만들면 `Detected use of different schedulers`로 무더기 실패한다
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
| PIN 인증 실기기/기타 미검증 | 실기기 왕복(숫자 키패드·소형 화면 잘림·포커스 이동·오류 후 재입력), exe 재빌드 후 PIN 콘솔 출력, 실 pystray PIN 메뉴 렌더는 사람이 확인 필요(서버 테스트는 임시 venv 재실행으로 pystray 메뉴 구성까지는 확인함) |
| PIN 인증의 남은 한계 | 잠금 카운트는 프로세스 메모리 전용이라 서버 재시작 시 리셋됨. NAT 뒤에서 같은 공인 IP를 쓰는 여러 기기는 한 기기의 실패로 함께 잠김. 공격자가 IP를 바꾸며 시도하면 이 잠금을 우회할 수 있어 실질 방어선은 "6자리 100만 조합 + 3초 AUTH 타임아웃"이다. **UDP 세션 토큰이 평문이고 발신 IP도 검증하지 않는 스푸핑 문제는 PIN 인증으로 해결되지 않는다**(TCP 핸드셰이크만 보호됨 — UDP MOVE 자체를 인증하려면 별도 설계 필요). `--allow-multiple`로 서버를 두 개 띄우면 PIN도 두 개(트레이/콘솔 각자 자기 PIN만 표시) |
| 다중 기기 연결 (정책 확정, 구조 미해소) | **정책은 닫혔다 — 단일 클라이언트로 확정(Phase 5).** 여러 기기 동시 지원은 "미정"이 아니라 **명시적으로 범위 밖**이다(서로 뺏고 뺏는 핑퐁 방지). 서버는 활성 TCP 클라이언트를 항상 최대 1개로 유지하므로(`SingleClientGuard`) 여러 기기가 동시에 커서를 움직이는 증상은 더 이상 발생하지 않는다. **구조는 그대로다:** 근본 원인인 세션별 상태 분리가 없다 — `_drag_active`는 여전히 프로세스 전역이고 `SessionRegistry`도 여전히 집합 기반이다. 단일 클라이언트 정책은 그 구조 위에 얹은 **입장 제한**이지 상태 분리가 아니다. 나중에 다중 기기를 실제로 지원하기로 뒤집는다면 `_drag_active` 등 프로세스 전역 상태 분리부터 해야 한다. 정책은 **TCP 입장만** 제한한다 — 밀려난 기기의 UDP MOVE는 세션 토큰이 회수될 때까지(수 ms) 계속 처리된다 |
| sub-pixel 이동 정밀도 | `InputController._move`에 한정된 이슈 — 정수 반올림만 하고 잔차를 누적하지 않아, 아주 느린 드래그의 미세 델타가 소실될 수 있음. SCROLL은 Android가 잔차를 완전히 처리해 보내므로 해당 없음 — 별도 이슈로 개선 검토 |
| heartbeat 리셋 비대칭 | 서버는 CLICK 등 어떤 상향 데이터로도 미응답 카운터가 리셋되지만, 서버→클라이언트 하향 트래픽은 ACK뿐이라 Android 쪽은 사실상 ACK만이 유일한 리셋 수단. 한쪽 방향만 끊기는 비대칭 시나리오가 가능함 — 자동 재연결은 앱이 유실을 감지한 뒤에만 시작되므로, 서버만 끊었고 앱은 아직 모르는 구간(최대 15초)은 그대로 남는다 |
| Android 절전/도즈 환경의 heartbeat | 화면 꺼짐·도즈·Wi-Fi 절전으로 5초 주기 전송이 지연되면 서버가 먼저 15초 타임아웃으로 끊는 오탐 가능성 — 실기기 미검증. 자동 재연결은 백오프 타이머(코루틴 `delay`)만 쓰므로 도즈 중에는 타이머 자체가 지연될 수 있고, 백그라운드·네트워크 전환(WiFi→모바일)·`ConnectivityManager` 기반 즉시 재시도는 이번 재연결 범위 밖 — wake lock과 함께 별도로 다룰 것 |
| 재연결 직후 서버 세션 2개 (대부분 해소) | **대부분 해소(Phase 5 단일 클라이언트 정책).** 예전에는 서버가 옛 연결의 EOF나 heartbeat 15초 타임아웃을 기다려야 해서 세션 2개 구간이 **최대 15초**였고, 그 안에 새 연결에서 드래그를 시작하면 옛 연결의 강제 해제가 그 드래그를 놓아버릴 수 있었다. 이제 재연결한 앱이 AUTH를 통과하는 순간 서버가 자기 자신의 유령 세션을 즉시 밀어내고 닫으므로(밀어내기 → 새 SESSION 발급 순서) **기존 발생 조건은 사실상 사라졌다.** **남은 창:** 밀려난 연결의 정리(`finally`의 세션 회수 + 드래그 강제 해제)는 그 연결을 처리하던 스레드에서 비동기로 돌기 때문에, 밀어내기와 그 정리 사이 **수 ms** 동안 옛 스레드의 강제 해제가 새 클라이언트의 드래그를 놓을 여지가 원리적으로 남는다(실측 재현 불가 수준). 뿌리는 위 "다중 기기 연결"의 구조 항목과 같으며 세션별 상태 분리 때 함께 해결할 것 |
| 단일 클라이언트 알림 유실 시 핑퐁 (조건부 잔여) | 서버의 `SESSION_REPLACED` 전송은 best-effort다. 그 줄이 도달하지 못하면 앱은 평범한 EOF로 관측해 자동 재연결을 시작하고, 성공하면 방금 들어온 기기를 다시 밀어낸다(정책이 막으려던 핑퐁). close 전 드레인이 이 경로를 대부분 막는다 — QA 실측: 미판독 바이트가 0.5MB까지는 항상 알림+clean FIN, 드레인 예산(0.1초/1MiB)을 넘겨 계속 스트리밍 중일 때(3.2MB 이상)만 RST와 함께 알림이 사라진다. 현재 TCP 채널은 저빈도 JSON 한 줄만 흐르므로 실기기에서 도달 불가 — **TCP에 고빈도 이벤트를 추가하는 변경이 생기면 이 조건이 되살아나므로 그때 다시 볼 것.** 완전 차단은 프로토콜 변경(서버가 최근 밀어낸 주소의 재입장을 잠시 거절 등)이 필요 |
| 단일 클라이언트 정책 실기기 미검증 | 폰 두 대로 실제 밀어내기 왕복, 밀려난 화면의 문구 렌더·소형 화면 잘림, 드래그 중 밀려났을 때 실기기에서 버튼이 실제로 놓이는지는 사람이 확인해야 한다. 실제 JVM 클라이언트 2개 + 실서버 루프백으로 밀어내기·clean FIN·드래그 강제 해제·AUTH 실패 무영향까지는 확인함(Wi-Fi 경로 미검증). PyInstaller exe는 신규 모듈(`single_client.py`) 추가로 재빌드 필요(`server.py` 정적 import라 hiddenimports 수정은 불필요할 것으로 보이나 미확인) |
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
| `SendInput` 주입 실패 감지의 한계 | 모든 호출이 `InputController._send_input()`을 지나며 반환값을 검사한다(반환값 계약은 이 머신에서 실측: `MOUSEEVENTF_MOVE` dx=dy=0 1개 → 반환 1). **남은 한계:** ① UIPI(관리자 권한 창에 일반 권한 프로세스가 주입)로 차단되면 Microsoft 문서상 반환값도 `GetLastError`도 실패를 알리지 않아 **감지 불가** — 서버를 관리자로 띄우는 것 외에 코드로 해결할 방법이 없다. ② 실패 경로(잠금 화면/보안 데스크톱)는 단위 테스트로만 커버, 실환경 미재현. ③ 로그의 `last error` 값은 `windll`이 `use_last_error`가 아니라 best-effort(표시용, 분기 근거 아님). ④ 입력이 오래 막혀도 서버는 복구 동작 없이 로그+카운터만 남긴다 |
| 입력 실패의 사용자 노출 | `InputController.input_failures`로 누적 실패 수를 읽을 수 있지만 트레이/UI에 아직 표시하지 않는다. 누적 전용(리셋 API 없음)이라 트레이에 붙일 때는 "최근 N초 실패" 표현이 필요할 수 있음 |
| 연결 타임아웃/취소 실기기 검증 | `CONNECT_TIMEOUT_MS`(5초) 만료 체감, `Connecting` 화면의 "취소" 버튼 렌더·반응, 오류 2줄의 소형 화면 잘림, 취소 후 IP 입력값 유지는 실기기 미검증(컴파일·JVM 단위 테스트만 통과). 타임아웃이 실제로 만료되는 경로는 블랙홀 주소가 필요해 단위 테스트로 재현하지 않았고, 고정한 것은 "OS 기본값에 맡기지 않는다"는 계약이다. 취소 직후 최대 5초간 뒤에서 도는 연결 시도는 결과가 버려지므로(`connectEpoch`) 무해하다 |
| 오류 문구의 다국어 | `ConnectionErrorMessages`가 한국어 문자열을 코드에 직접 담고 있다(단일 로케일 전제). 다국어가 필요해지면 이 파일 하나만 `strings.xml`로 옮기면 된다 |
| 3손가락 제스처 실기기 검증 | 임계 120px의 체감, 제조사 시스템 제스처(스크린샷/분할화면 등)가 3손가락 터치를 가로채는지, 실제 터치에서 `pointerCount == 3`이 안정적으로 보고되는지 미검증. 임계값이 dp가 아니라 px라(기존 모든 제스처 상수와 동일 규약) 고해상도 기기에서는 상대적으로 짧게 느껴질 수 있음. `TrackpadScreen`의 3번째 손가락 flush/consume 배선은 Compose 의존이라 자동 테스트가 없다(판정 로직은 순수 클래스로 전부 커버) |
| 데스크톱 전환 실기기 미검증 | 키 코드·플래그·순서·`sizeof(INPUT)`·주입 성공은 테스트와 실측으로 고정했으나, Ctrl+Win+Left/Right가 실제로 데스크톱을 넘기는지는 사람이 한 번 확인해야 한다(테스트 중 실제 전환은 고의로 실행하지 않음). 주입이 2개(Ctrl↓ Win↓)만 성공한 뒤 정리 키 업이 나가면 Win 키 업이 시작 메뉴를 열 수 있다(Win 고착보다는 낫다고 판단, 실기기 확인 항목). 정리 키 업마저 실패하는 경우는 코드로 더 막을 수 없다. 실패 1회당 `input_failures`가 2 증가(본 시퀀스 + 정리) — 트레이에 노출할 때 감안할 것 |
| 3손가락 래치의 UX 대가 | 2손가락 스크롤 중 세 번째 손가락이 스치면 그 제스처의 남은 스크롤이 전부 억제된다(어긋난 릴리스의 클릭 오발동 차단과 맞바꾼 스펙). 4→3 전환은 새 구간이 시작되므로 전환이 발사될 수 있다(구간 단위 정의의 귀결, 실해 없음). 실기기에서 거슬리면 재논의 |
| 자동 탐색 실기기/방화벽 미검증 | 실제 Wi-Fi에서 브로드캐스트가 닿는지(AP가 제한 브로드캐스트를 버리는지, Android 10+ 멀티캐스트/브로드캐스트 전력 제어), 결과 목록 렌더·탭 선택 배선(`ServerDiscoverySection`은 Compose UI 테스트 없음), 1.5초 창의 체감, **UDP 9002 인바운드 방화벽**(exe는 `python.exe`와 다른 바이너리라 새 프롬프트 — 미검증)은 실측하지 못했다. 서버는 같은 PC에서 `255.255.255.255`와 서브넷 브로드캐스트 양쪽으로 응답을 실측했고 종료 후 9002 재바인딩·잔여 프로세스 0을 확인했다. exe를 재빌드해 9002가 뜨는지도 미확인(`discovery.py`는 stdlib 전용이고 `server.py`가 정적 import해 hidden import 문제는 없을 것) |
| 탐색 응답 위조 가능 | 인증 이전 단계라 같은 LAN의 누구나 `SERVER` 응답을 위조해 목록에 줄을 올릴 수 있다. 선택은 입력란만 채우고 연결은 사용자가 누르게 했지만 이름만 보고 누르면 공격자 주소로 붙는다 — 근본 해결은 PIN 인증과 함께(위 "PIN 인증" 행). 반대로 같은 LAN의 누구나 PC 이름·TCP 포트를 알 수 있다(스펙이 의도한 트레이드오프, 토큰은 절대 넣지 않음) |
| 탐색의 한계 | IPv6 전용 네트워크 미지원(브로드캐스트는 IPv4 개념 — mDNS 등 별도 설계 필요), 응답에 TCP 포트만 있어 서버가 비표준 UDP(MOVE) 포트로 뜨면 앱이 알 수 없음(현재 `main()`은 항상 9001), 서버 recv 오류가 50회 연속이면 탐색 스레드가 포기하는데 `start()`는 이미 `True`를 돌려준 뒤라 9002를 점유한 채 응답이 없다(QA W-6, 발생 조건이 좁아 허용), 취소 후 최대 200ms 소켓이 살아 있음, 같은 IP 뒤의 두 앱은 응답 제한(IP 단위 5회/초)을 공유 |
| 서버 두 번째 인스턴스의 9002 | 탐색 소켓은 `SO_REUSEADDR`를 쓰지 않아 **두 번째 인스턴스의 9002 bind는 실패**한다(응답이 두 개 나가지 않음 — named mutex와 별개의 두 번째 방어선). `--allow-multiple`로 일부러 두 개를 띄우면 두 번째는 탐색이 꺼진 채 동작한다 |
| 서버 GUI 실기기/exe 미검증 | 창+트레이 동시 구동은 개발자의 실제 Windows 데스크톱에서 실측(23/23 PASS, 3회 반복) — 창 표시, X→트레이 축소, 트레이에서 창 복원, 시작/정지로 포트 열고 닫기, 재시작마다 새 PIN까지 확인. **PyInstaller 재빌드는 이번 범위 밖**이라 exe에서도 같은지 미확인(스펙의 `tkinter` 제외를 풀었으니 되어야 하지만 실측 필요) |
| 앱 백그라운드 진입 시 드래그 미종료 | 드래그 홀드 중 Android 앱이 백그라운드로 가서 `TrackpadViewModel`이 파기되면 `DRAG_END`를 보낼 기회가 없다 — 서버 heartbeat 타임아웃(≈15초)이 감지해 강제로 놓을 때까지 PC 버튼이 눌린 채 유지됨. 실기기 미검증 |
