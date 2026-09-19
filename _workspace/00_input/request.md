# 요청: 재연결 로직 (Phase 4 — "연결 끊김 감지 → 자동 재시도")

사용자 요청: "이어서 작업해줘" → AGENTS.md 섹션 6 Phase 4 항목
- [ ] 재연결 로직 (연결 끊김 감지 → 자동 재시도) — heartbeat가 만드는 `Error("Heartbeat timeout")`/`Error("Connection lost")`를 재시도 트리거로 사용

## 범위 판단: **단일 사이드 (Android)**

사유: 이벤트 `type`/필드/채널/세션 형식 변경이 없다. 재연결은 **기존 핸드셰이크(TCP 연결 → `SESSION` 줄 수신 → UDP 타깃 설정)를 그대로 다시 수행**할 뿐이고,
서버 입장에서는 "새 클라이언트가 접속한 것"과 구분되지 않는다. `ConnectionState`에 상태가 추가되지만 이는 앱 내부 UI 상태이지 와이어 프로토콜이 아니다.
→ android-dev 서브 에이전트 1명, protocol-qa 생략. ※ 구현 중 이벤트/핸드셰이크를 건드리게 되면 즉시 멈추고 보고할 것(교차 경계면 전환).

## 현재 구조 (에이전트가 코드로 직접 확인할 것 — `data/repository/TrackpadRepositoryImpl.kt`)
- 연결 유실은 전부 `reportConnectionLost(generation, message)`를 지난다(heartbeat sender 전송 실패 / watchdog EOF·예외·타임아웃 3회). 세대(generation) CAS로 이중 보고를 막는다.
- `connect()`/`disconnect()`는 `connectionMutex`로 직렬화되고 세대를 올려 옛 루프를 무효화한다(F-2/F-3 패턴 — **이 안전장치를 약화시키지 말 것**).
- **주의 — 전송 실패 경로가 다르다:** `Click`/`DoubleClick`/`DragStart`/`DragEnd`의 TCP 전송 실패는 지금 `_connectionState = Error(...)`만 세팅하고 **소켓 정리도, 세대 무효화도, keep-alive 중단도 하지 않는다**(소켓은 죽었는데 heartbeat 루프는 계속 돌고, 최대 5초 뒤 sender가 다시 `reportConnectionLost`로 Error를 덮어쓴다). 재연결을 붙이려면 이 경로도 같은 "연결 유실" 처리로 합류해야 한다(아래 §2).
- `MOVE`/`SCROLL`의 전송 실패는 **조용히 버리는 것이 의도**다(고빈도 이벤트가 heartbeat의 원인 메시지를 덮어쓰는 것 방지, F-1/F-2) — 이 두 경로는 **건드리지 말 것.**

## 확정 설계 (리더 결정 — 임의 변경 금지)

### 1. 재연결 트리거 조건
- **"Connected였던 세션이 유실됐을 때만"** 자동 재연결한다. 사용자가 IP를 입력해 처음 누른 `connect()`가 실패한 경우(핸드셰이크 실패, 연결 거부 등)는 **재시도하지 않고 기존처럼 `Error`로 남긴다** — 잘못된 IP에 무한 재시도하면 안 된다.
- 재연결 대상은 마지막으로 **성공**한 `host`/`port`. (연결 실패 중인 값이 아님.)
- 사용자 조작이 항상 이긴다: 재연결 대기/시도 중에 `disconnect()`(수동 해제)가 오면 재시도를 즉시 중단하고 `Disconnected`, 수동 `connect()`가 오면 진행 중인 재연결을 취소하고 그 요청을 수행한다.

