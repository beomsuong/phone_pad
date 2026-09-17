# 03 · android-dev 작업 요약 — TCP Heartbeat (Phase 2)

**담당:** android-dev
**기준 스펙:** `_workspace/00_input/request.md` "확정 스펙" (리더 사전 확정, 필드명/상수명/타이밍 무변경)
**배경:** `_workspace/02_protocol-qa_report.md` F-1 — Android가 핸드셰이크 후 TCP를 전혀 읽지 않아, 서버가 세션을 회수해도 앱은 `Connected`로 남고 커서만 조용히 멈추던 공백을 해소
**빌드/테스트 검증:** 실제 실행 완료 (`:app:testDebugUnitTest` 40 tests / 0 failures, `:app:assembleDebug` BUILD SUCCESSFUL)

---

## 1. 변경 파일 목록

| 파일 | 변경 내용 |
|------|-----------|
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/util/GestureConfig.kt` | `HEARTBEAT_INTERVAL_MS = 5000L`, `HEARTBEAT_MISS_LIMIT = 3` 추가 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/data/network/TcpClient.kt` | 핸드셰이크 성공 시 `soTimeout`을 `HEARTBEAT_INTERVAL_MS`로 전환(이전의 "원래 타임아웃 복원" 제거), `applyHeartbeatTimeout()`·`suspend fun readLine(): String?`·`soTimeoutMillis` 노출 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/data/repository/TrackpadRepositoryImpl.kt` | heartbeat sender / watchdog 루프, 연결 세대(generation) 기반 무효화, `reportConnectionLost()` 추가 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/di/DispatcherModule.kt` | **신규** — `@IoDispatcher` 한정자 + `Dispatchers.IO` 제공 (루프용 디스패처를 테스트에서 `TestDispatcher`로 교체 가능하게) |
| `phone_pad_app/app/src/test/.../data/repository/TrackpadRepositoryHeartbeatTest.kt` | **신규** — heartbeat 타이밍/판정/취소 테스트 11개 (가상 시간) |
| `phone_pad_app/app/src/test/.../data/network/TcpClientTest.kt` | **신규** — 루프백 소켓 기반 TcpClient 테스트 4개 |
| `phone_pad_app/app/src/test/.../data/repository/TrackpadRepositoryImplTest.kt` | 생성자에 `loopDispatcher`(StandardTestDispatcher) 추가만 — 기존 11개 검증 내용 무변경 |
| `phone_pad_app/app/src/test/.../presentation/util/GestureConfigTest.kt` | heartbeat 상수 회귀 테스트 1개 추가 |

기존 UDP MOVE 분기 / CLICK TCP 분기 / 세션 핸드셰이크 로직은 **무변경**이다.

## 2. 와이어 포맷 (확정 스펙과 동일, 변경 없음)

```jsonc
// Android → Server (TCP 9000, newline-delimited)
{"type":"HEARTBEAT"}
// Server → Android (TCP 9000)
{"type":"HEARTBEAT_ACK"}
```

- 전송 문자열은 상수 하나(`HEARTBEAT_JSON`)에서만 생성되며, 테스트가 `{"type":"HEARTBEAT"}`와 정확히 일치함을 고정한다.
- heartbeat는 **TCP 전용**. 테스트로 `udpClient.send`가 한 번도 호출되지 않음을 검증(AGENTS.md 섹션 4 채널 원칙).

## 3. 구현한 타이밍 / 판정 로직

### sender 루프
- `connect()` 성공 직후 리포지토리 소유 스코프(`CoroutineScope(SupervisorJob() + @IoDispatcher)`)에서 시작.
- `delay(HEARTBEAT_INTERVAL_MS)` → 전송 순서이므로 **첫 HEARTBEAT는 연결 후 5초 시점**에 나간다(핸드셰이크 직후 중복 전송 방지).
- 전송 자체가 예외를 던지면(소켓 사망) 카운터를 기다리지 않고 `Error("Connection lost")` + cleanUp.

### watchdog(reader) 루프 — 카운터 기반
| 조건 | 처리 |
|---|---|
| `readLine()`이 `SocketTimeoutException` | 미응답 +1. `HEARTBEAT_MISS_LIMIT`(3) 도달 시 `ConnectionState.Error("Heartbeat timeout")` + cleanUp |
| 아무 줄이나 성공 수신 (ACK 여부 무관) | 미응답 카운터 0으로 리셋 |
| `readLine()`이 `null` (EOF) | 즉시 `Error("Connection lost")` + cleanUp |
| 기타 예외(IOException 등) | 즉시 `Error("Connection lost")` + cleanUp |

