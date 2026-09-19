# protocol-qa 리포트 — 1손가락 더블탭 → DOUBLE_CLICK (Phase 3)

검증 방식: Android 송신부와 서버 소비부를 **동시에 읽고 비교**. 추가로 양쪽 테스트를 직접 실행하고,
서버 `_double_click`은 `SendInput`을 모킹해 **실측**했다.

**결론 요약: 경계면 계약 불일치 0건.** 실패 4건은 전부 Android 단일 사이드의 제스처 타이밍 semantics이며,
와이어 포맷·필드·채널·타입은 양쪽이 정확히 맞물린다.

| 구분 | 개수 |
|------|------|
| 통과 | 18 |
| 실패 | 4 (심각 0 / 중 2 / 하 2) |
| 미검증 | 3 |

---

## 1. 통과 (PASS)

### 경계면 정합성 (최우선)

| # | 항목 | 왼쪽 (Android) | 오른쪽 (Server) | 결과 |
|---|------|----------------|-----------------|------|
| P-1 | `type` 문자열 | `TrackpadRepositoryImpl.kt:125` → `"DOUBLE_CLICK"` | `input_controller.py:48` → `elif t == "DOUBLE_CLICK"` | 완전 일치 (대소문자·언더스코어 포함) |
| P-2 | `button` 필드명/타입 | `TrackpadEvent.kt:20` `val button: String = "left"` → `:125` 문자열로 직렬화 | `input_controller.py:49` `event.get("button", "left")` → `_double_click(button: str)` | 이름·타입 일치. snake/camel 혼용 없음 |
| P-3 | 필드 누락 시 기본값 | ViewModel이 항상 `BUTTON_LEFT` 명시(`TrackpadViewModel.kt:76`) | `event.get("button", "left")` 기본값 존재 | 누락돼도 안전 |
| P-4 | 채널 원칙 (MOVE만 UDP) | `TrackpadRepositoryImpl.kt:119-129`에서 `tcpClient.send` 사용, `udpClient` 미접촉 | `server.py:72` `handle_udp_packet`이 `type != "MOVE"`를 전부 드롭 | TCP 전송이 코드+테스트로 고정됨(`TrackpadRepositoryImplTest.kt:112` `udpClient.send` 0회 검증). UDP로 새더라도 서버가 무시하므로 이중 안전 |
| P-5 | session 필드 부재 | 평문 `{"type":"DOUBLE_CLICK","button":"left"}` | TCP 경로는 session을 요구하지 않음 (`server.py:152-157`) | CLICK/SCROLL과 동일 등급, 일치 |
| P-6 | 프레이밍 | `TcpClient.kt:73` `PrintWriter.println` → 개행 종료, `println(String)`이 락을 잡아 줄 단위 원자성 보장 | `server.py:137-141` `"\n"` 기준 split + strip | newline-delimited 규약 일치 |
| P-7 | 와이어 리터럴 고정 | `TrackpadRepositoryImplTest.kt:110` 문자열 완전 일치 단언 | `test_input_controller.py:174-185` `handle_event` → `SendInput` 종단 검증 | 양쪽 모두 리터럴/플래그로 계약을 잠금 |

### 서버 4-INPUT 원자성 (실측)

`ctypes.windll.user32.SendInput`을 패치해 `handle_event({"type":"DOUBLE_CLICK","button":"left"})`를 실행한 결과:

```
SendInput call_count = 1
count arg = 4 | len(inputs) = 4 | size = 40 | sizeof(INPUT) = 40
  [0] flags=LEFTDOWN  dx=0 dy=0 mouseData=0 MOVEbit=False
  [1] flags=LEFTUP    dx=0 dy=0 mouseData=0 MOVEbit=False
  [2] flags=LEFTDOWN  dx=0 dy=0 mouseData=0 MOVEbit=False
  [3] flags=LEFTUP    dx=0 dy=0 mouseData=0 MOVEbit=False
button 필드 생략 시: ['LEFTDOWN','LEFTUP','LEFTDOWN','LEFTUP']
```

