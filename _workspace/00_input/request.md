# 요청: 1손가락 더블탭 → DOUBLE_CLICK (AGENTS.md Phase 3)

**범위 판단:** 교차 경계면 (새 이벤트 타입 DOUBLE_CLICK 추가 + 서버 구현 필요)
**실행 경로:** 리더가 스펙 사전 확정 → android-dev/server-dev 병렬 호출 → protocol-qa 사후 검증

## 왜 "CLICK 두 번"이 아니라 명시적 DOUBLE_CLICK 이벤트가 필요한가

두 번째 탭이 화면(폰)에서 첫 번째 탭과 완전히 같은 좌표에 오는 경우는 거의 없다 — 사람이 두 번 탭하면 몇 픽셀씩 어긋난다. 만약 이 어긋남이 `MOVE_MIN_DISTANCE_PX`를 넘겨 그사이에 MOVE가 한 번이라도 나가면 PC 커서가 미세하게 움직이고, Windows의 네이티브 더블클릭 판정은 커서가 움직이지 않은 아주 좁은 사각형(기본 4px) 안에서 두 클릭이 일어나야만 성립한다. 즉 "CLICK을 빠르게 두 번 보내서 OS가 알아서 더블클릭으로 인식하게 하자"는 접근은 실기기에서 신뢰할 수 없다. 그래서 Android가 명시적으로 더블탭을 판정해서 **`DOUBLE_CLICK` 이벤트 하나만** 보내고, 서버가 커서를 전혀 움직이지 않은 채로 클릭 두 번을 원자적으로 실행한다.

## 확정 스펙

### 와이어 포맷 (TCP 9000, 기존 newline-delimited JSON)
```jsonc
{"type":"DOUBLE_CLICK","button":"left"}
```
- `button` 필드는 CLICK과 동일한 모양을 위해 유지하지만, 이번 범위(1손가락 더블탭)에서는 항상 `"left"`
- session 필드 없음 (CLICK과 동일한 TCP 평문 이벤트)

### 판정 로직 (지연 후 확정 방식)
1손가락 탭이 끝날 때마다 **즉시 CLICK을 보내지 않고**, 짧은 시간(`DOUBLE_TAP_INTERVAL_MS`) 동안 "혹시 이어서 탭이 또 오는지" 기다린다:
- 이 시간 안에 같은 위치(`DOUBLE_TAP_DISTANCE_PX` 이내) 근처에서 또 1손가락 탭이 끝나면 → 두 탭을 합쳐 **`DOUBLE_CLICK` 한 번만** 보낸다 (개별 CLICK 두 개를 보내지 않는다)
- 이 시간이 지나도록 두 번째 탭이 안 오면 → 그제서야 미뤄뒀던 **CLICK을 보낸다** (단일 탭은 여전히 CLICK 하나)
- 우클릭(2손가락 탭)은 이 지연/병합 로직과 완전히 무관하다 — 우클릭은 지금처럼 즉시 전송

이 방식의 대가: **모든 1손가락 탭(클릭)에 `DOUBLE_TAP_INTERVAL_MS`만큼의 지연이 생긴다.** 더블클릭을 지원하려면 구조적으로 피할 수 없는 트레이드오프이며(더블클릭을 지원하는 모든 UI가 같은 문제를 갖는다), 이번 작업의 의도된 결과다.

### 상수 (Android `GestureConfig.kt`)
- `DOUBLE_TAP_INTERVAL_MS = 300L` — 두 탭을 하나의 더블탭으로 묶는 최대 간격(첫 탭 종료 ~ 둘째 탭 종료). 실기기 미검증, 조정 가능
- `DOUBLE_TAP_DISTANCE_PX = 40f` — 두 탭 중심 좌표 사이 최대 허용 거리. `TAP_MAX_DISTANCE_PX`(20px)의 2배 — 두 번째 탭이 첫 번째와 완전히 같은 자리가 아니어도 되도록 여유를 준다

## Android 구현 대상

1. **`presentation/trackpad/DoubleTapDetector.kt` 신규** — 순수 Kotlin, Compose 비의존 (기존 `MultiTouchGestureTracker`와 같은 설계 원칙). "직전 탭"을 하나만 기억하는 상태 머신:
   - `onTap(x, y, timestampMs): Boolean` — 직전 탭이 있고 `DOUBLE_TAP_INTERVAL_MS` 이내 + `DOUBLE_TAP_DISTANCE_PX` 이내면 `true`(더블탭 확정, 내부 상태 리셋) 반환. 아니면 이 탭을 "직전 탭"으로 기억하고 `false` 반환
   - `reset()`
