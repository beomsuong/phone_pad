# android-dev 요약 — 1손가락 더블탭 → DOUBLE_CLICK (Phase 3)

확정 스펙(`_workspace/00_input/request.md`)의 "Android 구현 대상" 7항목을 전부 구현했습니다.
필드명·상수명·구조는 스펙 그대로 유지했습니다.

## 변경 파일

### 신규
| 파일 | 내용 |
|------|------|
| `presentation/trackpad/DoubleTapDetector.kt` | 순수 Kotlin 더블탭 판정 상태 머신 |
| `app/src/test/.../presentation/trackpad/DoubleTapDetectorTest.kt` | 더블탭 판정 테스트 13종 |

### 수정
| 파일 | 내용 |
|------|------|
| `presentation/util/GestureConfig.kt` | `DOUBLE_TAP_INTERVAL_MS = 300L`, `DOUBLE_TAP_DISTANCE_PX = 40f` 추가 |
| `presentation/trackpad/MultiTouchGestureTracker.kt` | `GestureEndDecision`에 `x`/`y` 추가. 판정 로직 자체는 불변 |
| `presentation/trackpad/TrackpadScreen.kt` | `coroutineScope` 래핑 + 지연/병합 클릭 로직, `TrackpadSurface`에 `onDoubleClick` 배선 |
| `domain/model/TrackpadEvent.kt` | `data class DoubleClick(val button: String = "left")` 추가 |
| `data/repository/TrackpadRepositoryImpl.kt` | `DoubleClick` → TCP 직렬화 (실패 시 `Error`) |
| `presentation/trackpad/TrackpadViewModel.kt` | `sendDoubleClick()` 추가 |
| 기존 테스트 3종 | `GestureConfigTest`, `TrackpadRepositoryImplTest`, `TrackpadViewModelTest`, `MultiTouchGestureTrackerTest`에 케이스 추가 |

## 와이어 포맷 (확정 스펙 그대로)

```jsonc
{"type":"DOUBLE_CLICK","button":"left"}
```
TCP 9000, newline-delimited. `session` 필드 없음 (CLICK과 동일 등급의 평문 이벤트).
리터럴은 `TrackpadRepositoryImplTest`에서 문자열 비교로 고정했습니다.

## 실제 구현한 지연/병합 로직

### 1. `DoubleTapDetector` — "언제 두 탭이 하나인가"만 판정

```
onTap(x, y, timestampMs): Boolean
```
직전 탭 **하나만** 기억합니다. `0 <= elapsed <= DOUBLE_TAP_INTERVAL_MS` **이고**
`hypot(dx, dy) <= DOUBLE_TAP_DISTANCE_PX`이면 `true`를 돌려주고 **즉시 내부 상태를 비웁니다.**
아니면 이 탭을 새 "직전 탭"으로 기억하고 `false`.

- 확정 즉시 리셋하는 이유: 안 하면 3번 탭했을 때 `A+B`와 `B+C`가 각각 더블클릭으로 잡혀
  더블클릭이 두 번 나갑니다. 테스트로 고정했습니다(`네 번 탭하면 더블탭이 정확히 두 번 확정된다`).
- 거리는 축별이 아니라 **직선 거리(hypot)** 기준입니다.
- 음수 간격(시각 역행)은 짝으로 인정하지 않습니다 — `System.currentTimeMillis()`는 단조 증가가
  보장되지 않아서, `elapsed <= INTERVAL`만 보면 아무리 오래된 탭과도 묶일 수 있습니다.
- 시간 축(지연 전송)은 이 클래스의 책임이 아닙니다. 그래서 코루틴 없이 JUnit만으로 전부 검증됩니다.

### 2. `MultiTouchGestureTracker` — 탭 위치를 함께 반환

