# android-dev 요약 — PIN 코드 인증 (Phase 5)

결론: **스펙대로 구현 완료, 테스트 420건 전부 통과(실패 0).** 범위는 `phone_pad_app/`만.
커밋하지 않았고 `git stash`/`checkout`/`reset`도 쓰지 않았다(병렬 server-dev 작업 트리 보존).

> **리더 지시 반영 (2차)**: 아래 §6-1로 보고한 "재연결이 서버 브루트포스 잠금을 유발"
> 문제에 대해, 리더 승인으로 **재연결 루프에서 `AUTH_FAILED`가 나오면 백오프를 소진하지 않고
> 즉시 중단하고 `Error(message, kind=AUTH_FAILED)`로 전이**하도록 구현했다. 나머지 실패
> (HEARTBEAT_TIMEOUT/CONNECTION_LOST/거부 등)는 **기존 백오프 재시도를 그대로 유지**한다.
> 해당 절(§1·§3·§6)을 갱신했다.

---

## 1. 변경 파일

### 신규 (main)
| 파일 | 역할 |
|------|------|
| `app/src/main/java/.../data/network/AuthHandshake.kt` | AUTH 줄 생성 / `AUTH_FAIL` 판별 / `reason` 추출. 순수 함수 object (`SessionHandshake`와 같은 방식) |
| `app/src/main/java/.../domain/model/AuthFailedException.kt` | PIN 거부 전용 예외(`IOException` 상속). 진단 메시지 `"Auth failed[: reason]"` |

### 수정 (main)
| 파일 | 변경 |
|------|------|
| `data/network/TcpClient.kt` | `connect(host, port, **pin**)`. 소켓 연결 직후 **다른 어떤 읽기/쓰기보다 먼저** AUTH 한 줄 전송 → 그 다음 `SESSION_HANDSHAKE_TIMEOUT_MS`로 한 줄 읽기. 그 줄이 `AUTH_FAIL`이면 `AuthFailedException`, 그 외는 기존과 동일(SESSION 파싱 / null) |
| `data/repository/TrackpadRepositoryImpl.kt` | `connect(host, port, pin)` → `openConnection(host, port, pin, …)` → `tcpClient.connect(host, port, pin)`. 성공 시 `lastConnectedPin` 저장, 자동 재연결(`startReconnect`)이 그 값을 재사용. **`startReconnect` 루프에 `AUTH_FAILED` 즉시 중단 분기 추가** — 그 실패만 남은 백오프를 버리고 `Error(outcome.message, AUTH_FAILED)`로 전이(종류를 `RECONNECT_FAILED`로 덮지 않는다). 다른 종류는 기존대로 `attempt += 1` |
| `domain/repository/TrackpadRepository.kt` | `connect(host, port, pin)` 시그니처 확장 + KDoc |
| `domain/model/ConnectionErrorKind.kt` | `AUTH_FAILED` 추가 |
| `domain/model/ConnectionErrorClassifier.kt` | `classifyOne`의 타입 판정 **첫 분기**에 `AuthFailedException -> AUTH_FAILED` (메시지 키워드 추측보다 우선) |
| `presentation/util/ConnectionErrorMessages.kt` | `AUTH_FAILED` 한국어 문구("PIN이 올바르지 않습니다. PC 화면에 표시된 PIN 6자리를 다시 확인해 입력하세요.") |
| `presentation/util/GestureConfig.kt` | `AUTH_PIN_MAX_LENGTH = 32` (와이어 크기 방어) |
| `presentation/trackpad/TrackpadViewModel.kt` | `_pinInput` + `onPinInputChange()`, `uiState` combine 5개로 확장, `connect()`가 host·PIN 둘 다 비면 진행 안 함. **DataStore 영속화 경로 없음** |
| `presentation/trackpad/TrackpadUiState.kt` | `pinInput: String = ""` |
| `presentation/trackpad/TrackpadScreen.kt` | IP 입력란 바로 아래 PIN 입력란(`KeyboardType.Number`, 라벨 "PIN 번호 (PC 화면의 6자리)"), 연결 버튼 `enabled = host.isNotBlank() && pin.isNotBlank()`, 안내 문구 갱신 |

---

## 2. 수정한 **기존** 테스트 목록 (파괴적 변경 대응 — 삭제 없음)

