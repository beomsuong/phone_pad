# 서버 작업 요약 — 예외 처리 강화 (A. 서버: SendInput 반환값 검사)

담당: server-dev / 범위: `pc_server/input_controller.py` + 테스트만. `server.py`·`single_instance.py`·`logging_setup.py`·`AGENTS.md`·`CLAUDE.md` 무변경, 커밋 없음.

## 변경 파일

| 파일 | 변경 |
|------|------|
| `pc_server/input_controller.py` | `_send_input()` 단일 창구 도입, 반환값 검사, 실패 카운터 + rate limit 로그, 드래그 상태 정합성 수정 |
| `pc_server/tests/send_input_stub.py` | **신규**. `SendInput` 계약(주입 개수 반환)을 흉내내는 patch 헬퍼 |
| `pc_server/tests/test_input_controller.py` | 기존 26개 patch 지점 교체 + 실패 경로 테스트 19개 추가 |
| `pc_server/tests/test_server_drag.py` | patch 지점 12개 교체, 계약 위반 `side_effect` 2건 수정 |
| `pc_server/tests/test_server_shutdown.py` | patch 지점 7개 교체 |

## 구현 내용

1. **단일 창구** `InputController._send_input(count, inputs, kind) -> bool`
   - `ctypes.windll.user32.SendInput(count, inputs, ctypes.sizeof(INPUT))`의 반환값이 `count`와 다르면 실패로 판정.
   - **호출 경로(`ctypes.windll.user32.SendInput`)와 인자 구성은 그대로** — `_move`는 여전히 `byref` 1개, `_click` 2개, `_double_click` 4개, `_scroll` 1~2개, 버튼 플래그 1개. `handle_event`의 외부 동작(어떤 이벤트에 SendInput 몇 번, 어떤 플래그 순서)은 무변경.
   - `_move`/`_scroll`/`_click`/`_double_click`/`_send_button_flag`가 모두 `bool`을 반환하도록 시그니처만 확장(기존 호출부는 반환값을 무시해도 동작 동일). `_send_button_flag(flag, kind)`로 인자 1개 추가 — 내부 전용 메서드.
2. **드래그 상태 정합성(핵심 버그)**
   - `_drag_start()`: 주입 실패 시 `_drag_active`를 True로 만들지 않고 False 반환. (이전에는 눌린 적 없는 버튼을 "눌림"으로 기록해, 이후 DRAG_END/연결 종료 안전장치가 유령 LEFTUP을 쏘거나 헛돌았다.)
   - `_drag_end()` / `force_release_drag()`: 주입 실패 시 `_drag_active`를 **True로 유지**하고 False 반환 → 연결 종료 안전장치가 나중에 재시도 가능. 성공 경로 동작·반환값은 그대로.
   - 락 순서는 항상 `_drag_lock` → `_failure_lock` 단방향(역순 없음).
3. **실패 알림 (로그 폭주 방지, ASCII)**
   - 같은 `kind`는 `INPUT_FAILURE_LOG_INTERVAL_SEC = 5.0`초에 1줄만. 버킷은 종류별(`MOVE`/`SCROLL`/`CLICK`/`DOUBLE_CLICK`/`DRAG_START`/`DRAG_END`)이라 초당 수십 번 오는 MOVE가 드래그 실패 로그를 묻어버리지 않는다.
   - 시간 소스는 생성자 주입(`InputController(monotonic=..., log=..., failure_log_interval=...)`, 기본값 `time.monotonic`/`print`/5.0) → 테스트에서 실제 sleep 없이 검증. `server.py`가 쓰는 `InputController()` 호출은 무변경.
   - 메시지(ASCII 전용, AGENTS.md 섹션 9): `[!] SendInput MOVE: injected 0 of 1 (last error 0, failures 12) - input desktop blocked? (UAC prompt / lock screen)`
   - 로그 호출 자체를 `try/except (OSError, ValueError)`로 감쌌다 — 리다이렉트된 로그 스트림이 닫혀 있어도 입력 처리는 계속된다.
