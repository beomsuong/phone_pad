---
name: phone-pad-orchestrator
description: "Phone Pad 프로젝트(Android 트랙패드 앱 + Windows Python 서버)의 기능 구현을 android-dev/server-dev/protocol-qa 에이전트 팀으로 조율. 'Phone Pad에 {기능} 추가', 'Phase 2/3/4 구현해줘', '스크롤/우클릭/더블탭/드래그/heartbeat 구현', '트랙패드 제스처 추가', 'MOVE를 UDP로 분리' 등 요청 시 트리거. 후속 작업: 이전 구현 수정/보완, 특정 이벤트 타입만 다시 구현, 경계면 버그 수정, 테스트 추가 요청 시에도 반드시 이 스킬을 사용."
---

# Phone Pad Orchestrator

Android 앱과 Windows 서버를 동시에 건드리는 기능(새 제스처, 새 이벤트 타입, 통신 채널 변경 등)을 android-dev, server-dev, protocol-qa 3인 에이전트 팀으로 구현·검증한다.

## 실행 모드: 에이전트 팀 (기본)

두 사이드가 하나의 JSON 프로토콜 계약을 공유하기 때문에, 구현 도중 실시간으로 스펙을 맞추고 QA가 즉시 경계면을 검증하는 것이 핵심이다. 서브 에이전트 모드로는 이 실시간 조율이 불가능하므로 팀 모드를 기본으로 한다.

## 에이전트 구성

| 팀원 | 에이전트 타입 | 역할 | 스킬 | 출력 |
|------|-------------|------|------|------|
| android-dev | 커스텀 (`.claude/agents/android-dev.md`) | Kotlin/Compose 구현 + JUnit/MockK 테스트 | `android-trackpad-dev` | `phone_pad_app/` 변경 + `_workspace/*_android-dev_summary.md` |
| server-dev | 커스텀 (`.claude/agents/server-dev.md`) | Python 서버 구현 + pytest 테스트 | `pc-server-dev` | `pc_server/` 변경 + `_workspace/*_server-dev_summary.md` |
| protocol-qa | 커스텀, `general-purpose` 타입 (`.claude/agents/protocol-qa.md`) | 경계면 정합성 검증 | `protocol-boundary-qa` | `_workspace/*_protocol-qa_report.md` |

모든 팀원 스폰 시 `model: "opus"`를 명시한다.

## 워크플로우

### Phase 0: 컨텍스트 확인 (후속 작업 지원)

1. 프로젝트 루트의 `_workspace/` 디렉토리 존재 여부 확인
2. 분기:
   - **미존재** → 초기 실행. Phase 1로 진행
   - **존재 + 사용자가 특정 이벤트/부분 수정 요청** → 부분 재실행. 해당 부분만 담당하는 에이전트(들)만 재호출하고, `_workspace/`의 관련 파일만 갱신
   - **존재 + 새 기능/새 Phase 요청** → 새 실행. 기존 `_workspace/`를 `_workspace_{YYYYMMDD_HHMMSS}/`로 이동한 뒤 Phase 1 진행
3. 부분 재실행 시, 재호출되는 에이전트의 프롬프트에 이전 `_summary.md`/`_report.md` 경로를 포함하여 기존 결과를 반영하도록 지시

### Phase 1: 준비

1. 사용자 요청에서 대상 기능/이벤트 타입 파악 (예: "2손가락 스크롤" → `SCROLL` 이벤트, AGENTS.md 섹션 6 로드맵 대조)
2. AGENTS.md 섹션 4(통신 프로토콜)·5(제스처 설계)·6(로드맵)을 읽고, 요청이 어느 Phase/어느 이벤트 타입에 해당하는지, 이미 정의된 스펙이 있는지 확인
3. `_workspace/00_input/request.md`에 요청 분석 결과 저장 (대상 이벤트 타입, 관련 파일, 채널(TCP/UDP), AGENTS.md 상 현재 상태)

### Phase 2: 팀 구성

```
TeamCreate(
  team_name: "phone-pad-team",
  members: [
    { name: "android-dev", agent_type: "android-dev", model: "opus", prompt: "<request.md 경로 + 담당 이벤트 타입>" },
    { name: "server-dev", agent_type: "server-dev", model: "opus", prompt: "<request.md 경로 + 담당 이벤트 타입>" },
    { name: "protocol-qa", agent_type: "protocol-qa", model: "opus", prompt: "<request.md 경로 + incremental QA 지시>" }
  ]
)
```

```
TaskCreate(tasks: [
  { title: "Android: {이벤트} 감지·전송 구현", assignee: "android-dev" },
  { title: "Android: {이벤트} 단위 테스트 작성", assignee: "android-dev", depends_on: ["Android: {이벤트} 감지·전송 구현"] },
  { title: "Server: {이벤트} 처리 구현", assignee: "server-dev" },
  { title: "Server: {이벤트} 단위 테스트 작성", assignee: "server-dev", depends_on: ["Server: {이벤트} 처리 구현"] },
  { title: "QA: {이벤트} 경계면 정합성 검증", assignee: "protocol-qa", depends_on: ["Android: {이벤트} 감지·전송 구현", "Server: {이벤트} 처리 구현"] }
])
```

> 소규모 팀(3명)이므로 팀원당 2~4개 작업이 적정. 기능이 여러 이벤트 타입을 포함하면 이벤트 타입별로 작업을 묶어 반복한다.

### Phase 3: 구현 (팀원 자체 조율)

**실행 방식:** 팀원들이 공유 작업 목록에서 작업을 claim하여 독립 수행하며 SendMessage로 조율한다.

