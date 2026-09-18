# 02 · protocol-qa 경계면 정합성 리포트 — MOVE UDP 분리 + 세션 핸드셰이크

**검증자:** protocol-qa
**대상 커밋 상태:** 작업 트리 (android-dev / server-dev 병렬 구현 직후)
**검증 방식:** 양쪽 동시 읽기 + **실측**(서버 파서에 Android가 실제로 만드는 바이트를 직접 주입) + 양쪽 테스트 실제 실행
**코드 수정:** 하지 않음 (요청대로 기록만)

## 0. 집계

| 구분 | 개수 |
|------|------|
| ✅ 통과 | 14 |
| ❌ 실패 | 9 (코드 3 · 문서 6) |
| ⚠️ 미검증 | 4 |

**심각(블로커) 실패: 없음.** 코드 레벨 경계면(필드명/타입/채널/세션 왕복)은 **실측으로 맞물림 확인**. 실패 9건은 전부 (a) AGENTS.md 문서 지연 6건, (b) heartbeat 미구현에서 파생된 논리적 공백 2건, (c) 재연결 구간 토큰 초기화 누락 1건이며 정상 경로 동작을 막지 않는다.

---

## 1. 실측 검증 방법 (재현 가능)

QA가 별도로 실행한 검증:

1. **SESSION 라인 실제 바이트 추출** — `server.py:100`의 표현식을 그대로 평가
   → `b'{"type": "SESSION", "session": "0123456789abcdef0123456789abcdef"}\n'`
   (`json.dumps` 기본 separators라 **콜론 뒤에 공백이 있다**)
2. **Android `SessionHandshake` 정규식을 Python으로 1:1 이식**해 위 바이트(개행 제거 = `BufferedReader.readLine()` 결과)에 적용 → 토큰 정상 추출
3. **Android `UdpClient`가 보내는 문자열 9종**(정상/정수형 실수/지수표기/NaN/Infinity/개행 첨부/구 토큰/UDP CLICK)을 `server.handle_udp_packet()`에 직접 주입해 반환값·`_move` 호출 인자 관찰
4. 양쪽 테스트 실제 실행
   - `cd pc_server && python -m pytest -q` → **28 passed**
   - `cd phone_pad_app && ./gradlew :app:testDebugUnitTest` → **exit 0, 23 tests / 0 failures / 0 errors** (XML 결과로 확인: 8+10+1+3 + ExampleUnitTest 1)

실측 결과 요약:

| 케이스 (Android 송신 바이트) | 서버 처리 |
|---|---|
| `{"session":"<32hex>","type":"MOVE","dx":2.5,"dy":-1.0}` | `handled=True`, `_move(2, -1)` |
| `dx:7.0, dy:0.0` | `handled=True`, `_move(7, 0)` |
| `dx:1.0E-4` (Kotlin Float 지수표기) | `handled=True`, `_move` 미호출(0으로 반올림) |
| `dx:1.0E7` | `handled=True`, `_move(10000000, -10000000)` |
| `dx:NaN` / `dx:Infinity` | `handled=False` + 로그, **예외 비전파** |
| 끝에 `\n` 첨부 | `handled=True`, `_move(2,-1)` (무해) |
| 구/미등록 토큰 | `handled=False` |
| UDP로 온 CLICK | `handled=False` (채널 원칙 유지) |

---

## 2. ✅ 통과 항목 (14)