- **P-8** `SendInput` 정확히 1회 — `_click`을 두 번 부르지 않음 (`input_controller.py:136-145`)
- **P-9** 순서 `[LEFTDOWN, LEFTUP, LEFTDOWN, LEFTUP]` 정확
- **P-10** 커서 이동 요소 없음 — 4개 INPUT 전부 `dx=dy=mouseData=0`, `MOUSEEVENTF_MOVE` 비트 0.
  Windows 더블클릭 판정 사각형(기본 4px) 문제 발생 여지 없음
- **P-11** `count` 인자와 배열 길이, `sizeof(INPUT)`(40) 일치 — ctypes 호출 규약상 정상

### 개별 CLICK으로 쪼개지지 않는가 (요청 항목 2)

- **P-12** 데이터 계층: `DoubleClick` 한 건 → TCP 한 줄. `TrackpadRepositoryImplTest.kt:127-135`가
  `tcpClient.send` 1회 + `"type":"CLICK"` 포함 전송 0회를 단언
- **P-13** ViewModel 계층: `sendDoubleClick()`은 `DoubleClick`만 발사, `Click` 미발사 (`TrackpadViewModelTest.kt:81-87`)
- **P-14** 대기 중 CLICK job 취소: `TrackpadScreen.kt:217-218`이 더블탭 판정 **이전에** 무조건
  `pendingClickJob?.cancel()`을 호출한다. `pendingClickJob`은 `awaitEachGesture` 바깥
  (`TrackpadScreen.kt:164`, `coroutineScope` 스코프)에 선언돼 제스처를 가로질러 취소 가능하다.
  `delay(300)` 대기 중인 job은 취소되어 `onClick()`에 도달하지 못한다 → **정상 경로에서 좀비 CLICK 없음**.
  (단 한 지점의 경합은 F-2 참조)
- **P-15** 더블탭 확정 시 detector 즉시 리셋(`DoubleTapDetector.kt:42-47`) → 3연타에서 `A+B`, `B+C`가
  겹쳐 더블클릭 2회가 나가지 않음. `DoubleTapDetectorTest`가 `[false,true,false,true]`로 고정

### 알 수 없는 type / 회귀

- **P-16** 알 수 없는 `type` 무시 (실측): `DOUBLE-CLICK`, `double_click`, `DOUBLECLICK`, `{}`, `SESSION`
  → `SendInput` 호출 0회, 예외 없음. `handle_event`는 if/elif만 있고 else가 없어 조용히 통과.
  추가로 `server.py:147-151`이 non-dict JSON을, `:156-162`가 개별 이벤트 예외를 격리 (기존 F-4/F-5 수정 유지)
- **P-17** 기존 이벤트 회귀 없음: `input_controller.py`의 MOVE/CLICK/SCROLL 분기와 `_click`/`_move`/`_scroll`
  본문은 이번 변경에서 **손대지 않았다**(`_double_click` 추가와 `elif` 한 줄만 삽입).
  `MultiTouchGestureTracker`의 판정식(`buttonForTap`, `resolveTap` 조건, 스크롤 잔차 누적)도 불변이며
  `GestureEndDecision`에 `x`/`y`를 **기본값과 함께 뒤에 추가**해 기존 호출부/테스트가 깨지지 않았다.
  2손가락 우클릭·스크롤 경로는 `TrackpadScreen.kt:198-208, 233`에서 이전과 동일
- **P-18** 상수 분리 규약 준수: `DOUBLE_TAP_INTERVAL_MS`/`DOUBLE_TAP_DISTANCE_PX`가 `GestureConfig`에만
  존재하고 `DoubleTapDetector`/`TrackpadScreen`이 참조. 테스트도 상수에서 파생 (하드코딩 없음)

### 테스트 실측 재현 (요청 항목 6)

보고된 수치 **양쪽 다 재현됨**.

```
$ cd pc_server && python -m pytest
collected 67 items ... 67 passed in 0.20s
  test_input_controller.py 27 / test_server_heartbeat.py 19 / test_server_udp_session.py 21
```

