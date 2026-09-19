# android-dev 요약 — 재연결 로직 (Phase 4)

워크트리: `C:\Github\phone_pad\.claude\worktrees\reconnect-logic` (다른 워크트리/원본 체크아웃 미접근)
범위: **Android 단일 사이드.** 이벤트 `type`/필드/채널/핸드셰이크/서버 코드 **무변경** — 교차 경계면 전환 사유 없음.

## 1. 변경/추가 파일

### 신규
| 파일 | 내용 |
|------|------|
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/domain/model/ReconnectPolicy.kt` | 순수 백오프 정책(`enabled`/`maxAttempts`/`baseDelayMs`/`maxDelayMs`, `delayBeforeAttempt`, `shouldAttempt`, `isActive`). `Default`/`Disabled` 상수 제공 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/di/ReconnectModule.kt` | Hilt `@Provides ReconnectPolicy = ReconnectPolicy.Default` (값 객체라 `@Inject` 생성자 불가) |
| `phone_pad_app/app/src/test/java/com/example/phone_pad_app/domain/model/ReconnectPolicyTest.kt` | 9 tests — 지연 수열/상한/총 55초/오버플로/경계/비활성 |
| `phone_pad_app/app/src/test/java/com/example/phone_pad_app/data/repository/TrackpadRepositoryReconnectTest.kt` | 16 tests — 리포지토리 재연결 시나리오(전부 가상 시간) |

### 수정
| 파일 | 내용 |
|------|------|
| `domain/model/ConnectionState.kt` | `Reconnecting(host, attempt, maxAttempts)` 추가 |
| `presentation/util/GestureConfig.kt` | `RECONNECT_MAX_ATTEMPTS = 8`, `RECONNECT_BASE_DELAY_MS = 1_000L`, `RECONNECT_MAX_DELAY_MS = 10_000L` (heartbeat 상수 바로 아래) |
| `data/repository/TrackpadRepositoryImpl.kt` | 생성자에 `reconnectPolicy` 추가, `connect` 본문을 `openConnection()`으로 추출, 재연결 루프/취소, TCP 전송 실패의 유실 경로 합류 |
| `presentation/trackpad/TrackpadScreen.kt` | `Reconnecting` 분기 렌더(`ConnectingPanel`을 `message`/`hostLabel`/`onCancel` 파라미터로 확장) |
| `data/repository/TrackpadRepositoryImplTest.kt`, `TrackpadRepositoryHeartbeatTest.kt` | 생성자에 `ReconnectPolicy.Disabled` 주입만 추가 — **테스트 로직·단언은 한 줄도 수정하지 않음** |

`TrackpadViewModel`은 무변경(취소는 기존 `disconnect()` 재사용). `TrackpadEvent`/`TcpClient`/`UdpClient`/`SessionHandshake`/서버 무변경.

## 2. 설계 결정과 근거

### 2.1 `connect` 본문 추출 (복제 금지)
`openConnection(host, port): String?`(성공 null / 실패 원인 메시지)을 만들어 **수동 연결과 자동 재연결이 같은 코드**를 타게 했다.
실패 후의 상태 전이만 호출자가 정한다 — 수동은 `Error`, 재연결은 다음 시도 또는 최종 `Error`.
`_connectionState.value = Connecting`도 호출자로 올렸다: 재연결은 `Connecting`이 아니라 `Reconnecting`을 보여야 하기 때문.

### 2.2 재연결 트리거 = "Connected였던 세션의 유실"만
- `lastConnectedHost`/`lastConnectedPort`는 **핸드셰이크 성공 직후에만** 갱신한다 → 실패 중인 IP로는 절대 재시도하지 않는다.
- `reportConnectionLost`의 첫 줄에 `if (_connectionState.value !is Connected) return`을 뒀다. 이 한 줄이 두 사고를 막는다:
  1) 수동 `disconnect()` 뒤 뒤늦게 실패한 전송(예: 300ms 지연 클릭)이 연결을 되살리는 것,
  2) `Reconnecting` 중 도착한 좀비 전송 실패가 진행 중인 재시도를 `Error`로 깨는 것.
  (실제 중재자는 여전히 세대 CAS이고, 이 검사는 "보고 자격"을 좁히는 역할이다.)
- 첫 `connect()` 실패/핸드셰이크 실패는 기존과 100% 동일하게 `Connecting → Error`.

### 2.3 경합 처리 — 세대 / 뮤텍스 / 취소
세 장치를 **역할을 나눠서** 썼다(기존 F-2/F-3 안전장치는 그대로 유지, 약화 없음).