grep(`connect(`)로 전수 조사해 handshake를 시뮬레이션/모킹하는 곳을 **전부** 고쳤다.

| 파일 | 무엇을 고쳤나 | 테스트 수 |
|------|---------------|-----------|
| `data/network/TcpClientTest.kt` | **서버 역할 fake 재작성**: 수락 즉시 SESSION을 보내던 것을 → 클라이언트의 AUTH 줄을 **먼저 읽은 뒤** SESSION 응답. 서버 쪽 리더를 필드로 보관해 `send` 테스트가 같은 리더를 재사용(새 리더를 만들면 버퍼 바이트 유실). `connect(…, PIN)` | 4 → 6 (+2 신규) |
| `data/network/TcpClientConnectTimeoutTest.kt` | `connect(…, PIN)`, "실패한 연결 뒤에도 정상 연결" 테스트의 fake 서버가 AUTH를 먼저 읽도록 | 7 → 7 |
| `data/repository/TrackpadRepositoryImplTest.kt` | `tcpClient.connect(…, PIN)` 스텁/검증, `repository.connect(…, PIN)`, `connect(any(), any())` → `(any(), any(), any())`, 파일 상단 `PIN` 상수 | 27 → 27 |
| `data/repository/TrackpadRepositoryHeartbeatTest.kt` | 동일 | 12 → 12 |
| `data/repository/TrackpadRepositoryReconnectTest.kt` | 동일 (`OTHER_HOST` 경로 포함) | 16 → 16 |
| `data/repository/TrackpadRepositoryCancelConnectTest.kt` | 동일 | 8 → 8 |
| `presentation/trackpad/TrackpadViewModelDiscoveryTest.kt` | `repository.connect` 기대값에 PIN 인자 추가 + `viewModel.connect()`를 호출하는 5개 테스트에 `onPinInputChange(PIN)` 추가(그 중 "host가 비면 연결 안 함"은 **PIN을 채워 둬서** 다른 이유로 막히지 않게) | 13 → 13 |
| `presentation/util/GestureConfigTest.kt` | `AUTH_PIN_MAX_LENGTH` 불변식 테스트 추가 | 29 → 30 (+1) |

`presentation/util/ConnectionErrorMessagesTest.kt`는 **수정하지 않았다** — "모든 `ConnectionErrorKind`가 비어 있지 않은 한국어 문구를 갖는다" 루프가 `AUTH_FAILED`를 자동으로 커버한다(문구를 빠뜨렸으면 여기서 깨졌을 것).

## 3. 신규 테스트 (6개 파일, 46건)

