# protocol-qa 검증 리포트 — 탭홀드 + 드래그 → DRAG_START / DRAG_END (Phase 3)

**검증일:** 2026-09-19
**대상:** `phone_pad_app` (android-dev) ↔ `pc_server` (server-dev), 양쪽 동시 읽기 + 실측
**결과 요약:** **통과 22 / 실패 2 / 미검증 4** (+ 관찰·위험 7건)
**안전장치 판정:** 연결 종료 시 강제 LEFTUP은 **세 경로(정상 EOF / heartbeat 타임아웃 / 예외) 모두 실측으로 동작 확인**. 버튼 고착을 막는 최종 방어선은 실재한다.

---

## 0. 검증 방법 (요약을 믿지 않고 실측한 것)

| 방법 | 내용 |
|------|------|
| 양쪽 동시 읽기 | Android 송신부 6개 파일 ↔ 서버 `input_controller.py`/`server.py` 대조 |
| 서버 테스트 재현 | `cd pc_server && python -m pytest` → **95 passed in 0.37s** (보고 수치 일치) |
| Android 테스트 재현 | `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest` → BUILD SUCCESSFUL, XML 집계 결과 **tests=149 failures=0 errors=0 skipped=0** (보고 수치 일치) |
| **독립 실증 스크립트** | server-dev 테스트와 **무관하게 직접 작성**한 probe로 `handle_client`를 FakeConn + SendInput 후킹으로 구동, 실제 전송되는 마우스 플래그 시퀀스를 관측 → **10/10 PASS**. 스크립트: `C:\Users\membe\AppData\Local\Temp\claude\C--Github-phone-pad\5b62218c-b334-4cbf-9fea-c0e84a9fd377\scratchpad\qa_drag_probe.py` |

Android 테스트 스위트별 내역: MultiTouchGestureTracker 36 / DragHoldDetector 24 / TrackpadRepositoryImpl 23 / GestureConfig 14 / DoubleTapDetector 13 / TrackpadViewModel 13 / TrackpadRepositoryHeartbeat 12 / SessionHandshake 8 / TcpClient 4 / SendEventUseCase 1 / Example 1 = 149.

---

## 1. 통과 (22)

### 와이어 포맷 / 경계면 계약 (P-1 ~ P-6)

| # | 항목 | 왼쪽 (Android) | 오른쪽 (Server) | 판정 |
|---|------|----------------|------------------|------|
| P-1 | `DRAG_START` 리터럴 | `TrackpadRepositoryImpl.kt:265` `const val DRAG_START_JSON = """{"type":"DRAG_START"}"""` | `input_controller.py:73` `elif t == "DRAG_START"` | 일치 |
| P-2 | `DRAG_END` 리터럴 | `TrackpadRepositoryImpl.kt:266` | `input_controller.py:76` `elif t == "DRAG_END"` | 일치 |
| P-3 | 필드 없음 / session 없음 | 직렬화 문자열에 `type` 외 키 없음 (`:148`, `:158`) | `handle_event`가 두 분기에서 `event.get()`을 **한 번도 호출하지 않음** | 일치. 양쪽 모두 "필드 없음"을 실제로 구현 |
| P-4 | 채널 원칙 (섹션 4) | `sendEvent`의 DragStart/DragEnd 분기가 `tcpClient.send` 사용, UDP 아님 | `handle_udp_packet`이 `event.get("type") != "MOVE"`에서 조기 반환 (`server.py:72`) | **실측 확인**: UDP로 보낸 `DRAG_START`는 `handled=False`, `drag_active=False` 유지. 같은 세션의 MOVE는 정상 처리 |
| P-5 | 미지 `type` 무시 | — | `handle_event`의 `if/elif` 체인에 `else` 없음 → 조용히 반환 | **실측**: `{"type":"NOPE"}`, `[1,2,3]`, `not json`을 드래그 중간에 섞어도 크래시 없이 무시, 드래그 정상 완주 |
| P-6 | 여분 필드 내성 | — | 두 분기가 필드를 읽지 않음 | 앱이 훗날 필드를 추가해도 서버가 깨지지 않음 |

