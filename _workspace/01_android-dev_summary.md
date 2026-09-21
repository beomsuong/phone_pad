# android-dev 요약 — UDP 브로드캐스트 자동 서버 탐색 (Phase 4 마지막 항목)

## 1. 결론

`request.md`의 "공통 금지 / 와이어 스펙 / Android 스펙"을 **스펙 이탈 없이** 전부 구현했다.
`phone_pad_app/`만 수정했고, 커밋·`git stash/checkout/reset`·`AGENTS.md`/`CLAUDE.md`/`pc_server/`/`.claude/worktrees/`
변경은 하지 않았다(작업 트리에 보이는 `pc_server/server.py`, `pc_server/discovery.py`,
`pc_server/tests/test_discovery.py` 변경은 병렬 server-dev의 것이며 손대지 않았다).

- 테스트: **368 tests, 0 failures, 0 errors, 0 skipped** (기준선 292 → **+76**, 회귀 0)
- 권한 추가 없음 — `AndroidManifest.xml` **무변경**(INTERNET만으로 브로드캐스트 송수신)
- 와이어 프로토콜: 요청 `{"type":"DISCOVER"}` / 응답 `{"type":"SERVER","name":..,"port":..}`,
  전용 UDP **9002**. TCP 9000·UDP 9001 경로는 코드 한 줄도 건드리지 않았다.

## 2. 변경 파일

### 신규 (main)
| 파일 | 역할 |
|------|------|
| `data/network/DiscoveryProtocol.kt` | 와이어 포맷 순수 파서(`DISCOVER_REQUEST` 리터럴, `parseResponse`). 소켓 비의존 |
| `data/network/ServerDiscoveryClient.kt` | 브로드캐스트 송신 + 폴링 수신 루프. `DiscoverySocket`/`DiscoveryPacket`(internal) 포함 |
| `data/repository/ServerDiscoveryRepositoryImpl.kt` | 도메인 경계(위임만) |
| `domain/model/DiscoveredServer.kt` | `name`/`host`/`port` |
| `domain/model/DiscoveryState.kt` | `Idle` / `Searching` / `Found(servers)` / `NotFound` |
| `domain/repository/ServerDiscoveryRepository.kt` | 인터페이스 |
| `domain/usecase/DiscoverServersUseCase.kt` | ViewModel의 유일한 진입점 |
| `presentation/trackpad/ServerDiscoverySection.kt` | "서버 찾기" 버튼 + 결과 목록 (별도 컴포저블 파일) |
| `presentation/util/DiscoveryMessages.kt` | 한국어 문구 전담(표시 계층 순수 함수) |

### 수정 (main)
| 파일 | 변경 |
|------|------|
| `presentation/util/GestureConfig.kt` | `DISCOVERY_PORT=9002`, `DISCOVERY_TIMEOUT_MS=1500`, `DISCOVERY_PROBE_COUNT=3`, `DISCOVERY_PROBE_INTERVAL_MS=300`, `DISCOVERY_RECEIVE_POLL_MS=200`, `DISCOVERY_MAX_RESULTS=8`, `DISCOVERY_MAX_NAME_LENGTH=64` 추가 (+57줄) |
| `presentation/trackpad/TrackpadViewModel.kt` | `DiscoverServersUseCase` 주입, `_port`/`_discovery` 상태, `startDiscovery()`/`selectServer()`/`stopDiscovery()`. `connect()`가 `uiState.value.port` 대신 `_port.value`를 읽도록 수정 |
| `presentation/trackpad/TrackpadUiState.kt` | `discovery` 필드 추가 (기존 `port` 필드가 처음으로 실제로 쓰이게 됨) |
| `presentation/trackpad/TrackpadScreen.kt` | `ConnectPanel`에 인자 4개 + `ServerDiscoverySection` 호출 1개, 연결 화면 Column에 `verticalScroll` (+27줄). **제스처(`pointerInput`) 코드 무접촉** |
| `di/AppModule.kt` | `ServerDiscoveryRepository` 바인딩 (+9줄) |

