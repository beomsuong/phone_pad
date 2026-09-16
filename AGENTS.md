# Phone Pad — Agent Harness

AI 코딩 에이전트가 이 프로젝트를 이어받아 작업할 수 있도록 작성된 컨텍스트 문서입니다.
이 파일 하나만 읽어도 현재 상태와 다음 할 일을 파악할 수 있도록 유지하세요.

---

## 1. 프로젝트 한 줄 요약

Android 폰 터치스크린 → 같은 WiFi의 Windows PC 마우스 커서 제어.
애플 매직 트랙패드처럼 멀티터치 제스처까지 지원하는 것이 최종 목표.

---

## 2. 저장소 구조

```
phone_pad/
├── AGENTS.md                  ← 이 파일 (에이전트 컨텍스트)
├── phone_pad_app/             ← Android 앱 (Kotlin + Jetpack Compose)
│   └── app/src/main/java/com/example/phone_pad_app/
│       ├── PhonePadApplication.kt
│       ├── MainActivity.kt
│       ├── domain/
│       │   ├── model/         TrackpadEvent.kt, ConnectionState.kt
│       │   ├── repository/    TrackpadRepository.kt (interface)
│       │   └── usecase/       SendEventUseCase.kt
│       ├── data/
│       │   ├── network/       TcpClient.kt
│       │   └── repository/    TrackpadRepositoryImpl.kt
│       ├── di/                AppModule.kt
│       └── presentation/
│           ├── util/          GestureConfig.kt
│           └── trackpad/      TrackpadScreen.kt, TrackpadViewModel.kt, TrackpadUiState.kt
└── pc_server/                 ← Windows Python 서버
    ├── server.py
    └── input_controller.py
```

---

## 3. 기술 스택 & 버전

### Android
| 항목 | 값 |
|------|----|
| 언어 | Kotlin 1.8.10 |
| 빌드 | AGP 8.1.3, compileSdk/targetSdk 34, **minSdk 26** |
| UI | Jetpack Compose BOM 2023.03.00 (Compose 1.4.0) |
| Compose Compiler | 1.4.3 |
| 아키텍처 | Clean Architecture + MVVM |
| DI | Hilt 2.48 (kapt) |
| 비동기 | Coroutines 1.7.1 + Flow |
| ViewModel | lifecycle-viewmodel-compose 2.6.1 |

### Windows Server
| 항목 | 값 |
|------|----|
| 언어 | Python 3.x |
| 네트워크 | 표준 `socket` 모듈 |
| 커서 제어 | `ctypes.windll.user32.SendInput` |
| 패키징 | 추후 PyInstaller 단일 exe 고려 |

---

## 4. 통신 프로토콜

### 원칙 (변경 금지)
- **MOVE 이벤트만 UDP**, 나머지(클릭·스크롤·드래그·heartbeat 등) **전부 TCP**
- "이동 좌표인가?" 한 가지 기준으로 채널 결정. 애매하면 TCP.

### 현재 구현 (Phase 1 — TCP 단일)
포트: TCP **9000**
형식: **newline-delimited JSON** (한 줄 = 한 이벤트, `\n` 종료)

```jsonc
// 커서 이동
{"type":"MOVE","dx":2.5,"dy":-1.0}

// 좌클릭
{"type":"CLICK","button":"left"}

// 우클릭 (Phase 2)
{"type":"CLICK","button":"right"}

// 더블클릭 (Phase 2)
{"type":"DOUBLE_CLICK","button":"left"}

// 스크롤 (Phase 2)
{"type":"SCROLL","dx":0,"dy":-3}

// 드래그 (Phase 3)
{"type":"DRAG_START"}
{"type":"DRAG_END"}

// heartbeat (Phase 2+)
{"type":"HEARTBEAT"}
```

### Phase 2 이후 — 하이브리드
- UDP 포트: **9001** (MOVE 전용, 세션 토큰 포함)
  ```json
  {"session":"abc123","type":"MOVE","dx":2.5,"dy":-1.0}
  ```
- TCP로 세션 토큰 발급 후 UDP 인증에 사용

---

## 5. 제스처 설계

제스처 해석은 **Android 앱에서** 수행. 서버로는 "해석된 이벤트"만 전송.

| 제스처 | 이벤트 | 단계 | 구현 상태 |
|--------|--------|------|-----------|
| 1손가락 드래그 | MOVE(dx, dy) | Phase 1 | ✅ 완료 |
| 1손가락 탭 | CLICK(left) | Phase 1 | ✅ 완료 |
| 1손가락 더블탭 | DOUBLE_CLICK | Phase 2 | ⬜ 미구현 |
| 2손가락 탭 | CLICK(right) | Phase 2 | ⬜ 미구현 |
| 2손가락 상하좌우 드래그 | SCROLL(dx, dy) | Phase 2 | ⬜ 미구현 |
| 탭홀드 + 드래그 | DRAG_START → MOVE → DRAG_END | Phase 3 | ⬜ 미구현 |
| 3손가락 스와이프 | 가상 데스크톱 전환 등 | Phase 4 | ⬜ 미구현 |

### 엣지 케이스 (구현 시 주의)
- 드래그 도중 손가락 개수 변화(1→2) → 현재 제스처 취소 후 새 제스처로 재시작
- 화면 밖으로 나간 손가락 → pointerInfo 변화 감지 후 DRAG_END 전송