카멜/스네이크 혼용 없음 — `type` 한 키뿐이며 값은 대문자 스네이크 상수로 양쪽 동일.

### 멱등성 (P-7 ~ P-8) — 전부 실측

| # | 시나리오 | 관측된 SendInput 플래그 |
|---|----------|-------------------------|
| P-7 | `DRAG_START` × 3 연속 | `[LEFTDOWN, LEFTUP]` — LEFTDOWN **1회만**. `_drag_start()`가 `_drag_lock` 안에서 `if self._drag_active: return False` (`input_controller.py:140-146`) |
| P-8 | `DRAG_START` 없이 `DRAG_END` × 2 | `[]` — **아무 입력도 안 나감**, 크래시 없음 (`input_controller.py:154-156`) |

### 연결 종료 안전장치 (P-9 ~ P-13) — 가장 중요, 전부 실측

`server.py:165-177`의 `finally` 블록에서 `controller.force_release_drag()` 호출. 내가 직접 `handle_client`를 구동해 관측한 결과:

| # | 경로 | 대본 | 관측 결과 | 판정 |
|---|------|------|-----------|------|
| P-9 | **정상 종료(EOF)** | `DRAG_START` → `b""` | `[LEFTDOWN, LEFTUP]` | 강제 해제 확인 |
| P-10 | **heartbeat 타임아웃** | `DRAG_START` → `socket.timeout` × 3 | `[LEFTDOWN, LEFTUP]` | 강제 해제 확인. 세션 회수·소켓 종료도 유지 |
| P-11 | **예외** | `DRAG_START` → `ConnectionResetError` | `[LEFTDOWN, LEFTUP]` | 강제 해제 확인 |
| P-12 | 정상 `DRAG_END` 수신 후 종료 | `DRAG_START`+`DRAG_END` → EOF | `[LEFTDOWN, LEFTUP]` — **중복 LEFTUP 없음** | 멱등성이 finally와 맞물려 정상 |
| P-13 | 강제 해제 중 예외 발생 시 | (F-1 상황에서 실측) | `conn.closed=True`, `sessions=set()` | 소켓 정리·세션 회수가 막히지 않음 |

**결론: "PC 마우스 버튼이 영원히 눌린 채 멈추는" 심각한 실패는 서버 측에서 막힌다.** (단 프로세스 kill 시는 예외 — O-5)

### 승격/탭 배타성 (P-14 ~ P-17)

| # | 항목 | 근거 |
|---|------|------|
| P-14 | `DRAG_HOLD_THRESHOLD_MS == TAP_MAX_DURATION_MS` | `GestureConfig.kt:90` `const val DRAG_HOLD_THRESHOLD_MS: Long = TAP_MAX_DURATION_MS` — 숫자 중복 없이 **참조**. 스펙 2번 그대로. `GestureConfigTest.kt:102-103`이 등식과 200L을 고정 |
| P-15 | 탭은 `<`, 승격은 `>=` | 탭: `MultiTouchGestureTracker.kt:272` `if (drag \|\| elapsed >= TAP_MAX_DURATION_MS) return null`. 승격: `DragHoldDetector.kt:167` `if (timestampMs - startTimeMs < DRAG_HOLD_THRESHOLD_MS) return None` → **경계 200ms는 드래그 쪽**, 사각지대·중복 없음 |
| P-15b | 드래그 홀드 제스처가 CLICK/DOUBLE_CLICK을 완전히 건너뛰는가 | `TrackpadScreen.kt:312` `if (!dragHold.hasPromoted) { ... }` 가 클릭·더블탭 분기 **전체**를 감싼다. `hasPromoted`는 `onGestureEnd()`(=`release()`)에서도 유지됨(`DragHoldDetector.kt:177-182`가 `promotedOnce`를 건드리지 않음) → 종료 후 조회해도 true. **이중 방어**: 승격되려면 200ms 이상 유지해야 하고, 그 시점 이미 탭 조건(`elapsed >= 200`)에서 탈락 |
| P-16 | "재무장 안 함" 설계 판정 | **올바른 설계로 판정.** `DragHoldDetector.kt:109-112`에서 `pointerCount != 1` → `release()` → `armed=false`. 재무장은 `onGestureStart()`(제스처당 1회, `awaitFirstDown` 직후)에서만. 재무장했다면 2손가락 스크롤 후 `2→1→0` 꼬리 구간의 1손가락이 새 anchor로 무장되어 **스크롤 직후 LEFTDOWN+LEFTUP(=좌클릭)이 튀어나온다** — 지난 라운드 F-2("스크롤했을 뿐인데 링크가 클릭됨")와 동일한 함정이며, 오히려 클릭이 아닌 "드래그 상태 진입"까지 유발해 더 나쁘다. 대가("2손가락→1손가락 후 홀드 드래그 불가")는 스펙 범위 밖이므로 수용 타당 |
| P-17 | 기존 "빠른 이동" 경로 무회귀 | `DragHoldDetector.kt:115-119` 거리 초과 시 `armed=false`(sticky) → 이후 `remainingHoldMs()`가 null → `TrackpadScreen.kt:239-243`이 **타임아웃 없는 기존 `awaitPointerEvent()`로 복귀**. 승격되지 않은 제스처의 대기 특성·이벤트 경로가 변경 전과 동일 |

