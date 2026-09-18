# 04 · protocol-qa 경계면 정합성 리포트 — TCP Heartbeat (Phase 2)

**검증자:** protocol-qa
**대상:** android-dev / server-dev 병렬 구현 직후 작업 트리
**검증 방식:** 양쪽 동시 읽기 + **실측**(실제 TCP 루프백 소켓에 `server.handle_client`를 직접 태우고, Android `PrintWriter.println`이 내보내는 바이트와 Android 타이밍(첫 beat = 5초)으로 구동) + 양쪽 테스트 실제 실행
**코드 수정:** 하지 않음 (요청대로 기록만)

## 0. 집계

| 구분 | 개수 |
|------|------|
| ✅ 통과 | 17 |
| ❌ 실패 | 6 (코드 4 · 테스트품질 1 · 문서화된 주장 1) + 문서(AGENTS.md) 10 |
| ⚠️ 미검증 | 6 |

**심각(블로커) 실패: 없음.** 와이어 포맷·상수·채널·타이밍·카운터 리셋·세션 회수는 **실측으로 맞물림 확인**. 특히 이번 작업의 목표였던 지난 리포트 **F-1(서버가 세션을 회수해도 앱이 모름)은 해소**되었다 — 실측에서 서버가 15.02초에 소켓을 닫고 클라이언트가 즉시 EOF를 받는다.

실패 6건은 (a) 건강한 연결에서도 뜨는 서버 오탐 로그 1건, (b) Android 상태 덮어쓰기 경합 2건, (c) 비원자적 필드 1건, (d) 테스트가 실제로는 다른 것을 증명하는 1건, (e) 양쪽 summary가 "대칭"이라고 표현한 리셋 조건이 실제로는 반쪽 대칭인 점 1건이다. 정상 경로 동작을 막는 항목은 없다.

---

## 1. 실측 방법 (재현 가능)

작성한 하네스: `%TEMP%\claude\C--Github-phone-pad\<session>\scratchpad\e2e_heartbeat.py`, `e2e_robust.py`
(실제 `socket` 쌍 + 실제 `server.handle_client`. `InputController`만 기록용으로 교체 — 단, 비-dict 케이스는 **실제 `InputController`로 재측정**)

### S1 — 정상 클라이언트, Android 타이밍(첫 beat 5초, 이후 5초 주기)
```
[t= 0.00s] CLIENT RECV b'{"type": "SESSION", "session": "9c51...676b"}\n'
[t= 5.00s] CLIENT SEND b'{"type":"HEARTBEAT"}\n'   → RECV b'{"type":"HEARTBEAT_ACK"}\n'
[t=10.00s] SEND → RECV ACK
[t=15.00s] SEND → RECV ACK
[!] Heartbeat miss 1/3 from ('127.0.0.1', 56558)        ← 건강한 연결인데 발생 (F-1 참조)
[t=20.00s] SEND → RECV ACK
-> ACK count: 4 / handle_event calls: []  / 세션 유지
```
### S2 — 클라이언트 무응답
```
[!] Heartbeat miss 1/3 → 2/3 → 3/3 → [!] Heartbeat timeout: dropping
[=] Session revoked / [t=15.02s] CLIENT EOF (server closed)
```
### S3 — CLICK만 4초 주기로, HEARTBEAT 전혀 없음
```
17초 동안 miss 로그 0건, handle_event CLICK 4회, 세션 유지
```
### 라인 강건성 (실측 요약)

| 입력 라인 | HEARTBEAT 가로채기 | handle_event | 연결 생존 |
|---|---|---|---|
| `{"type":"HEARTBEAT"}\n` | O | 미호출 | 유지 |
| `{"type":"HEARTBEAT"}\r\n` (JVM `println`) | **O** (`line.strip()` 덕분) | 미호출 | 유지 |
| `{"type":"HEARTBEAT","session":"x","extra":1}` | O (여분 필드 무해) | 미호출 | 유지 |
| `{"type":"heartbeat"}` (소문자) | X (대소문자 구분) | 호출 → 무동작 | 유지 |
| `{"type":"HEARTBEAT_ACK"}` (클라가 역전송) | X | 호출 → 무동작 | 유지 |
| `{"type":"UNKNOWN_FUTURE"}` | X | 호출 → 무동작 | **유지** |
| `not json at all` | X | 미호출(`continue`) | 유지 |
| `[1,2,3]` / `"HEARTBEAT"` (비-dict) | X | 호출 → **AttributeError** | **끊김·세션 회수** (F-5) |

