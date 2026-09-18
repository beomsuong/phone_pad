# 01 · android-dev 작업 요약 — MOVE 이벤트 UDP 분리 + 세션 토큰

**담당:** android-dev
**범위:** `phone_pad_app/` (Kotlin + Jetpack Compose)
**상태:** 구현 완료 · **유닛 테스트 23개 전부 통과 (실제 gradle 빌드 검증됨)**

---

## 1. 변경/추가 파일

### main
| 파일 | 변경 내용 |
|------|-----------|
| `app/src/main/java/com/example/phone_pad_app/data/network/TcpClient.kt` | `connect(host, port)`의 반환형을 `Unit` → `String?`(세션 토큰)으로 변경. 연결 직후 `BufferedReader`로 핸드셰이크 한 줄을 읽어 `SessionHandshake.parseSession()`으로 파싱. 읽는 동안만 `soTimeout = SESSION_HANDSHAKE_TIMEOUT_MS` 적용 후 원복. `disconnect()`에서 reader도 정리 |
| `app/src/main/java/com/example/phone_pad_app/data/network/SessionHandshake.kt` | **신규.** 핸드셰이크 한 줄 → 세션 토큰 순수 파서(소켓 비의존, JVM 단위 테스트 대상). 정규식 기반이라 JSON 라이브러리 의존성 추가 없음 |
| `app/src/main/java/com/example/phone_pad_app/data/network/UdpClient.kt` | **신규.** `@Singleton`. `DatagramSocket` + `InetAddress.getByName(host)`로 MOVE JSON을 UDP 패킷 하나로 전송(개행 없음). `connect/send/close/isReady` |
| `app/src/main/java/com/example/phone_pad_app/data/repository/TrackpadRepositoryImpl.kt` | `UdpClient` 주입 추가. `connect()`에서 TCP 핸드셰이크 → 토큰 확보 실패(null/blank) 시 소켓 정리 + `ConnectionState.Error("Session handshake failed")`, 성공 시 `udpClient.connect(host, GestureConfig.UDP_PORT)` 후 `Connected`. `sendEvent()`에서 `Move`는 UDP(+session), `Click`은 TCP로 분기. `disconnect()`는 UDP 소켓 close + 세션 토큰 null 초기화 |
| `app/src/main/java/com/example/phone_pad_app/presentation/util/GestureConfig.kt` | `UDP_PORT = 9001`, `SESSION_HANDSHAKE_TIMEOUT_MS = 3000` 추가. `DEFAULT_PORT` 주석에 채널 용도 명시 |
| `app/src/main/java/com/example/phone_pad_app/presentation/trackpad/TrackpadScreen.kt` | **기존 빌드 오류 수정(범위 외 최소 수정).** `OutlinedTextField`에 `@OptIn(ExperimentalMaterial3Api::class)` 누락으로 `compileDebugKotlin`이 실패하던 상태였음 → `ConnectPanel`에 OptIn 추가. 이 수정 없이는 유닛 테스트 실행 자체가 불가 |
| `app/build.gradle.kts` | `testImplementation("io.mockk:mockk:1.13.8")`, `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")` 추가 |

### test (신규)
- `app/src/test/java/com/example/phone_pad_app/data/network/SessionHandshakeTest.kt`
- `app/src/test/java/com/example/phone_pad_app/data/repository/TrackpadRepositoryImplTest.kt`
- `app/src/test/java/com/example/phone_pad_app/domain/usecase/SendEventUseCaseTest.kt`
- `app/src/test/java/com/example/phone_pad_app/presentation/util/GestureConfigTest.kt`

### 변경하지 않은 파일 (호환 유지)
- `domain/repository/TrackpadRepository.kt` — 인터페이스 시그니처 변경 없음
- `domain/usecase/SendEventUseCase.kt`, `presentation/trackpad/TrackpadViewModel.kt` — 변경 없음. ViewModel은 여전히 UseCase 경유로만 이벤트 전송, 네트워크 계층 직접 호출 없음
- `di/AppModule.kt` — `UdpClient`가 `@Singleton @Inject constructor()`라 별도 provides 불필요

---

## 2. 실제 구현된 와이어 스펙

### TCP 9000 (핸드셰이크)
서버 → 클라이언트, 연결 직후 한 줄(newline 종료):
```json
{"type":"SESSION","session":"0123456789abcdef0123456789abcdef"}
```
파싱 규칙 (`SessionHandshake.parseSession`):
- `type`이 정확히 `"SESSION"`이어야 함. 아니면 null
- `session`이 없거나 빈 문자열/공백이면 null
- 필드 순서·공백·알 수 없는 추가 필드는 허용 (전방 호환)
- null 반환 시 앱은 `Connected`로 전환하지 않고 `Error` 상태 + 소켓 정리