| 장치 | 담당 |
|------|------|
| `connectionMutex` | "접속 1회"의 직렬화. 재연결 시도도 이 락을 잡고 `openConnection`을 호출한다 → 수동 연결과 재연결이 절대 겹치지 않음 |
| `generation` (기존) | **연결 단위** 유효성. heartbeat 루프와 TCP 전송 실패가 같은 유실을 중복 보고하지 못하게 하는 CAS |
| `reconnectEpoch` (신규) | **재시도 묶음 단위** 유효성. 수동 `connect()`/`disconnect()`가 +1 해서 진행 중 루프를 무효화 |
| `reconnectJob.cancel()` | 백오프 `delay` 중인 루프를 즉시 깨움 |

- **취소는 뮤텍스 밖에서 먼저 한다.** 재연결 루프가 접속 시도 중 락을 쥐고 있을 수 있어서, 락을 잡은 뒤 취소하면 그 시도가 끝날 때까지 기다리게 되고(=이중 접속) 취소 의미가 사라진다.
- 취소만으로는 부족해서 epoch를 둔다: `cancel()` 직후 아직 취소를 확인하지 못한 루프가 상태를 한 번 더 덮어쓸 수 있다. 루프는 상태를 쓰기 전·락을 잡은 직후마다 epoch를 확인한다.
- `openConnection`은 `CancellationException`을 **따로 잡아 그대로 올린다**(기존 `catch (e: Exception)`에 삼켜지면 취소가 `Error`로 둔갑한다). 소켓 정리는 우리를 취소한 쪽이 이어서 한다(`disconnect()`의 `cleanUp()`, 또는 `TcpClient.connect()`의 선행 `disconnect()`).
- 재연결 루프는 `keepAliveScope`가 **아닌** 별도 `reconnectScope`에서 돈다. 유실을 보고한 heartbeat 루프가 곧바로 자기 스코프를 취소하므로, 같은 스코프에 두면 태어나자마자 취소된다.
- 첫 `Reconnecting`은 `reportConnectionLost` 안에서 **동기적으로** 쓴다(코루틴 스케줄을 기다리지 않음) → `Error` 깜빡임이 구조적으로 불가능.

### 2.4 TCP 이벤트 전송 실패의 합류
`Click`/`DoubleClick`/`DragStart`/`DragEnd`의 `try/catch` 4벌을 `sendOverTcp(json)` 하나로 모아 `reportConnectionLost(전송 시점 세대, e.message ?: "Send failed")`를 호출한다.
→ 소켓 정리 + 세대 무효화 + keep-alive 중단 + 재연결이 heartbeat 유실과 완전히 같은 경로로 일어난다(기존에는 상태만 `Error`가 되고 좀비 루프가 남았다).
`Move`/`Scroll`의 `runCatching` 조용한 실패는 **한 글자도 건드리지 않았다**(F-1/F-2 의도 보존, 회귀 테스트로 고정).

### 2.5 백오프 값
1→2→4→8→10→10→10→10초(합 55초), 상한 10초. 상한 근거는 서버가 옛 세션을 회수하는 시간(5초×3=15초)과 같은 자릿수라 회수 이후에도 여러 번 문을 두드린다는 것. 지수 계산은 shift 폭을 31로 제한해 Long 오버플로(음수 지연 → 재시도 폭주)를 막았다.

### 2.6 UI
`ConnectingPanel(message, hostLabel?, onCancel?)`로 확장해 `Connecting`/`Reconnecting`이 공유한다. **취소 버튼은 `onCancel`이 있는 재연결에서만** 나오고 첫 연결 화면은 기존 그대로다. `settingsAvailable`은 `Disconnected || Error` 그대로 — 재연결 중에는 설정에 들어갈 수 없다.
`Connected` 복귀 시 제스처 상태 리셋 코드는 **추가하지 않았다**(확인만): 트래커/`DragHoldDetector`/`DoubleTapDetector`는 `awaitEachGesture` 블록 안에서 제스처마다 새로 생성되고, `pendingClickJob`은 `pointerInput`의 `coroutineScope`와 함께 파기된다.

## 3. 테스트 결과 (실제 출력 기준)

```
.\gradlew.bat :app:testDebugUnitTest   → BUILD SUCCESSFUL
.\gradlew.bat :app:assembleDebug       → BUILD SUCCESSFUL
```
XML 리포트(`app/build/test-results/testDebugUnitTest/*.xml`) 집계: **`<testcase>` 217개, `<failure>` 0개.**
(재연결 도입 전 192개 → +25: `ReconnectPolicyTest` 9 + `TrackpadRepositoryReconnectTest` 16. 기존 192개는 전부 통과.)

