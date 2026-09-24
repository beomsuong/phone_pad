# server-dev 요약 — 단일 클라이언트 정책 (`SESSION_REPLACED`)

## 변경 파일

| 파일 | 상태 | 내용 |
|---|---|---|
| `pc_server/single_client.py` | **신규** (142줄) | `SingleClientGuard`(Lock + `_current` 슬롯), `SESSION_REPLACED_LINE`, `evict(conn, addr)`, `_drain(conn)` |
| `pc_server/server.py` | 수정 (**+30 / -1**) | `handle_client(..., guard=None)` 통합, `ServerRuntime(..., single_client_guard=None)`, `main()` 이 항상 가드 생성 |
| `pc_server/tests/test_single_client.py` | **신규** (38 테스트) | 아래 참조 |
| `pc_server/tests/fake_conn.py` | 수정 (+7) | 공용 `FakeConn` 에 `shutdown(how)` 추가 (`shutdowns`, `call_order` 기록) |
| `pc_server/tests/test_server_shutdown.py` | 수정 (+3) | 기존 시그니처 고정 테스트에 `guard` 파라미터 + 기본값 `None` 반영 |

`phone_pad_app/`, `AGENTS.md`, `CLAUDE.md`, `.claude/worktrees/` **무수정**. 커밋 안 함. `git stash`/`checkout`/`reset` 사용 안 함.

## 와이어/동작

- 서버 → 클라이언트: `{"type":"SESSION_REPLACED"}\n` (필드 없음, session 없음). 밀려나기 **직전 딱 한 번**.
- 순서: AUTH 통과 → `registry.issue()` → `guard.take_over()` → (밀려난 게 있으면) `evict()` → **그 다음** SESSION 줄 전송. 테스트 `test_eviction_happens_before_the_new_session_line_goes_out` 으로 순서 고정.
- AUTH 미통과(PIN 오류 / 잠금 / 형식 오류 / EOF / 타임아웃)는 **슬롯을 건드리지 않음** — 기존 연결 유지, 알림 없음.
- `guard.release(conn)` 은 `is` 비교로 자기 슬롯일 때만 지움. 밀려난 뒤의 뒤늦은 정리는 무시(`test_release_after_being_evicted_does_not_clear_the_new_client`).
- 소켓 I/O 는 전부 락 **밖**. `take_over()` 는 `(evicted_conn, evicted_addr)` 만 반환.
- 로그: `[!] Evicted previous client: {evicted_addr} (replaced by {addr})` — ASCII 전용(신규 print 리터럴 전수 확인).
- 기본값 비활성 컨벤션 준수: `handle_client(..., guard=None)`, `ServerRuntime(..., single_client_guard=None)`. `main()` 만 항상 가드 생성, **끄는 CLI 옵션 없음**(`test_parse_args_has_no_switch_to_disable_the_policy` 로 고정).

## 스펙 이탈: 1건 (의도적, 근거는 실측)

**스펙**: `shutdown(SHUT_RDWR)` → `close()`
**구현**: `sendall` → **`_drain(conn)`** → `shutdown(SHUT_RDWR)` → `close()`

리더가 지시한 "RST 방지"를 실측으로 검증하다가 **`shutdown(SHUT_RDWR)` 만으로는 RST 를 막지 못한다**는 것을 발견했습니다. Windows 루프백 실측(`close_only` / `shutdown_close` / `drain_shutdown_close` / `shutwr_drain_close` 4가지 비교):

```
close_only             -> RST: 10054, 클라이언트가 받은 바이트: b''
shutdown_close         -> RST: 10054, 클라이언트가 받은 바이트: b''   <-- 스펙대로 해도 실패
drain_shutdown_close   -> clean EOF,  b'{"type":"SESSION_REPLACED"}\n'
shutwr_drain_close     -> clean EOF,  b'{"type":"SESSION_REPLACED"}\n'
```

수신 큐에 미판독 바이트가 남으면 커널이 RST 를 보내고, **이미 도착해 있던 `SESSION_REPLACED` 까지 클라이언트 버퍼에서 날아갑니다**. 그러면 앱은 "밀렸다"와 "그냥 끊겼다"를 구분하지 못하고 자동 재연결을 시작해 — 이 기능이 막으려던 바로 그 핑퐁이 납니다. PIN 브루트포스 잠금 close 때의 F-1 과 같은 함정이고, 해법도 같습니다(닫기 전 짧은 드레인).