4. **공개 카운터** `InputController.input_failures` (`_failure_lock`으로 스레드 안전). 트레이/UI 연결은 범위 밖 — 값만 노출.
5. **에러 코드**는 `ctypes.GetLastError()` best-effort(`_last_error()`). `windll` 경로가 `use_last_error=True`가 아니라 중간에 덮어써질 수 있음을 코드 주석에 명시 — **분기 근거로 쓰지 않고 로그 표시용으로만** 쓴다.

### 의도적으로 하지 않은 것 (해석 근거)
- **ctypes 호출이 던지는 예외(OSError 등)는 여전히 밖으로 전파**시킨다. 스펙 3항의 "예외를 밖으로 던지지 않는다"는 **새로 추가한 실패 감지/알림 경로**에 적용했다(카운터·로그는 절대 던지지 않음). 예외를 `_send_input`이 삼키면 `server.py`의 기존 `except` 경로(`[!] Failed to release drag ...`, `test_force_release_failure_does_not_break_disconnect_cleanup`)가 사실상 죽은 코드가 되어 기존 테스트의 검증력이 약해진다. 지금도 `server.py`가 이벤트별로 잡고 있어 세션은 끊기지 않는다.

## 테스트

- 기준선: `227 passed, 1 skipped` → **현재 `246 passed, 1 skipped`** (`cd pc_server && python -m pytest`, 실제 실행 확인).
- 기존 테스트의 mock은 `MagicMock` 기본 반환값이라 새 계약에서 "0개 주입"으로 읽힌다. `tests/send_input_stub.py`의 `patch_send_input()`(성공=요청 개수 반환) / `injected_none` / `injected_partial(n)`으로 **45개 patch 지점 전부** 교체했다. **호출 횟수·플래그 시퀀스 단언은 하나도 약화시키지 않았다** — `_sent_flags`/`sent_flags` 헬퍼와 `call_count` 단언 그대로.
  - 계약 위반이던 2건만 수정: `test_drag_end_wire_line_releases_button_before_disconnect`의 `side_effect`가 `None`을 반환하던 것 → 개수 반환하며 기록, `test_force_release_failure_does_not_break_disconnect_cleanup`의 `[None, OSError]` → `[1, OSError]`.
- 추가 테스트 19개: 각 이벤트 경로의 실패 카운트(예외 없음), 부분 주입(CLICK 2개 중 1개)도 실패로 판정, 카운터 누적/멀티스레드(4스레드×50회 = 200), `_drag_start` 실패 시 비활성 유지 + 이후 DRAG_END가 아무것도 보내지 않음 + 재시도 성공, `_drag_end`/`force_release_drag` 실패 시 활성 유지 후 재시도 성공, rate limit(같은 창 20회 → 로그 1줄, 창 경과 후 다시 1줄, 종류별 버킷 분리), 로그 ASCII 인코딩 및 문구, 로그 예외 무시, 기본 생성자 인자 회귀.
- **변이 검사(mutation check)**: `_send_input`의 반환값 검사를 일부러 무력화(`if True: return True`)하면 **16개 테스트가 실패**(`16 failed, 230 passed`)하고, 되돌리면 246 통과. 새 테스트가 실제로 이 버그를 잡는다는 증거.

## 실제 검증 (이 머신, 요청 7항)

`python`으로 실제 `SendInput` 1회 호출(`MOUSEEVENTF_MOVE`, dx=0, dy=0, 1개):

```
raw SendInput return = 1 int
_move(0,0) -> True  input_failures = 0
cursor before/after: (412, 866) (412, 866)
```

- 반환값은 **정수 `1`** = 요청 개수 → "반환값 = 주입된 이벤트 수" 계약이 이 환경에서 실제로 성립.
- 새 헬퍼 경로(`_move`)도 `True`를 반환하고 실패 카운터는 0.
- dx=dy=0만 썼고 `GetCursorPos`로 전후를 확인해 **커서는 움직이지 않았다**.

## 미해결 이슈 / 한계