### 회귀 / 테스트 커버리지 (P-18 ~ P-22)

| # | 항목 | 결과 |
|---|------|------|
| P-18 | 서버 테스트 재현 | 95 passed (보고와 일치) |
| P-19 | Android 테스트 재현 | 149 tests, 0 failures (보고와 일치) |
| P-20 | 양쪽 모두 신규 이벤트 테스트 보유 | Android: `DragHoldDetectorTest`(24) + `TrackpadRepositoryImplTest`가 **와이어 리터럴 문자열 자체를 assert**(`:184`, `:198`). Server: `test_input_controller.py` +14, `test_server_drag.py` 14. **양쪽이 같은 리터럴을 독립적으로 고정**하고 있어 한쪽이 바뀌면 반대쪽 테스트가 깨진다 — 경계면 회귀 방어로 적절 |
| P-21 | 드래그 중 타 이벤트 혼재 | **실측**: `DRAG_START`→`MOVE(3,-2)`→`HEARTBEAT`→`SCROLL(0,-1)`→`DOUBLE_CLICK`→`DRAG_END` = `[LEFTDOWN, MOVE, WHEEL, LEFTDOWN, LEFTUP, LEFTDOWN, LEFTUP, LEFTUP]` (예상과 정확히 일치). MOVE/SCROLL/DOUBLE_CLICK 모두 무회귀, 드래그 상태 불변 |
| P-22 | TCP 청크 분할 수신 | **실측**: `{"type":"DRAG` / `_START"}\n{"type":"DR` / `AG_END"}\n` 3조각 → `[LEFTDOWN, LEFTUP]` 정상 파싱 |

---

## 2. 실패 (2)

### F-1 (server, 중간) — 안전장치가 성공했는데 "실패했다"고 로그하는 인코딩 버그

**`pc_server/server.py:173`**

```python
print(f"[!] Drag was active on disconnect — left button released ({addr})")
```

- **기대값:** 강제 해제 성공 시 `[!] Drag was active on disconnect ... released` 가 콘솔에 출력된다.
- **실제값:** 한국어 Windows 콘솔(기본 `cp949`)에서 이 줄의 **em dash `—`(U+2014)가 인코딩 불가**라 `print` 자체가 `UnicodeEncodeError`를 던진다. 예외는 바로 아래 `except Exception`(`:174-176`)에 잡혀 **`[!] Failed to release drag on disconnect: 'cp949' codec can't encode character '\u2014'`** 가 출력된다.
- **실측 로그 (내 probe, 실제 환경 재현):**
  ```
  [=] Session revoked: ...
  [!] Failed to release drag on disconnect: 'cp949' codec can't encode character '\u2014' in position 34: illegal multibyte sequence
  [-] Disconnected: ('1.2.3.4', 55555)
          SendInput flags = ['LEFTDOWN', 'LEFTUP']   ← 버튼은 실제로 놓였다
  ```
