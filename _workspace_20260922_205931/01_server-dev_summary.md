# server-dev 요약 — UDP 브로드캐스트 자동 서버 탐색 (Phase 4 마지막 항목)

## 1. 변경 파일

| 파일 | 상태 | 내용 |
|------|------|------|
| `pc_server/discovery.py` | **신규** (+317줄, 표준 라이브러리만) | `DISCOVERY_PORT=9002`, 순수 함수 `handle_discovery_packet`, `ResponseRateLimiter`, `DiscoveryResponder`(데몬 스레드), `normalize_name`/`server_name`/`sender_key` |
| `pc_server/server.py` | 수정 (**+34 / -2**) | `ServerRuntime(discovery_port=None)` 선택 인자, `serve()`에서 응답자 기동, `stop()`/`close()`에서 정지, `--no-discovery` 플래그, `main()`이 런타임을 만들어 주입 |
| `pc_server/tests/test_discovery.py` | **신규** (77 tests) | 순수 함수/남용 제한/루프백 왕복/바인딩 실패/런타임 통합/CLI |

`handle_client`·`udp_listener`·`handle_udp_packet`·`SessionRegistry`·소켓 옵션·기존 로그는 **무변경**(diff로 확인). `phone_pad_app/`, `AGENTS.md`, `CLAUDE.md`, `.claude/worktrees/` 무수정, 커밋/스태시/체크아웃 없음.

## 2. 처리하는 메시지와 기대 필드

수신 (UDP 9002, 패킷 1개 = 메시지 1개, 개행 없음):
```json
{"type":"DISCOVER"}
```
응답 (요청 발신 주소로 유니캐스트, 개행 없음, 압축 JSON, 키 순서 `type`→`name`→`port`):
```json
{"type":"SERVER","name":"MY-PC","port":9000}
```
- `name` = `socket.gethostname()`, **최대 64자 절단**, 비면 `"PC"`, 비문자열이면 `"PC"`.
- `port` = `ServerRuntime`이 **실제 바인딩한 TCP 포트**(`main()` 경로에서는 9000).
- 서버 IP·세션 토큰·기타 필드는 **응답에 없다**(키 집합이 정확히 `{type,name,port}`임을 테스트로 고정).
- 응답은 **정확히 `type=="DISCOVER"`인 JSON 객체**에만. 그 외(깨진 JSON, 배열/숫자/문자열/`null`/`true`, `"discover"` 소문자, `{"TYPE":...}`, 256바이트 초과, UTF-8 아님, NUL 바이트, 두 메시지 붙은 패킷 등 **29종**)는 **조용히 무시**(응답·로그·예외 전부 없음).
- 세션 생성 0 / 입력 주입 0 (런타임 통합 테스트에서 `registry.snapshot() == set()` 확인).
- 남용 방지: **발신 IP별 1초당 최대 5회** 응답(초과분 무시). 시간 소스 주입 가능, 발신자 테이블 상한 512 + 창 경과 항목 제거.
- 바인딩 실패(`OSError`)는 **서버 기동을 막지 않는다**: `[!] Discovery disabled: <이유>` 한 줄 후 탐색만 꺼짐.

## 3. 테스트 결과 (실제 실행 수치)

```
cd C:\Github\phone_pad\pc_server && python -m pytest
기준선(작업 전):  287 passed, 1 skipped
작업 후:          364 passed, 1 skipped  (신규 77건, 회귀 0)
```
- 변이 검사 11종으로 테스트가 실제로 버그를 잡는지 확인: 압축 separators 제거→2건 실패, 남용 제한 무력화→1건, 64자 절단 제거→1건, 빈 이름 폴백 제거→2건, `type` 검사 제거→13건, dict 검사 제거→7건, 256바이트 상한 제거→2건, `SO_REUSEADDR` 추가→1건, `serve()`의 `start()` 제거→3건, `close()`의 정지 제거→1건.
  - 살아남은 변이 2종(정보): ① `stop()`의 `join` 제거 — Windows에서는 소켓 close가 `recvfrom`를 즉시 깨워 스레드가 바로 끝나서 잡히지 않음(join의 가치는 POSIX/지연 종료 경로). ② `ServerRuntime.stop()`의 응답자 정지 제거 — `serve()`의 `finally → close()`가 대신 내려서 관측상 동일(이중 방어).