`GestureEndDecision`에 `x`, `y`(기본값 `0f`)를 **뒤에 덧붙여** 기존 호출부/테스트를 깨지 않았습니다.
기존 `resolveClickButton`을 `resolveTap`으로 바꾸면서 `TapResolution(button, x, y)`를 돌려주게 했는데,
**분기 조건과 판정식은 한 글자도 바꾸지 않았습니다** — 각 분기가 원래 쓰던 구간의 시작 좌표를
함께 싣기만 합니다. 꼬리 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`) 경로에서는 꼬리가 아니라
**직전 구간의 시작 좌표**를 돌려주도록 `prevStartX`/`prevStartY`를 새로 추적합니다
(판정 기준과 좌표 기준이 어긋나지 않게).

### 3. `TrackpadScreen` — 지연된 단일 클릭 job

`pointerInput(Unit) { coroutineScope { ... awaitEachGesture { ... } } }` 구조입니다.
`coroutineScope`를 한 겹 두른 이유는, 지연 클릭 job이 **자기를 만든 제스처보다 오래 살아야** 하기
때문입니다 — 대기 중에 들어오는 "두 번째 탭"은 이미 다음 제스처이고, `awaitEachGesture` 블록
안에서 `launch`하면 제스처마다 스코프가 달라 그 job을 가로질러 취소할 수 없습니다.

1손가락 탭(`BUTTON_LEFT`)이 끝났을 때:
1. 대기 중인 `pendingClickJob`을 **먼저 무조건 취소** (어느 쪽으로 판정되든)
2. `doubleTapDetector.onTap(end.x, end.y, lastTimestamp)`
   - `true` → `onDoubleClick()` 즉시 호출 (미뤄뒀던 CLICK은 1번에서 이미 취소되어 사라짐)
   - `false` → `launch { delay(DOUBLE_TAP_INTERVAL_MS); onClick() }`로 새로 예약

2손가락 탭(우클릭)은 이 로직을 전혀 거치지 않고 기존처럼 즉시 `onRightClick()`입니다.

**의도된 대가:** 모든 1손가락 좌클릭에 300ms 지연이 생깁니다. request.md에 적힌 대로
더블클릭을 지원하는 구조에서 피할 수 없는 트레이드오프입니다.

### 4. 채널 원칙 준수

`DoubleClick`은 이동 좌표가 아니므로 **TCP**입니다. MOVE만 UDP라는 AGENTS.md 섹션 4 원칙을
유지했고, "UDP로 새지 않는지"를 테스트로 고정했습니다. 전송 실패 시에는 CLICK과 같은 등급으로
`ConnectionState.Error`를 세팅합니다(MOVE/SCROLL처럼 조용히 버리지 않음) — 저빈도 · 사용자
명시 행동이라 watchdog이 세팅한 원인 메시지를 덮어쓸 위험이 사실상 없기 때문입니다.

## 테스트

### `DoubleTapDetectorTest` (신규 13종)
임계값을 하드코딩하지 않고 전부 `GestureConfig`에서 파생시켰습니다.

- 첫 탭은 언제나 false
- 간격·거리 모두 이내 → 두 번째 탭에서 true / 완전히 같은 좌표도 true
- **간격 초과** → false + 두 번째 탭이 새 직전 탭이 됨(이어지는 세 번째 탭이 묶이는지로 확인)
- **간격 경계값**(정확히 `DOUBLE_TAP_INTERVAL_MS`)은 "이내"로 인정
- **거리 초과** → 간격이 이내여도 false
- **거리 경계값**(정확히 `DOUBLE_TAP_DISTANCE_PX`)은 "이내"로 인정
- 거리 판정이 축별이 아니라 직선 거리 기준인지 (각 축 0.8D → 합성 1.13D는 초과)
- 거리 초과로 실패한 탭도 새 직전 탭으로 갱신되는지
- **확정 후 리셋**: A-B가 더블탭이면 C는 새로 시작 / 네 번 탭 → `[false, true, false, true]`
- `reset()` 후 다음 탭이 첫 탭이 되는지
- 시각 역행(음수 간격) 방어

### 기존 테스트 확장
- `GestureConfigTest` +3: 상수값 고정(300L/40f), `DOUBLE_TAP_DISTANCE_PX > TAP_MAX_DISTANCE_PX`(정확히 2배),
  `DOUBLE_TAP_INTERVAL_MS > TAP_MAX_DURATION_MS`
- `TrackpadRepositoryImplTest` +4: 와이어 리터럴 고정, 기본 button=left, CLICK 두 개로 쪼개지지 않음, 실패 시 Error
- `TrackpadViewModelTest` +3: `sendDoubleClick`이 `DoubleClick(left)`를 보냄, CLICK을 보내지 않음, 둘이 구분됨
- `MultiTouchGestureTrackerTest` +3: 탭 위치가 구간 시작 좌표인지, 떨림이 있어도 고정인지,
  꼬리 보정 시 직전 구간 좌표를 쓰는지

### 실행 결과

```
$ cd phone_pad_app && ./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL in 46s
31 actionable tasks: 13 executed, 18 up-to-date
```

| 클래스 | 테스트 | 실패 |
|--------|--------|------|
| ExampleUnitTest | 1 | 0 |
| SessionHandshakeTest | 8 | 0 |
| TcpClientTest | 4 | 0 |
| TrackpadRepositoryHeartbeatTest | 12 | 0 |
| TrackpadRepositoryImplTest | 18 | 0 |
| SendEventUseCaseTest | 1 | 0 |
| **DoubleTapDetectorTest (신규)** | **13** | **0** |
| MultiTouchGestureTrackerTest | 36 | 0 |
| TrackpadViewModelTest | 10 | 0 |
| GestureConfigTest | 11 | 0 |
| **합계** | **114** | **0** |

`compileDebugKotlin`이 통과했으므로 `coroutineScope { awaitEachGesture { ... } }` 중첩에서
`PointerInputScope`(바깥)와 `CoroutineScope`(안쪽) 리시버가 모두 정상 해석됩니다 —
`awaitEachGesture`는 바깥 리시버로, `launch`는 안쪽 리시버로 잡힙니다.

**회귀 없음:** 1손가락 드래그(MOVE), 2손가락 우클릭, 2손가락 스크롤 관련 기존 테스트 전부 통과.

## 남은 이슈

1. **실기기 미검증** — 단위 테스트만 통과했고 실제 기기에서의 체감(300ms 클릭 지연이 답답한지,
   40px 허용 거리가 충분한지)은 확인하지 못했습니다. 두 상수 모두 `GestureConfig`에 분리되어 있어
   조정은 한 곳만 고치면 됩니다.

2. **경계값 300ms 근처의 좁은 경합** — 두 번째 탭이 정확히 `DOUBLE_TAP_INTERVAL_MS` 시점에
   들어오면, `delay(300)`이 이미 깨어나 `onClick()`을 실행한 뒤일 수 있습니다. 그 경우
   CLICK과 DOUBLE_CLICK이 모두 나갑니다. 판정 시각(`System.currentTimeMillis()`)과
   코루틴 `delay`의 시간축이 서로 다르므로 구조적으로 완전히 없앨 수는 없고, 실제로 문제가 되면
   판정을 `elapsed < INTERVAL`(경계 배제)로 좁히는 선택지가 있습니다.
   확정 스펙이 "이내"라고 명시해서 현재는 경계 포함으로 두었습니다.

3. **좌클릭 직후 우클릭의 순서 역전** — 1손가락 탭 후 300ms 안에 2손가락 탭을 하면,
   우클릭이 먼저 나가고 지연된 좌클릭이 그 뒤에 도착합니다. 스펙이 "우클릭은 이 로직과 완전히
   무관"이라고 못박아서 우클릭 경로에서 `pendingClickJob`을 건드리지 않았습니다.
   실사용 빈도가 낮은 조합이지만, 필요하면 우클릭 분기에서도 대기 job을 flush/취소하는 방식으로
   정리할 수 있습니다. **스펙 변경 사항이라 임의로 하지 않았습니다.**

4. **화면 전환 시 대기 중인 클릭 유실** — `pointerInput` 스코프가 취소되면(연결 해제 등)
   대기 중이던 지연 클릭은 전송되지 않고 사라집니다. 최대 300ms 이내의 클릭이라
   실질적 영향은 없다고 판단했습니다.

5. **`AGENTS.md` 갱신 필요** — 섹션 4의 `// 더블클릭 (Phase 2, 미구현)` 주석, 섹션 5 표의
   `1손가락 더블탭 → ⬜ 미구현`, 섹션 5 감도 상수 목록(신규 2개 누락), 섹션 6 Phase 3 체크박스를
   갱신해야 합니다. 이 세션은 소스/테스트만 건드렸습니다.
