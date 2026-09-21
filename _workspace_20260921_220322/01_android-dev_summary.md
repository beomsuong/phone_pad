# android-dev 요약 — 3손가락 스와이프 → DESKTOP_SWITCH (Phase 5 첫 항목)

확정 스펙(`_workspace/00_input/request.md`의 "와이어 스펙" + "Android 스펙")을 **그대로** 구현했다.
스펙에서 벗어난 부분 없음. 커밋하지 않았고 `pc_server/`·`AGENTS.md`·`CLAUDE.md`는 건드리지 않았다.

## 와이어 스펙 (구현된 그대로)

```jsonc
// TCP 9000, newline-delimited, session 필드 없음, 공백 없음, 필드 순서 type → direction
{"type":"DESKTOP_SWITCH","direction":"left"}   // 왼쪽 데스크톱으로 전환 (= Ctrl+Win+Left)
{"type":"DESKTOP_SWITCH","direction":"right"}  // 오른쪽 데스크톱으로 전환 (= Ctrl+Win+Right)
```

- `direction`은 **전환 결과의 방향**이다(손가락 방향이 아님).
- 손가락 방향 → 와이어 방향 매핑은 **`MultiTouchGestureTracker.resolveDesktopSwitch()` 한 곳에만** 있다.
  - 손가락 **왼쪽** 스와이프 → `"right"`
  - 손가락 **오른쪽** 스와이프 → `"left"`
- 전송 채널: **TCP**(CLICK/DOUBLE_CLICK과 완전히 같은 경로 — `sendOverTcp()` → 실패 시 `reportConnectionLost`).
  MOVE/SCROLL의 "조용한 실패"가 아니다.

## 변경 파일

### 프로덕션 (`phone_pad_app/app/src/main/java/com/example/phone_pad_app/`)
| 파일 | 변경 |
|------|------|
| `domain/model/TrackpadEvent.kt` | `data class DesktopSwitch(val direction: String)` 추가 (`Click(button)`과 같은 스타일, 기본값 없음) |
| `data/repository/TrackpadRepositoryImpl.kt` | `sendEvent`의 `when`에 분기 추가 → `sendOverTcp("""{"type":"DESKTOP_SWITCH","direction":"${'$'}{event.direction}"}""")` |
| `presentation/util/GestureConfig.kt` | `THREE_POINTER_COUNT = 3`, `THREE_FINGER_SWIPE_MIN_DISTANCE_PX = 120f`, `THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE = 2f` 추가 (사용자 설정으로 열지 않음) |
| `presentation/trackpad/MultiTouchGestureTracker.kt` | `GestureDecision.desktopSwitch: String?` 추가, 3손가락 스와이프 판정(`resolveDesktopSwitch`), 구간당 1회 래치(`desktopSwitchEmittedInSegment`), **제스처 단위 3손가락 래치**(`threeFingerLatched`), companion `DIRECTION_LEFT`/`DIRECTION_RIGHT` |
| `presentation/trackpad/TrackpadViewModel.kt` | `sendDesktopSwitch(direction)` 추가 — UseCase 경유 (네트워크 직접 호출 없음) |
| `presentation/trackpad/TrackpadScreen.kt` | `onDesktopSwitch: (String) -> Unit` 배선, 3번째 손가락이 닿는 순간 `flushPendingClick()` + `doubleTapDetector.reset()`, 안내 문구 한 줄 추가 (최소 diff) |

### 테스트 (`phone_pad_app/app/src/test/java/com/example/phone_pad_app/`)
| 파일 | 변경 |
|------|------|
| `presentation/trackpad/MultiTouchGestureTrackerDesktopSwitchTest.kt` | **신규 23건** — 방향 매핑, 임계/우세 조건, 구간당 1회, 4손가락, 래치 |
| `data/repository/TrackpadRepositoryImplTest.kt` | +4건 — 와이어 리터럴 2종 고정, 방향 무변형, 전송 실패 → Error 합류 |
| `presentation/trackpad/TrackpadViewModelTest.kt` | +3건 — UseCase 위임, 방향 뒤집기 없음, 상수 일치 |
| `presentation/util/GestureConfigTest.kt` | +4건 — 상수 값 고정 + 불변식(`> TAP_MAX_DISTANCE_PX * 2`, `> DOUBLE_TAP_DISTANCE_PX`, 우세 배수 > 1) |

**기존 `MultiTouchGestureTrackerTest`(36건)는 한 줄도 고치지 않았다** — "래치가 1·2손가락 동작을 바꾸면 안 된다"는 스펙 6번의 기준을 그대로 만족.

## 설계 메모 (리뷰 포인트)