- **실서버 실측**(`python server.py --no-tray --allow-multiple`, 끝난 뒤 프로세스·포트 정리 확인):
  - `255.255.255.255:9002`와 서브넷 브로드캐스트 `192.168.0.255:9002` **양쪽 다 도달**, 응답 3개(브로드캐스트 2 + 루프백 1) 수신. 응답 발신 주소는 전부 도달 경로에 맞는 인터페이스 주소(`192.168.0.13` / `127.0.0.1`) — 스펙이 의도한 "발신 주소를 서버 주소로" 가 실제로 성립.
  - 깨진/소문자/비UTF-8/900바이트 패킷 → **응답 0개**, 이후에도 정상 응답(스레드 생존).
  - 같은 IP에서 10연속 요청 → **정확히 5회 응답**, 1.5초 뒤 다시 응답(창 복구).
  - 탐색과 무관하게 TCP 9000 `SESSION` 핸드셰이크 정상.
  - 종료 후 9002 즉시 재바인딩 가능(포트 해제), 잔여 프로세스 없음(`Get-Process`/`Get-NetUDPEndpoint` 확인).

## 4. 스펙에서 벗어난 부분 / 추가한 부분

| 항목 | 내용 | 이유 |
|------|------|------|
| `server.py` diff 크기 | **+34 / -2** (스펙 "대략 +25줄 이내"를 약간 초과) | 초과분은 주석 3줄과 `main()`이 `ServerRuntime`을 만들어 `run_console`/`run_with_tray`에 주입하는 6줄. 두 실행 함수는 이미 `runtime` 인자를 받고 있어 시그니처 변경은 없음 |
| `main()`의 런타임 생성 위치 | `main()`이 `ServerRuntime(..., discovery_port=...)`을 만들어 전달 | `run_console`/`run_with_tray`에 포트 인자를 새로 뚫는 것보다 diff가 작고, 기존 테스트(두 함수 모킹)와 호환 |
| recv 루프의 `OSError` 처리 | 즉시 `break`가 아니라 "로그(throttle) + 계속", 연속 50회면 탐색만 포기 | Windows에서 ① 버퍼보다 큰 데이터그램(`WSAEMSGSIZE`) ② 응답 상대가 소켓을 닫아 ICMP unreachable이 돌아온 경우(`WSAECONNRESET`)에도 `recvfrom`이 `OSError`를 던진다. 기존 `udp_listener`식 `break`면 탐색이 조용히 죽는다. 무한 스핀 방지용 상한 포함 |
| 오류 로그 throttle | 종류별 5초당 한 줄(`input_controller._note_input_failure`와 같은 방식) | 브로드캐스트 포트는 남의 앱 패킷도 들어와 로그 폭주가 쉽다 |
| 남용 제한 키 | `IP:port`가 아니라 **IP 단위** | 클라이언트가 재시도마다 포트를 바꾸면 제한이 무력화된다. 대가: 같은 IP 뒤의 두 앱이 창을 공유 |
| `ResponseRateLimiter` 공개 클래스 | 순수 클래스로 분리 | sleep 없이 창 경과/발신자 분리/테이블 상한을 결정적으로 테스트 |
| `RECV_BUFFER_SIZE = 2048` | 요청 상한(256)보다 큰 버퍼 | 버퍼를 256으로 두면 초과 패킷이 "잘린 정상 크기"로 보여 상한 검사가 무의미해진다 |

## 5. 미해결 이슈 / 한계

1. **응답 크기와 Android 수신 버퍼(경계면 주의).** `json.dumps`는 기본 `ensure_ascii=True`라 한국어 호스트명이 `\uXXXX`로 escape된다(실측: 호스트명 `최범서` → `{"type":"SERVER","name":"\ucd5c\ubc94\uc11c","port":9000}` = 59바이트). 상한은 이름 64자 기준 **BMP 문자 424바이트 / 비BMP(서로게이트 페어) 이론상 808바이트**. Android 쪽 `DiscoveryProtocol.MAX_RESPONSE_BYTES = 512`(읽기만 함)는 현실적인 이름(Windows 컴퓨터 이름은 보통 15자)에는 충분하지만, 이론적 최악값은 넘는다. 필요하면 서버에서 **문자 수가 아니라 인코딩 후 바이트 수**로 자르는 쪽이 정석 — 스펙이 "64자"로 확정해 그대로 뒀다. 파서는 반드시 `\uXXXX` escape를 해석해야 한다.
2. **Android의 3회×N인터페이스 전송과 5회/초 제한.** 브로드캐스트 대상이 여러 개면(예: `255.255.255.255` + 서브넷 2개) 3라운드 = 9패킷이 한 IP에서 나가 **4개가 무시**된다. 첫 라운드는 전부 응답되고 클라이언트는 `host:port`로 중복 제거하므로 기능 문제는 없으나, 인터페이스가 6개 이상인 기기에서는 첫 라운드 안에서도 일부가 잘린다(그래도 최소 5개는 응답).
3. **비표준 UDP MOVE 포트는 응답에 없다**(스펙 확정 사항). 서버를 비표준 UDP 포트로 띄우면 앱이 `GestureConfig.UDP_PORT`를 그대로 써서 MOVE가 안 간다. 현재 `main()`은 항상 9001이라 실제 문제는 없음.
4. **방화벽 미검증.** 실측은 같은 PC 안에서만 했다. 실기기 → PC 브로드캐스트는 Windows Defender 방화벽의 **UDP 9002 인바운드(개인 네트워크)** 허용이 필요하고, PyInstaller exe는 `python.exe`와 다른 바이너리라 새 프롬프트가 뜬다. 사람이 한 번 확인해야 함.
5. **서버 IP가 여러 개인 환경의 응답 주소**는 OS 라우팅에 맡긴다(실측에서 Wi-Fi/루프백 모두 올바른 주소로 나갔다). VPN/Hyper-V가 낀 복잡한 라우팅은 미검증.
6. **exe 경로 미검증.** `build_exe.ps1`로 다시 빌드해 exe에서 9002가 뜨는지는 확인하지 않았다(`discovery.py`는 표준 라이브러리만 쓰고 `server.py`가 직접 import하므로 hidden import 문제는 없을 것). 재빌드 시 확인 항목.
7. **`taskkill /F` 하드 킬**은 기존 한계 그대로(소켓은 OS가 회수하므로 탐색에 추가 위험은 없음).
8. 탐색은 인증 이전 단계라 **같은 LAN의 누구나 PC 이름·TCP 포트를 알 수 있다**(스펙이 의도한 트레이드오프, 토큰은 절대 넣지 않음). PIN 인증 설계 시 함께 재검토.