```
$ cd phone_pad_app && ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest
BUILD SUCCESSFUL — TOTAL 114, failures 0, skipped 0
  DoubleTapDetectorTest 13 / MultiTouchGestureTrackerTest 36 / TrackpadRepositoryImplTest 18
  TrackpadRepositoryHeartbeatTest 12 / GestureConfigTest 11 / TrackpadViewModelTest 10
  SessionHandshakeTest 8 / TcpClientTest 4 / SendEventUseCaseTest 1 / ExampleUnitTest 1
```

> 주의: 처음 `./gradlew :app:testDebugUnitTest`만 돌렸을 때 `testDebugUnitTest UP-TO-DATE`로
> **실제 실행 없이 BUILD SUCCESSFUL**이 떴다. `cleanTestDebugUnitTest`를 앞에 붙여 강제 재실행한 뒤
> XML 결과(`app/build/test-results/`)로 개수를 센 값이 위 수치다. 앞으로 수치를 보고할 때는
> UP-TO-DATE 여부를 반드시 확인할 것.

---

## 2. 실패 (FAIL)

> 4건 모두 **경계면(Android↔서버 계약) 문제가 아니다.** 전부 Android 쪽 제스처 타이밍이며,
> 서버는 받은 이벤트를 스펙대로 정확히 처리한다. 수정은 하지 않았다.

### F-1 (중) 대기 중인 CLICK이 후속 드래그 도중에 발사되어 엉뚱한 좌표에서 클릭된다

**`TrackpadScreen.kt:211-235`**

- 기대값: 탭 A 직후 사용자가 곧바로 1손가락 드래그를 시작하면, 탭 A의 클릭은 **탭 A의 위치**에서 일어난다
  (이번 변경 이전 동작 — 탭이 끝나는 즉시 CLICK 전송).
- 실제값: `when (end.clickButton)`에서 `BUTTON_LEFT` 분기만 `pendingClickJob`을 취소한다.
  드래그로 끝난 제스처는 `else -> Unit`(`:234`)이라 **대기 중인 CLICK을 건드리지 않는다.**
  따라서 탭 A 종료 + 300ms 시점에, 그사이 UDP MOVE로 커서가 이미 이동한 상태에서 CLICK이 나간다.
  → "탭하고 바로 스와이프했더니 이동 중간 지점이 클릭됨".
- 왜 회귀인가: 이번 변경 전에는 CLICK이 탭 종료 즉시 나가서 MOVE보다 항상 앞섰다. 300ms 지연이
  생기면서 CLICK과 후속 MOVE의 **순서가 뒤집힐 수 있는 창**이 새로 열렸다. 요청 항목 5(1손가락
  MOVE/CLICK 회귀)에 해당한다.
- 수정 제안: 새 제스처에서 **첫 MOVE/SCROLL이 방출되는 순간** 대기 클릭을 즉시 flush 한다.
  `TrackpadScreen.kt:198-208` 블록에서 `move != null || scroll != null`일 때
  `pendingClickJob?.cancel(); pendingClickJob = null;` 후 `onClick()`을 직접 호출.
  `MOVE_MIN_DISTANCE_PX`(5px) 임계 덕분에 이 시점의 커서 오차는 무시할 수준이고, 두 번째 탭은
  MOVE를 방출하지 않으므로 더블탭 병합을 깨지 않는다. (스펙 변경이므로 리더 판단 필요)

### F-2 (하) 300ms 경계에서 CLICK과 DOUBLE_CLICK이 둘 다 나간다 — 개발자 보고 이슈 ① 실재 확인

**`TrackpadScreen.kt:217-229` × `DoubleTapDetector.kt:63-69`**

- 기대값: 어떤 경우에도 두 탭은 `CLICK 2개` 또는 `DOUBLE_CLICK 1개` 중 하나로만 번역된다.
- 실제값: 경합이 **실재한다.** 판정은 `System.currentTimeMillis()` 차이로 `elapsed <= 300`(경계 포함,
  `DoubleTapDetector.kt:65`), 전송은 코루틴 `delay(300)`(`TrackpadScreen.kt:227`)이라 시간축이 다르다.
  두 번째 탭의 UP 이벤트가 `t1+300` 직전/직후에 들어오면, `delay`의 재개가 먼저 디스패치되어
  `onClick()`이 이미 실행된 뒤 `:217`의 `cancel()`이 도착할 수 있다. `onClick()`은
  `viewModelScope.launch`(`TrackpadViewModel.kt:60-64`)라 job 취소로 되돌릴 수 없다.
  결과: 서버에 `CLICK` + `DOUBLE_CLICK`이 연달아 도착.
