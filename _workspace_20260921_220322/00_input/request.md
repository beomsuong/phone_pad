# 요청 — 3손가락 스와이프 → 가상 데스크톱 전환 (Phase 5 첫 항목)

## 범위 판단: **교차 경계면** (사유: 새 이벤트 `type` `DESKTOP_SWITCH` 추가 → AGENTS.md 섹션 4 수정 필요)

실행: android-dev ∥ server-dev 병렬 → protocol-qa 사후 검증. 리더가 아래 스펙을 사전 확정했으므로 **임의로 바꾸지 말 것**.
다른 세션이 `discovery` 워크트리에서 UDP 자동 탐색(`server.py` 수정 예정)을 진행 중이다 — **서버는 `server.py`를 건드리지 않는다**(TCP 이벤트는 이미 `handle_event`로 전달되는 경로가 있음. 확인 결과 화이트리스트 없음). 커밋 금지, AGENTS.md/CLAUDE.md 수정 금지(리더가 함).

## 와이어 스펙 (확정)

```jsonc
// TCP 9000, newline-delimited, session 필드 없음 (CLICK 등과 동일한 저빈도 이벤트)
{"type":"DESKTOP_SWITCH","direction":"left"}    // 왼쪽 가상 데스크톱으로 이동 = Ctrl+Win+Left
{"type":"DESKTOP_SWITCH","direction":"right"}   // 오른쪽 가상 데스크톱으로 이동 = Ctrl+Win+Right
```

- `direction`은 **"전환 결과의 방향"**이다(손가락 방향이 아님). 손가락 방향 → 와이어 방향 매핑은 **Android 한 곳**(트래커)에서만 한다.
  - 손가락이 **왼쪽**으로 스와이프 → `"right"` (콘텐츠가 손가락을 따라 밀려나며 오른쪽 데스크톱이 드러남 = Windows 정밀 터치패드 관례)
  - 손가락이 **오른쪽**으로 스와이프 → `"left"`
  - 서버는 이 매핑을 모른다. 서버는 받은 `direction`을 그대로 키 조합으로 바꿀 뿐이다. **두 사이드가 동시에 뒤집지 않도록 매핑은 Android에만 둔다**(섹션 10 "스크롤 방향 규약"과 같은 원칙).
- `direction`이 `"left"`/`"right"`가 아니면(누락·다른 문자열·문자열이 아닌 값) 서버는 **아무 키도 보내지 않고 조용히 무시**한다(예외 전파 금지, 세션 유지).
- 수직 스와이프(작업 보기 등)·4손가락은 범위 밖. 이벤트는 좌/우 두 개뿐.

## Android 스펙 (android-dev)

1. `TrackpadEvent.DesktopSwitch(direction: String)` 추가(`Click(button: String)`과 같은 스타일). 방향 문자열 상수는 `MultiTouchGestureTracker` 안 `BUTTON_LEFT`처럼 companion 상수로 둔다(`DIRECTION_LEFT="left"`, `DIRECTION_RIGHT="right"`).
2. `TrackpadRepositoryImpl`: TCP로 직렬화. **CLICK/DOUBLE_CLICK과 같은 경로** — 전송 실패는 `sendOverTcp()` → `reportConnectionLost`로 합류(MOVE/SCROLL의 "조용한 실패"가 아님). JSON은 정확히 `{"type":"DESKTOP_SWITCH","direction":"left"}` 형태(공백 없음, 필드 순서 type→direction). **와이어 리터럴을 고정하는 테스트를 둘 것**(섹션 9 컨벤션).
3. `GestureConfig` 상수 추가(사용자 설정으로 열지 않는다, 기본값만):
   - `THREE_FINGER_SWIPE_MIN_DISTANCE_PX = 120f` — 3손가락 구간 시작 centroid로부터의 수평 이동 거리. `TAP_MAX_DISTANCE_PX`보다 충분히 커야 함(`GestureConfigTest`로 강제).
   - `THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE = 2f` — `|dx| >= 2*|dy|`일 때만 수평 스와이프로 인정(대각선/수직은 무시).
   - `THREE_POINTER_COUNT = 3`.
4. `MultiTouchGestureTracker`(순수 Kotlin 유지):
   - **정확히 3손가락인 구간**에서, 구간 시작 centroid 대비 수평 이동이 `MIN_DISTANCE` 이상이고 수평 우세이면 **그 시점에(손을 뗄 때가 아니라 임계 통과 즉시) 한 번만** `DESKTOP_SWITCH`를 방출한다. 한 구간(=3손가락 유지 동안)에 최대 1회 — 계속 밀어도 반복 전환 금지. 3→2→3처럼 구간이 새로 시작되면 다시 1회 가능(구간 단위 정의를 따른다).
   - `GestureDecision`에 필드 추가(예: `desktopSwitch: String? = null`). MOVE/SCROLL과 **동시에 non-null 금지**.
   - **3손가락 래치(핵심 안전장치):** 한 제스처(첫 down ~ 모든 손가락 up) 안에서 손가락이 **한 번이라도 3개 이상**이 되면, 그 제스처의 나머지 동안 **MOVE·SCROLL·클릭(좌/우)·드래그홀드 승격을 전부 억제**하고 `DESKTOP_SWITCH`만 허용한다. 이유: 3손가락을 어긋나게 떼면 `3→2→1→0` 꼬리가 생기는데 `MULTI_TOUCH_RELEASE_GRACE_MS`(50ms) 밖의 꼬리는 지금 로직이 "정상 탭"으로 취급해 **스와이프 직후 우클릭/좌클릭이 샌다**. 래치는 유예 시간 튜닝에 의존하지 않고 이를 원천 차단한다.
   - 3손가락 "탭"(스와이프 없이 뗌)은 아무 이벤트도 없다(클릭 없음 — 기존 동작 유지).
   - 이 래치는 기존 1·2손가락 제스처의 동작을 **바꾸면 안 된다** — 기존 트래커 테스트 전부 무수정 통과가 기준.
