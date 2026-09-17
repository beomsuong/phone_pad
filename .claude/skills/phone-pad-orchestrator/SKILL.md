---
name: phone-pad-orchestrator
description: "Phone Pad 프로젝트(Android 트랙패드 앱 + Windows Python 서버)의 기능 구현을 조율. 'Phone Pad에 {기능} 추가', 'Phase 2/3/4 구현해줘', '스크롤/우클릭/더블탭/드래그/heartbeat 구현', '트랙패드 제스처 추가', 'MOVE를 UDP로 분리' 등 양쪽에 걸친 요청은 물론, 'Android 감도 조정', '제스처 판정 로직 개선', '서버 로깅 추가', 'SendInput 가속도 조정'처럼 한쪽만 건드리는 요청도 이 스킬이 판단하여 처리. 후속 작업: 이전 구현 수정/보완, 특정 이벤트 타입만 다시 구현, 경계면 버그 수정, 테스트 추가 요청 시에도 반드시 이 스킬을 사용."
---

# Phone Pad Orchestrator

Phone Pad 기능 작업을 요청 범위에 따라 가벼운 경로(단일 에이전트) 또는 무거운 경로(3인 팀)로 자동 분기하여 처리한다.

## 실행 모드: 하이브리드

| 요청 범위 | 모드 | 이유 |
|-----------|------|------|
| 단일 사이드 (Android만 또는 서버만, 이벤트 스펙에 영향 없음) | 서브 에이전트 1명 | 팀 조율 오버헤드가 불필요하고, protocol-qa가 검증할 경계면 자체가 없다 |
| 교차 경계면 (양쪽 모두, 또는 이벤트/프로토콜에 영향) | 에이전트 팀 3명 | 실시간 스펙 합의 + 즉시 경계면 검증(incremental QA)이 필요 |

**애매하면 팀 모드로 간다.** 판단이 틀려서 프로토콜 변경인데 QA를 건너뛰는 것이, 단순 작업에 팀 오버헤드를 쓰는 것보다 훨씬 비싸다 (AGENTS.md의 "애매하면 TCP" 원칙과 동일한 보수적 태도).

## 에이전트 구성

| 팀원 | 에이전트 타입 | 역할 | 스킬 | 출력 |
|------|-------------|------|------|------|
| android-dev | 커스텀 (`.claude/agents/android-dev.md`) | Kotlin/Compose 구현 + JUnit/MockK 테스트 | `android-trackpad-dev` | `phone_pad_app/` 변경 + `_workspace/*_android-dev_summary.md` |
| server-dev | 커스텀 (`.claude/agents/server-dev.md`) | Python 서버 구현 + pytest 테스트 | `pc-server-dev` | `pc_server/` 변경 + `_workspace/*_server-dev_summary.md` |
| protocol-qa | 커스텀, `general-purpose` 타입 (`.claude/agents/protocol-qa.md`) | 경계면 정합성 검증 (교차 경계면 경로에서만 참여) | `protocol-boundary-qa` | `_workspace/*_protocol-qa_report.md` |

모든 에이전트 호출 시 `model: "opus"`를 명시한다.

## 워크플로우

### Phase 0: 컨텍스트 확인 (후속 작업 지원)

1. 프로젝트 루트의 `_workspace/` 디렉토리 존재 여부 확인
2. 분기:
   - **미존재** → 초기 실행. Phase 1로 진행
   - **존재 + 사용자가 특정 이벤트/부분 수정 요청** → 부분 재실행. 해당 부분만 담당하는 에이전트(들)만 재호출
   - **존재 + 새 기능/새 Phase 요청** → 새 실행. 기존 `_workspace/`를 `_workspace_{YYYYMMDD_HHMMSS}/`로 이동한 뒤 Phase 1 진행
3. 부분 재실행 시, 재호출되는 에이전트의 프롬프트에 이전 `_summary.md`/`_report.md` 경로를 포함하여 기존 결과를 반영하도록 지시

### Phase 1: 준비 + 범위 판단

1. 사용자 요청에서 대상 기능/이벤트 타입 파악, AGENTS.md 섹션 4(통신 프로토콜)·5(제스처 설계)·6(로드맵) 대조
2. **범위 판단** — 아래 중 하나라도 해당하면 **교차 경계면**으로 분류한다:
   - 새 이벤트 `type` 추가/삭제
   - 기존 이벤트의 필드(dx, dy, button 등) 추가/변경/삭제
   - 통신 채널(TCP/UDP) 구조, 세션/인증 방식 변경
   - `AGENTS.md` 섹션 4(통신 프로토콜) 자체의 수정이 필요한 작업
   - 위 조건에 해당하는지 확신이 서지 않는 경우 (보수적으로 판단)

   그 외 — 이미 정의된 이벤트를 그대로 쓰면서 한쪽 코드 내부만 바뀌는 작업(예: `GestureConfig` 감도 값 조정, UI/애니메이션, 제스처 판정 로직 개선, `SendInput` 가속도 곡선, 로깅, 에러 핸들링 강화, 리팩터링)은 **단일 사이드**로 분류한다.