- **영향도 평가(요청 항목 4-①): 낮음. 수정 불필요 수준.**
  - 창의 폭: pointerInput 코루틴과 지연 job이 **같은 메인 디스패처**에서 돌기 때문에 진짜 병렬은 아니고,
    "어느 continuation이 먼저 큐에 들어갔나"만 문제가 된다. 실질 창은 `t1+300` 근처 한 프레임(~16ms)
    이내이며, 그것도 300ms 구간의 **맨 끝**에서만 발생한다. 더블탭을 의도한 사용자는 보통 100~250ms에
    떨어지고, 두 번 클릭을 의도한 사용자는 400ms 이상이므로 실사용 히트율이 매우 낮다.
  - 결과의 파괴력: `CLICK → DOUBLE_CLICK`은 대부분의 앱에서 "클릭 후 단어 선택/열기"가 되어
    사용자가 의도했던 더블클릭 결과와 크게 다르지 않다. 파괴적 오동작이 아니다.
  - 다만 두 이벤트가 각각 별도 코루틴에서 `Dispatchers.IO`로 넘어가므로(`TcpClient.kt:72`)
    **와이어 순서가 보장되지 않는다** — 드물게 `DOUBLE_CLICK → CLICK` 순으로 도착할 수도 있다.
    (줄 단위 원자성은 `PrintWriter.println`이 보장하므로 JSON이 깨지지는 않는다.)
- 수정 제안(원한다면): `DoubleTapDetector.kt:65`를 `elapsed >= GestureConfig.DOUBLE_TAP_INTERVAL_MS`로
  좁혀 경계를 배제하면 창이 줄지만 없어지지는 않는다. 근본 해결은 판정 시각도 `delay`와 같은 시간축
  (`SystemClock.uptimeMillis()` 또는 코루틴 스코프 내 측정)으로 통일하는 것. **현재로선 그대로 두는 것을 권장.**

### F-3 (중) 좌클릭 직후 우클릭 시 순서가 역전된다 — 개발자 보고 이슈 ② 실재 확인

**`TrackpadScreen.kt:233`**

- 기대값: 사용자가 [1손가락 탭 → 2손가락 탭]을 하면 서버는 `CLICK(left)` → `CLICK(right)` 순으로 받는다.
- 실제값: `BUTTON_RIGHT -> onRightClick()`이 `pendingClickJob`을 전혀 건드리지 않는다.
  탭 A 후 300ms 안에 2손가락 탭을 하면 우클릭이 먼저 나가고, 좌클릭이 그 뒤에 도착한다.
- **영향도 평가(요청 항목 4-②): F-2보다 위험하지만, 발생 조건이 좁아 "허용 가능한 트레이드오프"로 판단.**
  - 위험한 이유: 우클릭은 Windows에서 **컨텍스트 메뉴를 띄운다.** 그 직후 도착하는 좌클릭은 커서가
    메뉴 원점에 있는 상태에서 발사되므로, 메뉴를 그냥 닫는 데 그칠 수도 있지만 **메뉴 첫 항목을
    눌러버릴 가능성**이 (DPI·메뉴 스타일에 따라) 있다. F-2와 달리 "사용자가 지시하지 않은 명령 실행"이
    될 수 있다는 점에서 질적으로 더 나쁘다.
  - 그럼에도 허용 가능하다고 보는 근거:
    1. 조건이 좁다 — 좌탭이 끝난 뒤 **300ms 안에 두 손가락을 내려놓고 200ms 이내에 떼는**(탭 조건)
       연속 동작이어야 한다. 트랙패드에서 좌클릭 직후 곧바로 우클릭하는 조작 자체가 드물고,
       보통은 "클릭 → 대상 확인 → 우클릭"으로 사이에 수백 ms가 들어간다.
    2. 커서가 움직이지 않으므로 좌클릭이 메뉴 **바깥**을 누를 일은 없고, 최악이라도 메뉴 원점
       (테두리~첫 항목 경계)이라 항상 항목이 실행되는 것도 아니다.
    3. 사용자가 즉시 알아차릴 수 있는(무음 실패가 아닌) 오동작이라 데이터 손실형 위험이 아니다.
  - 결론: **지금 고치지 않아도 되지만, 고칠 때 비용이 매우 싸다(한 줄)** — Phase 3 마무리나
    실기기 검증 때 함께 처리하기를 권장. 우선순위는 F-1보다 낮다.
