# 요청: 2손가락 탭 → 우클릭 (AGENTS.md Phase 2)

**범위 판단:** 단일 사이드 (Android만). 새 이벤트 타입이나 프로토콜 필드를 추가하지 않는다 — **기존 CLICK 이벤트를 `button:"right"`로 재사용**한다.

**서버는 이미 지원한다 (확인 완료, 변경 불필요):** `pc_server/input_controller.py`의 `_click(button)`이
```python
down_flag = MOUSEEVENTF_LEFTDOWN if button == "left" else MOUSEEVENTF_RIGHTDOWN
up_flag = MOUSEEVENTF_LEFTUP if button == "left" else MOUSEEVENTF_RIGHTUP
```
로 `"left"`가 아닌 모든 button 값을 우클릭으로 처리한다(Phase 1부터 있던 코드). 즉 Android가 `{"type":"CLICK","button":"right"}`을 TCP로 보내기만 하면 서버는 이미 우클릭을 수행한다. **서버는 건드리지 않는다.**

**실행 경로:** Phase 2A(서브 에이전트 1명, android-dev). protocol-qa/server-dev 불필요.

## 확정 스펙

1. 지난 작업(`MultiTouchGestureTracker`)이 이미 `GestureEndDecision.pointerCount`로 마지막 구간의 손가락 개수를 반환한다. `onGestureEnd()`에 **2손가락 탭 판정**을 추가한다: `pointerCount == 2 && !isDrag && elapsed < TAP_MAX_DURATION_MS` → 우클릭으로 판정
2. `GestureEndDecision`을 확장해 "탭이 일어났다면 어느 버튼인지"를 표현한다 (예: `click: Boolean` 대신 `clickButton: String?` — `"left"`/`"right"`/`null`). 기존 1손가락 탭 판정 로직·임계값은 그대로 유지
3. `TrackpadScreen.kt`의 어댑터가 이 결과에 따라 `onClick()`(좌클릭, 기존) 또는 새 `onRightClick()` 콜백을 호출하도록 분기
4. `TrackpadViewModel.kt`에 우클릭 전송 메서드 추가 (예: `sendRightClick()` → `sendEventUseCase(TrackpadEvent.Click(button = "right"))`) — `TrackpadEvent.Click`은 이미 `button: String = "left"` 파라미터가 있으므로 도메인 모델 변경 불필요
5. `TrackpadSurface`(Composable)가 `onRightClick: () -> Unit` 파라미터를 받아 `TrackpadScreen`에서 `viewModel::sendRightClick`으로 연결

## 구현 대상

- `presentation/trackpad/MultiTouchGestureTracker.kt`
- `presentation/trackpad/TrackpadScreen.kt`
- `presentation/trackpad/TrackpadViewModel.kt`
- (도메인 모델 `TrackpadEvent.kt`, 데이터 계층 `TrackpadRepositoryImpl.kt`, 서버는 변경 불필요 — 손대지 말 것)

작업 착수 전 `android-trackpad-dev` 스킬을 Skill 도구로 호출하고, AGENTS.md 섹션 4(통신 프로토콜의 CLICK 우클릭 예시)와 섹션 5(제스처 설계 표의 "2손가락 탭" 행)를 읽을 것.

## 테스트 (JUnit)

- `MultiTouchGestureTrackerTest`: 2손가락 탭 → `clickButton == "right"` 반환, 2손가락 드래그(이동 많음) → 우클릭 아님, 1손가락 탭은 기존처럼 `"left"` 그대로 유지(회귀)
- `TrackpadViewModelTest`(신규 또는 기존 확장): `sendRightClick()` 호출 시 `TrackpadEvent.Click(button="right")`가 UseCase로 전달되는지
- 기존 관련 테스트 전부 회귀 없이 통과할 것

## 참고 문서
- `AGENTS.md` 섹션 4/5
- `_workspace_20260918_210759/01_android-dev_summary.md` (직전 멀티터치 기반 작업 요약)
