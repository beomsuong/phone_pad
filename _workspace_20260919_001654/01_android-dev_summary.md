# android-dev 작업 요약 — 2손가락 드래그 → 스크롤 (SCROLL)

작업일: 2026-09-18
입력 스펙: `_workspace/00_input/request.md` (리더 사전 확정) — 필드명/상수명 변경 없음

---

## 1. 변경 파일 목록

### 구현 (`phone_pad_app/app/src/main/java/com/example/phone_pad_app/`)
| 파일 | 변경 내용 |
|------|-----------|
| `domain/model/TrackpadEvent.kt` | `data class Scroll(val dx: Int, val dy: Int)` 추가 (sealed class 확장 기본 규칙, AGENTS.md 섹션 9) |
| `presentation/util/GestureConfig.kt` | `SCROLL_SENSITIVITY_PX_PER_STEP = 40f` 추가 + 근거 주석 |
| `presentation/trackpad/MultiTouchGestureTracker.kt` | `ScrollDelta` 데이터 클래스 신설, `GestureDecision.scroll` 필드 추가, 2손가락 구간 스크롤 방출 + 잔차 누적(`scrollRemainderX/Y`, `accumulateScroll()`) |
| `data/repository/TrackpadRepositoryImpl.kt` | `sendEvent`의 `when`에 `is TrackpadEvent.Scroll` 분기 추가 (TCP 직렬화) |
| `presentation/trackpad/TrackpadViewModel.kt` | `sendScroll(dx: Int, dy: Int)` 추가 (UseCase 경유) |
| `presentation/trackpad/TrackpadScreen.kt` | `TrackpadSurface`에 `onScroll: (Int, Int) -> Unit` 파라미터 추가 + 트래커 결정 배선, 안내 문구에 "두 손가락 드래그로 스크롤" 추가 |

### 테스트 (`phone_pad_app/app/src/test/java/com/example/phone_pad_app/`)
| 파일 | 추가 테스트 수 |
|------|---------------|
| `presentation/trackpad/MultiTouchGestureTrackerTest.kt` | +13 (19 → 32) |
| `data/repository/TrackpadRepositoryImplTest.kt` | +2 (11 → 13) |
| `presentation/trackpad/TrackpadViewModelTest.kt` | +2 (5 → 7) |
| `presentation/util/GestureConfigTest.kt` | +1 (7 → 8) |

---

## 2. 이벤트 JSON 스펙 (확정, 서버와 공유)

```jsonc
// TCP 9000, newline-delimited JSON. session 필드 없음 (CLICK과 동일한 평문 이벤트)
{"type":"SCROLL","dx":0,"dy":-3}
```

- `dx`/`dy`는 **정수** 휠 스텝(노치). 앱에서 이미 px→스텝 변환과 잔차 누적을 끝낸 값이므로 서버는 그대로 `steps × WHEEL_DELTA(120)`만 곱하면 된다.
- 부호: `dy` 양수 = 손가락이 아래로 이동 / `dx` 양수 = 손가락이 오른쪽으로 이동.
- **둘 다 0인 SCROLL은 앱이 애초에 보내지 않는다** (`accumulateScroll()`이 stepsX·stepsY 모두 0이면 `null` 반환). 서버의 "0이면 no-op" 가드는 방어선으로 유지하되, 정상 경로에서는 도달하지 않는다.
- 직렬화 리터럴은 `TrackpadRepositoryImplTest`가 문자열 그대로 고정하고 있다 (`{"type":"SCROLL","dx":0,"dy":-3}`).
- MOVE가 아니므로 **TCP 전용** — 테스트에서 `udpClient.send`가 0회임을 명시적으로 검증한다.

---

## 3. 실제 구현한 판정/변환 로직

### 3.1 스크롤 개시 조건 (탭/스크롤 사각지대 없음)
`MultiTouchGestureTracker.onPointerEvent()`의 2손가락 구간에서, 기존에 이미 계산하던 `isDrag`
(= 구간 시작점 기준 누적 이동이 `TAP_MAX_DISTANCE_PX`(20px) 초과)를 **그대로** 스크롤 시작 조건으로
재사용한다.

```kotlin
val scroll = if (pointerCount == GestureConfig.DOUBLE_POINTER_COUNT && isDrag) {
    accumulateScroll(dx, dy)
} else null
```

- `isDrag` 판정이 스크롤 계산보다 **먼저** 수행되므로, 임계값을 넘긴 바로 그 프레임의 델타도
  버리지 않고 스크롤에 포함된다 (경계 손실 없음).