- 수정 제안: `BUTTON_RIGHT` 분기에서 대기 클릭을 **취소가 아니라 flush**한다.
  ```kotlin
  MultiTouchGestureTracker.BUTTON_RIGHT -> {
      pendingClickJob?.cancel(); pendingClickJob = null
      onClick()        // 미뤄둔 좌클릭을 먼저 내보내 순서를 보존
      doubleTapDetector.reset()
      onRightClick()
  }
  ```
  취소(drop)가 아니라 flush여야 하는 이유: 사용자가 실제로 한 좌클릭을 삼키면 안 된다.
  2손가락 탭은 1손가락 탭과 절대 더블탭으로 병합될 수 없으므로 flush는 의미 손실이 없다.
  (스펙이 "우클릭은 이 로직과 완전히 무관"이라고 못박았으므로 **스펙 변경 사항** — 리더 결정 필요)

### F-4 (하) 우클릭이 DoubleTapDetector 상태를 리셋하지 않아 우클릭을 사이에 낀 두 좌탭이 병합된다

**`TrackpadScreen.kt:232-233`**

- 기대값: [좌탭 A] → [우탭] → [좌탭 C] 는 `CLICK(left)`, `CLICK(right)`, `CLICK(left)` 세 개.
- 실제값: 우클릭 분기가 `doubleTapDetector`를 건드리지 않으므로 A의 기록이 살아남는다.
  C가 A로부터 300ms·40px 이내면 `onTap`이 `true`를 반환해 **`DOUBLE_CLICK`이 나간다**
  (게다가 A의 대기 클릭은 C 시점에 취소되어 사라진다). 실제 와이어: `CLICK(right)`, `DOUBLE_CLICK`.
- 영향도: F-3과 같은 조건(300ms 안에 3연속 조작)이라 실사용 빈도는 매우 낮다. F-3 수정에
  `doubleTapDetector.reset()` 한 줄을 같이 넣으면 동시에 해소된다.
- 수정 제안: 위 F-3 스니펫의 `doubleTapDetector.reset()` 참조.

---

## 3. 미검증 (NOT VERIFIED)

실패로 간주하지 않는다.

- **N-1 실기기 더블클릭 인식** — `SendInput`을 모킹한 단위 테스트라 "시각차 0으로 주입한 4개 INPUT을
  실제 Windows 앱들이 더블클릭으로 받아들이는가"는 확인 불가. `GetDoubleClickTime`(기본 500ms) 안에
  들어가는 것은 확실하나, down-up 간 최소 간격을 요구하는 앱이 있을 가능성은 배제 못 함.
  탐색기/메모장 등에서 실기기 확인 필요.
- **N-2 체감 상수** — `DOUBLE_TAP_INTERVAL_MS=300L`(모든 좌클릭에 붙는 지연), `DOUBLE_TAP_DISTANCE_PX=40f`
  는 실기기 미검증. 특히 300ms 지연이 답답하게 느껴지는지는 사람이 만져봐야 안다.
- **N-3 Compose 계층 동작** — `TrackpadScreen`의 지연/취소 로직은 Compose `pointerInput` 의존이라
  단위 테스트가 없다. F-1~F-4는 전부 **코드 독해로 확인**한 것이며 자동 테스트로 고정돼 있지 않다.
  `DoubleTapDetector`(순수 로직)만 13종으로 커버됨. Compose UI 테스트나, 지연/취소 로직을 별도
  순수 클래스(`PendingClickScheduler` 같은)로 추출해 `runTest` 가상 시간으로 검증하는 방안 권장.

---

