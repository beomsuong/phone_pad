# protocol-qa 리포트 — 단일 클라이언트 정책 (`SESSION_REPLACED`)

검증일: 2026-09-24 · 대상: 미커밋 작업 트리(`git status` 기준) · 커밋/코드 수정 없음

## 판정 요약

**차단 없음 (BLOCKER 0, FAIL 0).** 와이어 리터럴·채널·상태 전이가 양쪽에서 일치하고, 실소켓 + 실제 JVM
`BufferedReader` 클라이언트로 밀어내기 전 경로를 재현해 통과를 확인했다. 주의(W) 5건은 전부 발생 조건이
좁거나 이미 양쪽 요약에 기록된 항목이며, 그중 **W-1(3기기 핸드셰이크 레이스)만 이번에 새로 발견**해
실제로 재현했다.

| # | 검증 항목 | 판정 |
|---|-----------|------|
| 1 | 와이어 리터럴 정확 일치 + 실소켓 clean FIN 재현 | **PASS** (W-2 단서) |
| 2 | AUTH 실패가 기존 연결에 영향 없음 | **PASS** |
| 3 | 밀려난 연결의 서버 측 정리(세션 회수 + 드래그 강제 해제) | **PASS** (W-3/W-4 단서) |
| 4 | Android 자동 재연결 억제 + 다른 줄 회귀 | **PASS** |
| 5 | 알림 유실 시 핑퐁 재발 위험 평가 | **PASS** — 잔여 위험 정량화 → W-2 |
| 6 | 섹션 10 두 항목 최종 판정 | 아래 "섹션 10 최종 문구 제안" |
| 7 | 기존 프로토콜 회귀 없음 | **PASS** |

---

## 검증 방법 (양쪽 동시 대조 + 실측)

코드 대조는 `pc_server/single_client.py` ↔ `data/network/SessionReplacedNotice.kt`,
`pc_server/server.py:222-326` ↔ `TrackpadRepositoryImpl.kt:494-578` 를 나란히 읽어 수행했다.

실측은 문서·양쪽 요약을 믿지 않고 직접 재현했다. 하네스(스크래치패드, 저장소 밖):

- `qa_server.py` — 실제 `ServerRuntime`(가드 주입, PIN `483920`, discovery 끔)을 **`InputController` 대역**으로
  구동. (AGENTS.md 섹션 9 함정: 실소켓 테스트에 진짜 `InputController`를 쓰면 개발 PC 마우스가 실제로 눌린다.)
- `QaClient.java` — Android `TcpClient`와 **같은 구성**의 JVM 클라이언트
  (`Socket` → `PrintWriter.println(AUTH)` → `BufferedReader(InputStreamReader(UTF_8)).readLine()`,
  `soTimeout=3000`→`5000`). `isSessionReplaced`도 `SessionReplacedNotice`와 동일한 정규식으로 재구현해
  **실제 JVM 문자열이 앱 판별기를 통과하는지**까지 확인했다.

`pc_server/tests/test_single_client.py` 단독 재실행: `38 passed in 1.16s`.
(전체 스위트는 리더가 이미 재실행 확인 — server 585/1 skipped, Android 441/0 failures.)

---

## 1. 와이어 리터럴 정확 일치 + clean FIN 실측 — PASS

**리터럴 대조**

| | 값 | 근거 |
|---|---|---|
| 서버 송신 | `{"type":"SESSION_REPLACED"}\n` | `pc_server/single_client.py:35-37` — `json.dumps(..., separators=(",", ":"))` (공백 없음) |
| Android 판별 | `type == "SESSION_REPLACED"` 정확 일치 | `SessionReplacedNotice.kt:24,26,38-41` |
| 채널 | TCP 9000, 필드 없음, `session` 없음 | `server.py:242` (`conn` = TCP 소켓), UDP 경로 무변경 |

`TrackpadEvent.kt` / `input_controller.py` **무수정**(`git diff --stat` 공란) — 이벤트 타입/필드 계약은
전혀 건드리지 않았고, 서버→클라이언트 연결 유지 계층 메시지 1종만 추가됐다. 스펙대로다.

**실측 (실제 JVM 클라이언트 2대, 첫 번째가 DRAG_START 상태)**

