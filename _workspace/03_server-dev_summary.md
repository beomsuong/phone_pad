# server-dev 작업 요약 — TCP Heartbeat (Phase 2)

## 변경 파일
| 파일 | 변경 내용 |
|------|-----------|
| `pc_server/server.py` | heartbeat 상수 3개 추가, `handle_client` 핸드셰이크 직후 `settimeout`, 수신 루프에 `socket.timeout` 카운터, HEARTBEAT 가로채기 + ACK 응답 |
| `pc_server/tests/test_server_heartbeat.py` | **신규** — heartbeat 처리/타임아웃 판정 테스트 17건 |
| `pc_server/tests/test_server_udp_session.py` | 기존 `FakeConn`에 `settimeout()` 스텁 + `timeouts` 기록 필드 추가 (서버가 이제 `conn.settimeout`을 호출하므로 mock 소켓도 대응해야 함). 테스트 로직 자체는 변경 없음 |

`input_controller.py`, `SessionRegistry`, `handle_udp_packet`, `udp_listener`, 기존 buffer/newline 파싱 로직은 **변경 없음**.

## 구현한 상수 / 와이어 포맷
```python
HEARTBEAT_INTERVAL_S = 5.0
HEARTBEAT_MISS_LIMIT = 3
HEARTBEAT_ACK_LINE = b'{"type":"HEARTBEAT_ACK"}\n'   # separators=(",",":") 로 공백 없이 직렬화
```
- 수신: `{"type":"HEARTBEAT"}\n` (TCP 9000, 기존 newline-delimited JSON 그대로)
- 응답: `{"type":"HEARTBEAT_ACK"}\n` — 스펙 문자열과 바이트 단위로 동일 (테스트 `test_ack_line_is_exact_wire_format`로 고정)

## 타이밍 / 판정 로직 (server.py:102-154)
1. **핸드셰이크**: `SESSION` 줄을 `sendall`로 보낸 **직후** `conn.settimeout(HEARTBEAT_INTERVAL_S)` 호출. 핸드셰이크 자체는 기존대로 블로킹 상태에서 수행되고, 실패하면(OSError) 타임아웃을 걸지 않고 기존 경로대로 세션 회수 + 소켓 종료.
2. **수신 루프**: `conn.recv(4096)`만 내부 `try`로 감싸 `socket.timeout`을 잡는다.
   - `socket.timeout` → `missed += 1`, 로그 `[!] Heartbeat miss n/3 from <addr>` 출력.
   - `missed >= HEARTBEAT_MISS_LIMIT`(3) → 로그 `[!] Heartbeat timeout: dropping <addr>` 후 `break` → 기존 `finally`의 `registry.remove(session)` + `conn.close()`를 그대로 태운다. 3회째에 즉시 빠져나가므로 4번째 `recv()`는 호출되지 않는다 (실측 무응답 시간 ≈ 15초).
   - 한도 미달이면 `continue`로 루프 유지.
   - `recv()`가 데이터를 반환하면 `missed = 0`. **완전한 JSON 줄이 아니어도(개행 없는 부분 수신도) 리셋**된다 — 리셋은 파싱 전에 수행.
   - `recv()`가 `b""`(EOF)면 기존과 동일하게 `break`.
3. **HEARTBEAT 가로채기**: 줄 파싱 후 `isinstance(event, dict) and event.get("type") == "HEARTBEAT"`이면 `controller.handle_event`를 호출하지 않고 `conn.sendall(HEARTBEAT_ACK_LINE)` 후 다음 줄로. 그 외 타입은 전부 기존대로 `handle_event`.
   - 부수 정리 1건: 기존에는 `json.loads` + `handle_event`가 같은 `try` 안에 있었는데, JSON 파싱 실패와 이벤트 처리를 분리하고 파싱 실패 시 `continue`하도록 바꿨다 (동작 동일, HEARTBEAT 분기를 끼워넣기 위한 구조 변경).
   - ACK 전송 중 `sendall`이 실패하면 예외가 기존 `except Exception` → `finally`로 흘러 세션이 정상 회수된다.
   - JSON이 dict가 아닌 경우(`[1,2,3]`, `"HEARTBEAT"`)는 HEARTBEAT로 오인하지 않고 기존 경로 유지.

