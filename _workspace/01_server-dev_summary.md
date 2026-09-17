# 01 server-dev — MOVE UDP 분리 + 세션 토큰 (완료)

## 변경/추가 파일

| 파일 | 상태 | 내용 |
|------|------|------|
| `pc_server/server.py` | 수정 | `SessionRegistry`, `handle_udp_packet`, `udp_listener` 추가 / `handle_client`에 세션 발급·해제 추가 / `main()`에 UDP 리스너 스레드 기동 |
| `pc_server/input_controller.py` | **변경 없음** | 기존 `handle_event`의 `float()` → `int(round())` 변환으로 UDP MOVE를 그대로 재사용 가능 (확인 완료) |
| `pc_server/tests/conftest.py` | 신규 | `pc_server/`를 `sys.path`에 추가 (평면 모듈 import용) |
| `pc_server/tests/test_input_controller.py` | 신규 | `handle_event` 분기 단위 테스트 (7개) |
| `pc_server/tests/test_server_udp_session.py` | 신규 | 세션/UDP/TCP 수명 테스트 (21개) |

## 실제 구현 스펙

### TCP 9000 (핸드셰이크 추가, 기존 이벤트 처리 로직 무변경)
- accept 직후 `uuid.uuid4().hex` (32자리 hex)로 토큰 발급 → 활성 집합에 등록 →
  `{"type":"SESSION","session":"<32 hex>"}\n` **한 줄을 첫 바이트로** 전송
- 이후 `buffer` 기반 newline-delimited JSON 처리 루프는 기존 코드 그대로 (`CLICK` 등)
- `finally` 블록에서 세션 제거 → 재연결 시 반드시 새 토큰 발급
- 세션 전송 실패(OSError) 시 토큰 즉시 회수하고 연결 종료

### UDP 9001 (MOVE 전용)
- `main()`에서 `0.0.0.0:9001` 바인딩, `udp_listener`를 daemon 스레드로 기동 (TCP accept 루프보다 먼저)
- 수신 패킷: `{"session":"<토큰>","type":"MOVE","dx":2.5,"dy":-1.0}` (개행 불필요, 1 패킷 = 1 이벤트)
- `handle_udp_packet`의 무시 조건 (모두 예외 없이 `False` 반환):
  1. UTF-8 디코드 실패 / JSON 파싱 실패 / dict 아닌 JSON(`[1,2,3]` 등)
  2. `session` 필드 없음 / 문자열 아님 / 활성 목록에 없음(미등록·만료)
  3. `type != "MOVE"` (CLICK 등이 UDP로 와도 처리하지 않음 — 채널 원칙 유지)
  4. `controller.handle_event` 내부 예외 (로그만 남기고 루프 유지)
- 통과 시 `InputController.handle_event({"type":"MOVE","dx":...,"dy":...})` 호출 (session 필드는 벗겨서 전달 → `handle_event` 시그니처 불변)

### 동시성
- `SessionRegistry`가 `threading.Lock`으로 `set`을 보호. `issue/add/remove/is_active/snapshot` 전부 락 내부에서 동작
- TCP 스레드(쓰기) ↔ UDP 스레드(읽기) 간 공유는 이 클래스 하나로만

## 테스트 결과

```
cd pc_server && python -m pytest -q
28 passed in 0.19s
```

`SendInput`은 단 한 번도 호출되지 않음 — 모든 테스트가 `patch.object(controller, "_move"/"_click")`로 모킹.

### test_input_controller.py
- MOVE 반올림(2.6 → 3), 문자열 숫자("3.2" → 3), 필드 누락 기본값 0, 1px 미만 드롭
- CLICK 기본값 left, right 전달
- 미정의 타입(`SESSION`/`UNKNOWN`/빈 dict) 무시 — 예외 없음