3. `_workspace/00_input/request.md`에 요청 분석 + 범위 판단 결과(단일 사이드 / 교차 경계면, 사유 1줄) 저장

### Phase 2A: 단일 사이드 경로 (서브 에이전트)

**실행 모드:** 서브 에이전트 1명

1. 대상 사이드에 맞는 에이전트를 `Agent` 도구로 직접 호출한다 (`TeamCreate` 사용하지 않음):
   ```
   Agent(
     subagent_type: "android-dev" 또는 "server-dev",
     model: "opus",
     prompt: "<request.md 요약 + 해당 에이전트의 SKILL(android-trackpad-dev 또는 pc-server-dev) 참조 지시 + 기존 이벤트 스펙을 변경하지 말 것을 명시>"
   )
   ```
2. 해당 에이전트가 구현 + 테스트 작성까지 완료한 결과(반환값 + `_workspace/*_summary.md`)를 수집한다
3. **QA는 생략한다** — 이벤트 스펙이 바뀌지 않았으므로 검증할 경계면이 없다. 단, 에이전트의 산출물을 검토하는 과정에서 의도치 않게 이벤트 필드/타입을 건드린 것이 발견되면 즉시 Phase 2B(교차 경계면 경로)로 전환한다
4. Phase 5로 진행

### Phase 2B: 교차 경계면 경로 (병렬 서브 에이전트 + 사후 QA)

**실행 모드:** 에이전트 팀 — 단, 이 저장소/환경에는 `TeamCreate`/`TaskCreate` 도구가 없다(2026-09-17 실행에서 확인). `TeamCreate`를 시도하지 말고 아래 방식으로 동일한 의도(스펙 불일치 방지 + 즉시 경계면 검증)를 구현한다:

1. **리더가 스펙을 먼저 확정한다.** android-dev/server-dev가 실시간으로 SendMessage 협상을 하는 대신, 리더가 `AGENTS.md`와 사용자 요청을 근거로 이벤트 JSON 필드명·타입·채널·(필요 시) 세션/핸드셰이크 형식을 정확히 정의해 `_workspace/00_input/request.md`에 적는다. 여기 적힌 스펙은 두 에이전트 모두에게 동일하게 전달되므로, 필드명이 갈라질 여지가 없다.
2. **android-dev와 server-dev를 한 메시지에서 병렬로 호출한다** (`Agent` 도구, `subagent_type`: 각각 "android-dev"/"server-dev", `model: "opus"`). 프롬프트에 request.md 경로, 구현 범위, "스펙을 임의로 바꾸지 말 것"을 명시한다. 이 두 호출은 서로 독립적이므로 병렬 실행이 안전하다.
3. 두 에이전트의 완료 알림(SubagentHandback)을 받으면, **protocol-qa를 호출**(`Agent` 도구, `subagent_type: "protocol-qa"`, `model: "opus"`)해 request.md + 양쪽 summary + 실제 코드를 "양쪽 동시 읽기"로 대조하게 한다. QA는 코드를 고치지 않고 리포트만 작성한다(`_workspace/*_protocol-qa_report.md`).
4. **실패 항목 처리:** QA 리포트의 실패 항목 중,
   - 수정 방법이 명확하고 규모가 작으면(수 줄 이내) 리더가 직접 `Edit`으로 고치고, 관련 테스트를 실행해 회귀가 없는지 확인한다(가능하면 QA가 제안한 회귀 테스트도 추가한다)
   - 규모가 크거나 해당 에이전트의 전문성이 필요하면 `SendMessage`로 해당 에이전트(예: `to: "android-dev"`, 완료된 에이전트는 이름으로 재개 가능)에게 파일:라인 단위로 재작업을 요청한다. 최대 2회까지 재요청, 그래도 실패하면 사용자에게 보고 후 진행 여부 확인
   - 문서(AGENTS.md) 불일치는 리더가 직접 갱신한다 (Phase 5에서 일괄 처리)
5. Phase 5로 진행

**참고:** `SendMessage`는 실제로 존재하며 완료된 서브 에이전트를 이름/agentId로 재개할 수 있다. 다만 이번 실행에서는 스펙을 사전 확정해 놓아 병렬 실행 중 실시간 협상이 필요 없었다 — 만약 스펙이 request.md만으로 명확히 정의되지 않는 애매한 작업이라면, 한쪽을 먼저 실행해 스펙을 확정한 뒤(예: android-dev가 실제 정의한 필드) 그 결과를 다른 쪽 프롬프트에 포함시켜 순차 실행하는 것을 고려한다.

