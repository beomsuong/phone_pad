---
name: pc-server-dev
description: "Phone Pad Windows Python 서버(pc_server/)에서 새 이벤트 처리 로직 추가, SendInput 기반 마우스/스크롤 제어 구현, TCP/UDP 소켓 및 세션 관리 작업, pytest 테스트 작성 시 반드시 사용. 'handle_event에 이벤트 추가', 'SendInput 확장', 'UDP 소켓 추가', 'heartbeat 구현', '서버 테스트 작성' 요청 시 트리거."
---

# PC Server Dev

Phone Pad의 Windows 서버는 `socket` 표준 라이브러리와 `ctypes.windll.user32.SendInput`만으로 구현되어 있다 (외부 의존성 없음). 이 스킬은 이 저장소 특유의 이벤트 처리 패턴과 테스트 방법을 담는다.

## 이벤트 처리는 반드시 `handle_event`에 집중

`InputController.handle_event(event: dict)`가 모든 이벤트 타입의 유일한 진입점이다. 새 이벤트 타입을 추가할 때 `server.py`나 다른 곳에 분기를 만들지 않는다 — 처리 로직이 흩어지면 `protocol-qa`가 경계면을 검증할 때 놓치기 쉽고, 유지보수도 어려워진다.

## 새 이벤트 타입 추가 체크리스트

1. `android-dev`가 SendMessage로 공유한 JSON 스펙 확인 (필드명, 타입을 그대로 사용 — 임의 변환 금지)
2. `input_controller.py`의 `handle_event`에 `elif t == "...":` 분기 추가
3. 좌표/수치 필드는 `float(event.get(key, 0))` → `int(round(...))`처럼 안전하게 변환 (Android는 Float로 보내지만 `SendInput`은 정수 필드를 요구)
4. 새로운 `SendInput` 동작이 필요하면(예: 휠 스크롤) `MOUSEEVENTF_*` 플래그와 `MOUSEINPUT` 필드를 추가 — 기존 `_move`/`_click` 패턴을 따른다
5. `server.py`의 소켓 처리가 영향받으면(예: UDP 세션 추가) TCP는 그대로 두고 UDP 소켓을 별도로 추가한다 — MOVE만 UDP라는 원칙(AGENTS.md 섹션 4)을 지킨다
6. `pc_server/tests/`에 테스트 추가 (아래 "테스트 작성 패턴" 참조)

## 테스트 작성 패턴

`pc_server/tests/` 디렉토리가 아직 없으면 새로 만든다 (`pc_server/tests/test_input_controller.py` 등).

`SendInput`은 Windows 전용 `ctypes.windll` 호출이므로, 실제 OS 호출 없이 로직만 검증하려면 `InputController._move`/`_click`(또는 신규 메서드)을 `unittest.mock.patch`로 모킹한다:

```python
from unittest.mock import patch
from input_controller import InputController

def test_handle_move_event_calls_move_with_rounded_ints():
    controller = InputController()
    with patch.object(controller, "_move") as mock_move:
        controller.handle_event({"type": "MOVE", "dx": 2.6, "dy": -1.4})
        mock_move.assert_called_once_with(3, -1)

def test_handle_unknown_type_does_not_raise():
    controller = InputController()
    controller.handle_event({"type": "UNKNOWN"})  # 예외 없이 무시되어야 함
```

핵심 검증 대상:
- 필드 누락 시 기본값 처리 (`event.get("dx", 0)`)
- 좌표 반올림/정수 변환이 올바른지
- 정의되지 않은 `type`을 받아도 크래시하지 않는지 (`protocol-qa` 체크리스트 항목)
- 소켓 관련 변경 시 TCP/UDP 라인 파싱이 개행 기준으로 올바르게 분리되는지 (`server.py`의 `buffer` 로직)

## 흔한 실수

- 좌표를 문자열이나 float 그대로 `MOUSEINPUT`에 넣는 것 — `ctypes.c_long`은 정수만 받는다.
- 새 이벤트 처리 로직을 `server.py`에 직접 추가하는 것 — 반드시 `InputController.handle_event`를 거친다.
- 알 수 없는 `type`에 대해 예외를 던지는 것 — 무시하고 넘어가야 클라이언트-서버 프로토콜 버전 차이에 관대해진다.
