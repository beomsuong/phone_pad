# server-dev 작업 요약 — DRAG_START / DRAG_END (Phase 3)

## 변경 파일

| 파일 | 변경 |
|------|------|
| `pc_server/input_controller.py` | `__init__` 신규, `handle_event`에 `DRAG_START`/`DRAG_END` 분기, `_send_button_flag`/`_drag_start`/`_drag_end`/`force_release_drag` 추가, `drag_active` 프로퍼티, `threading` import |
| `pc_server/server.py` | `handle_client`의 `finally` 블록에 강제 해제 안전장치 추가 |
| `pc_server/tests/test_input_controller.py` | DRAG 단위 테스트 14개 추가, `test_handle_unknown_type_does_not_raise`에 drag 모킹 추가 |
| `pc_server/tests/test_server_drag.py` | **신규** — 서버 경로 + 연결 종료 안전장치 테스트 14개 |

기존 `_move`/`_click`/`_double_click`/`_scroll`, `SessionRegistry`, UDP 경로, heartbeat 로직은 전혀 건드리지 않았다.

## 처리하는 이벤트 (TCP 9000, 필드 없음)

```jsonc
{"type":"DRAG_START"}   // LEFTDOWN 1개짜리 INPUT, SendInput 1회. UP 없음
{"type":"DRAG_END"}     // LEFTUP 1개짜리 INPUT, SendInput 1회
```
- 두 이벤트 모두 필드를 읽지 않는다(있어도 무시). `session` 없음.
- 드래그 중 이동은 기존 MOVE(UDP)가 그대로 담당 — `_move` 무변경.

## 구현한 로직

**`InputController`**
- `__init__`: `self._drag_active = False` + `self._drag_lock = threading.Lock()`
  (InputController는 프로세스 전역 1개이고 TCP 클라이언트 스레드가 여러 개일 수 있어
  "검사 후 변경"을 원자화. 스펙 요구사항은 아니지만 중복 LEFTDOWN 경합을 막는다.)
- `drag_active` 프로퍼티(읽기 전용) — 외부 관측용.
- `_drag_start()`: 비활성일 때만 `MOUSEEVENTF_LEFTDOWN` 1개 전송 후 활성화. 이미 활성이면 no-op(멱등). 전송 실패 시 상태를 바꾸지 않는다(버튼이 안 눌렸으므로).
- `_drag_end()`: 활성일 때만 `MOUSEEVENTF_LEFTUP` 1개 전송 후 비활성화. 이미 비활성이면 no-op(멱등). **전송 실패 시 `_drag_active`를 True로 남긴다** — 이후 연결 종료 안전장치가 다시 시도할 수 있게.
- `force_release_drag() -> bool`: `_drag_end()`와 동일 동작. 실제로 놓았으면 `True`.
- `_send_button_flag(flag)`: down/up 단독 INPUT 1개를 보내는 공용 헬퍼(커서 이동/mouseData 없음).

**`server.handle_client` finally**
```python
registry.remove(session)
print(...)
try:
    if controller.force_release_drag():
        print(f"[!] Drag was active on disconnect — left button released ({addr})")
except Exception as e:
    print(f"[!] Failed to release drag on disconnect: {e}")
conn.close()
```
- 정상 종료(EOF) / heartbeat 타임아웃 / 예외 전부 이 블록을 지난다.
- 강제 해제 중 예외가 나도 세션 회수·소켓 종료를 막지 않도록 try/except로 감쌌다.
- 핸드셰이크 실패 early-return 경로(SESSION 전송 실패)는 드래그가 활성일 수 없어 손대지 않았다.

## 테스트 (총 95개 전부 통과)

`cd pc_server && python -m pytest` → **95 passed in 0.29s** (실행 확인 완료)