### TCP 9000 (이벤트, newline-delimited · session 필드 없음)
```json
{"type":"CLICK","button":"left"}
```

### UDP 9001 (MOVE 전용, 패킷 하나 = 이벤트 하나, 개행 없음)
```json
{"session":"0123456789abcdef0123456789abcdef","type":"MOVE","dx":2.5,"dy":-1.0}
```
- 필드 순서: `session` → `type` → `dx` → `dy` (서버는 순서 무관 파싱 권장)
- `dx`/`dy`는 Kotlin `Float` 문자열화 → 항상 소수점 포함(`2.5`, `-1.0`, `1.0`). **정수가 아닌 실수로 도착함** — 서버는 `int(float(...))` 등으로 변환 필요
- 세션 토큰이 없는 상태(연결 전/disconnect 후)에서는 MOVE를 아예 전송하지 않음 (고빈도 이벤트라 Error 상태로 덮어쓰지 않고 조용히 드롭)

---

## 3. 테스트 목록 (전부 통과)

`gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, 23 tests / 0 failures / 0 errors

**SessionHandshakeTest (8)**
확정 스펙 파싱 / 공백·필드 순서 변형 / 추가 필드 허용 / type 불일치 시 null / session 누락 시 null / session 빈 값 시 null / null·빈 줄 / 비-JSON 쓰레기 값

**TrackpadRepositoryImplTest (10, MockK + runTest)**
- connect가 TCP 핸드셰이크 후 UDP를 9001로 준비하고 `Connected`로 전환
- Move가 `session` 포함 JSON으로 **UDP로만** 전송되고 TCP로는 전혀 나가지 않음 (JSON 문자열 완전 일치 검증)
- Move JSON에 개행 없음
- Click이 TCP로 전송되고 `session` 필드 없음 (JSON 문자열 완전 일치)
- 핸드셰이크 null → `Error` + UDP 미준비 + TCP 정리
- TCP connect 예외 → `Error` + UDP close + TCP disconnect
- 세션 토큰 없이 보낸 Move는 미전송
- disconnect가 UDP close + TCP disconnect + 세션 초기화(이후 Move 미전송)
- 재연결 시 새 토큰이 MOVE JSON에 반영
- UDP 전송 실패 → `Error` 상태 보고

**SendEventUseCaseTest (1)** UseCase가 이벤트를 그대로 repository에 위임(채널 분기는 repository 책임)

**GestureConfigTest (3)** TCP 9000 / UDP 9001 상이 포트 고정, 핸드셰이크 타임아웃 양수, `MOVE_MIN_DISTANCE_PX < TAP_MAX_DISTANCE_PX`

---

## 4. 남은 이슈 / server-dev·protocol-qa 확인 요청

1. **dx/dy 타입** — Android가 보내는 값은 항상 실수 문자열(`2.5`, `-1.0`). 서버 `SendInput`이 정수만 받으므로 서버 측 캐스팅 필요. (server-dev 확인 요청)
2. **세션 토큰 형식** — 앱은 32자리 hex를 가정하지 않고 "비어 있지 않은 문자열"이면 수용. 서버가 길이/문자셋을 바꿔도 앱은 동작함
3. **핸드셰이크 지연** — 서버가 3초 내에 SESSION 줄을 보내지 않으면 `SocketTimeoutException` → `Error` 상태. 서버는 accept 직후 즉시 전송해야 함
4. **UDP 패킷 유실/순서** — 현 구현은 재전송·시퀀스 번호 없음(Phase 2 범위 외). MOVE는 상대 변위(dx/dy)라 유실 시 커서가 약간 덜 움직이는 정도로 열화
5. **AGENTS.md 미갱신** — 섹션 4(프로토콜)·섹션 6(Phase 2 체크박스)·섹션 7(세션 흐름)의 실제 구현 반영은 오케스트레이터/protocol-qa 판단에 맡김. 섹션 5 감도 상수 표에 `UDP_PORT`/`SESSION_HANDSHAKE_TIMEOUT_MS` 추가 필요
6. **HEARTBEAT 미구현** — 이번 작업 범위 외. TcpClient에 reader가 확보되었으므로 `HEARTBEAT_ACK` 수신 기반 구현이 가능해진 상태
7. **계층 참조** — `data` 계층(`TcpClient`, `TrackpadRepositoryImpl`)이 `presentation.util.GestureConfig`를 참조. 포트 상수를 `GestureConfig`에 두라는 요청 스펙을 따른 결과이며, 엄밀히는 포트/타임아웃을 `data` 또는 `core` 계층 설정 객체로 옮기는 리팩터링이 바람직 (후속 과제)
8. **실기기 통합 테스트 미수행** — 유닛 테스트와 컴파일은 검증됐으나 실제 폰↔PC 왕복 확인은 서버 구현 완료 후 필요
