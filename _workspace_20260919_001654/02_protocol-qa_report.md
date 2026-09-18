# protocol-qa 검증 리포트 — 2손가락 드래그 → 스크롤 (SCROLL)

검증일: 2026-09-18
검증 방식: 양쪽 동시 읽기(Android 송신부 ↔ 서버 소비부) + 양쪽 테스트 실제 실행 + 와이어 리터럴 실증

| 구분 | 개수 |
|------|-----:|
| 통과 (P) | 14 |
| 실패 (F) | 4 |
| 관찰/권고 (O) | 4 |
| 미검증 (U) | 5 |

**심각한(치명적) 실패 없음.** 경계면 계약(타입 문자열·필드명·타입·채널·부호)은 완전히 일치한다.
실패 4건은 전부 경계면 "바깥"의 상태/견고성/문서 문제이며, 스크롤 기능 자체는 정상 동작한다.
다만 **F-1과 F-2는 실기기에서 사용자가 체감하는 오동작**이라 릴리스 전 수정을 권한다.

---

## 1. 통과 (PASS)

### P-1. 이벤트 타입 문자열 일치
- Android: `TrackpadRepositoryImpl.kt:123` → `"type":"SCROLL"`
- Server: `input_controller.py:48` → `elif t == "SCROLL":`
- 대소문자·철자 동일.

### P-2. 필드명·타입 일치 (camelCase/snake_case 혼용 없음)
- Android가 내보내는 키: `type`, `dx`, `dy` (전부 소문자). `session` 없음.
- Server가 읽는 키: `event.get("type")`, `event.get("dx", 0)`, `event.get("dy", 0)`.
- `TrackpadEvent.Scroll(val dx: Int, val dy: Int)` — Int이므로 Kotlin 문자열 템플릿이 소수점을 붙이지 않는다.
  `TrackpadRepositoryImplTest.kt:124`가 `contains(".")`를 명시적으로 금지해 회귀를 막고 있다.

### P-3. 와이어 바이트 실증 (직접 교차 실행)
Android가 만드는 **리터럴 문자열 그대로**를 `server.py`의 버퍼 분리 로직(`split("\n") → strip() → json.loads → isinstance(dict)`)과
실제 `InputController.handle_event`에 통과시켜 확인함:

```
'{"type":"SCROLL","dx":0,"dy":-3}'  -> {'type':'SCROLL','dx':0,'dy':-3} -> _scroll(0, -3)
'{"type":"SCROLL","dx":2,"dy":5}'   -> ...                              -> _scroll(2, 5)
'{"type":"SCROLL","dx":-1,"dy":0}'  -> ...                              -> _scroll(-1, 0)
'{"type":"SCROLL","dx":0,"dy":0}'   -> ...                              -> 호출 없음
```
양쪽 테스트가 각각 "문자열"과 "dict"만 고정하고 있어 **이 연결 고리 자체는 어느 테스트도 검증하지 않는다**(→ O-3 참고).
수동으로는 통과 확인.

### P-4. 정수 변환 무손실
Android `Int` → JSON 정수 리터럴 → Python `int(round(float(x)))`. 정수 입력에서 왕복 손실 없음.
`float()` 경유는 `-2.0`/`"1"` 같은 관용 입력 방어용이며 정상 경로에 영향 없음
(`test_handle_scroll_accepts_float_and_string_numbers`).

### P-5. 라인 프레이밍
`TcpClient.send` → `PrintWriter(autoFlush=true).println` (Android 개행 `\n`).
서버는 `buffer.split("\n", 1)` 후 `line.strip()` — `\r`가 섞여도 안전. CLICK/HEARTBEAT와 동일 경로.

### P-6. 부호 격리가 실제로 한 곳뿐임 (요청 #3)
전 저장소에서 스크롤 부호를 건드리는 코드를 추적한 결과, **부호 변환은 `input_controller.py:81,83` 두 줄에만 존재**한다.
- `MultiTouchGestureTracker.accumulateScroll` (184-193행): 양수 상수(`40f`)로 나누기만 하고 부정(negation) 없음. `toInt()`는 0 방향 절사라 양/음 대칭.
- `TrackpadViewModel.sendScroll` (72-76행): 그대로 전달.
- `TrackpadRepositoryImpl` (123행): 그대로 직렬화.
- `InputController.handle_event` (52-55행): `round`만, 부호 조작 없음.
→ 두 요약의 "뒤집을 거면 `_scroll` 한 곳만" 주장은 **사실**이다.

