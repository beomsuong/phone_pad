# 요청: 예외 처리 강화 (AGENTS.md Phase 4) — 조사로 확인된 실제 결함 2개

**범위 판단:** 서버(A)·Android(B) 각각 **단일 사이드**, 서로 독립. 와이어 프로토콜/이벤트 변경 없음 → 에이전트 2명 병렬, protocol-qa 생략.
"예외 처리 강화"는 범위가 모호한 항목이라 추측으로 넓히지 않고, 코드 조사로 **재현/확인된 결함만** 다룬다. 아래 "범위 밖"은 건드리지 말 것.

**병렬 세션 주의:** 다른 세션이 `discovery` worktree에서 UDP 자동 탐색(Android 연결 화면 + 서버 응답기)을 작업 중이다. `server.py`는 이번 작업에서 **건드리지 않는다**(A는 `input_controller.py`만). Android는 `TrackpadScreen.kt`/`ConnectPanel` 변경을 **최소**로 하고 새 로직은 새 파일/순수 함수로 분리해 충돌 면적을 줄일 것.

---

## A. 서버 — `SendInput` 반환값을 확인하지 않아 상태가 어긋난다

### 확인된 사실 (조사)
- `pc_server/input_controller.py`의 모든 `ctypes.windll.user32.SendInput(...)` 호출이 **반환값을 버린다** (`_move`, `_scroll`, `_send_button_flag`, `_click`, `_double_click`).
- `_drag_start()` 주석: "SendInput 이 실패하면 상태를 바꾸지 않는다" / `_drag_end()` 주석: "실패하면 `_drag_active`를 True로 남겨 안전장치가 재시도". **그런데 코드는 반환값을 보지 않고 무조건 `_drag_active`를 바꾼다** — 주석이 설명하는 동작은 ctypes가 예외를 던질 때만 성립하는데 `SendInput`은 실패 시 예외가 아니라 `0`을 반환한다. 즉 주석과 코드가 어긋난 실제 버그다.
- `SendInput`은 삽입한 이벤트 수를 반환하고, 입력이 막히면(예: 잠금 화면/UAC 보안 데스크톱 등 입력 데스크톱에 접근 불가) 0을 반환한다. **단, Microsoft 문서상 UIPI(관리자 권한 창에 일반 권한 프로세스가 입력을 주입하려는 경우)로 차단된 경우에는 반환값도 `GetLastError`도 실패를 알려주지 않는다** — 이 경우는 감지 불가능한 한계이므로 코드로 해결하려 하지 말고 문서에 한계로 남길 것(리더가 AGENTS.md 반영).

### 확정 스펙
1. `SendInput` 호출을 **한 곳의 헬퍼**(예: `_send_input(count, inputs) -> bool`)로 모으고, 반환값이 요청한 `count`보다 작으면 "주입 실패"로 판정한다. 각 `_move/_scroll/_click/_double_click/_send_button_flag`는 이 헬퍼를 쓰고, `handle_event`의 외부 동작(어떤 이벤트에 어떤 SendInput을 몇 번 부르는가)은 **바뀌면 안 된다** — 기존 테스트가 SendInput 호출 횟수/플래그 시퀀스를 고정하고 있다.
2. `_drag_start()`: `SendInput`이 **실패하면 `_drag_active`를 True로 만들지 않고** False를 반환. `_drag_end()`: 실패하면 **`_drag_active`를 True로 유지**(이후 연결 종료 안전장치 `force_release_drag`가 재시도할 수 있게)하고 False를 반환. 성공 경로의 동작/반환값은 그대로.
3. 실패는 **로그 폭주 없이** 알린다: MOVE는 초당 수십 번 오므로 실패 로그는 **rate limit**(예: 같은 종류는 N초에 1회 — 시간 소스를 주입 가능하게 해 테스트에서 실제 sleep 없이 검증)하고, 메시지는 **ASCII만**(AGENTS.md 섹션 9): 어떤 종류의 입력이 몇 개 중 몇 개 주입됐는지 + 마지막 에러 코드 + "input desktop blocked? (UAC prompt / lock screen)" 힌트. 예외를 밖으로 던지지 않는다(이벤트 하나의 실패가 TCP 세션을 끊으면 안 됨 — 기존 F-4 원칙).
4. 누적 실패 횟수를 읽을 수 있는 공개 카운터(예: `input_failures`)를 둔다(스레드 안전하게). **트레이 아이콘/UI에 표시하는 것은 이번 범위 밖**이다 — 나중에 트레이에 연결할 수 있게 값만 노출.
5. 에러 코드는 가능하면 `ctypes.GetLastError()`/`ctypes.get_last_error()`로 best-effort(신뢰 불가할 수 있음을 주석으로). **기존 테스트가 `ctypes.windll.user32.SendInput`을 patch하는 방식을 유지**해야 하므로 호출 경로(`ctypes.windll.user32.SendInput`)는 바꾸지 말 것.
6. **테스트 주의:** 기존 테스트의 `SendInput` mock은 반환값이 `MagicMock`이다. 이제 반환값을 정수로 비교하므로 mock이 **실제 계약(요청 개수를 반환)을 흉내내도록** 테스트를 갱신해야 한다 — 이건 계약이 의도적으로 바뀐 것이므로 정당한 수정이다. 단, 테스트가 검증하던 **호출 횟수·플래그 시퀀스 단언은 약화시키지 말 것**.
7. 실제 검증 1건: 이 머신에서 **무해한 실제 `SendInput`**(`MOUSEEVENTF_MOVE`, dx=0, dy=0, 1개)을 호출해 반환값이 `1`인지 확인하고 요약에 기록(계약 "반환값 = 주입된 이벤트 수"가 이 환경에서 실제로 성립하는지). 커서가 움직이면 안 되므로 dx=dy=0만 사용.

