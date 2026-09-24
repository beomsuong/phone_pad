# 요청 — UDP 브로드캐스트 자동 서버 탐색 (Phase 4 마지막 항목)

## 범위 판단: **교차 경계면** (사유: 새 UDP 채널/포트 + 새 메시지 2종 → AGENTS.md 섹션 4 수정 필요)

실행: android-dev ∥ server-dev 병렬 → protocol-qa 사후 검증. 리더가 아래 스펙을 사전 확정 — **임의로 바꾸지 말 것**.

배경 확인: `discovery` 워크트리(`.claude/worktrees/discovery`, 브랜치 `worktree-discovery`)는 코드 변경이 전혀 없는 상태(트레이 커밋 313d370에 멈춘 채 `_workspace` 파일만 수정)라 실질적으로 미착수다 — 이 작업이 그 자리를 이어받는다. **그 워크트리는 건드리지 말 것.**

공통 금지: **커밋 금지**(git add/commit 하지 말 것), `AGENTS.md`/`CLAUDE.md` 수정 금지(리더가 함), **`git stash`/`git checkout`/`git reset` 등 작업 트리를 되돌리는 git 명령 금지**(병렬 에이전트의 미커밋 작업을 날린다 — 지난 실행에서 실제로 사고가 났다. 기준선 비교가 필요하면 `git show HEAD:<path>`/`git diff`로 읽기만 할 것). 저장소 루트는 `C:\Github\phone_pad`, `.claude/worktrees/`는 무시.

## 와이어 스펙 (확정)

**전용 UDP 포트 9002** (`DISCOVERY_PORT`). 9001(MOVE 전용, 세션 토큰 검증)에 섞지 않는다 — "UDP는 MOVE만" 원칙 유지. TCP 9000/UDP 9001 프로토콜은 **무변경**.

```jsonc
// 클라이언트 → 서버 (브로드캐스트 UDP 패킷 1개, 개행 없음)
{"type":"DISCOVER"}

// 서버 → 클라이언트 (요청을 보낸 주소로 유니캐스트 응답, 패킷 1개, 개행 없음)
{"type":"SERVER","name":"MY-PC","port":9000}
```

- `name`: 서버의 PC 호스트명(`socket.gethostname()`), 표시용. 서버는 **최대 64자로 자른다**. 비어 있으면 `"PC"`.
- `port`: 서버의 **TCP** 포트(실제 바인딩된 값, 기본 9000). UDP MOVE 포트는 응답에 넣지 않는다 — Android는 지금처럼 `GestureConfig.UDP_PORT`를 쓴다(서버가 비표준 UDP 포트로 뜨는 경우는 이번 범위 밖, 한계로 문서화).
- **서버 IP는 응답 본문에 넣지 않는다.** 클라이언트가 응답 패킷의 **발신 주소**를 서버 주소로 쓴다(멀티 NIC/VPN 환경에서 서버가 자기 IP를 잘못 추정하는 문제 회피).
- **세션 토큰·기타 비밀은 절대 응답에 넣지 않는다**(탐색은 인증 이전 단계, 같은 LAN의 누구나 볼 수 있다).
- 서버는 `{"type":"DISCOVER"}`(JSON 객체, `type == "DISCOVER"`) **정확히 이 경우에만** 응답한다. 그 외 — 깨진 JSON, 배열/문자열 등 dict가 아닌 값, 다른 type, **256바이트 초과 패킷**, UTF-8 아님 — 은 **조용히 무시**(응답 없음, 예외 전파 없음, 로그 폭주 없음).
- 서버는 이 응답으로 **세션을 만들거나 입력을 주입하지 않는다**(상태 변경 0).

## 서버 스펙 (server-dev)

