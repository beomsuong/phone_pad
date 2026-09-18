# 01 android-dev — 2손가락 탭 → 우클릭 (CLICK button="right")

**범위:** Android 단일 사이드. 새 이벤트 타입/프로토콜 필드 **없음** — 기존 `CLICK` 이벤트를 `button:"right"`로 재사용.
**서버 무변경:** `pc_server/`는 한 줄도 건드리지 않았다 (`_click()`이 이미 `button != "left"`를 우클릭으로 처리).
**도메인/데이터 계층 무변경:** `domain/model/TrackpadEvent.kt`, `data/repository/TrackpadRepositoryImpl.kt`, `data/network/*` 모두 무변경.
**빌드/테스트:** `cd phone_pad_app && ./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL in 15s**, **69 tests / 0 failures / 0 errors**.

---

## 1. 변경·신규 파일

| 파일 | 구분 | 내용 |
|------|------|------|
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/trackpad/MultiTouchGestureTracker.kt` | 변경 | `GestureEndDecision.click: Boolean` → `clickButton: String?`, 2손가락 탭 판정 + 멀티터치 해제 꼬리 보정, `BUTTON_LEFT`/`BUTTON_RIGHT` 상수 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/trackpad/TrackpadScreen.kt` | 변경 | 어댑터가 `clickButton`으로 `onClick()`/`onRightClick()` 분기, `TrackpadSurface`에 `onRightClick` 파라미터 추가 및 `viewModel::sendRightClick` 배선, 안내 문구 1줄 추가 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/trackpad/TrackpadViewModel.kt` | 변경 | `sendRightClick()` 추가, `sendClick()`의 버튼 값을 상수로 명시 |
| `phone_pad_app/app/src/main/java/com/example/phone_pad_app/presentation/util/GestureConfig.kt` | 변경 | `DOUBLE_POINTER_COUNT = 2`, `MULTI_TOUCH_RELEASE_GRACE_MS = 50L` 추가 |
| `phone_pad_app/app/src/test/java/com/example/phone_pad_app/presentation/trackpad/MultiTouchGestureTrackerTest.kt` | 변경 | 기존 12개를 `clickButton` 기준으로 갱신 + 우클릭/꼬리 보정 테스트 8개 추가 (총 20) |
| `phone_pad_app/app/src/test/java/com/example/phone_pad_app/presentation/trackpad/TrackpadViewModelTest.kt` | **신규** | ViewModel → UseCase 이벤트 번역 테스트 5개 |
| `phone_pad_app/app/src/test/java/com/example/phone_pad_app/presentation/util/GestureConfigTest.kt` | 변경 | 새 상수 회귀 테스트 2개 추가 (총 7) |

와이어로 나가는 JSON은 `{"type":"CLICK","button":"right"}` 한 종류가 **추가로 발생**할 뿐, 기존 스펙에 변경이 없다 (AGENTS.md 섹션 4의 "우클릭" 예시 그대로, TCP 9000).

---

## 2. 실제 구현한 판정 로직

### 2-1. `GestureEndDecision` 확장

```kotlin
data class GestureEndDecision(
    val clickButton: String? = null,   // "left" / "right" / null
    val pointerCount: Int = 0,
)
```
`click: Boolean`은 제거했다 (탭 여부 = `clickButton != null`). 값 어휘는 프로토콜 JSON의 `button` 필드와 동일하며,
`MultiTouchGestureTracker.BUTTON_LEFT` / `BUTTON_RIGHT` 상수로 고정했다.

### 2-2. `onGestureEnd()` 판정

```
탭 조건: !drag && elapsed < TAP_MAX_DURATION_MS
  → pointerCount == SINGLE_POINTER_COUNT(1) : "left"   (기존 로직·임계값 그대로)
  → pointerCount == DOUBLE_POINTER_COUNT(2) : "right"  (신규)
  → 그 외(0, 3+)                            : null
```
1손가락 경로의 조건식·임계값은 한 글자도 바꾸지 않았다. 손가락 개수 → 버튼 매핑만 `when`으로 확장했다.

### 2-3. 멀티터치 해제 꼬리 보정 (스펙에 없던 추가 — **읽어주세요**)