### 감도 상수 (`GestureConfig.kt`)
```kotlin
MOVE_SENSITIVITY       = 1.5f   // 이동 배율
TAP_MAX_DISTANCE_PX    = 20f    // 탭 판정 최대 이동 거리
TAP_MAX_DURATION_MS    = 200L   // 탭 판정 최대 지속 시간
MOVE_MIN_DISTANCE_PX   = 5f     // 커서 이동 최소 거리 (떨림 억제)
DEFAULT_PORT           = 9000
```

---

## 6. 개발 로드맵

### ✅ Phase 1 — MVP (완료)
- [x] Clean Architecture 뼈대 (domain / data / presentation)
- [x] Hilt DI 설정
- [x] TCP 단일 채널 연결
- [x] 1손가락 이동(MOVE) + 1손가락 탭(CLICK)
- [x] Python 서버: TCP 수신 + SendInput 커서/클릭 제어
- [x] Android 연결 UI (IP 입력 → 연결 중 → 트랙패드 서페이스)

### ⬜ Phase 2 — 하이브리드 통신 + 추가 제스처
- [ ] MOVE를 UDP(9001)로 분리, 세션 토큰 기반 매칭
- [ ] TCP heartbeat (주기: 5초, 미응답 3회 → 연결 해제)
- [ ] 2손가락 탭 → 우클릭
- [ ] 2손가락 드래그 → 스크롤
- [ ] Python 서버에 UDP 소켓 추가 (TCP 세션과 매핑)
- [ ] Android: `PointerInfo` 기반 멀티터치 제스처 감지

**Phase 2 구현 시 핵심 파일:**
- `data/network/TcpClient.kt` — heartbeat 추가
- `data/network/UdpClient.kt` — 신규 생성
- `data/repository/TrackpadRepositoryImpl.kt` — UDP 채널 분기
- `presentation/trackpad/TrackpadScreen.kt` — 멀티터치 제스처 감지
- `pc_server/server.py` — UDP 소켓 + 세션 매핑 추가

### ⬜ Phase 3 — 제스처 확장
- [ ] 1손가락 더블탭 → DOUBLE_CLICK (타이머 기반 판정)
- [ ] 탭홀드(200ms↑) + 드래그 → DRAG_START / DRAG_END
  - DRAG 중 이동은 여전히 UDP MOVE 사용
- [ ] 감도 설정 화면 (Android Settings Screen)
- [ ] `GestureConfig`를 DataStore로 영속화

### ⬜ Phase 4 — 완성도
- [ ] PC 트레이 아이콘 (`pystray`) — 연결 상태 표시 + 종료
- [ ] UDP 브로드캐스트 자동 서버 탐색 (수동 IP 입력은 fallback 유지)
- [ ] 재연결 로직 (연결 끊김 감지 → 자동 재시도)
- [ ] 예외 처리 강화 (네트워크 오류, 권한 오류 등)
- [ ] PyInstaller로 단일 exe 패키징

### ⬜ Phase 5 — 선택 확장
- [ ] PIN 코드 인증 (TCP 핸드셰이크 단계에 추가)
- [ ] 3손가락 스와이프 → 가상 데스크톱 전환
- [ ] 다중 클라이언트 지원 정책 결정

---

## 7. 세션 흐름 (Phase 2+ 설계)

```
Android                              PC Server
   |                                     |
   |-- UDP broadcast (탐색) ---------->  |
   |<-- UDP response (IP:port) --------  |
   |                                     |
   |-- TCP connect ------------------>   |
   |<-- {"session":"abc123"} ---------   |
   |                                     |
   |-- TCP: CLICK/SCROLL/HEARTBEAT -->   |
   |-- UDP: {session, MOVE, dx, dy} -->  |
   |                                     |
   |-- TCP: HEARTBEAT (5s) ---------->   |
   |<-- TCP: HEARTBEAT_ACK -----------   |
```

---

## 8. 실행 방법

### PC 서버
```bash
cd pc_server
python server.py
# → TCP 9000 포트에서 대기
```

### Android 앱
1. Android Studio에서 `phone_pad_app/` 열기
2. 빌드 후 기기에 설치
3. 앱 실행 → PC IP 입력 → 연결

---

## 9. 코딩 컨벤션

- 새 이벤트 타입 추가 시: `TrackpadEvent.kt` sealed class 확장 →
  `TrackpadRepositoryImpl.kt`의 `when` 직렬화 → `input_controller.py`의 `handle_event`
- 제스처 판정 임계값은 반드시 `GestureConfig.kt` 상수로 분리
- ViewModel에서 직접 네트워크 호출 금지 — UseCase 경유
- 새 화면 추가 시 `presentation/<feature>/` 하위 패키지로 분리
- Python 서버: 이벤트 타입별 처리는 `InputController.handle_event`에 집중
- **새 기능/버그 수정 시 테스트 코드도 함께 작성**
  - Android: `usecase`/`repository` 등 도메인 로직은 JUnit + MockK 단위 테스트, 제스처 판정 로직(`GestureConfig` 기준값)은 별도 테스트로 검증
  - Python 서버: `input_controller.py`의 `handle_event` 등 이벤트 처리 로직은 `unittest`/`pytest`로 단위 테스트 작성
  - 테스트 없는 PR/커밋은 지양 — 최소한 핵심 로직(제스처 판정, 이벤트 직렬화/처리)은 커버

---

## 10. 미결 사항

| 항목 | 현황 |
|------|------|
| Android DI | Hilt 2.48 사용 중 (확정) |
| 바이너리 프로토콜 전환 | Phase 2 성능 테스트 후 결정 |
| PC 서버 배포 | PyInstaller — Phase 4에서 |
| PIN 인증 | Phase 5 선택 사항 |
| 다중 기기 연결 | 정책 미정 |