| # | 항목 | 근거 (좌: Android / 우: Server) |
|---|------|------|
| P-1 | **SESSION 핸드셰이크 바이트 정합** | `server.py:100` `json.dumps({"type":"SESSION","session":...})+"\n"` ↔ `SessionHandshake.kt:15-16` 정규식이 `"\\s*:\\s*"`로 공백 허용 → 실측 파싱 성공. 필드 순서·추가 필드도 무관 |
| P-2 | **핸드셰이크가 첫 바이트** | `server.py:98-106` accept 직후 recv 루프 진입 **전** 전송 ↔ `TcpClient.kt:38-45` `soTimeout=3000`으로 한 줄 읽기. 서버 지연 요소 없음(`registry.issue()`는 락 하나) |
| P-3 | **세션 필드명/타입 왕복** | 양쪽 모두 필드명 `session`, 타입 문자열. `TrackpadRepositoryImpl.kt:41,58`이 토큰을 **가공 없이 그대로** 보간 ↔ `server.py:36-40` `isinstance(token,str)` + 집합 조회. 서버 32자 hex, Android는 "비어있지 않은 문자열"만 요구 → 서버가 형식을 바꿔도 호환 |
| P-4 | **MOVE `type` 문자열 동일** | `TrackpadRepositoryImpl.kt:58` `"type":"MOVE"` ↔ `server.py:63` `event.get("type") != "MOVE"` |
| P-5 | **dx/dy는 JSON 숫자 리터럴 (문자열 아님)** | `TrackpadRepositoryImpl.kt:58`이 `${event.dx}`를 **따옴표 없이** 보간하고 `TrackpadEvent.Move`가 `Float`(`TrackpadEvent.kt:4`) → `2.5`/`-1.0` 형태의 JSON number ↔ `input_controller.py:37-38` `float(...)`이 숫자/문자열 모두 수용. android-dev의 "실수로 도착" 보고는 정확하며, 추가로 **문자열이 아님**을 QA가 확인 |
| P-6 | **채널 분기 원칙 (AGENTS.md 섹션 4)** | Move만 `udpClient.send`, Click은 `tcpClient.send` (`TrackpadRepositoryImpl.kt:52-64`). Android 테스트가 "MOVE는 TCP로 전혀 안 나감"을 검증 ↔ 서버는 UDP에서 MOVE 외 전부 거부(`server.py:63`) |
| P-7 | **개행 규약** | `UdpClient.kt:37-38` 패킷 하나에 개행 없음 ↔ `server.py:53` `json.loads`. 개행이 붙어도 무해함을 실측 확인 |
| P-8 | **알 수 없는 type 무시 (크래시 금지)** | `input_controller.py:34-42` if/elif만 → `SESSION`/`UNKNOWN`/빈 dict 무동작 ↔ `server.py:57-64,74-76` 비-dict/깨진 JSON/비UTF8/핸들러 예외 전부 `False` 반환 |
| P-9 | **필드 누락 기본값** | `server.py:70-71` `event.get("dx", 0)` + `input_controller.py:37-38` 재차 `get("dx", 0)` — 이중 방어 |
| P-10 | **CLICK 회귀 없음** | `{"type":"CLICK","button":"left"}` 문자열이 Phase 1과 동일(`TrackpadRepositoryImpl.kt:63`). 서버 buffer 분할 루프(`server.py:108-124`) 무변경, 청크 분할 CLICK 2개 파싱 테스트 존재. 실측 `_click('left')` 호출 확인 |
| P-11 | **포트 상수 일치** | `GestureConfig.kt:17,20` 9000/9001 ↔ `server.py:9-10` 9000/9001. 양쪽 모두 테스트로 고정(`GestureConfigTest`, `test_ports_match_spec`) |
| P-12 | **TCP 종료 시 세션 제거** | `server.py:127-132` `finally` 블록 → 정상 종료·예외 종료 모두 회수. 전송 실패 시도 즉시 회수(`server.py:102-106`). 재연결마다 새 토큰(테스트 존재) |
| P-13 | **disconnect 순서** | `TrackpadRepositoryImpl.kt:76-80` 토큰 null → UDP close → TCP disconnect. 토큰을 먼저 비우므로 close 사이에 MOVE가 새어나갈 수 없음. 서버는 TCP가 닫힌 뒤 회수 → **Android가 먼저 멈추고 서버가 뒤따르는** 안전한 순서 |
| P-14 | **동시성 보호 / 양쪽 테스트 존재** | `SessionRegistry`가 모든 접근을 `threading.Lock` 내부에서 수행(`server.py:17-44`) · UDP 스레드는 이 클래스만 공유. 신규 기능에 대해 Android 23 / Server 28 테스트 존재하며 QA가 직접 실행해 전부 통과 확인 |

