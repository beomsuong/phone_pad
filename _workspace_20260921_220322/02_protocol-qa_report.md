# protocol-qa 리포트 — DESKTOP_SWITCH 경계면 정합성 (Phase 5 첫 항목)

검증 대상: 미커밋 작업 트리 (`git status` 기준).
읽은 코드: Android 6개 파일 + `pc_server/input_controller.py` + `pc_server/server.py`(TCP 경로, 무변경).
실행: `cd pc_server && python -m pytest` → **285 passed, 1 skipped** (server-dev 보고와 일치).
Gradle은 리더가 별도 실행 중이므로 돌리지 않았다 — Android 테스트 **파일의 존재와 내용**만 정적으로 확인했다.

**총평: 와이어 계약·방향 의미·채널·래치는 전부 일치한다. 실패 1건(F-1, 서버 수정 키 고착 틈)과 주의 4건.**

---

## 1. 와이어 리터럴 일치 — **PASS**

| | 값 |
|---|---|
| Android 송신 | `TrackpadRepositoryImpl.kt:297` → `{"type":"DESKTOP_SWITCH","direction":"${event.direction}"}` |
| Android 고정 테스트 | `TrackpadRepositoryImplTest.kt` — `assertEquals("""{"type":"DESKTOP_SWITCH","direction":"left"}""", json.captured)` |
| 서버 수신 | `input_controller.py:199` `elif t == "DESKTOP_SWITCH":` → `:202` `event.get("direction")` |
| 서버 고정 테스트 | `tests/test_desktop_switch.py:38-39` — `LEFT_LINE = b'{"type":"DESKTOP_SWITCH","direction":"left"}\n'` |

- 양쪽 테스트 상수가 **바이트 단위로 동일**하다(서버 쪽은 `TcpClient`가 붙이는 `\n`만 추가). 공백 없음, 필드 순서 `type` → `direction`, 전부 소문자.
- `session` 필드 없음 — Android 테스트가 `assertFalse(json.captured.contains("session"))`로 고정.
- 채널: `sendOverTcp()` 경유 = TCP 9000. `coVerify(exactly = 0) { udpClient.send(any()) }`로 UDP 누수 배제. 서버 쪽도 `test_desktop_switch_does_not_reach_udp_path`로 반대 방향 고정.
- 서버는 `json.loads` 결과를 dict로 보므로 공백/순서 변화에도 견디지만, 스펙이 요구한 "리터럴 고정"은 양쪽 모두 충족.

## 2. 방향 의미 일관성 — **PASS** (이번 검증의 최우선 항목)

- 손가락→와이어 뒤집기가 존재하는 곳은 **정확히 한 줄**이다:
  `MultiTouchGestureTracker.kt:289` → `return if (totalDx < 0f) DIRECTION_RIGHT else DIRECTION_LEFT`
  (`grep -rn "direction\|DIRECTION_" phone_pad_app/.../main/`으로 전수 확인 — 나머지 히트는 전부 KDoc·상수 정의·그대로 전달하는 계층이다.)
- `TrackpadViewModel.kt:140-144`, `TrackpadRepositoryImpl.kt:297`은 값을 **변형 없이 통과**시킨다. 각각 회귀 테스트 존재(`sendDesktopSwitch는 방향을 뒤집지 않고...`, `DesktopSwitch는 direction 값을 뒤집지 않고 그대로 싣는다`).
- 서버는 `DESKTOP_SWITCH_VK = {"left": VK_LEFT(0x25), "right": VK_RIGHT(0x27)}` (`input_controller.py:31-34`) — 뒤집기 없음. `test_handle_event_passes_direction_verbatim_without_translating`로 고정.
- **실측(스텁 주입)으로 확인한 최종 키 시퀀스**:
  - `direction="left"` → `0xA2↓, 0x5B↓, 0x25↓(ext), 0x25↑(ext), 0x5B↑, 0xA2↑` = Ctrl+Win+**Left**
  - `direction="right"` → 화살표만 `0x27` = Ctrl+Win+**Right**
