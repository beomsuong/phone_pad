# 요청: 감도 설정 화면 + GestureConfig 영속화 (Phase 3 마지막 2개 항목)

사용자 요청: "이어서 작업해줘" → AGENTS.md 섹션 6 Phase 3의 남은 체크박스 2개
- [ ] 감도 설정 화면 (Android Settings Screen)
- [ ] `GestureConfig`를 DataStore로 영속화

## 범위 판단: **단일 사이드 (Android)**

사유: 이벤트 `type`/필드/채널/세션 변경이 전혀 없다. MOVE의 `dx/dy`와 SCROLL의 스텝 정수는
"Android가 값을 어떻게 계산하느냐"의 내부 문제이고 서버는 받은 값을 그대로 쓴다
(px→스텝 변환과 잔차 누적은 Android 전담 — AGENTS.md 섹션 4). → android-dev 서브 에이전트 1명, protocol-qa 생략.
※ 구현 중 이벤트 필드/타입을 건드리게 되면 즉시 교차 경계면(Phase 2B)으로 전환한다.

## 확정 설계 (리더 결정 — 에이전트는 임의로 바꾸지 말 것)

### 1. 조정 가능 값은 딱 2개
| 설정 | 기본값 | 허용 범위 | 비고 |
|------|--------|-----------|------|
| 포인터 속도 `moveSensitivity` | 1.5f (= 현재 `MOVE_SENSITIVITY`) | 0.5f ~ 4.0f | 커서 이동 배율 |
| 스크롤 스텝 거리 `scrollPxPerStep` | 40f (= 현재 `SCROLL_SENSITIVITY_PX_PER_STEP`) | 25f ~ 100f | **값이 클수록 스크롤이 느림.** UI 라벨은 "스크롤 속도"로 하되 슬라이더 방향은 오른쪽=빠름(= px/step이 작아짐)으로 뒤집어 사용자 직관과 맞출 것 |

**왜 이 2개만?** 나머지(탭 시간/거리, 더블탭 간격, 드래그 홀드 시간, 해제 유예)는 서로 얽힌 **불변식**이 있다
(`DRAG_HOLD_THRESHOLD_MS == TAP_MAX_DURATION_MS`, `MULTI_TOUCH_RELEASE_GRACE_MS << TAP_MAX_DURATION_MS`,
`SCROLL_SENSITIVITY_PX_PER_STEP > TAP_MAX_DISTANCE_PX`). 사용자가 한쪽만 움직이면 사각지대/오발동이 생기므로 상수로 유지한다.
- 스크롤 허용 하한 25f는 `TAP_MAX_DISTANCE_PX`(20f)보다 커야 한다는 불변식을 슬라이더로도 깨지 못하게 하려는 값이다.
  `GestureConfig`의 기존 KDoc/테스트가 이 불변식을 이미 강제하므로, 새 범위 상수도 그 테스트에 걸리도록 하라.
- `GestureConfig`의 `const val`은 **기본값의 단일 출처로 유지**한다(삭제/이름 변경 금지). 나머지 상수 전부 무변경.

### 2. 계층 구조 (Clean Architecture 유지)
- `domain/model/GestureSettings.kt` — `data class GestureSettings(moveSensitivity: Float, scrollPxPerStep: Float)`
  기본값은 GestureConfig 상수 참조. **범위 clamp 로직은 여기(순수 Kotlin)에** — 저장소에서 읽은 값이 범위 밖/NaN이어도 항상 유효 값이 되게 (`coerceIn`, NaN은 기본값).
  범위 상수(`MOVE_SENSITIVITY_RANGE`, `SCROLL_PX_PER_STEP_RANGE`)도 여기 또는 GestureConfig에 둔다.
- `domain/repository/SettingsRepository.kt` — `val settings: Flow<GestureSettings>`, `suspend fun update(GestureSettings)` (+ `reset()`), 인터페이스만.
- `data/repository/DataStoreSettingsRepository.kt` — `androidx.datastore:datastore-preferences` (Kotlin 1.8.10 / AGP 8.1.3와 호환되는 1.0.0 사용).
  키: `move_sensitivity`, `scroll_px_per_step`. 값이 없으면 기본값. 읽을 때 clamp. IOException 시 `emptyPreferences()`로 복구(catch).