- **3손가락 래치**: `onPointerEvent`에서 `pointerCount >= 3`을 본 순간 `threeFingerLatched = true`. 이후 그 제스처가
  끝날 때까지(`onGestureEnd`/`reset`) MOVE·SCROLL·탭 판정(좌/우클릭)이 전부 억제되고 `DESKTOP_SWITCH`만 허용된다.
  `resolveTap()`의 맨 앞에서도 래치를 먼저 확인하므로 꼬리 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`) 분기 자체를 타지 않는다.
  → 유예 시간 튜닝에 의존하지 않고 `3→2→1→0` 꼬리의 클릭 누수를 원천 차단.
- **드래그 홀드**: 별도 코드 추가가 필요 없었다. `DragHoldDetector`는 `pointerCount != 1`이면 `release()`하고
  같은 제스처 안에서 **재무장하지 않는다**(기존 규칙). 3손가락이 닿으면 기존 경로가 `DRAG_END`를 내고 끝난다.
- **MOVE/SCROLL과 동시 방출 불가**: `desktopSwitch`는 정확히 3손가락 구간에서만 나오고, 그 구간은 정의상 래치가
  걸린 상태라 `move`/`scroll`이 구조적으로 null이다. 전용 테스트로 고정.
- **구간당 1회 / 제스처당 N회**: `desktopSwitchEmittedInSegment`는 `startSegment()`에서 리셋되므로
  3→2→3 처럼 구간이 새로 시작되면 다시 1회 발사 가능(스펙 4번의 "구간 단위 정의" 준수). 계속 미는 것만으로는 반복 전환 불가.
- **임계 판정은 "이상"**: `|dx| >= 120px` 이고 `|dx| >= 2 * |dy|`. 경계(정확히 120px, 정확히 2배)에서 발사되어 사각지대가 없다.

## 테스트 결과 (강제 재실행)

```
cd C:\Github\phone_pad\phone_pad_app
./gradlew --offline :app:cleanTestDebugUnitTest :app:testDebugUnitTest
→ BUILD SUCCESSFUL
```

XML 리포트(`app/build/test-results/testDebugUnitTest/*.xml`, 23개 suite) 집계:

| 항목 | 값 |
|------|-----|
| tests | **292** |
| failures | **0** |
| errors | **0** |
| skipped | **0** |

기준선 258 → 292 (**신규 34건**, **회귀 0**).

### 변이(mutation) 검사 — 새 테스트가 실제로 버그를 잡는지 확인
실제로 코드를 망가뜨려 돌려 보고 되돌렸다:

| 변이 | 결과 |
|------|------|
| 방향 매핑 뒤집기(`totalDx < 0f` → LEFT/RIGHT 교체) + `resolveTap`의 래치 검사 제거 | **9건 실패** (방향 8건 + `스와이프 후 손가락을 어긋나게 떼도 클릭이 새지 않는다`) |
| MOVE/SCROLL의 `!threeFingerLatched` 가드 2곳 제거 | **2건 실패** |

되돌린 뒤 최종 실행에서 292 passed / 0 failed 재확인.

## 미해결 이슈 / 한계

1. **빌드는 검증됨(컴파일·JVM 단위 테스트), 실기기는 미검증.** 특히 다음은 실기기에서만 확인 가능:
   - 실제 3손가락 터치에서 Android가 `pointerCount == 3`을 안정적으로 주는지(제조사 시스템 제스처와의 충돌 가능성 —
     일부 기기는 3손가락 스와이프를 스크린샷/분할화면 시스템 제스처로 가로챈다).
   - 120px 임계의 체감(화면 밀도에 따라 다름 — px 기준이라 고해상도 기기에서는 상대적으로 짧게 느껴진다).
2. **`TrackpadScreen`의 배선에는 여전히 자동 테스트가 없다**(Compose `awaitEachGesture` 의존 — AGENTS.md 섹션 10의
   기존 항목과 같은 한계). 판정 로직은 전부 순수 클래스로 분리해 커버했지만, "3번째 손가락이 닿을 때 pending click을
   flush 한다"는 화면 계층 규칙 자체는 코드 리뷰로만 확인했다.
3. **px 기준 임계값**: `THREE_FINGER_SWIPE_MIN_DISTANCE_PX`는 dp가 아니라 px다(기존 모든 제스처 상수와 동일한 규약).
   화면 밀도별 정규화는 이번 범위 밖 — 손대려면 전체 상수를 함께 옮겨야 한다.
4. **수직/4손가락 제스처는 범위 밖**(스펙대로). 4손가락은 래치 때문에 "아무 이벤트도 나가지 않는" 상태이며 테스트로 고정했다.
5. **서버 측 `direction` 검증에 의존하지 않는다**: Android는 `DIRECTION_LEFT`/`DIRECTION_RIGHT` 외의 값을 만들지 않지만,
   `TrackpadEvent.DesktopSwitch(direction)`는 임의 문자열을 받을 수 있다(타입으로 막지 않음 — `Click(button)`과 같은 스타일 유지).
   잘못된 값이 들어가면 서버가 조용히 무시한다(와이어 스펙).

## 리더가 AGENTS.md에 반영할 내용

**섹션 4 (통신 프로토콜) — TCP 이벤트 목록에 추가:**
```jsonc
// 가상 데스크톱 전환 — ✅ 구현됨 (Phase 5). 3손가락 수평 스와이프. session 필드 없음.
// direction은 "전환 결과의 방향"이며 손가락 방향이 아니다 — 손가락 왼쪽 스와이프 → "right",
// 오른쪽 스와이프 → "left"(Windows 정밀 터치패드 관례). 이 뒤집기는 Android의
// MultiTouchGestureTracker 한 곳에서만 하고 서버는 받은 값을 Ctrl+Win+Left/Right로 옮기기만 한다
// (섹션 10 "스크롤 방향 규약"과 같은 원칙 — 두 사이드가 같이 뒤집으면 원위치).
// direction이 "left"/"right" 소문자 정확 일치가 아니면 서버는 아무 키도 보내지 않고 조용히 무시한다.
{"type":"DESKTOP_SWITCH","direction":"left"}
{"type":"DESKTOP_SWITCH","direction":"right"}
```

**섹션 5 (제스처 설계) — 표 갱신:**
`| 3손가락 스와이프 | 가상 데스크톱 전환 등 | Phase 4 | ⬜ 미구현 |`
→ `| 3손가락 좌우 스와이프 | DESKTOP_SWITCH(direction) | Phase 5 | ✅ 완료 |`

**섹션 5 엣지 케이스 — 추가:**
> - **3손가락 래치**: 한 제스처(첫 down ~ 모든 손가락 up) 안에서 포인터가 한 번이라도 3개 이상이 되면,
>   그 제스처의 나머지 동안 MOVE·SCROLL·클릭(좌/우)이 전부 억제되고 `DESKTOP_SWITCH`만 허용된다.
>   3손가락을 어긋나게 떼면 `3→2→1→0` 꼬리가 생기는데, 그 꼬리가 `MULTI_TOUCH_RELEASE_GRACE_MS`(50ms)
>   **밖**이면 기존 로직이 "정상 탭"으로 취급해 스와이프 직후 좌/우클릭이 샌다. 래치는 유예 시간 튜닝에
>   의존하지 않고 이를 원천 차단한다. 드래그 홀드는 `DragHoldDetector`가 개수 변화 시 재무장하지 않으므로
>   추가 장치가 필요 없었다.
> - 데스크톱 전환은 **임계를 넘는 그 프레임에 즉시**(손을 뗄 때가 아니라) 발사되고, **한 구간에 최대 1회**다
>   (계속 밀어도 반복 전환 없음). 3→2→3처럼 구간이 새로 시작되면 다시 1회 가능.
> - 3손가락 "탭"(스와이프 없이 뗌)은 아무 이벤트도 없다(기존 동작 유지).

**섹션 5 감도 상수 블록 — 추가:**
```kotlin
THREE_POINTER_COUNT = 3                        // 3손가락 구간 판정 기준 (DESKTOP_SWITCH 전용)
THREE_FINGER_SWIPE_MIN_DISTANCE_PX = 120f      // 구간 시작 centroid 대비 수평 이동 임계.
                                               //   TAP_MAX_DISTANCE_PX(20)의 6배 — 전역 동작이라 오발동 여유를 크게 둔다
                                               //   (GestureConfigTest가 "> TAP_MAX_DISTANCE_PX*2" 및 "> DOUBLE_TAP_DISTANCE_PX"를 강제)
THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE = 2f   // |dx| >= 2*|dy| 일 때만 수평 스와이프로 인정 (약 26.6도 이내)
```
> 이 세 값은 **사용자 설정으로 열지 않는다**(기존 조정 가능 값은 여전히 `MOVE_SENSITIVITY`와
> `SCROLL_SENSITIVITY_PX_PER_STEP` 두 개뿐).

**섹션 6 로드맵 Phase 5 — `3손가락 스와이프 → 가상 데스크톱 전환` 체크.**

**섹션 7 세션 흐름 — 한 줄 추가:**
```
   |-- TCP: DESKTOP_SWITCH (3손가락 좌우 스와이프) -->  |   ✅ 구현됨 (Ctrl+Win+Left/Right)
```

**섹션 10 미결 사항 — 추가 후보:**
| 항목 | 현황 |
|------|------|
| 3손가락 제스처 실기기 검증 | 임계 120px의 체감, 제조사 시스템 제스처(스크린샷/분할화면 등)가 3손가락 터치를 가로채는지, 실제 터치에서 `pointerCount == 3`이 안정적으로 보고되는지 미검증. 임계값이 dp가 아니라 px라 고해상도 기기에서는 상대적으로 짧게 느껴질 수 있음 |
