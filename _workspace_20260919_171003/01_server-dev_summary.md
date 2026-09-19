# server-dev 요약 — DOUBLE_CLICK (Phase 3)

> **주의:** 이 작업은 원래 android-dev 에이전트로 기동된 세션이 코디네이터 지시에 따라
> 이어받아 수행했습니다. 구현부(`input_controller.py`)는 이미 디스크에 존재했고,
> 이 세션이 추가한 것은 **테스트뿐**입니다.

## 변경 파일

| 파일 | 변경 내용 | 작성자 |
|------|----------|--------|
| `pc_server/input_controller.py` | `handle_event`에 `DOUBLE_CLICK` 분기 + `_double_click(button)` 신규 | 이전 세션 (검토만 수행) |
| `pc_server/tests/test_input_controller.py` | DOUBLE_CLICK 테스트 9종 + CLICK 회귀 2종 추가 | 이 세션 |

## 구현부 검토 결과 (스펙 준수 확인)

`_double_click(button)`은 request.md 확정 스펙을 그대로 따르고 있음을 확인했습니다:

- `flags = (down_flag, up_flag, down_flag, up_flag)` 4개를 `(INPUT * 4)` 배열로 만들어
  **`SendInput(4, inputs, ...)` 단 1회**로 전송 — `_click`을 두 번 호출(SendInput 2회)하지 않음
- 버튼 분기는 기존 `_click`과 동일한 규약(`button == "left"`가 아니면 우클릭 플래그)
- `MOUSEINPUT(dwFlags=flag)`만 세팅하므로 `dx`/`dy`/`mouseData`가 모두 0 —
  커서를 움직이는 요소가 없어 Windows 더블클릭 판정 사각형(기본 4px) 문제가 발생하지 않음
- `handle_event`의 분기 위치는 `CLICK` 바로 뒤, `event.get("button", "left")` 기본값도 CLICK과 동일

## 추가한 테스트 (11종)

### DOUBLE_CLICK 디스패치
1. `test_handle_double_click_defaults_to_left` — `button` 필드 없으면 `"left"`로 위임
2. `test_handle_double_click_passes_explicit_button` — 명시된 `button`을 그대로 전달
3. `test_handle_double_click_does_not_reuse_click` — `_click`이 호출되지 않고 `SendInput`이 정확히 1회.
   **이번 스펙의 핵심(원자성)을 고정하는 테스트**

### SendInput 시퀀스
4. `test_double_click_sends_down_up_down_up_in_single_send_input` —
   `[LEFTDOWN, LEFTUP, LEFTDOWN, LEFTUP]` 순서 + SendInput 1회 + `count == len(inputs)` + `size == sizeof(INPUT)`
5. `test_double_click_right_button_uses_right_flags` —
   `[RIGHTDOWN, RIGHTUP, RIGHTDOWN, RIGHTUP]` (이번 범위 외지만 재사용성 확인)
6. `test_double_click_does_not_move_cursor` —
   4개 INPUT 전부 `dx == dy == mouseData == 0`이고 `dwFlags & MOUSEEVENTF_MOVE == 0`.
   "커서가 안 움직여야 더블클릭이 성립한다"는 설계 근거를 직접 검증
7. `test_handle_double_click_end_to_end_flags` — `handle_event` → `_double_click` → `SendInput` 전 구간

### 회귀 (DOUBLE_CLICK 추가가 단일 클릭을 건드리지 않았는지)
8. `test_single_click_still_sends_exactly_two_inputs` — `_click("left")` → `[LEFTDOWN, LEFTUP]` 2개, SendInput 1회
9. `test_single_right_click_still_sends_exactly_two_inputs` — `_click("right")` → `[RIGHTDOWN, RIGHTUP]`

`_click`의 SendInput 레벨 테스트는 기존에 없었습니다(디스패치 테스트만 존재) — 이번에 신규로 추가해
"CLICK은 2개, DOUBLE_CLICK은 4개"라는 구분을 코드로 고정했습니다.

### 기존 테스트 보강 (이전 세션 작업, 그대로 유지)
- `test_handle_unknown_type_does_not_raise`에 `_double_click` 미호출 검증 추가 —
  `SESSION`/`UNKNOWN`/`{}` 가 실수로 더블클릭을 유발하지 않음

## 헬퍼

`_sent_flags(mock_send_input)` 신규 — SendInput 1회 호출을 단언하고 `dwFlags` 목록을 뽑습니다.
기존 `_sent_wheel_events`는 `mouseData`까지 필요한 휠 전용이라 분리했습니다.

## 테스트 실행 결과

```
$ cd pc_server && python -m pytest
platform win32 -- Python 3.13.1, pytest-9.0.3, pluggy-1.6.0
collected 67 items

tests\test_input_controller.py ...........................               [ 40%]
tests\test_server_heartbeat.py ...................                       [ 68%]
tests\test_server_udp_session.py .....................                   [100%]

============================= 67 passed in 0.20s ==============================
```

`test_input_controller.py` 27개(기존 16 + 신규 11), 전체 67개 전부 통과. MOVE/CLICK/SCROLL/HEARTBEAT/UDP 세션 회귀 없음.

## 남은 이슈

1. **실기기(실제 Windows 입력) 미검증** — `SendInput`을 모킹한 단위 테스트라 "실제로 더블클릭으로
   인식되는지"는 확인하지 못했습니다. 특히 4개 INPUT을 시각차 0으로 주입하는 방식이 모든
   애플리케이션에서 더블클릭으로 받아들여지는지는 실기기 확인이 필요합니다.
   (`GetDoubleClickTime` 기본 500ms 안에는 확실히 들어가지만, 일부 앱이 down-up 간
   최소 간격을 요구할 가능성은 배제 못 함)
2. **`AGENTS.md` 갱신 필요** — 섹션 4의 `// 더블클릭 (Phase 2, 미구현)` 주석과 섹션 5 표의
   `1손가락 더블탭 → ⬜ 미구현`, 섹션 6 Phase 3 체크박스를 구현 완료로 바꿔야 합니다.
   이 세션은 소스/테스트만 건드렸습니다.
3. `_move`의 sub-pixel 잔차 누적 이슈(기존 별도 이슈)는 이번 작업과 무관하게 그대로 남아 있습니다.