### Phase 5: 정리 (공통)

1. android-dev/server-dev의 `_summary.md`(및 팀 경로의 경우 protocol-qa 리포트)를 취합해 결과 요약 작성: 어떤 기능이 추가됐는지, 변경 파일 목록, 테스트 실행 방법, 미해결 이슈
2. AGENTS.md 갱신이 필요하면(로드맵 체크박스, 프로토콜 표) 반영
3. `CLAUDE.md`의 변경 이력 테이블에 이번 실행 내용 기록 (실행 경로가 단일 사이드였는지 팀이었는지도 함께 기록)
4. `_workspace/` 보존 (중간 산출물은 삭제하지 않음)
5. 사용자에게 결과 요약 + 실행/테스트 방법 보고, 개선 피드백 요청

## 데이터 흐름

```
[리더] → Phase 1: 범위 판단
              │
    ┌─────────┴─────────┐
    ↓ 단일 사이드         ↓ 교차 경계면
[Agent(android-dev        [리더가 스펙 사전 확정: request.md]
 또는 server-dev)]         Agent(android-dev) ∥ Agent(server-dev)  (병렬 호출)
    │                            │                                │
    ↓ 반환값 + summary          ↓ 완료 알림                     ↓ 완료 알림
    │                       android summary                 server summary
    │                            └──────────→ [protocol-qa] ←────┘
    │                                          (양쪽 코드 동시 읽기)
    │                                                │
    │                                          QA 리포트
    └──────────────────┬─────────────────────────────┘
                        ↓
                  Phase 5: 정리 + 보고
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 단일 사이드 경로에서 에이전트가 이벤트 스펙을 건드림을 뒤늦게 발견 | 즉시 Phase 2B로 전환 — 이미 완료된 작업은 재사용하고, 반대편 에이전트 + protocol-qa를 추가로 투입 |
| android-dev 또는 server-dev 1명 실패/중지 | 리더가 감지 → SendMessage(팀 모드) 또는 재호출(단일 모드)로 재시작. 재실패 시 나머지 결과만으로 보고, "일부 미구현" 명시 |
| protocol-qa가 반복적으로 같은 불일치 지적 (2회+) | 리더가 직접 개입, 그래도 안 되면 사용자에게 에스컬레이션 |
| android-dev/server-dev 스펙 합의 불일치 | AGENTS.md 섹션 4를 기준으로 리더가 판정, 필요 시 문서 자체를 갱신 |
| 빌드/테스트 실행 불가 환경 | 정적 검토로 대체하고 "빌드 미검증" 상태를 최종 보고서에 명시 |
| 범위 판단이 틀려 단일 사이드로 시작했는데 실제로는 팀 모드가 필요했던 경우 | 손실 없음 — 이미 한 작업을 유지한 채 위 "즉시 Phase 2B 전환" 규칙 적용 |

## 테스트 시나리오

### 정상 흐름 — 단일 사이드
1. 사용자: "MOVE_SENSITIVITY를 1.5에서 2.0으로 조정하고, 관련 테스트도 추가해줘"
2. Phase 1: 기존 이벤트 스펙 변경 없음 → 단일 사이드(Android) 분류
3. Phase 2A: `android-dev` 서브 에이전트 1회 호출, `GestureConfig.kt` 수정 + 테스트 추가
4. Phase 5: 결과 요약 보고 (팀 생성/QA 없이 완료)

### 정상 흐름 — 교차 경계면
1. 사용자: "2손가락 우클릭(CLICK right) 구현해줘"
2. Phase 1: 기존 CLICK 이벤트에 button 파라미터가 있어 신규 필드는 아니지만, 제스처 감지(Android)와 처리(서버) 양쪽 동작 확인이 필요해 보수적으로 교차 경계면 분류
3. Phase 2B: 3인 팀 구성 → 구현 → protocol-qa 검증 통과
4. Phase 5: AGENTS.md 로드맵 체크박스 갱신, 결과 보고

### 에러 흐름
1. 단일 사이드 경로로 "서버 로깅 추가" 작업을 `server-dev` 서브 에이전트에게 위임
2. 작업 중 에이전트가 로깅을 위해 이벤트 딕셔너리 구조를 바꾸는 것을 리더가 결과 검토 중 발견
3. 즉시 Phase 2B로 전환: `android-dev`, `protocol-qa`를 추가 투입해 교차 검증
4. 최종 보고서에 "범위 판단 재조정: 단일 사이드 → 교차 경계면" 명시