## 4. AGENTS.md 실제 구현과 어긋나는 부분 (요청 항목 7 — 목록만, 수정 안 함)

| 위치 | 현재 문서 | 실제 구현 |
|------|-----------|-----------|
| `AGENTS.md:96-97` (§4) | `// 더블클릭 (Phase 2, 미구현)` | 구현 완료. 또한 §6은 이 항목을 **Phase 3**로 분류 — 문서 내부에서 Phase 번호가 어긋남 |
| `AGENTS.md:96-97` (§4) | 부가 설명 없음 | SCROLL 항목처럼 "session 필드 없음 / TCP / 서버는 SendInput 1회로 down-up-down-up 4개를 원자 전송 / 커서 이동 없음" 주석 추가 필요 |
| `AGENTS.md:137` (§5 표) | `1손가락 더블탭 \| DOUBLE_CLICK \| Phase 2 \| ⬜ 미구현` | `✅ 완료`. 단계 표기도 Phase 3으로 통일 필요 |
| `AGENTS.md:149-164` (§5 감도 상수) | `DOUBLE_TAP_INTERVAL_MS`, `DOUBLE_TAP_DISTANCE_PX` 누락 | `GestureConfig.kt:66, 76`에 존재 (300L / 40f, 후자는 `TAP_MAX_DISTANCE_PX`의 2배) |
| `AGENTS.md:143-147` (§5 엣지 케이스) | 언급 없음 | **모든 1손가락 CLICK이 300ms 지연된다**는 구조적 트레이드오프가 문서에 전혀 없다. 새 엣지 케이스 항목으로 명시 필요 (+ F-1/F-3 순서 이슈도 함께) |
| `AGENTS.md:178` (§6 Phase 2 헤더) | `🔶 Phase 2 — ... (남은 항목은 더블탭 하나)` | 더블탭이 Phase 3으로 옮겨 구현 완료 → Phase 2는 `✅` 전량 완료 표기 가능 |
| `AGENTS.md:192` (§6 핵심 파일) | `다음 확장 지점은 더블탭/탭홀드 드래그` | 더블탭 완료 → `탭홀드 드래그`만 남음 |
| `AGENTS.md:197` (§6 Phase 3) | `[ ] 1손가락 더블탭 → DOUBLE_CLICK (타이머 기반 판정)` | `[x]`. "타이머 기반"은 맞으나 실제 방식은 **"지연 후 확정"**(단일 클릭을 300ms 미뤘다가 두 번째 탭이 없을 때 전송) — 이 한 줄 설명을 넣어두면 이후 작업자가 지연의 존재를 놓치지 않는다 |
| `AGENTS.md:36-37` (§2 구조) | trackpad 패키지에 `DoubleTapDetector.kt` 누락 | 신규 파일 존재 |
| `AGENTS.md:228-233` (§7 세션 흐름) | `TCP: DOUBLE_CLICK` 줄 없음 | 추가 권장 (선택) |
| `AGENTS.md:294-306` (§10 미결 사항) | 해당 항목 없음 | "300ms 클릭 지연 체감", "지연 클릭 vs 후속 드래그/우클릭 순서"(F-1/F-3)를 미결 항목으로 올릴 것 권장 |

---

## 5. 종합 판단

- **경계면은 깨끗하다.** `type`·`button`·채널·프레이밍·기본값·알 수 없는 타입 처리까지 Android 송신부와
  서버 소비부가 정확히 맞물린다. 양쪽 모두 와이어 리터럴/플래그 시퀀스를 테스트로 잠갔다.
- 서버 구현은 이번 스펙의 핵심(**SendInput 1회 + 4-INPUT + 커서 이동 없음**)을 실측으로 만족한다.
- 남은 4건은 전부 Android 쪽 **시간 축** 문제다. 이 중 실사용에서 먼저 체감될 것은 F-1(탭 후 즉시
  드래그)이고, F-3은 위험도는 높지만 발생 조건이 좁아 지금은 트레이드오프로 수용 가능하다.
- 우선순위 제안: **F-1 > F-3(+F-4 동시 해결) > F-2(보류 권장)**. 셋 다 스펙 변경을 수반하므로
  리더 결정이 필요하다.