- 2손가락 탭(우클릭)은 `!isDrag` 조건으로 판정되므로 **구조적으로 상호 배타적**이다.
  "탭도 아니고 스크롤도 아닌" 구간이 생기지 않는다.
- 3손가락 이상은 여전히 추적만 한다 (Phase 4/5 예약).

### 3.2 px → 정수 스텝 변환 + 잔차 누적
```kotlin
private fun accumulateScroll(dx: Float, dy: Float): ScrollDelta? {
    scrollRemainderX += dx / GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP
    scrollRemainderY += dy / GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP
    val stepsX = scrollRemainderX.toInt()   // 0 방향으로 절사 → 음수도 대칭
    val stepsY = scrollRemainderY.toInt()
    scrollRemainderX -= stepsX
    scrollRemainderY -= stepsY
    return if (stepsX == 0 && stepsY == 0) null else ScrollDelta(stepsX, stepsY)
}
```
- 잔차(`|값| < 1`)는 **구간(segment) 수명 동안** 유지된다. 프레임당 이동이 1스텝 미만인
  느린 드래그에서도 스텝이 잘려나가지 않는다.
- `startSegment()`와 `reset()`에서 잔차를 0으로 초기화한다 — 손가락 개수가 바뀌거나 제스처가
  끝나면 이전 구간의 잔차가 다음 스크롤의 첫 스텝을 앞당기지 못한다.
- `toInt()`는 0 방향 절사이므로 `-1.7 → -1` (잔차 `-0.7`)로 양/음 방향이 대칭이다.

### 3.3 `SCROLL_SENSITIVITY_PX_PER_STEP = 40f` 근거
1. **반드시 `TAP_MAX_DISTANCE_PX`(20px)보다 커야 한다.** 스크롤은 `isDrag` 이후에만 시작되는데,
   스텝 단위가 탭 한계보다 작으면 스크롤이 걸리는 순간 이미 1스텝 이상이 쌓여 있어 "손대자마자
   툭 튀는" 느낌이 된다. 2배로 두면 스크롤 진입이 0스텝에서 시작한다.
   (`GestureConfigTest`가 이 부등식을 회귀 테스트로 고정)
2. Windows 휠 1노치 ≈ 3줄이므로, 실사용에서 편한 400px 정도의 2손가락 스와이프가
   10스텝 ≈ 30줄 ≈ 텍스트 한 화면 분량이 된다.
3. 실기기 체감 튜닝은 request.md 명시대로 범위 밖 — Phase 3 감도 설정 UI로 미룬다.

### 3.4 Screen 배선
`TrackpadSurface`의 포인터 루프에서 `decision.move`/`decision.scroll`을 각각 확인하고,
둘 중 하나라도 방출되면 pressed 포인터를 consume 한다. 트래커 구조상 `move`와 `scroll`이
동시에 non-null이 되는 일은 없다 (MOVE는 1손가락, SCROLL은 2손가락 전용).

---

## 4. 테스트 목록

### `MultiTouchGestureTrackerTest` (신규 13개)
| 테스트 | 검증 내용 |
|--------|-----------|
| `2손가락이 탭 한계 안에서 움직이는 동안에는 스크롤을 방출하지 않고 우클릭이 유지된다` | isDrag 전환 전 무방출 + **우클릭 회귀 방지** |
| `isDrag로 전환된 2손가락 구간은 centroid 이동을 정수 스텝 스크롤로 방출한다` | 기본 방출 경로 (120px → 3스텝) |
| `스크롤 부호는 손가락 진행 방향을 그대로 따른다` | dx/dy 부호 규약 4방향 고정 |
| `1스텝 미만 프레임이 이어져도 잔차가 누적되어 스텝이 손실되지 않는다` | **잔차 누적 핵심** — 프레임당 0.25스텝 × 40프레임 = 정확히 10스텝 |
| `잔차 누적은 음의 방향에서도 대칭으로 동작한다` | 음수 절사 방향 |
| `스텝이 하나도 차지 않은 프레임은 스크롤을 방출하지 않는다` | 0스텝 → null (서버 헛도는 SendInput 방지) |
| `스크롤로 끝난 2손가락 구간은 우클릭으로 판정되지 않는다` | 스크롤/우클릭 상호 배타 |
| `1손가락 구간은 스크롤을 방출하지 않는다` | MOVE는 그대로, scroll은 null |
| `3손가락 구간은 스크롤을 방출하지 않는다` | 상위 제스처 예약 보호 |
| `손가락 개수가 바뀌면 스크롤 잔차가 새 구간으로 새지 않는다` | 구간 재시작 시 잔차 리셋 |
| `제스처가 끝나면 스크롤 잔차도 초기화된다` | `reset()` 시 잔차 리셋 |
| `스크롤 개시 시점은 탭 한계 초과 프레임이며 그 프레임의 이동도 버리지 않는다` | 경계 프레임 손실 없음 |