1. 새 모듈 `pc_server/discovery.py`(표준 라이브러리만): 
   - `DISCOVERY_PORT = 9002`
   - 순수 함수 `handle_discovery_packet(data: bytes, name: str, tcp_port: int) -> bytes | None`(소켓 비의존, 위 규칙 전부 구현 — 응답은 `separators=(",",":")` 압축 JSON, 키 순서 type→name→port).
   - `DiscoveryResponder`: `0.0.0.0:<discovery_port>`에 UDP 소켓을 바인딩하고 백그라운드 데몬 스레드에서 수신 → 응답 유니캐스트 전송. `start()`/`stop()`(정지 가능: 소켓 timeout 0.5s 폴링 + stop Event, 스레드 join). 포트 0 바인딩 + 실제 포트 노출(테스트용). **`SO_REUSEADDR`를 설정하지 않는다**(Windows에서 이미 점유된 포트에도 bind가 성공해 버리는 함정 — AGENTS.md 섹션 10 참조).
   - **바인딩 실패(`OSError`)는 서버 기동을 막지 않는다**: ASCII 로그 한 줄(`[!] Discovery disabled: ...`) 후 탐색만 꺼진 채 서버는 정상 동작. 이유: 탐색은 편의 기능이고 수동 IP 입력이 fallback이다.
   - 응답 남용 방지: 발신 주소별 **초당 최대 5회**까지만 응답(초과분은 조용히 무시). 시간 소스는 주입 가능하게(`monotonic`) 해 sleep 없이 테스트. 발신자 테이블은 무한히 자라지 않게(예: 오래된 항목 정리 또는 상한).
   - 수신/응답 처리 중 예외는 스레드를 죽이지 않는다(잡아서 ASCII 로그, 루프 계속). 로그는 **ASCII만**(AGENTS.md 섹션 9).
2. `server.py` 통합은 **최소 diff**(대략 +25줄 이내): `ServerRuntime`에 선택 인자 `discovery_port: int | None = None`(기본 **비활성** — 기존 테스트가 `ServerRuntime`을 만들 때마다 실제 9002를 잡으면 서버가 떠 있을 때 깨진다), `serve()`에서 응답자 시작 / `stop()`·`close()`에서 정지. `main()`에서만 `DISCOVERY_PORT`를 넘기고, `--no-discovery` 옵션으로 끌 수 있게 한다. `handle_client`/`udp_listener`/`handle_udp_packet`/`SessionRegistry`/소켓 옵션은 **무변경**. 응답에 넣을 `port`는 `ServerRuntime`이 실제 바인딩한 TCP 포트.
3. 테스트(`pc_server/tests/test_discovery.py` 신규): 순수 함수(정상 → 정확한 바이트 리터럴, 이름 64자 절단, 빈 이름 → "PC", 잘못된 입력 18종 이상 무시: 깨진 JSON/배열/숫자/None/다른 type/`"discover"` 소문자/256바이트 초과/UTF-8 아님 등), 실제 **루프백 UDP** 왕복(응답자 시작 → `127.0.0.1`로 DISCOVER 전송 → 응답 파싱 → stop), 잘못된 패킷 후에도 스레드 생존, 남용 제한(같은 발신자 6번째 무시, 창 경과 후 재응답, 발신자 분리), 바인딩 실패 시 서버 계속(포트를 미리 점유해 재현) + 로그 ASCII, 응답이 세션 토큰을 포함하지 않음, `ServerRuntime` 기본값은 탐색 비활성(9002 미점유)/명시하면 활성·`stop()`에서 포트 해제, `--no-discovery` 파싱. 기준선 **287 passed, 1 skipped** → 회귀 0. 실제 서버를 `main()`으로 띄워 9002 응답을 한 번 실측하면 좋다(끝나면 프로세스 정리 — 지난번처럼 잔여 프로세스를 남기지 말 것).

## Android 스펙 (android-dev)

1. `data/network/`에 탐색 클라이언트 신설(예: `ServerDiscoveryClient` — 이름은 자유). 동작:
   - `DatagramSocket().apply { broadcast = true }`로 `{"type":"DISCOVER"}`를 **UDP 9002로 브로드캐스트**. 대상 주소는 두 가지를 모두 쓴다: `255.255.255.255`와, 활성 IPv4 인터페이스별 **서브넷 브로드캐스트**(`NetworkInterface.getNetworkInterfaces()` → `isUp && !isLoopback` → `interfaceAddresses`의 `broadcast != null` — 추가 권한 불필요. 일부 기기는 255.255.255.255를 버린다). 네트워크 열거/전송 실패는 개별적으로 삼키고(하나 실패해도 나머지 시도) 전부 실패면 빈 결과.
   - UDP 유실 대비 **0/300/600ms에 3회 전송**, 총 수신 창 `DISCOVERY_TIMEOUT_MS = 1500`(GestureConfig 상수). 수신은 `soTimeout` 짧게(≈200ms) 루프 + 남은 시간·코루틴 취소(`isActive`) 확인 — **블로킹 `receive`는 코루틴 취소로 안 풀리므로** 취소 시 소켓을 닫거나 짧은 타임아웃 루프로 반드시 빠져나올 것(AGENTS.md 섹션 6 `TcpClient` 취소 설계 참조). 소켓은 어떤 경로에서도 닫는다.
   - 응답 파싱은 **순수 코드**(예: `DiscoveryProtocol.parseResponse(bytes, senderHost)`)로 분리: `type == "SERVER"`, `port`가 정수이고 1..65535, `name`은 문자열(제어문자 제거, 64자 절단, 빈 값이면 발신 주소 문자열로 대체) — 그 외는 무시(예외 없음). **서버 주소는 발신 주소**(응답 본문의 어떤 필드도 주소로 쓰지 않는다). 발신 주소가 IPv4가 아니거나 자기 자신의 요청 에코(같은 DISCOVER 문자열)면 무시.
   - 결과는 `host:port` 기준 중복 제거, 최대 8개, 발견 순서 유지.