```
[client1] handshake <- {"type": "SESSION", "session": "2fc17126..."}
[client2] handshake <- {"type": "SESSION", "session": "950ecf9b..."}
[client1] readLine -> {"type":"SESSION_REPLACED"}      <- 앱 판별기 통과 (notice_recognized=true)
[client1] readLine -> null (clean EOF)                 <- RST 아님 (clean_eof=true)

서버 로그:
[!] Evicted previous client: ('127.0.0.1', 54093) (replaced by ('127.0.0.1', 54094))
[!] Error from ('127.0.0.1', 54093): [WinError 10038] ...
[=] Session revoked: 2fc17126...
[QA] force_release_drag -> button released
```

**server-dev의 스펙 이탈(`_drain` 추가)은 유지를 권고한다.** 드레인이 없으면 어떻게 깨지는지를 반대편에서
독립 확인했다 — 드레인 예산(0.1초/1MiB)을 일부러 초과시키면 정확히 그 증상이 재현된다(W-2).
스펙(`shutdown`→`close`)의 순서 자체는 그대로 유지됐고 그 앞에 드레인만 끼워 넣은 것이므로
**와이어 스펙 변경이 아니다.**

---

## 2. AUTH 실패가 기존 연결에 영향 없음 — PASS

**코드 근거**: `server.py:226-233`. `authenticate_client()`가 False를 돌려주면 `registry.issue()`·
`guard.take_over()`에 **도달하기 전에** `return` 한다. 즉 PIN 오류/형식 오류/타임아웃/EOF/브루트포스
잠금 네 갈래 전부 슬롯을 만질 코드 경로 자체가 없다. 슬롯 교체는 `server.py:236-242` 한 곳뿐이다.

**실측** (활성 연결 1개 유지 상태에서 잘못된 PIN 3회 + 형식 오류 1회):

```
[bad0..2]   <- {"type":"AUTH_FAIL","reason":"invalid_pin"}
[malformed] <- null (조용히 닫힘)
[client1] readLine -> {"type":"HEARTBEAT_ACK"}
RESULT active_survived=true got_notice=false
```

브루트포스 잠금 경로는 실측하지 않았으나(5회 임계까지 채우면 이후 테스트 IP가 잠긴다) 위 코드 경로가
동일하고 `test_locked_out_attempt_does_not_evict_the_active_client`가 커버한다.

---

## 3. 밀려난 연결의 서버 측 정리 — PASS

- **세션 토큰 회수**: `server.py:310` `registry.remove(session)`. 위 실측 로그의 `[=] Session revoked` 로 확인.
  밀려난 스레드는 다른 스레드의 `close()` 때문에 `recv()`가 `[WinError 10038]`(WSAENOTSOCK)로 풀려
  기존 `except Exception`(`server.py:307`) → `finally`(309) 경로를 그대로 탄다. **요청서가 요구한
  "새 예외 처리 불필요"가 실측으로 확인됐다.**
- **드래그 강제 해제**: 실측에서 `client1`이 `DRAG_START` 상태로 밀려났고 서버가
  `force_release_drag -> button released` 를 실행했다(`server.py:319-321`).
- **슬롯 identity 보호**: `single_client.py:73-77` `is` 비교. 밀려난 연결의 뒤늦은 `release()`가
  새 활성 클라이언트를 지우지 않는다. `test_release_after_being_evicted_does_not_clear_the_new_client`
  + `test_release_compares_by_identity_not_equality`로 고정됨.
- **SESSION 전송 실패 경로**에도 `guard.release(conn)`가 추가됨(`server.py:254-255`) — 요청서에 없던
  구멍(죽은 소켓이 슬롯을 점유)을 server-dev가 스스로 메운 것으로, 옳은 추가다.

---

## 4. Android 자동 재연결 억제 — PASS

코드 추적(변이는 작업 트리 보호를 위해 수행하지 않음. android-dev가 watchdog 분기 변이로
`9 tests, 6 failed`를 이미 확인했고, 나는 반대쪽 절반인 `reportConnectionLost`를 추적으로 검증했다):