### P-7. 스크롤 ↔ 2손가락 탭(우클릭) 상호 배타 (요청 #4)
android-dev의 "isDrag로 구조적 상호 배타" 주장은 **코드상 성립**한다.
- `isDrag`는 구간 내에서 **sticky**다 (`MultiTouchGestureTracker.kt:151-153` — true가 된 뒤 구간 종료 전까지 false로 돌아가지 않음).
- `buttonForTap` (237행)이 `if (drag ...) return null` 이므로, 한 번이라도 스크롤이 나간 구간은 우클릭이 될 수 없다.
- 경계 케이스 검증: **"스크롤 방출 후 멈췄다가 다시 짧게 움직이는" 경우** → `isDrag`가 유지되므로 (a) 우클릭 오발동 없음, (b) 잔차가 살아 있어 짧은 이동도 누적되어 새지 않음. 정상.
- `totalMoved`는 누적 경로 길이가 아니라 **구간 시작점으로부터의 직선 거리**라서, 크게 움직였다가 원위치로 돌아와도 `isDrag` sticky 덕분에 스크롤이 끊기지 않는다.
- 단, 손가락을 어긋나게 떼는 꼬리 구간에는 별도의 구멍이 있다 → **F-2**.

### P-8. 0-스텝 SCROLL 미전송 (요청 #5)
- Android: `accumulateScroll`이 `stepsX == 0 && stepsY == 0`이면 `null` 반환(193행) → `TrackpadScreen.kt:184-187`에서 `onScroll` 자체를 호출하지 않음. **0/0 SCROLL은 전송 불가능**하다. 테스트로도 고정됨(`스텝이 하나도 차지 않은 프레임은...`).
- 서버 가드는 **죽은 코드가 아니다**: `{"type":"SCROLL"}` 처럼 필드가 누락된 입력, 다른 클라이언트/수동 테스트 입력을 막는 실제 방어선이며 `test_handle_scroll_missing_fields_defaults_to_zero`가 커버한다. 유지 권장.

### P-9. 채널 원칙 준수 + 서버측 이중 방어 (요청 #6)
- Android: `sendEvent`의 `Scroll` 분기는 `tcpClient.send`만 호출(123행). `udpClient`를 전혀 건드리지 않음. `TrackpadRepositoryImplTest`가 `coVerify(exactly = 0) { udpClient.send(any()) }`로 고정.
- Server: `handle_udp_packet`이 `event.get("type") != "MOVE"`를 즉시 거부(`server.py:72-73`) → SCROLL이 UDP로 새더라도 무시된다(`test_udp_non_move_type_is_ignored_on_udp_channel`).

### P-10. 기존 MOVE(UDP)/CLICK(TCP) 회귀 없음
- `sendEvent`의 Move/Click 분기 로직 변경 없음. `MOUSEINPUT`/`INPUT` 구조체 정의 변경 없음(`mouseData`는 원래부터 `c_ulong`).
- `_move`/`_click` 본문 변경 없음. `server.py`는 전혀 손대지 않음.
- 기존 Android 회귀 테스트(1손가락 MOVE/CLICK, 손가락 개수 전환, 꼬리 보정) 19개 + 서버 MOVE/CLICK 테스트 전부 통과.

### P-11. 알 수 없는 type 무시
`handle_event`는 if/elif만으로 구성되어 미지의 타입에서 조용히 반환한다.
`test_handle_unknown_type_does_not_raise`가 `_scroll` 미호출까지 검증하도록 갱신됨.

### P-12. 필드 누락 기본값
`event.get("dx", 0)` / `event.get("dy", 0)` 존재. 누락 시 0/0 → `_scroll` 미호출.

### P-13. 양쪽 테스트 존재 및 보고 수치 재현 (요청 #7)
직접 실행 결과 — **두 보고 수치 모두 정확히 재현됨.**