### 테스트 실제 실행
- `cd pc_server && python -m pytest -q` → **45 passed in 0.17s**
- `cd phone_pad_app && ./gradlew :app:testDebugUnitTest --offline` → BUILD SUCCESSFUL, test-results XML 클래스별 합계 **40 tests / failures 0 / errors 0**
  (ExampleUnitTest 1 · SessionHandshakeTest 8 · **TcpClientTest 4** · **TrackpadRepositoryHeartbeatTest 11** · TrackpadRepositoryImplTest 11 · SendEventUseCaseTest 1 · GestureConfigTest 4)
  → 양쪽 summary의 수치 주장(45 / 40)은 QA가 직접 재현 확인했다.

---

## 2. ✅ 통과 항목 (17)

| # | 항목 | 근거 (좌: Android / 우: Server) |
|---|------|--------------------------------|
| P-1 | **HEARTBEAT `type` 문자열 동일** | `TrackpadRepositoryImpl.kt:202` `HEARTBEAT_JSON = {"type":"HEARTBEAT"}` → `TcpClient.kt:73` `println` ↔ `server.py:144` `event.get("type") == "HEARTBEAT"`. 실측 왕복 성공 |
| P-2 | **ACK 바이트 정합** | `server.py:18-20`이 `separators=(",",":")`로 직렬화 → 실측 `b'{"type":"HEARTBEAT_ACK"}\n'` ↔ `TcpClient.readLine()`이 개행 제외 한 줄 반환(`TcpClientTest.kt:69-73`이 동일 리터럴로 고정). **SESSION 줄과 달리 콜론 뒤 공백이 없다** — 양쪽 리터럴이 정확히 일치 |
| P-3 | **Q1: Android는 ACK를 파싱하지 않는다 (확정)** | `TrackpadRepositoryImpl.kt:164-169` — `readLine()` 결과를 **null 여부만 확인**하고 내용은 전혀 검사하지 않는다. 즉 스펙이 허용한 두 방식 중 **"아무 줄" 취급** 쪽이 구현되었다. `SessionHandshake.parseSession`은 핸드셰이크에서만 쓰인다. 부수 효과로 견고하다: ACK가 타임아웃 경계에서 쪼개져 `BufferedReader.readLine()`이 앞부분을 잃어도(자바 표준 동작) 생존 신호 판정은 깨지지 않는다 |
| P-4 | **Q4: HEARTBEAT가 마우스 명령으로 흘러들지 않는다** | `server.py:144-147`이 `continue`로 `server.py:148` `controller.handle_event(event)` **앞에서** 가로챈다. 실측 S1: 4회 beat 동안 `handle_event calls: []` |
| P-5 | **채널 원칙 (MOVE만 UDP)** | heartbeat는 `tcpClient.send`만 사용(`Impl.kt:143`). `udpClient.send` 0회를 Android 테스트가 고정. 서버는 UDP에서 MOVE 외 전부 거부(`server.py:72`) → HEARTBEAT가 UDP로 새도 무시됨 |
| P-6 | **상수 대칭** | `GestureConfig.kt:30,33` `5000L`/`3` ↔ `server.py:16-17` `5.0`/`3`. 양쪽 모두 회귀 테스트로 고정(`GestureConfigTest.kt:26-37`, `test_heartbeat_constants_match_spec`). `HEARTBEAT_INTERVAL_MS.toInt()` = soTimeout 5000ms로 서버 `settimeout(5.0)`과 동일 창 |
| P-7 | **Q2: 타이밍 — 15초 근방에서 일치, 오탐 해제 없음** | 실측: 무응답 시 서버가 **15.02초**에 종료(스펙 ≈15초). 정상 트래픽 20초 구동에서 해제 없음. 양쪽 모두 3회째에 즉시 판정하므로 한쪽이 4번째 창을 기다리는 어긋남 없음 |
| P-8 | **Q3: 서버 카운터는 HEARTBEAT 전용이 아니다** | `server.py:132` `missed = 0`이 **파싱 전에** 실행 → 실측 S3: CLICK만 4초 주기로 17초간 보냈을 때 miss 로그 0건·연결 유지. 개행 없는 부분 수신도 리셋(server-dev 테스트 + 코드 위치로 확인) |
| P-9 | **Q2: 죽은 연결에 쓰기 시도해도 이상 예외 없음** | 서버 선행 종료 → Android `readLine()`이 `null`(정상 FIN) 또는 `SocketException`(RST) → 둘 다 즉시 `Error("Connection lost")`(`Impl.kt:166,178-181`). 그 사이 sender가 쓰면 `PrintWriter`가 에러를 삼키거나 `checkNotNull(writer)`가 ISE → `Impl.kt:145-150`이 잡고, `reportConnectionLost`의 세대 CAS 실패로 **무동작 return**. Android 선행 종료 → 소켓 close → 서버 `recv`가 `b""` → `break` → `finally` 세션 회수. 양방향 모두 크래시·좀비 없음 |
| P-10 | **Q5: F-2(재연결 시 구 세션 무효화)와 계속 호환** | `Impl.kt:65-67` — `invalidateCurrentConnection()` → `sessionToken = null` → `udpClient.close()`가 **`tcpClient.connect()`(69행) 전에** 그대로 남아있다. F-2 수정이 훼손되지 않았고, 세대 증가가 같은 지점에서 일어나 무효화 시점이 일치 |
| P-11 | **Q5: 재연결 시 heartbeat 루프가 확실히 취소된다** | `invalidateCurrentConnection()` → `stopKeepAlive()` → `scope.cancel()`(`Impl.kt:118-128`). `connect()` 진입 시(핸드셰이크 **전**)와 `disconnect()` 시 모두 호출. 블로킹 읽기에 걸려 즉시 죽지 않는 옛 루프는 `generation.compareAndSet`(`Impl.kt:188`)으로 상태 변경이 차단된다. android-dev 테스트 8이 취소 횟수(0→1→2)를 직접 검증하며, 돌연변이 검증(해당 호출 제거 시 테스트가 좀비 코루틴으로 멈춤) 기록도 신뢰할 만하다 |
| P-12 | **Q6: TCP 해제 후 UDP MOVE 처리 — server-dev 주장대로다** | 실측: `registry.remove(token)` 전 `handle_udp_packet` → `True` + `_move` 인자 도착 / 후 → **`False`, 예외 없음, SendInput 미호출**(`server.py:69`). Android 쪽도 `cleanUp()`이 `sessionToken = null`을 **`udpClient.close()`보다 먼저** 수행(`Impl.kt:196-198`)해 전송이 멈춘다. 게다가 서버가 소켓을 닫으므로 watchdog이 15초를 기다리지 않고 **즉시** EOF로 알아챈다 |
| P-13 | **와이어 관용성** | 여분 필드 포함 HEARTBEAT·청크 분할·`\r\n` 종료 모두 정상 가로채기(실측). `PrintWriter.println`이 플랫폼 개행을 쓰므로 호스트 JVM 테스트에서는 `\r\n`이 나가는데, `server.py:136` `line.strip()`이 흡수한다 |
| P-14 | **알 수 없는 type 무시, 연결 유지** | 실측 `{"type":"UNKNOWN_FUTURE"}` → 세션 유지 + 이후 CLICK 정상 처리. 깨진 JSON도 `server.py:141-143`이 `continue` (heartbeat 작업에서 분리된 구조가 오히려 개선) |
| P-15 | **양쪽 테스트 존재 + 실제 green** | 서버 45 / Android 40, QA가 직접 실행. 신규 이벤트에 대해 양쪽 모두 테스트 보유(체크리스트 충족). `TcpClientTest.kt:35-37`이 서버 실제 출력 형식(`json.dumps` 기본형, 콜론 뒤 공백)을 골든 리터럴로 쓰기 시작해 **지난 리포트 5절 지적이 SESSION에 대해 해소** |
| P-16 | **Q7: HEARTBEAT를 `TrackpadEvent`에 넣지 않은 것은 문제 아님 — android-dev에 동의** | 경계면 계약은 "와이어에 나가는 바이트"이며 `{"type":"HEARTBEAT"}`는 `Impl.kt:202` 상수 하나에서만 생성되고 테스트가 리터럴을 고정한다. sealed class는 **presentation이 발사할 수 있는 제스처 어휘**이고 heartbeat는 전송 계층 관심사이므로, 추가하면 `SendEventUseCase` 경유로 UI가 임의 heartbeat를 쏠 수 있게 되어 오히려 계약이 나빠진다. 단 `AGENTS.md:240` 컨벤션이 "새 이벤트 타입 = sealed class 확장"을 무조건으로 적고 있어 **다음 에이전트가 "누락"으로 오인해 되돌릴 위험**이 있다 → 문서에 예외 명문화 필요(D-9) |
| P-17 | **핸드셰이크 실패 경로 정합** | 서버: `conn.settimeout`이 `try` 내부 `sendall` 뒤(`server.py:107-110`)라 SESSION 전송 실패 시 타임아웃 미설정 + 세션 회수. Android: `Impl.kt:70-74`가 `startKeepAlive` **전에** return → 루프 미시작. 양쪽 모두 "연결도 못 한 상태에서 heartbeat 기계가 돌지 않는다" |

