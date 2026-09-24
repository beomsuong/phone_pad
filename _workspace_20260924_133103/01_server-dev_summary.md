# server-dev 작업 요약 — PIN 코드 인증 (Phase 5)

## 결과 한 줄
`request.md`의 와이어 스펙/서버 스펙을 **이탈 없이** 구현. `python -m pytest` = **544 passed, 1 skipped**
(기준선 364 passed/1 skipped → **회귀 0**, 신규 180건). 기존 TCP end-to-end 테스트는 삭제 없이 전부 수정.

## 변경 파일

### 신규
| 파일 | 내용 |
|------|------|
| `pc_server/pin_auth.py` | 순수 로직: `generate_pin()`(secrets, 6자리 0-패딩), `parse_auth_message()`(bytes/str/dict 수용, 어떤 입력에도 예외 없음), `pins_match()`(`hmac.compare_digest`, 바이트 비교), `source_key()`(IP 단위), `AuthAttemptLimiter`(시간 주입, 60s/5회, 테이블 상한 512, 종류별 rate-limit ASCII 로그). 표준 라이브러리만, 소켓 비의존 |
| `pc_server/tests/test_pin_auth.py` | 161건 (아래) |
| `pc_server/tests/fake_conn.py` | **공용** `FakeConn` + `make_auth_line()`/`AUTH_LINE`. 파괴적 변경을 한 곳에서 흡수하는 장치 |

### 수정 (최소 diff)
| 파일 | 변경 |
|------|------|
| `pc_server/server.py` | `AUTH_TIMEOUT_S=3.0`, `AUTH_MAX_LINE_CHARS=4096` 상수 / `read_auth_line()`·`authenticate_client()` 신규 / `handle_client(..., expected_pin=None, auth_limiter=None)` 앞단에 AUTH 한 단계 삽입(그 뒤 코드는 무변경) / 이벤트 루프의 "줄 파싱"을 recv **앞으로** 이동(AUTH 줄과 같은 청크에 붙어온 이벤트 유실 방지) / `ServerRuntime(expected_pin=None, auth_limiter=None)` + accept 시 전달 / `run_with_tray`가 트레이에 `pin=` 전달 / `parse_args`에 `--pin CODE`·`--no-auth` / `resolve_expected_pin(args)` / `main()`이 PIN 한 줄 출력 |
| `pc_server/tray_status.py` | `normalize_pin()`, `format_pin_label()`, `TOOLTIP_PIN_SUFFIX_TEMPLATE`, `PIN_LABEL_PREFIX`/`PIN_DISABLED` 추가. `tray_status(count, pin=None)` — pin이 있을 때만 툴팁 끝에 `" - PIN: {pin}"` |
| `pc_server/tray.py` | `TrayController(..., pin=None)`, `MENU_KEY_PIN`, `pin_label()`. 메뉴는 상태/주소/**PIN**/구분선/종료 — PIN 항목은 **인증이 켜져 있을 때만** 들어가고 클릭 불가 |

## 처리하는 이벤트/메시지와 기대 필드

