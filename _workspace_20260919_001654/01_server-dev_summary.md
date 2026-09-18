# server-dev 작업 요약 — SCROLL (2손가락 드래그 → 스크롤)

## 변경 파일
- `pc_server/input_controller.py` — 휠 상수 추가, `handle_event`에 `SCROLL` 분기 추가, `_scroll` 신규 구현
- `pc_server/tests/test_input_controller.py` — SCROLL 테스트 11개 추가, 기존 unknown-type 테스트에 `_scroll` 미호출 검증 추가

건드리지 않은 것: `server.py`(TCP/UDP 소켓, `SessionRegistry`, heartbeat), 기존 `_move`/`_click` 로직.

## 처리하는 이벤트와 기대 필드
```jsonc
{"type":"SCROLL","dx":0,"dy":-3}   // TCP 9000, newline-delimited JSON, session 필드 없음
```
- `dx`/`dy`: 정수 스크롤 스텝(휠 노치 개수). 픽셀 아님. 누락 시 0.
- 채널: TCP (AGENTS.md 섹션 4 원칙 — UDP는 MOVE 전용). `server.py`는 이미 TCP 라인을 `handle_event`로 넘기므로 소켓 쪽 변경 불필요.

## 실제 구현한 변환 로직

### `handle_event` 분기
```python
elif t == "SCROLL":
    dx = int(round(float(event.get("dx", 0))))
    dy = int(round(float(event.get("dy", 0))))
    if dx != 0 or dy != 0:
        self._scroll(dx, dy)
```
스펙상 정수지만 MOVE와 동일하게 `float() → int(round())`로 방어 변환 (JSON이 `-2.0`이나 `"1"`로 와도 크래시하지 않음). 둘 다 0이면 `_scroll` 자체를 호출하지 않음.

### `_scroll(dx_steps, dy_steps)`
- 새 상수: `MOUSEEVENTF_WHEEL = 0x0800`, `MOUSEEVENTF_HWHEEL = 0x1000`, `WHEEL_DELTA = 120`
- 수직: `dy_steps != 0`일 때만 `(MOUSEEVENTF_WHEEL, dy_steps * 120)` INPUT 생성
- 수평: `dx_steps != 0`일 때만 `(MOUSEEVENTF_HWHEEL, dx_steps * 120)` INPUT 생성
- 두 축 모두 0이면 즉시 return — `SendInput` 미호출
- 두 축 모두 0이 아니면 **INPUT 배열 1개로 묶어 `SendInput(2, ...)` 한 번 호출** (기존 `_click`이 down/up 2개를 한 번에 보내는 패턴과 동일). 축별로 두 번 호출하지 않음 — 한 제스처 프레임의 수직/수평 델타가 원자적으로 주입된다.
- `mouseData`는 기존 `MOUSEINPUT` 구조체를 그대로 재사용하며 `c_ulong`(DWORD)이므로, 음수 delta는 `delta & 0xFFFFFFFF`로 2의 보수 인코딩해서 넣는다 (휠에서는 signed로 해석됨). 구조체 정의는 변경하지 않아 `_move`/`_click`에 영향 없음.

### 부호 규약 (뒤집기 지점)
Windows 표준 그대로, 추가 반전 없음:
- `dy_steps` 양수 → WHEEL 양수 delta = 휠 앞으로 굴림
- `dx_steps` 양수 → HWHEEL 양수 delta = 오른쪽

**변환은 `_scroll` 내부의 `deltas.append(...)` 두 줄에만 존재**한다. 실기기에서 방향이 반대로 느껴지면 그 두 줄에서 `dy_steps`/`dx_steps` 앞에 `-`만 붙이면 되고 `handle_event`나 Android 스펙은 손댈 필요 없다.

## 테스트 (`pc_server/tests/test_input_controller.py`)
SCROLL 관련 11개:

`handle_event` 레벨 (`_scroll` 모킹):
1. `test_handle_scroll_passes_integer_steps` — `{"dx":0,"dy":-3}` → `_scroll(0, -3)`
2. `test_handle_scroll_horizontal` — `{"dx":2,"dy":0}` → `_scroll(2, 0)`
3. `test_handle_scroll_accepts_float_and_string_numbers` — `{"dx":"1","dy":-2.0}` → `_scroll(1, -2)`
4. `test_handle_scroll_zero_does_not_call_scroll` — 둘 다 0 → 미호출
5. `test_handle_scroll_missing_fields_defaults_to_zero` — 필드 누락 → 미호출

`_scroll` 레벨 (`input_controller.ctypes.windll.user32.SendInput` 모킹, 실제 OS 호출 없음):

6. `test_scroll_vertical_sends_wheel_with_step_times_wheel_delta` — INPUT 1개, flag=WHEEL, mouseData=-360
7. `test_scroll_vertical_positive_step` — mouseData=+120
8. `test_scroll_horizontal_sends_hwheel_only` — INPUT 1개, flag=HWHEEL, mouseData=240 (WHEEL INPUT 없음)
9. `test_scroll_both_axes_sends_two_inputs` — INPUT 2개 [WHEEL -240, HWHEEL +120], `SendInput` 호출은 1회
10. `test_scroll_zero_does_not_call_send_input` — `SendInput` 미호출
11. `test_handle_scroll_end_to_end_mouse_data` — `handle_event` → `SendInput`까지 mouseData=-360

헬퍼 `_signed32()`로 `c_ulong`에 실린 mouseData를 다시 signed로 해석해 비교하고, `SendInput` 인자의 `count`/`sizeof(INPUT)`도 함께 검증한다.

회귀: 기존 MOVE/CLICK/unknown-type 테스트 전부 유지. `test_handle_unknown_type_does_not_raise`에 `_scroll` 미호출 검증을 추가해, 정의되지 않은 타입이 스크롤을 유발하지 않음을 보장.

## 테스트 실행 결과
```
$ cd pc_server && python -m pytest
platform win32 -- Python 3.13.1, pytest-9.0.3
56 passed in 0.20s
```
(SCROLL만: `-k scroll` → 11 passed)

## 남은 이슈 / 인수인계
- **부호 방향 실기기 미검증.** 사용자 기대(자연 스크롤 vs 전통 스크롤)와 다를 수 있음. 수정 지점은 `_scroll` 한 곳으로 격리해 두었다.
- **잔차 누적은 Android 책임.** 서버는 정수 스텝만 받으므로 sub-pixel 누적 로직을 두지 않았다. Android가 잔차를 잘라 보내면 서버에서 복구 불가.
- `handle_event`의 `float()` 변환은 숫자로 파싱 불가능한 값(`"abc"`, `null`)에서 `ValueError`/`TypeError`를 던진다 — MOVE와 동일한 기존 동작이며 이번 범위에서 바꾸지 않았다. 프로토콜 관용성을 높이려면 MOVE/SCROLL 공통으로 별도 처리 필요(별개 이슈).
- `AGENTS.md` 섹션 4의 "스크롤 (Phase 2, 미구현)" 주석과 섹션 6 로드맵 갱신은 리더/문서 담당 몫으로 남겨둠 (서버 코드만 변경).