| 파일 | 건수 | 고정하는 계약 |
|------|------|---------------|
| `data/network/AuthHandshakeTest.kt` | 10 | 와이어 리터럴 `{"type":"AUTH","pin":"483920"}` 정확 일치, 개행 없음, 빈 PIN도 같은 형식, 32자 절단, `"`/`\` 이스케이프, **제어문자 제거(개행으로 줄이 쪼개지지 않음)**, `AUTH_FAIL` 판별(compact/`json.dumps` 공백형 양쪽)·소문자·`AUTH_FAILED` 오인 금지, `reason` 추출 |
| `data/network/TcpClientAuthTest.kt` | 7 | **루프백 소켓**. 서버가 먼저 말하지 않아도 AUTH 후 SESSION 수신, `AUTH_FAIL` → `AuthFailedException`(+`reason`), 거부 후 죽은 소켓 안 남김, 빈 PIN도 성립, `AUTH_FAIL`도 SESSION도 아닌 줄 → 기존처럼 null, 서버 무응답 종료 → null |
| `data/repository/TrackpadRepositoryAuthTest.kt` | 9 | PIN 무가공 전달, `AUTH_FAIL` → `Error(kind=AUTH_FAILED)`, 핸드셰이크 실패와 분리, 거부 후 소켓 정리·UDP 미개방, **첫 연결 AUTH_FAIL은 재연결 시작 안 함**, 재연결이 성공했던 PIN 재사용, **재연결 1회차 AUTH_FAIL → 남은 백오프(7회)를 쓰지 않고 즉시 `Error(AUTH_FAILED)`** (600초 진행 후에도 `connect` 호출 총 2회), **AUTH_FAILED가 아닌 재연결 실패는 기존처럼 2회차 대기로 넘어감**(즉시 중단 분기가 넓어지는 회귀 방지) |
| `domain/model/AuthFailedClassificationTest.kt` | 7 | 타입 판정이 메시지 키워드보다 우선("refused"/"timed out"이 섞인 reason도 AUTH_FAILED), 원인 체인 탐색, 진단 메시지가 PIN 값을 담지 않음, 긴 reason 절단 |
| `presentation/util/AuthFailedMessageTest.kt` | 4 | 문구가 PIN과 확인할 곳을 짚음, 원문이 주 메시지를 점령하지 않고 보조 줄에 남음, HANDSHAKE_FAILED와 다른 문구 |
| `presentation/trackpad/TrackpadViewModelPinTest.kt` | 10 | PIN 빈 값/공백만 → connect 안 함, 둘 다 있으면 연결, PIN 무가공 전달, 앞뒤 공백 제거, `uiState.pinInput` 반영·초기값 `""`, **서버 선택으로 PIN이 채워지지 않음**, host를 고쳐도 PIN 유지 |

## 4. 테스트 결과 (강제 재실행 XML 집계)

```
./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest   → BUILD SUCCESSFUL
app/build/test-results/testDebugUnitTest/*.xml (37 files)
TOTAL tests=420  failures=0  errors=0  skipped=0
```
기준선 370 → **420 (+50)**, 회귀 0. `TrackpadRepositoryReconnectTest`는 16건 그대로 통과
(즉시 중단 분기가 기존 재연결 동작을 건드리지 않았다는 확인). 경고 1건은 기존
`limitedParallelism` opt-in 경고로 이번 변경과 무관.

**변이 검사(mutation check) 2회 — 새 테스트가 실제로 결함을 잡는지 확인:**
1. `TcpClient`에서 `w.println(AuthHandshake.buildAuthLine(pin))` 한 줄 제거 → `TcpClient*`
   **20건 중 13건 실패**(`TcpClientTest` 6/6, `TcpClientAuthTest` 6/7,
   `TcpClientConnectTimeoutTest` 1/7).
2. 재연결 루프의 `if (outcome.kind == ConnectionErrorKind.AUTH_FAILED)`를 `if (false)`로
   무력화 → `data.repository.*` **83건 중 정확히 1건 실패**(새 "즉시 Error" 테스트만).
   즉시 중단 분기가 그 테스트로만 고정되어 있고 다른 재연결 테스트를 흔들지 않음을 함께 확인.

두 변이 모두 원복 후 전체 재실행해 420건 통과를 재확인했다.

## 5. 스펙 이탈 / 스펙이 정하지 않아 내가 정한 것

이탈은 **없음**(와이어 형식·필드명·채널·상태 전이 전부 스펙대로). 아래는 스펙이 비워 둔
자리에 대한 결정 — 리더/QA가 판단할 수 있게 명시한다.

1. **`AuthFailedException`을 `domain/model/`에 뒀다** (스펙은 위치 미지정). `data/network/`에
   두면 `ConnectionErrorClassifier`(domain)가 data를 import하게 되어 의존 방향이 뒤집힌다.
   `TcpClient`(data) → domain 의존은 정상 방향.
2. **IP 입력란의 `imeAction`을 `Go` → `Next`로 바꿨다.** 그대로 두면 IP만 입력하고 키보드
   "Go"를 눌렀을 때 PIN이 비어 `connect()`가 조용히 무시돼 "앱이 반응 없음"으로 보인다.
   `onGo → connect()`는 PIN 입력란으로 옮겼다.
3. **PIN의 앞뒤 공백만 제거한다**(host와 같은 규약). 길이·숫자 여부는 검증하지 않는다 —
   판정자는 서버.
4. **재연결용 `lastConnectedPin`을 추가**했다. 스펙 3번이 "재연결도 `connect()`를 그대로
   탄다"고 했으므로 PIN을 들고 있어야 한다. 성공한 연결에서만 갱신하며 영속화하지 않는다.
5. 연결 화면 안내 문구를 "IP 주소와 PC 화면에 표시된 PIN을 입력하세요"로 갱신(스펙 6번의
   "안내 문구 한 줄 추가 고려" 반영).
6. `TrackpadEvent` sealed class는 **건드리지 않았다** — AUTH는 제스처가 아니라 연결 계층
   메시지이므로 AGENTS.md 섹션 9의 HEARTBEAT 예외 규약을 따라 data 계층에만 뒀고,
   와이어 리터럴은 테스트로 고정했다.

server-dev의 `pc_server/pin_auth.py`·`server.py`를 **읽기만** 해서 교차 확인한 결과
`{"type":"AUTH","pin":…}` / `{"type":"AUTH_FAIL","reason":"invalid_pin"}`가 양쪽 동일하고,
서버의 첫 줄 상한 4096자 > 우리 최대 전송(32자 PIN + 이스케이프) 이라 안전하다. 서버가
인증이 꺼진 상태에서도 "첫 줄은 AUTH 형식이어야 한다"고 요구하는 점도 우리 구현과 일치한다
(항상 보냄).

## 6. 미해결 이슈 / 한계

1. **[해소 — 리더 승인으로 스펙 변경] 재연결이 서버의 브루트포스 잠금을 유발하던 문제.**
   원래 스펙 3번은 "재연결 중 AUTH_FAILED는 기존 재연결 실패 처리와 동일(백오프 소진)"이었다.
   그대로 두면 서버 재시작으로 PIN이 바뀔 때 재연결 8회(1+2+4+8+10+10+10+10 = **55초**)가
   전부 AUTH 실패로 집계되고, 서버 규칙(**60초/5회 → IP 잠금**)에 따라 5회째(약 25초)에
   폰 IP가 잠긴다. 그러면 남은 재시도와, 사용자가 곧바로 **올바른** PIN을 입력한 첫 수동
   연결까지 서버가 AUTH 줄도 읽지 않고 조용히 닫고, 앱은 그것을 `HANDSHAKE_FAILED`
   ("Phone Pad 서버가 아니거나 버전이 다릅니다")로 분류해 최대 60초간 오해를 부르는 문구를
   보여준다. → **리더 승인으로 후보 ①을 구현**: 재연결 루프는 `AUTH_FAILED`를 만나면 즉시
   중단하고 `Error(kind=AUTH_FAILED)`로 가서 사용자에게 PIN을 다시 묻는다. 서버는 이 시나리오
   에서 실패 2회(유실 전 성공 1 + 재시도 1)만 보므로 잠금 임계(5회)에 닿지 않는다.
   **남은 경계**: 사용자가 틀린 PIN으로 수동 연결을 5회 연달아 누르면 여전히 잠기고, 그
   상태의 실패는 `HANDSHAKE_FAILED`로 보인다(서버가 잠금을 알리지 않는 것이 스펙이라 앱이
   구분할 수단이 없다 — 아래 6번과 같은 뿌리). 연결 버튼이 PIN 빈 값을 막는 것이 이 카운터를
   낭비하지 않기 위한 1차 방어다.
2. PIN 입력란 자체에 자동 테스트가 없다 — `TrackpadScreen`은 Compose UI 테스트가 전무한
   기존 한계(AGENTS.md 섹션 10)를 그대로 따른다. 라벨·키보드 타입·버튼 활성 조건은
   컴파일 + 코드 리뷰로만 확인했다.
3. **실기기 미검증**: 숫자 키패드가 실제로 뜨는지, 입력란 2개 + 오류 2줄 + 탐색 결과가
   소형 화면/키보드 노출 시 잘리지 않는지, IP→PIN 포커스 이동(`ImeAction.Next`) 체감,
   PIN 오류 후 재입력 흐름.
4. PIN은 연결 성공 후에도 `ViewModel` 메모리에 남는다(재연결에 필요). 프로세스 메모리
   밖으로는 나가지 않지만 "쓰고 버리기"는 아니다.
5. 앱을 완전히 종료하고 다시 켜면 PIN을 다시 입력해야 한다 — **의도된 동작**(스펙 5번).
6. 앱이 서버의 브루트포스 잠금 상태를 구분할 수단이 없다(스펙이 의도한 것). 그래서
   잠금 중 실패는 전부 `HANDSHAKE_FAILED`로 보인다 — 위 1번의 근원.

## 7. 리더가 AGENTS.md에 반영할 내용

- **섹션 4 (통신 프로토콜)**: TCP 9000 핸드셰이크 설명을 **뒤집어야 한다**. "클라이언트가
  연결하면 서버가 먼저 SESSION을 보낸다" → "**클라이언트가 연결 직후 AUTH 한 줄을 먼저
  보내고**, 서버가 그 응답으로 SESSION(성공) 또는 AUTH_FAIL(불일치, 보낸 뒤 즉시 닫음)을
  보낸다". 함께 기록할 것:
  - `{"type":"AUTH","pin":"483920"}` — 인증이 꺼져 있어도 **항상** 보낸다(와이어 형식이 서버
    설정에 따라 갈라지지 않는다). 키 순서 `type` → `pin`, 공백 없음.
  - `{"type":"AUTH_FAIL","reason":"invalid_pin"}`
  - 서버의 첫 줄 읽기 타임아웃 3초, 형식 오류/EOF/타임아웃/잠금은 **무응답 종료**,
    PIN 불일치만 `AUTH_FAIL`. 브루트포스: 발신 IP별 60초/5회 실패 → 잠금.
  - PIN은 탐색 응답(UDP 9002)에 **절대** 넣지 않는다.
- **섹션 5/감도 상수**: `AUTH_PIN_MAX_LENGTH = 32` (제스처 상수는 아니지만 `GestureConfig`에
  있으므로 표에 추가).
- **섹션 6 (로드맵)**: Phase 5 "PIN 인증" 완료 표기.
- **섹션 4 또는 6 (재연결 동작)** — 이번에 확정된 새 규칙 한 줄:
  **"자동 재연결은 `AUTH_FAILED`를 만나면 백오프를 소진하지 않고 즉시 중단하고
  `Error(kind=AUTH_FAILED)`로 간다."** 근거: PIN 불일치는 기다려서 복구되는 일시 장애가
  아니라 "첫 연결 실패는 재시도하지 않는다"와 같은 범주이고, 계속 두드리면 서버의 브루트포스
  잠금(60초/5회)을 스스로 유발해 올바른 PIN으로 수동 재연결해도 최대 60초간 막힌다.
  **다른 실패 종류(HEARTBEAT_TIMEOUT/CONNECTION_LOST/거부 등)는 기존 백오프 재시도를 유지한다**
  — 이 분기를 넓히면 재연결 기능 자체가 죽으므로 회귀 방지 테스트를 함께 두었다.
- **섹션 9 (컨벤션)** — 이번에 얻은 두 가지:
  - **와이어 핸드셰이크의 방향/순서를 바꾸는 변경은 "새 테스트 추가"로 끝나지 않는다.**
    handshake를 흉내 내는 기존 테스트를 grep으로 전수 조사해 **fake 서버의 대사 순서까지**
    고쳐야 한다(이번에 기존 8개 파일 수정). 순서를 고정하는 테스트는 "fake 서버가 먼저
    말하지 않는다"로 만들어야 의미가 있다 — 그러지 않으면 AUTH를 안 보내도 통과한다.
  - **같은 소켓에 리더를 두 번 만들지 않는다.** 테스트의 fake 서버가 첫 줄을 읽은
    `BufferedReader`를 버리고 새로 만들면, 첫 리더 버퍼에 남은 바이트가 사라져 이후 읽기가
    조용히 어긋난다(`TcpClientTest`에서 리더를 필드로 보관해 해결).
- **섹션 10 (미결 사항)**:
  - 기존 "PIN 인증" 행을 **해소**로 갱신하되, 위 6번 1항(재연결이 잠금을 유발 →
    `HANDSHAKE_FAILED`로 오보고)을 새 미결 항목으로 남길 것.
  - "PIN 인증 실기기 미검증"(숫자 키패드, 소형 화면 잘림, 포커스 이동) 행 추가.
  - 기존 "탐색 응답 위조 가능" 행: 근본 해결로 PIN 인증을 지목하고 있었으므로, "연결 시
    PIN을 요구하므로 위조 목록을 눌러도 공격자 서버는 세션을 발급할 수 없다"는 점을 반영
    (단 사용자가 공격자 주소에 PIN을 **입력해 버리면** 그 PIN이 공격자에게 노출된다 —
    남은 경계로 기록).
