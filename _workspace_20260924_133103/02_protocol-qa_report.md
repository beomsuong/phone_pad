# protocol-qa 리포트 — PIN 코드 인증 (Phase 5, TCP 핸드셰이크 파괴적 변경)

검증 방식: `request.md` 확정 스펙 + 양쪽 요약 + **양쪽 실제 미커밋 코드 동시 대조**, 그리고
경계면 주장 검증용 **실측 3종**:

1. `ServerRuntime`(실제 `server.py`, `InputController`만 스텁) + **Android `AuthHandshake.buildAuthLine()`을
   바이트 단위로 재현한 Python 클라이언트**로 7개 상태 왕복 — AUTH/SESSION/AUTH_FAIL/이벤트/UDP MOVE/DISCOVER.
2. 같은 실서버에 **실제 JVM 클라이언트**(`TcpClient.connect()`의 호출 순서를 그대로 옮긴 Java 프로그램,
   JDK 17)를 붙여 `readLine()`이 무엇을 받는지·어떤 예외가 나는지 관측 후 `ConnectionErrorClassifier`
   규칙을 그대로 적용.
3. 서버 테스트 부분 재실행: `test_pin_auth.py test_desktop_switch.py test_server_udp_session.py
   test_server_heartbeat.py test_server_drag.py test_discovery.py` → **333 passed**.

코드는 수정하지 않았다. 리더가 이미 전체(server 544/1 skipped, Android 420/0 failures)를 확인했으므로
전체 재실행은 생략했다.

---

## 요약

| # | 항목 | 판정 |
|---|------|------|
| 1 | 와이어 리터럴 정확 일치 (AUTH / SESSION / AUTH_FAIL, 이스케이프) | **PASS** (+ W-1) |
| 2 | 인증 없음(`--no-auth`/기본 None) 하위 호환 · 기존 이벤트 회귀 | **PASS** |
| 3 | 재연결 AUTH_FAILED 조기 중단이 서버 카운터를 1회만 소비 | **PASS** (+ W-2) |
| 4 | 브루트포스 잠금 중 Android 분류 / 해제 후 재성공 | **FAIL (F-1)** — 잠금 중 분류가 `HANDSHAKE_FAILED`가 아니라 `UNKNOWN` |
| 5 | PIN이 탐색(UDP 9002) 응답에 새지 않음 | **PASS** |
| 6 | 로그에 PIN 미노출 | **PASS** (+ W-3: 의도된 노출점이 1개가 아니라 2개) |
| 7 | 기존 프로토콜 무변경 · 기존 TCP e2e 테스트 전환 누락 없음 | **PASS** |
| 8 | PIN 길이/특수문자/빈 PIN 경계 | **FAIL (F-2)** — `--pin ""`에서 양쪽 결론이 어긋남 (+ W-4) |

**차단 등급 FAIL 없음.** F-1은 사용자에게 보이는 오분류(기능 정지는 아님), F-2는 개발용 플래그의
계약 구멍이다. 다만 **F-1은 리더가 AGENTS.md에 그대로 옮겨 적으려는 문장이 사실과 다르므로**
문서 반영 전에 고쳐야 한다(양쪽 요약이 똑같이 틀렸다).

---

## FAIL

### F-1 — 브루트포스 잠금 중 실패가 `HANDSHAKE_FAILED`가 아니라 `UNKNOWN`으로 분류된다

- **위치(서버)**: `pc_server/server.py:177-181` — 잠긴 IP는 `recv`를 **한 번도 하지 않고** 즉시
  `conn.close()`.
- **위치(Android)**: `domain/model/ConnectionErrorClassifier.kt:78-89` (`fromMessage`) +
  `data/network/TcpClient.kt:91` (`r.readLine()`).
- **기대(양쪽 요약의 진술)**: android-dev 요약 §6-1·§6-6과 server-dev 요약 "미해결/한계"가 모두
  "잠금 상태의 무응답 close는 앱에서 `HANDSHAKE_FAILED`(= '서버가 아니거나 버전이 다릅니다')로
  보인다"고 적고 있다.
