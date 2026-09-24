# 요청 — 단일 클라이언트 정책 (Phase 5 마지막 항목)

## 범위 판단: **교차 경계면** (사유: 새 서버→클라이언트 알림 메시지 추가 + 재연결 억제 규칙)

사용자 결정(확인됨): **단일 클라이언트로 제한**. 새 연결이 AUTH를 통과하면 기존 연결에게 `SESSION_REPLACED` 알림을 보낸 뒤 강제로 끊는다. 밀려난 기기는 **자동 재연결하지 않는다**(두 기기가 서로 뺏고 뺏는 핑퐁을 막기 위함 — 재연결하면 자신이 방금 밀려난 자리를 다시 빼앗아 상대를 밀어내는 무한 루프가 될 수 있다).

실행: android-dev ∥ server-dev 병렬 → protocol-qa 사후 검증. 리더가 아래 스펙을 사전 확정 — **임의로 바꾸지 말 것**.

공통 금지: **커밋 금지**, `AGENTS.md`/`CLAUDE.md` 수정 금지(리더가 함), **`git stash`/`checkout`/`reset` 금지**(병렬 작업 트리 보존). 저장소 루트 `C:\Github\phone_pad`, `.claude/worktrees/`는 무시.

## 와이어 스펙 (확정)

```jsonc
// 서버 → 클라이언트: 새 연결이 이 연결을 대체했을 때, 강제로 닫기 직전에 딱 한 번 보낸다
{"type":"SESSION_REPLACED"}
```
- 필드 없음. TCP 9000, session 필드 없음(이 연결 자체가 곧 끊길 것이므로 의미 없음).
- **연결 유지/전송 계층 전용 메시지**(HEARTBEAT/HEARTBEAT_ACK와 같은 범주) — `TrackpadEvent` sealed class에 넣지 않는다(AGENTS.md 섹션 9 예외 규칙).
- 이 알림은 **이미 SESSION을 받고 정상 동작 중이던 연결**에게만 간다. 새로 접속을 시도 중인 클라이언트(AUTH 단계)는 대상이 아니다 — AUTH가 성공한 클라이언트만 "새 활성 클라이언트"가 되어 기존 클라이언트를 밀어낼 자격이 생긴다. **PIN이 틀렸거나(AUTH_FAIL), 형식 오류, 타임아웃, 브루트포스 잠금** 등으로 AUTH를 통과하지 못한 시도는 기존 연결에 어떤 영향도 주지 않는다(기존 연결 유지, 알림 없음).
- 서버는 **활성 클라이언트를 항상 최대 1개**로 유지한다. 새 연결이 AUTH를 통과하는 순간, 그 시점에 활성 상태이던 연결(있다면)에게 `SESSION_REPLACED`를 보내고(best-effort — 이미 죽은 소켓일 수 있으므로 전송 실패는 무시) 강제로 닫는다. 그 다음에야 새 연결에 SESSION을 발급한다(밀어내기 → 새 SESSION 발급 순서, 반대로 하면 순간적으로 활성 클라이언트가 2개가 되는 창이 생긴다).
- 밀려난 연결이 강제로 닫히면 **기존 안전장치가 그대로 작동해야 한다**: `handle_client`의 `finally` 블록(세션 토큰 회수, 드래그 강제 해제)이 정상적으로 실행됨을 실측으로 확인할 것(다른 스레드에서 소켓을 닫아도 그 연결을 처리하던 스레드의 `recv()`가 예외로 풀려나와 기존 `except Exception`/`finally` 경로를 그대로 탄다 — 새 예외 처리를 추가할 필요는 없을 것이나 실제로 검증할 것).
- **이 정책에는 끄는 옵션을 두지 않는다**(`--pin`/`--no-auth`/`--no-discovery`/`--no-tray`/`--allow-multiple`과 달리, 이번 것은 사용자가 방금 확정한 제품 정책이지 개발 편의 토글이 아니다).
- **Android는 이 알림을 받으면 자동 재연결을 시작하지 않고 곧바로 `Error`로 간다** — "Connected였던 세션의 유실은 항상 재연결을 시작한다"는 기존 규칙(AGENTS.md 섹션 6 자동 재연결 설계)의 **의도된 예외**다.

## 서버 스펙 (server-dev)