- 즉 "손가락 왼쪽 → `"right"` → Ctrl+Win+Right"로 request.md 스펙과 정확히 일치하며, **양쪽이 동시에 뒤집혀 원위치되는 사고는 없다.**

## 3. 서버 `handle_client` 전달 경로 / 잘못된 direction — **PASS**

- `server.py:151-175` 경로를 직접 읽어 확인: 화이트리스트 없음. `HEARTBEAT`는 `handle_event` **앞에서** 가로채고(`:166-169`), non-dict는 그 앞에서 `continue`(`:161-165`)라서 새 type과 충돌하지 않는다. `server.py`는 무변경(`git status`로 확인).
- `controller.handle_event(event)`는 `try/except Exception`으로 감싸져 있어(`:170-175`) 이벤트 하나가 세션을 끊지 않는다.
- 잘못된 `direction`: `input_controller.py:377`에서 `isinstance(direction, str)`를 **먼저** 평가하므로 리스트/딕트 같은 unhashable 값도 `TypeError` 없이 `None`이 된다 → `:378-379`에서 아무 키도 보내지 않고 `False`. 예외 전파 없음, 실패 카운터도 안 오른다.
- `test_bad_direction_over_tcp_keeps_session_alive`가 "잘못된 줄 뒤의 정상 이벤트가 그대로 처리됨"까지 고정.
- 드래그 안전장치 회귀도 `test_drag_safety_net_still_runs_after_a_desktop_switch`로 확인됨.

## 4. 수정 키 고착 시나리오 — **FAIL (F-1)**

### [F-1] `_send_input`이 예외를 던지면 Ctrl/Win 정리가 호출되지 않는다

- **위치**: `pc_server/input_controller.py:393-400` (`_desktop_switch`의 성공/실패 분기)
- **기대**: 6개 INPUT 주입이 완전 성공하지 않은 **모든** 경로에서 `_release_desktop_switch_keys()`가 한 번 호출된다(request.md 서버 스펙 4번: "Win 키가 눌린 채 남는 것이 최악의 결과").
- **실제**: 정리 호출은 `if self._send_input(...)`가 **정상적으로 False를 반환한 경우에만** 실행된다. `_send_input`이 예외로 빠져나가면 `:399`에 도달하지 못하고 예외가 `handle_event` 밖으로 나간다. 세션은 `server.py:170-175`가 잡아 살아남지만, **부분 주입된 Ctrl/Win은 눌린 채 남는다.**
- **재현 (실측, 두 경로 모두 확인)**:

  ```python
  # 경로 A: SendInput 자체가 OSError를 던짐
  #   → SendInput 호출 [6]건만, 정리([3])가 없음. handle_event가 OSError를 던짐.
  # 경로 B(더 위험): injected=3 (Ctrl↓/Win↓/Arrow↓만 들어감) + _note_input_failure 안에서
  #   self._monotonic()이 예외 → _send_input이 False를 반환하기 전에 스택을 빠져나감
  #   → SendInput 호출 [6]건만, 정리([3])가 없음. 실제로 Ctrl+Win이 눌린 채 남는다.
  ```

  경로 B는 `_note_input_failure`(`:147-173`)가 `self._monotonic()`·dict 갱신·`self._log`를 실행하는데, `_log` 호출만 `except (OSError, ValueError)`로 감싸져 있고(`UnicodeEncodeError`는 `ValueError` 하위라 여기서 커버됨) **그 바깥은 무방비**라는 데서 온다. 프로덕션 기본값(`time.monotonic`/`print`)에서는 발생 확률이 낮지만, 이 두 인자는 생성자로 주입 가능하고(트레이/로그 어댑터가 넘긴다) 하필 **부분 주입이 일어난 직후**에만 실행되는 코드라서 "정리가 가장 필요한 순간에만 정리를 건너뛰는" 모양이 된다.