### 신규/수정 (test)
| 파일 | 건수 |
|------|------|
| `data/network/DiscoveryProtocolTest.kt` (신규) | 25 |
| `data/network/ServerDiscoveryClientTest.kt` (신규, 소켓·시간 주입) | 17 |
| `data/network/ServerDiscoveryLoopbackTest.kt` (신규, 실소켓) | 3 |
| `presentation/trackpad/TrackpadViewModelDiscoveryTest.kt` (신규) | 13 |
| `presentation/util/DiscoveryMessagesTest.kt` (신규) | 6 |
| `domain/model/DiscoveryStateTest.kt` (신규) | 3 |
| `data/repository/ServerDiscoveryRepositoryImplTest.kt` (신규) | 2 |
| `domain/usecase/DiscoverServersUseCaseTest.kt` (신규) | 1 |
| `presentation/util/GestureConfigTest.kt` (수정) | 23 → 29 (탐색 불변식 6건 추가) |
| `presentation/trackpad/TrackpadViewModelTest.kt` (수정) | 생성자 인자 추가만(로직·기대값 무변경, 19건 그대로 통과) |

## 3. 확정 스펙 준수 대조

| 스펙 항목 | 구현 |
|-----------|------|
| 전용 UDP 9002, 9001에 섞지 않음 | `GestureConfig.DISCOVERY_PORT`, MOVE 경로 무변경 (`GestureConfigTest`가 포트 3개 상호 배타 고정) |
| `255.255.255.255` + 인터페이스별 서브넷 브로드캐스트 | `defaultBroadcastTargets()`: 제한 브로드캐스트 + `isUp && !isLoopback` 인터페이스의 `interfaceAddresses.broadcast` |
| 0/300/600ms 3회, 창 1500ms | 루프가 `nowMs()` 기준으로 시각 판정. 테스트가 전송 시각 3건을 고정 |
| 짧은 `soTimeout` 루프 + 취소 시 소켓 닫기 | 폴링 200ms(항상 ≥1ms), 매 회차 `yield()`로 취소 확인, **모든 경로 `finally { socket.close() }`** |
| 응답 파싱은 순수 코드 | `DiscoveryProtocol.parseResponse(data, length, senderHost)` |
| `type=="SERVER"`, `port` 정수 1..65535, `name` 문자열(제어문자 제거·64자·빈 값→발신 주소) | 전부 구현 + 25건 테스트 |
| 서버 주소는 발신 주소만 | 응답의 `host`/`ip` 필드가 있어도 무시(전용 테스트) |
| 발신 주소가 IPv4 아니거나 에코면 무시 | IPv4 정규식 + `DISCOVER_REQUEST` 에코 검사 |
| `host:port` 중복 제거, 최대 8개, 순서 유지 | `LinkedHashMap` + 상한 도달 시 즉시 종료 |
| ViewModel은 UseCase 경유 | `DiscoverServersUseCase` → `ServerDiscoveryRepository` → `ServerDiscoveryClient` |
| 탐색 중 재호출 무시(정책 하나로 고정) | `startDiscovery()`가 `Searching`이면 즉시 return |
| 선택 시 host/port만 채우고 **자동 연결 안 함** | `selectServer()`가 `_hostInput`/`_port`만 설정 |
| 호스트 직접 수정 시 port 기본값 복귀 | `onHostInputChange()`가 `_port = DEFAULT_PORT` |
| 연결 시작/취소·ViewModel 종료 시 탐색 취소 | `connect()`/`cancelConnect()`가 `stopDiscovery()`, 종료는 `viewModelScope`가 담당 |
| 결과 없음 시 한국어 안내(Wi-Fi/서버/방화벽 UDP 9002/수동 입력) | `DiscoveryMessages.NOT_FOUND` 4줄 + 계약 테스트 |
| 별도 컴포저블 파일로 분리 | `ServerDiscoverySection.kt` |
| 권한 추가 없음 | 매니페스트 무변경 |