---

## 3. ❌ 실패 항목 (6)

### F-1 (하 · server-dev) 건강한 연결에서도 `Heartbeat miss 1/3` 로그가 주기적으로 뜬다

- **파일:라인** — `pc_server/server.py:110` `conn.settimeout(HEARTBEAT_INTERVAL_S)` ↔ Android 송신 주기 `GestureConfig.kt:30` `HEARTBEAT_INTERVAL_MS = 5000L`
- **기대값 vs 실제값** — 기대: 정상 왕복 중에는 miss 로그가 0건. **실제(실측 S1): 정상 연결 20초 구동에서 `[!] Heartbeat miss 1/3` 1회 발생** (t=20.00s 직전). 원인은 수신 창과 송신 주기가 **정확히 같은 5.000초**여서 생기는 knife-edge 경합이다. 서버의 recv 창은 직전 수신 시각 + 5s에 만료되고 다음 beat는 5s + 지터에 도착하므로, **지터가 직전보다 커지는 구간마다 miss가 1 올라간다**(정상 상태에서 대략 절반의 주기). miss 직후 이미 소켓 버퍼에 beat가 들어와 있어 다음 `recv()`가 즉시 성공 → `missed`는 **1을 넘지 못하고 0으로 리셋**되므로 오탐 해제는 일어나지 않는다(실측 확인). 즉 기능 결함은 아니고 **진단 로그 오염**이다 — 실사용에서 5~10초마다 경고성 로그가 뜨면 진짜 이상을 구분할 수 없다. Android 쪽도 `soTimeout == 송신 주기`라 동일한 경합으로 `missedBeats`가 0↔1을 오가지만(로그가 없어 보이지 않음) 동일하게 3에 도달하지 못한다
- **수정 제안** — 택 1. ① 로그 레벨만 낮추고(예: `missed >= 2`일 때만 출력) 상수는 유지 — 스펙 무영향, 최소 변경. ② 수신 창에 여유를 준다: `conn.settimeout(HEARTBEAT_INTERVAL_S + 1.0)` (해제 시각 15→18초) 또는 `* 1.5`(→22.5초). **상수·타이밍은 리더가 사전 확정한 스펙이므로 ②는 리더 승인 필요**(AGENTS.md "≈15초" 문구도 함께 갱신). QA 권고는 ①(스펙 불변) + Android도 동일한 판단 근거를 주석에 남기기
- **비고** — 실측으로만 드러나는 항목이다. 양쪽 유닛 테스트는 타임아웃을 큐에 넣어 모사하므로 이 경합을 재현할 수 없다

