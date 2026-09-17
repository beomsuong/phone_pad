# 요청: MOVE 이벤트 UDP 분리 + 세션 토큰 매칭

**범위 판단:** 교차 경계면 (통신 채널 구조 변경 + AGENTS.md 섹션 4 프로토콜 수정)
**실행 경로:** 이 환경에 TeamCreate/TaskCreate 도구가 없어 실시간 팀 협상 대신, 오케스트레이터(리더)가 스펙을 아래와 같이 사전 확정하고 android-dev/server-dev를 병렬 서브 에이전트로 호출 → protocol-qa로 사후 검증.

## 확정 스펙

### TCP 핸드셰이크 (기존 9000, 확장)
1. 클라이언트가 TCP 연결하면 서버가 즉시 한 줄을 보낸다: `{"type":"SESSION","session":"<32자리 hex 토큰>"}\n` (`uuid.uuid4().hex` 등)
2. 클라이언트는 이 줄을 읽어 세션 토큰을 확보한 뒤에야 `ConnectionState.Connected`로 전환한다
3. CLICK 등 기존 TCP 이벤트는 그대로 유지 (세션 필드 불필요)
4. 서버는 TCP 연결이 끊기면 해당 세션 토큰을 활성 목록에서 제거한다 (재연결 시 새 토큰 발급)

### UDP 채널 (신규 9001)
1. 서버는 시작 시 UDP 소켓을 9001에 바인딩하고 별도 스레드에서 수신 루프를 돈다
2. 클라이언트는 세션 토큰 확보 후 UDP로 MOVE 이벤트를 전송: `{"session":"<토큰>","type":"MOVE","dx":2.5,"dy":-1.0}` (JSON 한 덩어리, UDP 패킷 하나 = 이벤트 하나, 개행 불필요)
3. 서버는 UDP 패킷을 받으면 `session`이 활성 목록에 있는지 확인 → 있으면 dx/dy를 뽑아 기존 `InputController.handle_event({"type":"MOVE","dx":...,"dy":...})`를 그대로 재사용 → 없으면 조용히 무시(크래시 금지)
4. 활성 세션 목록은 TCP accept 스레드와 UDP 수신 스레드가 함께 접근하므로 `threading.Lock`으로 보호

### Android 변경 대상
- `data/network/TcpClient.kt`: connect 직후 서버가 보낸 한 줄을 읽어(`BufferedReader`) 세션 토큰을 파싱해 반환하는 기능 추가
- `data/network/UdpClient.kt` (신규): `DatagramSocket`으로 host:9001에 MOVE JSON을 전송하는 클라이언트
- `data/repository/TrackpadRepositoryImpl.kt`: connect 시 TCP 핸드셰이크로 세션 토큰 획득 → UDP 클라이언트 준비. `sendEvent`에서 `TrackpadEvent.Move`만 UDP(+session 필드 포함)로, 그 외는 기존처럼 TCP로 분기. `disconnect()`에서 UDP 소켓도 정리하고 세션 토큰 초기화
- `presentation/util/GestureConfig.kt`: `UDP_PORT = 9001` 상수 추가

### Server(pc_server) 변경 대상
- `server.py`: UDP 소켓(9001) 리스너 스레드 추가, 활성 세션 집합(Lock으로 보호) 관리, TCP 연결 시 세션 발급 및 최초 전송, TCP 연결 종료 시 세션 제거
- `input_controller.py`: 기존 `handle_event`는 그대로 재사용 (수정 불필요할 가능성 높음 — 확인 후 필요시만 변경)

### 테스트
- Android: `TrackpadRepositoryImpl`이 Move는 UDP(mock)+session 필드로, Click은 TCP(mock)로 보내는지 JUnit+MockK로 검증. TCP 핸드셰이크 세션 파싱 테스트.
- Server: `pc_server/tests/`에 세션 등록/미등록 시 UDP MOVE 처리 여부, TCP 연결 종료 시 세션 제거 여부를 pytest로 검증 (SendInput은 모킹).

## 참고 문서
- `AGENTS.md` 섹션 4(통신 프로토콜), 섹션 7(세션 흐름) — 구현 후 실제 코드에 맞게 갱신 필요