스펙(`마지막 구간의 pointerCount == 2`)을 그대로만 구현하면 **실기기에서 2손가락 탭이 거의 항상 좌클릭으로 뒤집힌다.**
두 손가락을 물리적으로 동시에 뗄 수 없으므로 Android는 `ACTION_POINTER_UP` → `ACTION_UP`을 따로 보내고,
어댑터의 `pressed.size`는 `2 → 1 → 0`으로 흐른다. 이때 중간의 `1`이 새 1손가락 구간을 시작시키고,
그 구간은 이동 0 · 지속 수십 ms이므로 완벽한 "1손가락 탭"으로 판정된다 → 좌클릭.

그래서 다음 보정을 넣었다:

> 마지막 구간이 **(a) 포인터 개수 감소로 시작됐고, (b) 그 구간에서 드래그가 없었고, (c) `MULTI_TOUCH_RELEASE_GRACE_MS`(50ms) 안에 제스처가 끝났으면**
> 그 구간을 "손가락을 어긋나게 뗀 꼬리"로 보고 버리고, **직전 구간**(더 많은 손가락)의 시작 시각·드래그 여부로 판정한다.

- 트래커에 `hasPrevSegment` / `prevPointerCount` / `prevStartTimeMs` / `prevIsDrag` 4개 필드를 추가하고 `startSegment()`에서 스냅샷, `reset()`에서 함께 초기화한다.
- **1손가락만 쓰는 제스처는 `prevPointerCount`가 존재하지 않으므로 이 경로에 진입할 수 없다** — 기존 좌클릭 동작은 구조적으로 영향받지 않는다.
- 유예(50ms)는 `TAP_MAX_DURATION_MS`(200ms)의 1/4이라서, "손가락 하나를 떼고 남은 손가락으로 탭"하는 정상 동작(지난 작업의 회귀 테스트 #6)은 그대로 좌클릭으로 남는다. 이 부등식은 `GestureConfigTest`에서 고정한다.
- 두 손가락이 같은 이벤트에서 동시에 떨어지는 경우는 꼬리 구간 자체가 생기지 않아 2-2의 일반 경로로 우클릭이 된다.

### 2-4. 어댑터 / ViewModel 배선

```kotlin
// TrackpadScreen.kt — awaitEachGesture 종료 지점
when (tracker.onGestureEnd(lastTimestamp).clickButton) {
    MultiTouchGestureTracker.BUTTON_LEFT  -> onClick()
    MultiTouchGestureTracker.BUTTON_RIGHT -> onRightClick()
    else -> Unit
}
```
`TrackpadSurface(host, onMove, onClick, onRightClick, onDisconnect)`로 파라미터 1개 증가, `TrackpadScreen`에서 `viewModel::sendRightClick` 연결.
ViewModel은 `sendEventUseCase(TrackpadEvent.Click(BUTTON_RIGHT))`만 호출한다 — 네트워크 직접 호출 없음, 채널(TCP) 선택은 기존대로 repository 책임.
안내 문구에 "두 손가락 탭으로 우클릭" 한 줄 추가.

---

## 3. 테스트 목록

### `MultiTouchGestureTrackerTest` (12 → 20)

기존 12개는 `end.click` → `end.clickButton` 기준으로 갱신만 했고 시나리오·기대값은 동일하다 (1손가락 탭 → `"left"` 확인으로 강화).

| # | 신규 테스트 | 검증 |
|---|-------------|------|
| 13 | 2손가락 탭은 우클릭으로 판정되고 이동은 방출되지 않는다 | request.md 시나리오 1 |
| 14 | 2손가락 드래그는 이동도 클릭도 방출하지 않는다 | request.md 시나리오 2 (기존 테스트 이름 변경) |
| 15 | 2손가락을 오래 누르고 있다 떼면 우클릭하지 않는다 | `TAP_MAX_DURATION_MS` 경계 |
| 16 | 두 손가락이 어긋나게 떨어져도 우클릭으로 판정된다 | 2-3 꼬리 보정 (실기기 이벤트 순서) |
| 17 | 꼬리 구간이 유예 시간을 넘기면 남은 손가락의 좌클릭으로 판정된다 | 꼬리 보정이 정상 동작을 삼키지 않음 |
| 18 | 꼬리 보정은 직전 2손가락 구간이 드래그였으면 적용되지 않는다 | 2손가락 스크롤이 우클릭으로 새지 않음 |
| 19 | 3손가락 탭은 어느 버튼으로도 판정되지 않는다 | 2 초과 일반화 |
| 20 | 버튼 상수는 프로토콜 JSON의 button 값과 같다 | AGENTS.md 섹션 4 고정 |

추가로 기존 "종료 후 상태 초기화" 테스트 옆에 **"종료 후에는 직전 구간 정보도 초기화되어 다음 제스처에 새지 않는다"**를 넣어 `prevSegment` 누수를 막았다.

### `TrackpadViewModelTest` (신규 5개)
`Dispatchers.setMain(UnconfinedTestDispatcher())` + MockK(`SendEventUseCase`, `TrackpadRepository`).

1. `sendRightClick()` → `TrackpadEvent.Click(button="right")` 1회 (request.md 지정 시나리오)
2. `sendClick()` → `Click(button="left")` 1회, `"right"`는 0회 (회귀)
3. 좌/우 클릭이 서로 다른 이벤트로 구분되어 횟수대로 전달
4. `sendMove()`는 `Move`만 보내고 `Click` 경로와 섞이지 않음
5. 트래커 버튼 상수 ↔ `TrackpadEvent.Click.button` 어휘 일치 (기본값 `"left"` 포함)

### `GestureConfigTest` (5 → 7)
- `DOUBLE_POINTER_COUNT == 2` 이고 `SINGLE_POINTER_COUNT`보다 큼
- `0 < MULTI_TOUCH_RELEASE_GRACE_MS < TAP_MAX_DURATION_MS / 2` (2-3 보정의 전제 조건)

### 실행 결과

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 15s
```

| 클래스 | tests | failures | errors |
|--------|------:|---------:|-------:|
| SessionHandshakeTest | 8 | 0 | 0 |
| TcpClientTest | 4 | 0 | 0 |
| TrackpadRepositoryHeartbeatTest | 12 | 0 | 0 |
| TrackpadRepositoryImplTest | 11 | 0 | 0 |
| SendEventUseCaseTest | 1 | 0 | 0 |
| ExampleUnitTest | 1 | 0 | 0 |
| **MultiTouchGestureTrackerTest** | **20** | 0 | 0 |
| **TrackpadViewModelTest** | **5** | 0 | 0 |
| GestureConfigTest | 7 | 0 | 0 |
| **합계** | **69** | **0** | **0** |

직전 실행 54개 → 69개. 기존 54개 전부 통과(회귀 없음).

---

## 4. 남은 이슈

1. **실기기 미검증** — JVM 유닛 테스트와 `compileDebugKotlin`/`kaptDebugKotlin`은 통과했으나 에뮬레이터/실기기 실행은 하지 않았다. 특히 2-3 꼬리 보정의 **유예 50ms가 실제 손가락 떼는 간격을 충분히 덮는지**는 실기기에서 확인해야 한다. 만약 우클릭 대신 좌클릭이 나가는 사례가 보이면 `MULTI_TOUCH_RELEASE_GRACE_MS`만 올리면 된다(단, `TAP_MAX_DURATION_MS/2` 미만 유지 — 테스트가 고정).
2. **우클릭 억제 케이스 없음** — "2손가락 탭 직후 바로 또 탭"처럼 연타하면 우클릭이 연속 발생한다. 더블클릭(Phase 2 미구현)과의 상호작용 정책은 아직 정의되지 않았다.
3. **2손가락 스크롤과의 경계** — 현재는 `TAP_MAX_DISTANCE_PX`(20px)만 넘으면 우클릭이 취소된다. SCROLL을 붙일 때 "스크롤 시작 임계값"이 이 값보다 크면 20~임계값 구간이 무동작 사각지대가 된다. SCROLL은 교차 경계면 작업이므로 그때 함께 설계해야 한다.
4. **AGENTS.md 갱신 여지** — 섹션 4의 `// 우클릭 (Phase 2, 미구현)` 주석과 섹션 5 표의 "2손가락 탭 → CLICK(right) ⬜ 미구현" 행이 이제 구현됨으로 바뀌어야 한다. 문서 수정은 이번 범위에 없어 손대지 않았으니 리더가 `docs(harness):` 커밋에서 함께 처리할 것을 제안한다.
5. **`GestureDecision.segmentStarted`는 여전히 어댑터에서 미사용** — 트래커 API에는 있지만 `TrackpadScreen`이 쓰지 않는다(지난 작업부터). 스크롤/드래그 작업에서 쓰일 예정이라 제거하지 않았다.
