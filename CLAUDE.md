## 하네스: Phone Pad (Android ↔ PC 서버)

**목표:** Android 트랙패드 앱과 Windows Python 서버를 건드리는 기능 작업을, 범위(단일 사이드/교차 경계면)에 맞는 비용으로 구현.

**트리거:** 제스처/이벤트 추가, 통신 프로토콜 변경, 감도·UI·서버 로직 조정 등 `phone_pad_app` 또는 `pc_server` 관련 구현 작업 요청 시 `phone-pad-orchestrator` 스킬을 사용하라 — 오케스트레이터가 내부적으로 단일 사이드(서브 에이전트 1명)와 교차 경계면(android-dev/server-dev/protocol-qa 3인 팀)을 판단해 분기한다. 코드 변경이 필요 없는 단순 질문은 직접 응답 가능.

프로젝트 상세 컨텍스트(구조, 프로토콜 스펙, 로드맵)는 `AGENTS.md` 참조.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-09-17 | 초기 구성 (android-dev/server-dev/protocol-qa 3인 팀) | 전체 | Android↔서버 경계면 불일치를 초기에 잡기 위해 하네스 도입 |
| 2026-09-17 | 하이브리드 실행 모드 도입 (단일 사이드는 서브 에이전트 1명, 교차 경계면만 3인 팀) | skills/phone-pad-orchestrator | 한쪽만 건드리는 작업에 매번 팀 오버헤드를 쓰는 게 낭비라는 피드백 |
| 2026-09-17 | Phase 2B에서 `TeamCreate`/`TaskCreate` 사용 제거, "리더가 스펙 사전 확정 → 병렬 Agent 호출 → 사후 QA" 방식으로 교체 | skills/phone-pad-orchestrator | 첫 실전 실행(MOVE UDP 분리)에서 이 환경에 해당 도구가 없음을 확인 |
| 2026-09-17 | MOVE 이벤트 UDP(9001) 분리 + TCP 세션 핸드셰이크 구현 (android-dev/server-dev 병렬 + protocol-qa 검증, F-2 재연결 토큰 누수 수정) | phone_pad_app, pc_server, AGENTS.md | AGENTS.md Phase 2 로드맵 항목 진행 |
| 2026-09-17 | TCP heartbeat 구현 (카운터 기반 5초×3회 판정, android-dev/server-dev 병렬 + protocol-qa 검증). QA 발견 F-3(겹친 connect() 경합)을 Mutex 직렬화로 수정, F-2(Move 실패가 Error 덮어씀)·F-4(비원자적 필드)·서버 F-1(정상 연결 오탐 로그)·F-5(TCP 경로 non-dict 크래시 방지) 수정 | phone_pad_app, pc_server, AGENTS.md | AGENTS.md Phase 2 로드맵 항목 진행, 지난 F-1(세션 회수를 앱이 모름) 해소 |
| 2026-09-18 | Phase 5(정리)에 "커밋은 파트별로 나눈다" 규칙 명문화 — `feat(android):`/`feat(server):`/`docs(harness):` 3개로 분리, 실제 변경 없는 파트는 생략. AGENTS.md 섹션 9에도 동일 컨벤션 추가 | skills/phone-pad-orchestrator, AGENTS.md | 사용자가 매번 "나눠서 커밋해줘"를 반복 요청하지 않도록 기본 동작으로 고정해달라는 피드백 |
| 2026-09-18 | PointerInfo 기반 멀티터치 제스처 감지 기반 구축 (Android 단일 사이드, `MultiTouchGestureTracker` 순수 Kotlin 판정기 도입). 우클릭/스크롤 연결은 후속 작업 | phone_pad_app, AGENTS.md | AGENTS.md Phase 2 로드맵 항목 진행. 2손가락 탭/드래그의 선행 작업 |
| 2026-09-18 | 2손가락 탭 → 우클릭 구현 (Android 단일 사이드, 서버 무변경 — Phase 1부터 `button != "left"`를 우클릭으로 처리 중이었음 확인). 손가락을 어긋나게 떼는 실기기 특성 보정(`MULTI_TOUCH_RELEASE_GRACE_MS`) 추가 | phone_pad_app, AGENTS.md | AGENTS.md Phase 2 로드맵 항목 진행 |
| 2026-09-18 | 2손가락 드래그 → 스크롤 구현 (android-dev/server-dev 병렬 + protocol-qa 검증). QA 발견 F-1(SCROLL 전송 실패가 heartbeat Error를 덮어씀), F-2(스크롤 후 손가락을 어긋나게 떼면 좌클릭 오발동), 서버 F-4(잘못된 필드값이 TCP 세션 전체를 끊음) 수정. AGENTS.md Phase 2 로드맵 완주(남은 건 더블탭) | phone_pad_app, pc_server, AGENTS.md | AGENTS.md Phase 2 로드맵 마지막 항목 |
| 2026-09-19 | 1손가락 더블탭 → DOUBLE_CLICK 구현 (android-dev/server-dev 병렬 + protocol-qa 검증). 지연 후 확정 방식(CLICK 두 개 대신 DOUBLE_CLICK 하나) 채택 — Windows 네이티브 더블클릭 판정 사각형이 좁아 두 CLICK을 따로 보내면 실패할 수 있어서. 작업 중 에이전트 재개(SendMessage) 시 리더가 agentId를 헷갈려 android-dev/server-dev에 지시가 뒤바뀐 사고 발생 — 두 에이전트 모두 자기 역할이 아님을 스스로 인지하고 원래 작업을 계속해 실질 피해 없음. QA 발견 F-1(드래그 시작 시 대기 클릭 미방출)·F-3(우클릭 시 대기 클릭 미방출로 컨텍스트 메뉴 오클릭 위험)·F-4(우클릭이 더블탭 감지기를 리셋 안 함) 수정 — "취소"가 아니라 "즉시 발사(flush)"로 처리해 클릭 유실 방지. F-2(정확히 300ms 경계 레이스)는 영향 미미해 보류. AGENTS.md Phase 3 진행 | phone_pad_app, pc_server, AGENTS.md | AGENTS.md Phase 3 로드맵 항목 진행 |
| 2026-09-19 | 탭홀드(200ms) + 드래그 → DRAG_START/DRAG_END 구현 (android-dev/server-dev 병렬 + protocol-qa 검증). 승격 판정은 `DragHoldDetector` 순수 클래스로 분리. 드래그 중 이동은 새 이벤트 없이 기존 UDP MOVE 재사용. **서버 안전장치**: TCP 연결이 어떤 이유로든 끊기면 드래그 활성 시 강제로 버튼을 놓음(실제 handle_client 구동으로 3개 경로 실측 확인, 블로커 없음). QA 발견 F-1(서버 로그 em dash가 cp949 콘솔에서 UnicodeEncodeError를 던져 안전장치 성공을 실패로 오보고)·F-2(DRAG_START/DRAG_END가 이벤트별 별도 코루틴+공용 IO 풀을 타서 전송 순서 역전 가능)를 수정 — 로그를 ASCII로, `TcpClient.send()`에 전용 단일 스레드 디스패처 도입. AGENTS.md Phase 3 로드맵 사실상 완주(남은 건 감도 설정 UI 2개) | phone_pad_app, pc_server, AGENTS.md | AGENTS.md Phase 3 로드맵 항목 진행 |