---

## 3. ❌ 실패 항목 (9)

### 3.1 코드 레벨 (3)

#### F-1 (중) 서버가 세션을 회수해도 Android가 알 수 없다 — 커서만 조용히 멈춤

- **파일:라인** — `TrackpadRepositoryImpl.kt:53-60` (Move 분기) + `TcpClient.kt:48-50` (TCP 수신 루프 없음) vs `server.py:127-132` (`finally`에서 세션 제거)
- **기대값 vs 실제값** — 기대: 세션이 무효화되면 앱이 `ConnectionState.Error`/재연결로 전환. 실제: Android는 핸드셰이크 이후 TCP를 **한 번도 읽지 않으므로**, 서버 재시작·프로세스 종료·Wi-Fi 이탈로 세션이 회수되면 `sessionToken`은 그대로 남고 UDP `send()`는 무연결이라 성공하며 `connectionState`는 `Connected` 유지. 사용자에게는 "연결됨인데 커서가 안 움직임"으로 보임
- **수정 제안** — Phase 2 heartbeat 작업에서 (a) `TcpClient`에 `reader` 기반 수신 루프 추가(이미 `reader` 확보됨), (b) `{"type":"HEARTBEAT"}` 5초 주기 / `HEARTBEAT_ACK` 3회 미응답 → `Error`, (c) 서버는 세션 회수 시 가능하면 TCP로 통보. 즉시 조치 대안: 서버가 미등록 토큰 MOVE를 받으면 카운트하고 로그로 경고(진단성 향상)
- **비고** — 양측 summary가 모두 언급한 기지(known) 공백이나, **경계면 계약의 구멍**이므로 실패로 분류

#### F-2 (중) 재연결 구간에 구 토큰이 살아있다 — `connect()`가 진입 시 토큰을 비우지 않음

- **파일:라인** — `TrackpadRepositoryImpl.kt:32-43` (`connect()` 시작부에 `sessionToken` 초기화 없음) · `TcpClient.kt:31` (`connect()`가 내부에서 먼저 `disconnect()` 호출)
- **기대값 vs 실제값** — 기대: 새 연결 시도 시작 시점에 구 토큰/구 UDP 타깃이 즉시 무효. 실제: `tcpClient.connect()`가 기존 소켓을 닫는 순간 서버는 구 토큰을 회수하지만(`server.py:129`), Android의 `sessionToken`은 새 핸드셰이크가 끝날 때까지(TCP 연결 시간 + 최대 3초) **구 토큰 그대로**. 이 구간의 MOVE는 구 토큰으로 UDP 전송되어 서버에서 전량 드롭된다. 또한 `udpClient`의 `address`도 이전 호스트를 유지하므로 **다른 IP로 재연결하는 경우 이전 호스트로 패킷이 나간다**(수신 측에서는 무효 토큰이라 드롭 — 기능 영향은 없고 위생 문제)
- **수정 제안** — `connect()`의 `ConnectionState.Connecting` 직후에 `sessionToken = null; runCatching { udpClient.close() }` 추가(= 기존 `cleanUp()` 선행 호출). 회귀 테스트: "connect 재호출 직후~핸드셰이크 완료 전 MOVE는 전송되지 않는다"
- **담당** — android-dev

#### F-3 (하) sub-pixel 델타가 누적 없이 버려진다 (느린 정밀 이동 불가)

