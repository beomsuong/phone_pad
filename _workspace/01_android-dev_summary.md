# 01 android-dev — 예외 처리 강화 (B. Android)

담당 범위: `request.md`의 **B. Android** 절만. `pc_server/`는 건드리지 않음.
커밋하지 않음. `AGENTS.md`/`CLAUDE.md` 수정하지 않음. `TrackpadEvent`/와이어 프로토콜 무변경.

---

## 1. 결과 요약

| 확정 스펙 | 상태 |
|---|---|
| 1. `CONNECT_TIMEOUT_MS = 5000` + `Socket()` + `connect(InetSocketAddress, timeout)` | 완료 |
| 2. 첫 연결(`Connecting`)에 "취소" 버튼 — 오류 없이 `Disconnected` 복귀, 뒤늦은 결과가 상태를 못 덮어씀 | 완료 |
| 3. 예외 → 한국어 조치 힌트 순수 함수 + 표시 계층 적용 | 완료 |
| 4. 내부 문자열(`"Heartbeat timeout"` 등)·재연결 로직 유지 | 유지됨 (아래 2.4 참조) |
| 5. 친절한 문구 = 주 메시지 / 원문 = 보조 줄 | 완료 |

**테스트: 258 passed, 0 failed, 0 skipped** (기준선 217 + 신규 41).
**UI 미확인 — 컴파일 + JVM 단위 테스트만 검증** (실기기/에뮬레이터 없음).

---

## 2. 변경 파일

### 신규 (main)
| 파일 | 역할 |
|---|---|
| `app/src/main/java/.../domain/model/ConnectionErrorKind.kt` | 실패 원인 종류 enum (9개) |
| `app/src/main/java/.../domain/model/ConnectionErrorClassifier.kt` | `Throwable` → `ConnectionErrorKind` **순수 함수** |
| `app/src/main/java/.../presentation/util/ConnectionErrorMessages.kt` | `kind`(+원문) → 한국어 문구/보조 줄. **순수 함수**, Compose 비의존 |
| `app/src/main/java/.../presentation/trackpad/ConnectionErrorSection.kt` | 오류 2줄 렌더 컴포저블 (`TrackpadScreen` 변경 면적 축소용 분리) |

### 수정 (main)
| 파일 | 변경 |
|---|---|
| `presentation/util/GestureConfig.kt` | `CONNECT_TIMEOUT_MS = 5000` 추가 (근거 주석 포함) |
| `data/network/TcpClient.kt` | `Socket(host, port)` → `Socket()` + `connect(InetSocketAddress, connectTimeoutMs)`. 실패 시 소켓/필드 정리 후 rethrow. 테스트 주입점 `internal var connectTimeoutMs` / `internal var socketFactory` |
| `domain/model/ConnectionState.kt` | `Error(message)` → `Error(message, kind = UNKNOWN)` (2번째 인자 **기본값 있음**) |
| `domain/repository/TrackpadRepository.kt` | `suspend fun cancelConnect()` 추가 |
| `data/repository/TrackpadRepositoryImpl.kt` | `connectEpoch` 도입, `cancelConnect()` 구현, `openConnection`에 `isStillWanted` 람다 + `ConnectOutcome`(Success/Cancelled/Failure), 각 실패 경로에 `kind` 부여 |
| `presentation/trackpad/TrackpadViewModel.kt` | `cancelConnect()` 추가 (UseCase 무관 — 연결 제어는 기존 `connect`/`disconnect`와 동일 경로) |
| `presentation/trackpad/TrackpadScreen.kt` | **최소 변경**: `Connecting` 분기에 `onCancel` 1개, `ConnectPanel(errorMessage: String?)` → `error: ConnectionState.Error?`, 오류 렌더 8줄 → `ConnectionErrorSection(error)` 1줄, KDoc 갱신 |

### 신규 (test)
- `data/network/TcpClientConnectTimeoutTest.kt` (7)
- `data/repository/TrackpadRepositoryCancelConnectTest.kt` (8)
- `domain/model/ConnectionErrorClassifierTest.kt` (10)
- `presentation/util/ConnectionErrorMessagesTest.kt` (12)