| 확인 | 근거 |
|---|---|
| 알림 감지 시 카운터 리셋 안 하고 즉시 `reportConnectionLost(..., SESSION_REPLACED)` 후 `return` | `TrackpadRepositoryImpl.kt:503-511` — `missedBeats = 0`(512)보다 **위**에 있다 |
| `reportConnectionLost`가 재연결 정책을 **보지 않고** 곧바로 `Error` | `TrackpadRepositoryImpl.kt:567-570` — `reconnectPolicy.isActive` 검사(573)보다 **위**에서 `return` |
| `cleanUp()`/`stopKeepAlive()`는 그대로 거침 | 동 558-560 (조기 return이 정리 뒤에 있다 — 소켓 누수 없음) |
| EOF(null)는 기존 경로 유지 | 동 499-502 → `CONNECTION_LOST` → 재연결 |
| HEARTBEAT_ACK 포함 그 외 모든 줄은 카운터만 리셋 | 동 512. `isSessionReplaced`는 `type` 정확 일치만 true (`SessionReplacedNotice.kt:40`) |

테스트가 계약을 실제로 잠근다:
`TrackpadRepositorySessionReplacedTest.kt:126-145`가 **`ReconnectPolicy.Default`(실제 앱 정책)를 주입한 채**
전체 상태 전이를 수집해 `Reconnecting`을 한 프레임도 거치지 않음을 고정하고, 148-162가 120초(백오프 총량
~55초의 2배)를 진행해도 `connect`/`send` 0회임을 고정한다. 회귀 3건(237/252/266행)이 HEARTBEAT_ACK·임의
줄·평범한 EOF의 기존 동작을 잠근다. `SessionReplacedNoticeTest.kt`는 거짓 양성 쪽(유사 type·부분 일치·
다른 필드의 같은 문자열)을 10건으로 촘촘히 막는다 — 이 스위치는 거짓 양성이 훨씬 위험하므로 올바른 무게 배분이다.

**실측 교차 확인**: 위 1번 실측에서 JVM `BufferedReader`가 받은 실제 바이트가 동일 정규식 판별을 통과했다
(`notice_recognized=true`). 리터럴이 아니라 **실제 소켓에서 온 문자열**로 확인한 것이다.

---

## 5. 알림 유실 → 핑퐁 재발 위험 평가 (android-dev 잔여 위험 #1)

**결론: 주장은 타당하나, server-dev의 드레인 수정으로 실기기 도달 가능성은 사실상 0이다. 차단하지 않는다.**
정량화를 위해 밀려나는 클라이언트가 보내는 미판독 바이트 양을 바꿔 가며 실측했다.

| 시나리오 | 밀려날 때 상대가 보낸 미판독 바이트 | 결과 |
|---|---|---|
| 전송 없음(정상 앱) | 0 | 알림 수신 + clean EOF |
| 전송 후 서버가 다 읽음 | 1KB ~ 3.2MB | 알림 수신 + clean EOF (5/5) |
| **밀어내는 순간에도 계속 전송 중** | 4KB / 64KB / 512KB | 알림 수신 + clean EOF |
| **밀어내는 순간에도 계속 전송 중** | **3.2MB / 32MB** | **알림 유실 + `SocketException`** |

즉 임계는 "밀어내기 시점에 상대가 **0.1초 드레인 예산 안에 1MiB를 넘겨 계속 밀어넣는 중**"이고,
실측상 0.5MB 동시 전송까지는 안전하다. Phone Pad의 TCP 채널은 CLICK/SCROLL/DRAG/DESKTOP_SWITCH/HEARTBEAT
같은 저빈도 JSON 한 줄(수십 바이트)만 흐르므로 초당 수 KB를 넘지 않는다 — **실기기에서 도달할 수 없는
영역**이다. android-dev가 "확률은 낮다"고 쓴 것을 수치로 확정한다: 정상 앱의 위험은 무시 가능.

남은 진짜 위험은 유실이 아니라 **소켓이 이미 죽어 있어 `sendall`이 실패하는 경우**(예: 기기가 Wi-Fi를
벗어난 뒤 서버가 아직 모름)인데, 이때 그 기기는 애초에 서버에 닿지 못하므로 핑퐁이 성립하지 않는다.