```
cd pc_server && python -m pytest -q
56 passed in 0.18s
```

```
cd phone_pad_app && ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest
BUILD SUCCESSFUL
```
| 테스트 클래스 | tests | fail | err |
|---|---:|---:|---:|
| ExampleUnitTest | 1 | 0 | 0 |
| data.network.SessionHandshakeTest | 8 | 0 | 0 |
| data.network.TcpClientTest | 4 | 0 | 0 |
| data.repository.TrackpadRepositoryHeartbeatTest | 12 | 0 | 0 |
| data.repository.TrackpadRepositoryImplTest | 13 | 0 | 0 |
| domain.usecase.SendEventUseCaseTest | 1 | 0 | 0 |
| presentation.trackpad.MultiTouchGestureTrackerTest | 32 | 0 | 0 |
| presentation.trackpad.TrackpadViewModelTest | 7 | 0 | 0 |
| presentation.util.GestureConfigTest | 8 | 0 | 0 |
| **합계** | **86** | **0** | **0** |

(주의: 첫 실행은 `UP-TO-DATE`로 스킵됐다. `cleanTestDebugUnitTest`를 함께 걸어 실제 재실행 후 XML 결과를 집계한 수치다.)

### P-14. 잔차 누적의 정확성
`scrollRemainderX/Y`는 구간 수명 동안 유지되고 `startSegment()`/`reset()`에서만 0으로 초기화된다(278-279, 255-256행).
`toInt()`가 0 방향 절사이므로 `|잔차| < 1`이 항상 보장되어 무한 누적이 없고, 양/음 대칭이다.
`GestureConfigTest`가 `SCROLL_SENSITIVITY_PX_PER_STEP > TAP_MAX_DISTANCE_PX` 부등식을 회귀로 고정해
"스크롤 개시 순간 툭 튀는" 사각지대를 막는다. 설계·구현·테스트가 일관됨.

---

## 2. 실패 (FAIL)

### F-1 [중] 고빈도 SCROLL이 heartbeat의 연결 해제 사유를 덮어쓴다 — 지난 F-2 수정의 회귀

**파일:라인** — `phone_pad_app/.../data/repository/TrackpadRepositoryImpl.kt:119-127`

**기대값 vs 실제값**
- 기대: SCROLL은 한 번의 2손가락 드래그에서 수십 회 발생하는 **고빈도 이벤트**다. 바로 위 `Move` 분기(98-109행)에 명시된 프로젝트 규약대로 — *"MOVE는 고빈도 이벤트여서 연결 상태를 Error로 덮어쓰지 않는다(F-2). 실패는 조용히 버린다. 여기서 상태를 덮어쓰면 (watchdog이 넣은) 원인 메시지만 지워진다"* — 전송 실패를 조용히 버려야 한다.
- 실제: `Click` 분기를 그대로 복사해 `catch` 안에서 `_connectionState.value = ConnectionState.Error(e.message ?: "Send failed")`로 상태를 덮어쓴다.

**왜 실제로 터지는가 (추정이 아님)**
`TcpClient.send`는 `checkNotNull(writer) { "Not connected" }`이므로, `cleanUp()`이 writer를 null로 만든 직후의 전송이 `IllegalStateException("Not connected")`를 던진다. 그런데 `reportConnectionLost()`(211-218행)는 **`cleanUp()`을 먼저 호출하고 그 다음에** `Error("Heartbeat timeout")`을 넣는다. `sendScroll`은 `viewModelScope.launch`로 코루틴을 개별 발사하므로, 스크롤 중 연결이 죽으면 큐에 남아 있던 SCROLL 코루틴들이 곧바로 `Error("Not connected")`로 덮어쓴다.
→ 사용자는 `Heartbeat timeout` / `Connection lost` 대신 **의미 없는 "Not connected"** 를 보게 되고, Phase 4 재연결이 이 메시지를 트리거로 쓰기로 되어 있어(AGENTS.md 섹션 6) 후속 기능까지 오염된다.
연결 해제(`disconnect()`) 직후에도 같은 이유로 `Disconnected` 화면이 `Error("Not connected")` 화면으로 뒤집힌다.

