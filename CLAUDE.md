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