### 수정 (test)
- `presentation/util/GestureConfigTest.kt` (+2)
- `presentation/trackpad/TrackpadViewModelTest.kt` (+2)
- `data/repository/TrackpadRepository{Impl,Heartbeat,Reconnect}Test.kt` — **의도된 계약 변경분만**: `Error("...")` 단언에 2번째 인자(`kind`) 추가 12곳 + import 1줄. 메시지 문자열·검증 의미는 하나도 약화시키지 않음

---

## 3. 구현한 로직

### 3.1 연결 타임아웃
```kotlin
const val CONNECT_TIMEOUT_MS = 5000   // GestureConfig
```
근거(코드 주석에 기록): 같은 LAN의 살아있는 서버는 handshake가 수 ms~수십 ms /
혼잡한 Wi-Fi의 TCP 초기 재전송(RTO≈1s, 이후 2s) 2~3회는 덮어야 함 / OS 기본값(Android 20초+)은
오타 IP에 사용자를 가둠. **`SESSION_HANDSHAKE_TIMEOUT_MS`(3초)와 직렬** → 최악 8초, 그 전에 취소 가능.

`TcpClient.connect()`는 소켓을 **연결 시도 전에** `socket` 필드에 등록한다 — 블로킹 `connect()`는
코루틴 취소로 풀리지 않으므로 다른 코루틴이 `disconnect()`로 소켓을 닫아 깨우는 것이 유일한 수단이다.
실패 시 `s.close()` + (`socket === s`일 때만) `disconnect()`로 죽은 소켓을 남기지 않고 예외를 그대로 올린다.

### 3.2 첫 연결 취소 (경합 설계 — AGENTS.md 섹션 6 패턴 준수)
새 카운터 **`connectEpoch`**(기존 `generation`/`reconnectEpoch`와 별개):
- `connect()` / `cancelConnect()` / `disconnect()`가 올린다. **자동 재연결 루프는 건드리지 않는다**
  (재연결이 이 값을 올리면 락을 기다리던 사용자의 수동 연결이 영문도 모르고 무효화된다).
- `openConnection(host, port) { connectEpoch.get() == epoch }` — 블로킹 접속이 끝난 **직후**,
  `Connected`를 쓰기 **전에** 유효성을 확인한다.

`cancelConnect()`는 전부 **뮤텍스 밖**에서, 이 순서로:
1. `Connecting`이 아니면 즉시 return (살아있는 연결·재연결을 건드리지 않음)
2. `connectEpoch++` — 진행 중 시도 선무효화
3. `_connectionState = Disconnected` — **오류 표시 없음**
4. `cleanUp()` → 소켓 close로 블로킹 `connect()` 깨움

`openConnection`의 반환을 `String?`에서 `ConnectOutcome`(Success/**Cancelled**/Failure)으로 바꿔
"실패"와 "취소"를 구분한다. Cancelled면 호출자는 상태를 쓰지 않는다. 취소와 접속 성공이 겹치면
붙어버린 소켓을 `cleanUp()`으로 반드시 닫는다(서버에 유령 세션을 남기지 않기 위해).
`CancellationException`은 기존대로 별도 catch에서 rethrow.

부수 효과(개선): 수동 연결이 끼어들 때 진행 중이던 **재연결** 시도도 같은 람다
(`reconnectEpoch` 기준)로 Cancelled 처리되어, 이전처럼 "재연결이 방금 만든 연결을 놔둔 채
수동 연결이 또 붙는" 이중 접속 창이 닫혔다. 기존 "사용자 조작이 항상 이긴다" 테스트는 그대로 통과.

### 3.3 메시지 매핑 (순수 함수 2단)
`ConnectionErrorClassifier.classify(Throwable?)` — 타입 → 메시지 키워드 → 폴백 순.
원인 체인을 최대 5단계까지 따라가며, 자기 자신을 cause로 갖는 예외에서도 멈춘다.

