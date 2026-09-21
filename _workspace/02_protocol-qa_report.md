# protocol-qa 리포트 — UDP 브로드캐스트 자동 서버 탐색 (UDP 9002, DISCOVER/SERVER)

검증자: protocol-qa · 대상: 미커밋 작업 트리(`git status` 확인: `pc_server/discovery.py`·`tests/test_discovery.py`·Android 신규 9개 파일 untracked, `server.py`·`GestureConfig.kt`·`TrackpadViewModel.kt`·`TrackpadUiState.kt`·`TrackpadScreen.kt`·`AppModule.kt` 수정)
방법: 양쪽 코드 동시 읽기 + **차분 검증**(서버 `handle_discovery_packet`의 실제 출력 바이트를 Kotlin `DiscoveryProtocol.parseResponse`의 정규식·unescape를 Python으로 1:1 포팅한 파서에 통과시켜 대조) + **실서버 왕복 실측**(`ServerRuntime(tcp_port=0, udp_port=0, discovery_port=0)`을 실제로 띄워 Android 전송 스케줄 재현)
실행: `pc_server`에서 `python -m pytest` → **364 passed, 1 skipped**(server-dev 보고와 일치). Android gradle은 리더가 실행 중이라 **미실행**.

## 요약

| # | 검증 항목 | 판정 |
|---|-----------|------|
| 1 | 와이어 리터럴 일치(DISCOVER 바이트, SERVER 필드 이름·타입·순서, 포트 9002) | **PASS** |
| 2 | 응답 `name`의 JSON escape 디코딩 / 응답 길이 상한 | **PASS(현실) / FAIL(계약)** → F-1 |
| 3 | 발신 주소 규칙(본문에 IP·토큰 없음, 발신 주소만 사용) | **PASS** |
| 4 | 에코·자기 요청·DISCOVER 외 패킷 무시, 9001/9002 분리 | **PASS** |
| 5 | 속도 제한(5회/초/IP) × Android 3회 × 대상 N개 상호작용 | **PASS**(실측) |
| 6 | 포트 전파(실제 TCP 포트 → uiState.port → 연결, UDP MOVE는 9001 고정) | **PASS** |
| 7 | 서버 기동 안전성(기본 비활성, 바인딩 실패 무해, 해제, `--no-discovery`, 트레이, `SO_REUSEADDR`, PyInstaller) | **PASS** |
| 8 | 취소/수명(탐색 취소, 블로킹 receive 탈출, 소켓 close, `runCatching` 잔존) | **PASS** |
| 9 | 기존 프로토콜 회귀(TCP 9000 / UDP 9001 / 매니페스트) | **PASS** |
| 10 | AGENTS.md 섹션 4/6 문서 ↔ 코드 | **문서 갱신 필요**(리더 담당) |

**FAIL 1건**(F-1, 계약상 불일치·현실 발생 불가), **주의 8건**(W-1~W-8, 전부 위조 패킷 한정 또는 표시상 문제), **미검증 4건**.

---

## 1. 와이어 리터럴 일치 — PASS

| 방향 | Android | Server | 일치 |
|------|---------|--------|------|
| 요청 | `DiscoveryProtocol.kt:32` `{"type":"DISCOVER"}` (19바이트, 테스트가 리터럴·바이트 수 고정) | `discovery.py:31` `REQUEST_TYPE="DISCOVER"`, `:97` `request.get("type") != REQUEST_TYPE` → 무시 | O |
| 응답 | `DiscoveryProtocol.kt:46-55` `type`/`name`/`port` 정규식(키 순서·공백 무관) | `discovery.py:99-105` `{"type","name","port"}` + `separators=(",",":")`, 개행 없음 | O |
| 포트 | `GestureConfig.kt:171` `DISCOVERY_PORT = 9002` | `discovery.py:28` `DISCOVERY_PORT = 9002` | O |