**`tests/test_input_controller.py` 추가분 14개**
- 신규 컨트롤러는 `_drag_active is False`
- `DRAG_START` → `[LEFTDOWN]` 1개, `_drag_active True`
- `DRAG_START`가 커서를 건드리지 않음(dx/dy/mouseData 0, MOVE 플래그 없음)
- `DRAG_START` 3연속 → SendInput 1회만(멱등)
- `DRAG_END` → `[LEFTUP]` 1개, `_drag_active False`
- `DRAG_START` 없이 `DRAG_END` → SendInput 호출 없음, 크래시 없음
- `DRAG_END` 2연속 → 1회만
- down→up→down→up 사이클 반복 가능
- `DRAG_START`에 여분 필드가 있어도 무시하고 정상 동작
- 드래그 중 MOVE는 `_move(3,-1)`만 하고 드래그 상태 불변
- `force_release_drag()` 활성 시 LEFTUP + True / 비활성 시 no-op + False
- `DRAG_END` 정상 수신 후 `force_release_drag()`는 아무것도 안 보냄
- LEFTUP 전송 실패 시 활성 상태 유지 → 재시도 성공
- (수정) 미지의 type에 대해 `_drag_start`/`_drag_end`도 호출되지 않음

**`tests/test_server_drag.py` 신규 14개** (FakeConn으로 `handle_client` 구동, 실제 소켓/SendInput 없음)
- `{"type":"DRAG_START"}` 줄 → `_drag_start()` 호출
- `DRAG_END` 줄이 도착한 시점(EOF 읽기 전)에 이미 버튼이 놓임
- 한 연결 안 DRAG_START→DRAG_END → `[LEFTDOWN, LEFTUP]`
- 줄이 청크 경계로 쪼개져 와도 파싱됨
- 드래그 이벤트는 하향 트래픽을 만들지 않음(SESSION만)
- **정상 종료(EOF) 시 드래그 활성 → 강제 LEFTUP**
- **heartbeat 타임아웃(3연속) 시 드래그 활성 → 강제 LEFTUP** + 세션 회수·소켓 종료 유지
- **recv 예외(ConnectionResetError) 경로에서도 강제 LEFTUP**
- `DRAG_END`를 정상 수신했으면 finally에서 중복 LEFTUP 없음
- 드래그를 안 쓴 연결은 종료 시 SendInput 호출 0
- 강제 해제가 실패해도 세션 회수·소켓 종료는 진행
- 재연결 시 이미 해제된 상태면 중복 LEFTUP 없음
- UDP로 온 `DRAG_START`는 무시(MOVE 전용 채널 원칙)
- 같은 연결에서 HEARTBEAT/SCROLL/DOUBLE_CLICK 회귀 없음

기존 회귀 스위트(MOVE/CLICK/DOUBLE_CLICK/SCROLL/HEARTBEAT/UDP 세션) 81개 전부 통과.

## 남은 이슈 / 참고

1. **다중 클라이언트 시 교차 해제**: `InputController`가 프로세스 전역 1개라 A 연결이 드래그 중인데 B 연결이 끊기면 B의 finally가 A의 드래그를 놓는다. 현재 구조(1대1 사용 전제, AGENTS.md 섹션 10 "다중 기기 연결" 미결)에서는 문제가 아니며, 버튼이 눌린 채 멈추는 쪽보다 안전한 실패 방향이라 그대로 뒀다. 다중 기기를 지원하게 되면 드래그 상태를 세션별로 분리해야 한다.
2. **프로세스 강제 종료**: 서버 프로세스 자체가 kill되면 finally가 돌지 않아 버튼이 눌린 채 남는다. `atexit`/시그널 핸들러는 이번 범위 밖(Phase 4 트레이 아이콘 작업에서 함께 다루면 좋다).
3. **AGENTS.md 갱신 필요**: 섹션 4의 `// 드래그 (Phase 3, 미구현)` 주석과 섹션 5/6 로드맵을 구현 완료로 바꿔야 한다(리더/문서 담당 몫으로 남김).
4. Android 쪽 와이어 리터럴 고정 테스트(SCROLL처럼 `test_handle_client_processes_android_scroll_wire_literal` 형태)를 DRAG에도 추가하려면 android-dev의 직렬화 테스트 리터럴 확정이 필요 — 현재는 스펙 문자열(`{"type":"DRAG_START"}`)을 그대로 하드코딩해 두었다.