- `di/`에 DataStore 제공 + `SettingsRepository` 바인딩 (Hilt, Singleton). 기존 `AppModule`/`DispatcherModule` 스타일을 따를 것.
- `presentation/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, (필요 시 `SettingsUiState`) — 슬라이더 2개 + "기본값으로 복원" 버튼 + 뒤로가기.
  슬라이더는 드래그 중에는 로컬 상태만 갱신하고 `onValueChangeFinished`에서 저장(매 프레임 디스크 쓰기 금지).

### 3. 제스처 판정기에 값 주입 (가장 조심할 부분)
- `MultiTouchGestureTracker`는 지금 `GestureConfig.MOVE_SENSITIVITY` / `SCROLL_SENSITIVITY_PX_PER_STEP`를 직접 읽는다 (`MultiTouchGestureTracker.kt:168-169, 191-192`).
  이 두 곳을 **생성자 파라미터로 주입**받게 바꾼다. 기본값은 GestureConfig 상수 → **기존 `MultiTouchGestureTrackerTest`가 무수정으로 통과해야 한다** (테스트가 `GestureConfig.*`를 참조하므로 기본값이 같으면 통과).
- 트래커는 **Compose 비의존 순수 Kotlin 유지**(설계 원칙 — DataStore/Flow를 트래커가 알면 안 됨). 값만 받는다.
- `TrackpadScreen`이 현재 설정값을 트래커 생성 시점에 넘긴다. 설정은 연결 전(Disconnected/Error) 화면에서만 바꿀 수 있으므로
  `TrackpadSurface`가 컴포지션에 들어올 때의 값을 쓰면 충분하다(제스처 도중 값이 바뀌는 경합 없음). 단 `pointerInput`의 key에 설정값을 넣어
  값이 바뀌면 블록이 재시작되도록 해서 낡은 값이 캡처되는 버그를 막을 것.

### 4. 화면 진입 경로
- 새 의존성(Navigation) 추가하지 말 것. `TrackpadScreen`의 ConnectPanel(Disconnected/Error 상태)에 설정 진입 버튼(톱니 아이콘 or 텍스트 버튼)을 추가하고,
  화면 전환은 상태 기반(`rememberSaveable { mutableStateOf(false) }` 등)으로 처리한다. Connected 상태(전체 화면 트랙패드)에는 설정 버튼을 두지 않는다 — 제스처 표면을 침범하면 안 됨.
- 뒤로가기(시스템 백 + 화면 내 버튼)로 ConnectPanel로 복귀. `BackHandler` 사용.
- 새 아이콘 의존성(material-icons-extended) 필요하면 추가하지 말고 텍스트 버튼으로 대체.

## 테스트 요구사항 (JUnit + MockK, 기존 스타일)
1. `GestureSettings` — 기본값이 GestureConfig와 일치, clamp(범위 밖 → 경계, NaN/Infinity → 기본값), 스크롤 하한이 `TAP_MAX_DISTANCE_PX`보다 큼.
2. `DataStoreSettingsRepository` — 임시 파일 기반 `PreferenceDataStoreFactory.create(...)`로 실제 왕복(저장→재읽기), 미저장 시 기본값, 범위 밖 값이 저장돼 있어도 clamp되어 읽힘, reset 후 기본값.
3. `SettingsViewModel` — 로드/변경 저장/reset 동작 (MockK 또는 fake repo + `runTest`, 기존 ViewModelTest 패턴 따름).
4. `MultiTouchGestureTracker` — 주입한 `moveSensitivity`/`scrollPxPerStep`이 실제로 MOVE 배율·SCROLL 스텝 변환에 반영되는 테스트 추가 (예: 배율 2배 → dx 2배, px/step 절반 → 같은 이동에서 스텝 2배).
5. 기존 테스트 전부 통과 유지.

## 실행/검증
- `phone_pad_app/`에서 `./gradlew testDebugUnitTest` 로 실행. 빌드 환경이 없어 실행 불가하면 정적 검토로 대체하고 **"빌드 미검증"을 summary에 명시**.
- 작업 디렉토리: 이 워크트리(`C:\Github\phone_pad\.claude\worktrees\settings-ui-datastore`) 안에서만 작업. 원본 체크아웃(`C:\Github\phone_pad`)은 건드리지 말 것.

## 산출물
- 코드/테스트: `phone_pad_app/` (워크트리 내)
- 요약: `_workspace/01_android-dev_summary.md` (변경 파일, 설계 결정, 실행 결과, 미해결 이슈, 실기기 미검증 항목)

## 제외 (이번 범위 아님)
- 서버 변경 없음. AGENTS.md/CLAUDE.md 갱신은 리더가 Phase 5에서 수행 — 에이전트는 문서를 수정하지 말 것.
- 다크모드/테마, 설정 내보내기, 서버 IP 저장(Phase 4 재연결 항목과 함께 다룸) 등은 하지 않는다.