- 와이어 스펙(메시지/필드/순서)은 **바뀌지 않았습니다**. `shutdown` → `close` 순서도 그대로 유지했고, 그 앞에 예산 제한 드레인만 추가했습니다.
- 예산: `DRAIN_BUDGET_S=0.1`, `DRAIN_POLL_TIMEOUT_S=0.02`, `DRAIN_MAX_BYTES=1MiB`. 밀려난 클라이언트가 계속 밀어넣어도 새 클라이언트의 SESSION 발급이 붙잡히지 않습니다(`test_evict_drain_stops_at_the_time_budget_when_the_peer_floods`).
- 삼킨 바이트는 어차피 버려질 이벤트입니다. `DRAG_END` 를 삼키더라도 `finally` 의 드래그 강제 해제가 버튼을 놓습니다(테스트로 확인).
- **리더 판단 필요**: 이탈이 부적절하다면 드레인을 빼면 되지만, 그러면 밀려난 앱이 `SESSION_REPLACED` 를 못 받는 경우가 생깁니다.

## 신규 테스트 (38건, `tests/test_single_client.py`)

1. **순수 `SingleClientGuard` (9)** — 첫 `take_over`=None / 두 번째가 첫 conn 반환 / `release` identity(`is`) 비교(`__eq__`가 항상 True인 대역으로 `==` 사용을 배제) / 밀려난 뒤 `release` 무시 / 멱등 / 24스레드 동시 `take_over` 시 밀려난 개수가 정확히 N-1
2. **`evict()` I/O (7)** — 알림 1줄 → 드레인 → `shutdown(SHUT_RDWR)` → `close()` 순서, 전송 실패/`shutdown` 실패/드레인 불가에도 반드시 close, 드레인이 EOF·시간예산에서 멈춤, 와이어 페이로드에 잉여 필드 없음
3. **`handle_client` end-to-end, `FakeConn` (6)** — 두 번째가 첫 번째를 밀어냄(첫 번째: `["SESSION","SESSION_REPLACED"]` + 세션 토큰 회수 + `closed`) / 알림 정확히 1회 / 3연속 접속 시 각자 한 번씩만 밀림 / **드래그 활성 중 밀려나면 LEFTDOWN→LEFTUP** / 슬롯이 항상 최신 1개 / 밀어내기가 새 SESSION 전송보다 먼저
4. **AUTH 미통과는 무영향 (4)** — 잘못된 PIN / 브루트포스 잠금 / 형식 오류 / AUTH 전 EOF
5. **회귀: guard 기본값 (6)** — guard 없이 호출하면 종전과 동일(shutdown 호출 0) / 두 연결 공존 / `ServerRuntime` 기본 None / 주입 저장 / `main()` 은 플래그 조합 3가지 전부에서 가드 생성 / 정책을 끄는 CLI 스위치 부재
6. **실소켓 (5)** — 첫 클라이언트가 `SESSION_REPLACED` 를 읽고 그 다음 **clean EOF**(`recv()==b''`) / **미판독 바이트가 쌓인 상태에서도 clean EOF**(서버 스레드를 `handle_event` 안에 붙잡아 큐를 500KB 채우는 결정적 재현) / 밀려난 세션 토큰 회수 / 잘못된 PIN 시 기존 연결 무영향 / 가드 없으면 두 연결 공존

### 변이(mutation) 검증
- `shutdown` 제거 → `test_evict_sends_the_notice_then_shuts_down_then_closes` 실패
- `_drain` 제거 → 실소켓 `..._with_unread_bytes_still_gets_clean_eof` 가 실제로 `ConnectionResetError(10054)` 로 실패 (+ 단위 2건) → RST 회귀 테스트가 진짜로 동작함을 확인

## 테스트 결과 (실제 실행 수치)

```
기준선 : 547 passed, 1 skipped
현재   : 585 passed, 1 skipped in 7.15s      (회귀 0, 신규 38)
연속 3회 재실행: 585 / 585 / 585 passed — 스레드 테스트 flake 없음
```

### 실서버 실측 (`python server.py --no-tray --no-discovery --pin 483920`, 포트 9000)
```
first  -> {"type": "SESSION", "session": "6434879a...}
bad    -> {"type":"AUTH_FAIL","reason":"invalid_pin"}      (잘못된 PIN)
first after bad pin -> still quiet (OK)                    (기존 연결 무영향)
second -> {"type": "SESSION", "session": "13a0420b...}
first gets -> {"type":"SESSION_REPLACED"}
first tail -> b''                                          (clean FIN, RST 아님)
```
종료 후 9000/9001 LISTENING 잔여 없음.

## 미해결 이슈 / 한계

