# 요청: 탭홀드 + 드래그 → DRAG_START / DRAG_END (AGENTS.md Phase 3)

**범위 판단:** 교차 경계면 (새 이벤트 타입 2개 추가 + 서버가 마우스 버튼을 "누른 채로 유지"하는 상태를 새로 가짐)
**실행 경로:** 리더가 스펙 사전 확정 → android-dev/server-dev 병렬 호출 → protocol-qa 사후 검증

## 이 제스처가 뭔지

1손가락으로 화면을 누른 채 `DRAG_HOLD_THRESHOLD_MS`(탭 최대 지속시간과 동일, 200ms) 이상 **움직이지 않고 버티면** "드래그 홀드"로 승격된다 — PC에서 마우스 왼쪽 버튼을 누른 채로 유지하는 것과 같다. 이후 손가락을 움직이면 버튼이 눌린 채로 커서가 움직여 텍스트 선택/아이콘 드래그 등이 된다. 손가락을 떼면 버튼을 놓는다.

이건 기존 "1손가락 드래그"(그냥 커서만 움직이는 것, Phase 1부터 있음)와 다르다 — 기존 드래그는 버튼을 전혀 누르지 않는다. 이번 기능은 **버티는 시간**이 핵심 트리거다: 빨리 움직이면 여전히 기존처럼 버튼 없는 커서 이동이고, 제자리에서 200ms 이상 버티면 그 순간 버튼이 눌린다.

## 확정 스펙

### 와이어 포맷 (TCP 9000, 필드 없음)
```jsonc
{"type":"DRAG_START"}
{"type":"DRAG_END"}
```
- 둘 다 필드가 전혀 없다. session도 없다 (CLICK/SCROLL과 동일한 TCP 평문 이벤트)
- **드래그 중 이동은 새 이벤트가 아니라 기존 MOVE(UDP)를 그대로 쓴다** — 서버가 버튼을 누른 채로 유지하는 동안 MOVE가 오면 커서만 움직이므로, 실제 드래그 효과는 "버튼 누름 + 커서 이동 + 버튼 뗌"의 조합만으로 자연히 발생한다. `_move` 자체는 전혀 손댈 필요 없다

### 판정 로직 (Android)
1. 1손가락 구간이 시작된 뒤, **제자리(탭 최대 이동 거리 이내)를 유지한 채** `DRAG_HOLD_THRESHOLD_MS`가 지나면(아직 손가락을 떼지 않았다면) 그 순간 드래그 홀드로 승격 → `DRAG_START` 전송
2. `DRAG_HOLD_THRESHOLD_MS`는 `TAP_MAX_DURATION_MS`와 **정확히 같은 값**이어야 한다 — "탭으로 인정되지 않게 되는 바로 그 시점"이 "드래그 홀드 후보가 되는 시점"과 일치해야 사각지대가 없다. 별도 상수로 만들되 `TAP_MAX_DURATION_MS`를 참조할 것(`const val DRAG_HOLD_THRESHOLD_MS = TAP_MAX_DURATION_MS`)
3. 승격 전에 손가락이 `TAP_MAX_DISTANCE_PX`를 넘게 움직이면(즉 시간이 되기 전에 이미 드래그가 시작되면) 승격하지 않는다 — 기존처럼 버튼 없는 일반 커서 이동(MOVE)으로 처리된다 (기존 동작, 회귀 금지)
4. 승격된 뒤에는 이동을 계속 기존 MOVE 경로로 방출한다 (판정/감도 로직 변경 없음)
5. 드래그 홀드 상태에서 손가락 개수가 바뀌면(예: 두 번째 손가락이 닿음) 즉시 `DRAG_END`를 보내고 버튼을 놓는다 — 2손가락 드래그 홀드는 이번 범위가 아니다
6. 드래그 홀드 상태에서 손가락이 화면 밖으로 나가거나 제스처가 취소되는 경우에도 `DRAG_END`를 보낸다 (AGENTS.md 섹션 5의 기존 엣지 케이스 문구가 이걸 가리킨다)
7. 드래그 홀드로 끝난 제스처는 탭/더블탭/클릭 판정을 아예 하지 않는다 (`DRAG_END`만 보내고 끝 — `CLICK`/`DOUBLE_CLICK`과 무관)
8. 드래그 홀드가 승격됐지만 그 뒤로 전혀 움직이지 않고 바로 손을 뗀 경우 → `DRAG_START` 직후 `DRAG_END`만 나간다 (사이에 MOVE 없음). 이는 서버 관점에서 일반 좌클릭의 down/up과 동일한 결과이므로 문제 없다

### 상수 (Android `GestureConfig.kt`)
- `DRAG_HOLD_THRESHOLD_MS: Long = TAP_MAX_DURATION_MS` — 위 2번 참고