### F-2 (하 · android-dev) MOVE 전송 실패가 연결 상태를 덮어쓴다 — 코드가 자기 주석과 모순

- **파일:라인** — `TrackpadRepositoryImpl.kt:89-90` 주석("MOVE는 고빈도 이벤트여서 연결 상태를 Error로 덮어쓰지 않는다") vs `TrackpadRepositoryImpl.kt:101-103` 공용 `catch`
- **기대값 vs 실제값** — 기대: 주석대로 Move 분기의 실패는 상태를 바꾸지 않는다. 실제: `when` 전체를 감싼 `catch (e: Exception)`이 **Move에도 적용**되어 `ConnectionState.Error(e.message)`를 쓴다. 91행 `sessionToken ?: return`이 대부분을 막아주지만 경합 구간이 남는다 — `sessionToken`을 non-null로 읽은 직후 watchdog의 `cleanUp()`이 완주하면(`Impl.kt:196-198`) `udpClient.send` → `UdpClient.kt:35` `checkNotNull(socket) { "UDP not ready" }` → `IllegalStateException` → **`Error("Heartbeat timeout")`이 `Error("UDP not ready")`로, `disconnect()` 직후라면 `Disconnected`가 `Error("UDP not ready")`로 덮인다**. 사용자에게는 원인 메시지가 사라지고, "사용자가 직접 끊었는데 오류 화면"이 될 수 있다
- **수정 제안** — Move 분기에 자체 `try/catch`를 두어 예외를 삼키거나(주석 의도대로), `catch` 안에서 `if (event is TrackpadEvent.Move) return`으로 분기. 더 일관되게는 `reportConnectionLost`처럼 세대 CAS로 보호. 회귀 테스트: "cleanUp 이후 도착한 MOVE는 `Error("Heartbeat timeout")`을 덮어쓰지 않는다"
- **비고** — heartbeat가 `Error`를 세팅하기 시작해서 **비로소 관측 가능해진** 기존 코드의 결함이다(전에는 덮어쓸 상태 자체가 없었다)

