# android-dev 작업 요약 — 감도 설정 화면 + DataStore 영속화 (Phase 3 마지막 2개 항목)

작업 디렉토리: `C:\Github\phone_pad\.claude\worktrees\settings-ui-datastore` (워크트리 내에서만 작업, 원본 체크아웃 미접근)
커밋하지 않음 (리더가 처리). `AGENTS.md`/`CLAUDE.md` 미수정.

**프로토콜 무변경 확인**: 이벤트 `type`/필드/채널/세션을 건드린 곳이 없다.
`domain/model/TrackpadEvent.kt`, `data/repository/TrackpadRepositoryImpl.kt`, `data/network/*`, `pc_server/*` 전부 미수정 —
`git status`에도 나타나지 않는다. 이번 변경은 "Android가 dx/dy와 스크롤 스텝을 **어떤 값으로 계산하느냐**"에만 닿는다.

---

## 1. 변경/추가 파일

### 추가 (main)
| 파일 | 역할 |
|------|------|
| `app/src/main/java/.../domain/model/GestureSettings.kt` | 값 객체 + 허용 범위 + clamp(`sanitized()`). 순수 Kotlin |
| `app/src/main/java/.../domain/repository/SettingsRepository.kt` | 인터페이스 (`settings: Flow`, `update`, `reset`) |
| `app/src/main/java/.../data/repository/DataStoreSettingsRepository.kt` | DataStore(Preferences) 구현. 읽기·쓰기 양쪽에서 clamp, `IOException` → `emptyPreferences()` |
| `app/src/main/java/.../di/DataStoreModule.kt` | `DataStore<Preferences>` 제공(@Singleton, 손상 시 빈 Preferences로 복구) |
| `app/src/main/java/.../presentation/settings/SettingsScreen.kt` | 슬라이더 2개 + "기본값으로 복원" + 뒤로가기(`BackHandler`) |
| `app/src/main/java/.../presentation/settings/SettingsViewModel.kt` | 로드/저장/리셋 |
| `app/src/main/java/.../presentation/settings/SettingsUiState.kt` | `settings` + `isLoaded` |
| `app/src/main/java/.../presentation/settings/ScrollSpeedSlider.kt` | "스크롤 속도" 슬라이더 방향 뒤집기(순수 함수, Compose 비의존) |

### 수정 (main)
| 파일 | 변경 |
|------|------|
| `app/build.gradle.kts` | `androidx.datastore:datastore-preferences:1.0.0` 추가 (그 외 의존성 추가 없음 — Navigation/material-icons-extended 미추가) |
| `presentation/util/GestureConfig.kt` | 범위 상수 4개 추가(`MOVE_SENSITIVITY_MIN/MAX`, `SCROLL_PX_PER_STEP_MIN/MAX`) + 두 기본값 상수의 KDoc 보강. **기존 상수는 삭제/개명/값 변경 없음** |
| `presentation/trackpad/MultiTouchGestureTracker.kt` | 배율·px/step을 생성자 파라미터로 주입(기본값 = GestureConfig 상수). `GestureSettings` 편의 생성자 추가. `scrollPxPerStep > 0` require |
| `presentation/trackpad/TrackpadScreen.kt` | `SettingsViewModel` 구독, 상태 기반 설정 화면 전환, ConnectPanel에 "감도 설정" 버튼, `TrackpadSurface`에 감도 전달 + `pointerInput(moveSensitivity, scrollPxPerStep)` |
| `di/AppModule.kt` | `SettingsRepository` 바인딩 추가 |

### 추가/수정 (test)
| 파일 | 내용 |
|------|------|
| `test/.../domain/model/GestureSettingsTest.kt` (신규, 10) | 기본값 == GestureConfig, 범위 불변식, clamp, NaN/±Inf → 기본값, 필드 독립성, 멱등성 |
| `test/.../data/repository/DataStoreSettingsRepositoryTest.kt` (신규, 9) | 임시 파일 기반 실제 왕복, 미저장 시 기본값, 저장 시/읽기 시 clamp, reset, 키 이름 고정 |
| `test/.../presentation/settings/SettingsViewModelTest.kt` (신규, 7) | 로드/부분 변경/연속 변경/리셋, MockK 호출 검증 |
| `test/.../presentation/settings/ScrollSpeedSliderTest.kt` (신규, 5) | 역함수 관계, 양 끝 스왑, 단조성, 범위 유지 |
| `test/.../presentation/trackpad/MultiTouchGestureTrackerSettingsTest.kt` (신규, 9) | 기본 생성자 == 상수, 배율 2배 → dx 2배, px/step 절반 → 스텝 2배, 잔차 누적, 판정(탭/우클릭) 불변, 0 이하 거부 |
| `test/.../presentation/util/GestureConfigTest.kt` (수정, +3) | 범위가 기본값을 포함, 이동 하한 > 0, **스크롤 하한 > `TAP_MAX_DISTANCE_PX`** |