**통신 규칙:**
- android-dev와 server-dev는 이벤트 JSON 스펙(필드명·타입)을 구현 착수 전에 SendMessage로 먼저 합의한다.
- 한쪽이 구현을 완료하면 즉시 protocol-qa에게 알린다 — protocol-qa는 양쪽이 모두 끝나길 기다리지 않고, 먼저 끝난 쪽의 스펙만으로도 문서(AGENTS.md) 대조는 시작할 수 있다.
- protocol-qa가 불일치를 발견하면 해당 에이전트(들)에게 파일:라인 단위로 구체적 수정을 요청하고, 수정 완료 알림을 받으면 재검증한다.

**리더 모니터링:** 팀원이 유휴 상태가 되면 알림을 받는다. 특정 팀원이 막히면 SendMessage로 상태 확인 후 지시하거나 작업을 재할당한다. `TaskGet`으로 전체 진행률을 확인한다.

### Phase 4: 최종 검증 및 통합

1. 모든 작업 완료 대기 (`TaskGet`)
2. `protocol-qa`의 최종 리포트(`_workspace/*_protocol-qa_report.md`) 확인
3. **실패 항목이 있으면**: 해당 에이전트에게 최대 2회까지 수정을 재요청한다 (생성-검증 패턴의 재시도 한도). 2회 수정 후에도 실패하면 사용자에게 보고하고 진행 여부를 확인한다.
4. android-dev/server-dev의 `_summary.md`를 Read하여 변경 파일 목록, 추가된 테스트를 취합
5. 최종 결과 요약 작성: 어떤 이벤트/기능이 추가됐는지, 변경 파일 목록, 테스트 실행 방법(Android는 Android Studio/gradle, 서버는 `cd pc_server && pytest`), 미해결 이슈

### Phase 5: 정리

1. AGENTS.md 갱신이 필요하면(로드맵 체크박스, 프로토콜 표) 반영 — 코드와 문서가 어긋난 채로 남기지 않는다
2. `CLAUDE.md`의 변경 이력 테이블에 이번 실행 내용 기록
3. 팀원들에게 종료 알림 후 `TeamDelete`
4. `_workspace/` 보존 (중간 산출물은 삭제하지 않음 — 사후 검증·감사 추적용)
5. 사용자에게 결과 요약 + 실행/테스트 방법 보고, 개선 피드백 요청

## 데이터 흐름

```
[리더] → TeamCreate → [android-dev] ←SendMessage(스펙 합의)→ [server-dev]
                            │                                    │
                            ↓ 완료 알림                          ↓ 완료 알림
                       android summary                    server summary
                            │                                    │
                            └──────────────→ [protocol-qa] ←─────┘
                                              (양쪽 코드 동시 읽기)
                                                     │
                                              QA 리포트 (통과/실패/미검증)
                                                     │
                                            [리더: 실패 시 재작업 지시]
                                                     ↓
                                              최종 결과 + AGENTS.md 갱신
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| android-dev 또는 server-dev 1명 실패/중지 | 리더가 감지 → SendMessage로 상태 확인 → 재시작. 재실패 시 나머지 팀원 결과만으로 보고서 작성, "일부 미구현" 명시 |
| protocol-qa가 반복적으로 같은 불일치 지적 (2회+) | 리더가 직접 개입해 해당 에이전트에게 명확한 지시 재전달, 그래도 안 되면 사용자에게 에스컬레이션 |
| android-dev/server-dev 스펙 합의 불일치(서로 다른 필드명 주장) | 리더가 AGENTS.md 섹션 4를 기준으로 판정, 필요 시 문서 자체를 갱신 |
| 빌드/테스트 실행 불가 환경(Android SDK 미설치 등) | 정적 검토로 대체하고 "빌드 미검증" 상태를 최종 보고서에 명시 |
| 타임아웃 | 현재까지 완료된 작업만으로 Phase 4 진행, 미완료 항목은 누락 명시 |

## 테스트 시나리오

### 정상 흐름
1. 사용자: "2손가락 우클릭(CLICK right) 구현해줘"
2. Phase 1: AGENTS.md 대조 → Phase 2 로드맵의 `CLICK(right)` 항목, 기존 CLICK 이벤트에 button 파라미터 이미 존재함을 확인
3. Phase 2: 3인 팀 구성 + 5개 작업 등록 (Android 구현/테스트, Server 구현/테스트, QA 검증)
4. Phase 3: android-dev가 2손가락 탭 감지 로직 추가 후 server-dev와 스펙 재확인(이미 존재하는 CLICK 이벤트라 서버 수정 불필요할 수 있음) → protocol-qa가 즉시 검증
5. Phase 4: 리포트 통과, 요약 작성
6. Phase 5: AGENTS.md 로드맵 체크박스 갱신, `_workspace/` 보존, 팀 정리
7. 예상 결과: 2손가락 탭 시 우클릭 동작 + 관련 테스트 추가 + 로드맵 갱신

### 에러 흐름
1. Phase 3에서 server-dev가 응답 없이 유휴 상태
2. 리더가 유휴 알림 수신 → SendMessage로 상태 확인 → 재시작 시도
3. 재시작 실패 → 리더가 직접 `pc_server/input_controller.py`를 확인해 필요한 최소 변경(없을 수도 있음, 기존 CLICK 이벤트 재사용 시)을 판단
4. protocol-qa는 android 쪽 스펙만으로 문서 대조 진행, 서버 쪽은 "미검증"으로 표기
5. 최종 보고서에 "server-dev 작업 미완료 — 수동 확인 필요" 명시
