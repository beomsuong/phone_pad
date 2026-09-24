# 요청 — PIN 코드 인증 (Phase 5, 기본 켜짐)

## 범위 판단: **교차 경계면** (사유: TCP 핸드셰이크 첫 줄 자체가 바뀌는 파괴적 변경)

사용자 결정(확인됨): **기본 켜짐** — 서버가 세션마다 랜덤 6자리 PIN을 자동 생성해 콘솔/트레이에 표시하고, **앱은 연결 화면에 PIN 입력란이 필수**다.

실행: android-dev ∥ server-dev 병렬 → protocol-qa 사후 검증. 리더가 아래 스펙을 사전 확정 — **임의로 바꾸지 말 것**.

**⚠️ 이번 스펙은 파괴적 변경이다.** 클라이언트가 TCP 연결 후 보내는 **첫 줄 자체**가 바뀐다(지금까지는 서버가 먼저 SESSION을 보냈지만, 이제 클라이언트가 먼저 AUTH를 보내야 서버가 응답한다). 그래서 **기존 핸드셰이크를 흉내 내는 테스트가 전부 영향을 받는다** — 새 테스트를 추가하는 것만으로는 부족하고, 아래 목록(및 grep으로 찾은 나머지)을 **전부 갱신**해야 한다:
- 서버: `test_server_drag.py`, `test_server_shutdown.py`, `test_desktop_switch.py`의 TCP end-to-end 테스트(`FakeConn`/`run_client` 계열 헬퍼로 SESSION을 기대하는 곳 전부)
- Android: `TrackpadRepositoryImplTest`, `TrackpadRepositoryHeartbeatTest`, `TrackpadRepositoryReconnectTest`, `TrackpadRepositoryCancelConnectTest`, `TcpClientConnectTimeoutTest` 등 handshake를 시뮬레이션하는 곳 전부

공통 금지: **커밋 금지**, `AGENTS.md`/`CLAUDE.md` 수정 금지(리더가 함), **`git stash`/`checkout`/`reset` 금지**(병렬 작업 트리 보존). 저장소 루트 `C:\Github\phone_pad`, `.claude/worktrees/`는 무시.

## 와이어 스펙 (확정)

**TCP 9000. 클라이언트가 연결 직후 가장 먼저 보내는 줄이 바뀐다** — 서버가 먼저 SESSION을 보내던 것에서, **클라이언트가 먼저 AUTH를 보내야** 서버가 응답하는 순서로 뒤집힌다.

```jsonc
// 클라이언트 → 서버: TCP 연결 직후, 다른 어떤 것보다도 먼저 보내는 한 줄 (개행 포함)
{"type":"AUTH","pin":"483920"}

// 성공: 서버 → 클라이언트 — 기존 SESSION 줄 그대로 (필드/형식 무변경)
{"type":"SESSION","session":"0123456789abcdef0123456789abcdef"}

// 실패(PIN 불일치): 서버 → 클라이언트, 보낸 뒤 즉시 연결을 닫는다
{"type":"AUTH_FAIL","reason":"invalid_pin"}
```

- **AUTH는 인증이 꺼져 있어도(`--no-auth`) 항상 보낸다.** 서버가 PIN을 요구하는지 클라이언트는 미리 알 수 없으므로, 와이어 형식은 항상 동일해야 한다(서버 설정에 따라 클라이언트 동작이 갈라지지 않는다). 인증이 꺼져 있으면 서버는 `pin` 값과 무관하게(빈 문자열이어도) AUTH를 통과시키고 SESSION을 보낸다.
- 서버는 연결 수락 직후 **첫 줄을 읽을 때 `AUTH_TIMEOUT_S = 3.0`초 타임아웃**을 건다(기존 heartbeat 타임아웃과 별개 — 지금까지 서버는 클라이언트가 뭘 보내든 기다리지 않고 SESSION부터 보냈으므로 이런 대기가 없었다). 다음 경우는 **아무 응답 없이 조용히 연결을 닫는다**(응답을 만들 정보가 없거나, 실패를 알리는 것 자체가 무의미한 경우): 타임아웃, 빈 줄/EOF, 깨진 JSON, dict가 아닌 값, `type != "AUTH"`, `pin`이 문자열이 아님.
- **PIN이 명확히 일치하지 않을 때만** `AUTH_FAIL`을 보내고 닫는다(위 "조용히 닫는" 경우와 구분 — 형식은 맞았는데 값이 틀린 경우).
- **PIN 문자열 비교는 `hmac.compare_digest`로 한다**(타이밍 공격 방지 — 값 자체는 6자리 숫자라 큰 위협은 아니지만 비용이 없다).
- **브루트포스 방어(신규):** 서버는 발신 IP별로 **AUTH 실패 횟수**를 추적한다(discovery.py의 `ResponseRateLimiter`와 같은 패턴 — 시간 주입 가능한 순수 클래스, sleep 없이 테스트). 같은 IP에서 **60초 안에 5회 실패**하면, 그 뒤 남은 시간 동안 그 IP의 새 연결은 **AUTH 줄을 읽지도 않고 즉시 조용히 닫는다**(정상적인 "형식 오류로 조용히 닫음"과 구분되지 않게 — 잠금 상태를 공격자에게 알리지 않기 위함). 인증이 꺼져 있으면(`--no-auth`) 이 추적 자체를 하지 않는다. ASCII 로그로 잠금 이벤트를 종류별 rate-limit 있게 남긴다(폭주 방지, `input_controller._note_input_failure`와 같은 패턴).
- **PIN은 탐색 응답(UDP 9002 `SERVER` 메시지)에 절대 넣지 않는다.** 탐색은 인증이 없는 채널이라 PIN을 실어 보내면 인증 자체가 무의미해진다 — discovery.py는 건드리지 않는다.
- 세션 발급(`SessionRegistry`), heartbeat, 그 이후의 모든 이벤트 처리는 **완전히 무변경**이다. 바뀌는 것은 SESSION을 보내기 **전에** AUTH를 한 단계 거친다는 것뿐이다.