2. 도메인/프레젠테이션(Clean Architecture 유지 — ViewModel은 UseCase 경유, 네트워크 직접 호출 금지):
   - `domain/model`에 `DiscoveredServer(name, host, port)`, 탐색 상태(예: `Idle / Searching / Found(servers) / NotFound`), 저장소/유스케이스, Hilt 바인딩.
   - `TrackpadViewModel`에 `startDiscovery()`/선택 처리: 탐색 중 재호출은 무시(또는 재시작 — 하나로 고정), 서버 선택 시 `hostInput = host`, `port = 선택한 port`로 채운다(**자동 연결하지 않는다** — 사용자가 연결 버튼을 누른다. 수동 IP 입력은 그대로 fallback). 사용자가 호스트를 직접 수정하면 `port`는 기본값(`GestureConfig.DEFAULT_PORT`)으로 되돌린다(선택한 서버의 비표준 포트가 다른 IP에 새는 것 방지 — 현재 `TrackpadUiState.port` 사용 방식을 먼저 확인할 것). 연결 시작/취소·ViewModel 종료 시 진행 중 탐색은 취소.
   - 화면: 연결 전(Disconnected/Error) 화면에 "서버 찾기" 버튼 + 결과 목록(서버 이름 + `host:port`, 탭하면 선택). 탐색 중 로딩 표시, 결과 없음이면 한국어 안내(같은 Wi-Fi인지·서버 실행 중인지·방화벽 UDP 9002 허용 여부·수동 IP 입력 가능). **별도 컴포저블 파일로 분리**(`ConnectionErrorSection.kt` 방식 — `TrackpadScreen.kt` diff 최소화, 트래킹 제스처 코드 무접촉). 사용자 문구와 내부 상태 분리 컨벤션(섹션 9) 준수.
3. `AndroidManifest.xml`은 **권한 추가 없이** 시도(INTERNET만으로 브로드캐스트 송수신 가능). 추가가 정말 필요하면 이유를 요약에 명시.
4. 상수(`GestureConfig`): `DISCOVERY_PORT = 9002`, `DISCOVERY_TIMEOUT_MS = 1500L`, 전송 횟수/간격, `DISCOVERY_MAX_RESULTS = 8`. `GestureConfigTest`로 불변식 고정(예: 마지막 전송 시각 < 총 창).
5. 테스트: 파서(정상/포트 범위/타입 불일치/이름 정제/발신 주소 사용/에코 무시/깨진 입력), 클라이언트는 **소켓/시간 주입**으로 결정적으로(가상 시간 `runTest`, 실제 1.5초 대기 금지 — 필요하면 루프백 실소켓 왕복 1~2건은 허용), 중복 제거·상한·취소 시 소켓 close·전송 일부 실패 격리, ViewModel(선택 시 host/port 반영, 자동 연결 안 함, 수동 편집 시 port 리셋, 중복 실행 정책, 연결 시작 시 취소), 문구 계약(모든 상태가 문구를 가짐). 
6. 런타임 테스트는 `:app:cleanTestDebugUnitTest :app:testDebugUnitTest`로 **강제 재실행**(`UP-TO-DATE` 스킵 방지). 기준선 **292 → 회귀 0**. `runTest`에서 무한 루프를 살려둔 채 끝내지 말 것(섹션 6 함정). Gradle이 SDK를 못 찾으면 `ANDROID_HOME`/`ANDROID_SDK_ROOT`=`C:\Users\membe\AppData\Local\Android\Sdk`.

## 산출물
- 각자 `_workspace/01_android-dev_summary.md` / `_workspace/01_server-dev_summary.md`(변경 파일, 테스트 결과 수치, 스펙에서 벗어난 부분, 미해결 이슈/한계, "리더가 AGENTS.md에 반영할 내용").
- 커밋하지 말 것.