### 서버 테스트
- 각 이벤트 경로에서 SendInput이 요청 개수보다 적게 반환하면 실패 카운터가 오르고 예외가 없다
- `_drag_start` 실패 시 `_drag_active` False 유지 / `_drag_end` 실패 시 True 유지 → `force_release_drag`가 이후 재시도해 성공하면 False로
- rate limit: 같은 창 안 연속 실패는 로그 1줄, 창이 지나면 다시 1줄(주입한 시간 소스로)
- 성공 경로의 기존 동작 회귀 없음 (기준선 `227 passed, 1 skipped` 이상)

### 서버 범위 밖
UIPI 차단 감지, 트레이 표시, `server.py`/`single_instance.py`/`logging_setup.py` 변경, 서버 기동 실패 시 안내창.

---

## B. Android — 연결 타임아웃 없음 · 첫 연결 취소 불가 · 영어 원문 오류 메시지

### 확인된 사실 (조사)
- `TcpClient.connect()`가 `Socket(host, port)`를 쓴다 → **연결 타임아웃이 없다.** 틀린 IP(다른 네트워크/꺼진 PC)를 입력하면 OS 기본 타임아웃(수십 초)까지 "연결 중..."에 갇힌다.
- `ConnectingPanel`의 "취소" 버튼은 재연결(`Reconnecting`)에만 있다. **첫 연결(`Connecting`)에는 취소가 없어** 그 수십 초 동안 빠져나갈 방법이 없다.
- 연결 실패 메시지는 예외 `e.message` **원문 그대로** UI에 노출된다(예: `failed to connect to /192.168.0.5 (port 9000) from /:: (port 41822) after 21000ms`). 한국어 사용자에게 무슨 조치를 해야 하는지 전혀 알려주지 않는다.