(기존 19개 — 1손가락 MOVE/CLICK, 손가락 개수 전환, 2손가락 탭 우클릭, 꼬리 보정 — 전부 유지)

### `TrackpadRepositoryImplTest` (신규 2개)
- `Scroll 이벤트는 정수 스텝을 담아 TCP로 전송된다` — 와이어 리터럴 고정 + UDP 0회
- `Scroll JSON은 session 필드를 포함하지 않고 소수점도 붙지 않는다`

### `TrackpadViewModelTest` (신규 2개)
- `sendScroll은 정수 스텝 그대로 SCROLL 이벤트를 보낸다` — UseCase 경유, Click/Move와 미혼선
- `트래커의 ScrollDelta가 그대로 도메인 이벤트 필드로 옮겨진다` — dx/dy 축 뒤바뀜 방지

### `GestureConfigTest` (신규 1개)
- `스크롤 1스텝 거리는 탭 최대 이동 거리보다 크다` — 사각지대 방지 부등식 고정

---

## 5. 테스트 실행 결과

```
cd phone_pad_app && ./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL in 17s
```

| 테스트 클래스 | tests | failures | errors |
|---------------|------:|---------:|-------:|
| `data.network.SessionHandshakeTest` | 8 | 0 | 0 |
| `data.network.TcpClientTest` | 4 | 0 | 0 |
| `data.repository.TrackpadRepositoryHeartbeatTest` | 12 | 0 | 0 |
| `data.repository.TrackpadRepositoryImplTest` | 13 | 0 | 0 |
| `domain.usecase.SendEventUseCaseTest` | 1 | 0 | 0 |
| `ExampleUnitTest` | 1 | 0 | 0 |
| `presentation.trackpad.MultiTouchGestureTrackerTest` | 32 | 0 | 0 |
| `presentation.trackpad.TrackpadViewModelTest` | 7 | 0 | 0 |
| `presentation.util.GestureConfigTest` | 8 | 0 | 0 |
| **합계** | **86** | **0** | **0** |

컴파일(kapt/Hilt 포함)까지 실제로 통과했으므로 **빌드 검증 완료** 상태다 (정적 검토가 아님).

---

## 6. 남은 이슈 / 후속 필요 사항

| # | 항목 | 비고 |
|---|------|------|
| 1 | **AGENTS.md 갱신 필요** | 섹션 4 SCROLL 주석의 "(Phase 2, 미구현)" → 구현됨, 섹션 5 표의 "2손가락 상하좌우 드래그" ⬜ → ✅, 섹션 5 감도 상수 목록에 `SCROLL_SENSITIVITY_PX_PER_STEP` 추가, 섹션 6 Phase 2 체크박스, 섹션 7 세션 흐름의 `TCP: SCROLL` 주석. **이번 작업 범위에서 손대지 않음** (리더/문서 커밋 파트) |
| 2 | 감도 기본값 실기기 미검증 | `SCROLL_SENSITIVITY_PX_PER_STEP = 40f`는 계산 근거 기반 추정치. 실기기 체감은 request.md 명시대로 범위 밖 — Phase 3 감도 설정 UI에서 조정 |
| 3 | 부호 규약 실기기 미검증 | 앱은 "손가락 방향 = 부호"로 단순 매핑만 한다. 자연스러운 스크롤(반전) 여부는 서버 `_scroll()` 한 곳에서 뒤집도록 스펙이 잡혀 있으므로, 실기기에서 뒤집혀 느껴지면 **서버만** 고치면 된다. 앱 쪽 부호는 프로토콜 정의 그대로 유지할 것 |
| 4 | 수평 스크롤 트리거 조건 | 현재 dx/dy를 독립적으로 누적한다(축 잠금 없음). 대각선 드래그는 수직·수평 휠이 동시에 나간다. 실기기에서 의도치 않은 수평 스크롤이 거슬리면 "주축 잠금(axis lock)" 도입을 별도 이슈로 검토 |
| 5 | 서버 sub-pixel 잔차 (기존 백로그) | `pc_server/input_controller.py` `_move`의 잔차 미누적 문제는 **별개 이슈로 유지**. 이번 SCROLL은 앱 쪽에서 잔차를 완전히 처리했으므로 영향 없음 |
| 6 | Compose UI 계층 테스트 없음 | `TrackpadScreen`의 `onScroll` 배선 자체는 유닛 테스트 대상이 아니다(트래커·ViewModel 양끝은 커버됨). 기존 프로젝트 방침과 동일 |