2. **`MultiTouchGestureTracker.kt`**: `GestureEndDecision`에 탭 위치(`x`, `y`, 예: 구간 시작 좌표 재사용)를 추가해서, 호출부가 별도로 좌표를 들고 다닐 필요 없게 한다. 판정 로직(탭/드래그/우클릭) 자체는 변경하지 않는다
3. **`TrackpadScreen.kt`**: `pointerInput(Unit)` 블록을 `coroutineScope { }`로 감싸서, 개별 제스처(`awaitEachGesture`)보다 오래 사는 스코프에서 "지연된 단일 클릭"을 `launch`로 관리한다.
   - 1손가락 탭(`clickButton == BUTTON_LEFT`)이 끝나면 `DoubleTapDetector.onTap(...)` 호출
     - `true`(더블탭 확정) → 대기 중인 지연 클릭 job이 있으면 취소하고, 즉시 `onDoubleClick()` 호출
     - `false` → 기존에 대기 중이던 job이 있으면 취소하고, `launch { delay(DOUBLE_TAP_INTERVAL_MS); onClick() }`으로 새로 예약
   - 2손가락 탭(우클릭)은 기존처럼 즉시 `onRightClick()` — 이 로직과 무관
4. **`domain/model/TrackpadEvent.kt`**: `data class DoubleClick(val button: String = "left") : TrackpadEvent()` 추가
5. **`data/repository/TrackpadRepositoryImpl.kt`**: `DoubleClick`을 TCP로 `{"type":"DOUBLE_CLICK","button":"left"}` 직렬화. CLICK과 같은 등급(저빈도, 사용자 명시 행동)이므로 전송 실패 시 기존 CLICK처럼 `ConnectionState.Error`로 알려도 된다 (MOVE/SCROLL과 달리 조용히 버리지 않음)
6. **`TrackpadViewModel.kt`**: `sendDoubleClick()` 추가 → `sendEventUseCase(TrackpadEvent.DoubleClick())`
7. **`TrackpadSurface`**: `onDoubleClick: () -> Unit` 파라미터 추가, `viewModel::sendDoubleClick`로 배선

기존 1손가락 드래그(MOVE), 2손가락 우클릭, 2손가락 스크롤 로직은 회귀 없이 그대로 유지해야 한다.

## Android 테스트
- `DoubleTapDetectorTest`(신규): 간격/거리 이내 두 탭 → 두 번째 호출에서 true. 간격 초과 → false(새 직전 탭으로 갱신). 거리 초과 → false. 더블탭 확정 후 상태 리셋되어 세 번째 탭이 두 번째와 다시 묶이지 않는지(즉 A-B가 더블탭이면 C는 새로 시작)
- `TrackpadScreen`/어댑터 레벨 검증이 어려우면(Compose 의존) 최소한 `DoubleTapDetector` 순수 로직 테스트로 커버. 기존 테스트 전부 회귀 없이 통과할 것

## Server 구현 대상
`pc_server/input_controller.py`:
- `handle_event`에 `DOUBLE_CLICK` 분기 추가 → `self._double_click(event.get("button", "left"))`
- `_double_click(button)` 신규: 기존 `_click`과 같은 INPUT/MOUSEINPUT 구조체 패턴으로 **down-up-down-up 4개를 하나의 `SendInput` 호출**에 담아 원자적으로 보낸다 (커서를 움직이는 요소가 전혀 없으므로 Windows 더블클릭 판정 사각형 문제가 발생하지 않는다). `_click`을 두 번 호출(=SendInput 두 번)하지 말 것 — 한 번에 묶는 것이 이번 스펙의 핵심이다

## Server 테스트
- `handle_event({"type":"DOUBLE_CLICK","button":"left"})` → `SendInput`이 1회만 호출되고, 전달되는 INPUT 배열이 정확히 [LEFTDOWN, LEFTUP, LEFTDOWN, LEFTUP] 순서인지 (모킹)
- `button`이 `"right"`로 와도 동작하는지(RIGHTDOWN/RIGHTUP 4개, 이번 범위는 아니지만 재사용성 확인 차원)
- 기존 MOVE/CLICK/SCROLL/HEARTBEAT 회귀 없는지

## 참고 문서
- `AGENTS.md` 섹션 4(통신 프로토콜)/5(제스처 설계)/6(로드맵) — 구현 후 갱신 필요