- **영향 범위:** 기능은 안전하다 — `force_release_drag()`가 **먼저** 실행되어 LEFTUP이 이미 나간 뒤 print가 터지므로 버튼은 정상 해제되고, 세션 회수·`conn.close()`도 진행된다(P-13). 문제는 **가장 안전이 중요한 순간에 운영자가 정반대의 메시지를 본다**는 것: 실제로는 "복구 성공"인데 "복구 실패"로 읽힌다. 실기기 트러블슈팅 시 잘못된 방향으로 몰고 갈 수 있다.
- **왜 기존 테스트가 못 잡았나:** pytest는 stdout을 UTF-8로 캡처하므로 통과한다. 실제 `python server.py` 콘솔에서만 재현된다.
- **수정 제안:** `pc_server/server.py:173`의 `—`를 ASCII로 교체.
  ```python
  print(f"[!] Drag was active on disconnect - left button released ({addr})")
  ```
  `pc_server` 전체 `print` 문 중 비ASCII 문자는 **이 한 줄이 유일**함을 grep으로 확인했으므로, 이 한 글자만 고치면 끝난다. (근본 대책을 원하면 `server.py` 진입점에서 `sys.stdout.reconfigure(encoding="utf-8", errors="replace")`를 추가하는 방법도 있으나 Phase 4 범위로 미뤄도 무방.)

### F-2 (android, 중간~높음) — `DRAG_START` → `DRAG_END` 전송 **순서가 보장되지 않는다**

android-dev 요약 17행은 "앱이 보내는 순서 보장: `DRAG_START` → (0개 이상의 MOVE) → `DRAG_END`"라고 명시하지만, 코드는 이를 보장하지 않는다.

**관련 파일:**
- `presentation/trackpad/TrackpadViewModel.kt:100-116` — `sendDragStart()`/`sendDragEnd()`가 **각각 별도의 `viewModelScope.launch`** 를 연다.
- `data/network/TcpClient.kt:72-74` — `suspend fun send(json: String) = withContext(Dispatchers.IO) { ...println(json) }`