**수정 제안** — SCROLL을 CLICK이 아니라 MOVE와 같은 등급으로 취급한다:
```kotlin
is TrackpadEvent.Scroll -> {
    // 고빈도 이벤트 — 실패해도 연결 상태를 덮어쓰지 않는다 (F-2와 동일한 이유).
    // 실제 연결 유실은 heartbeat watchdog이 판정한다.
    runCatching {
        tcpClient.send("""{"type":"SCROLL","dx":${event.dx},"dy":${event.dy}}""")
    }
}
```
`TrackpadRepositoryImplTest`에 "전송 실패해도 connectionState가 Error로 바뀌지 않는다" 테스트를 추가할 것
(`TrackpadRepositoryHeartbeatTest`의 F-2 테스트와 같은 패턴).
담당: **android-dev** (서버 변경 불필요)

---

### F-2 [중] 스크롤 종료 시 손가락을 어긋나게 떼면 좌클릭이 오발동한다

**파일:라인** — `phone_pad_app/.../presentation/trackpad/MultiTouchGestureTracker.kt:219-232` (`resolveClickButton`)

**기대값 vs 실제값**
- 기대: 스크롤로 끝난 제스처는 어떤 클릭도 발생시키지 않는다(현재 `스크롤로 끝난 2손가락 구간은 우클릭으로 판정되지 않는다` 테스트가 담보하는 의도).
- 실제: **좌클릭이 발생한다.** 조건은 "2손가락을 뗄 때 남은 한 손가락이 50ms~200ms 사이로 화면에 남아 있는 경우".

**판정 경로 추적 (결정적)**
```
t=0    2손가락 down                         → segment(2)
t=..   드래그 → isDrag=true, SCROLL 방출
t=300  손가락 1개 뗌                        → startSegment(1): prevPointerCount=2, prevIsDrag=true,
                                              새 구간 isDrag=false, segmentStartTimeMs=300
t=360  마지막 손가락 뗌 → onGestureEnd(360)
       elapsed = 60
       isReleaseTail = prev(2) > last(1) && !isDrag(true) && 60 < MULTI_TOUCH_RELEASE_GRACE_MS(50)
                     = false            ← 유예 시간을 넘겨 꼬리 보정이 꺼진다
       → buttonForTap(pointerCount=1, drag=false, elapsed=60)
       → 60 < TAP_MAX_DURATION_MS(200) → BUTTON_LEFT  ⚠ 스크롤 직후 좌클릭
```
`isReleaseTail`은 **현재(꼬리) 구간의 `isDrag`** 만 보고 `prevIsDrag`는 보지 않으며, 유예 시간을 넘긴 순간 직전 구간이 스크롤이었다는 사실 자체가 판정에서 완전히 사라진다.
기존 테스트 `꼬리 구간이 유예 시간을 넘기면 남은 손가락의 좌클릭으로 판정된다`(196-209행)가 정확히 이 분기를 "정상"으로 고정하고 있다 — 2손가락 **탭**에는 맞는 규칙이지만, 2손가락 **스크롤**에는 틀렸다. 위험 창은 꼬리 길이 **[50ms, 200ms)** 로, 사람이 손가락을 어긋나게 떼는 전형적 구간과 정확히 겹친다.

**영향** — 웹페이지를 스크롤한 뒤 손을 떼는 것만으로 커서 위치의 링크/버튼이 클릭된다. 이전에는 2손가락 드래그가 아무 기능도 없어 드물게만 노출됐지만, SCROLL이 상시 사용 제스처가 되면서 노출 빈도가 급증한다(엄밀히는 선존재 결함이나 이번 기능이 실사용 위험으로 승격시켰다).