5. `TrackpadScreen`/`TrackpadViewModel`/`SendEventUseCase` 배선: 스와이프 결정 시 `viewModel`을 통해 전송(ViewModel은 UseCase 경유). 3번째 손가락이 닿는 순간 **대기 중인 단일 클릭(더블탭 지연)은 flush**(기존 "다른 제스처 시작 → flush" 규칙과 동일), 진행 중인 드래그홀드는 손가락 개수 변화로 기존 규칙대로 `DRAG_END`. 래치 중 `DragHoldDetector` 재무장 금지(기존 규칙). 화면 변경은 최소 diff.
6. 테스트: 트래커(정상 좌/우 → 올바른 방향 매핑, 임계 미만 무발사, 수직·대각선 무발사, 1구간 1회, 4손가락 무발사, 래치: 스와이프 후 어긋난 꼬리에서 클릭/MOVE/SCROLL 없음, 스와이프 없이 3손가락 탭 → 무이벤트, 2손가락 스크롤 후 3번째 손가락이 닿는 경우, 기존 1·2손가락 회귀), 저장소 직렬화 리터럴 2종 + 전송 실패 합류, ViewModel 위임, GestureConfig 불변식.
7. **런타임 테스트는 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`로 강제 재실행**(`UP-TO-DATE` 스킵 방지). 기준선 258 → 회귀 0. `runTest`에서 heartbeat 루프를 살려둔 채 끝내지 말 것(AGENTS.md 섹션 6 함정).

## 서버 스펙 (server-dev)

1. `input_controller.py`에 **키보드 주입** 추가: `KEYBDINPUT` 구조체(`wVk`, `wScan`, `dwFlags`, `time`, `dwExtraInfo`)를 `_INPUTunion`에 추가(`ctypes.sizeof(INPUT)`이 변하면 안 됨 — 변하면 SendInput이 통째로 실패한다. 테스트로 고정), `INPUT.type = INPUT_KEYBOARD(1)`.
2. `handle_event`에 `DESKTOP_SWITCH` 분기 추가 → `_desktop_switch(direction) -> bool`.
3. 키 시퀀스는 **`SendInput` 1회 호출, 6개 INPUT으로 원자적**: `Ctrl down → Win down → Arrow down → Arrow up → Win up → Ctrl up`. (VK_LCONTROL 0xA2, VK_LWIN 0x5B, VK_LEFT 0x25 / VK_RIGHT 0x27.) 화살표 키는 `KEYEVENTF_EXTENDEDKEY(0x1)` 필수, 키 업은 `KEYEVENTF_KEYUP(0x2)`. 반드시 기존 `_send_input(count, inputs, kind)` 창구를 경유(섹션 9 컨벤션: `SendInput` 직접 호출 금지), kind는 `"DESKTOP_SWITCH"`.
4. **수정 키 고착 방지(핵심 안전장치):** 주입이 부분 성공(`injected != 6`)일 수 있으므로, 실패로 판정되면 **Ctrl·Win 및 화살표의 키 업 3개를 best-effort로 한 번 더 보낸다**(정리용 호출은 kind `"DESKTOP_SWITCH_CLEANUP"` 등 별도 종류로 하되 재귀/무한 재시도 금지, 정리 호출 자체의 예외도 삼켜 원래 실패 처리에 영향 없게). Win 키가 눌린 채 남는 것이 최악의 결과다. 성공 경로에서는 정리 호출을 하지 않는다(호출 횟수 단언).
5. 드래그와의 상호작용: 서버는 드래그 활성 중 `DESKTOP_SWITCH`를 받아도 **거부하지 않는다**(Android가 3손가락이 닿으면 이미 DRAG_END를 보내므로 정상 흐름에선 안 겹친다). 상태를 건드리지 않는다.
6. 잘못된 `direction`은 무시(위 와이어 스펙). 예외 전파 금지.
7. `server.py`는 **수정 금지**(병렬 세션과 충돌 방지). TCP 경로의 `handle_event` 위임이 새 type을 통과시키는지는 `handle_client`를 실제 구동하는 테스트(기존 `test_server_drag.py` 방식)로 검증하고, `patch_send_input()`(tests/send_input_stub.py)를 사용할 것.
8. 테스트: 정확한 6개 INPUT의 순서·vk·플래그(좌/우), 화살표만 EXTENDEDKEY, `SendInput` 호출 1회, 잘못된 direction 무시(누락/None/숫자/대소문자 다른 값 `"LEFT"` 등 — **`"left"`/`"right"` 소문자 정확 일치만 허용**), 실패 시 카운터 + 정리 호출 3개 키 업, 성공 시 정리 없음, 정리 호출 예외 무시, INPUT 구조체 크기 불변, TCP end-to-end 1건, 드래그 활성 중에도 드래그 상태 불변. 기준선 246 passed, 1 skipped → 회귀 0.
9. 가능하면 **실제 SendInput 키 주입은 하지 말 것**(실행 중 데스크톱을 실제로 전환하거나 Win 키가 고착될 위험). 실측이 필요하면 `KEYEVENTF_KEYUP` 단독 같은 무해한 호출로 반환값 계약만 확인.

## 산출물
- 각자 `_workspace/01_android-dev_summary.md` / `_workspace/01_server-dev_summary.md` (변경 파일, 테스트 결과 수치, 미해결 이슈, "리더가 AGENTS.md에 반영할 내용").
- 커밋하지 말 것.
