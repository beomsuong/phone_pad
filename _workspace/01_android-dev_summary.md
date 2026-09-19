# android-dev 작업 요약 — 탭홀드 + 드래그 → DRAG_START / DRAG_END (Phase 3)

## 결과 한 줄
1손가락 제자리 홀드 200ms 승격 → `DRAG_START`, 이후 이동은 기존 MOVE 그대로, 해제 시 `DRAG_END`.
승격 판정은 순수 클래스 `DragHoldDetector`로 분리했고, 전체 149개 단위 테스트 통과(회귀 없음).

## 확정 스펙 (server-dev / protocol-qa 공유용)

TCP 9000, newline-delimited JSON. **필드 없음, session 없음** (CLICK/SCROLL과 같은 평문 이벤트).

```jsonc
{"type":"DRAG_START"}
{"type":"DRAG_END"}
```

- 드래그 중 이동은 새 이벤트가 아니라 **기존 MOVE(UDP 9001)** 를 그대로 사용 — 서버 `_move` 무변경.
- 앱이 보내는 순서 보장: `DRAG_START` → (0개 이상의 MOVE) → `DRAG_END`.
- 승격되지 않은 제스처는 이전과 100% 동일하게 동작(MOVE만, 버튼 없음).
- 와이어 리터럴은 `TrackpadRepositoryImpl`의 `DRAG_START_JSON` / `DRAG_END_JSON` 상수에 고정되어 있고,
  `TrackpadRepositoryImplTest`가 문자열 그대로를 assert 한다.

## 변경 파일 목록

### 신규
| 파일 | 내용 |
|------|------|
| `app/src/main/java/com/example/phone_pad_app/presentation/trackpad/DragHoldDetector.kt` | 승격 판정 순수 클래스 + `DragHoldSignal` enum (None/Start/End) |
| `app/src/test/java/com/example/phone_pad_app/presentation/trackpad/DragHoldDetectorTest.kt` | 승격 경계 조건 테스트 24개 |

### 수정
| 파일 | 내용 |
|------|------|
| `presentation/util/GestureConfig.kt` | `DRAG_HOLD_THRESHOLD_MS: Long = TAP_MAX_DURATION_MS` 추가 (숫자 중복 없이 참조) |
| `domain/model/TrackpadEvent.kt` | `object DragStart`, `object DragEnd` 추가 (필드 없음) |
| `data/repository/TrackpadRepositoryImpl.kt` | `when` 분기 2개 + 와이어 리터럴 상수. 전송 실패 시 CLICK과 동급으로 `ConnectionState.Error` |
| `presentation/trackpad/TrackpadViewModel.kt` | `sendDragStart()` / `sendDragEnd()` (UseCase 경유) |
| `presentation/trackpad/TrackpadScreen.kt` | 타이머 경합 루프, `handleDragHold()`, `TrackpadSurface` 콜백 배선, 안내 문구에 "길게 눌렀다 움직여 드래그" 추가 |
| `app/src/test/.../presentation/util/GestureConfigTest.kt` | 상수 관계 테스트 3개 추가 |
| `app/src/test/.../data/repository/TrackpadRepositoryImplTest.kt` | 직렬화/실패 처리 테스트 5개 추가 |
| `app/src/test/.../presentation/trackpad/TrackpadViewModelTest.kt` | 이벤트 번역 테스트 3개 추가 |

## 실제 구현한 타이머 / 승격 로직

### 1. 타이머 경합 (`TrackpadScreen.kt`)
손가락이 완전히 정지하면 Android가 MotionEvent를 아예 주지 않으므로, 이벤트만 기다려서는 "제자리 유지 시간"을 잴 수 없다. 그래서 루프 매 회차마다:

```kotlin
val remainingHold = dragHold.remainingHoldMs(System.currentTimeMillis())
val event = if (remainingHold == null) awaitPointerEvent()
            else withTimeoutOrNull(remainingHold) { awaitPointerEvent() }
if (event == null) { handleDragHold(dragHold.onHoldTimeout(now)); continue }
```