| 입력 | kind | 사용자 문구(요지) |
|---|---|---|
| `ConnectException` / `"refused"` | `CONNECTION_REFUSED` | 서버 실행 여부 + 방화벽 TCP **9000**(상수에서 가져옴) |
| `SocketTimeoutException` / `"timed out"` | `TIMEOUT` | IP가 맞는지, 같은 Wi-Fi인지 |
| `UnknownHostException` / `"unable to resolve host"` | `UNKNOWN_HOST` | 주소를 찾을 수 없음, IP 확인 |
| `NoRouteToHostException`/`PortUnreachableException`/`"unreachable"` | `NETWORK_UNREACHABLE` | Wi-Fi 확인 |
| 핸드셰이크 null/blank | `HANDSHAKE_FAILED` | Phone Pad 서버가 맞는지/버전 |
| heartbeat 미응답 한계 | `HEARTBEAT_TIMEOUT` | PC 절전/Wi-Fi 확인 |
| EOF·전송 실패 | `CONNECTION_LOST` | Wi-Fi·서버 확인 후 재연결 |
| 재연결 소진 | `RECONNECT_FAILED` | 확인 후 다시 연결 |
| 그 외 | `UNKNOWN` | `연결 실패: <원문>` (원문 없으면 일반 문구) |

`ConnectionErrorMessages.userMessage(kind, raw)` / `detail(kind, raw)` — 둘 다 순수 함수.
UI는 **친절한 문구를 주 메시지**(`bodyMedium`, error 색), **원문을 보조 줄**(`bodySmall`, onSurfaceVariant)로
보여준다. `UNKNOWN`에서는 주 메시지가 이미 원문을 품으므로 보조 줄을 생략(중복 방지).

### 3.4 기존 계약 유지 여부 (스펙 4)
- 내부 문자열 **전부 그대로**: `"Heartbeat timeout"`, `"Connection lost"`,
  `"Session handshake failed"`, `"Connection failed"`, `"Send failed"`, `"Reconnect failed: "` 접두사.
- 재연결 트리거 로직은 문자열이 아니라 `generation` CAS + `reconnectPolicy.isActive`에 의존함을 확인 — 무변경.
- `ConnectionState.Error`의 `kind`는 **기본값 `UNKNOWN`**이라 1-인자 생성이 계속 컴파일된다.
  단 `data class` 동등성에는 포함되므로, 리포지토리가 실제로 kind를 세팅하는 12개 단언은
  2번째 인자를 명시하도록 갱신했다(의도된 계약 변경, 검증 강도는 오히려 상승).

---

## 4. 테스트

실행 명령(요구대로 clean 포함, 실제 실행함):
```
cd C:\Github\phone_pad\phone_pad_app
./gradlew --offline :app:cleanTestDebugUnitTest :app:testDebugUnitTest
```
결과: `BUILD SUCCESSFUL` / XML 리포트 집계 **tests=258, failures=0, errors=0, skipped=0**
(기준선 217 → +41, 회귀 0).

### 신규 테스트 목록
**`ConnectionErrorClassifierTest` (10)** — 거부 / 메시지 없는 `ConnectException` / 타임아웃(JVM·Android 문구 둘 다) /
주소 해석 / 라우팅·포트 불가 / 메시지로만 잡히는 네트워크 없음 / 원인 체인 추적 / 자기 참조 cause 무한루프 방지 /
단서 없음(null·빈 예외) / 메시지 전용 경로.

**`ConnectionErrorMessagesTest` (12)** — 모든 enum 값이 한글 문구를 가짐(값 추가 시 누락 검출) /
종류별 조치 힌트 포함(서버·방화벽·포트 상수·IP·Wi-Fi·"Phone Pad") /
**원인을 아는 종류의 주 메시지에 예외 원문이 섞이지 않음** / 원문은 보조 줄로 보존 /
`UNKNOWN` 접두사 + 보조 줄 생략 / null·빈·공백 원문 폴백 / **내부 진단 리터럴 4종이 화면에 그대로 나가지 않음**.