→ **섹션 10 신규 항목으로 기록하되 "허용"으로 두는 것에 동의.** 다만 "발생 확률이 낮다"보다
"드레인 예산(0.1초/1MiB)을 초과해 스트리밍 중일 때만 — 현재 TCP 트래픽 프로파일로는 도달 불가"라고
조건을 명시하는 편이 다음 세션에 유용하다(누군가 TCP로 고빈도 이벤트를 추가하면 조건이 살아난다).

---

## 6. 기존 프로토콜 회귀 없음 — PASS

`git status` 기준 변경 파일은 서버 5개(신규 2 포함)·Android 6개(신규 3 포함)뿐이고, 계약을 담은 파일은
전부 무수정이다.

| 경로 | 상태 |
|---|---|
| `pc_server/input_controller.py` (handle_event / SendInput) | 무수정 — CLICK/DOUBLE_CLICK/SCROLL/DRAG/DESKTOP_SWITCH/MOVE 계약 그대로 |
| `pc_server/pin_auth.py`, `discovery.py`, `tray*.py`, `single_instance.py`, `logging_setup.py` | 무수정 |
| `domain/model/TrackpadEvent.kt` | 무수정 — 스펙대로 sealed class에 넣지 않음 |
| `data/network/TcpClient.kt`, `UdpClient.kt`, `SessionHandshake.kt`, `AuthHandshake.kt` | 무수정 |
| `presentation/util/GestureConfig.kt`, `ConnectionErrorClassifier` | 무수정 |
| UI (`TrackpadScreen`, `ConnectionErrorSection`) | 무수정 — 새 kind는 `ConnectionErrorMessages` 경유로 자동 표시 |

`server.py` 변경은 +30/-1이고 기존 이벤트 루프(261-306행)·heartbeat·AUTH 로직에 손대지 않았다.
`handle_client(..., guard=None)` / `ServerRuntime(..., single_client_guard=None)` 기본 비활성 컨벤션을
지켰고 `test_server_shutdown.py`의 시그니처 고정 테스트도 함께 갱신됐다.
`fake_conn.py` 변경은 `shutdown()` 추가뿐(순수 추가, 기존 단언에 영향 없음).

실측으로도 heartbeat 왕복(`HEARTBEAT` → `HEARTBEAT_ACK`)과 PIN 인증 3경로(성공/AUTH_FAIL/형식 오류 조용히 닫기)가
그대로 동작함을 확인했다(위 2번 로그).

---

## 주의 항목 (W) — 차단 아님

### W-1. 3기기 동시 접속 시 새 클라이언트가 SESSION 대신 SESSION_REPLACED를 핸드셰이크로 받는다 (신규 발견, 재현됨)

- **위치**: `pc_server/server.py:236-244` — `guard.take_over()`(238)와 `conn.sendall(SESSION...)`(244) 사이의 창.
  그 사이에 `single_client.evict()`(242)가 들어 있어 드레인이 예산에 걸리면 창이 **최대 0.1초**까지 벌어진다.
- **기대 vs 실제**:
  - 기대 — 새 연결 B는 항상 `{"type":"SESSION","session":"..."}` 를 첫 줄로 받는다.
  - 실제 — B가 슬롯을 잡은 직후 C가 AUTH를 통과하면 C가 B를 밀어내 **B의 첫 줄이
    `{"type":"SESSION_REPLACED"}` 가 된다.** B는 아직 SESSION을 받은 적이 없으므로 watchdog 루프가
    돌지 않고, `SessionHandshake.parseSession()`(`SessionHandshake.kt:24-32`)이 null을 돌려준다.
- **앱 쪽 귀결**: `TrackpadRepositoryImpl.kt:218` → `Error(kind=HANDSHAKE_FAILED)` →
  `ConnectionErrorMessages.kt:47-48` **"응답한 서버가 Phone Pad 서버가 아니거나 버전이 다릅니다."**
  실제 원인(다른 기기에 밀려남)과 전혀 다른 안내다.
- **재현 시나리오** (실제로 3회 중 1회 재현):
  1. A 접속·인증 후 대량 전송을 시작해 evict 드레인이 0.1초 예산을 쓰게 만든다.
  2. B가 접속·AUTH 통과 → `take_over(B)` → `evict(A)` 드레인 진입.
  3. 그 25ms 뒤 C가 접속·AUTH 통과 → `take_over(C)` → B에게 `SESSION_REPLACED` 전송.
  4. B의 `readLine()` 결과: `{"type":"SESSION_REPLACED"}` (`b_got_replaced_as_handshake=true`).