- **실제**: 잠금 경로는 **AUTH 바이트를 읽지 않고** 소켓을 닫으므로 수신 큐에 미판독 데이터가 남은
  채 close가 일어나 커널이 **RST**를 보낸다. 클라이언트는 clean EOF(`readLine()==null`)를 받지 못하고
  **`java.net.SocketException`**으로 빠져나온다. 실측(JDK 17, 같은 실서버):

  | 서버 상태 | JVM 클라이언트가 관측한 것 | 분류 결과 |
  |---|---|---|
  | 인증 ON, 정답 PIN | `{"type": "SESSION", ...}` | SUCCESS |
  | 인증 ON, 오답 PIN | `{"type":"AUTH_FAIL","reason":"invalid_pin"}` | `AUTH_FAILED` |
  | 형식 오류 → 조용히 close (**AUTH 줄을 읽은 뒤** close) | `readLine()==null` (clean EOF) | `HANDSHAKE_FAILED` ✅ |
  | **잠금 → 읽지 않고 close** | `threw java.net.SocketException: <OS 메시지>` | **`UNKNOWN`** ❌ |

  `SocketException`은 `classifyOne`의 타입 분기에 없고(부모가 `IOException`,
  `ConnectException`이 아니다), 메시지도 `refused`/`unreachable`/`timed out`/`timeout`/
  `no route to host`/`unable to resolve host` 중 어느 키워드도 담지 않는다(Windows는 지역화된
  OS 문장, Android는 `Connection reset by peer` 또는 `Software caused connection abort: recv failed`).
  그래서 `UNKNOWN`으로 떨어지고, `ConnectionErrorMessages.userMessage` 는
  `UNKNOWN_PREFIX + 원문`을 그대로 보여준다 → 사용자는 **"연결 실패: Connection reset by peer"**
  같은 원문 영문/시스템 문장을 본다. 요약이 말한 문구가 아니고, 오히려 더 나쁘다.
- **재현 시나리오**: 사용자가 틀린 PIN으로 연결 버튼을 5회 누른다(각 1회 실패 = 서버 카운터 5) →
  IP 잠금 → 6번째로 **올바른** PIN을 입력해도 서버가 읽지 않고 닫는다 → 화면에 원문 시스템 메시지.
  최대 60초간 이 상태가 지속된다.
  (실측 스크립트: 5회 실패 유도 → `is_locked_out==True` 확인 → JVM 클라이언트 2회 붙여 동일 결과)
- **수정 제안** (택 1, 둘 다 코드 1~2줄):
  - (A) 서버 쪽에서 **조용히 닫는 두 경로의 와이어 동작을 정말로 같게** 만든다 —
    `pc_server/server.py:177-181`의 잠금 분기에서 close 전에 수신 큐를 한 번 비워 clean FIN이
    되게 한다. 반드시 **짧은 타임아웃을 먼저 걸 것** (그러지 않으면 아무것도 보내지 않는 상대에게
    블로킹된다):
    ```python
    auth_limiter.note_blocked(key)
    try:                      # clean FIN 을 위해 수신 큐만 한 번 비운다 (AUTH 는 해석하지 않는다)
        conn.settimeout(0.05)
        conn.recv(AUTH_MAX_LINE_CHARS)
    except OSError:
        pass
    return False, ""
    ```
    그러면 스펙이 의도한 "형식 오류와 구분 불가"가 **바이트 수준에서 실제로** 성립하고(지금은
    RST vs FIN 으로 구분 가능하다 — 공격자에게 잠금 여부가 새는 셈이기도 하다), 앱은
    `HANDSHAKE_FAILED`로 떨어져 두 요약의 진술이 맞아진다. → **권장**(스펙 의도에 가장 충실).
    비용은 잠긴 연결당 최대 50ms 이고, 읽은 바이트는 버린다(= "읽지도 않고 닫는다"는 스펙의
    의도인 '판정하지 않는다'는 유지된다).
  - (B) Android 쪽에서 `ConnectionErrorClassifier.fromMessage`에 `reset`/`abort` 키워드를 추가해
    `CONNECTION_LOST`로 보내거나, `TcpClient.connect()`의 핸드셰이크 읽기에서 `SocketException`을
    `HANDSHAKE_FAILED`로 접는다. (A)보다 약하다 — 잠금이 아닌 진짜 네트워크 리셋과 섞인다.
  - 어느 쪽이든 **AGENTS.md에 "잠금 중 실패는 `HANDSHAKE_FAILED`로 보인다"고 쓰기 전에** 먼저
    고칠 것. 지금 그대로 문서화하면 다음 세션이 틀린 계약을 물려받는다.
