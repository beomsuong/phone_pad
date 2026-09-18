# 01 android-dev — PointerInfo 기반 멀티터치 제스처 감지 기반 구축

**범위:** Android 단일 사이드. 새 이벤트 타입/프로토콜 필드 없음. `data/`·`pc_server/` 무변경.
**빌드/테스트:** `cd phone_pad_app && ./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL**, 54 tests / 0 failures / 0 errors.

---

## 1. 변경·신규 파일

| 파일 | 구분 | 내용 |
|------|------|------|
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/trackpad/MultiTouchGestureTracker.kt` | **신규** | 순수 Kotlin 제스처 판정기 (Compose 의존 0) |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/trackpad/TrackpadScreen.kt` | 변경 | `awaitEachGesture` 블록을 트래커 호출 어댑터로 리팩터링, 미사용 import(`Offset`, `positionChange`) 제거 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/util/GestureConfig.kt` | 변경 | `SINGLE_POINTER_COUNT = 1` 추가 (손가락 개수 기준의 매직 넘버 제거) |
| `phone_pad_app/app/src/test/java/com/example/phone_pad_app/presentation/trackpad/MultiTouchGestureTrackerTest.kt` | **신규** | 판정 로직 JUnit 테스트 12개 |
| `phone_pad_app/app/src/test/java/com/example/phone_pad_app/presentation/util/GestureConfigTest.kt` | 변경 | `SINGLE_POINTER_COUNT` 회귀 테스트 1개 추가 |

프로토콜 관련 파일(`domain/model/TrackpadEvent.kt`, `data/repository/TrackpadRepositoryImpl.kt`, `data/network/*`)과 `pc_server/`는 **전혀 건드리지 않았다.** 이번 작업으로 와이어에 나가는 JSON은 1바이트도 바뀌지 않는다.

---

## 2. 구현한 판정 로직 구조

### `MultiTouchGestureTracker` (순수 Kotlin, 스레드 안전 아님 — 포인터 루프 전용)

**입력 API**
```kotlin
fun onPointerEvent(pointerCount: Int, x: Float, y: Float, timestampMs: Long): GestureDecision
fun onGestureEnd(timestampMs: Long): GestureEndDecision
fun reset()
```
**반환 타입**
```kotlin
data class MoveDelta(val dx: Float, val dy: Float)              // MOVE_SENSITIVITY 이미 적용됨
data class GestureDecision(move: MoveDelta?, segmentStarted: Boolean, pointerCount: Int)
data class GestureEndDecision(click: Boolean, pointerCount: Int)
```

**내부 상태:** "구간(segment)" 단위로 `segmentPointerCount` / `segmentStartX,Y` / `segmentStartTimeMs` / `lastX,Y` / `isDrag`.

**규칙**
1. `pointerCount`가 직전 구간과 달라지면(또는 첫 이벤트면) `startSegment()`로 시작 좌표·시작 시각·`isDrag`를 그 시점 값으로 리셋하고 `segmentStarted = true`를 반환, 그 이벤트에서는 아무것도 방출하지 않는다 → AGENTS.md 섹션 5 "1→2 변화 시 현재 제스처 취소 후 재시작".
2. 델타는 직전 이벤트 좌표 대비(`x - lastX`)로 계산하고, `lastX/lastY`는 방출 여부와 무관하게 항상 전진시킨다 — 기존 `change.positionChange()` 동작과 정확히 동일(임계값 미만 미세 이동이 누적되지 않고 소실되는 동작까지 포함).
3. `pointerCount == SINGLE_POINTER_COUNT` 이고 시작점 기준 누적 거리 > `MOVE_MIN_DISTANCE_PX` 일 때만 `MoveDelta`를 반환.
4. 누적 거리 > `TAP_MAX_DISTANCE_PX` 이면 `isDrag = true` (구간 단위, 손가락 개수 무관하게 추적).
5. 2손가락 이상 구간은 좌표·시각·`isDrag`만 추적하고 **`move`는 항상 null**.
6. `onGestureEnd()`: `마지막 구간의 pointerCount == 1 && !isDrag && (end - segmentStartTimeMs) < TAP_MAX_DURATION_MS` 일 때만 `click = true`. 판정 후 `reset()`.
7. `pointerCount <= 0` 은 no-op (제스처 종료는 `onGestureEnd`로만 알림).