- **기대값:** `onDragStart()` 호출이 `onDragEnd()` 호출보다 먼저면, 소켓에 쓰이는 줄 순서도 `DRAG_START` → `DRAG_END`.
- **실제값:** 두 호출은 서로 다른 코루틴이며 각자 `withContext(Dispatchers.IO)`로 **멀티스레드 풀(병렬도 64)에 작업을 넘긴다.** 작업 제출 순서는 보존되지만 실행은 서로 다른 워커에서 병렬로 일어나므로 **완료 순서가 뒤집힐 수 있다**(`PrintWriter.println`이 내부 동기화되어 줄이 섞이진 않지만, 줄의 **순서**는 보장되지 않는다).
- **터지는 시나리오:** 확정 스펙 8번 — "승격 직후 안 움직이고 바로 뗌". 200ms 타임아웃 승격 직후 손을 떼면 두 전송이 수 ms 간격으로 제출된다. 역전되면 서버는 `DRAG_END`(비활성 → 무시) → `DRAG_START`(**LEFTDOWN, 대응 LEFTUP 없음**) 순으로 처리한다. **앱은 드래그가 끝난 줄 알고 서버는 버튼을 누른 채 남는다** — 이 기능이 막으려던 바로 그 상태다.
- **완화 요인(그래서 "높음"이 아닌 "중간~높음"):** ① 창이 좁아 발생 확률이 낮고, ② 이후 임의의 CLICK이 보내는 LEFTUP이나 다음 드래그의 `DRAG_END`, 그리고 최종적으로 연결 종료 안전장치(P-9~P-11)가 회수한다. 다만 회수 전까지 바탕화면이 드래그 상태로 끌려다닌다.
- **이것이 신규 위험인 이유:** 기존 이벤트는 전부 자기완결형(CLICK = 서버에서 down+up 원자 전송)이라 순서가 의미 없었다. `DRAG_START`/`DRAG_END`는 **프로토콜 최초의 순서 의존 이벤트 쌍**이라 이 구조적 공백이 이번에 처음 드러났다.
- **수정 제안:** TCP 쓰기를 **직렬화(FIFO)** 한다. `Mutex`는 획득 순서 자체가 이미 경합이라 부족하고, 단일 소비자 디스패처가 정답이다.
  ```kotlin
  // data/network/TcpClient.kt
  @OptIn(ExperimentalCoroutinesApi::class)
  private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

  suspend fun send(json: String) = withContext(sendDispatcher) {
      checkNotNull(writer) { "Not connected" }.println(json)
  }
  ```
  호출부(`TrackpadScreen`의 포인터 루프 → `viewModelScope`(Main.immediate) → `launch` 본문이 메인 스레드에서 즉시 실행)가 **호출 순서대로 작업을 제출**하고, 병렬도 1 디스패처가 제출 순서대로 처리하므로 순서가 복원된다. 부수 효과로 `flushPendingClick()`의 CLICK과 뒤이은 `DRAG_START`의 순서 역전(F-1/F-3 계열의 잔여 위험)도 함께 닫힌다. heartbeat 전송까지 같은 큐를 타지만 저빈도라 영향 없음.
  - 회귀 테스트 제안: `TrackpadRepositoryImplTest`에 `sendEvent(DragStart)` → `sendEvent(DragEnd)`를 연속 호출하고 MockK `verifyOrder`로 두 리터럴의 순서를 고정 (현재 `:211-212`에 순서 구분 테스트가 있으나 순차 `runTest` 호출이라 동시성 역전을 재현하지 못한다).

---

## 3. 관찰 / 위험 (실패 아님 — 판단 근거 포함)

### O-1 (요청 4번 답변) `DRAG_START`(TCP) ↔ 첫 `MOVE`(UDP) 순서 역전 — **수정 불필요로 판단**

- **근거 1 (창의 크기):** 승격은 "제자리 200ms 유지"로만 일어난다. 그 직후의 첫 MOVE는 ⓐ 최소 한 프레임(~16ms) 뒤이고, ⓑ 사용자가 정지 상태에서 움직임을 시작하는 반응 시간(통상 50ms 이상)이 더해지며, ⓒ `MultiTouchGestureTracker`가 `totalMoved > MOVE_MIN_DISTANCE_PX(5px)`를 넘겨야 비로소 방출된다. UDP가 TCP를 추월하려면 TCP가 이 합계만큼 늦어야 하는데, 동일 WiFi 링크에서 그 정도 지연은 재전송급 이상 상황이다.
- **근거 2 (피해의 성질):** 역전돼도 결과는 "버튼이 눌리기 직전에 커서가 몇 px 움직임"이다. **상태가 어긋나지 않고 이벤트도 유실되지 않는다** — 텍스트 선택 시작점이 몇 px 밀리는 정도로, F-2처럼 버튼 고착으로 가는 실패 모드가 아니다.
- **근거 3 (구조적 대안의 비용):** 완전 해결은 시퀀스 번호 도입이나 드래그 상태를 MOVE와 같은 채널로 옮기는 것인데, 둘 다 "MOVE만 UDP"라는 섹션 4의 변경 금지 원칙을 건드린다. 이 정도 피해에 지불할 비용이 아니다.
- **다만 값싼 완화책 하나는 권장(O-6 참조):** `TcpClient`가 `tcpNoDelay`를 설정하지 않아 Nagle 알고리즘이 `DRAG_START`를 수십 ms 지연시킬 수 있다(직전 HEARTBEAT가 미ACK 상태면 특히). `socket.tcpNoDelay = true` 한 줄이면 이 창이 좁아지고 CLICK 체감 지연도 함께 개선된다.