- **영향받는 에이전트**: server-dev (A안) / android-dev (B안) — **양쪽 모두에게 통보 필요**
  (양쪽 요약이 같은 오진을 적었다).

### F-2 — `--pin ""`: 서버는 "인증 ON + 기대값 빈 문자열", 앱은 "빈 PIN 전송 불가" → 아무도 못 붙는다

- **위치(서버)**: `pc_server/server.py:583-587` (`resolve_expected_pin` — `args.pin is not None`
  이므로 `""`를 그대로 기대값으로 채택) → `server.py:174` (`auth_enabled = expected_pin is not None`
  → **True**) → `pin_auth.pins_match(pin, "")`.
- **위치(Android)**: `presentation/trackpad/TrackpadViewModel.kt:164-165` (`if (pin.isBlank()) return`)
  + `TrackpadScreen.kt:206` (`enabled = hostInput.isNotBlank() && pinInput.isNotBlank()`).
- **기대(server-dev 요약)**: "`--pin \"\"`는 스펙대로 값을 그대로 쓰므로 빈 PIN이 되어
  **'아무 pin이나 통과'에 가까워진다**".
- **실제**: 정반대다. 인증이 **켜진** 상태이고 기대값이 `""`이므로 **오직 `pin:""`만 통과**한다.
  앱은 UI/ViewModel 양쪽에서 빈 PIN을 막으므로 `pin:""`를 **절대 보낼 수 없다**. 실측:

  ```
  --pin ""  + 앱이 보내는 "x"  → {"type":"AUTH_FAIL","reason":"invalid_pin"}
  --pin ""  + pin:""(앱 경로로는 불가) → {"type": "SESSION", ...}
  ```

  즉 `--pin ""`으로 띄운 서버는 앱에서 **접속 불가**이고, 사용자가 5번 시도하면 자기 IP가 잠긴다
  (그리고 그 잠금은 F-1 때문에 `UNKNOWN` 원문 메시지로 보인다). 개발 편의 플래그가 조용히
  "모두 차단 + 자기 잠금"으로 동작한다.
- **부수 불일치**: `pc_server/tray_status.py:61-69` `normalize_pin("")` → `None` →
  **트레이는 "인증 꺼짐"과 똑같이 그린다**(PIN 메뉴 항목 없음, 툴팁 접미사 없음). 같은 `""`를
  `authenticate_client`는 "인증 ON", 트레이는 "인증 OFF"로 해석한다 — 한 프로세스 안에서 두 갈래다.
- **재현 시나리오**: `python server.py --pin ""` → 폰에서 아무 PIN이나 입력해 연결 → AUTH_FAIL 반복
  → 5회째부터 잠금. 트레이에는 PIN 표시가 없어 사용자는 인증이 꺼진 줄 안다.
- **수정 제안**: `pc_server/server.py:583-587`에서 빈/공백만인 `--pin`을 명시적으로 거부한다 —
  ```python
  pin = getattr(args, "pin", None)
  if pin is not None:
      if not pin.strip():
          raise SystemExit("[!] --pin must not be empty (use --no-auth to disable authentication)")
      return pin
  ```
  (`--no-auth`로 매핑하는 것은 "기본 켜짐" 원칙과 충돌하므로 **거부**가 맞다.)
  거부하지 않기로 결정한다면, 최소한 (a) server-dev 요약의 "아무 pin이나 통과" 문장을 정정하고
  (b) `normalize_pin`과 `authenticate_client`의 빈 문자열 해석을 하나로 맞춰야 한다.
- **영향받는 에이전트**: server-dev.

---

## 주의 / 기록 (수정 필수 아님)