임계값은 전부 `GestureConfig` 참조 — 트래커 안에 숫자 리터럴 없음.

### `TrackpadScreen`의 어댑터 역할
```
awaitEachGesture {
  tracker = MultiTouchGestureTracker()
  down = awaitFirstDown()            → tracker.onPointerEvent(SINGLE_POINTER_COUNT, down.position, now)
  loop {
    event = awaitPointerEvent(); now = currentTimeMillis()
    pressed = event.changes.filter { it.pressed }
    if (pressed.isEmpty()) break                       // 모든 손가락이 떨어짐 = 제스처 종료
    decision = tracker.onPointerEvent(pressed.size, centroid(pressed), now)
    decision.move?.let { onMove(it.dx, it.dy); pressed.forEach { c -> c.consume() } }
  }
  if (tracker.onGestureEnd(lastTimestamp).click) onClick()
}
```
- 대표 좌표는 **눌린 포인터들의 중심점(centroid)**. 1손가락이면 그 포인터 좌표와 동일하므로 기존 동작과 일치하고, 2손가락 스크롤에 그대로 쓸 수 있다.
- 기존에 `event.changes.firstOrNull()` 하나만 보던 것을 `filter { it.pressed }`로 바꿔 두 번째 손가락이 인식된다.
- 이동 방출 시 `consume()` 대상이 단일 change → 눌린 change 전체로 확대(1손가락 구간에서는 동일 동작).

---

## 3. 테스트 목록 (`MultiTouchGestureTrackerTest`, 12개)

요청서 5개 시나리오 전부 + 회귀 보강. 임계값은 전부 `GestureConfig`에서 파생(`MOVE_MIN/2`, `TAP_MAX*3`, `TAP_MAX_DURATION/2`)시켜 상수를 바꿔도 의도가 유지되게 했다.

| # | 테스트 | 검증 대상 |
|---|--------|-----------|
| 1 | 1손가락 탭은 클릭으로 판정되고 이동은 방출되지 않는다 | 요청서 시나리오 1 |
| 2 | 1손가락 드래그는 감도가 적용된 델타를 매 프레임 방출하고 종료 시 클릭하지 않는다 | 요청서 시나리오 2 (감도 배율·프레임 간 델타·클릭 억제) |
| 3 | 1손가락이라도 탭 최대 지속 시간을 넘기면 클릭하지 않는다 | 기존 회귀 |
| 4 | 같은 좌표가 반복되면 이동을 방출하지 않는다 | `positionChange() != Zero` 게이트 대응 |
| 5 | 1에서 2손가락으로 바뀌면 이전 1손가락 구간이 취소되고 2손가락 구간은 아무것도 방출하지 않는다 | 요청서 시나리오 3 |
| 6 | 2에서 1손가락으로 바뀌면 그 시점부터 새 1손가락 구간이 시작되어 탭으로 판정된다 | 요청서 시나리오 4 (전체 제스처 길이는 탭 한계의 5배 초과인데도 탭 성립 → 타이머 재시작 증명) |
| 7 | 2에서 1손가락으로 바뀐 뒤 크게 움직이면 이동이 방출되고 클릭은 없다 | 요청서 시나리오 4 (드래그 분기) |
| 8 | 처음부터 끝까지 2손가락이면 이동도 클릭도 방출되지 않는다 | 요청서 시나리오 5 |
| 9 | 3손가락 구간도 아무것도 방출하지 않는다 | 2 초과 일반화 |
| 10 | 포인터 개수가 0이면 아무 상태도 바꾸지 않는다 | no-op 보장 |
| 11 | 이벤트가 없었으면 종료 시 클릭하지 않는다 | 빈 제스처 |
| 12 | 종료 후에는 상태가 초기화되어 다음 제스처가 독립적으로 판정된다 | `isDrag` 누수 방지 |

`GestureConfigTest`에 "단일 포인터 구간 기준은 1손가락이다" 1개 추가 (5개).

