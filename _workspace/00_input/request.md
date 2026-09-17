# 요청: TCP Heartbeat 구현 (AGENTS.md Phase 2)

**범위 판단:** 교차 경계면 (새 이벤트 타입 HEARTBEAT/HEARTBEAT_ACK 추가 + 연결 유지/해제 로직 변경)
**실행 경로:** 리더가 스펙 사전 확정 → android-dev/server-dev 병렬 호출 → protocol-qa 사후 검증 (TeamCreate 미사용, `.claude/skills/phone-pad-orchestrator/SKILL.md` Phase 2B 참조)

## 배경
지난 MOVE UDP 분리 작업의 QA 리포트(`_workspace/02_protocol-qa_report.md`)에서 F-1으로 지적된 문제: Android가 세션 핸드셰이크 이후 TCP를 전혀 읽지 않아서, 서버가 세션을 회수해도(재시작, Wi-Fi 이탈 등) 앱은 `Connected` 상태로 남고 커서만 조용히 멈춘다. 이번 heartbeat 구현이 이 문제를 직접 해소한다 — Android가 TCP 읽기 루프를 갖게 되므로 서버 쪽 이상을 감지할 수 있다.

## 확정 스펙

### 상수
- Android `GestureConfig.kt`: `HEARTBEAT_INTERVAL_MS = 5000L`, `HEARTBEAT_MISS_LIMIT = 3`
- Server `server.py`: `HEARTBEAT_INTERVAL_S = 5.0`, `HEARTBEAT_MISS_LIMIT = 3`

### 와이어 포맷 (TCP 9000, 기존 newline-delimited JSON 그대로)
- 클라이언트 → 서버: `{"type":"HEARTBEAT"}` — 연결된 동안 5000ms(`HEARTBEAT_INTERVAL_MS`)마다 전송
- 서버 → 클라이언트: `{"type":"HEARTBEAT_ACK"}` — HEARTBEAT 줄을 받으면 즉시 응답. **`InputController.handle_event`로 넘기지 않는다** (마우스 명령이 아님)

### 연결 해제 판정 방식 (양쪽 대칭 — "카운터 기반", 둘 다 동일한 원리)
- **서버**: 핸드셰이크 직후 `conn.settimeout(HEARTBEAT_INTERVAL_S)`를 건다. `recv()`가 `socket.timeout`을 던지면 미응답 카운트 +1, **어떤 데이터든(꼭 HEARTBEAT가 아니어도) 성공적으로 받으면 카운트를 0으로 리셋**. 카운트가 `HEARTBEAT_MISS_LIMIT`(3)에 도달하면 로그 남기고 연결을 끊는다 (기존 `finally`의 세션 회수/소켓 종료 재사용).
- **클라이언트(Android)**: 핸드셰이크 성공 직후 소켓 `soTimeout`을 `HEARTBEAT_INTERVAL_MS`로 바꾼다. 이후 읽기 루프에서 한 줄 읽기를 반복 시도 — 타임아웃이면 미응답 카운트 +1, 아무 줄이나 성공적으로 읽으면 카운트를 0으로 리셋. 카운트가 3에 도달하면 `ConnectionState.Error("Heartbeat timeout")`로 전환하고 정리(cleanUp). EOF나 다른 IOException을 만나면(서버가 먼저 끊은 경우) 카운트를 기다리지 않고 즉시 `ConnectionState.Error("Connection lost")`로 전환한다.
- 두 판정 모두 "5초 간격 × 3회 무응답 ≈ 15초"라는 AGENTS.md 스펙을 만족하되, 구현은 카운터 기반으로 통일한다.

## Android 변경 대상
- `presentation/util/GestureConfig.kt`: `HEARTBEAT_INTERVAL_MS`, `HEARTBEAT_MISS_LIMIT` 상수 추가
- `data/network/TcpClient.kt`:
  - 핸드셰이크 성공 후 소켓 `soTimeout`을 `HEARTBEAT_INTERVAL_MS`로 설정하는 기능 추가 (현재는 핸드셰이크용 3000ms만 있고 이후 원래 타임아웃으로 되돌림 — 이제는 heartbeat 타임아웃으로 바꿔야 함)
  - `suspend fun readLine(): String?` 같은, 커넥션 유지 중 한 줄씩 읽을 수 있는 함수 노출 (`SocketTimeoutException`은 호출자가 판단하도록 그대로 던진다)
- `data/repository/TrackpadRepositoryImpl.kt`:
  - connect() 성공 시 이 리포지토리가 소유하는 코루틴 스코프에서 두 개의 루프를 시작: (1) 5초마다 HEARTBEAT 전송하는 sender, (2) 위에서 정의한 카운터 기반 판정을 수행하는 reader/watchdog
  - disconnect()/재연결 시작 시(F-2 패턴과 동일하게) 반드시 이전 루프를 취소하고 나서 진행 — 옛 루프가 새 연결에 대해 계속 돌면 안 됨
  - 두 루프 다 예외를 자체적으로 처리해서 앱을 죽이지 않아야 함
- 테스트: 5초 간격 전송(가상 시간), ACK 수신 시 카운터 리셋, 3회 연속 타임아웃 시 Error+cleanUp, EOF 시 즉시 Error, 재연결 시 이전 루프가 취소되는지

## Server(pc_server) 변경 대상
- `server.py`: `HEARTBEAT_INTERVAL_S`, `HEARTBEAT_MISS_LIMIT` 상수 추가. `handle_client`에서 핸드셰이크 직후 `conn.settimeout(HEARTBEAT_INTERVAL_S)` 설정. 메인 수신 루프에서 `socket.timeout`을 잡아 미응답 카운트 관리(3회 시 연결 종료), 정상 수신 시 카운트 리셋. 파싱된 이벤트의 `type`이 `"HEARTBEAT"`이면 `handle_event`로 넘기지 않고 `{"type":"HEARTBEAT_ACK"}\n`을 즉시 응답
- 기존 buffer/newline 파싱 로직과 `input_controller.py`는 최대한 그대로 유지 (HEARTBEAT는 `handle_client` 레벨에서 가로챈다)
- 테스트: HEARTBEAT 수신 시 ACK 응답 + handle_event 미호출, 연속 3회 timeout 시 세션 회수/소켓 종료, 중간에 정상 데이터가 오면 카운트가 리셋되어 3회를 못 채우면 연결 유지

## 참고 문서
- `AGENTS.md` 섹션 4(통신 프로토콜), 섹션 6(로드맵 Phase 2), 섹션 7(세션 흐름 다이어그램) — 구현 후 갱신 필요
- 지난 QA 리포트 F-1: `_workspace/02_protocol-qa_report.md`