### O-2 (android, 중간) 느린 정밀 이동이 드래그로 오발동할 수 있음 — 실기기 튜닝 필요

승격 조건은 "200ms 동안 20px 이내 유지"다. 즉 **첫 200ms의 평균 속도가 100px/s 미만이면 승격된다.** 고해상도 폰에서 20px는 약 1mm 남짓이라, 사용자가 커서를 **천천히 정밀하게** 옮기려고 살살 밀면 의도치 않게 버튼이 눌린 채 끌리는 드래그가 된다. 스펙(3번, 상수 등식)을 그대로 따른 결과이므로 구현 결함은 아니지만, 실기기에서 가장 먼저 체감될 항목이다. 조정 시 `DRAG_HOLD_THRESHOLD_MS`를 `TAP_MAX_DURATION_MS`와 분리하면 스펙이 지키려던 "사각지대 없음" 성질이 깨지므로, **`TAP_MAX_DISTANCE_PX`와 별개로 승격 전용의 더 엄격한 정지 반경(예: 8px)을 두는 쪽**이 안전하다.

### O-3 (android, 낮음) 두 손가락을 200ms 이상 벌려 내려놓으면 우클릭 앞에 좌클릭이 하나 샌다

첫 손가락이 닿고 200ms 넘게 제자리면 `DRAG_START`가 나가고, 두 번째 손가락이 닿는 순간 `DragHoldDetector.kt:109-111`이 즉시 `DRAG_END`를 낸다. 서버 관점에서 LEFTDOWN+LEFTUP = **좌클릭 한 번**이 우클릭 직전에 발생한다. 확정 스펙 5·8번을 그대로 따른 동작이고 실제 사람은 두 손가락을 100ms 안에 내려놓는 게 보통이라 빈도는 낮다. 실기기에서 재현되면 O-2와 같은 처방(승격 전용 정지 반경 / 승격 직전 짧은 확인 지연)으로 함께 다루면 된다.

### O-4 (server, 낮음 — 기존 한계, 실측 확인) 다중 클라이언트 교차 해제

`InputController`가 프로세스 전역 1개라, A가 드래그 중일 때 **무관한 B 연결이 끊기면 B의 `finally`가 A의 드래그를 놓는다.** 내 probe로 실측 확인(B 종료 시 `flags=[LEFTDOWN, LEFTUP]`, A의 `drag_active`가 False로 전환). server-dev가 스스로 보고한 대로 현재 1:1 전제에서는 문제가 아니고, "버튼이 눌린 채 멈추는 것"보다 안전한 실패 방향이라 **현 상태 유지에 동의한다.** AGENTS.md 섹션 10 "다중 기기 연결" 항목에 이 의존성을 명시해두면 좋다.

### O-5 (server, 낮음 — 기존 한계) 서버 프로세스 강제 종료 시 버튼 고착

`finally`는 프로세스 kill 시 실행되지 않는다. `atexit`/시그널 핸들러가 없어 드래그 중 서버를 kill하면 버튼이 눌린 채 남는다. Phase 4(트레이 아이콘)에서 종료 훅과 함께 다루는 것이 적절.

### O-6 (android, 낮음) `TcpClient`에 `tcpNoDelay` 미설정

`TcpClient.kt:32-37`에서 소켓 생성 후 `tcpNoDelay`를 켜지 않는다. Nagle로 인해 작은 이벤트(CLICK/DRAG_START/DRAG_END)가 직전 미ACK 데이터에 묶여 수십 ms 지연될 수 있다. O-1의 창을 넓히는 요인이며, 단독으로도 클릭 반응성에 영향. 별도 이슈 권장.

### O-7 (android, 낮음) ViewModel이 파기되면 `DRAG_END`가 나가지 못한다

