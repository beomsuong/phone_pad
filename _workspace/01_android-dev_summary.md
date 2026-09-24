# android-dev 요약 — 단일 클라이언트 정책 (`SESSION_REPLACED`)

## 한 줄

서버가 보내는 `{"type":"SESSION_REPLACED"}` 한 줄을 heartbeat watchdog이 인식해, **자동 재연결을 시작하지 않고 곧바로 `Error(kind=SESSION_REPLACED)`** 로 전환한다. 요청서 "Android 스펙" 1~8 전 항목 구현 완료. 와이어/필드/채널 변경 없음(신규 수신 메시지 1종만 추가).

## 변경 파일

### 신규 (main)
| 파일 | 내용 |
|------|------|
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/data/network/SessionReplacedNotice.kt` | 순수 리터럴 판별기. `SESSION_REPLACED_TYPE` 상수 + `isSessionReplaced(line: String?): Boolean`. `SessionHandshake`/`AuthHandshake`와 동일한 정규식 스타일(전체 JSON 파서 미사용) |

### 수정 (main)
| 파일 | 내용 |
|------|------|
| `.../data/repository/TrackpadRepositoryImpl.kt` | ① `heartbeatWatchdogLoop()`: `readLine()`이 준 줄이 `SessionReplacedNotice.isSessionReplaced(line)`이면 **카운터를 리셋하지 않고** `reportConnectionLost(forGeneration, MESSAGE_SESSION_REPLACED, ConnectionErrorKind.SESSION_REPLACED)` 후 `return`. 그 외 줄은 기존과 완전히 동일(파싱 없음). ② `reportConnectionLost()`: `kind == SESSION_REPLACED`면 재연결 정책을 **보지 않고** 곧바로 `Error(message, kind)`로 가고 return. 다른 kind는 기존 분기 무수정. ③ companion object에 `MESSAGE_SESSION_REPLACED = "Session replaced by another device"` |
| `.../domain/model/ConnectionErrorKind.kt` | `SESSION_REPLACED` 추가 (+ 왜 `CONNECTION_LOST`와 분리하는지 KDoc). `ConnectionErrorClassifier`는 **무수정** — 이 kind는 `Throwable`에서 분류되지 않고 코드가 직접 지정한다(`HEARTBEAT_TIMEOUT`/`RECONNECT_FAILED`와 같은 방식) |
| `.../presentation/util/ConnectionErrorMessages.kt` | `SESSION_REPLACED` 한국어 문구 추가: "다른 기기가 이 PC에 새로 연결되어 현재 연결이 끊겼습니다. 자동으로 다시 연결되지 않으니, 계속 사용하려면 직접 다시 연결하세요." |

**UI diff 없음** — `ConnectionErrorSection`/Disconnected-Error 화면은 kind를 하드코딩하지 않고 `ConnectionErrorMessages`만 거치므로 무수정으로 새 문구를 표시한다(`grep`으로 main 전체의 `ConnectionErrorKind.` 사용처를 확인 — 분기하는 곳은 리포지토리와 Classifier뿐).

### 신규/수정 (test)
| 파일 | 테스트 수 |
|------|-----------|
| `.../data/network/SessionReplacedNoticeTest.kt` (신규) | 10 |
| `.../data/repository/TrackpadRepositorySessionReplacedTest.kt` (신규) | 9 |
| `.../presentation/util/ConnectionErrorMessagesTest.kt` (수정) | 12 → 14 (+2) |

신규 테스트 합계 **+21**.

`SessionReplacedNoticeTest`는 정상 인식보다 **거절**을 촘촘히 고정한다(HEARTBEAT_ACK, SESSION 핸드셰이크, `SESSION_REPLACED_LATER`/`XSESSION_REPLACED`/소문자, `type`이 아닌 필드에 같은 문자열이 들어간 줄, 비JSON, null/빈 줄). 이 한 줄이 "자동 재연결을 안 한다"는 예외 경로의 유일한 스위치라 거짓 양성이 훨씬 위험하기 때문.

`TrackpadRepositorySessionReplacedTest`는 **`ReconnectPolicy.Default`(실제 앱 정책)를 주입한 채로** 검증한다. 상태 전이를 전부 수집해 `Reconnecting`을 한 프레임도 거치지 않음을 고정하고, 120초(기본 백오프 총량 ~55초의 2배 이상)를 진행해도 `tcpClient.connect`/`send`가 0회임을 확인한다. 회귀 3건도 함께: HEARTBEAT_ACK·임의의 알 수 없는 줄은 여전히 카운터만 리셋, **평범한 EOF는 여전히 재연결을 시작**(새 예외가 일반 유실 경로를 삼키지 않음).

## 테스트 결과 (강제 재실행 XML 집계)

명령: `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest`
집계: `app/build/test-results/testDebugUnitTest/*.xml` 39개 파일 합산

```
suites=39  tests=441  failures=0  errors=0  skipped=0
```

기준선 **420 → 441 (+21), 회귀 0.** `BUILD SUCCESSFUL`.

### 변이 검사 (새 테스트가 실제로 버그를 잡는지)

`heartbeatWatchdogLoop`의 판정 분기를 `if (false && ...)`로 무력화하고 신규 테스트 클래스만 실행 → **9 tests completed, 6 failed** (`BUILD FAILED`). 이후 원복하고 전체 재실행해 441 전건 통과를 다시 확인했다. 즉 새 테스트는 구현이 빠지면 확실히 실패한다.

## 스펙 이탈 여부

**이탈 없음.** 다만 한 군데 시그니처가 요청서 문구와 미세하게 다르므로 밝혀 둔다:

- 요청서: `SessionReplacedNotice.isSessionReplaced(line: String): Boolean`
- 구현: `isSessionReplaced(line: String?): Boolean` (**nullable**)

요청서가 함께 지시한 "`SessionHandshake`/`AuthHandshake`와 같은 스타일"을 따른 결과다 — 두 기존 파일 모두 `String?`을 받고 null/공백을 false/null로 돌린다. 호출부는 이미 null 검사를 통과한 비-null `line`을 넘기므로 동작 차이는 없고, 와이어 스펙·필드명·채널·상태 전이는 요청서 그대로다.

그 밖에는 전부 스펙대로다: 채널 무변경(TCP 9000 수신), `TrackpadEvent` sealed class **미수정**(연결 유지/전송 계층 전용 메시지 — AGENTS.md 섹션 9 예외 규칙), `GestureConfig` 신규 상수 없음(임계값이 필요 없는 변경), `ConnectionErrorClassifier` 무수정, UI 컴포넌트 무수정.

## 경계면 교차 확인 (server-dev의 미커밋 코드, 읽기만 함)

`pc_server/single_client.py:35`가 `json.dumps({"type": "SESSION_REPLACED"}, separators=(",", ":")) + "\n"` → 바이트로 `{"type":"SESSION_REPLACED"}\n`. `pc_server/tests/test_single_client.py:175`가 같은 리터럴을 고정. 내 파서와 정확히 일치한다. (`pc_server/`는 **전혀 건드리지 않았다** — `git status`상 서버 쪽 변경은 전부 병렬 server-dev의 것이다.)

## 미해결 이슈 / 한계

1. **알림이 유실되면 정책이 무력화된다 (잔여 위험, 앱 단독으로는 해결 불가).**
   서버의 `SESSION_REPLACED` 전송은 스펙상 best-effort다. 그 한 줄이 도달하지 못하고 소켓만 닫히면 앱은 **평범한 EOF**로 관측해 `CONNECTION_LOST` → 자동 재연결을 시작하고, 재연결이 성공하면 방금 들어온 기기를 다시 밀어낸다 — 정확히 이 정책이 막으려던 핑퐁이다. 발생 확률은 낮다(살아 있는 소켓에 `sendall` 한 줄). 앱 쪽에서 EOF만 보고 "밀려남"을 추론할 방법은 없으므로(구분 불가), 완전 차단은 프로토콜 변경(예: 서버가 최근 밀어낸 주소의 재입장을 잠시 거절)이 필요하다. **범위 밖으로 두고 기록만 한다.**

2. **밀려난 순간 드래그 중이면 앱은 `DRAG_END`를 보낼 수 없다.** 소켓이 이미 닫힌 뒤라 전송이 실패하고, 세대 CAS가 이미 소비되어 그 실패 보고는 조용히 버려진다(상태 오염 없음 — 의도된 동작). PC 버튼 해제는 **서버의 연결 종료 시 강제 해제 안전장치**에 전적으로 의존한다. 기존 "앱 백그라운드 진입 시 드래그 미종료" 항목과 같은 뿌리.

3. **실기기 미검증.** 밀려난 순간의 화면 전환 체감, 새 문구(2줄)의 소형 화면 잘림, 실제로 두 대의 폰으로 밀어내기를 했을 때의 왕복. JVM 단위 테스트와 컴파일만 통과한 상태다.

4. **`SESSION_REPLACED`는 `Connected` 상태에서만 관측된다** — watchdog 루프가 그때만 돌기 때문이며 이는 스펙과 일치한다(서버도 AUTH를 통과한 활성 연결에만 보낸다). 재연결 대기 중이거나 AUTH 단계인 클라이언트는 이 경로를 타지 않는다.

## 리더가 AGENTS.md에 반영할 내용

### 섹션 4 (통신 프로토콜) — 추가
TCP 9000 메시지 목록에 서버→클라이언트 알림 1종:
```jsonc
// 단일 클라이언트 정책 - 새 연결이 AUTH를 통과해 이 연결이 밀려났을 때, 강제로 닫기 직전 딱 한 번.
// 필드 없음, session 없음(이 연결이 곧 끊기므로 실을 정보가 없다). 전송은 best-effort.
// AUTH를 통과하지 못한 시도(PIN 불일치/형식 오류/타임아웃/브루트포스 잠금)는 기존 연결에 영향 없음.
{"type":"SESSION_REPLACED"}
```
- HEARTBEAT/HEARTBEAT_ACK와 같은 **연결 유지/전송 계층 전용 메시지**라 `TrackpadEvent` sealed class에 넣지 않았다(섹션 9 예외 규칙의 세 번째 사례). Android는 `SessionReplacedNotice` 순수 판별기 + `TrackpadRepositoryHeartbeatTest` 계열의 리터럴 고정 테스트로 계약을 잠갔다.
- **끄는 옵션 없음** — 제품 정책이지 개발 편의 토글이 아니다.

### 섹션 6 (자동 재연결 설계) — 예외 규칙 추가
"Connected였던 세션의 유실은 항상 재연결을 시작한다"에 **두 번째 의도된 예외**를 명시:
- 기존 예외: `AUTH_FAILED`(재연결 루프가 백오프를 소진하지 않고 즉시 중단 — 서버의 브루트포스 잠금 자초 방지)
- 신규 예외: `SESSION_REPLACED`(정책을 **확인조차 하지 않고** 곧바로 `Error`). 재연결은 곧 "방금 나를 밀어낸 기기를 내가 다시 밀어내기"이고, 상대도 같은 앱이라 똑같이 되돌아와 두 기기가 서로를 무한히 쫓아낸다. 막는 것은 **자동** 재연결뿐이며 수동 재연결은 정상 동작한다(테스트로 고정).

### 섹션 9 (코딩 컨벤션) — 함정 1건 추가
- **리포지토리 상태 테스트는 `finally`에서 반드시 `disconnect()`를 부른다.** `TrackpadRepositoryImpl`의 keep-alive/재연결 스코프는 테스트 스코프의 자식이 아니라 본문이 예외로 끝나도 취소되지 않는다. 연결이 살아 있는 채로 단언이 깨지면 `runTest`의 정리 단계가 영원히 도는 가상 시간을 따라가느라 **테스트가 실패하는 대신 멈춘다**. 이번 작업의 변이 검사에서 실측했다 — 판정 분기를 무력화한 첫 실행이 10분을 넘겨도 끝나지 않았고, 테스트를 `withRepository { }` try/finally 패턴으로 감싼 뒤 같은 변이가 `9 tests, 6 failed`로 26초 만에 깔끔히 실패했다. 기존 섹션 10의 "`runTest`가 본문 종료 후에도 가상 시간을 진행해 OOM" 항목과 같은 뿌리이나, **정상 종료가 아니라 실패 경로**라 기존 "테스트를 `disconnect()`로 끝낸다" 지침만으로는 안 잡힌다.

### 섹션 10 (미결 사항) — 두 항목에 대한 판단

**"다중 기기 연결" — 정책 부분은 해소, 구조 부분은 미해소. 삭제하지 말고 재작성 권장.**
- 해소: "정책 미정"이 끝났다(**단일 클라이언트**로 확정). 관측 가능한 증상 — 여러 기기가 동시에 커서를 움직이는 상황 — 은 서버가 활성 클라이언트를 항상 최대 1개로 유지하므로 더 이상 발생하지 않는다.
- 미해소: 근본 원인인 **세션별 상태 분리**는 그대로다. `_drag_active`는 여전히 프로세스 전역이고, 서버는 여전히 세션 집합 기반이다. 단일 클라이언트 정책은 그 구조 위에 얹은 **입장 제한**이지 상태 분리가 아니다. 제안 문구: "정책 확정(단일 클라이언트, Phase 5) — 동시 조작은 입장 단계에서 차단된다. 다만 세션별 상태 분리는 여전히 없어, 다중 기기를 실제로 지원하게 되면 `_drag_active` 등 프로세스 전역 상태부터 분리해야 한다."

**"재연결 직후 서버 세션 2개" — 대부분 해소. 창이 크게 줄었으나 0은 아니다.**
- 해소된 부분: 앱이 재연결하면 새 연결의 AUTH 통과가 **곧바로** 옛 연결을 밀어내고 회수한다. 예전에는 서버가 옛 연결의 EOF나 heartbeat 15초 타임아웃을 기다려야 했으므로 세션 2개 구간이 **최대 15초**였는데, 이제는 밀어내기 정리 지연 수준(밀리초 이하)으로 줄었다. "재연결 후 15초 안에 새 연결에서 드래그를 시작해야 한다"는 기존 발생 조건은 사실상 사라진다.
- 남은 부분: 밀려난 연결의 정리(`finally`의 세션 회수 + 드래그 강제 해제)는 **그 연결을 처리하던 스레드에서 비동기로** 실행되고, 스펙상 새 SESSION 발급은 밀어내기 **직후**다. 따라서 옛 스레드의 강제 해제가 새 클라이언트의 드래그를 놓아버릴 수 있는 창이 원리적으로는 남는다(폭이 극히 좁아 실측으로 재현하기 어렵다). 뿌리는 위 "다중 기기 연결"의 구조 부분과 동일하다.
- 제안: 이 행을 **"해소 대부분"** 으로 격하하되 삭제하지 말고, "다중 기기 연결"의 구조 항목과 함께 해결할 것으로 연결해 두기.
- **주의**: 이 판단은 병렬 server-dev의 **미커밋** 구현(`pc_server/single_client.py`)을 읽고 내린 것이다. 밀어내기/정리 순서에 대한 최종 확인은 server-dev 요약과 protocol-qa 검증에 맡긴다.

### 섹션 10 — 신규 항목 제안
- **단일 클라이언트 알림 유실 시 핑퐁 재발 가능**: 위 "미해결 이슈 1"의 내용. 서버의 `SESSION_REPLACED` 전송이 best-effort라, 그 줄이 도달하지 못하면 앱은 평범한 EOF로 보고 자동 재연결을 시작해 방금 들어온 기기를 다시 밀어낸다. 앱 쪽에서 EOF와 밀려남을 구분할 방법이 없어 완전 차단은 프로토콜 변경이 필요하다. 발생 확률은 낮아 현재는 허용.
- **단일 클라이언트 정책 실기기 미검증**: 폰 두 대로 실제 밀어내기 왕복, 밀려난 화면의 문구 렌더·소형 화면 잘림, 드래그 중 밀려났을 때 서버 안전장치가 실제로 버튼을 놓는지.

## 커밋

**하지 않았다** (요청서 "공통 금지"). `git stash`/`checkout`/`reset`도 일절 사용하지 않았다 — 작업 트리에 병렬 server-dev의 미커밋 변경(`pc_server/server.py`, `tests/fake_conn.py`, `tests/test_server_shutdown.py`, 신규 `single_client.py`, `tests/test_single_client.py`)이 그대로 살아 있음을 `git status`로 확인했다.