1. **드레인은 best-effort.** 밀려난 클라이언트가 1MiB 이상을 0.1초 안에 계속 밀어넣으면 드레인이 예산에서 멈추고 RST 가 날 수 있습니다. TCP 채널은 저빈도 이벤트(클릭/스크롤/드래그/데스크톱전환)만 흐르므로 실기기에서 도달 가능성은 사실상 없지만, 계약이 완전히 닫히지는 않았습니다.
2. **`_drain` 이 `conn.settimeout()` 을 다른 스레드와 공유되는 소켓에 겁니다.** 그 소켓은 수 ms 뒤 닫히므로 영향은 없지만, 밀려난 스레드의 `recv()` 가 `socket.timeout` 으로 풀려 heartbeat miss 로 세는 찰나가 있을 수 있습니다(어차피 곧 닫혀 `finally` 로 갑니다).
3. **UDP MOVE 는 정책 밖.** 밀려난 기기의 세션 토큰은 `finally` 에서 회수되지만, 회수 전 몇 ms 동안 도착한 UDP MOVE 패킷은 여전히 처리됩니다. 단일 클라이언트 정책이 UDP 채널을 즉시 차단하지는 않습니다(실용상 무해, 하지만 명시해 둡니다).
4. **동시 AUTH 통과 2건의 미세 창.** `registry.issue()` 가 `take_over()` 보다 앞이라, 밀려난 세션이 자기 `finally` 를 돌 때까지 레지스트리에 토큰이 2개 있습니다(수 ms). 슬롯은 항상 1개이므로 정책 자체는 지켜집니다.
5. **실기기 미검증.** 모든 확인은 루프백(127.0.0.1)입니다. Wi-Fi 경로의 RST/FIN 거동과 Android 수신은 protocol-qa / 실기기 테스트 몫입니다.
6. **PyInstaller exe 재빌드 미수행** — 새 모듈 `single_client.py` 가 추가되었으므로 exe 를 다시 빌드해야 반영됩니다(`server.py` 가 직접 import 하므로 `phone_pad_server.spec` hiddenimports 수정은 불필요할 것으로 보이나 미확인).

## 리더가 AGENTS.md 에 반영할 내용

### 섹션 4 (통신 프로토콜)
- 서버 → 클라이언트 메시지 표에 `SESSION_REPLACED` 추가: TCP 9000, 필드 없음, **연결 유지/전송 계층 전용**(HEARTBEAT_ACK 와 같은 범주, `TrackpadEvent` sealed class 밖).
- "서버는 활성 TCP 클라이언트를 항상 최대 1개로 유지한다. 새 연결이 **AUTH 를 통과하는 순간** 기존 활성 연결에 `SESSION_REPLACED` 를 보내고 닫는다(밀어내기 → 새 SESSION 발급 순서). AUTH 미통과 시도는 기존 연결에 영향 없음." 명시.

### 섹션 9 (컨벤션) — 새 함정 2개
- **"소켓을 닫기 전에는 수신 큐를 비워라. `shutdown(SHUT_RDWR)` 만으로는 부족하다."** 실측: 미판독 바이트가 남은 채 `close()` 하면 Windows 는 RST 를 보내고, `shutdown` 을 먼저 불러도 막히지 않으며, **이미 보낸 줄까지 클라이언트 버퍼에서 사라진다**. PIN 잠금(F-1)에 이어 두 번째 사례이므로 "마지막 줄을 보내고 닫는 모든 경로"의 규칙으로 승격할 만합니다.
- **실소켓 테스트에서 실제 이벤트 줄을 보내지 말 것.** `real_runtime()` 은 진짜 `InputController` 를 쓰므로 `CLICK`/`SCROLL` 을 보내면 개발자 PC 의 마우스가 실제로 눌립니다(작성 중 한 번 실제로 발생시켰고, 더미 바이트 + `InputController` 대역으로 교체했습니다).

### 섹션 10 (미해결 항목) — 두 항목에 대한 판단

**"재연결 직후 서버 세션 2개" → 해소됨.** 이번 가드가 정확히 이 증상을 겨냥합니다. 재연결한 앱이 AUTH 를 통과하면 서버가 **자기 자신의 유령 세션**을 즉시 밀어내고 닫으므로, "서버 세션 2개 공존 시 드래그 강제 해제 오작동" 가능성도 함께 닫힙니다(밀려난 연결의 `finally` 에서 드래그가 실제로 해제되는 것을 테스트·실측으로 확인). 다만 UDP 세션 토큰 회수에는 수 ms 지연이 있다는 점(위 한계 3·4)은 각주로 남기는 것이 정확합니다.

**"다중 기기 연결" → 해소가 아니라 "설계 결정으로 닫음".** 이 항목은 원래 *여러 기기를 동시에 지원할지*에 대한 미정 사항이었는데, 이번에 사용자가 **단일 클라이언트로 제한**을 확정했습니다. 즉 기능이 구현된 게 아니라 **반대 방향으로 결정되어 항목이 없어진** 것입니다. "미해결"에서 빼고 섹션 4 정책 문단으로 옮기되, "여러 기기 동시 지원은 명시적으로 범위 밖(뺏고 뺏기는 핑퐁 방지)" 이라고 사유와 함께 기록하는 편이 나중에 다시 논의될 때 유용합니다.

### 커밋 분리
서버 변경은 `feat(server): 단일 클라이언트 정책 — SESSION_REPLACED 밀어내기` 한 덩어리입니다(신규 `single_client.py` + `server.py` + 테스트 3파일).