## 서버 스펙 (server-dev)

1. 신규 모듈(예: `pc_server/pin_auth.py`, 표준 라이브러리만 — `secrets`, `hmac`, `time`):
   - `generate_pin() -> str`: `secrets.randbelow(1_000_000)`로 6자리, `f"{n:06d}"`로 0-패딩.
   - 순수 함수 `parse_auth_message(data_or_dict) -> str | None`: AUTH 메시지에서 `pin` 문자열을 뽑아낸다(형식이 안 맞으면 None). 소켓/타이밍 비의존.
   - `pins_match(actual: str, expected: str) -> bool`: `hmac.compare_digest`.
   - `AuthAttemptLimiter`(시간 주입, discovery의 `ResponseRateLimiter`처럼): `record_failure(ip)`, `is_locked_out(ip) -> bool`(60초 창에 5회 이상이면 True), 발신자 테이블 상한.
2. `server.py`의 `handle_client` 통합(시그니처는 `expected_pin: str | None = None` 같은 새 파라미터 추가 — **기본값은 `None`(= 인증 없음)** 이라 기존 단위 테스트가 auth를 명시적으로 켜지 않는 한 지금까지와 동일하게 동작). 흐름:
   - 연결 수락 직후, 잠금 상태면(설정된 경우만) 아무것도 읽지 않고 즉시 close.
   - 아니면 `conn.settimeout(AUTH_TIMEOUT_S)` 후 한 줄 읽기(SESSION 이전이므로 buffer 재사용 가능하도록 기존 heartbeat 루프의 버퍼링 로직을 참고해 최소 침습적으로 구현).
   - 형식 오류/type 불일치/timeout/EOF → 조용히 close.
   - `expected_pin is None`(인증 없음) → 그대로 통과, 기존 SESSION 발급 흐름(현재 코드 그대로).
   - `expected_pin`이 있고 불일치 → `AuthAttemptLimiter.record_failure(addr)` + `AUTH_FAIL` 전송 + close.
   - 일치 → 기존 SESSION 발급 흐름 그대로 진행(그 뒤 코드는 **완전 무변경**).
   - **로그에 PIN 값 자체를 남기지 않는다**(콘솔이든 실패 로그든 — "[!] Auth failed for {addr}" 처럼 값 없이).