`TrackpadScreen.kt:344-350`의 `finally`는 `viewModelScope`로 전송을 위임하는데, 앱이 백그라운드로 가며 ViewModel까지 정리되면 그 스코프가 취소되어 전송이 누락될 수 있다. 이 경우 회수는 전적으로 서버 heartbeat 타임아웃(≈15초)에 의존한다 — 안전장치가 있으니 치명적이진 않으나, 최악의 경우 **최대 15초간 버튼이 눌린 상태**가 된다. Phase 4 재연결/생명주기 작업 시 "앱 백그라운드 진입 시 명시적 `DRAG_END`" 처리를 고려할 것.

---

## 4. 미검증 (4) — 실패로 간주하지 않음

| # | 항목 | 사유 |
|---|------|------|
| U-1 | `TrackpadScreen`의 코루틴 타이밍 (`withTimeoutOrNull` 경합, `try/finally` 배선, `flushPendingClick` 호출 순서) | Compose `awaitEachGesture`에 묶여 순수 단위 테스트 불가. android-dev 스스로 보고한 대로 **부분 해소**(판정은 `DragHoldDetector`로 분리되어 24개 테스트로 커버). 지난 라운드 대비 개선은 명확하나 AGENTS.md 섹션 10의 미결 항목은 남는다 |
| U-2 | 화면 밖 이탈 / 제스처 취소 시 Compose의 실제 이벤트 순서 | 코드상 `finally` 안전망은 확인했으나 실기기 미검증. 서버 안전장치가 최종 방어선으로 실재함은 확인됨(P-9~P-11) |
| U-3 | 타임아웃 경계에서의 포인터 이벤트 유실 가능성 | 타임아웃 취소 직후~다음 `awaitPointerEvent()` 사이에 이벤트가 디스패치될 경우의 Compose 내부 동작은 계측 없이 판정 불가. 이론적이며 제스처당 타임아웃이 소수 회뿐이라 위험은 낮다고 본다 |
| U-4 | 실기기 드래그 체감 (텍스트 선택 / 아이콘 드래그), O-2의 오발동 빈도 | 에뮬레이터/단위 테스트로 대체 불가 |

---

## 5. AGENTS.md 드리프트 목록 (리더가 갱신할 것 — 이번 QA에서는 문서 미수정)