## 6. 리더가 AGENTS.md에 반영할 내용

- **섹션 4(통신 프로토콜)** — "UDP 9001 MOVE 전용" 아래에 새 채널 추가:
  - **UDP 9002 — 서버 탐색 전용.** 요청 `{"type":"DISCOVER"}`(브로드캐스트), 응답 `{"type":"SERVER","name":"<hostname 64자>","port":<TCP 포트>}`(요청 발신 주소로 유니캐스트, 패킷 1개, 개행 없음).
  - "MOVE만 UDP" 원칙은 **이벤트 채널**에 대한 것이고, 9002는 이벤트가 아니라 **연결 이전 탐색**이라 전용 포트로 분리했다고 명기(9001에 섞지 않은 이유).
  - **서버 IP는 응답 본문에 넣지 않는다**(발신 주소 사용 — 멀티 NIC/VPN 대응, 실측으로 확인). **세션 토큰 등 비밀은 절대 넣지 않는다**(인증 이전 단계).
  - 정확히 `type=="DISCOVER"`인 JSON 객체에만 응답, 그 외는 조용히 무시. 256바이트 초과 무시. 발신 IP별 1초 5회 응답 상한. 응답으로 세션/입력 상태를 바꾸지 않음.
  - `name`은 `ensure_ascii` escape(`\uXXXX`)를 포함할 수 있으므로 **클라이언트 파서는 escape를 해석해야 하고 수신 버퍼는 512바이트 이상**이어야 한다.
- **섹션 8(실행 방법)** — `--no-discovery` 플래그 추가(탐색 없이 서버만).
- **섹션 9(코딩 컨벤션)** — 강화할 항목 2개:
  - "진입점 동작은 새 모듈로" 규칙을 이번에도 따랐음(`discovery.py`) + **`ServerRuntime`의 새 선택 인자는 기본값을 "비활성"으로 둔다** — 기본값이 실포트를 잡으면 서버가 떠 있는 동안 무관한 테스트가 깨진다(트레이/단일 인스턴스 때와 같은 교훈).
  - **Windows UDP `recvfrom`은 정상 동작 중에도 `OSError`를 던진다**(`WSAEMSGSIZE` = 버퍼보다 큰 데이터그램, `WSAECONNRESET` = 응답 상대의 ICMP port unreachable). 수신 루프에서 `except OSError: break`로 쓰면 리스너가 조용히 죽는다 — 정지 신호/소켓 닫힘과 구분해서 계속 돌 것.
- **섹션 10(미결 사항)** — 새 행:
  - `탐색 실기기/방화벽 미검증`: 실기기 브로드캐스트, UDP 9002 인바운드 허용(exe는 별도 프롬프트), 인터페이스가 많은 기기에서 5회/초 제한과 3회 전송의 상호작용.
  - `탐색 응답 크기 vs 이름 64자`: 문자 수 기준 절단이라 비ASCII 이름에서 escape 후 바이트가 최대 424(비BMP 이론상 808)바이트 — Android 상한 512와의 여유. 바이트 기준 절단으로 바꿀지는 추후.
  - `서버 중복 실행` 행에 한 줄 추가: 탐색 소켓은 `SO_REUSEADDR`를 쓰지 않아 **두 번째 인스턴스의 9002 bind는 실패**한다(응답이 두 개 나가지 않음). named mutex와 별개의 두 번째 방어선.