| 방향 | 메시지 | 기대 필드 | 서버 동작 |
|------|--------|-----------|-----------|
| 앱 → 서버 | `{"type":"AUTH","pin":"<str>"}` | `type`(정확히 `"AUTH"`), `pin`(**문자열**, 빈 문자열 허용) | 인증 꺼짐 → 값 무관 통과 / 켜짐 → `compare_digest` 비교 |
| 서버 → 앱 | `{"type":"SESSION","session":"<32 hex>"}` | 기존과 **완전 동일**(형식·발급 로직 무변경) | 인증 통과 시에만 |
| 서버 → 앱 | `{"type":"AUTH_FAIL","reason":"invalid_pin"}` | 압축 JSON + 개행 | **PIN 값이 틀렸을 때만**, 보낸 뒤 즉시 close |
| 서버 → 앱 | (없음) | — | 타임아웃(3s)/EOF/깨진 JSON/dict 아님/`type != AUTH`/`pin` 비문자열/**잠금** → 조용히 close |

- 브루트포스: 같은 IP에서 60초 내 5회 실패 → 남은 창 동안 새 연결은 **AUTH 줄을 읽지도 않고** close(와이어 상 "형식 오류로 조용히 닫힘"과 구분 불가). `--no-auth`면 추적 자체를 안 한다.
- `SessionRegistry`/heartbeat/UDP MOVE/이후 모든 이벤트 처리는 **무변경**. `discovery.py`는 **무접촉**(PIN 미포함).

## 수정한 기존 테스트 (삭제 0)

핵심 전략: 파일마다 복사돼 있던 `FakeConn`을 **공용 `tests/fake_conn.py`로 통합**. 이 `FakeConn`은
AUTH 줄을 자동으로 먼저 흘려주고, `recv_calls`/`timeouts`는 **SESSION 이후만** 센다 —
그래서 `recv_calls == 3`, `timeouts == [HEARTBEAT_INTERVAL_S]` 같은 기존 단정의 *의미*가 보존된다
(AUTH 단계 값은 `auth_recv_calls`/`auth_timeouts`로 따로 노출). 앞으로 핸드셰이크가 또 바뀌면 이 한 파일만 고치면 된다.

| 파일 | 수정 내용 |
|------|-----------|
| `tests/test_server_heartbeat.py` | 로컬 `FakeConn` 삭제 → 공용 import. `test_handle_client_sets_socket_timeout_after_handshake`에 `auth_timeouts == [AUTH_TIMEOUT_S]` 단정 추가 |
| `tests/test_server_drag.py` | 로컬 `FakeConn` 삭제 → 공용 import (드래그 안전장치 3경로 그대로 통과) |
| `tests/test_desktop_switch.py` | 로컬 `FakeConn` 삭제 → 공용 import |
| `tests/test_server_udp_session.py` | 로컬 `FakeConn` 삭제 → 공용 import. `ProbeConn`/`ExplodingConn`이 `recv` 대신 **`_recv_event`** 를 오버라이드하도록 변경(그래야 AUTH 단계는 정상 통과하고, "SESSION 이후 첫 recv에서 터진다"는 원래 의도가 유지된다) |
| `tests/test_server_shutdown.py` | 실소켓 2건(`test_running_server_serves_a_real_client_then_stops`, `test_console_mode_serves_and_stops_without_a_tray`)이 `AUTH_LINE`을 먼저 전송 / `FakeTrayController`가 `pin=None` 수용 / 시그니처 회귀 테스트를 `[conn, addr, controller, registry, expected_pin, auth_limiter]` + **기본값 None** 단정으로 갱신 / 트레이 PIN 전달 테스트 1건 추가 |
| `tests/test_discovery.py` | **request.md 목록에 없었지만 grep으로 발견** — `test_server_keeps_serving_when_discovery_cannot_bind`의 실소켓 핸드셰이크에 `AUTH_LINE` 전송 추가 (탐색 로직 자체는 무변경) |
| `tests/test_tray_status.py` | PIN 표시 테스트 10건 추가 (인증 꺼짐 시 기존 문구 그대로임을 고정) |
| `tests/test_tray_adapter.py` | `Harness(pin=...)` 지원 + PIN 메뉴/툴팁 테스트 4건 추가 |

## 신규 테스트 (`tests/test_pin_auth.py` 161건)
1. **순수 함수**: `generate_pin` 형식/0-패딩(0·7·42·999999)/분포(300회 >10종)/`secrets.randbelow(1_000_000)` 사용 고정. `parse_auth_message` 정상 3형태 + 이상 입력 **22종**(빈 줄·공백·깨진 JSON·리스트/숫자/문자열/bool/null·type 누락/소문자/다른 type·pin 누락/숫자/null/bool/리스트/dict·非UTF-8) + 이상 객체 7종 무예외. `pins_match` 동일/불일치/길이 상이/비문자열/non-ASCII/`compare_digest` 호출 고정. `source_key`(포트 무시).
2. **AuthAttemptLimiter**: 4회 미잠금 → 5회째 잠금 → 창 내 유지 → 60.1s 후 해제, 창 경계에서 한 건씩 빠짐, IP별 분리, addr 튜플 집계, 테이블 상한, stale prune, 8스레드 동시 기록, 잠금/차단 로그의 **창당 1줄·ASCII·PIN 미포함**, 로그 스트림 예외 무해.
3. **TCP end-to-end(`handle_client` 실구동)**: 인증 꺼짐(어떤 pin 값도 통과, limiter 미사용) / 정답 → SESSION + 세션 수명 / 오답 → `AUTH_FAIL` 후 close·`registry.issue` 미호출·후속 이벤트 및 `SendInput` 미도달 / 형식 오류 12종 × (인증 on/off) → **무전송** close / 타임아웃·EOF·소켓 예외·부분 줄 → 조용히 / 과대 줄 1회 읽고 종료 / 형식 오류는 실패로 집계하지 않음 / 5회 실패 후 6번째는 **정답 PIN이어도** 읽히지 않음 / 잠금 응답이 형식 오류와 동일 / 타 IP 영향 없음 / 창 경과 후 재연결 가능 / limiter 없으면 잠금 없음 / **PIN 로그 미노출**(성공·실패·잠금 경로 전부 + `isascii()`) / AUTH 줄 청크 분할 / AUTH와 같은 청크의 이벤트 유실 없음 / 타임아웃 순서(`[AUTH_TIMEOUT_S, HEARTBEAT_INTERVAL_S]`) / **Android 와이어 리터럴 고정 3건**(`{"type":"AUTH","pin":"483920"}`, 빈 pin, JSON 이스케이프 디코딩).
4. **런타임/CLI**: `ServerRuntime` 기본값 = 인증 없음(+limiter None), pin 주면 limiter 자동 생성, limiter 주입 가능. **실소켓 3건**(정답→SESSION, 오답→AUTH_FAIL, 형식 오류→무응답). `parse_args`/`resolve_expected_pin`(기본 생성·`--pin`·`--no-auth` 우선), `main()`이 PIN을 **정확히 한 줄·ASCII**로 출력하고 그 값을 런타임에 전달, `--no-auth`는 PIN 미출력, windowed stdio 경유 출력.

## 검증 방법 (실제 실행 수치)
- `cd C:\Github\phone_pad\pc_server && python -m pytest` → **544 passed, 1 skipped** (5.6s). 기준선 364 passed/1 skipped 대비 회귀 0.
- **변이(mutation) 검사 3건** — 새 테스트가 실제로 결함을 잡는지 확인 후 원본 복원:
  - 잠금 검사 우회 → 2건 실패(`..._dropped_without_reading`, `..._indistinguishable_from_a_format_error`)
  - 형식 오류에 `AUTH_FAIL` 응답 → 14건 실패(실소켓 테스트 포함)
  - 실패 로그에 PIN 삽입 → 2건 실패(`..._never_logs_the_pin` 계열)
- **실제 pystray/Pillow venv 재실행**(AGENTS.md 섹션 9 규칙: 트레이 어댑터 수정 시 미설치 환경만 믿지 말 것) — 저장소 밖 임시 venv에서 `544 passed, 1 skipped`(스킵 이유가 "Pillow is installed" 쪽으로 바뀌어 pystray 경로가 실제로 실행됨을 확인). 실 pystray로 메뉴를 만들어 `['Phone Pad - 연결됨 (1대) - PIN: 483920', '접속 주소: ...', 'PIN: 483920', SEPARATOR, '종료']` 확인. **전역 Python은 무변경**(venv 삭제 완료, pystray 여전히 미설치).
- 경계면 교차 확인(읽기만): 병렬 android-dev의 `AuthHandshake.buildAuthLine()`이 만드는 리터럴이 서버 파서를 통과함을 리터럴 고정 테스트로 박아 둠.

## 스펙 이탈 여부
**이탈 없음.** 스펙에 없던 판단 3가지(모두 스펙과 상충하지 않는 보강):
1. `AUTH_MAX_LINE_CHARS = 4096` — 인증 이전 상대가 개행 없이 무한히 밀어넣는 것을 막는 상한. 초과 시 스펙의 "형식 오류" 계열과 동일하게 **조용히** 닫는다.
2. AUTH 줄 뒤에 남은 바이트를 버리지 않고 이벤트 루프 버퍼로 넘긴다(이를 위해 줄 파싱을 recv 앞으로 이동). 정상 앱은 SESSION 전에 아무것도 보내지 않아 실동작 영향은 0이지만, 파이프라이닝 시 이벤트 유실을 막는다.
3. `--no-auth`일 때 `main()`이 `[Server] PIN authentication disabled (--no-auth)` 한 줄을 출력(스펙은 PIN 출력만 규정). PIN 값은 당연히 없다.

## 미해결 이슈 / 한계
| 항목 | 내용 |
|------|------|
| **실기기 미검증** | 실제 폰 ↔ 실서버 PIN 입력 흐름은 테스트로만 검증(FakeConn + 로컬 실소켓). 방화벽/실 LAN 왕복은 리더/사용자 확인 필요 |
| `--pin ""` | 스펙대로 값을 그대로 쓰므로 빈 PIN이 되어 "아무 pin이나 통과"에 가까워진다(브루트포스 추적은 계속 동작). 개발용 플래그라 막지 않았음 — 필요하면 거부로 바꿀 수 있다 |
| 잠금은 프로세스 메모리에만 있다 | 서버를 재시작하면 실패 카운트가 초기화된다(PIN도 새로 생성되므로 실질적 약화는 아님) |
| IP 스푸핑/NAT | 집계 기준이 발신 IP라, NAT 뒤 여러 기기가 한 IP로 보이면 남의 실패로 같이 잠길 수 있다. 반대로 IP를 바꿀 수 있는 공격자는 잠금을 우회한다(PIN 6자리 = 100만 조합 + 연결마다 3초 타임아웃이 실질 방어) |
| 세션 토큰은 여전히 평문 UDP | PIN은 **연결 수립**만 지킨다. UDP MOVE의 세션 토큰 스푸핑(섹션 10 기존 항목)은 그대로 남아 있다 — PIN 인증이 이걸 해결하지 않는다는 점을 문서에 명시하는 게 좋다 |
| exe 재빌드 안 함 | `build_exe.ps1`/spec은 무변경이나 이번 변경 후 exe를 다시 빌드·실행해 보지는 않았다 |
| 중복 실행 시 PIN 2개 | 기존 미해결 항목(Windows `SO_REUSEADDR`)과 맞물려, `--allow-multiple`로 두 서버를 띄우면 각자 다른 PIN을 출력한다 |

## 리더가 AGENTS.md에 반영할 내용
1. **섹션 4(통신 프로토콜)**: TCP 핸드셰이크가 **클라이언트 선발**로 뒤집혔음을 표에 명시 — `AUTH`(앱→서버, 항상 전송, `pin` 문자열) → `SESSION`(성공) / `AUTH_FAIL{reason:"invalid_pin"}`(값 불일치만, 직후 close) / **무응답 close**(타임아웃 3s·EOF·형식 오류·잠금). `AUTH_TIMEOUT_S=3.0`은 heartbeat 타임아웃과 별개. **탐색 응답(9002)에는 PIN을 넣지 않는다**(인증 없는 채널).
2. **섹션 4/9(서버 규약)**: 브루트포스 방어 = 발신 IP별 60초/5회 → 남은 창 동안 **읽지 않고 close**, 형식 오류와 구분 불가. 잠금 로그는 종류별 rate-limit + ASCII. **PIN 값은 `main()`의 시작 메시지 한 줄 외에 어떤 로그에도 남기지 않는다**(테스트로 고정).
3. **섹션 9(컨벤션)에 추가할 교훈**: ① 새 파라미터는 **기본값이 "기능 꺼짐"** — `discovery_port` 때와 같은 이유로 `expected_pin`/`auth_limiter`/`TrayController(pin=)`도 기본 None(그래야 기존 테스트/호출자가 무수정 통과). ② 핸드셰이크를 바꾸면 **와이어를 흉내 내는 테스트가 전부** 영향받는다 → 이번에 `pc_server/tests/fake_conn.py`로 `FakeConn`을 통합했으니 다음 변경은 그 한 파일에서 흡수한다. **`recv_calls`/`timeouts`는 SESSION 이후만 센다**는 규약도 함께 기록. ③ 파괴적 변경 영향 범위는 request.md 목록만 믿지 말고 grep으로 재확인(`test_discovery.py`가 목록에 없었지만 실제로 깨졌다).
4. **섹션 10(미해결)**: 위 표의 "세션 토큰 평문 UDP"(PIN이 해결하지 않음), IP 스푸핑/NAT 한계, `--pin ""`, 잠금 상태의 비영속, exe 재빌드 미실시, 실기기 미검증.
5. **로드맵 Phase 5**: `PIN 코드 인증` 항목 체크 + "기본 켜짐, `--pin`/`--no-auth`로 조정, 트레이·콘솔에 PIN 표시" 요약.