3. `ServerRuntime`/`main()` 통합: `parse_args`에 `--pin <code>`(고정 PIN 지정), `--no-auth`(인증 완전 비활성 — 개발/테스트 편의, `--no-tray`/`--no-discovery`/`--allow-multiple`과 같은 선상). `main()`에서: `--no-auth`면 `expected_pin=None`, `--pin`이 있으면 그 값, 둘 다 없으면 `generate_pin()` 호출 후 콘솔에 ASCII로 `[Server] PIN for this session: 483920` 출력(고정 PIN을 줬을 때도 같은 형식으로 한 번 출력). `ServerRuntime`이 이 값을 들고 있다가 `handle_client` 호출 시 넘긴다(기존 `discovery_port` 파라미터를 추가했던 것과 같은 패턴 — **기본값은 인증 없음이어야 `ServerRuntime`을 만드는 기존 테스트가 깨지지 않는다**, discovery 때와 동일한 교훈).
4. 트레이 연동: `tray_status.py`(순수 로직)의 툴팁 생성 함수에 PIN을 붙일 수 있게 확장(인증이 켜져 있을 때만 툴팁 끝에 `" - PIN: {pin}"` 추가, 꺼져 있으면 기존과 동일). 메뉴에도 접속 주소 라벨처럼 PIN 라벨 항목을 하나 추가(클릭 불가, 표시 전용). `tray.py` 어댑터가 이 값을 전달받아 그리도록 최소 연결.
5. 테스트(`pc_server/tests/test_pin_auth.py` 신규): `generate_pin` 형식(6자리 숫자 문자열, 여러 번 호출 시 분포/0-패딩 확인), `parse_auth_message` 정상/이상 입력(18종 이상: 깨진 JSON, dict 아님, type 다름/소문자, pin 누락/숫자/None), `pins_match`(길이 다른 문자열 포함 안전 비교), `AuthAttemptLimiter`(4회는 안 잠김, 5회째부터 잠김, 창 경과 후 해제, IP별 분리, 인증 꺼져 있으면 미사용).
   - **TCP end-to-end**(`handle_client` 실제 구동, `FakeConn` 패턴): 인증 없음(기존과 동일하게 AUTH 줄만 보내면 SESSION 수신, `pin` 값 무관), 인증 있고 정답 → SESSION, 오답 → AUTH_FAIL 후 연결 종료(그 다음 아무것도 못 보냄을 확인), 형식 오류/AUTH 아닌 첫 줄/타임아웃 → 조용히 종료, 브루트포스 5회 실패 후 6번째 연결은 즉시 종료(SendInput/이벤트 처리로 전혀 진행 못 함), PIN이 로그에 노출되지 않음(caplog/print 캡처로 확인).
   - **기존 파일 갱신**(신규 추가가 아니라 **수정**): `test_server_drag.py`, `test_server_shutdown.py`, `test_desktop_switch.py`의 TCP 헬퍼가 AUTH 줄을 먼저 보내도록 고친다(인증 미설정 상태로 테스트하면 되므로 `pin` 값은 아무거나, 예: `""`). 공유 헬퍼가 있다면 그 한 곳만 고쳐 여러 파일에 자동 반영되게 하는 편이 낫다.
6. `python -m pytest` 기준선 **364 passed, 1 skipped** → 회귀 0(기존 테스트가 실패한다면 AUTH 줄을 안 보내서다 — 삭제하지 말고 수정할 것).

## Android 스펙 (android-dev)

1. `data/network/TcpClient.kt`: `connect(host, port, pin)`로 시그니처 확장. 소켓 연결 성공 직후, **다른 어떤 읽기/쓰기보다 먼저** `{"type":"AUTH","pin":"$pin"}`를 전송(JSON 문자열 이스케이프 없이 그대로 삽입해도 되지만, `"`나 제어문자가 섞이면 깨진 JSON이 되므로 — 실용적으로는 `pin`을 그대로 넣되 `"` 문자만 이스케이프하거나, 간단히 숫자 6자리를 넘는 등 비정상 값은 서버가 어차피 거부하므로 최소한의 방어만). 그 다음 기존처럼 `SESSION_HANDSHAKE_TIMEOUT_MS`로 `soTimeout`을 걸고 한 줄을 읽되, 그 줄이:
   - `SessionHandshake.parseSession()`으로 파싱되면 → 기존과 동일(성공).
   - `{"type":"AUTH_FAIL",...}`이면 → **새 예외 타입**(예: `AuthFailedException`)을 던진다(일반 handshake 실패와 구분하기 위해 — `ConnectionErrorClassifier`가 타입으로 우선 매핑할 수 있게).
   - 그 외(EOF/null/알 수 없는 형식)면 → 기존과 동일하게 handshake 실패로 처리(변경 없음).