### F-3 (중하 · android-dev) `connect()`의 성공/실패 경로는 세대 보호를 받지 않는다 — 최악의 경우 F-1 맹점이 재현

- **파일:라인** — `TrackpadRepositoryImpl.kt:70-82`(핸드셰이크 결과 처리 + `catch`) 및 `:130-135`(`startKeepAlive`) vs `:186-193`(루프는 CAS 보호됨)
- **기대값 vs 실제값** — 기대: 루프에 적용한 세대 무효화가 `connect()` 자신에게도 적용되어, 겹친 두 `connect()` 중 **나중 것만** 상태와 스코프를 소유한다. 실제: `connect()`는 진입 시 세대를 캡처하지만(`:65`) **`Connected` 세팅(:77)·`startKeepAlive`(:78)·`catch`의 `cleanUp()+Error`(:80-81) 어디에서도 세대를 재확인하지 않는다.** `TrackpadViewModel.kt:40-45`가 탭마다 `viewModelScope.launch`로 새 `connect()`를 띄우고, 화면 전환(`TrackpadScreen.kt:56 ConnectingPanel`)은 한 프레임 뒤에 일어나므로 **빠른 이중 탭으로 두 `connect()`가 겹칠 수 있다.** 그 경우
  (a) 늦게 끝난 stale 호출의 `catch`가 `cleanUp()`을 돌려 **살아있는 새 연결의 소켓을 닫고** `Error`로 덮거나,
  (b) stale 호출이 마지막에 `Connected` + `startKeepAlive(staleGen)`을 세팅 → 두 루프가 `forGeneration != generation.get()`으로 **즉시 종료** → **`Connected` 표시인데 sender도 watchdog도 없는 상태**. 이는 이번 작업이 없애려던 F-1(조용히 멈춘 커서) 그 자체이고, 서버는 beat가 없으니 15초 후 세션을 회수한다. 추가로 `:132` `keepAliveScope = scope`가 새 연결의 스코프 참조를 덮어써 취소 대상을 잃는다
- **수정 제안** — `connect()` 꼬리와 `catch`를 세대로 가드: `if (currentGeneration != generation.get()) { runCatching { tcpClient.disconnect() }; return }`을 `Connected` 세팅 전과 `catch` 진입부에 추가. 또는 `connect()`/`disconnect()` 전체를 `Mutex`로 직렬화(가장 단순·확실). 회귀 테스트: "connect()가 겹치면 마지막 호출만 Connected를 소유하고, 살아있는 세대의 루프가 정확히 1쌍 돈다"
- **비고** — 재현에 프레임 단위 이중 탭이 필요해 확률은 낮으나, **결과가 이번 기능의 목적을 무력화**하므로 중하로 분류한다. UI가 대부분 막아주는 것은 방어 계층이지 계약이 아니다

### F-4 (하 · android-dev) `keepAliveScope`가 비원자적 필드인데 스레드 3종에서 접근된다

- **파일:라인** — `TrackpadRepositoryImpl.kt:51` `private var keepAliveScope: CoroutineScope? = null` (`sessionToken`은 `:47`에서 `@Volatile`인데 이쪽은 아님)
- **기대값 vs 실제값** — 기대: 취소 지시가 유실되지 않는다. 실제: 이 필드는 `connect()`/`disconnect()`를 호출한 코루틴의 스레드, 그리고 `reportConnectionLost` → `stopKeepAlive`를 실행하는 **`@IoDispatcher` 워커 스레드**에서 동시에 읽고 쓰인다. `generation`은 `AtomicInteger`로 보호되지만 스코프 참조는 아니어서, stale 읽기로 **`cancel()`이 유실**(루프가 자기 세대 확인 시점까지 생존 — 블로킹 읽기 중이면 최대 5초)되거나 **새 스코프가 null로 덮일** 수 있다
- **수정 제안** — `@Volatile`을 붙이거나 `AtomicReference<CoroutineScope?>`로 바꾸고 `getAndSet(null)`로 취소. 세대 CAS가 잘못된 상태 변경은 이미 막고 있어 실해는 "좀비 루프가 조금 더 오래 살아있음" 수준이지만, 동일 클래스 안에서 `sessionToken`만 `@Volatile`인 것은 일관성 결함이다