**`TcpClientConnectTimeoutTest` (7)** — 기본 타임아웃이 `CONNECT_TIMEOUT_MS`로 전달됨 /
대상 host·port 정확성 / 타임아웃 값 주입 가능(**실제 5초 대기 없음**) /
`SocketTimeoutException` 그대로 전파 / 실패 후 죽은 소켓 미잔류(`isConnected=false`, `soTimeoutMillis=null`) /
루프백 닫힌 포트로 즉시 실패(실소켓 회귀) / 실패한 연결 뒤 정상 연결 성공(루프백 핸드셰이크까지).

**`TrackpadRepositoryCancelConnectTest` (8)** — 전부 가상 시간, 실제 소켓·실제 대기 없음.
취소 → `Disconnected`(Error 아님) / 취소가 소켓을 닫아 블로킹 접속을 깨움 /
**뒤늦은 성공이 `Connected`로 덮어쓰지 않음**(+ 소켓 정리 + UDP 미개방) /
**뒤늦은 실패가 `Error`로 덮어쓰지 않음** / 취소 후 재연결 정상 동작 /
`Connected` 상태의 취소는 무해 / `Reconnecting` 상태의 취소는 무해(재연결 취소는 `disconnect()` 담당) /
`Disconnected`에서의 취소는 완전 무동작.
→ **모든 테스트를 `Disconnected` 상태 또는 `repository.disconnect()`로 종료**(섹션 6 `runTest` 함정 회피).

**`GestureConfigTest` (+2)** — `CONNECT_TIMEOUT_MS == 5000` / 0(무한 대기) 금지 +
연결+핸드셰이크 최악 ≤ 10초 + TCP 재전송을 덮는 ≥ 3초.

**`TrackpadViewModelTest` (+2)** — `cancelConnect()`가 `repository.cancelConnect()`만 호출(=`disconnect()` 아님), 그 역도 성립.

---

## 5. 미해결 이슈 / 한계

1. **UI 미확인.** `ConnectingPanel`의 "취소" 버튼 렌더·탭 반응, 오류 2줄의 소형 화면 줄바꿈·잘림,
   취소 후 IP 입력값(`hostInput`)이 남아 있는지의 **실제 화면 확인은 못 했다**(에뮬레이터/실기기 없음).
   코드상 `hostInput`은 ViewModel이 별도 보관하므로 취소 후에도 유지된다.
2. **타임아웃 실제 만료 경로는 단위 테스트로 재현하지 않았다.** 블랙홀 주소가 필요해 환경에 따라
   불안정하다. 고정한 것은 "OS 기본값에 맡기지 않고 우리 값을 `connect(endpoint, timeout)`에 넘긴다"는 계약이며,
   실제 5초 만료 체감은 실기기(틀린 IP 입력)로 확인 필요.
3. **`cancelConnect()`의 `cleanUp()`이 호출자 디스패처(프로덕션에서는 Main)에서 소켓을 닫는다.**
   기존 `disconnect()`와 동일한 패턴이고 `Socket.close()`는 블로킹하지 않지만, 엄밀히는 IO 디스패처로
   옮기는 편이 낫다 — 기존 코드와의 일관성을 택했다.
4. **`Connecting` 직전의 취소 창.** `connect()`가 락을 잡기 전(= 상태가 아직 `Connecting`이 아님)에는
   취소 버튼 자체가 화면에 없으므로 실사용 영향은 없다. `disconnect()`가 `connectEpoch`를 올려 덮는다.
5. 범위 밖으로 남긴 것: IP 형식 검증, 포트 입력 UI, 앱 백그라운드 진입 시 드래그 종료, 서버 자동 탐색.

---

## 6. 리더가 AGENTS.md에 반영할 내용 (제안)

### 섹션 6 — Phase 4 체크박스
```
- [x] 예외 처리 강화 (네트워크 오류, 권한 오류 등) — 서버/Android 각각 단일 사이드(와이어 프로토콜 무변경).
      Android: 연결 타임아웃 5초 + 첫 연결 취소 + 예외 원문 대신 한국어 조치 힌트.
```
("권한 오류"는 이번 범위에서 다루지 않았음 — 조사로 확인된 결함만 처리했다는 점을 함께 적으면 좋겠다.)