### 확정 스펙
1. **연결 타임아웃:** `GestureConfig`에 `CONNECT_TIMEOUT_MS = 5000`(다른 네트워크 상수 옆, 근거 주석). `TcpClient.connect()`는 `Socket()` + `connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)`를 쓴다. `InetSocketAddress`가 unresolved면 `UnknownHostException`. 기존 세션 핸드셰이크 타임아웃(3초)/heartbeat 타임아웃 동작은 그대로.
2. **첫 연결 취소:** `Connecting` 화면에도 "취소" 버튼. 누르면 진행 중인 접속 시도를 즉시 끊고(블로킹 `connect`는 코루틴 취소로 안 풀리므로 소켓을 닫아 깨운다) **오류 표시 없이 IP 입력 화면(`Disconnected`)으로 복귀**한다. **취소된 시도의 뒤늦은 실패/성공이 상태를 `Error`/`Connected`로 덮어쓰면 안 된다**(기존 `generation`/`reconnectEpoch`/뮤텍스 밖 취소 패턴 — AGENTS.md 섹션 6 "재연결 구현 시 핵심 파일/설계"의 경합 설계를 반드시 읽고 따를 것. 취소는 뮤텍스 **밖**에서 먼저 한다).
3. **친절한 오류 메시지 (순수 함수):** `Throwable`(또는 실패 종류) → 한국어 메시지 + 조치 힌트를 돌려주는 **순수 함수**(Android 프레임워크 비의존, JUnit 테스트 가능). 최소 매핑:
   - 연결 거부(ConnectException, `refused`) → 서버 미실행/방화벽(TCP 9000) 확인
   - 연결 타임아웃(SocketTimeoutException) → IP가 맞는지, PC와 같은 Wi-Fi인지 확인
   - UnknownHostException → 주소를 찾을 수 없음, IP 확인
   - 네트워크 없음(NoRouteToHostException, `unreachable`) → Wi-Fi 확인
   - 핸드셰이크 실패(null 세션) → Phone Pad 서버가 맞는지/버전 확인
   - 그 외 → `연결 실패: <원문>` 폴백(원문을 완전히 버리지 않는다)
4. **메시지 계약을 깨지 말 것:** 기존 `ConnectionState.Error("Heartbeat timeout")`, `Error("Connection lost")`, 재연결 트리거 로직은 **문자열에 의존하지 않는다**는 것을 확인하고 그 상태/문자열은 **유지**한다(기존 테스트가 이 문자열을 단언한다). 친절한 문구는 "사용자에게 보이는 표시" 계층에서만 적용한다 — 예: `ConnectionState.Error`에 원인 종류(`kind`)를 **추가**하고 UI가 종류→문구로 변환하거나, 표시용 변환 함수를 UI에서 호출. `Error` 동등성을 쓰는 기존 테스트가 있으면 새 필드의 기본값으로 호환되게 설계하고, 그래도 바뀌는 단언은 의도된 계약 변경 범위만 최소로 수정할 것. heartbeat 유실/재연결 소진 메시지도 사용자 표시 계층에서 한국어로 보여주되 내부 문자열은 그대로.
5. UI는 오류 원문을 숨기지 말고 **친절한 문구를 주 메시지로, 원문은 작은 보조 텍스트로** 함께 보여주는 정도면 충분(디자인 과설계 금지). 실기기/에뮬레이터 UI 확인은 이 환경에서 불가 — "UI 미확인, 컴파일/단위 테스트만 검증"으로 명시할 것.

### Android 테스트
- 오류 메시지 매핑 순수 함수: 위 각 종류 + 폴백 + null/빈 메시지
- 연결 타임아웃 상수와 `TcpClient`의 타임아웃 적용(루프백에서 응답 없는 소켓/닫힌 포트로 실제 검증 가능한 범위에서. 실제 5초를 기다리는 테스트는 만들지 말고 타임아웃 값을 주입/오버라이드해 짧게)
- 첫 연결 취소: 접속 시도 진행 중 취소 → `Disconnected`, 이후 뒤늦게 실패/성공해도 상태 불변(가짜 `TcpClient`로 접속을 걸어두고 완료 시점을 제어)
- 기존 테스트 전부 회귀 없음 (기준선: Android 217개). **`runTest` 함정(AGENTS.md 섹션 6 "테스트 함정"): heartbeat/재연결 루프를 살려 둔 채 테스트를 끝내면 무한 루프+OOM** — 새 테스트는 반드시 `disconnect()`/`Error` 종료로 끝낼 것. 테스트 실행은 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`(clean 필수 — UP-TO-DATE로 스킵됨).

### Android 범위 밖
IP 입력 형식 검증(기존 trim+blank 체크 유지), 포트 입력 UI, 앱 백그라운드 진입 시 드래그 종료(별개 이슈 — 실기기 검증 필요), 서버 자동 탐색(다른 세션 담당), `TrackpadEvent`/와이어 변경.

---

## 공통
- 에이전트는 **`AGENTS.md`/`CLAUDE.md`를 수정하지 않는다**(리더 담당) — 요약 파일에 무엇을 어떻게 바꿔야 하는지만 적을 것.
- **커밋하지 않는다.**
- 참고: `AGENTS.md` 섹션 6(재연결 설계·테스트 함정), 섹션 9(컨벤션), 섹션 10(미결 사항)