- **심각도: 낮음.** ① 기기 3대가 수십 ms 안에 동시에 인증해야 하고, ② 핑퐁으로 이어지지 않는다
  (첫/수동 연결 실패는 자동 재연결을 하지 않음 — `TrackpadRepositoryImpl.kt:157-159`), ③ 서버 상태는
  깨지지 않는다(B의 `sendall`이 OSError → `registry.remove` + `guard.release`(identity라 no-op) → close).
  피해는 **오해를 부르는 오류 문구** 하나뿐이다.
- **수정 제안 (선택, 우선순위 낮음)**: `data/network/TcpClient.kt:91` 의 핸드셰이크 분기에
  `AuthHandshake.isAuthFail(line)` 검사와 나란히 `SessionReplacedNotice.isSessionReplaced(line)` 한 줄을
  추가해, 그 경우 `ConnectionErrorKind.SESSION_REPLACED`로 분류한다(판별기가 이미 있으므로 3~4줄).
  서버는 손대지 않는다 — 서버에서 이 창을 없애려면 슬롯 교체와 SESSION 전송을 한 임계구역에 넣어야 하는데,
  그것은 "락 안에서 소켓 I/O 금지"라는 확정 스펙(요청서 서버 스펙 1)에 정면으로 어긋난다.
- **영향받는 에이전트**: android-dev (서버는 무변경).

### W-2. 드레인 예산 초과 시 알림 유실 (server-dev 한계 #1 — 확인·정량화)

5번 절 참조. 밀어내는 순간 상대가 1MiB 초과를 계속 스트리밍할 때만 발생하며 실측 임계는 0.5MB(안전)/3.2MB(유실).
현재 TCP 트래픽 프로파일로는 도달 불가. **TCP 채널에 고빈도 이벤트를 추가하는 변경이 생기면 이 항목을 다시 볼 것.**

### W-3. 밀려난 스레드의 드래그 강제 해제가 새 클라이언트의 드래그를 놓을 수 있다

- **위치**: `pc_server/server.py:319-321` (`controller.force_release_drag()`), `_drag_active`는 프로세스 전역.
- 밀어내기(`evict`) 후 밀려난 스레드가 `finally`에 도달하기까지의 수 ms 동안 새 클라이언트가
  `DRAG_START`를 보내면, 옛 스레드가 그 드래그를 해제한다. 실측 로그에서 옛 스레드의 `finally`는
  SESSION 발급 직후 곧바로 도달했고(같은 밀리초대), 사람이 그 사이에 드래그를 시작하는 것은 불가능에 가깝다.
- android-dev 판단과 일치한다. **섹션 10의 구조 항목("세션별 상태 분리 없음")으로 흡수**하는 것이 맞고,
  단일 클라이언트 정책이 이 창을 **최대 15초에서 수 ms로 줄였다**는 점이 이번 작업의 실질 성과다.

### W-4. 밀려난 기기의 UDP MOVE가 수 ms 더 처리된다 (server-dev 한계 #3)