**스펙에서 벗어난 부분: 없음.** 스펙이 열어 둔 선택지에서 고른 것과, 스펙에 없어 추가한 방어 장치는 아래.

### 스펙이 위임한 선택 / 추가한 방어
1. **탐색 중 재호출 = 무시**(재시작 아님). 재시작이면 버튼 연타가 창을 계속 리셋해 "영원히 찾는 중"이 된다.
2. **응답 512바이트 상한**(`DiscoveryProtocol.MAX_RESPONSE_BYTES`) + 수신 버퍼 동일 크기. 스펙의 256바이트 상한은
   서버가 받는 **요청**에 대한 것이라, 응답 쪽에도 대칭으로 상한을 뒀다(위조 응답이 큰 문자열을 밀어넣는 것 차단).
3. **엄격 UTF-8 디코딩**(REPORT 모드). `String(bytes, UTF_8)`은 깨진 바이트를 U+FFFD로 대체해 "UTF-8 아니면 무시"를 못 지킨다.
4. **JSON 이스케이프 해제**(`\uXXXX` 포함). 파이썬 `json.dumps` 기본값(`ensure_ascii=True`)이라 한글 PC 이름이
   이스케이프로 오는데, 풀지 않으면 목록에 역슬래시 범벅이 찍힌다. 깨진 이스케이프는 패킷 무시.
5. **`name` 필드가 없거나 문자열이 아니면 패킷 무시**(스펙의 "그 외는 무시"를 문자 그대로). 빈 문자열만 발신 주소로 대체.
6. **전송이 3회 전부 실패하면 창을 기다리지 않고 즉시 빈 결과**. 응답이 올 수 없는 상태로 1.5초를 붙잡지 않는다.
7. 연결 화면 Column에 `verticalScroll` — 결과 8줄이 붙으면 소형 화면에서 "연결"/"감도 설정" 버튼이 잘린다.

## 4. 작업 중 실제로 발견한 것 (다음에도 걸릴 함정)

1. **`runCatching`이 `CancellationException`을 삼켜 취소된 탐색이 "못 찾음"으로 표시됐다.**
   `TrackpadViewModelDiscoveryTest`의 "연결을 시작하면 진행 중인 탐색을 끊는다"가 실제로 잡아냈다
   (`expected Idle but was NotFound`). `try/catch(CancellationException){throw}/catch(Exception)`로 수정.
   → AGENTS.md의 "취소는 rethrow" 규약이 ViewModel 계층에도 적용된다는 사례.
2. **KDoc 안의 `\uXXXX` 리터럴이 kapt 빌드를 깬다.** kapt이 생성하는 Java 스텁 주석에 그대로 복사되어
   `error: illegal unicode escape`로 `:app:kaptDebugKotlin`이 실패한다(같은 파일의 무관한 import 줄까지 오류로 뜬다).
   주석에서는 이스케이프를 말로 풀어 쓸 것. 코드/문자열 리터럴은 무해(스텁은 본문을 버린다).
3. **`runTest` + 별도로 만든 `StandardTestDispatcher`는 스케줄러가 갈라진다** —
   `Detected use of different schedulers`로 14건이 한꺼번에 죽었다. 디스패처를 필드 하나로 만들고
   모든 테스트를 `runTest(dispatcher)`로 돌려야 한다.
4. 루프백 실소켓 테스트에서 **닫힌 포트로 보내면 Windows의 ICMP port unreachable이 다음 `recv`를 깨울 수 있어**
   "창이 끝날 때까지 기다린다"를 측정할 수 없다. 받기만 하고 답하지 않는 소켓을 세워 재현했다.

## 5. 미해결 이슈 / 한계