### F-5 (하 · server-dev, 테스트 품질) `test_non_dict_json_line_does_not_crash`가 실제로는 "크래시하지 않음"을 증명하지 않는다

- **파일:라인** — `pc_server/tests/test_server_heartbeat.py:173-182` (`with patch.object(controller, "handle_event")`) vs `pc_server/server.py:148` `controller.handle_event(event)` + `input_controller.py:35` `event.get("type")`
- **기대값 vs 실제값** — 기대(테스트 이름): 비-dict JSON 줄이 와도 연결이 유지된다. 실제: `handle_event`가 Mock으로 교체되어 리스트를 받아도 조용히 통과하므로, 테스트가 증명하는 것은 **"HEARTBEAT로 오인하지 않는다"**뿐이다. 실제 `InputController`로 QA가 재측정한 결과 `[1,2,3]\n`·`"HEARTBEAT"\n` 모두 `AttributeError` → `server.py:149` `except Exception` → `finally` → **세션 회수 + 소켓 종료**(측정: `session alive after line = False`)
- **수정 제안** — ① 테스트 이름을 실제 검증 내용으로 정정(`test_non_dict_json_is_not_mistaken_for_heartbeat`), 그리고 ② `server.py:148` 앞에 `if not isinstance(event, dict): print(...); continue`를 추가한 뒤 "비-dict 줄 이후에도 CLICK이 처리된다"를 실제 `InputController`로 검증. 현재 Android는 그런 줄을 만들지 않으므로(dict 리터럴만 생성) **경계면 실해는 없고, heartbeat 작업의 회귀도 아니다**(변경 전에도 `AttributeError`는 외곽 핸들러로 떨어졌다) — 그래서 하 등급
- **비고** — UDP 경로는 `server.py:66-67`이 `isinstance(event, dict)`로 막고 있어 TCP 경로만 비어 있다. 두 경로의 방어 수준이 다른 점도 함께 정리하면 좋다

### F-6 (하 · 양쪽 summary의 서술) "카운트 리셋 조건이 양쪽 대칭"은 절반만 맞다

- **파일:라인** — `pc_server/server.py:132` `missed = 0` (수신 바이트 무엇이든) vs `TrackpadRepositoryImpl.kt:169` `missedBeats = 0` (수신 **줄** 무엇이든)
- **기대값 vs 실제값** — 양쪽 summary와 확정 스펙은 "아무 데이터나 받으면 리셋"이라는 대칭을 주장한다. 규칙 자체는 대칭이지만 **트래픽이 비대칭**이다: 서버는 CLICK 등 모든 상향 데이터로 리셋되지만(실측 S3), **서버가 클라이언트로 보내는 것은 ACK뿐**이므로(`server.py`에 다른 하향 전송 없음) Android 쪽에서는 사실상 **`HEARTBEAT_ACK`이 유일한 리셋 수단**이다. 결과적 방향성 차이: 서버가 ACK만 멈추고 수신은 계속하면 **Android는 15초에 끊고 서버는 건강하다고 판단**하는 구간이 생긴다(반대 방향은 Android가 beat를 멈추면 양쪽이 ≈15초에 함께 끊겨 대칭)
- **수정 제안** — 코드 변경 불필요(Android의 리셋 소스가 자기 sender에 종속되는 것은 설계상 수용 가능). 다만 `AGENTS.md`와 요약 문서에는 "양쪽 대칭" 대신 **"서버: 모든 상향 데이터로 리셋 / 클라이언트: 하향 트래픽은 ACK뿐이므로 실질적으로 ACK가 유일한 리셋 소스"**로 정확히 적을 것. Phase 4 재연결 설계 시 이 비대칭이 판단 근거가 된다
- **비고** — 질문 3(리셋 대칭 확인)에 대한 정확한 답이다. "heartbeat가 유일한 리셋 수단이 아님"은 **서버 쪽에서만** 성립한다

---

## 4. ❌ 문서(AGENTS.md) 불일치 (10) — 리더 갱신용