- **수정 제안** (`input_controller.py:393-400`을 아래로 교체):

  ```python
          ok = False
          try:
              ok = self._send_input(len(sequence), inputs, "DESKTOP_SWITCH")
          finally:
              if not ok:
                  # 예외로 빠져나가는 경로도 포함한다 - 부분 주입 뒤 실패 로깅이 던지면
                  # 지금 코드는 정리를 건너뛰고 Ctrl/Win 이 눌린 채 남는다.
                  self._release_desktop_switch_keys(vk_arrow)
          return ok
  ```

  `_release_desktop_switch_keys`는 이미 자체적으로 모든 예외를 삼키므로(`:409-425`) 원래 예외를 가리지 않는다. 성공 경로에서는 `ok=True`라 정리 호출이 없으므로 `test_success_path_does_not_send_cleanup`의 `call_count == 1` 단언도 그대로 유지된다.
- **추가 테스트 제안**: (1) `patch_send_input(side_effect=OSError("blocked"))` → 정리 호출 1회 + 원래 예외 전파, (2) `injected_partial(3)` + `monotonic`이 던지는 `InputController` → 정리 호출 1회.
- **영향받는 에이전트**: server-dev

### 정리 시퀀스 자체 — PASS
`injected_partial(3)`로 실측: 2번째 `SendInput`이 `count=3`, `0x25↑(ext) → 0x5B↑ → 0xA2↑`(누른 역순, 전부 KEYUP)로 정확히 나간다. 재귀·재시도 없음(`test_cleanup_is_not_retried_recursively`), 정리 예외는 삼킴(`test_cleanup_exception_does_not_escape_handle_event`).

## 5. 3손가락 래치와 다른 제스처 상호작용 — **PASS (판정기) / 미검증 (화면 배선)**

판정기(`MultiTouchGestureTracker`) 수준에서는 세 곳 전부 래치가 걸린다:

| 누수 경로 | 차단 지점 |
|---|---|
| MOVE | `:226-230` — `!threeFingerLatched &&` 가드 |
| SCROLL | `:241-245` — 동일 가드 |
| 좌/우클릭(탭) | `:341-343` — `resolveTap` **맨 앞**에서 반환. 꼬리 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`) 분기 자체를 타지 않으므로 유예 시간 튜닝에 의존하지 않는다 |
| 드래그홀드 재무장 | `DragHoldDetector.kt:109-112` — `pointerCount != 1`이면 `release()`, `armed=false`. `onGestureStart`는 제스처 첫 down에서만 호출되므로 같은 제스처 안에서 다시 무장하지 않는다(기존 규칙, 추가 코드 불필요) |

- `threeFingerLatched`는 `onPointerEvent` **최상단**(`:194-197`)에서 `pointerCount >= 3`에 걸리고, `reset()`(`:403`)에서만 풀린다. `startSegment`는 구간 단위 플래그(`desktopSwitchEmittedInSegment`)만 리셋하고 래치는 건드리지 않는다(`:428-431`) — 꼬리마다 풀리는 사고 없음.
- **드래그홀드 중 3번째 손가락 → DRAG_END 순서**: `TrackpadScreen.kt:359-366`의 `handleDragHold(...)`가 `tracker.onPointerEvent(...)`(`:368`)보다 **먼저** 실행되므로 DRAG_END가 항상 DESKTOP_SWITCH보다 앞선다. 또한 3손가락 전환 이벤트에서 트래커는 `segmentStarted`만 반환하고 `desktopSwitch`는 null이라 같은 프레임에 둘이 겹칠 수도 없다.
- **MOVE/SCROLL과 동시 방출 불가**: `desktopSwitch`는 `pointerCount == 3`, `move`는 `==1`, `scroll`은 `==2` 전용이라 래치와 무관하게 구조적으로 배타적이다.
- **더블탭 감지기 오염**: `TrackpadScreen.kt:382-385`가 `pointerCount >= 3`인 매 이벤트에서 `flushPendingClick()` + `doubleTapDetector.reset()`을 호출한다. 래치 때문에 그 제스처의 `end.clickButton`은 항상 null이라 `doubleTapDetector.onTap()`이 아예 불리지 않는다 → 3손가락 후 1손가락만 남긴 상태에서도 감지기가 오염되지 않는다.
- 커버된 시나리오(신규 `MultiTouchGestureTrackerDesktopSwitchTest.kt` 23건): 방향 매핑 2, 상수 일치, 임계 미만/정확히 임계, 수직/대각선, 구간당 1회/되돌리기, 구간 재시작, 제스처 재시작, 4손가락, 1·2손가락 무발사, 3손가락 탭 무이벤트, **어긋난 꼬리 클릭 누수 2건**, 래치 중 MOVE 차단, **2손가락 스크롤 중 3번째 손가락**, 동시 방출 금지, 래치 해제 후 1손가락 탭/2손가락 스크롤 회귀.

### 미검증 (실패 아님)
`TrackpadScreen.kt:382-393`의 화면 배선 — ① 3번째 손가락 접촉 시 pending click flush, ② `doubleTapDetector.reset()`, ③ `onDesktopSwitch` 호출 + `consume()` — 은 Compose `awaitEachGesture` 의존이라 자동 테스트가 없다(AGENTS.md 섹션 10의 기존 한계와 동일). 코드 리뷰로만 확인했다.

## 6. 전송 실패 시 재연결/Error 덮어쓰기 규칙 — **PASS**

- `TrackpadRepositoryImpl.kt:292-298` → `sendOverTcp(...)`. CLICK/DOUBLE_CLICK/DRAG_START/DRAG_END와 **완전히 같은 함수**를 탄다(`:315-324`): 실패 시 `reportConnectionLost(forGeneration, ...)`로 합류 → 소켓 정리 + 자동 재연결 진입, 세대 CAS로 이중 보고 방지.
- MOVE(`:247-252`)·SCROLL(`:273-275`)의 `runCatching` 조용한 실패 경로가 **아니다** — 스펙대로.
- 회귀 테스트: `DesktopSwitch 전송 실패는 CLICK과 같이 Error로 알린다` → `ConnectionState.Error("tcp down", CONNECTION_LOST)` 단언.
- 전송 순서: `TcpClient.send()`가 `Dispatchers.IO.limitedParallelism(1)` 전용 디스패처를 쓰고(`TcpClient.kt:36,117`), `viewModelScope`(Main.immediate)에서 launch 순서대로 enqueue되므로 `CLICK(flush) → DRAG_END → DESKTOP_SWITCH` 순서가 뒤집히지 않는다(지난 F-2 수정이 그대로 적용됨).

## 7. 기존 이벤트 회귀 — **PASS**

- `ctypes.sizeof(INPUT)` **40 불변** (실측: `MOUSEINPUT=32`, `KEYBDINPUT=24`, union=32, INPUT=40). `KEYBDINPUT`이 더 작아 union 크기가 안 변한다. `_move`/`_click`/`_double_click`/`_scroll`/`_send_button_flag`는 전부 무변경.
- `pytest` 285 passed / 1 skipped (기준선 246/1 → 신규 39, 회귀 0). 리더가 재현 가능.
- Android: `TrackpadEvent`에 `DesktopSwitch`만 추가돼 `sendEvent`의 `when`이 여전히 exhaustive(`else` 없음 — 새 이벤트 누락이 컴파일 에러로 잡히는 구조 유지). 기존 `MultiTouchGestureTrackerTest` 36건 무수정.
- 1:1 매핑 감사: Android 7종(MOVE/CLICK/DOUBLE_CLICK/SCROLL/DRAG_START/DRAG_END/DESKTOP_SWITCH) ↔ 서버 `handle_event` 7분기 — 고아 타입·죽은 코드 없음. (`HEARTBEAT`/`HEARTBEAT_ACK`는 설계상 `handle_event` 밖.)

## 8. 문서 ↔ 코드 — **FAIL (문서 미갱신, 리더 담당 · 조치 불필요)**

- `AGENTS.md`에 `DESKTOP_SWITCH`가 **전혀 없다**(`grep` 확인). 섹션 4 TCP 이벤트 목록에 없고, 섹션 5 표 `AGENTS.md:168`은 아직 `| 3손가락 스와이프 | 가상 데스크톱 전환 등 | Phase 4 | ⬜ 미구현 |`, 섹션 6 로드맵 `:300`도 미체크. 섹션 5 감도 상수 블록에 신규 3개 상수 없음.
- request.md가 양 에이전트에게 AGENTS.md 수정을 금지했으므로 **예상된 상태**다. 두 에이전트의 summary에 담긴 문안이 코드와 일치함을 확인했으니 그대로 반영하면 된다. 리더가 섹션 4/5/6/7/9/10을 갱신하기 전까지는 **문서가 코드보다 낡은 상태**라는 점만 기록해 둔다.

---

## 주의 항목 (FAIL 아님, 판단 필요)

1. **[서버] 실패 1회에 `input_failures`가 2 증가** — 본 시퀀스 + 정리 호출이 둘 다 `_send_input` 창구를 지난다. server-dev가 의도적으로 남겼고 테스트에 명시됨. 카운터가 진단용이라면 그대로 둬도 되지만, 트레이 UI에 노출할 때 "실패 횟수"가 2배로 보인다는 점은 기억해야 한다.
2. **[서버] 정리 키 업이 시작 메뉴를 열 수 있다** — 부분 주입이 하필 2개(`Ctrl↓, Win↓`)에서 멈추면 정리가 `Arrow↑ → Win↑ → Ctrl↑`를 보내는데, Win이 눌린 동안 **키 다운이 하나도 없었으므로** Windows가 이를 "Win 단독 탭"으로 보고 시작 메뉴를 열 수 있다. request.md가 정리를 키 업 3개로 못 박았고 `test_cleanup_only_sends_key_ups`가 이를 고정하므로 스펙 위반은 아니며, "Win 고착"보다는 훨씬 나은 결과다. 실기기 확인 항목으로만 남긴다.
3. **[Android] 래치는 제스처 끝까지 유지된다** — 2손가락 스크롤 중 3번째 손가락이 **스치기만 해도** 그 제스처의 나머지 스크롤이 전부 죽는다(손을 다 떼야 복구). 스펙이 명시적으로 요구한 동작이고 테스트로 고정돼 있지만, 실기기에서 체감이 나쁘면 재논의 대상이다.
4. **[Android] 4→3 전환 구간에서 전환이 발사될 수 있다** — 4손가락에서 하나를 떼면 새 3손가락 구간이 시작되고 `desktopSwitchEmittedInSegment`가 리셋된다. 이 상태로 120px 이상 수평 이동하면 전환이 나간다. "구간 단위 정의를 따른다"는 스펙의 논리적 귀결이며, 4손가락 제스처가 범위 밖인 현재는 실해가 없다.
5. **[Android] 문서 nit** — `TrackpadRepositoryImpl.kt:303`의 `sendOverTcp` KDoc이 여전히 "CLICK/DOUBLE_CLICK/DRAG_START/DRAG_END"만 열거한다. `DESKTOP_SWITCH` 추가 권장(1줄).

## 미검증 항목

- `TrackpadScreen`의 3손가락 배선(flush/reset/consume) — 자동 테스트 없음, 코드 리뷰만.
- Android 유닛 테스트 실제 실행 — 리더가 gradle로 진행 중(지시에 따라 미실행).
- 실기기: `pointerCount == 3` 보고 안정성, 제조사 시스템 제스처와의 충돌, 120px 임계 체감(px 기준).
- `Ctrl+Win+Left/Right`가 실제로 데스크톱을 넘기는지 — 양 에이전트 모두 고의로 미실행.