## 테스트 (`pc_server/tests/test_server_heartbeat.py`, 17건)
mock 소켓(`FakeConn`) 패턴은 기존 `test_server_udp_session.py`와 동일하며, `recv()` 큐에 예외 인스턴스를 넣어 `socket.timeout`을 시뮬레이션한다. 실제 소켓/`SendInput` 호출 없음.

상수·타임아웃 설정
- `test_heartbeat_constants_match_spec` — 5.0 / 3
- `test_handle_client_sets_socket_timeout_after_handshake` — SESSION 전송 후 `settimeout(5.0)` 1회
- `test_handshake_failure_does_not_set_timeout` — 핸드셰이크 실패 시 타임아웃 미설정 + 세션 회수

HEARTBEAT → ACK
- `test_heartbeat_gets_ack_and_is_not_passed_to_handle_event` — **ACK 응답 + `handle_event` 미호출**
- `test_ack_line_is_exact_wire_format`
- `test_each_heartbeat_gets_its_own_ack` — 3회 수신 → ACK 3회
- `test_heartbeat_split_across_chunks_is_acked_once` — 청크 경계 분할 수신
- `test_non_heartbeat_events_still_reach_handle_event` — CLICK은 기존대로 처리
- `test_invalid_json_still_ignored_and_does_not_produce_ack`
- `test_non_dict_json_line_does_not_crash`

미응답 카운트 / 연결 해제
- `test_three_consecutive_timeouts_revoke_session_and_close_socket` — **연속 3회 → 세션 회수 + 소켓 종료**, `recv` 호출 3회로 멈춤
- `test_two_timeouts_alone_do_not_close_connection` — **2회로는 끊기지 않고 이후 이벤트 정상 처리**
- `test_heartbeat_resets_miss_count` — timeout 2 → HEARTBEAT → timeout 2 → CLICK 처리 성공
- `test_partial_data_without_newline_also_resets_count` — 개행 없는 부분 수신도 리셋
- `test_timeouts_after_reset_still_eventually_disconnect` — 리셋 후 다시 3회 쌓이면 종료, 이후 큐의 CLICK은 미처리
- `test_eof_disconnect_still_works_with_timeout_enabled`
- `test_ack_send_failure_does_not_leak_session`

### 실행 결과
```
$ cd pc_server && python -m pytest
45 passed in 0.23s
```
(heartbeat 17건 + 기존 UDP/세션·input_controller 28건 전부 통과, 회귀 없음)

## 남은 이슈 / 인수인계
1. **AGENTS.md 문서 갱신 필요** — 섹션 4의 `{"type":"HEARTBEAT"}` 주석 "(Phase 2, 미구현)", 섹션 6 Phase 2 체크박스 `[ ] TCP heartbeat`, 섹션 7 다이어그램의 HEARTBEAT/HEARTBEAT_ACK "(미구현)" 표기를 구현 완료로 바꿔야 한다. 서버 쪽만 완료된 상태이므로 android-dev 작업과 합쳐 리더/QA가 일괄 갱신하는 편이 안전.
2. **서버는 HEARTBEAT를 먼저 보내지 않는다** — 확정 스펙대로 클라이언트 주도(client→server HEARTBEAT, server→client ACK) 단방향 트리거다. Android가 5초마다 보내지 않으면 서버는 15초 후 세션을 회수한다. 즉 **Android 구현이 배포되지 않은 구버전 앱은 15초마다 끊긴다** (하위 호환 없음) — 양쪽 동시 배포 필요.
3. **타임아웃은 TCP 소켓에만 적용** — UDP MOVE 채널(9001)에는 영향 없음. 단, Android가 MOVE만 계속 보내고 TCP HEARTBEAT를 멈추면 서버는 TCP를 끊고 세션을 회수하며, 그 순간부터 UDP MOVE는 `is_active` 실패로 조용히 무시된다 (의도된 동작).
4. `missed` 카운터는 "연속 타임아웃" 기준이라 실제 무응답 감지는 10초 초과 ~ 15초 사이에서 발생할 수 있다(3번째 타임아웃 만료 시점 기준). AGENTS.md의 "5초 × 3회 ≈ 15초" 스펙 범위 내.
5. `socket.timeout`은 Python 3.10+에서 `TimeoutError` 별칭이며 `OSError` 하위 클래스다. 현재 `except socket.timeout`이 `except Exception`보다 안쪽에 있어 정상 동작하지만, 향후 루프에 `OSError` 핸들러를 추가할 때는 순서(timeout이 먼저)를 유지해야 한다.