### W-1 — 앱이 사용자가 입력한 값과 **다른** PIN을 보낼 수 있고, 사용자는 그 사실을 모른다
`data/network/AuthHandshake.kt:44-47, 68-77`. 전송 직전 (a) 32자 초과분 절단, (b) 제어문자 제거를
한다. 둘 다 스펙이 허용한 방어이고 실측으로 **JSON이 깨지지 않고 줄 분할도 없음**을 확인했다
(아래 "항목별 확인 내역 1" 참조). 다만 결과가 `AUTH_FAIL`일 때 화면 문구는 "PIN이 올바르지 않습니다"뿐이라,
40자를 붙여넣은 사용자는 "32자만 나갔다"는 것을 알 수 없다. 6자리 PIN에는 영향 0이므로 방치 가능.
반대 방향 여담: `"48<TAB>3920"`처럼 제어문자가 섞인 오타는 제거 후 **정답이 되어 통과**한다(무해).

### W-2 — AUTH_FAILED 조기 중단은 "이미 잠긴 상태"를 덮지 못한다
`data/repository/TrackpadRepositoryImpl.kt:413-420`. 분기 조건이 `kind == AUTH_FAILED`인데, 잠금
상태의 실패는 F-1 때문에 `UNKNOWN`으로 온다. 따라서 **잠금 중에 시작된 재연결 묶음은 백오프 8회를
전부 소진**(~55초)한 뒤 `RECONNECT_FAILED`로 끝난다. 다만 잠금 경로는 서버가 실패로 **집계하지
않으므로** 잠금이 연장되지는 않는다(`pin_auth.AuthAttemptLimiter.record_failure`는 AUTH 줄을 읽은
뒤에만 호출됨) — 그래서 "자기 유발 잠금 방지"라는 원래 의도 자체는 유지된다. F-1을 (A)안으로
고치면 이 경로도 `HANDSHAKE_FAILED`로 정리된다.

### W-3 — PIN의 "의도된 노출점"은 1개가 아니라 2개다
server-dev가 AGENTS.md에 넣자고 제안한 문장("PIN 값은 `main()`의 시작 메시지 **한 줄** 외에 어떤
로그에도 남기지 않는다")은 트레이를 빼먹었다. `pc_server/tray_status.py:72-95`(툴팁 접미사
`" - PIN: {pin}"`)와 `pc_server/tray.py:184-186`(`pin_label()` 메뉴 항목)도 PIN을 화면에 띄운다 —
스펙 서버 4항이 요구한 것이므로 결함은 아니고, **문서 문장을 "콘솔 시작 줄 + 트레이 표시 2곳"으로
고쳐야** 한다. 로그(파일/stdout 반복 출력)에는 실제로 새지 않음을 확인했다(아래).

### W-4 — `--pin`은 서버가 원문 그대로 쓰는데 앱은 앞뒤 공백을 제거한다
`server.py:585-586`(strip 없음) vs `TrackpadViewModel.kt:164`(`trim()`). `--pin " 483920 "`으로
띄우면 앱은 절대 맞출 수 없다. 자동 생성 PIN에는 공백이 없으므로 개발 플래그 한정. F-2를 고칠 때
같은 자리에서 `args.pin.strip()`으로 정리하면 함께 닫힌다.

### W-5 — 구버전 앱 → 신버전 서버는 `TIMEOUT`으로 실패한다(의도된 파괴적 변경, 문서화만 필요)
서버가 먼저 말하지 않으므로 Phase 4 이전 앱은 3초 핸드셰이크 타임아웃 → `TIMEOUT` →
"연결 시간이 초과되었습니다. IP 주소가 맞는지…"를 본다(원인이 버전 불일치라는 단서 없음). 앱/서버를
함께 배포하면 되는 문제지만 AGENTS.md 섹션 4에 한 줄 남기는 게 좋다.

---

## 항목별 확인 내역

### 1. 와이어 리터럴 — PASS
- Android `AuthHandshake.buildAuthLine` → `{"type":"AUTH","pin":"483920"}` (공백 없음, `type`→`pin` 순).
  `AuthHandshakeTest.kt:20-22`가 리터럴을 고정. 서버는 `pin_auth.parse_auth_message`가
  `json.loads` 후 `type`/`pin`만 보므로 **키 순서·공백에 비의존** → 계약 성립.
- 성공 응답은 `server.py:223`의 기존 줄 그대로(`{"type": "SESSION", "session": "..."}` — 공백 있는
  기본 `json.dumps`). `SessionHandshake.parseSession`이 `\s*`를 허용하는 정규식이라 무변경 통과.
  실측 확인.