- **파일:라인** — `input_controller.py:37-40` (`int(round(...))` 후 `dx != 0 or dy != 0`) vs `TrackpadScreen.kt:156-161` (총 이동 5px 초과 후에는 프레임별 delta를 그대로 전송)
- **기대값 vs 실제값** — 기대: 작은 델타가 누적되어 결국 커서가 움직인다. 실제: `|dx*1.5| < 0.5`인 프레임은 0으로 반올림되어 완전히 소실(실측: `dx=1.0E-4` → `_move` 미호출). 잔차 누적기가 없어 아주 느린 드래그는 커서가 전혀 움직이지 않는다. 더하여 `int(round(2.5)) == 2` (Python banker's rounding)이고 `MOVE_SENSITIVITY=1.5f` 때문에 `x.5` 값이 자주 발생 → 미세한 하향 편향
- **수정 제안** — 서버 `InputController`에 sub-pixel accumulator 도입: `self._acc_x += float(dx); mv = int(self._acc_x); self._acc_x -= mv`. 또는 Android가 잔차를 누적. Phase 1부터 있던 동작이라 이번 변경의 회귀는 아니므로 **별도 이슈 권장**
- **담당** — server-dev (별도 이슈)

### 3.2 문서(AGENTS.md) 레벨 (6) — 리더가 갱신 예정

| # | 위치 | 문서 내용 | 실제 구현 | 제안 |
|---|------|-----------|-----------|------|
| D-1 (중) | `AGENTS.md:74-100` 섹션 4 "현재 구현 (Phase 1 — TCP 단일)" | 포트 TCP 9000 단일, MOVE 예시가 TCP 이벤트 목록에 포함 | TCP 9000 + UDP 9001 하이브리드가 **현재 구현**. MOVE는 TCP로 오지 않음 | 제목을 "현재 구현 (Phase 2 — 하이브리드)"로, MOVE를 UDP 절로 이동. SESSION 핸드셰이크 줄을 섹션 4에 정식 추가 |
| D-2 (중) | `AGENTS.md:196` 섹션 7 다이어그램 | `<-- {"session":"abc123"} ---` (**`type` 필드 없음**) | `{"type":"SESSION","session":"<32 hex>"}\n` | `{"type":"SESSION","session":"<32 hex>"}`로 수정. **이 다이어그램대로 구현하면 `SessionHandshake.parseSession`이 `type` 불일치로 null → 연결 실패**하므로 문서가 실제로 위험 |
| D-3 (하) | `AGENTS.md:105` 섹션 4 Phase 2 예시 | `{"session":"abc123", ...}` | 토큰은 `uuid.uuid4().hex` 32자리 hex | 토큰 길이·문자셋 명시(+ "앱은 비어있지 않은 문자열이면 수용" 주석) |
| D-4 (하) | `AGENTS.md:129-136` 섹션 5 감도 상수 표 | `DEFAULT_PORT = 9000`까지만 | `UDP_PORT = 9001`, `SESSION_HANDSHAKE_TIMEOUT_MS = 3000` 존재 | 두 상수 추가 |
| D-5 (하) | `AGENTS.md:151,155` 섹션 6 Phase 2 체크박스 | `[ ] MOVE를 UDP(9001)로 분리...`, `[ ] Python 서버에 UDP 소켓 추가` | 구현 완료 | 두 항목 `[x]`로. 나머지(heartbeat/우클릭/스크롤/멀티터치)는 미구현 유지 |
| D-6 (하) | `AGENTS.md:206-213` 섹션 8 + `AGENTS.md:19-40` 섹션 2 | "TCP 9000 포트에서 대기"만 명시 / 트리에 신규 파일 없음 | UDP 9001도 바인딩(TCP보다 먼저). 신규: `data/network/SessionHandshake.kt`, `data/network/UdpClient.kt`, `pc_server/tests/` | 섹션 8에 UDP 9001 및 **Windows 방화벽 UDP 인바운드 허용** 안내 추가, 섹션 2 트리에 3개 경로 추가 |

**부가(문서 정합성, 참고)** — `AGENTS.md:190-192` 섹션 7의 "UDP broadcast 탐색 / response"는 Phase 4 항목(`AGENTS.md:174`)인데 Phase 2+ 흐름도에 그려져 있어 구현된 것처럼 읽힌다. `(Phase 4 예정)` 주석 권장. 또한 섹션 9 컨벤션(`AGENTS.md:224-225`)의 "새 이벤트 타입 추가 시" 절차에 **채널 선택(MOVE류=UDP) 및 세션 필드 포함 여부** 단계가 빠져 있다.

---

## 4. ⚠️ 미검증 항목 (4)

| # | 항목 | 이유 / 필요한 후속 |
|---|------|--------------------|
| U-1 | **실기기 ↔ 실서버 왕복** | QA는 서버 파서에 Android 산출 바이트를 주입하는 방식으로 검증. 실제 폰에서 `DatagramSocket` 송신 + Windows 방화벽 통과 + `SendInput` 커서 이동까지의 E2E는 미수행. Windows 방화벽이 UDP 9001 인바운드를 막을 가능성이 가장 큰 미확인 리스크 |
| U-2 | **다중 클라이언트 정책** | `SessionRegistry`가 집합 기반이라 활성 세션 여러 개가 동시에 커서를 움직일 수 있음(`server.py:19`). 정책 미정(AGENTS.md 섹션 10) → 실패로 분류하지 않음 |
| U-3 | **UDP 부하/유실 특성** | 고빈도 MOVE(터치 프레임당 1패킷)의 패킷 유실·재정렬·버스트 시 지연을 측정하지 않음. 바이너리 프로토콜 전환 판단(AGENTS.md 섹션 10) 근거가 되므로 Phase 2 성능 테스트 필요 |
| U-4 | **HEARTBEAT / SCROLL / DOUBLE_CLICK / DRAG** | 양쪽 모두 미구현 — 검증 대상 없음(실패 아님). AGENTS.md 섹션 4에 스펙만 존재 |

---

## 5. 관찰: 테스트 구조상의 맹점 (양쪽 모두에게)

두 테스트 스위트는 **각자 자기 직렬화기로 만든 입력**을 검증하므로, 필드명/형식이 한쪽에서만 바뀌어도 양쪽 테스트는 계속 녹색이다.

- 서버: `test_server_udp_session.py:83` 등 모든 UDP 패킷을 `json.dumps({...})`로 생성 — Android가 실제로 만드는 **문자열 리터럴**을 쓰지 않는다
- Android: `SessionHandshakeTest.kt` 어디에도 서버 실제 출력인 `{"type": "SESSION", "session": "..."}` (콜론 뒤 공백 포함, `json.dumps` 기본형)이 케이스로 없다. 현재 정규식은 통과하지만(QA 실측 확인), 파서를 고정 문자열 비교로 리팩터링하면 조용히 깨진다

**제안(양쪽 공통)** — 골든 리터럴 교차 테스트를 각 사이드에 1개씩 추가:
- 서버: `server.handle_udp_packet(b'{"session":"<32hex>","type":"MOVE","dx":2.5,"dy":-1.0}')` → `_move(2,-1)`
- Android: `SessionHandshake.parseSession("""{"type": "SESSION", "session": "0123...cdef"}""")` → 토큰 반환

---

## 6. 담당자별 액션 요약

| 담당 | 항목 |
|------|------|
| **android-dev** | F-2 (connect 진입 시 토큰/UDP 초기화 — 유일한 즉시 수정 권장 항목), 섹션 5 골든 파싱 테스트 추가, F-1의 클라이언트 측(heartbeat·TCP 수신 루프)은 Phase 2 후속 |
| **server-dev** | F-3 (sub-pixel accumulator — 별도 이슈), `handle_client`의 `registry=None` 기본값 제거 권장(`server.py:94` — 프로토콜 필수 단계가 옵셔널 인자로 되어 있어, registry 없이 호출되면 SESSION 줄이 안 나가고 Android는 3초 타임아웃으로 Error), 섹션 5 골든 패킷 테스트 추가 |
| **리더(문서)** | D-1 ~ D-6 + 섹션 7 broadcast 주석 + 섹션 9 컨벤션에 채널 선택 단계 추가 |
| **Phase 5 백로그** | UDP 토큰 평문 + 소스 IP 미검증(동일 LAN 스푸핑 가능) — `server.py:60`은 `_addr`를 버린다. PIN 인증 시 재검토 |

**결론: 확정 스펙대로 양쪽이 맞물린다. 블로커 없음. 즉시 코드 수정을 권할 항목은 F-2 하나이며, 나머지는 문서 갱신(6건)과 Phase 2/3 백로그(F-1·F-3)다.**