1. **실기기 미검증**: 실제 Wi-Fi에서의 브로드캐스트 도달(AP가 제한 브로드캐스트를 버리는지), Android 10+의
   멀티캐스트/브로드캐스트 전력 제어, 결과 목록 렌더·탭 선택, 1.5초 창의 체감, PC 방화벽 첫 프롬프트.
   JVM 단위 테스트와 루프백 왕복만 통과했다.
2. **서버가 비표준 UDP(MOVE) 포트로 뜨는 경우는 범위 밖**(스펙 명시). 응답의 `port`는 TCP 포트뿐이고
   MOVE는 여전히 `GestureConfig.UDP_PORT`(9001) 고정이다.
3. **탐색은 인증이 없다.** 같은 LAN의 누구나 `SERVER` 응답을 위조해 목록에 줄을 올릴 수 있다. 그래서 선택은
   입력란만 채우고 연결은 사용자가 누르게 했지만, 사용자가 이름만 보고 누르면 여전히 공격자 주소로 붙는다 —
   근본 해결은 PIN 인증(Phase 5, AGENTS.md 섹션 10의 기존 항목)과 함께 다뤄야 한다.
4. **IPv6 전용 네트워크에서는 동작하지 않는다**(브로드캐스트 자체가 IPv4 개념, 파서도 IPv4 발신 주소만 허용).
   IPv6 지원은 멀티캐스트(mDNS 등) 설계가 필요해 범위 밖.
5. **취소 후 최대 200ms 동안 소켓이 살아 있다**(폴링 1회분). 블로킹 `receive`를 깨울 다른 수단이 없어서 감수했다
   (`TcpClient.connect()`가 소켓을 닫아 깨우는 방식과 달리, 여기서는 루프가 짧아 닫기 경쟁을 만들지 않는 편을 택했다).
6. **`ServerDiscoverySection`은 Compose UI 테스트가 없다**(프로젝트 전체 관례와 동일 — 순수 로직은
   `DiscoveryMessages`/ViewModel로 분리해 커버). 목록 탭 → `selectServer` 배선은 사람이 한 번 봐야 한다.
7. 탐색 중 화면을 회전하면 `discovery` 상태는 ViewModel에 있어 유지되지만, 진행 중 소켓은 `viewModelScope`가
   유지되는 동안 그대로 돈다(구성 변경으로 ViewModel이 살아남는 정상 동작). 실기기 미확인.

## 6. server-dev에게 전달 요청 (SendMessage로 직접 닿지 않아 리더 경유)

앱 파서의 엄격함 때문에 서버가 **반드시** 지켜야 하는 3가지 — 모두 현재 스펙과 일치하며, 스펙 변경 요청이 아니다:
1. `name`은 **항상 포함**하고 JSON 문자열이어야 한다. 빈 호스트명일 때 `"PC"`를 넣는 규칙이 지켜지지 않고
   필드를 생략하면 앱이 그 서버를 **무시**한다.
2. `port`는 JSON 정수여야 한다(`"9000"` 같은 문자열, 실수, 지수 표기는 무시된다). 1..65535 밖도 무시.
3. 응답 총 길이는 **512바이트 이하**. 이름 64자 절단을 지키면 여유롭다. (`ensure_ascii` 값은 어느 쪽이든 앱이 처리한다.)

참고: 한 번의 탐색에서 앱이 보내는 요청은 **대상 주소별 3회**다. 서버의 "발신 주소별 초당 5회" 제한과
충돌하지 않는다(같은 발신 IP에서 1.5초 동안 3회).

## 7. 리더가 AGENTS.md에 반영할 내용