### 2. 상태 머신
`ConnectionState`에 **`Reconnecting(host: String, attempt: Int, maxAttempts: Int)`** 를 추가한다(도메인 모델, 순수 데이터).
```
Connected ──(유실 감지)──► Reconnecting(attempt=1) ──(성공)──► Connected
                              │  ▲
                        (실패, 백오프 후 attempt+1)
                              ▼  │
                        Reconnecting(attempt=N) ──(N 소진)──► Error("Reconnect failed: <마지막 원인>")  (재시도 종료, 수동 연결 화면)
사용자 disconnect() → 어느 상태에서든 Disconnected (재시도 중단)
```
- 유실 감지 → **`Error`를 거치지 않고 곧바로 `Reconnecting`으로 전이**한다(UI가 Error 패널을 깜빡 보여주지 않게). 단, 재연결이 **비활성**이거나 재연결 조건이 아니면 기존처럼 `Error(message)`.
- `Click`/`DoubleClick`/`DragStart`/`DragEnd` 전송 실패도 `reportConnectionLost`와 **같은 처리로 합류**시킨다(소켓 정리 + 세대 무효화 + keep-alive 중단 + 재연결 트리거). 원인 메시지는 기존대로 `e.message ?: "Send failed"`. 세대는 **이벤트 전송 시점의 현재 세대**를 쓰고 CAS로 이중 보고를 막는다.
- 재연결 시도 1회 = 기존 `connect` 본문(TCP 연결+핸드셰이크 → 토큰 저장 → UDP 연결 → `Connected(host)` → keep-alive 시작)을 그대로 재사용한다. 코드 복제 금지 — 내부 함수로 추출해 수동 connect와 재연결이 같은 경로를 쓰게 할 것.
- 재연결 성공 시 시도 횟수 카운터는 리셋되어 다음 유실에서 다시 1부터 시작한다.

### 3. 백오프 정책 (순수 Kotlin 클래스로 분리)
- `domain/model/ReconnectPolicy.kt`(또는 동등한 위치): 순수 클래스. `maxAttempts`, `delayBeforeAttempt(attempt: Int): Long`(ms), `enabled` 등을 가진다. 시간 의존 없이 단위 테스트 가능해야 한다.
- 기본값(`GestureConfig`에 상수로 — 하트비트 상수와 같은 자리): 지수 백오프 1s → 2s → 4s → 8s → 이후 10s 상한, **`RECONNECT_MAX_ATTEMPTS = 8`**(총 대기 ≈ 1+2+4+8+10×4 = 55초). 상수 이름은 `RECONNECT_MAX_ATTEMPTS`, `RECONNECT_BASE_DELAY_MS`, `RECONNECT_MAX_DELAY_MS`.
- **재연결 정책은 생성자 주입**한다(Hilt로 기본 정책 제공). 기존 `TrackpadRepositoryImplTest`/`TrackpadRepositoryHeartbeatTest`는 "유실 → Error 전이"를 검증하고 있으므로, 그 테스트들이 **재연결 비활성 정책으로 의미를 그대로 보존**하도록 할 것(테스트 로직을 뜯어고쳐 통과시키지 말고, 정책을 주입해 기존 동작을 재현). 재연결 동작은 **신규 테스트**로 검증한다.
- 대기는 주입된 `ioDispatcher` 위의 코루틴 `delay`로 한다(테스트가 `StandardTestDispatcher`+가상 시간으로 제어 가능해야 함 — 실제 sleep 금지).

### 4. UI (`TrackpadScreen`)
- `Reconnecting` 상태를 렌더한다: "재연결 중… (attempt/maxAttempts)" 문구 + **"취소" 버튼**(누르면 `viewModel.disconnect()` → `Disconnected`, 재시도 중단). 기존 `ConnectingPanel` 재사용/확장 가능하되 **취소 버튼은 재연결 상태에서만**(첫 연결 중 Connecting은 지금 그대로).
- `when (state)`는 sealed 클래스 전체를 다뤄야 한다(컴파일 에러 방지). 설정 화면 진입 조건(`settingsAvailable`)은 Disconnected/Error 그대로 — Reconnecting 중에는 설정을 열 수 없다.
- 재연결 성공 시 `Connected`로 돌아오면 `TrackpadSurface`가 새로 컴포지션에 들어온다 — 제스처 판정 상태는 이미 `awaitEachGesture`/트래커가 제스처마다 새로 만들어지므로 별도 리셋 불필요(확인만 하고 불필요한 코드 추가 금지).