### 섹션 6 — "예외 처리 강화 구현 시 핵심 파일/설계" (새 소절 제안)
- `presentation/util/GestureConfig.kt` — `CONNECT_TIMEOUT_MS`(5초)는 `SESSION_HANDSHAKE_TIMEOUT_MS`(3초)와
  **직렬**로 붙어 최악 8초. 0은 "무한 대기"라 금지(`GestureConfigTest`가 고정).
- `data/network/TcpClient.kt` — `Socket()` + `connect(InetSocketAddress, timeout)`. 소켓을 **연결 시도 전에**
  필드에 등록하는 것이 취소 설계의 전제(블로킹 `connect()`는 코루틴 취소로 풀리지 않아 소켓 close가 유일한 수단).
  테스트 주입점 `connectTimeoutMs`/`socketFactory`(`internal`) — 실제 5초를 기다리는 테스트를 만들지 않기 위한 것.
- `data/repository/TrackpadRepositoryImpl.kt` — 경합 장치가 이제 **4종**이다:
  `connectionMutex` + `generation`(연결 단위) + `reconnectEpoch`(재시도 묶음) + **`connectEpoch`(수동 시도 단위, 신규)**.
  `connectEpoch`는 수동 `connect`/`cancelConnect`/`disconnect`만 올리고 **재연결 루프는 절대 올리지 않는다**
  (올리면 락을 기다리던 사용자의 수동 연결이 무효화된다). `cancelConnect()`는 전부 뮤텍스 **밖**에서
  "무효화 → 상태 되돌림 → 소켓 close" 순서로 한다.
  `openConnection`은 `ConnectOutcome`(Success/**Cancelled**/Failure)을 돌려줘 실패와 취소를 구분한다.
- `domain/model/ConnectionErrorKind.kt` / `ConnectionErrorClassifier.kt` /
  `presentation/util/ConnectionErrorMessages.kt` — **내부 진단 문자열과 사용자 문구를 분리한다.**
  `ConnectionState.Error.message`는 계약(기존 리터럴 유지)이고, 한국어 변환은 `kind`를 보고
  표시 계층에서만 한다. `kind`는 기본값 `UNKNOWN`이라 1-인자 생성이 계속 가능하지만 동등성에는 포함된다.
- `presentation/trackpad/ConnectionErrorSection.kt` — 오류 표시 전용 컴포저블.
  `TrackpadScreen`의 변경 면적을 줄이기 위한 분리(연결 화면을 동시에 손보는 작업과의 충돌 완화).

### 섹션 9 — 컨벤션 추가 제안
> 사용자에게 보이는 오류 문구와 내부 상태 문자열을 섞지 않는다. `ConnectionState.Error.message`는
> 진단용 계약이고, 한국어 문구는 `ConnectionErrorKind` → `ConnectionErrorMessages` 경로로 표시 계층에서만 만든다.
> 문구 전문을 테스트로 고정하지 말고(다듬을 수 있어야 한다) "원문이 주 메시지를 점령하지 않는다",
> "모든 kind가 문구를 갖는다" 같은 **계약**을 고정한다.

### 섹션 10 — 미결 사항 추가 제안
| 항목 | 현황 |
|---|---|
| 연결 타임아웃/취소 실기기 검증 | `CONNECT_TIMEOUT_MS`(5초) 만료 체감, `Connecting` 화면의 "취소" 버튼 렌더·반응, 오류 2줄의 소형 화면 잘림은 실기기 미검증(컴파일·JVM 단위 테스트만 통과). 타임아웃이 실제로 만료되는 경로는 블랙홀 주소가 필요해 단위 테스트로 재현하지 않았고, 고정한 것은 "OS 기본값에 맡기지 않는다"는 계약이다 |
| 오류 문구의 다국어 | `ConnectionErrorMessages`가 한국어 문자열을 코드에 직접 담고 있다(단일 로케일 전제). 다국어가 필요해지면 이 파일 하나만 `strings.xml`로 옮기면 된다 |