- 실패 응답 `pin_auth.AUTH_FAIL_LINE` = `{"type":"AUTH_FAIL","reason":"invalid_pin"}` —
  Android `AuthHandshake.isAuthFail`/`parseFailReason`이 정확히 이 줄을 집는다. JVM 실측으로
  `AuthFailedException(reason="invalid_pin")` → `AUTH_FAILED` → "PIN이 올바르지 않습니다…" +
  보조 줄 `Auth failed: invalid_pin` 확인.
- **이스케이프/줄 분할 공격 실측**: PIN 입력란에 `483920"}\n{"type":"CLICK","button":"right"}`를
  넣은 경우 실제 와이어는
  `{"type":"AUTH","pin":"483920\"}{\"type\":\"CLICK\",\"button"}` 한 줄이고(개행 제거 + `"` 이스케이프
  + 32자 절단), 서버는 **유효 JSON으로 파싱해 `AUTH_FAIL`을 돌려준다**(조용한 close가 아니다).
  두 번째 줄로 쪼개져 이벤트로 오인되는 일 없음 — 스펙이 의도한 대로.
- 부가 확인: JVM의 `PrintWriter.println`은 Windows에서 **CRLF**를 쓰는데,
  `parse_auth_message`의 `text = data.strip()`이 `\r`를 흡수해 통과한다(실측). 이 `strip()`이
  JVM 테스트를 실기기와 등가로 만들어 주는 지점이므로 **제거하면 안 된다**(컨벤션으로 기록 권장).

### 2. 인증 없음 하위 호환 — PASS
- 서버는 `--no-auth`/`expected_pin=None`에서도 **첫 줄이 AUTH 형식이어야** 한다
  (`server.py:188-196`) → 와이어가 서버 설정에 따라 갈라지지 않는다는 스펙과 일치.
- Android는 서버 설정을 모른 채 **항상** AUTH를 먼저 보낸다(`TcpClient.kt:88`).
- 실서버 + Android 리터럴 재현 클라이언트로 `expected_pin=None` 상태에서 회귀 실측:
  `CLICK(left/right)` / `DOUBLE_CLICK` / `SCROLL` / `DRAG_START` / `DRAG_END` /
  `DESKTOP_SWITCH(direction=right)` / **알 수 없는 type** / `HEARTBEAT→HEARTBEAT_ACK` /
  **UDP 9001 MOVE(session 포함)** 전부 정상, 알 수 없는 type은 무시(크래시 없음).
  `discovery` 응답도 정상. 인증 ON + 정답 PIN 상태에서도 같은 목록을 동일하게 재확인.
- `ServerRuntime` 기본값이 `expected_pin=None, auth_limiter=None`이라 기존 호출자 무수정
  (`server.py:349-356`) — discovery 때의 교훈을 그대로 따름.

### 3. 재연결 AUTH_FAILED 조기 중단 ↔ 서버 잠금 카운터 — PASS
- 서버 카운터는 **AUTH 줄을 읽고 형식이 맞았는데 값이 틀린 경우에만** 증가
  (`server.py:196-200`, `pin_auth.AuthAttemptLimiter.record_failure`). 형식 오류·잠금·타임아웃은
  집계하지 않음(테스트 `..._not_counted_as_a_failure` 존재).
- Android 재연결 루프(`TrackpadRepositoryImpl.kt:413-420`)는 **1회차 실패가 AUTH_FAILED면 즉시
  `return@launch`** 하므로 그 묶음에서 `openConnection`은 더 호출되지 않는다 → **서버가 보는 실패는
  1회**. 잠금 임계 5회에 닿지 않는다. 의도 달성.
- `lastConnectedPin`은 `lastConnectedHost`/`Port`와 **같은 성공 블록에서만** 대입
  (`TrackpadRepositoryImpl.kt:221-223`, 다른 대입 지점 없음 — grep 확인)이라 "host는 있는데 pin은
  빈 문자열"인 상태가 생기지 않는다.
- Android 테스트로 고정됨: `TrackpadRepositoryAuthTest`의 "재연결 1회차 AUTH_FAIL → 600초 진행 후에도
  `connect` 호출 총 2회"와 "AUTH_FAILED가 아닌 실패는 기존처럼 2회차로 넘어감"(분기 확대 회귀 방지).
  android-dev의 변이 검사(분기를 `if(false)`로 → 정확히 1건만 실패)도 이 고정을 뒷받침.