**수정 제안** — 꼬리 보정 여부와 무관하게 "직전 구간이 드래그였으면 클릭 없음"을 관철한다:
```kotlin
private fun resolveClickButton(timestampMs: Long, lastPointerCount: Int): String? {
    if (!hasSegment) return null

    val elapsed = timestampMs - segmentStartTimeMs
    val startedByRelease = hasPrevSegment && prevPointerCount > lastPointerCount

    // 직전 구간이 드래그(=스크롤/이동)였다면, 그 뒤에 붙은 짧은 꼬리는 탭이 아니다.
    if (startedByRelease && prevIsDrag && elapsed < GestureConfig.TAP_MAX_DURATION_MS) return null

    val isReleaseTail = startedByRelease && !isDrag &&
        elapsed < GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS
    ...
}
```
(임계값은 `GestureConfig` 상수로 분리 — AGENTS.md 섹션 9 규약)
추가 테스트: `2손가락 스크롤 후 꼬리가 유예 시간을 넘겨도 좌클릭하지 않는다`(꼬리 60ms), 그리고 회귀 보호용으로 `2손가락 탭 후 남은 손가락으로 다시 탭하면 좌클릭한다`(직전 구간 `isDrag=false`)를 함께 둘 것.
담당: **android-dev** (서버 변경 불필요)

---

### F-3 [하] AGENTS.md 섹션 4/5/6/7이 구현과 어긋남 (요청 #8)

**파일:라인 — 기대값 vs 실제값 — 수정 제안** (전부 문서만 갱신하면 되는 항목. 코드가 최신이고 문서가 낡음)

| # | AGENTS.md 위치 | 문서 내용 (현재) | 실제 코드 | 수정 제안 |
|---|---|---|---|---|
| 1 | 99-100행 (§4) | `// 스크롤 (Phase 2, 미구현)` | 구현 완료 | `// 스크롤 — ✅ 구현됨. dx/dy는 픽셀이 아니라 정수 휠 스텝(노치). px→스텝 변환과 잔차 누적은 Android(MultiTouchGestureTracker)가 끝낸 뒤 보낸다. 서버는 steps×WHEEL_DELTA(120)만 곱한다. 부호: dy 양수 = 손가락이 아래로, dx 양수 = 오른쪽. session 필드 없음.` |
| 2 | 137행 (§5 표) | `2손가락 상하좌우 드래그 \| SCROLL(dx,dy) \| Phase 2 \| ⬜ 미구현` | 구현 완료 | `✅ 완료` |
| 3 | 146-160행 (§5 감도 상수) | `SCROLL_SENSITIVITY_PX_PER_STEP` 누락 | `GestureConfig.kt:42`에 `40f` | `SCROLL_SENSITIVITY_PX_PER_STEP = 40f  // 휠 1스텝에 해당하는 centroid 이동 거리. 반드시 TAP_MAX_DISTANCE_PX보다 커야 함` 추가 |
| 4 | 141-144행 (§5 엣지 케이스) | 스크롤/탭 경계 규칙 없음 | `isDrag` sticky 재사용 | "2손가락 구간은 `isDrag`(탭 한계 초과) 이후부터만 SCROLL을 방출하며, `isDrag`가 곧 우클릭 배제 조건이라 탭/스크롤 사각지대가 없다" 추가. **F-2 수정 후** 꼬리 보정과 스크롤의 상호작용도 함께 명문화 |
| 5 | 180행 (§6 Phase 2) | `- [ ] 2손가락 드래그 → 스크롤 — ...계산은 해두고 버리는 중이라...교차 경계면 작업` | 완료 | `- [x] 2손가락 드래그 → 스크롤 — TCP + 정수 스텝. px→스텝 변환과 잔차 누적은 Android가 전담하고 서버는 steps×120만 수행. 감도 40px/스텝(탭 한계 20px의 2배로 사각지대 제거)` |
| 6 | 188행 (§6 핵심 파일) | `...스크롤(GestureDecision에 필드 추가)이 다음 확장 지점` | 이미 추가됨 | "스크롤까지 완료(`GestureDecision.scroll`/`ScrollDelta`). 다음 확장 지점은 더블탭/탭홀드 드래그" 로 교체 |
| 7 | 190행 (§6) / 297행 (§10) | sub-pixel 잔차 이슈가 전 범위처럼 읽힘 | SCROLL은 Android가 잔차를 완전 처리 | "`_move`에 한정된 이슈다. SCROLL은 앱이 잔차를 누적해 보내므로 해당 없음" 로 범위 명시 |
| 8 | 225행 (§7 세션 흐름) | `\|-- TCP: SCROLL --> \| (Phase 2 예정, 미구현)` | 구현 완료 | `✅ 구현됨 (정수 스텝, session 없음)` |
| 9 | 174행 (§6 헤더) | `🔶 Phase 2 — ... (일부 완료)` | 남은 항목은 DOUBLE_CLICK 하나 | 유지하되 잔여 항목이 더블탭뿐임을 명시 |