그 외: `phone_pad_app/local.properties`를 로컬 빌드용으로 생성(`sdk.dir`). `.gitignore` 대상이라 커밋되지 않는다.

---

## 2. 설계 결정과 근거

1. **조정 값은 2개로 제한** (확정 스펙 그대로). 나머지 상수는 서로 얽힌 불변식이 있어 무변경.
   스크롤 하한 25f가 `SCROLL_SENSITIVITY_PX_PER_STEP > TAP_MAX_DISTANCE_PX`(20f) 불변식을 슬라이더로도 못 깨게 막는다 —
   이 사실을 `GestureConfigTest`와 `GestureSettingsTest` 양쪽에서 고정했다.

2. **clamp는 도메인에**, ViewModel은 보정하지 않는다. `coerceIn`이 NaN을 **그대로 통과시키는** 점(비교 연산 기반)이 핵심 함정이라
   유한성 검사를 먼저 한다. NaN 배율이 판정기에 들어가면 모든 델타가 NaN이 되어 커서가 영원히 멈춘다.
   `±Infinity`도 스펙대로 경계가 아니라 기본값으로 되돌린다.

3. **`reset()`은 기본값을 쓰지 않고 키를 지운다.** 나중에 기본 상수가 바뀌면 "복원"한 사용자가 새 기본값을 따라가야 맞고,
   "명시적으로 고른 값만 저장돼 있다"는 상태가 더 단순하다.

4. **트래커는 값만 주입받는다** — `GestureSettings`(순수 Kotlin) 외에 Compose/DataStore/Flow를 전혀 모른다.
   생성자 기본값을 GestureConfig 상수로 둬서 **기존 `MultiTouchGestureTrackerTest`(36개)가 무수정 통과**한다.
   `scrollPxPerStep <= 0`은 `require`로 막았다(0으로 나누면 Infinity 스텝이 서버로 나간다).

5. **`pointerInput` key에 감도 두 값을 포함.** 지금은 설정을 연결 전에만 바꿀 수 있어 실제로 이 경로를 타지 않지만,
   key가 `Unit`이면 값이 바뀌어도 블록이 재시작되지 않아 **낡은 값이 캡처된 채 조용히 틀린 감도로 동작**한다.
   트래커 자체는 제스처 시작 시점 값으로 고정해(같은 스와이프 중 배율이 바뀌지 않게) 만든다.

6. **화면 전환은 상태 + `BackHandler`** — Navigation 의존성 미추가. 설정은 `Disconnected`/`Error`에서만 열리고,
   연결 상태가 살아나면(`settingsAvailable == false`) 자동으로 트랙패드로 돌아간다. Connected 화면에는 버튼을 두지 않았다(제스처 표면 보호).
   아이콘 대신 텍스트 버튼("감도 설정", "← 뒤로")을 써서 material-icons-extended를 들이지 않았다.

7. **스크롤 슬라이더 방향 뒤집기**를 `ScrollSpeedSlider`(범위 중심 대칭, 자기 자신의 역함수)로 분리했다.
   UI에서 즉석 계산하면 한 방향만 고치고 반대를 잊어 "설정을 열 때마다 값이 반대편으로 튀는" 버그가 나기 쉽다.

8. **저장은 `onValueChangeFinished`에서만.** 드래그 중에는 로컬 상태만 움직인다(매 프레임 디스크 쓰기 금지).
   저장 시에는 `uiState`가 아니라 `settings.first()`로 최신 저장값을 읽어 한 필드만 바꾼다 — `uiState`는 `WhileSubscribed`라
   구독자가 없는 동안 값이 멈춰 있을 수 있다.

9. **`isLoaded`가 false인 동안 슬라이더 비활성화** — 디스크 값이 한 프레임 늦게 도착하는 사이에 사용자가 기본값을 만져 덮어쓰는 것을 막는다.

---

## 3. 테스트 실행 결과 (실제 출력 기준)

```
cd phone_pad_app && ./gradlew :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 21s
```
`app/build/test-results/testDebugUnitTest/*.xml` 집계: **TOTAL=192, FAILURES=0** (신규 40 + 기존 152)

| 클래스 | 결과 |
|--------|------|
| GestureSettingsTest | 10 / 0 fail |
| DataStoreSettingsRepositoryTest | 9 / 0 fail |
| SettingsViewModelTest | 7 / 0 fail |
| ScrollSpeedSliderTest | 5 / 0 fail |
| MultiTouchGestureTrackerSettingsTest | 9 / 0 fail |
| GestureConfigTest | 17 / 0 fail (기존 14 + 3) |
| MultiTouchGestureTrackerTest (무수정) | 36 / 0 fail |
| 기존 나머지(TrackpadRepositoryImpl/Heartbeat, TcpClient, SessionHandshake, DoubleTap, DragHold, TrackpadViewModel, SendEventUseCase, Example) | 전부 0 fail |