주요 신규 케이스: 유실 → `Reconnecting(1,N)`(Error 미경유) / 백오프 경과 전 재접속 금지 / 재연결 성공 시 **새 세션 토큰이 UDP MOVE에 반영**(옛 토큰 아님) / 성공 후 재유실 시 카운터 1부터 / 연속 실패 시 attempt 1→N 후 `Error("Reconnect failed: refused")` 및 이후 무시도 / 첫 연결 실패·핸드셰이크 실패 무재시도 / 대기 중 `disconnect()` 즉시 중단 / 대기 중 수동 `connect()`가 재연결을 취소하고 1회만 접속 / `Click`·`DragEnd` 전송 실패의 재연결 합류 + 옛 루프의 2차 보고 없음(CAS) / `MOVE`·`SCROLL` 실패는 무트리거 / 비활성 정책은 기존 `Error` 그대로.

**작업 중 발견해 고친 테스트 함정(공유 가치 있음):** `runTest`는 본문이 끝난 뒤에도 가상 시간을 계속 진행시키므로, heartbeat 루프나 대기 중인 재연결 루프를 **살려둔 채 끝내면** 무한히(5초마다 전송 → 유실 → 재연결 → …) 돌면서 MockK가 호출을 기록하다 힙을 소진한다. 실제로 `OutOfMemoryError`가 나며 같은 JVM의 무관한 테스트 30개까지 동시에 무너졌다. 신규 테스트는 전부 `repository.disconnect()`(또는 Error 종료)로 끝내도록 고쳤고 그 이유를 클래스 KDoc에 남겼다.

## 4. 미해결 이슈 / 범위 밖

1. **재연결 직후 서버에 세션 2개** — 서버는 옛 연결의 EOF나 15초 heartbeat 타임아웃 전까지 옛 세션을 유지한다(다중 세션 허용 구조). AGENTS.md 섹션 10 "다중 기기 연결"의 드래그 상태 전역 문제와 같은 뿌리: **옛 연결이 회수되는 순간 서버의 `force_release_drag`가 새 연결의 드래그를 놓아버릴 수 있다.** 서버 변경은 이번 범위 아님 — 언급만.
2. 화면 꺼짐/도즈/앱 백그라운드 중 동작, WiFi→모바일 전환, `ConnectivityManager` 기반 즉시 재시도는 범위 밖(백오프 타이머만 사용). 도즈로 `delay`가 지연되면 실제 재시도 간격은 늘어날 수 있다.
3. 마지막 접속 IP 영속화는 범위 밖 — 재시도를 모두 소진하면 `Error` 화면으로 돌아가고 IP 입력값은 `_hostInput`에 남아 있지만 앱 재시작 시에는 사라진다.
4. 재연결 성공은 서버 입장에서 새 클라이언트다 — 앱이 드래그 중 끊겼다면 새 세션에서 `DRAG_START`는 다시 나가지 않는다(제스처가 이미 끝났으므로 의도된 동작). PC 버튼 해제는 서버의 연결 종료 안전장치에 의존한다.
5. `ReconnectPolicy`는 현재 사용자 조정 대상이 아니다(설정 화면 미노출). 필요해지면 `GestureSettings`처럼 DataStore로 올리는 경로가 열려 있다.
6. `domain/model/ReconnectPolicy.kt`가 `presentation.util.GestureConfig`를 import한다 — 기존 `GestureSettings`/`TrackpadUiState`와 동일한 계층 방향 역전. 정리하려면 `GestureConfig`를 domain으로 옮기는 별도 작업이 필요(이번에 새로 만들지 않고 기존 패턴을 따랐다).

## 5. 실기기에서만 확인 가능한 항목 (전부 미검증)

- **컴파일/유닛 테스트는 통과했지만 Compose 화면 동작은 실기기 전까지 미검증이다.**
- `Reconnecting` 패널 렌더링: 문구 `재연결 중… (n/N)`, 호스트 표시, "취소" 버튼 탭 → `Disconnected` 복귀.
- 유실 순간 `Error` 패널이 한 프레임도 보이지 않는지(코드상 불가능하도록 동기 전이했지만 체감 확인 필요).
- 실제 WiFi 끊김/AP 재접속/서버 프로세스 재시작에서 1→2→4→8→10초 백오프가 충분한지, 8회 55초가 적절한지.
- 재연결 성공 직후 트랙패드 표면의 제스처 반응(첫 탭·드래그가 즉시 먹히는지)과 커서 점프 유무.
- 재연결 성공 직후 **옛 세션이 서버에서 회수되는 순간** 드래그/클릭이 튀는지(위 미해결 1번).
- 도즈/화면 꺼짐 상태에서 재연결 타이머가 얼마나 늦춰지는지.