| # | 위치 | 문서 내용 | 실제 구현 | 제안 |
|---|------|-----------|-----------|------|
| D-1 (중) | `AGENTS.md:103-104` §4 | `// heartbeat (Phase 2, 미구현 — TCP 수신 루프 자체가 아직 없음)` | 양쪽 구현 완료 | "미구현" 주석 삭제. **`{"type":"HEARTBEAT_ACK"}` 서버→클라이언트 줄을 방향 표시와 함께 추가**하고 "`InputController.handle_event`로 넘기지 않는다(마우스 명령 아님)"를 명기 |
| D-2 (중) | `AGENTS.md:77-84` §4 TCP 절 | 세션 핸드셰이크만 서술 | 핸드셰이크 직후 `settimeout(5.0)`, 연속 3회 미수신 시 서버가 먼저 소켓을 닫는다 | "서버는 핸드셰이크 직후 recv 타임아웃 5초를 걸고, **상향 데이터가 3연속 창에서 없으면(≈15초) 세션을 회수하고 연결을 끊는다. HEARTBEAT뿐 아니라 CLICK 등 어떤 상향 데이터든 카운터를 리셋한다**"를 추가. 클라이언트 단독 트리거(서버는 HEARTBEAT를 먼저 보내지 않음)도 명시 |
| D-3 (중) | `AGENTS.md:133-142` §5 감도 상수 표 | `SESSION_HANDSHAKE_TIMEOUT_MS`까지 | `HEARTBEAT_INTERVAL_MS = 5000L`, `HEARTBEAT_MISS_LIMIT = 3` 존재 | 두 상수 추가 + "서버 `HEARTBEAT_INTERVAL_S = 5.0` / `HEARTBEAT_MISS_LIMIT = 3`과 **반드시 동시 갱신**" 주석 |
| D-4 (중) | `AGENTS.md:159` §6 Phase 2 | `[ ] TCP heartbeat ... ` + **알려진 공백**(Android가 TCP를 읽지 않아 조용히 멈춘다) 장문 | 구현 완료, 공백 해소 | `[x]`로 변경하고 **"알려진 공백" 문장 전체 삭제**(지난 F-1 해소). 판정 방식을 한 줄로: "카운터 기반, 양쪽 5초 창 × 3회" |
| D-5 (하) | `AGENTS.md:165` §6 핵심 파일 | `TcpClient.kt — 세션 핸드셰이크 완료. heartbeat 수신 루프는 아직 없음(추가 필요)` | `applyHeartbeatTimeout()` / `readLine()` / `soTimeoutMillis` 추가 완료 | "완료"로 정정. 루프 자체는 `TrackpadRepositoryImpl`이 소유(sender/watchdog 2개)하고 `TcpClient`는 한 줄 읽기만 제공한다는 책임 분리를 적으면 다음 에이전트가 헤매지 않는다 |
| D-6 (중) | `AGENTS.md:206, 209-210` §7 다이어그램 | `TCP: SCROLL/HEARTBEAT ---> (Phase 2 예정, 미구현)`, `TCP: HEARTBEAT (5s) ---> (미구현)`, `<-- HEARTBEAT_ACK (미구현)` | HEARTBEAT/ACK 구현, SCROLL 미구현 | **206행에서 HEARTBEAT를 떼어내 SCROLL만 남기고**, 209-210행의 "(Phase 2 예정, 미구현)"을 `✅ 구현됨`으로. 209행에 "첫 beat는 연결 후 5초", 210행에 "ACK는 handle_event 미경유" 주석 |
| D-7 (하) | `AGENTS.md:33` §2 트리 | `di/ AppModule.kt` | `DispatcherModule.kt` 신규 | `di/  AppModule.kt, DispatcherModule.kt`로 수정 |
| D-8 (하) | `AGENTS.md:222-223` §8 | 방화벽은 UDP 9001만 언급 | TCP가 15초 무응답으로 끊기면 UDP MOVE도 조용히 무시된다 | 트러블슈팅 한 줄 추가: "커서가 갑자기 멈추고 앱이 `Heartbeat timeout`을 띄우면 TCP 9000 경로(Wi-Fi 절전/도즈 포함)를 먼저 의심. **TCP 세션이 회수되면 UDP MOVE는 전량 드롭된다**" |
| D-9 (중) | `AGENTS.md:240-243` §9 컨벤션 | "새 이벤트 타입 추가 시: `TrackpadEvent.kt` sealed class 확장 → …" (무조건) | HEARTBEAT는 의도적으로 sealed class 밖(전송 계층 전용) | **예외 절 추가**: "연결 유지/전송 계층 전용 메시지(HEARTBEAT 등)는 sealed class에 넣지 않고 data 계층 내부 상수로만 만든다 — presentation이 임의 발사하지 못하게 하려는 의도이며, 대신 **와이어 리터럴을 고정하는 테스트를 반드시 둔다**". 이 문장이 없으면 다음 에이전트가 P-16을 '누락'으로 보고 되돌릴 위험이 있다 |
| D-10 (하) | `AGENTS.md:183` §4 Phase 4 / `:264` §10 | 재연결 로직 Phase 4 | heartbeat가 `Error`까지만 만들고 복구는 수동 | Phase 4 재연결 항목에 "heartbeat 판정(`Error("Heartbeat timeout")`/`Error("Connection lost")`)을 트리거로 쓴다"를 명시. §10에 F-6의 리셋 비대칭을 설계 메모로 남길 것 |