| 위치 | 현재 문서 | 실제 구현 | 제안 |
|------|-----------|-----------|------|
| **섹션 4** `AGENTS.md:110-112` | `// 드래그 (Phase 3, 미구현)` | ✅ 양쪽 구현 완료, 필드 없음·session 없음·TCP 확정 | `✅ 구현됨` 으로 변경 + 다음 내용 명시: ①필드 없음/session 없음, ②드래그 중 이동은 기존 MOVE(UDP) 재사용(`_move` 무변경), ③서버가 `_drag_active`를 인스턴스 상태로 갖고 **양쪽 이벤트가 멱등**, ④**TCP 연결이 끊기면(정상/타임아웃/예외 전부) 서버가 강제로 LEFTUP** |
| **섹션 4 헤더** `:76` | `### 현재 구현 (Phase 2 — 하이브리드, MOVE UDP 분리 완료)` | Phase 3 이벤트(DOUBLE_CLICK, DRAG_*)까지 포함됨 | 제목에서 Phase 2 한정 표현 제거 |
| **섹션 5 표** `:146` | `탭홀드 + 드래그 \| DRAG_START → MOVE → DRAG_END \| Phase 3 \| ⬜ 미구현` | 완료 | `✅ 완료` |
| **섹션 5 엣지 케이스** `:150` | "드래그 도중 손가락 개수 변화(1→2) → 현재 제스처 취소 후 새 제스처로 재시작" | 드래그 홀드에 한해서는 **재시작하지 않는다** — `DragHoldDetector`는 개수 변화 시 즉시 `DRAG_END` 후 그 제스처 내 재무장 안 함 | 드래그 홀드의 예외 규칙과 그 이유(스크롤 꼬리 오발동 방지)를 추가 |
| **섹션 5 엣지 케이스** `:153` | "화면 밖으로 나간 손가락 → pointerInfo 변화 감지 후 DRAG_END 전송" | 구현 방식이 다름 — `awaitEachGesture` 본문을 `try/finally`로 감싸 `finally`에서 `onGestureEnd()` | 실제 구현(finally 안전망 + 서버 강제 해제 이중 방어)으로 문구 갱신 |
| **섹션 5 상수** `:157-174` | `DRAG_HOLD_THRESHOLD_MS` 없음 | `GestureConfig.kt:90`에 존재 | `DRAG_HOLD_THRESHOLD_MS = TAP_MAX_DURATION_MS  // 드래그 홀드 승격 시간(반드시 탭 최대 지속시간과 동일 — 사각지대 방지)` 추가 |
| **섹션 6** `:206-212` | Phase 3 `- [ ] 탭홀드(200ms↑) + 드래그 → DRAG_START / DRAG_END` | 완료 | `- [x]` 로 변경. 핵심 파일 목록에 `presentation/trackpad/DragHoldDetector.kt`(신규 순수 판정기), `input_controller.py`의 `_drag_start`/`_drag_end`/`force_release_drag`, `server.py` finally 안전장치 추가 |
| **섹션 6** `:216` | `TrackpadScreen.kt` — **자동 테스트 없음** … "다음에 이 파일을 건드릴 때는 순수 클래스로 추출할 것" | 이번에 **승격 판정은 실제로 추출됨**(`DragHoldDetector`, 24 테스트). 남은 미검증은 코루틴 타이밍/배선뿐 | "부분 해소" 로 갱신 — 무엇이 추출됐고 무엇이 남았는지 구분 |
| **섹션 7** `:245-251` | 세션 흐름도에 DRAG 라인 없음 | — | `-- TCP: DRAG_START --->` / `-- TCP: DRAG_END --->` 및 **연결 종료 시 서버가 강제 LEFTUP** 을 흐름도에 추가 |
| **섹션 10** `:310-326` | 드래그 관련 미결 항목 없음 | — | 추가 권장: ①다중 클라이언트 교차 해제(O-4) — 기존 "다중 기기 연결" 행에 덧붙임, ②프로세스 kill 시 버튼 고착(O-5, Phase 4 트레이 아이콘과 함께), ③느린 정밀 이동의 드래그 오발동 실기기 튜닝(O-2), ④TCP 전송 순서 미보장(F-2, 수정 전까지) |
| **섹션 8** `:264-266` | 트러블슈팅에 드래그 항목 없음 | — | "PC 버튼이 눌린 채 멈춘 것 같으면" → 앱 연결을 끊으면(또는 15초 heartbeat 타임아웃) 서버가 자동으로 놓는다는 안내 추가 |

---

## 6. 에스컬레이션 / 조치 요청

| 대상 | 항목 | 우선순위 |
|------|------|----------|
| **server-dev** | F-1 — `server.py:173`의 em dash 1글자를 ASCII로 교체 | 높음(1줄, 즉시) |
| **android-dev** | F-2 — `TcpClient.send`를 `Dispatchers.IO.limitedParallelism(1)`로 직렬화 + `verifyOrder` 회귀 테스트 | 높음 |
| **android-dev** | O-6 — `tcpNoDelay = true` (선택, O-1 완화 겸 클릭 반응성) | 낮음 |
| **리더** | 섹션 5 전건(AGENTS.md 드리프트 11건) 문서 갱신 | 중간 |
| **리더** | O-2 실기기 검증 계획(승격 오발동 빈도) — Phase 3 감도 설정 UI 작업과 묶는 것을 권장 | 중간 |

**2회 이상 반복 지적 항목:** 없음. 지난 라운드 지적(F-1 대기 클릭 미방출, F-4 더블탭 미리셋)은 이번 구현에서 `handleDragHold`의 `Start` 분기(`TrackpadScreen.kt:198-202`)가 `flushPendingClick()` + `doubleTapDetector.reset()`을 모두 수행하여 **선제적으로 반영됨을 확인**했다.