2. `domain/model/ConnectionErrorKind.kt`에 `AUTH_FAILED` 추가(PIN이 틀렸음을 뜻함). `ConnectionErrorClassifier`가 `AuthFailedException`(또는 동급 타입)을 최우선으로 이 kind에 매핑. `presentation/util/ConnectionErrorMessages.kt`에 한국어 문구 추가(예: "PIN이 올바르지 않습니다. PC 화면에 표시된 PIN을 확인해 주세요.") — 다른 kind들과 같은 컨벤션(원문은 보조 줄, 조치 힌트 포함).
3. **재시도 없음**: AUTH 실패는 첫 연결 단계에서만 발생할 수 있다(재연결은 이미 발급된 세션을 다시 확보하는 게 아니라 처음부터 다시 connect()를 타므로, 재연결 시도에서도 같은 방식으로 AUTH를 다시 보낸다 — 이 부분은 새로 만들 것 없이 `openConnection()`이 이미 `connect()`를 재사용하는 구조를 그대로 타면 된다). AUTH 실패가 발생한 시도는 `ConnectOutcome.Failure(message, kind=AUTH_FAILED)`로 분류되어, 기존 "첫 connect 실패는 Error로 남기고 재연결 시작 안 함" 규칙을 그대로 따른다(별도 처리 불필요 — 기존 분기를 확인만 할 것). **재연결 도중** AUTH_FAILED가 나면(예: 서버가 재시작되며 PIN이 바뀐 경우) 재연결 루프는 그 시도를 실패로 세고 다음 백오프로 넘어간다(기존 재연결 실패 처리와 동일 — PIN이 계속 틀리면 결국 재시도 소진 후 `Error`).
4. `domain/repository/TrackpadRepository.kt` / `TrackpadRepositoryImpl.kt`: `connect(host, port)` → `connect(host, port, pin)`로 시그니처 확장, 내부적으로 `TcpClient.connect(host, port, pin)`에 그대로 전달.
5. `TrackpadViewModel`: `hostInput`과 **완전히 대칭인** `pinInput`을 추가(`_pinInput = MutableStateFlow("")`, `onPinInputChange(value)`, **DataStore에 영속화하지 않는다** — PIN은 서버가 매 실행마다 새로 생성하므로 저장해 봤자 다음 연결 시점엔 대부분 틀린 값이라 영속화가 오히려 해롭다. `hostInput`이 지금 영속화되지 않는 것과 같은 이유·같은 방식으로 맞춘다). `connect()`는 `hostInput`뿐 아니라 `pinInput`도 비어 있으면 진행하지 않는다(기존 `if (host.isBlank()) return`과 대칭으로 `if (pin.isBlank()) return` 추가).
6. `TrackpadUiState`에 `pinInput` 추가. `TrackpadScreen.kt`의 연결 화면(`ConnectingPanel`이 아니라 IP 입력 폼)에 host 입력란 바로 아래 PIN 입력란을 추가(라벨 예: "PIN 번호", `KeyboardType.Number` 힌트, 값 검증은 최소한으로 — 서버가 최종 판정자). 연결 버튼은 `hostInput.isNotBlank() && pinInput.isNotBlank()`일 때만 활성화(기존 `enabled = hostInput.isNotBlank()`를 대칭으로 확장). 서버 탐색으로 서버를 선택해도 **PIN은 채워지지 않는다**(탐색 응답에 PIN이 없으므로 — 사용자가 PC 화면을 보고 직접 입력해야 함, 화면에 안내 문구 한 줄 추가 고려).
7. `GestureConfig`에 `AUTH_PIN_MAX_LENGTH = 32`(와이어 크기 방어용 상한, 클라이언트가 전송 전에 이 길이를 넘는 값을 자르거나 무시 — 기본 6자리 PIN에는 전혀 영향 없음) 추가.
8. **기존 파일 갱신**(신규 추가가 아니라 **수정**, 위 "파괴적 변경" 경고 참조): handshake를 시뮬레이션하는 기존 테스트 전부에서 서버 역할 fake가 이제 클라이언트로부터 AUTH 줄을 받은 뒤에 SESSION을 주도록 고치고, `connect()` 호출부에 `pin` 인자를 추가한다. 인증이 꺼진 상태를 시뮬레이션하는 것이므로 서버 fake는 `pin` 값을 검사하지 않고 그냥 SESSION을 돌려주면 된다.
9. 신규 테스트: `TcpClient`(AUTH 줄이 정확히 먼저 나감 — 소켓 쓰기 순서 검증, `AUTH_FAIL` 수신 시 예외, 정상 SESSION은 기존과 동일), `ConnectionErrorClassifier`(`AuthFailedException` → `AUTH_FAILED`), `ConnectionErrorMessages`(`AUTH_FAILED` 문구 계약), `TrackpadViewModel`(PIN 빈 값이면 connect 안 함, `onPinInputChange` 반영, PIN은 서버 선택으로 안 채워짐), `GestureConfigTest`(`AUTH_PIN_MAX_LENGTH` 관련 불변식 있다면).
10. 런타임 테스트 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`로 강제 재실행. 기준선 **370 → 회귀 0**(단, 위 8번처럼 기존 handshake 테스트들의 **수정**은 정상이며 "회귀"가 아니다 — 테스트 개수는 늘거나 비슷하게 유지되어야 하고, 실패 0을 목표로 한다).

## 산출물
- 각자 `_workspace/01_android-dev_summary.md` / `_workspace/01_server-dev_summary.md`(변경 파일, **수정한 기존 테스트 목록**, 신규 테스트, 테스트 결과 수치, 스펙에서 벗어난 부분, 미해결 이슈/한계, "리더가 AGENTS.md에 반영할 내용").
- 커밋하지 말 것.