- 양쪽 테스트가 같은 리터럴을 고정한다: `tests/test_discovery.py:61` `== b'{"type":"SERVER","name":"MY-PC","port":9000}'` / `DiscoveryProtocolTest.kt:28`(요청), `:42`(응답). 서버가 실제로 내보낸 바이트를 Android 파서 포팅본에 넣어 전부 통과.
- 필드명은 전부 소문자 그대로(camelCase/snake_case 혼용 없음). 타입: `name` 문자열, `port` JSON 정수(`"9000"`·`9000.5`·`9e3`·`null`·`true`는 양쪽 다 거부 — `DiscoveryProtocol.kt:55`의 부정형 전방탐색이 실제로 동작함을 차분 검증으로 확인).
- 서버는 요청에 여분 필드·공백 패딩·256바이트까지 허용(관대), Android는 응답의 여분 필드를 무시(관대). 양방향 확장 여지 O.

## 2. `name` escape와 응답 길이 상한 — PASS(현실) / **FAIL(계약)**

디코딩은 전부 정상이다(차분 검증 결과):

| 서버가 보낸 것 | Android 파서 결과 |
|---|---|
| 한글 호스트명 `최범서` → `{"type":"SERVER","name":"최범서","port":9000}` (57B, 실측) | `최범서` O |
| 서로게이트 쌍(`😀`) | 이모지 1자로 정확히 결합 O (UTF-16 문자열이라 `code.toChar()` 두 번이 올바른 쌍이 된다) |
| `\"`, `\\`, `\/`, `\n`, `\t`, `\u0001` | 전부 해석 후 제어문자 제거 O |
| 잘린 escape(`\u12`, 끝이 `\`), 알 수 없는 escape(`\q`) | 패킷 무시 O |
| `ensure_ascii=False` 형태(원시 UTF-8 한글) | 그대로 디코딩 O (서버가 옵션을 바꿔도 안전) |
| 비UTF-8 바이트 | 무시 O (엄격 REPORT 디코더) |

### [불일치] F-1 — 스펙대로 자른 64자 이름이 Android 상한 512바이트를 넘길 수 있다

- **위치**: `pc_server/discovery.py:64`(`name = name[:MAX_NAME_LEN]` — **문자 수** 기준 절단) ↔ `phone_pad_app/.../data/network/DiscoveryProtocol.kt:44`(`MAX_RESPONSE_BYTES = 512`) + `ServerDiscoveryClient.kt:51`(수신 버퍼 = 같은 512)
- **기대**: 서버가 스펙(이름 64자 절단)을 지킨 **정상 응답**은 항상 Android가 받아들인다.
- **실제**(측정값, `json.dumps` 기본 `ensure_ascii=True`):
  - ASCII 64자 → 103바이트 → PASS
  - BMP(한글 등) 64자 → **423바이트** → PASS (여유 89바이트)
  - 비BMP(서로게이트 페어, 예: 이모지) 39자 → 507바이트 → PASS / **40자 → 519바이트, 64자 → 807바이트 → Android가 버린다**(버퍼 512로 잘려 깨진 JSON → `null`). 즉 **서버가 목록에 안 뜬다**.
- **재현 시나리오**: PC 호스트명이 비BMP 문자 40자 이상. Windows 컴퓨터 이름은 15자·제한된 문자 집합이라 **실기기에서는 도달 불가**이고, 한글/ASCII 이름은 어떤 길이에서도 안전하다(BMP 최악 423B). 그래서 운영 위험은 사실상 0이지만, **"서버가 스펙을 지키면 앱이 받는다"는 계약 자체는 성립하지 않는다** → FAIL로 기록.
- **수정 제안**(둘 중 하나, 1줄):
  1. (권장, Android) `DiscoveryProtocol.kt:44`를 `MAX_RESPONSE_BYTES = 1024`로. 서버의 이론적 최악값 807 < 1024라 계약이 닫힌다. 이때 `ServerDiscoveryClient.kt:51`의 수신 버퍼는 `MAX_RESPONSE_BYTES + 1`로 두어 "상한 초과"를 잘린 패킷과 구분할 수 있게 한다(W-2 참조).
  2. (서버) `discovery.py:99-105`에서 직렬화 후 길이를 보고 이름을 줄인다(문자 수가 아니라 **인코딩 후 바이트 수** 기준 절단). 정석이지만 스펙 문구("최대 64자")를 바꿔야 하므로 리더 판단 필요.
- **영향받는 에이전트**: android-dev(1안) 또는 server-dev(2안) — 어느 쪽이든 AGENTS.md 섹션 4에 "응답 상한"을 명시할 것.

## 3. 발신 주소 규칙 — PASS

- 서버 응답 본문 키는 정확히 `{type,name,port}` (실측 `body keys: ['name','port','type']`). IP·세션 토큰·기타 비밀 없음(`tests/test_discovery.py:78`이 키 집합과 `session` 문자열 부재를 고정).
- Android는 주소를 **발신 주소만** 사용: `DiscoveryProtocol.kt:78`(IPv4 검사) → `:100` `host = senderHost`. 본문에 `host`/`ip`/`address`가 있어도 무시됨을 차분 검증으로 확인(`{"...","host":"10.0.0.1","ip":"10.0.0.1","session":"secret"}` → `host=192.168.0.13`, 토큰은 결과에 없음). 전용 테스트 `DiscoveryProtocolTest.kt:52`, `:70` 존재.
- 탐색 응답이 세션을 만들거나 입력을 주입하지 않음: 실측에서 `registry.snapshot() == set()`, `discovery.py`에 controller/registry 참조 0.

## 4. 에코/자기 요청·타 패킷 무시, 9001↔9002 분리 — PASS

- 서버(실측, 실제 소켓): `MOVE`, `MOVE+session`, `SESSION`, `{"type":"discover"}`(소문자), `garbage`, 300바이트, 두 메시지 이어붙임 → **전부 무응답**. 스트레스 후에도 스레드 생존(`tests/test_discovery.py:218`).
- Android: 자기 요청 에코(`DiscoveryProtocol.kt:87`)와 다른 클라이언트의 DISCOVER(`type != "SERVER"` → `:90`)를 모두 무시. 공백 패딩된 에코도 `trim()` 후 걸린다(차분 검증 확인). 구조적으로도 앱 소켓은 임시 포트에 바인딩되고 브로드캐스트는 9002로 나가므로 남의 DISCOVER가 앱 소켓에 도착하지 않는다.
- 분리: 9001 경로(`server.py:70-100` `handle_udp_packet`)는 무변경이고 세션 토큰 없는 DISCOVER를 조용히 버린다(실측 "DISCOVER on MOVE port -> silent", `tests/test_discovery.py:598`). 9002 소켓은 별도 소켓·별도 스레드이며 `registry`를 모른다. `GestureConfigTest.kt:21`이 9000/9001/9002 상호 배타를 고정.

## 5. 속도 제한 × 3회 전송 × 대상 N개 — PASS (실측)

실서버에 Android 전송 스케줄(한 소켓 = 한 발신 IP, 0/300/600ms × 대상 N개, 창 1.5초)을 그대로 재현:

| 대상 수 N | 보낸 요청 | 받은 응답 | 서버 발견 |
|---|---|---|---|
| 1 | 3 | 3 | YES |
| 2 | 6 | 5 | YES |
| 3 | 9 | 5 | YES |
| 6 | 18 | 5 | YES |

- 제한에 걸린 응답은 **전부 잉여분**이다: 첫 라운드는 항상 창이 비어 있어 최소 5개까지 응답되고, Android는 `host:port`로 중복 제거(`ServerDiscoveryClient.kt:158`)하므로 응답 1개만 도달해도 결과가 같다. **"응답 손실로 서버가 안 보이는 시나리오는 없다"**(제한이 원인인 경우).
- 재탐색 버튼 연타: ViewModel이 `Searching` 중 재호출을 무시(`TrackpadViewModel.kt:84`)하고 화면도 버튼을 비활성(`DiscoveryMessages.isSearchEnabled`)하므로 최소 간격이 1.5초다. 쉬지 않고 3회 연속 탐색 실측 → 매번 5응답·발견 성공(1.5초 창이 지나면 1초 창의 기록이 비어 있음).
- 서버가 여러 대면 제한기는 서버별로 독립(`DiscoveryResponder`마다 자기 `ResponseRateLimiter`)이라 상호 간섭 없음. 제한 키는 IP 단위(`discovery.py:308`)라 앱이 포트를 바꿔도 우회되지 않는다.

## 6. 포트 전파 — PASS

- 서버: 응답의 `port`는 `serve()` 시점의 **실제 바인딩된 TCP 포트**(`server.py:276` `self.tcp_port = tcp_sock.getsockname()[1]` → `:289-296`에서 응답자에 주입). 포트 0 실측: `tcp=51292`로 뜬 서버가 `{"type":"SERVER",...,"port":51292}`를 반환(테스트 `test_runtime_with_discovery_port_answers_and_reports_tcp_port`도 동일 고정).
- Android: 선택 → `TrackpadViewModel.kt:111-114`가 `_hostInput`/`_port`만 설정 → `uiState.port`에 반영(`:46-62`). **자동 연결 없음**(`selectServer`는 repository를 호출하지 않음, 테스트 `서버를 골라도 자동으로 연결하지 않는다`).
- 연결은 `_port.value`를 읽는다(`:133`) — `uiState`가 `WhileSubscribed(5초)` 공유라 초기값을 들고 있을 수 있는 함정을 피한 올바른 선택.
- 수동 IP 편집 시 기본 포트 복귀: `:71-74` `onHostInputChange`가 `_port = DEFAULT_PORT`. 테스트 존재.
- UDP MOVE는 `TrackpadRepositoryImpl.kt:207` `udpClient.connect(host, GestureConfig.UDP_PORT)`로 **9001 고정**(비표준 UDP 포트는 스펙상 범위 밖, 한계로 문서화 필요).

## 7. 서버 기동 안전성 — PASS

- 기본 비활성: `server.py:228` `discovery_port: int = None`, `:289` `if self.requested_discovery_port is not None`. 실측에서 기본 런타임은 `requested_discovery_port=None`이고 프로세스 내 9002는 비어 있었다. 테스트 2건(`test_runtime_defaults_to_discovery_disabled`, `test_runtime_never_binds_discovery_by_default`)이 고정 → 서버가 떠 있는 상태에서도 기존 테스트가 9002를 잡지 않는다.
- 바인딩 실패 무해: `discovery.py:214-220`이 `OSError`를 잡아 `[!] Discovery disabled: ...`(ASCII) 한 줄 + `False`. `serve()`는 반환값을 보지 않고 계속 진행 → TCP/UDP 정상. 테스트 `test_server_keeps_serving_when_discovery_cannot_bind`, 로그 ASCII 테스트 존재.
- 해제: `stop()`(`server.py:326-328`)·`close()`(`:333-335`) 양쪽에서 응답자 정지, `DiscoveryResponder.stop()`은 join + close + 멱등(`discovery.py:233-247`). 실측에서 `stop()` 후 같은 포트 재바인딩 성공, 스레드 종료 확인.
- `--no-discovery`: `server.py:431-435` 정의, `:469-475`에서 `None if args.no_discovery else DISCOVERY_PORT`. 파싱/전달 테스트 2건.
- 트레이 모드: `main()`이 런타임을 만들어 `run_with_tray(controller, registry, runtime)`로 주입(`:476`), `run_with_tray`는 받은 런타임을 그대로 사용(`:376`) → 콘솔/트레이 두 경로에서 동일한 탐색 설정.
- `SO_REUSEADDR` 미설정 확인: `discovery.py:210-216`에 `setsockopt` 없음(주석으로 이유 명시), 전용 테스트 `test_responder_does_not_set_so_reuseaddr`. 9001/9000의 기존 `SO_REUSEADDR`는 무변경(`server.py:254`, `:264`).
- PyInstaller: `phone_pad_server.spec`의 `Analysis(["server.py"], pathex=[HERE])` + `server.py:9` `import discovery`(정적 import) + `discovery.py`는 표준 라이브러리만(`collections/json/socket/threading/time`) → **hidden import 불필요, 자동 포함**. 실제 재빌드는 미검증(아래).

## 8. 취소/수명 — PASS

- 연결 시작/취소: `TrackpadViewModel.kt:129`(`connect`), `:147`(`cancelConnect`) → `stopDiscovery()`(`:117-123`)가 job 취소 + `Searching`이면 `Idle` 복귀. ViewModel 종료는 `viewModelScope`가 job을 취소.
- 블로킹 `receive` 탈출: `ServerDiscoveryClient.kt:150` + `:180-193`에서 소켓 타임아웃을 `min(200ms, 남은 창, 다음 재전송까지)`로 쪼개고 `:133` `yield()`로 매 회차 취소 확인 → 취소 후 최대 200ms 내 탈출(android-dev가 한계로 명시). `soTimeout = 0`(무한 대기) 방지용 `coerceAtLeast(1)` 있음(테스트 `수신 타임아웃은 0이 아니고 폴링 간격을 넘지 않는다`).
- 소켓 close 누락 경로 없음: 소켓 생성 이후 모든 경로가 `try/finally { socket.close() }`(`:169-171`). 소켓 생성 실패·대상 0개는 소켓을 만들기 전에 반환(`:118-121`). 루프백 실소켓 테스트가 "취소 시 close"를 실측.
- `CancellationException` 삼킴 없음: 클라이언트의 `runCatching`은 **비-suspend 호출만** 감싼다(`broadcastTargets()`, `socketFactory()`, `socket.send`, `socket.close`) — 코루틴 취소가 통과할 지점이 아니다. `discover()`는 `catch (CancellationException) { throw e }`를 `catch (Exception)`보다 먼저 둔다(`:164-168`). ViewModel도 동일 패턴(`:89-96`, 주석에 실제로 잡았던 버그 기록). Repository/UseCase는 단순 위임.

## 9. 기존 프로토콜 회귀 — PASS

- 서버: `handle_client`/`udp_listener`/`handle_udp_packet`/`SessionRegistry`/소켓 옵션 무변경(diff가 `import discovery` 1줄 + `ServerRuntime` 인자/기동/정지 + `--no-discovery` + `main()`의 런타임 생성만 건드림, 총 +34/-2). `input_controller.py`(SendInput·`sizeof` 경로) **무변경**(git status에 없음). `pytest` 364 passed/1 skipped.
- Android: `TrackpadEvent.kt`·`TrackpadRepositoryImpl.kt`·`TcpClient.kt`·`UdpClient.kt`·`SessionHandshake` 무변경(git status). `TrackpadScreen.kt` diff는 `ConnectPanel` 인자 4개 + `ServerDiscoverySection` 호출 + `verticalScroll`뿐 — `pointerInput` 제스처 블록 무접촉.
- `AndroidManifest.xml` **무변경**(권한 추가 없음, `git diff` 빈 결과).

## 10. 주의 항목 (수정 필수 아님, 기록용)

| # | 위치 | 내용 | 제안 |
|---|------|------|------|
| W-1 | `DiscoveryProtocol.kt:167` | `hex.toIntOrNull(16)`은 Kotlin 규칙상 **부호를 허용**한다 → `\u-12f`/`\u+041` 같은 위조 escape가 거부되지 않고 엉뚱한 문자로 변환된다(`Int.toChar()`가 하위 16비트를 취함). `json.dumps`는 이런 출력을 만들지 않아 정상 서버와는 무관 | `hex`를 `Regex("[0-9a-fA-F]{4}")`로 먼저 검사하거나 `hex.all { it.isDigit() \|\| it in 'a'..'f' \|\| it in 'A'..'F' }` 확인 후 파싱 |
| W-2 | `ServerDiscoveryClient.kt:51` | 수신 버퍼가 `MAX_RESPONSE_BYTES`와 **같아서** `parseResponse`의 `length > MAX_RESPONSE_BYTES` 분기는 실소켓 경로에서 절대 성립하지 않는다(초과 패킷은 잘린 JSON으로 무시됨 — 결과는 같다). 테스트만 그 분기를 밟는다 | F-1을 1안으로 고칠 때 버퍼를 `MAX_RESPONSE_BYTES + 1`로 두면 "초과"와 "잘림"을 실제로 구분 |
| W-3 | `ServerDiscoveryClient.kt:19-23`, `:55` | `DiscoveryPacket`에 발신 **포트**가 없어 응답이 9002에서 왔는지 확인하지 않는다. 위조가 이미 스펙상 가능한 범위라 실익은 작다 | 강화하려면 `senderPort`를 싣고 `!= DISCOVERY_PORT`면 무시 |
| W-4 | `DiscoveryProtocol.kt:58-60` | IPv4 정규식이 `255.255.255.255`·`0.0.0.0`도 통과시킨다 → 위조 패킷이 연결 불가능한 줄을 목록에 올릴 수 있다(표시상 문제) | 필요 시 브로드캐스트/`0.0.0.0` 제외 |
| W-5 | `discovery.py:92` | 서버가 `{"type":"DISCOVER"}\n`·공백 패딩 요청에도 응답한다(`json.loads`의 관대함, 실측 확인). Android는 개행 없이 보내므로 현재 무해하고 Android 파서도 `trim()`으로 대칭 | 의도된 관대함으로 AGENTS.md에 한 줄 명시(향후 엄격/바이너리 파서로 바꿀 때 회귀 방지) |
| W-6 | `discovery.py:201-209`, `:270-275` | 연속 recv 오류 50회로 루프가 끊긴 뒤에는 `_thread`가 죽어 있어도 `start()`가 `True`를 돌려주고 소켓이 9002를 계속 점유한 채 무응답이 된다 | `start()`에서 `self._thread`의 `is_alive()`를 확인해 죽었으면 재기동(경계면 문제는 아님) |
| W-7 | `DiscoveryProtocol.kt:164-169` | 짝 없는 서로게이트 escape(`\udcXX` — Windows에서 `gethostname()` 디코딩이 서러게이트를 남길 수 있고 `ensure_ascii`가 그대로 내보낸다)는 그대로 문자열에 남는다. 크래시는 없고 글리프만 깨진다 | 표시 전 `isSurrogate` 문자를 제거하려면 `sanitizeName`에 필터 1줄 |
| W-8 | `DiscoveryProtocol.kt:113` | 제어문자를 **삭제**하므로 `A\tB` → `AB`로 단어가 붙는다(호스트명에 탭이 올 수 없어 실제 영향 없음) | 공백으로 치환하는 편이 표시상 자연스러움 |

## 11. 테스트 커버리지

| 검증 대상 | Android (JUnit/MockK) | Server (pytest) |
|---|---|---|
| 와이어 리터럴 | `DiscoveryProtocolTest.kt:28`(요청 19바이트), `:42`(응답) | `test_discover_returns_exact_response_bytes`, `test_response_has_no_trailing_newline` |
| 필드 타입/범위 | 포트 5케이스·비정수 6케이스·type 5케이스·name 6케이스(25건) | 무시 패킷 29종, 이름 절단/폴백 |
| escape | 유니코드·따옴표·깨진 escape 3건 | `test_non_ascii_hostname_survives_utf8_roundtrip` |
| 발신 주소/토큰 | `본문에 host나 ip 필드가 있어도` / `세션 토큰 같은 여분 필드` | `test_response_never_carries_a_session_token_or_ip` |
| 채널/포트 상수 | `GestureConfigTest.kt:21`(9000/9001/9002 배타) | `test_discovery_port_matches_protocol_spec`, `test_existing_udp_move_channel_is_untouched` |
| 속도 제한 | — (서버 책임) | 제한기 7건 + 실소켓 6번째 무시 |
| 수명/취소 | 클라이언트 17건(전송 시각, 취소 시 close, 부분 실패 격리) + 루프백 3건 | 런타임 기본 비활성/정지/close/바인딩 실패 8건, CLI 3건 |
| ViewModel 배선 | `TrackpadViewModelDiscoveryTest.kt` 13건(선택 시 host·port, 자동 연결 안 함, 수동 편집 시 리셋, 중복 실행, 연결 시 취소) | — |

양쪽 모두 신규 이벤트/메시지에 대한 테스트가 존재한다. **빠진 것**: 응답 **길이 경계**(512바이트 전후) 테스트가 Android에 없다 — F-1을 고칠 때 "BMP 64자 이름(423바이트) 응답은 받아들인다" 1건을 추가하면 계약이 테스트로 고정된다.

## 12. 미검증 (실패 아님)

1. **Android 단위 테스트 실행** — 리더가 gradle을 돌리는 중이라 이 리포트는 코드 읽기와 서버 측 실행만으로 판정했다(android-dev 보고: 368 tests / 0 failures).
2. **실기기·실네트워크** — AP가 제한 브로드캐스트를 버리는지, 서브넷 브로드캐스트 도달, Windows Defender의 UDP 9002 인바운드 첫 프롬프트, 목록 렌더/탭 → `selectServer` 배선(`ServerDiscoverySection`은 Compose UI 테스트 없음 — 프로젝트 관례).
3. **PyInstaller 재빌드** — 정적 import라 포함은 확실하지만 exe에서 9002가 실제로 뜨는지는 빌드하지 않았다(exe는 `python.exe`와 다른 바이너리라 방화벽 프롬프트도 새로 뜬다).
4. **멀티 NIC/VPN 라우팅** — 응답 발신 주소 선택은 OS에 맡긴다. 같은 PC 내 루프백/Wi-Fi 경로만 실측됨.

## 13. AGENTS.md 문서 갱신 요청 (리더)

현재 `AGENTS.md`에 9002 스펙이 **없다** — 섹션 4에 절이 없고, `AGENTS.md:335`는 여전히 `UDP broadcast (탐색) ... (Phase 4 예정, 미구현 — 현재는 수동 IP 입력)`, `:279`는 체크 안 된 상태다. 코드가 최신이므로 문서를 코드에 맞춰 갱신해야 한다(다음 세션 에이전트가 "미구현"으로 읽고 중복 구현할 위험).

반영할 내용은 두 summary(server-dev §6, android-dev §7)가 이미 정확하다. QA가 추가로 명시를 요청하는 3줄:

1. **응답 길이 상한**을 스펙에 적는다 — "클라이언트 수신 버퍼는 최소 512바이트(이름 64자 BMP 최악 423바이트)". F-1을 1안으로 고치면 "1024바이트(이론 최악 807)"로.
2. `name`은 **항상 포함되는 JSON 문자열**이라는 점(앱은 없거나 비문자열이면 그 서버를 무시한다) — 서버가 나중에 필드를 생략하면 조용히 안 보이게 된다.
3. 탐색 응답의 `port`는 **TCP 전용**이고 UDP MOVE는 앱이 9001을 고정으로 쓴다는 한계(섹션 10).
