# 요청: 2손가락 드래그 → 스크롤 (AGENTS.md Phase 2, 마지막 항목)

**범위 판단:** 교차 경계면 (새 이벤트 타입 SCROLL 추가, TrackpadEvent sealed class 확장 + 서버 구현 필요)
**실행 경로:** 리더가 스펙 사전 확정 → android-dev/server-dev 병렬 호출 → protocol-qa 사후 검증

## 확정 스펙

### 와이어 포맷 (TCP 9000, 기존 newline-delimited JSON)
```jsonc
{"type":"SCROLL","dx":0,"dy":-3}
```
- `dx`/`dy`는 **정수** 스크롤 스텝(휠 "노치" 개수) — 픽셀 값이 아니다
- `dy` 양수 = 손가락이 아래로 이동 = 콘텐츠를 아래로 스크롤. `dx` 양수 = 손가락이 오른쪽으로 이동
- session 필드 없음 (CLICK과 동일하게 TCP 평문 이벤트)
- MOVE가 아니므로 반드시 TCP로 보낸다 (AGENTS.md 섹션 4 원칙 — "이동 좌표인가?" 기준으로 UDP는 MOVE 전용)

### 도메인 모델
`TrackpadEvent.kt`에 `data class Scroll(val dx: Int, val dy: Int) : TrackpadEvent()` 추가. HEARTBEAT와 달리 SCROLL은 실제 제스처를 표현하는 이벤트이므로 AGENTS.md 섹션 9의 "sealed class 확장" 기본 규칙을 그대로 따른다 (예외 대상 아님).

### Android 쪽 판정 로직
`MultiTouchGestureTracker`의 2손가락 구간에 스크롤 델타 방출을 추가한다:
- 2손가락 구간에서 **`isDrag`가 true가 된 이후부터**(즉 `TAP_MAX_DISTANCE_PX`를 넘는 이동이 있었던 이후) centroid 이동을 스크롤로 변환해 방출한다. `isDrag`를 스크롤 시작 기준으로 재사용하면, 이미 탭/비탭 경계로 쓰이는 값과 일치하므로 "탭도 아니고 스크롤도 아닌" 사각지대가 생기지 않는다
- 픽셀 이동을 정수 스텝으로 변환할 때 **잔차를 구간 단위로 누적**해서 잘라버리지 않는다 (지난 QA에서 서버 `InputController`의 sub-pixel 소실 문제가 백로그로 남아있는데, 이번엔 처음부터 정확하게 만든다) — 예: `accumulatedDx += rawDeltaX / SCROLL_SENSITIVITY_PX_PER_STEP; val steps = accumulatedDx.toInt(); accumulatedDx -= steps`
- `GestureConfig`에 `SCROLL_SENSITIVITY_PX_PER_STEP` 상수를 추가한다 (몇 픽셀 이동이 스텝 1개인지 — 다른 상수들과 비슷한 스케일로 합리적인 기본값을 고르고 근거를 주석에 남길 것. 실기기 체감 튜닝은 이번 범위 밖)
- 2손가락 탭(제자리, `isDrag` false로 끝남)은 지난 작업대로 우클릭 판정 그대로 유지 — 스크롤 로직과 상호 배타적이어야 한다
- `GestureDecision`에 `scroll: ScrollDelta?` 필드 추가 (기존 `move: MoveDelta?`와 동일한 패턴)

### Server 쪽 처리
`pc_server/input_controller.py`의 `handle_event`에 `SCROLL` 분기 추가:
- `dx`/`dy`(정수 스텝)를 받아 `ctypes.windll.user32.SendInput`으로 휠 이벤트 발생
- 수직 스크롤: `MOUSEEVENTF_WHEEL` + `mouseData = dy_steps * WHEEL_DELTA`(120). 수평 스크롤: `MOUSEEVENTF_HWHEEL` + `mouseData = dx_steps * WHEEL_DELTA`
- **부호 규약이 실기기에서 사용자 기대와 다를 수 있다** — Windows 표준 규약(휠 앞으로 굴림 = 양수 = 보통 스크롤 업/콘텐츠 위로)을 따르되, 나중에 부호만 뒤집으면 되도록 변환 지점을 한 함수(`_scroll(dx_steps, dy_steps)`)에 모아둘 것
- `dx`/`dy`가 0이면 아무것도 하지 않음 (불필요한 SendInput 호출 방지)
- 기존 `_move`/`_click` 패턴(MOUSEINPUT/INPUT 구조체 재사용)을 따를 것

## 구현 대상
- Android: `domain/model/TrackpadEvent.kt`, `presentation/trackpad/MultiTouchGestureTracker.kt`, `presentation/trackpad/TrackpadScreen.kt`, `presentation/trackpad/TrackpadViewModel.kt`, `presentation/util/GestureConfig.kt`, `data/repository/TrackpadRepositoryImpl.kt`(TCP 직렬화 분기 추가)
- Server: `pc_server/input_controller.py`

## 테스트
- Android(JUnit): 2손가락 드래그가 `isDrag` 전환 이후 스크롤 델타를 방출하는지, 잔차가 구간에 걸쳐 누적되어 손실되지 않는지, 2손가락 탭(제자리)은 스크롤을 방출하지 않고 우클릭 판정이 유지되는지, 1손가락 구간은 영향받지 않는지(회귀)
- Server(pytest): `SCROLL` 이벤트가 올바른 `mouseData`(스텝×120)로 `SendInput`을 호출하는지(모킹), `dx`/`dy` 둘 다 0이면 호출 안 되는지, 기존 MOVE/CLICK 회귀 없는지

## 참고 문서
- `AGENTS.md` 섹션 4(통신 프로토콜)/5(제스처 설계)/6(로드맵) — 구현 후 갱신 필요
- 지난 QA 백로그: sub-pixel 잔차 누적 문제(`pc_server/input_controller.py`의 `_move`) — 이번 SCROLL 구현에서는 처음부터 잔차를 정확히 다룰 것 (MOVE 쪽 기존 이슈를 고치라는 뜻은 아님, 별개 이슈로 유지)