담당: **리더/문서 파트** (`docs(harness):` 커밋)

---

### F-4 [하] 숫자로 파싱 불가능한 dx/dy가 TCP 세션 전체를 끊는다

**파일:라인** — `pc_server/input_controller.py:52-53` (+ `pc_server/server.py:119-157`)

**기대값 vs 실제값**
- 기대: 잘못된 이벤트 한 건은 무시하고 다음 줄을 계속 처리한다 (UDP 경로 `handle_udp_packet`은 `try/except`로 이미 이렇게 동작하며, 서버는 "크래시하지 않는다"를 원칙으로 명시하고 있다 — AGENTS.md §4 123행).
- 실제: `float(event.get("dx", 0))`가 `{"type":"SCROLL","dx":"abc"}` 또는 `{"type":"SCROLL","dx":null}`에서 `ValueError`/`TypeError`를 던지고, `server.py`의 `try/except Exception`은 **`while True` 루프 바깥**(119행 try / 157행 except)이라 예외가 루프를 탈출해 `finally`에서 **세션 회수 + 소켓 close**로 이어진다. 잘못된 필드 하나에 연결이 통째로 끊긴다.

**영향** — 현재 Android 클라이언트는 항상 Int만 보내므로 정상 경로에서는 발생하지 않는다(그래서 심각도 "하"). 다만 MOVE에도 동일하게 존재하는 구조적 구멍이고, SCROLL이 표면적을 넓혔다. server-dev도 "별개 이슈"로 인지했으나 **연결이 끊긴다**는 점은 요약에 빠져 있다.

**수정 제안** — `server.py`에서 이벤트 단위로 격리한다 (UDP 경로와 대칭):
```python
try:
    controller.handle_event(event)
except Exception as e:      # 이벤트 한 건의 오류로 세션을 끊지 않는다
    print(f"[!] Event handling failed: {event!r}: {e}")
```
또는 `handle_event`의 MOVE/SCROLL 공통 숫자 변환 헬퍼(`_as_int(event, key)`)에서 파싱 실패 시 0으로 폴백.
테스트: `test_handle_client_survives_malformed_scroll_field`.
담당: **server-dev**

---

## 3. 관찰 / 권고 (수정 필수 아님)

### O-1. 현재 부호 규약은 "자연 스크롤"(macOS 기본)이며 Windows 기본값과 반대다
`dy` 양수(손가락 아래로) → `MOUSEEVENTF_WHEEL` 양수 delta → 휠 앞으로 굴림 → 뷰포트가 문서 앞쪽으로 이동 → **화면의 콘텐츠가 손가락을 따라 아래로** 움직인다.
이는 request.md의 문구("dy 양수 = 손가락이 아래로 = 콘텐츠를 아래로 스크롤")와는 **일치**하지만, Windows 정밀 터치패드 기본 설정("아래로 움직이면 아래로 스크롤")과는 **반대**다.
실기기에서 "거꾸로다"라는 피드백이 나올 가능성이 높다. 수정 지점은 `input_controller.py:81` 한 줄(`dy_steps` 앞 `-`)로 격리돼 있으므로 대응은 1줄이며, **Android는 절대 건드리지 말 것**(두 사이드가 동시에 뒤집으면 원위치된다).

### O-2. 경계면 연결 고리를 고정하는 테스트가 양쪽 어디에도 없다
Android 테스트는 리터럴 **문자열**만, 서버 테스트는 **dict**만 고정한다. 그 사이(문자열 → `json.loads` → dict)를 검증하는 테스트가 없어, 한쪽이 키 순서/철자를 바꿔도 양쪽 테스트는 초록으로 남는다.
권고: `pc_server/tests/`에 Android 와이어 리터럴을 **하드코딩 문자열 상수**로 두고 `handle_client`의 파싱 경로에 통과시키는 테스트를 1개 추가
(`test_handle_client_still_processes_tcp_click_over_newline_json`의 SCROLL 판.)