---

## 5. ⚠️ 미검증 항목 (6)

| # | 항목 | 이유 / 필요한 후속 |
|---|------|--------------------|
| U-1 | **실기기 ↔ 실서버 E2E** | QA 실측은 Android가 만드는 **바이트와 타이밍을 Python 클라이언트로 정확히 재현**해 실제 `handle_client`에 태운 것이다. APK를 실제 폰에 올려 15초 판정·ACK 왕복·`Error` 화면 전환을 본 것은 아니다. android-dev summary 6번과 동일 인식 |
| U-2 | **Android 절전/도즈에서의 5초 sender** | 화면 꺼짐·도즈·Wi-Fi 절전에서 `delay(5000)`과 소켓이 지연되면 **서버가 15초에 먼저 끊는 오탐**이 가능하다. 실측 불가(실기기 필요). Phase 4 재연결과 함께 다뤄야 하며, 필요 시 wake lock 또는 서버 여유 시간(F-1 ②안) 검토 |
| U-3 | **실제 네트워크 손실 형태별 거동** | 루프백은 항상 깔끔한 FIN/RST를 준다. 실제 Wi-Fi 이탈은 **블랙홀(응답 없음)**이 흔해 EOF 없이 15초 타임아웃 경로를 타게 되는데, 그 경로의 실측은 못 했다 |
| U-4 | **다중 클라이언트 heartbeat** | `missed`는 스레드 로컬 변수라 세션별로 독립적이지만(코드상 확인), 동시 2대 이상에서 서로 영향이 없는지는 미측정. `SessionRegistry`는 Lock 보호(지난 P-14) |
| U-5 | **Phase 2 잔여 이벤트** | SCROLL / DOUBLE_CLICK / CLICK(right) / DRAG는 양쪽 모두 여전히 미구현 — 검증 대상 없음(실패 아님) |
| U-6 | **F-3 경합의 실제 도달 가능성** | Compose 입력 파이프라인에서 프레임 내 이중 탭이 `connect()` 두 번을 실제로 겹치게 하는지는 코드 추론까지만 했고 계측하지 않았다. 다만 수정 비용이 낮아(가드 2줄 또는 Mutex) 도달성 확인보다 방어가 경제적이다 |

---

## 6. 담당자별 액션 요약

| 담당 | 항목 |
|------|------|
| **android-dev** | **F-3**(connect() 세대 가드 또는 Mutex — 유일하게 기능 목적을 무력화할 수 있는 항목, 우선 처리 권장) · F-2(MOVE 실패가 상태 덮어쓰기) · F-4(`keepAliveScope` `@Volatile`) |
| **server-dev** | F-1(정상 연결의 miss 로그 — ①로그 조건 완화 권고, ②타임아웃 여유는 리더 승인 필요) · F-5(테스트 이름 정정 + `isinstance(event, dict)` 가드) |
| **리더(문서)** | D-1 ~ D-10. 특히 **D-9(sealed class 예외 명문화)**와 **D-4(F-1 공백 문구 삭제)**는 다음 작업의 오판을 막는 항목 |
| **Phase 4 백로그** | U-2(도즈에서의 오탐) · D-10(heartbeat를 재연결 트리거로) · F-6의 리셋 비대칭을 설계 전제로 기록 |

**결론: 확정 스펙대로 양쪽이 맞물린다. 블로커 없음.** 와이어 포맷(HEARTBEAT / HEARTBEAT_ACK 바이트), 상수(5000ms·3 ↔ 5.0·3), 채널(TCP 전용), 15초 해제 타이밍, 서버 측 "아무 데이터나 리셋", HEARTBEAT의 `handle_event` 미경유, TCP 회수 후 UDP MOVE 무시 — **전부 실측으로 확인**했다. 지난 리포트 F-1은 해소되었고 F-2 수정도 훼손되지 않았다. 남은 지적은 오탐 로그 1건, Android 상태 경합 3건, 테스트 이름 1건, 서술 정정 1건, 문서 10건이며 정상 경로를 막는 것은 없다.