### 실행 결과
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 31s
```
| 클래스 | tests | failures | errors |
|--------|------:|---------:|-------:|
| SessionHandshakeTest | 8 | 0 | 0 |
| TcpClientTest | 4 | 0 | 0 |
| TrackpadRepositoryHeartbeatTest | 12 | 0 | 0 |
| TrackpadRepositoryImplTest | 11 | 0 | 0 |
| SendEventUseCaseTest | 1 | 0 | 0 |
| ExampleUnitTest | 1 | 0 | 0 |
| **MultiTouchGestureTrackerTest** | **12** | 0 | 0 |
| GestureConfigTest | 5 | 0 | 0 |
| **합계** | **54** | **0** | **0** |

기존 제스처 관련 테스트는 `GestureConfigTest`의 임계값 회귀 4개뿐이었고(`TrackpadScreen` 자체 테스트는 없었음) 전부 통과 상태로 유지된다.

---

## 4. 남은 이슈 / 다음 작업 연결 지점

1. **2손가락 우클릭 연결 지점** — `MultiTouchGestureTracker.onGestureEnd()`. 현재 `click`은 `pointerCount == 1`일 때만 true지만, `GestureEndDecision.pointerCount`를 이미 반환하고 `isDrag`/`elapsed` 판정도 손가락 개수와 무관하게 구간 단위로 돌고 있다. `pointerCount == 2 && !isDrag && elapsed < TAP_MAX_DURATION_MS` 분기를 추가하고 `GestureEndDecision`에 `button`/`kind` 같은 필드를 얹으면, `TrackpadScreen`은 `onRightClick()` 콜백 한 줄만 늘면 된다. 어댑터 구조 변경 불필요.
2. **2손가락 스크롤 연결 지점** — `onPointerEvent()`의 `pointerCount == SINGLE_POINTER_COUNT` 분기 else 쪽. 이미 centroid 델타(`dx`, `dy`)를 계산해 두고 버리고 있으므로, `GestureDecision`에 `scroll: ScrollDelta?`를 추가해 반환하면 된다. 다만 **SCROLL은 TCP 채널**이고 `dx`/`dy`가 정수 스텝(AGENTS.md 섹션 4: `{"type":"SCROLL","dx":0,"dy":-3}`)이므로, px→스텝 변환 임계값(`SCROLL_STEP_PX` 등)을 `GestureConfig`에 새로 추가하고 잔차 누적 처리를 함께 설계해야 한다. 서버 쪽 `handle_event`의 SCROLL 구현이 함께 필요하므로 **교차 경계면 작업**이며, 이번처럼 단일 사이드로 진행하면 안 된다.
3. **핀치/줌 등 좌표 분산 정보 없음** — 현재 트래커는 centroid만 받아서 두 포인터 간 거리를 모른다. 핀치 제스처를 하려면 `onPointerEvent` 시그니처에 포인터 간 spread를 추가해야 한다 (Phase 4+ 이슈).
4. **엣지 케이스 "화면 밖으로 나간 손가락 → DRAG_END"(AGENTS.md 섹션 5)** — 미구현. DRAG_START/DRAG_END 자체가 Phase 3 항목이라 이번 범위 밖. 현재는 포인터가 사라지면 `pressed` 목록에서 빠져 개수 변화로 처리되므로 크래시나 좌표 튐은 없다.
5. **실기기 미검증** — Android SDK 에뮬레이터/기기 실행은 하지 않았다. 유닛 테스트(JVM)와 `compileDebugKotlin` 컴파일은 통과했으나, 실제 멀티터치 이벤트 스트림에서 centroid 기반 델타가 체감상 자연스러운지는 실기기 확인이 필요하다. 특히 **손가락을 하나 뗄 때 centroid가 순간 점프하는 문제**는 개수 변화 시 구간 리셋(시작점 = 남은 손가락 좌표)으로 델타 튐이 구조적으로 차단되지만, 체감 검증은 별도로 권장한다.
6. **AGENTS.md 갱신 여지** — 섹션 6 Phase 2의 "Android: `PointerInfo` 기반 멀티터치 제스처 감지" 항목과 섹션 6 핵심 파일 목록의 `TrackpadScreen.kt`(미착수) 표기를 갱신할 수 있다. 문서 수정은 이번 작업 범위에 포함되지 않아 손대지 않았으므로, 리더가 `docs(harness):` 커밋에서 함께 처리할 것을 제안한다.