### test_server_udp_session.py
- SessionRegistry: 32자리 hex 발급, 50회 유일성, remove 후 비활성(중복 remove 안전), `None`/`""`/`int`/미등록 토큰 거부
- UDP: 활성 세션 MOVE → `_move(2,-1)` 호출 / 미등록 세션 무시 / session 필드 누락 무시 / 세션 제거 후 무시 / 깨진 JSON·빈 패킷·비UTF8·JSON 배열 무시 / UDP로 온 CLICK 무시 / dx·dy 누락 시 SendInput 생략 / `_move` 예외 비전파
- UDP 루프: 실제 loopback UDP 소켓에 패킷 전송 → `udp_listener`가 처리하는지 확인 (`stop_event`로 안전 종료)
- TCP: SESSION 줄이 첫 전송이며 `\n`으로 끝남 / 연결 유지 중 세션 활성 / 정상 종료 시 제거 / `ConnectionResetError` 발생 시에도 제거+close / 청크 분할된 CLICK 2개 파싱(기존 buffer 로직 회귀) / 깨진 줄 무시 후 정상 줄 처리 / 연결마다 다른 토큰
- 포트 상수 검증: `TCP_PORT == 9000`, `UDP_PORT == 9001`

## android-dev / protocol-qa 확인 요청 사항

1. **세션 줄 파싱**: 서버는 `{"type":"SESSION","session":"..."}` 로 `type` 필드를 **포함**해 보낸다 (AGENTS.md 섹션 7 다이어그램에는 `{"session":"abc123"}`로 축약돼 있으나, request.md 확정 스펙의 `type` 포함 형태를 따랐다). Android `TcpClient`는 `type == "SESSION"`을 확인하고 `session`을 꺼내면 된다.
2. **토큰 길이**: 항상 32자리 hex 문자열. UDP 패킷 `session` 필드는 문자열이어야 하며, 다른 타입이면 무시된다.
3. **UDP 패킷에 개행을 붙여도 무해**하다 (`json.loads`가 트림) 하지만 스펙대로 붙이지 않는 것을 권장.
4. **재연결 시 토큰 무효화**: TCP가 끊기면 이전 토큰으로 온 UDP MOVE는 전부 조용히 드롭된다. Android는 `disconnect()`에서 반드시 토큰을 초기화하고 재연결 후 새 토큰을 사용해야 커서가 멈추지 않는다.

## 남은 이슈 / 후속 작업

- **AGENTS.md 갱신 필요** (섹션 4 "현재 구현 (Phase 1 — TCP 단일)" → 하이브리드, 섹션 7의 `{"session":"abc123"}` → `{"type":"SESSION","session":"<32 hex>"}`, 섹션 8 실행 방법에 UDP 9001 명시, 섹션 6 Phase 2 체크박스). 리더/protocol-qa 판단에 맡김 — 본 작업에서는 코드만 변경.
- **반올림 규칙**: `int(round(2.5)) == 2` (Python banker's rounding). 기존 Phase 1 동작이라 유지했으나, Android의 `MOVE_SENSITIVITY=1.5f` 배율과 맞물려 0.5 단위 델타가 잦으면 미세한 하향 편향이 생길 수 있다. 개선 시 서버에서 누적 잔차(sub-pixel accumulator)를 두는 편이 안전 — 별도 이슈 권장.
- **UDP 인증 강도**: 토큰은 평문이고 소스 IP 검증을 하지 않는다 (동일 WiFi 내 스푸핑 가능). Phase 5 PIN 인증 범위에서 재검토.
- **다중 클라이언트**: 활성 세션이 여러 개일 때 모두 MOVE를 수행할 수 있다(집합 기반). 단일 기기 정책이 확정되면 최신 세션만 허용하도록 제한 필요 — AGENTS.md 섹션 10 "다중 기기 연결" 미결 항목과 연결.
- **heartbeat 타임아웃**: 미구현. 현재는 TCP 소켓이 실제로 끊겨야 세션이 회수된다 (Wi-Fi 이탈 시 좀비 세션 가능). Phase 2 heartbeat 작업에서 idle 타임아웃과 함께 처리 예정.