`registry.remove()`는 밀려난 스레드의 `finally`에서 일어나므로 밀어내기 직후 수 ms 동안 그 세션 토큰이
유효하다. UDP MOVE는 상태를 남기지 않는 커서 이동이라 실해가 없다. 정책이 TCP 입장만 제한하고 UDP를
즉시 차단하지 않는다는 점은 **섹션 4에 한 줄 명시**하는 편이 좋다(다음 세션이 "단일 클라이언트인데 왜
다른 기기 MOVE가 들어오지?"로 헤매지 않도록).

### W-5. 밀어내기 로그가 정상 동작인데 `[!] Error from ...`로 남는다 (진단 품질)

밀려난 스레드의 `recv()`가 `[WinError 10038]`로 풀리므로 **모든 밀어내기마다** `server.py:308`의
`[!] Error from {addr}: ...`가 한 줄 찍힌다. 게다가 Windows OSError 문자열은 **OS 로케일로 번역되어
나오므로 비ASCII가 로그에 들어간다**(cp949 콘솔·`logging_setup`의 `encoding="utf-8", errors="replace"`
양쪽에서 예외는 나지 않음을 확인 — 섹션 9의 em dash 함정과 달리 크래시 위험은 없다).
정상 정책 동작이 에러로 보이는 것뿐이라 기능 문제는 아니다.
**수정 제안(선택)**: `single_client.py`가 evict 대상 conn을 집합에 기록해 두고 `server.py:307-308`에서
"밀려난 연결이면 `[=] Evicted client thread finished`"로 낮춰 찍는 정도. 우선순위 낮음.

### 스펙 이탈 2건 — 둘 다 승인 권고

1. **`_drain` 추가** (server-dev): 위 1·5번에서 드레인이 실제로 부하를 지는 것을 독립 확인했다.
   와이어 스펙(메시지/필드/순서)은 불변. **유지 권고.**
2. **`isSessionReplaced(line: String?)` nullable** (android-dev): `SessionHandshake`/`AuthHandshake`와
   동일 스타일이며 호출부는 이미 non-null을 넘긴다. **무해, 유지 권고.**

---

## 섹션 10 최종 문구 제안 (QA 중재)

두 에이전트의 판단이 갈린 항목이다. **android-dev 쪽이 정확하다** — server-dev의 "항목 삭제"는
관측 증상과 구조 원인을 같은 것으로 묶은 결과다. 다만 server-dev가 지적한 "이건 구현이 아니라
반대 방향 결정"이라는 점은 사유로 반드시 남겨야 한다. 두 의견을 합친 문구:

**행 제목 변경: `다중 기기 연결` → `다중 기기 연결 (정책 확정, 구조 미해소)`**

> **정책은 닫혔다 — 단일 클라이언트로 확정(Phase 5).** 여러 기기 동시 지원은 "미정"이 아니라
> **명시적으로 범위 밖**이다(서로 뺏고 뺏는 핑퐁 방지). 서버는 활성 TCP 클라이언트를 항상 최대 1개로
> 유지하므로(`single_client.SingleClientGuard`) 여러 기기가 동시에 커서를 움직이는 증상은 더 이상
> 발생하지 않는다. **구조는 그대로다:** 근본 원인인 세션별 상태 분리가 없다 — `_drag_active`는 여전히
> 프로세스 전역이고 `SessionRegistry`도 여전히 집합 기반이다. 단일 클라이언트 정책은 그 구조 위에 얹은
> **입장 제한**이지 상태 분리가 아니다. 나중에 다중 기기를 실제로 지원하기로 뒤집는다면 `_drag_active`
> 등 프로세스 전역 상태 분리부터 해야 한다. 덧붙여 정책은 **TCP 입장만** 제한한다 — 밀려난 기기의 UDP
> MOVE는 세션 토큰이 회수될 때까지(수 ms) 계속 처리된다.

**행 제목 변경: `재연결 직후 서버 세션 2개` → `재연결 직후 서버 세션 2개 (대부분 해소)`**

> **대부분 해소(Phase 5 단일 클라이언트 정책).** 예전에는 서버가 옛 연결의 EOF나 heartbeat 15초
> 타임아웃을 기다려야 해서 세션 2개 구간이 **최대 15초**였고, 그 안에 새 연결에서 드래그를 시작하면
> 옛 연결의 강제 해제가 그 드래그를 놓아버릴 수 있었다. 이제 재연결한 앱이 AUTH를 통과하는 순간 서버가
> 자기 자신의 유령 세션을 즉시 밀어내고 닫으므로(밀어내기 → 새 SESSION 발급 순서) **기존 발생 조건은
> 사실상 사라졌다.** **남은 창:** 밀려난 연결의 정리(`finally`의 세션 회수 + 드래그 강제 해제)는 그
> 연결을 처리하던 스레드에서 비동기로 돌기 때문에, 밀어내기와 그 정리 사이 **수 ms** 동안 옛 스레드의
> 강제 해제가 새 클라이언트의 드래그를 놓을 여지가 원리적으로 남는다(실측 재현 불가 수준 —
> 밀어내기 직후 사람이 손가락을 누를 수 없는 폭). 뿌리는 위 "다중 기기 연결"의 구조 항목과 같으며
> 세션별 상태 분리 때 함께 해결할 것.

**신규 행 제안: `단일 클라이언트 알림 유실 시 핑퐁 (조건부 잔여)`**

> 서버의 `SESSION_REPLACED` 전송은 best-effort다. 그 줄이 도달하지 못하면 앱은 평범한 EOF로 관측해
> 자동 재연결을 시작하고, 재연결이 성공하면 방금 들어온 기기를 다시 밀어낸다(정책이 막으려던 핑퐁).
> `single_client._drain`(닫기 전 수신 큐 비우기)이 이 경로를 막는다. **QA 실측:** 밀려나는 순간 상대가
> 동시에 보내는 미판독 바이트가 0.5MB까지는 항상 알림 + clean FIN, **드레인 예산(0.1초 / 1MiB)을 넘겨
> 계속 스트리밍 중일 때(3.2MB 이상)만 RST가 나면서 이미 도착한 알림까지 사라진다.** 현재 TCP 채널은
> 저빈도 JSON 한 줄만 흐르므로 실기기에서 도달할 수 없다. **TCP로 고빈도 이벤트를 추가하는 변경이
> 생기면 이 조건이 되살아나므로 그때 다시 볼 것.** 앱 쪽에서 EOF와 밀려남을 구분할 방법은 없어서
> 완전 차단은 프로토콜 변경(서버가 최근 밀어낸 주소의 재입장을 잠시 거절 등)이 필요하다.

**신규 행 제안: `단일 클라이언트 정책 실기기 미검증`**

> 폰 두 대로 실제 밀어내기 왕복, 밀려난 화면의 2줄 문구 렌더·소형 화면 잘림, 드래그 중 밀려났을 때
> 실기기에서 버튼이 실제로 놓이는지. QA는 실제 JVM `BufferedReader` 클라이언트 + 실서버 루프백으로
> 밀어내기·clean FIN·드래그 강제 해제·AUTH 실패 무영향까지 확인했으나, Wi-Fi 경로와 Android 런타임은
> 사람이 확인해야 한다.

**섹션 4에 추가할 내용** (양쪽 요약이 이미 잘 정리했고 QA가 확인함 — 아래만 보강 권고):
- `{"type":"SESSION_REPLACED"}` — TCP 9000, 필드 없음, session 없음, 연결 유지/전송 계층 전용
  (HEARTBEAT_ACK와 같은 범주, `TrackpadEvent` sealed class 밖 — 섹션 9 예외 규칙의 세 번째 사례).
- 밀어내기 순서: AUTH 통과 → `take_over` → 기존 연결에 알림 → **닫기 전 수신 큐 드레인** → `shutdown` → `close`
  → 그 다음 새 SESSION 발급. AUTH 미통과 시도는 기존 연결에 영향 없음. 끄는 옵션 없음.
- **정책은 TCP 입장만 제한한다** (W-4).
- **드레인 규칙 승격**(server-dev 제안에 동의): "마지막 줄을 보내고 닫는 모든 경로는 close 전에 수신 큐를
  비운다. `shutdown(SHUT_RDWR)`만으로는 RST를 막지 못한다." PIN 브루트포스 잠금(F-1)에 이은 두 번째 사례이므로
  섹션 9 컨벤션으로 올릴 만하다.

---

## 미검증 / 범위 밖

- **실기기**: 폰 두 대 왕복, Wi-Fi 경로의 FIN/RST 거동, 밀려난 화면의 문구 렌더. 전부 루프백(127.0.0.1)만 확인.
- **브루트포스 잠금 상태의 실소켓 밀어내기 무영향**: 코드 경로가 PIN 오류와 동일하고 pytest가 커버해
  실측하지 않았다(실측하면 테스트 IP가 60초 잠긴다).
- **PyInstaller exe**: 신규 모듈 `single_client.py` 추가로 재빌드 필요(server-dev 한계 #6). QA도 재빌드하지 않았다.
  `server.py`가 정적 import하므로 hiddenimports 수정은 불필요할 것으로 보이나 미확인.
- **Android 변이 검사**: 작업 트리 보호(코드 수정 금지)를 위해 수행하지 않고 코드 추적으로 대체.
  android-dev가 watchdog 분기 변이(`9 tests, 6 failed`)를 이미 수행했다.