`:app:assembleDebug`도 성공 — Compose 코드 컴파일과 **Hilt DI 그래프 검증**(kapt/Dagger)까지 통과했다는 뜻이다.
빌드 경고는 기존에 있던 `TcpClient.kt:35` opt-in 경고 하나뿐이고 이번 변경으로 새로 생긴 경고는 없다.

### 도중에 실제로 잡힌 실패 (해결 과정)
첫 실행에서 `DataStoreSettingsRepositoryTest` 2개가 실패했다:
```
java.io.IOException: Unable to rename ...\gesture_settings.preferences_pb.tmp.
  at androidx.datastore.core.SingleProcessDataStore.writeData(SingleProcessDataStore.kt:433)
```
원인은 우리 코드가 아니라 **DataStore 1.0.0 + Windows JVM 조합의 제약**이다. DataStore는 임시 파일에 쓴 뒤
`File.renameTo`로 갈아끼우는데, Windows의 `renameTo`는 대상 파일이 이미 있으면 실패한다(POSIX `rename`과 달리 덮어쓰기 불가).
즉 **"같은 파일에 두 번째로 쓰는" 모든 테스트가 이 환경에서만 실패**한다(첫 쓰기는 파일이 없어 성공).
→ 실제 파일 왕복(저장 → 인스턴스 종료 → 재생성 → 재읽기)은 실파일 테스트로 그대로 유지하고,
다중 쓰기가 필요한 2개(필드 독립성, reset의 키 제거)만 같은 `DataStore<Preferences>` 계약을 메모리로 구현한 대역으로 검증했다.
테스트 파일에 이유를 주석으로 남겼다.

---

## 4. 미해결 이슈 / 남은 리스크

1. **다중 쓰기 경로의 실파일 검증은 이 환경에서 불가** (위 참조). 실제 타깃인 Android(리눅스)에서는 `rename`이 원자적으로 덮어쓰므로
   정상 동작하지만, "설정을 두 번 바꿔 저장했을 때 파일이 제대로 갱신되는지"는 **실기기에서만 최종 확인 가능**하다.
   (DataStore 1.1+는 이 문제가 해결돼 있으나, Kotlin 1.8.10/AGP 8.1.3 라인 유지를 위해 1.0.0을 썼다.)
2. **`GestureSettings`(domain)가 `GestureConfig`(presentation.util)를 import**한다 — 계층 방향이 거꾸로다.
   확정 스펙이 "GestureConfig를 기본값의 단일 출처로 유지"하라고 못 박아서 그대로 따랐다(기존 `TrackpadUiState`도 같은 방식으로 참조 중).
   정리하려면 `GestureConfig`를 domain 쪽으로 옮겨야 하는데, 이는 스펙이 금지한 이동/개명이라 이번 범위 밖으로 남긴다.
3. **`SettingsScreen`에 Compose UI 테스트 없음** — 슬라이더 드래그/커밋 타이밍, `BackHandler` 동작은 자동 테스트가 없다.
   순수 로직(`ScrollSpeedSlider`, ViewModel)만 분리해 커버했다. 기존 `TrackpadScreen`의 코루틴 타이밍이 미커버인 것과 같은 한계.
4. **설정 변경이 `pointerInput`을 재시작시키는 경로는 미실행 코드에 가깝다** — 현재 UI에서는 연결 전에만 설정을 바꿀 수 있어
   Connected 상태에서 값이 바뀌는 일이 없다. 나중에 "연결 중 설정 진입"을 허용하면 이 경로가 처음으로 실제로 돌게 된다.
5. **감도 범위의 체감 타당성은 미검증** — 0.5~4.0배, 25~100px/step은 계산 근거만 있고 실기기 튜닝 결과가 아니다
   (AGENTS.md 섹션 10의 "SCROLL_SENSITIVITY_PX_PER_STEP 체감" 항목이 이 화면으로 조정 가능해졌을 뿐, 기본값 검증은 여전히 미완).

---

## 5. 실기기에서만 확인 가능한 항목

- 설정 변경 후 **앱을 완전히 종료했다 재실행**했을 때 값이 유지되는지 (DataStore 파일 영속성, 위 4-1과 연결)
- 슬라이더를 끝까지 올린 상태(4.0배)에서 커서가 실제로 쓸 만한지, 끝까지 내린 상태(0.5배)에서 답답하지 않은지
- 스크롤 하한(25px/step)에서 스크롤 진입이 튀지 않는지 — 불변식상 안전하지만 체감은 별개
- "감도 설정" 버튼이 연결 화면 레이아웃(소형 화면/키보드 올라온 상태)에서 가려지지 않는지
- 시스템 백 제스처로 설정 → 연결 화면 복귀가 자연스러운지 (앱 종료로 새지 않는지)
- 설정 화면을 열어 둔 채 화면 회전 시 상태 유지(`rememberSaveable`) 및 슬라이더 값 복원