- 남은 경계는 W-2.

### 4. 잠금 중 정상 흐름과의 상호작용 — **FAIL (F-1)**
- 분류: 위 F-1. 요약 2건의 진술이 실제 코드/실측과 불일치.
- 잠금 해제 후 재성공: `AuthAttemptLimiter.is_locked_out`가 창을 벗어난 실패를 즉시 pop하고
  비면 키를 삭제(`pin_auth.py:186-199`)하므로 60초 경과 후 같은 IP가 정상 연결된다. 서버 테스트
  `창 경과 후 재연결 가능`이 시간 주입으로 고정. **PASS**.
- 잠금은 실패로 집계되지 않아 **두드려도 연장되지 않는다**(확인) — 공격자에게 유리한 면은 없고,
  사용자 입장에서도 최대 60초로 유계.

### 5. 탐색(UDP 9002)에 PIN 미포함 — PASS
- `pc_server/discovery.py`·`data/network/DiscoveryProtocol.kt` **git diff 없음**, 두 파일에
  `pin` 문자열이 **0회** 등장(grep).
- `server.py:408-412`의 `DiscoveryResponder(tcp_port, host, discovery_port)` 호출에 PIN을 넘기는
  인자가 없다.
- 실측: 인증 ON 상태에서 `DISCOVER` → `{"type":"SERVER","name":"...","port":...}`, PIN 문자열
  미포함·`pin` 키 없음 확인.
- Android `selectServer`도 PIN을 채우지 않는다(`TrackpadViewModel.kt:138-141`, PIN 미대입 —
  `TrackpadViewModelPinTest`에 "서버 선택으로 PIN이 채워지지 않음" 테스트 존재).

### 6. 로그 미노출 — PASS (W-3 참조)
- 서버 실패 로그는 `server.py:198` `print(f"[!] Auth failed for {addr}")` — **값 없음**.
  잠금/차단 로그(`pin_auth.py:179-183, 206`)도 IP·횟수만, ASCII, 종류별 5초 rate-limit.
- **형식 오류 경로는 받은 줄 자체를 아예 로그하지 않는다**(`server.py:188-191`) — 시도된 PIN이
  깨진 JSON에 실려 있어도 새지 않는다. 중요 포인트로 확인.
- 파이프라이닝된 두 번째 AUTH 줄이 leftover로 이벤트 루프에 넘어가는 경우도 점검:
  `input_controller.handle_event`(175-202)는 알 수 없는 type을 **로그 없이** 무시하므로
  `{"type":"AUTH","pin":...}`가 로그에 찍히지 않는다. (`[!] Invalid JSON` 경로는 JSON이 깨졌을
  때만이고, 앱은 AUTH를 두 번 보내지 않는다.)
- Android: `main/` 전체에 `Log.*`/`println`/`print` **0건**(grep). `AuthFailedException`의 메시지는
  `"Auth failed[: reason]"`로 서버가 준 `reason`만 담고 PIN을 담지 않으며 64자로 절단
  (`AuthFailedException.kt:9-18`). `ConnectionState.Error.message`에 들어가는 값도 이것뿐.
- 의도된 노출: 콘솔 `[Server] PIN for this session: ...` **+ 트레이 툴팁/메뉴**(W-3).

### 7. 기존 프로토콜 회귀 없음 / 테스트 전환 누락 없음 — PASS
- AUTH 통과 **이후** 코드는 무변경(diff 확인). 유일한 구조 변경은 이벤트 루프에서 "완성된 줄 파싱"을
  `recv` **앞으로** 이동한 것(`server.py:236-280`)으로, AUTH 줄과 같은 청크에 붙어온 이벤트 유실을
  막는 목적이다. heartbeat `missed` 증감·`HEARTBEAT_MISS_LIMIT` 판정·`break`/`continue` 의미는
  동일(줄이 모두 소진된 상태에서만 `recv`에 도달하므로 등가).
- `conn.settimeout`: AUTH 단계 `AUTH_TIMEOUT_S=3.0` → SESSION 직후 `HEARTBEAT_INTERVAL_S`
  (`server.py:226`). 순서를 고정하는 서버 테스트 존재(`[AUTH_TIMEOUT_S, HEARTBEAT_INTERVAL_S]`).