1. 신규 클래스(예: `pc_server/server.py` 안에 `SingleClientGuard`, 또는 작은 별도 모듈 — server-dev 판단): `Lock` + `_current`(현재 활성 연결의 `(conn, addr, session)` 또는 `None`) 상태를 갖는다.
   - `take_over(conn, addr, session) -> (evicted_conn, evicted_addr) | None`: 락 안에서 `_current`를 새 연결로 교체하고, **교체되기 전의 값**을 반환한다(없었으면 None). **소켓 I/O(전송/닫기)는 락 밖에서** 호출자가 한다(락 안에서 I/O를 하면 다른 연결의 accept를 불필요하게 오래 막는다).
   - `release(conn)`: 연결이 스스로 끝날 때(정상 종료/heartbeat 타임아웃/예외) 호출한다. **`_current`가 여전히 이 conn을 가리킬 때만** 지운다(identity 비교, `is`) — 이미 다른 연결에 밀려난 뒤라면 아무것도 하지 않는다(안 그러면 오래된 연결의 뒤늦은 정리가 새 활성 클라이언트의 슬롯을 지워버린다).
2. `handle_client(..., guard=None)` — **기본값은 비활성**(discovery_port/auth_limiter와 같은 이유: 기존 단위 테스트가 guard 없이 호출하면 지금까지와 동일하게 동작해야 한다). 통합 지점: `authenticate_client()`가 통과한 **직후, SESSION을 보내기 전**:
   - `guard`가 있으면 `evicted = guard.take_over(conn, addr, session)`.
   - `evicted`가 있으면: `evicted_conn`에 `{"type":"SESSION_REPLACED"}\n`을 best-effort로 전송(예외 무시) → `evicted_conn.shutdown(SHUT_RDWR)`(예외 무시) → `evicted_conn.close()`. ASCII 로그 한 줄(예: `[!] Evicted previous client: {addr}`).
   - 그 다음 기존처럼 SESSION 발급.
   - `finally` 블록에 `if guard is not None: guard.release(conn)` 추가(세션 토큰 회수와 같은 위치).
3. `ServerRuntime(..., single_client_guard=None)` — **기본값 비활성**(같은 이유). `main()`이 실제 서버를 띄울 때는 **항상** `SingleClientGuard()`를 만들어 넘긴다(끄는 CLI 옵션 없음 — 위 와이어 스펙 참조).
4. 테스트(`pc_server/tests/test_single_client.py` 신규):
   - 순수 `SingleClientGuard`: 첫 `take_over`는 evicted=None, 두 번째는 첫 번째 conn을 evicted로 반환, `release`는 identity 일치할 때만 지움(밀려난 뒤의 `release` 호출은 무시됨을 검증).
   - **TCP end-to-end**(`handle_client` 실제 구동, `tests/fake_conn.py`의 공용 `FakeConn` 재사용): 두 번째 연결이 AUTH를 통과하면 첫 번째가 `SESSION_REPLACED`를 받고 닫히며, `SessionRegistry`에서 첫 번째 세션 토큰이 제거됨, 드래그 강제 해제 안전장치가 밀려난 연결에도 동작함(드래그 활성 중 밀려나면 버튼이 놓임 — 기존 "연결 종료 시 강제 해제" 테스트 패턴 재사용), 두 번째는 정상 SESSION을 받음.
   - **PIN이 틀린 시도는 기존 연결에 영향 없음**: 인증 켜진 상태에서 잘못된 PIN으로 접속을 시도해도 기존 활성 연결은 `SESSION_REPLACED`를 받지 않고 계속 동작.
   - 브루트포스 잠금 중인 IP의 시도도 기존 연결에 영향 없음(같은 이유 — AUTH를 통과하지 못했으므로).
   - **실소켓 테스트**(`pin_auth` 때 쓴 `real_runtime`/`exchange` 패턴 참고): 실제 두 개의 `socket.create_connection()`으로 첫 번째가 `SESSION_REPLACED` 줄을 받고 그 다음 EOF를 관측함을 확인(잠금 close의 clean FIN 교훈과 마찬가지로, 여기서도 `shutdown` 순서가 RST 없이 깨끗이 닫히는지 실측 권장).
   - `ServerRuntime` 기본값(guard 없음)에서는 두 연결이 동시에 살아있어도 서로 영향 없음(기존 회귀 확인).