### 서버 안전장치 (중요)
`DRAG_END`가 유실되면(네트워크 문제, 앱 강제 종료 등) PC의 마우스 왼쪽 버튼이 **영원히 눌린 채로 멈추는** 심각한 상태가 된다. 이를 막기 위해 서버는:
- `InputController`가 드래그 활성 여부(`_drag_active`)를 인스턴스 상태로 가진다
- `DRAG_START`/`DRAG_END`는 각각 **멱등**이어야 한다 — 이미 활성 상태에서 또 `DRAG_START`가 오면 무시(중복 LEFTDOWN 방지), 비활성 상태에서 `DRAG_END`가 오면 무시
- **TCP 연결이 어떤 이유로든 끊길 때(정상 종료, heartbeat 타임아웃, 예외 등 `handle_client`의 `finally` 블록 전부)** 드래그가 활성 상태였다면 서버가 강제로 버튼을 놓는다(LEFTUP 전송). 이건 세션/연결과 무관하게 `InputController`가 전역으로 가지는 상태이므로(현재 구조상 `InputController` 인스턴스 자체가 프로세스 전체에서 하나) `handle_client`의 `finally`에서 안전하게 호출 가능해야 한다

## Android 구현 대상
- `presentation/util/GestureConfig.kt`: `DRAG_HOLD_THRESHOLD_MS` 추가
- `presentation/trackpad/TrackpadScreen.kt`: 1손가락 구간에서 "제자리 유지 시간"을 재는 타이머 경합 로직 추가 (예: `awaitPointerEvent()`를 남은 시간만큼의 타임아웃과 경합시켜, 타임아웃이 먼저 나면 승격). **가능하면 승격 판정 자체(경과 시간 + 누적 이동 거리로 승격 여부를 결정하는 순수 로직)는 별도의 작은 순수 함수/클래스로 분리해서 단위 테스트가 가능하게 할 것** — 코루틴 타이밍 자체는 Compose에 묶여 테스트하기 어렵더라도, "승격 조건" 판정 로직만이라도 순수 함수로 빼면 회귀를 잡을 수 있다 (지난 라운드 QA가 `TrackpadScreen`에 자동 테스트가 없다고 지적한 것과 같은 문제를 이번엔 최대한 피해가는 방향)
- `domain/model/TrackpadEvent.kt`: `object DragStart : TrackpadEvent()`, `object DragEnd : TrackpadEvent()` 추가 (필드 없음)
- `data/repository/TrackpadRepositoryImpl.kt`: TCP로 `{"type":"DRAG_START"}` / `{"type":"DRAG_END"}` 직렬화. CLICK과 같은 등급(저빈도, 명시적 상태 전이)이라 전송 실패 시 `Error`로 알림
- `TrackpadViewModel.kt`: `sendDragStart()`, `sendDragEnd()` 추가
- `TrackpadSurface`: 콜백 배선

기존 1손가락 MOVE/CLICK/더블탭, 2손가락 우클릭/스크롤 로직은 절대 회귀시키지 마세요.

## Android 테스트
- 승격 판정 순수 로직(있다면): 제자리 유지 + 시간 경과 → 승격 / 시간 전에 이동 초과 → 승격 안 됨 등 경계 조건
- 최소한 기존 테스트 스위트 전부 회귀 없이 통과

## Server 구현 대상
`pc_server/input_controller.py`:
- `InputController.__init__`에 `self._drag_active = False` 추가 (현재 `__init__`이 없으므로 신규 작성)
- `handle_event`에 `DRAG_START`/`DRAG_END` 분기 추가
- `_drag_start()`: `_drag_active`가 False일 때만 LEFTDOWN 1개짜리 INPUT을 `SendInput`으로 보내고 `_drag_active = True`. 이미 True면 아무것도 안 함
- `_drag_end()`: `_drag_active`가 True일 때만 LEFTUP 1개짜리 INPUT을 `SendInput`으로 보내고 `_drag_active = False`. 이미 False면 아무것도 안 함
- 외부에서 호출 가능한 강제 해제 메서드도 필요(이름은 자유, 예: `force_release_drag()`) — 내부적으로 `_drag_end()`와 동일하게 동작하면 됨 (Python은 private 강제가 없으니 `_drag_end()`를 직접 호출해도 무방, 어느 쪽이든 서버 안전장치 요구사항을 만족하면 됨)

`pc_server/server.py`:
- `handle_client`의 `finally` 블록(기존 세션 회수/소켓 종료 로직 바로 옆)에서, 드래그가 활성 상태였다면 강제로 놓는 호출을 추가

## Server 테스트
- `DRAG_START` → LEFTDOWN 1개짜리 `SendInput` 호출, `_drag_active` True
- 연속 `DRAG_START` 두 번 → `SendInput`이 첫 번째만 호출(멱등)
- `DRAG_END` → LEFTUP 1개짜리 `SendInput` 호출, `_drag_active` False
- `DRAG_START` 없이 `DRAG_END`만 오면 → 아무 호출도 안 함, 크래시 없음
- **연결이 끊길 때 드래그가 활성 상태였다면 서버가 LEFTUP을 강제로 보내는지** (`handle_client`를 FakeConn으로 구동해 확인 — heartbeat 타임아웃 경로와 정상 종료 경로 둘 다)
- 기존 MOVE/CLICK/DOUBLE_CLICK/SCROLL/HEARTBEAT 회귀 없는지

## 참고 문서
- `AGENTS.md` 섹션 4(통신 프로토콜)/5(제스처 설계, 엣지 케이스에 이미 "화면 밖으로 나간 손가락 → DRAG_END" 문구가 있음)/6(로드맵) — 구현 후 갱신 필요