- 여기서 쓰는 `withTimeoutOrNull`은 **kotlinx의 것이 아니라 `AwaitPointerEventScope`의 멤버**다(import 없이 해석됨 — import를 지우고 재컴파일해 확인). 포인터 입력 스코프 전용 구현이라 타임아웃이 제스처 루프 자체를 취소하지 않는다.
- `remainingHoldMs()`는 승격 후보가 아니면(이미 승격 / 거리 초과로 탈락 / 종료) `null`을 돌려주므로, 그 뒤로는 기존과 동일하게 타임아웃 없이 대기한다 → **기존 MOVE/CLICK 경로의 대기 특성이 그대로 유지**된다.
- 임계를 이미 넘긴 경우 음수가 아니라 `0L`을 돌려준다(`withTimeoutOrNull(0)`은 즉시 null → 한 회차 만에 승격 후 정착, 무한 루프 없음).

### 2. 승격 판정 (`DragHoldDetector`, 순수 Kotlin)
- `onGestureStart(x, y, t)` — 첫 down 시점에 무장. 기준 좌표/시각 고정.
- `onPointerEvent(count, x, y, t)` — 개수≠1이면 즉시 해제(`End`), 시작점에서 `TAP_MAX_DISTANCE_PX` 초과 시 **sticky 탈락**(되돌아와도 승격 없음, 트래커의 `isDrag` 규약과 동일), 시간이 찼으면 승격(떨림 이벤트로 타이머가 계속 갱신되는 경우 대비).
- `onHoldTimeout(t)` — 제자리 유지 확인 → `elapsed >= DRAG_HOLD_THRESHOLD_MS`면 `Start`.
- `onGestureEnd()` — 활성이었으면 `End`, **멱등**(정상 경로 + `finally` 양쪽에서 호출).
- `hasPromoted` — 이 제스처가 승격된 적 있는지. 호출부가 클릭/더블탭 판정을 통째로 건너뛰는 근거(스펙 7번).

### 3. 엣지 케이스 처리
| 상황 | 처리 |
|------|------|
| 손가락 개수 변화(1→2) | 즉시 `DRAG_END`. **그 제스처 안에서는 재무장하지 않음** |
| 화면 밖 이탈 / 제스처 취소 / 컴포저블 파기 | `awaitEachGesture` 본문을 `try/finally`로 감싸 `finally`에서 `onGestureEnd()` → 활성이었으면 `DRAG_END` |
| 승격된 제스처의 종료 | `DRAG_END`만. `hasPromoted` 검사로 CLICK/DOUBLE_CLICK 판정 자체를 생략 |
| 승격 직후 안 움직이고 뗌 | `DRAG_START` → `DRAG_END` (스펙 8번, 서버 관점에서 좌클릭 down/up과 동일) |
| 대기 중인 지연 클릭이 있는데 드래그 승격 | 지난 라운드 `flushPendingClick()` 패턴 그대로 **즉시 발사**(취소 아님) + `doubleTapDetector.reset()` — F-1/F-4와 같은 이유 |

**"재무장하지 않음"을 택한 근거(설계 결정):** 1→2→1로 돌아왔을 때 다시 무장하면, 2손가락 스크롤 후 손가락을 하나씩 떼는 꼬리 구간이 "제자리 1손가락 유지"로 보여 **스크롤 직후 드래그가 오발동**한다. 지난 라운드 F-2(스크롤 뒤 좌클릭 오발동)와 정확히 같은 함정이라 같은 방향으로 막았다. 대가로 "2손가락 → 1손가락으로 줄인 뒤 홀드 드래그"는 동작하지 않는다(스펙 범위 밖).

### 4. 탭 판정과의 상호 배타성
`DRAG_HOLD_THRESHOLD_MS == TAP_MAX_DURATION_MS`이고 트래커의 탭 조건은 `elapsed < TAP_MAX_DURATION_MS`, 승격 조건은 `elapsed >= DRAG_HOLD_THRESHOLD_MS`라 **구조적으로 겹치지 않는다**(경계 200ms는 드래그 쪽). 실제 두 클래스를 함께 돌려 이걸 고정하는 테스트를 넣었다. 그럼에도 꼬리 구간 보정 등으로 다른 구간이 탭으로 판정될 여지를 남기지 않기 위해 `hasPromoted` 가드를 이중으로 두었다.