- 소켓 `soTimeout = HEARTBEAT_INTERVAL_MS`이므로 읽기 1회 = 5초 창 → **5초 × 3회 ≈ 15초**에 타임아웃 판정(AGENTS.md 섹션 6). 테스트에서 14,999ms까지 `Connected`, 15,000ms에 `Error`임을 고정.
- 두 루프 모두 `try/catch`로 예외를 삼키고(`CancellationException`은 재전파) 종료하므로 앱이 죽지 않는다.

### 이전 루프 취소 (F-2와 동일한 무효화 정신)
- `AtomicInteger generation`: `connect()`/`disconnect()`/실패 판정마다 +1. 루프는 시작 시점 세대를 캡처하고, 상태를 바꾸기 전 `compareAndSet`으로 세대를 선점한 루프만 상태를 바꾼다 → 블로킹 읽기에 걸려 즉시 취소되지 않은 옛 루프가 새 연결을 깨뜨릴 수 없고, sender/watchdog이 동시에 실패를 보고하지도 않는다.
- `connect()` 진입 시(핸드셰이크 시도 **전**)와 `disconnect()` 시 `stopKeepAlive()`로 이전 스코프를 `cancel()`한다 → 소켓 읽기에 매달린 reader 코루틴이 좀비로 남지 않음(테스트로 취소 횟수 직접 검증).
- 실패 판정 시에도 스코프를 취소해 형제 루프까지 함께 정리한다.

### UI 반영
`TrackpadViewModel`은 `repository.connectionState`를 그대로 UI 상태로 흘리므로, heartbeat 판정 결과(`Error`)가 별도 변경 없이 화면에 나타난다. ViewModel은 여전히 UseCase/Repository만 사용하며 네트워크 계층을 직접 호출하지 않는다.

## 4. 테스트 목록 (전부 실제 실행, 40 tests / 0 failures / 0 errors)

`TrackpadRepositoryHeartbeatTest` (11) — 가상 시간, 읽기 타임아웃을 `delay(5s)` 후 `SocketTimeoutException`으로 모사
1. HEARTBEAT는 5초 주기로 TCP로만 전송된다 (4,999ms=0회 / 5,000ms=1회 / 15,000ms=3회, UDP 0회)
2. 아무 줄이나 수신하면 미응답 카운터가 0으로 리셋된다 (타임아웃 2 → ACK → 타임아웃 2, 누적 4회지만 연결 유지)
3. 연속 3회 타임아웃이면 `Error("Heartbeat timeout")` + `tcpClient.disconnect()`/`udpClient.close()` 1회씩, 이후 heartbeat 정지
4. EOF(null)를 만나면 카운터를 기다리지 않고 즉시 `Error("Connection lost")` + 정리
5. IOException 등 기타 예외도 즉시 `Connection lost`
6. HEARTBEAT 전송 실패는 즉시 `Connection lost`
7. 재연결하면 이전 루프가 취소되고 heartbeat는 주기당 1회만 나간다 (좀비 sender 없음)
8. `disconnect`와 재연결은 이전 reader 코루틴을 **실제로 취소**한다 (취소 횟수 0→1→2 검증)
9. disconnect 후 heartbeat 정지 + 옛 watchdog이 `Disconnected`를 `Error`로 덮어쓰지 못한다
10. 핸드셰이크 실패 시 루프를 시작하지 않는다 (`send`/`readLine` 0회)
11. HEARTBEAT JSON이 확정 스펙 문자열과 정확히 일치한다

`TcpClientTest` (4, 루프백 `ServerSocket`)
12. 핸드셰이크 성공 후 `soTimeout == HEARTBEAT_INTERVAL_MS`
13. `readLine()`이 서버가 보낸 줄을 개행 없이 반환 (`{"type":"HEARTBEAT_ACK"}`)
14. 서버가 먼저 끊으면 `readLine()`이 `null`(EOF)
15. `send()`가 newline 종료 JSON 한 줄을 그대로 내보낸다

`GestureConfigTest` (+1)
16. `HEARTBEAT_INTERVAL_MS == 5000L`, `HEARTBEAT_MISS_LIMIT == 3`, 곱이 15,000ms, `soTimeout(Int)` 범위 내 — 서버 상수(`HEARTBEAT_INTERVAL_S=5.0`, `HEARTBEAT_MISS_LIMIT=3`)와 대칭 고정