### 5. 알려진 경계 (구현하지 말고 summary에 미해결로 기록)
- 서버는 옛 연결의 종료(EOF/heartbeat 15초 타임아웃)를 감지하기 전까지 옛 세션을 유지한다 → 재연결 직후 잠깐 동안 서버에 세션이 2개일 수 있다(서버는 다중 세션 허용 구조). 서버 변경은 이번 범위 아님. AGENTS.md 섹션 10 "다중 기기 연결"의 드래그 상태 전역 문제(옛 연결의 종료가 새 연결의 드래그를 놓아버릴 수 있음)와 같은 뿌리 — 언급만.
- 화면 꺼짐/도즈/앱 백그라운드 중의 동작, 네트워크 전환(WiFi→모바일), `ConnectivityManager` 기반 즉시 재시도는 이번 범위 아님(백오프 타이머만 사용).

## 테스트 요구사항 (JUnit + MockK, 기존 스타일, `runTest` 가상 시간)
1. `ReconnectPolicy`(순수) — 지연 시퀀스 1,2,4,8,10,10..., 상한 클램프, `maxAttempts` 경계, 비활성 정책.
2. 리포지토리 재연결 시나리오(가짜/MockK `TcpClient`·`UdpClient`, 가상 시간):
   - Connected 후 heartbeat 유실 → `Reconnecting(1,N)` (Error를 거치지 않음) → 다음 시도 성공 시 `Connected`, 새 세션 토큰이 UDP MOVE에 쓰임(옛 토큰 아님), 카운터 리셋
   - 시도가 연속 실패 → attempt가 1..N으로 증가 → N 소진 후 `Error("Reconnect failed: ...")`, 그 이후 더 이상 재시도하지 않음
   - **첫 연결 실패는 재시도하지 않음**(Connecting → Error 그대로, 가상 시간을 한참 진행해도 추가 connect 호출 없음)
   - 재연결 대기 중 `disconnect()` → 즉시 `Disconnected`, 이후 가상 시간을 진행해도 connect 호출 없음
   - 재연결 대기/시도 중 수동 `connect()` → 진행 중 재연결 취소, 수동 연결 1회만 수행(이중 접속 없음)
   - `Click`/`DragEnd` 전송 실패 → 소켓 정리 + 재연결 트리거(Error 깜빡임 없이 Reconnecting), 그리고 그 뒤 옛 heartbeat 루프가 두 번째 유실 보고를 내지 않음(세대 CAS)
   - `MOVE`/`SCROLL` 전송 실패는 재연결을 **트리거하지 않음**(기존 F-1/F-2 유지)
   - 재연결 비활성 정책이면 기존과 동일하게 `Error(message)`
3. 기존 테스트 전부 통과(위 §3의 정책 주입 방식으로). 192개 → 그 이상.
4. `TrackpadViewModel` 쪽 변경이 필요하면 최소한으로(취소는 기존 `disconnect()` 재사용).

## 실행/검증
- `phone_pad_app/`에서 `./gradlew :app:testDebugUnitTest :app:assembleDebug` (Windows PowerShell이면 `.\gradlew.bat`). `phone_pad_app/local.properties`는 이미 리더가 복사해 두었다(gitignore 대상, 커밋 금지).
- 작업 디렉토리: **이 워크트리(`C:\Github\phone_pad\.claude\worktrees\reconnect-logic`) 안에서만.** 다른 워크트리(`settings-ui-datastore`)와 원본 체크아웃(`C:\Github\phone_pad`)은 읽기·수정 모두 금지.
- 컴파일이 통과해도 Compose 화면 동작은 실기기 전엔 미검증임을 summary에 명시.

## 산출물
- 코드/테스트: `phone_pad_app/`
- 요약: `_workspace/01_android-dev_summary.md` — 변경 파일, 설계 결정과 근거, 실제 테스트 출력 기준 결과, 미해결 이슈, 실기기 확인 항목
- **커밋하지 말 것**(리더 처리). AGENTS.md/CLAUDE.md 수정 금지(리더 처리).

## 제외
- 서버 변경, UDP 브로드캐스트 자동 탐색, 트레이 아이콘, IP 영속화(마지막 접속 IP 저장), ConnectivityManager 연동, 앱 백그라운드/도즈 대응.