## 테스트 목록

### `DragHoldDetectorTest` (신규, 24개)
승격: 제자리+임계 도달 / 임계 직전 미승격 / 경계값 200ms는 승격 / 탭 한계 거리 이내 떨림은 승격 허용 / 떨림 이벤트 경로 승격
미승격: 시간 전 거리 초과 → 영영 미승격 / 거리 초과 sticky(복귀해도 미승격) / 승격 전 종료는 무신호
해제: 승격 후 뗌 → End / `onGestureEnd` 멱등 / 손가락 추가 → 즉시 End / 개수 변화 후 재무장 안 함 / 2손가락 시작은 후보 아님
승격 후: 이동해도 재발사 없음 / 승격 직후 뗌 = Start+End만
타이머: 무장 직후 남은 시간 = 임계 전체 / 초과 시 0(음수 아님) / 승격·탈락·종료 후 null
재사용: 새 제스처 시 이력 초기화 / `reset()`
배타성: 승격 제스처는 트래커가 탭으로 판정 안 함 / 짧은 탭은 승격 안 함

### 기존 파일 추가분
- `GestureConfigTest` +3: 임계 == `TAP_MAX_DURATION_MS`(==200L) / 해제 유예보다 김 / 더블탭 간격보다 짧음
- `TrackpadRepositoryImplTest` +5: `DRAG_START` 리터럴·TCP 전용·session 없음 / `DRAG_END` 동일 / 순서 구분 / 각각 전송 실패 → `Error`
- `TrackpadViewModelTest` +3: `sendDragStart`/`sendDragEnd` 번역(클릭 계열과 안 섞임) / 드래그 중 이동이 기존 MOVE 경로 사용

### 실행 결과
```
cd phone_pad_app && ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest
BUILD SUCCESSFUL
tests=149 skipped=0 failures=0 errors=0
```
테스트 스위트 11개 전부 통과 — `MultiTouchGestureTrackerTest`(36), `DoubleTapDetectorTest`(13), `TrackpadRepositoryHeartbeatTest`(12) 등 기존 테스트 회귀 없음.
컴파일(`:app:compileDebugKotlin`)·kapt까지 실제로 통과했으므로 **빌드 미검증 상태 아님**. 단, 실기기 동작은 미검증.

## 남은 이슈 / server-dev·protocol-qa 확인 요청

1. **`TrackpadScreen`의 코루틴 타이밍 자체는 여전히 자동 테스트 없음.** 승격 "판정"은 전부 `DragHoldDetector`로 빼서 테스트했지만, `withTimeoutOrNull` 경합·`try/finally` 배선·`flushPendingClick` 호출 순서는 Compose에 묶여 있어 단위 테스트가 없다. 지난 라운드 대비 커버리지는 늘었으나 AGENTS.md 섹션 10의 해당 미결 항목은 **부분 해소**에 그친다.
2. **재무장 안 함 정책**(위 3번 표)은 스펙에 명시되지 않은 내 판단이다. protocol-qa가 다르게 봐야 한다고 판단하면 재검토 필요.
3. **화면 밖 이탈 경로는 실기기 미검증.** `finally` 안전망은 넣었지만, Compose가 그 상황에서 어떤 순서로 이벤트를 주는지는 코드상으로만 확인했다. 서버 측 "연결 종료 시 강제 LEFTUP" 안전장치가 최종 방어선으로 반드시 있어야 한다.
4. **승격 직후 첫 MOVE 사이의 서버 처리 순서 의존.** `DRAG_START`는 TCP, 뒤따르는 MOVE는 UDP라 이론상 UDP가 먼저 도착할 수 있다(채널이 다르므로 순서 보장 없음). 이 경우 버튼이 눌리기 직전에 커서가 조금 움직이는 정도라 영향은 미미하지만, 구조적 한계로 기록해 둔다.
5. 서버가 `DRAG_START`/`DRAG_END`를 **멱등**으로 처리한다는 전제에 의존한다(취소 경로에서 `DRAG_END`가 중복 전송될 이론적 여지는 `onGestureEnd` 멱등성으로 앱에서도 막았지만, 이중 방어가 맞다).