**섹션 4 (통신 프로토콜) — 새 하위 절 "UDP 9002 — 서버 자동 탐색"**
```jsonc
// 클라이언트 → 서버 (브로드캐스트 UDP 패킷 1개, 개행 없음)
{"type":"DISCOVER"}
// 서버 → 클라이언트 (요청 발신 주소로 유니캐스트, 패킷 1개, 개행 없음)
{"type":"SERVER","name":"MY-PC","port":9000}
```
- 전용 포트 9002. **9001(MOVE 전용)에 섞지 않는다** — "UDP는 MOVE만" 원칙 유지. TCP 9000/UDP 9001 무변경.
- `name` = PC 호스트명(표시용, 64자 절단, 비면 `"PC"`), `port` = 서버의 **TCP** 포트. UDP MOVE 포트는 응답에 없다.
- **서버 주소는 응답 본문에 넣지 않고 발신 주소를 쓴다**(멀티 NIC/VPN 오추정 회피 + 주소 위조 차단).
- 세션 토큰 등 비밀은 절대 응답에 넣지 않는다(탐색은 인증 이전 단계).
- 앱은 `type != "SERVER"`, 비정수/범위 밖 `port`, 문자열 아닌 `name`, 비UTF-8, 512바이트 초과, 비IPv4 발신 주소,
  자기 요청 에코를 **조용히 무시**한다. 수동 IP 입력은 계속 fallback.

**섹션 5 / 감도 상수** — `GestureConfig`에 추가된 값 7개:
`DISCOVERY_PORT=9002`, `DISCOVERY_TIMEOUT_MS=1500L`, `DISCOVERY_PROBE_COUNT=3`,
`DISCOVERY_PROBE_INTERVAL_MS=300L`, `DISCOVERY_RECEIVE_POLL_MS=200`, `DISCOVERY_MAX_RESULTS=8`,
`DISCOVERY_MAX_NAME_LENGTH=64`. 불변식: `(PROBE_COUNT-1)*PROBE_INTERVAL < TIMEOUT - RECEIVE_POLL`,
`0 < RECEIVE_POLL <= PROBE_INTERVAL`, `TIMEOUT < CONNECT_TIMEOUT_MS` (`GestureConfigTest`가 강제).

**섹션 6 Phase 4** — "UDP 브로드캐스트 자동 서버 탐색" 체크. 앱 쪽 핵심 파일:
`DiscoveryProtocol`(순수 파서) / `ServerDiscoveryClient`(소켓·시간 주입) / `DiscoverServersUseCase` /
`TrackpadViewModel.startDiscovery·selectServer` / `ServerDiscoverySection`.

**섹션 9 (컨벤션) 추가 후보**
- **KDoc·주석에 `\u` 유니코드 이스케이프를 적지 않는다** — kapt 스텁 주석으로 복사되어
  `illegal unicode escape`로 빌드가 깨진다(실측).
- **`runCatching`으로 suspend 호출을 감싸지 않는다** — `CancellationException`까지 삼켜 취소를 실패로 보고한다.
  `try/catch(CancellationException){throw}/catch(Exception)` 패턴을 쓴다(repository에 이미 있던 규약을
  presentation 계층에도 적용).
- 테스트: **하나의 `TestDispatcher`를 필드로 두고 `runTest(dispatcher)`로 넘긴다** —
  `runTest {}` 안에서 새 디스패처를 만들면 `Detected use of different schedulers`로 무더기 실패한다.

**섹션 10 (미결 사항) 추가 후보**
- 자동 탐색 실기기 미검증(AP의 브로드캐스트 정책, 방화벽 프롬프트, 목록 UI).
- 탐색 응답은 인증이 없어 같은 LAN에서 위조 가능 — 자동 연결을 하지 않는 이유이며 PIN 인증과 함께 재검토.
- IPv6 전용 네트워크 미지원(브로드캐스트 기반).
- 서버가 비표준 UDP(9001) 포트로 뜨는 경우 탐색 결과로 알 수 없음(응답에 TCP 포트만 담기는 스펙).

## 8. 재현 명령

```powershell
$env:ANDROID_HOME = "C:\Users\membe\AppData\Local\Android\Sdk"
cd C:\Github\phone_pad\phone_pad_app
.\gradlew.bat :app:cleanTestDebugUnitTest :app:testDebugUnitTest --console=plain
# XML 집계: app\build\test-results\testDebugUnitTest\*.xml → tests=368 failures=0 errors=0 skipped=0
```