### O-3. `handle_event`와 `_scroll`의 0 가드가 중복
`input_controller.py:54`(`if dx != 0 or dy != 0`)와 `:84`(`if not deltas: return`)가 같은 일을 한다. 무해하며 `_scroll` 직접 호출도 보호하므로 유지해도 되지만, 의도(이중 방어)를 주석으로 남기면 나중에 한쪽이 "죽은 코드"로 오해받아 지워지는 걸 막을 수 있다.

### O-4. SCROLL 전송 순서가 보장되지 않는다
`TrackpadViewModel.sendScroll`이 이벤트마다 `viewModelScope.launch`를 새로 띄우고, `TcpClient.send`가 `withContext(Dispatchers.IO)`로 넘어가므로 IO 풀의 서로 다른 스레드에서 `println`이 실행된다(개별 `println`은 `PrintWriter` 락으로 원자적이라 문자열이 섞이지는 않는다).
스크롤 델타는 가산적이라 순서가 바뀌어도 **총량은 동일**하므로 실사용 영향은 미미하다. 다만 Phase 3의 `DRAG_START`/`DRAG_END`처럼 순서가 의미를 갖는 이벤트를 추가할 때는 이 구조가 곧바로 버그가 되므로, 그 시점에 단일 송신 큐(Channel) 도입을 검토할 것.

(android-dev가 이미 기록한 "축 잠금 없음 → 대각선 드래그에서 수평 휠 동시 발생"도 유효한 관찰이다. 경계면 문제는 아님.)

---

## 4. 미검증 (UNVERIFIED)

| # | 항목 | 사유 |
|---|------|------|
| U-1 | 실기기 스크롤 방향 체감 | 실제 Android 기기 + Windows 조합 미보유. O-1의 예측은 코드 기반 추론이며 실측 아님 |
| U-2 | `SCROLL_SENSITIVITY_PX_PER_STEP = 40f`의 체감 적정성 | request.md가 범위 밖으로 명시. 계산 근거는 타당하나 실측 아님 |
| U-3 | 실제 `SendInput` 휠 주입 동작 | 테스트는 `SendInput`을 모킹한다. `mouseData`의 2의 보수 인코딩(`delta & 0xFFFFFFFF`)이 Windows에서 실제 음수 휠로 해석되는지는 실행 미확인 (표준 관행상 올바른 구현) |
| U-4 | 고빈도 SCROLL의 TCP 처리량/지연 | 한 드래그당 수십 이벤트가 TCP로 나가지만 부하 측정 없음. `AGENTS.md §10`의 "바이너리 프로토콜 전환 — Phase 2 성능 테스트 후 결정" 판단 자료가 아직 없음 |
| U-5 | 실제 통합(앱↔서버 소켓) 동작 | 본 검증은 정적 대조 + 단위 테스트 + 와이어 리터럴 수동 통과까지다. 실제 소켓 연결로 스크롤이 움직이는지는 미확인 |

참고: 서버측 heartbeat 카운터는 **어떤 상향 데이터로도 리셋**되므로(`server.py:135`), 스크롤 중에는 서버가 세션을 타임아웃시킬 위험이 없다 — 이 부분은 문제없음으로 확인했다.

---

## 5. 후속 조치 요약

| 대상 | 항목 |
|------|------|
| **android-dev** | F-1 (SCROLL 실패가 연결 상태를 덮어쓰지 않도록), F-2 (스크롤 후 좌클릭 오발동) |
| **server-dev** | F-4 (이벤트 단위 예외 격리), O-1 대비(부호 뒤집기는 `_scroll` 한 줄 — 피드백 수신 시에만) |
| **리더/문서** | F-3 (AGENTS.md §4/5/6/7 갱신, 표 그대로 적용 가능) |
| **양쪽** | O-2 (경계면 연결 고리 테스트 1개 추가 — 서버 쪽에 두는 것을 권장) |