5. `python -m pytest` 기준선 **547 passed, 1 skipped** → 회귀 0.

## Android 스펙 (android-dev)

1. `data/network/`에 순수 리터럴 판별 유틸(예: `SessionReplacedNotice.isSessionReplaced(line: String): Boolean`, `SessionHandshake`/`AuthHandshake`와 같은 스타일의 가벼운 정규식 기반 — 전체 JSON 파싱 라이브러리 불필요).
2. `data/repository/TrackpadRepositoryImpl.kt`의 `heartbeatWatchdogLoop()`: 지금은 `tcpClient.readLine()`이 null이 아니면 무조건 `missedBeats = 0`으로 리셋한다(내용을 파싱하지 않음). **이 줄에서만** 예외를 둔다 — 읽은 줄이 `SessionReplacedNotice.isSessionReplaced(line)`이면 카운터를 리셋하지 않고 즉시 `reportConnectionLost(forGeneration, MESSAGE_SESSION_REPLACED, ConnectionErrorKind.SESSION_REPLACED)` 후 `return`. 그 외 모든 줄(HEARTBEAT_ACK 포함)은 기존처럼 카운터만 리셋 — **다른 어떤 내용도 파싱하지 않는다**(범위를 넓히지 말 것).
3. `reportConnectionLost()`: `kind == ConnectionErrorKind.SESSION_REPLACED`일 때는 **재연결 정책을 확인하지 않고 곧바로** `_connectionState.value = ConnectionState.Error(message, kind)`로 간다(기존 "Connected였던 세션의 유실 → 재연결 시작" 분기를 타지 않는다 — 이 kind만의 의도된 예외). 다른 kind는 기존 로직(재연결 정책 활성이면 `Reconnecting`, 아니면 `Error`) 그대로 유지.
4. `domain/model/ConnectionErrorKind.kt`에 `SESSION_REPLACED` 추가. `presentation/util/ConnectionErrorMessages.kt`에 한국어 문구 추가(예: "다른 기기가 이 PC에 새로 연결되어 현재 연결이 끊겼습니다. 다시 연결하려면 재접속하세요." — 자동으로 되지 않는다는 것을 명확히 할 것). 이 kind는 `Throwable`에서 분류되는 게 아니라(HEARTBEAT_TIMEOUT/RECONNECT_FAILED와 같은 방식으로) 코드에서 직접 지정하므로 `ConnectionErrorClassifier` 수정은 필요 없다.
5. 새 상수(내부 진단 문자열, 표시용 아님): `MESSAGE_SESSION_REPLACED = "Session replaced by another device"` 같은 리터럴을 companion object에 추가.
6. 화면 쪽 새 컴포넌트 불필요 — 기존 `ConnectionErrorSection`/Disconnected-Error 화면이 이 kind도 그대로 표시한다(문구만 다르게 보임). UI diff 없음이 목표.
7. 테스트: `SessionReplacedNotice` 리터럴 판별(정상/변형/HEARTBEAT_ACK와 구분), `TrackpadRepositoryImplTest` 또는 신규 파일에 heartbeat watchdog 루프 테스트(가상 시간, `SESSION_REPLACED` 수신 시 재연결 루프가 **시작되지 않고** 곧바로 `Error(kind=SESSION_REPLACED)`가 됨 — `ReconnectPolicy.Default`를 주입해도 `Reconnecting`을 절대 거치지 않음을 고정, 다른 임의의 줄은 기존처럼 카운터만 리셋함을 회귀로 확인), `ConnectionErrorMessagesTest`(새 kind도 문구를 가짐 — 기존 "모든 kind가 문구를 갖는다" 루프가 자동 커버할 것).
8. 런타임 테스트 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`로 강제 재실행. 기준선 **420 → 회귀 0**.

## 산출물
- 각자 `_workspace/01_android-dev_summary.md` / `_workspace/01_server-dev_summary.md`(변경 파일, 신규 테스트, 테스트 결과 수치, 스펙 이탈 여부, 미해결 이슈/한계, "리더가 AGENTS.md에 반영할 내용" — 특히 이 작업이 섹션 10의 "다중 기기 연결"·"재연결 직후 서버 세션 2개" 두 미결 항목을 해소하는지에 대한 각자의 판단 포함).
- 커밋하지 말 것.