1. **UIPI 차단은 감지 불가**(Microsoft 문서). 관리자 권한 창(UAC로 승격된 앱, 작업 관리자 등)에 일반 권한 서버가 입력을 주입하면 반환값도 `GetLastError`도 성공처럼 보인다 → `_send_input`이 True를 반환해도 "실제로 먹혔다"는 보장은 없다. 코드로 해결 불가, 문서 한계로 남김(코드 주석에도 기재).
2. **실패 경로의 실환경 재현 없음.** 반환값 0은 잠금 화면/보안 데스크톱에서만 나오고 이 세션에서 재현할 수 없어(화면을 잠가야 함) 단위 테스트로만 커버했다. 성공 경로만 실제 `SendInput`으로 확인.
3. `GetLastError` 값은 신뢰 불가(best-effort). 로그에 `last error 0`이 찍혀도 "에러 없음"을 뜻하지 않는다.
4. `input_failures`는 **누적 전용**(리셋 API 없음). 트레이 표시를 붙일 때 "최근 N초 실패" 같은 표현이 필요하면 그때 설계.
5. 입력이 오래 막혀 있어도 서버는 **아무 복구 동작을 하지 않는다**(로그+카운터만). 드래그가 끊긴 채 남을 수 있는 시나리오(DRAG_START 실패 후 사용자가 손가락을 유지)는 Android 쪽 상태와 어긋날 수 있으나, 서버가 버튼을 안 눌렀으므로 "버튼이 눌린 채 멈춤"보다 안전한 실패 방향이다.
6. 타입 힌트 `_send_input(self, count: int, inputs, kind: str)`의 `inputs`는 `byref` 결과와 배열을 모두 받아 힌트를 생략했다.

## 리더가 AGENTS.md에 반영할 내용 (에이전트는 수정하지 않음)

**섹션 10(미결 사항) 표에 추가 권장:**

| 항목 | 현황 |
|------|------|
| `SendInput` 주입 실패 감지 | 모든 호출이 `InputController._send_input()` 한 곳을 지나며 반환값(주입된 이벤트 수)을 검사한다. 요청보다 적게 주입되면 `input_failures` 카운터를 올리고 종류별 5초 rate limit으로 ASCII 로그 1줄을 남긴다(예외는 던지지 않음). `_drag_start`는 실패 시 `_drag_active`를 올리지 않고, `_drag_end`/`force_release_drag`는 실패 시 True로 남겨 나중에 재시도한다. 반환값 계약은 이 머신에서 실측(`MOUSEEVENTF_MOVE` dx=dy=0 1개 → 반환 1). **남은 한계:** ① UIPI(관리자 권한 창에 일반 권한 프로세스가 주입)로 차단되면 Microsoft 문서상 반환값도 `GetLastError`도 실패를 알리지 않아 **감지 불가** — 서버를 관리자로 띄우는 것 외에 코드로 해결할 방법이 없다. ② 실패 경로(잠금 화면/보안 데스크톱)는 단위 테스트로만 커버, 실환경 미재현. ③ 로그의 `last error` 값은 `windll`이 `use_last_error`가 아니라 best-effort |
| 입력 실패의 사용자 노출 | `InputController.input_failures`로 누적 실패 수를 읽을 수 있지만 트레이/UI에 아직 표시하지 않는다(이번 범위 밖). 트레이에 붙일 때는 누적값 대신 "최근 실패" 표현이 필요할 수 있음 |

**섹션 9(코딩 컨벤션)에 한 줄 추가 권장:**
- `pc_server`에서 `SendInput`을 직접 호출하지 않는다 — 반드시 `InputController._send_input(count, inputs, kind)`를 경유한다(반환값 검사·실패 카운터·rate limit 로그가 이 한 곳에 있다). 테스트에서 `SendInput`을 모킹할 때는 `tests/send_input_stub.py`의 `patch_send_input()`을 쓴다(`MagicMock` 기본 반환값은 "0개 주입"으로 읽혀 실패로 판정된다).