- **누락 grep 재확인** (server-dev가 뒤늦게 찾은 `test_discovery.py` 외에 빠진 것이 있는지):
  - `tests/` 내 로컬 `FakeConn` **복사본 0개** — 남은 `class *Conn`은 전부 공용
    `tests/fake_conn.py:32`의 **서브클래스**(`test_pin_auth.Probe`/`FailingConn`,
    `test_server_heartbeat.FailingConn`/`AckFailingConn`,
    `test_server_udp_session.ProbeConn`/`ExplodingConn`).
  - `handle_client(` 직접 호출: `test_desktop_switch` / `test_server_drag` / `test_server_heartbeat` /
    `test_server_udp_session` / `test_pin_auth` — 전부 공용 `FakeConn` 경유(AUTH 자동 공급).
  - 실소켓 핸드셰이크: `test_discovery.py:442` / `test_server_shutdown.py:129,518` /
    `test_pin_auth.py:853` — 모두 `AUTH_LINE`/`make_auth_line`을 먼저 보낸다. **다른 누락 없음.**
  - `SESSION`을 언급하는 나머지 파일은 `test_input_controller.py:104`뿐이고, 이는
    "`handle_event`가 알 수 없는 type을 무시한다"를 확인하는 무관한 테스트다.
  - `ProbeConn`/`ExplodingConn`이 `recv` 대신 `_recv_event`를 오버라이드한 전환은 적절하다 —
    AUTH 단계는 정상 통과하고 "SESSION 이후 첫 recv에서 터진다"는 원래 의도가 보존된다.
  - `recv_calls`/`timeouts`가 **SESSION 이후만** 센다는 규약(`fake_conn.py:47-50`) 덕에 기존
    단정의 의미가 유지된다. 다음 핸드셰이크 변경은 이 한 파일에서 흡수 가능 — 좋은 설계.
- 부분 재실행: 위 6개 파일 **333 passed**.

### 8. PIN 길이/특수문자/빈 PIN 경계 — **FAIL (F-2)**, 길이 쪽은 PASS
- `AUTH_PIN_MAX_LENGTH=32`(Android, 전송 전 절단) vs `AUTH_MAX_LINE_CHARS=4096`(서버 첫 줄 상한):
  최악값 계산 — PIN 32자가 전부 `\`/`"`면 이스케이프 후 64자, 고정부 22자 + 개행 = **87바이트**.
  4096의 2% 수준으로 **여유 충분**, 계약이 닫힌다. **PASS**.
- 아주 긴 PIN 실측: 5000자 입력 → 와이어는 `{"type":"AUTH","pin":"999…9"}`(32자) →
  서버 `AUTH_FAIL`. 양쪽 결론 동일("거부"). **PASS**.
- 빈 PIN: 앱은 `pin.isBlank()`에서 막혀 **전송 자체가 불가**(`TrackpadViewModel.kt:164-165`,
  버튼도 비활성). 서버 `--pin ""`은 "인증 ON + 기대값 빈 문자열" → **F-2**.
- 참고: `--no-auth` 서버에서도 앱은 사용자가 아무 값이나 입력해야 연결 버튼이 켜진다(스펙
  "PIN 입력란 필수"대로). 개발 편의 측면의 마찰이지만 스펙 준수이므로 결함으로 보지 않는다.

---

## 미검증 (범위 밖 / 수단 없음)

- 실기기 ↔ 실서버 왕복(방화벽 프롬프트, 숫자 키패드 노출, 입력란 2개 + 오류 2줄의 소형 화면 잘림,
  `ImeAction.Next` 포커스 이동 체감). 양쪽 요약도 동일하게 미검증으로 보고.
- Compose UI 테스트 부재(기존 한계) — PIN 입력란의 라벨/키보드 타입/버튼 활성 조건은 코드 리뷰로만 확인.
- exe 재빌드 후 PIN 출력(`--noconsole` 경로는 `logging_setup` 경유 테스트만 존재).
- 실 pystray 환경의 PIN 메뉴 렌더 — server-dev가 임시 venv에서 실측 보고(재확인은 안 함).