기존 테스트: `TrackpadRepositoryImplTest` 11, `SessionHandshakeTest` 8, `SendEventUseCaseTest` 1, `ExampleUnitTest` 1 — 전부 통과(회귀 없음).

**실행 로그**
```
cd phone_pad_app && ./gradlew :app:testDebugUnitTest --offline   → BUILD SUCCESSFUL (컴파일 경고 0)
   test-results XML: 8 + 4 + 11 + 11 + 1 + 1 + 4 = 40 tests, failures=0, errors=0
cd phone_pad_app && ./gradlew :app:assembleDebug --offline       → BUILD SUCCESSFUL (Hilt 그래프 검증 = @IoDispatcher 바인딩 정상)
```

**돌연변이 검증(테스트가 비어있지 않음을 확인):** watchdog의 `missedBeats = 0` 리셋을 제거하면 2번 테스트가 `expected:<Connected> but was:<Error(Heartbeat timeout)>`로 실패했고, `invalidateCurrentConnection()`의 `stopKeepAlive()`를 제거하면 8번 테스트가 좀비 코루틴 때문에 완료되지 못했다. 두 돌연변이 모두 되돌린 뒤 최종 그린을 재확인했다.

## 5. 남은 이슈 / protocol-qa 참고

1. **HEARTBEAT는 `TrackpadEvent` sealed class에 추가하지 않았다.** heartbeat는 제스처가 아니라 전송 계층의 연결 유지 관심사이므로 리포지토리 내부에서만 생성한다(`sendEvent`에 HEARTBEAT 분기 없음). presentation 계층이 heartbeat를 임의로 발사할 수 없게 하는 의도적 선택이다 — QA가 "이벤트 추가 체크리스트 미이행"으로 볼 수 있어 근거를 남긴다.
2. **`TcpClient.send()`는 소켓이 죽어도 예외를 던지지 않는다.** `PrintWriter(autoFlush=true)`가 IOException을 내부 에러 플래그로 삼켜서, sender 루프의 "전송 실패 → Connection lost" 경로는 실제 기기에서 거의 발동하지 않는다. 연결 손실 감지는 watchdog(EOF/RST 즉시, 또는 15초 타임아웃)이 담당하므로 기능 공백은 없다. 개선안: `send()` 후 `writer.checkError()`가 true면 IOException을 던진다 (CLICK 전송 실패도 함께 표면화되므로 별도 이슈로 제안).
3. **첫 HEARTBEAT는 연결 후 5초 시점.** 서버는 미응답 3회(≈15초)까지 버티므로 오탐 없음. 서버가 "핸드셰이크 직후 첫 HEARTBEAT 도착"을 가정하지 않는지 server-dev 측 구현 확인이 필요하다.
4. **watchdog은 수신한 줄의 내용을 검사하지 않는다** (확정 스펙: "아무 줄이나 성공 수신 시 카운트 0"). 즉 `HEARTBEAT_ACK`가 아닌 다른 줄도 생존 신호로 취급한다 — 서버 판정과 대칭이며 의도된 동작.
5. **자동 재연결은 여전히 미구현(Phase 4).** heartbeat가 `Error`로 전환해 주므로 화면에는 즉시 드러나지만, 재연결은 사용자 조작이 필요하다.
6. **실기기 E2E 미검증.** 검증은 유닛 테스트 + 루프백 소켓 범위다. 실제 폰↔PC에서 15초 판정과 ACK 왕복은 protocol-qa/리더의 E2E 단계에서 확인이 필요하다.
7. **AGENTS.md 갱신 필요(리더 담당):** 섹션 4의 `{"type":"HEARTBEAT"}` "(Phase 2, 미구현)" 주석 제거 + `HEARTBEAT_ACK` 추가, 섹션 5 상수 표에 `HEARTBEAT_INTERVAL_MS`/`HEARTBEAT_MISS_LIMIT` 추가, 섹션 6 Phase 2 heartbeat 체크박스 `[x]`(+ F-1 공백 문구 삭제), 섹션 7 다이어그램의 HEARTBEAT/HEARTBEAT_ACK "(미구현)" 표기 제거, 섹션 2 트리에 `di/DispatcherModule.kt` 추가.
