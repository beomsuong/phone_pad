---
name: android-trackpad-dev
description: "Phone Pad Android 앱(phone_pad_app/)에서 새 트랙패드 이벤트 타입 추가, 제스처 감지 로직 구현/수정, TCP/UDP 통신 계층 작업, JUnit+MockK 테스트 작성 시 반드시 사용. 'MOVE/CLICK/SCROLL/DRAG 이벤트 추가', '제스처 인식 구현', 'GestureConfig 감도 조정', 'TrackpadScreen 수정', 'Hilt DI 추가' 요청 시 트리거."
---

# Android Trackpad Dev

Phone Pad Android 앱은 Clean Architecture(domain/data/presentation) + Hilt DI + Compose로 구성되어 있다. 이 스킬은 새 이벤트를 추가하거나 제스처 감지 로직을 바꿀 때 거쳐야 하는 정확한 순서와, 이 프로젝트 특유의 제약을 담는다.

## 왜 순서가 중요한가

`TrackpadEvent` sealed class → `TrackpadRepositoryImpl`의 직렬화 → PC 서버의 `handle_event`는 하나의 계약으로 묶여 있다. 이 중 하나만 바꾸고 나머지를 건드리지 않으면, 컴파일은 되지만 런타임에 서버가 이벤트를 무시하거나 잘못 해석하는 조용한 버그가 생긴다. 그래서 이벤트를 추가할 때는 항상 "정의 → 직렬화 → 감지 로직" 순서로 진행하고, 서버 쪽 수정은 `server-dev` 에이전트와 스펙을 먼저 맞춘 뒤 진행한다.

## 새 이벤트 타입 추가 체크리스트

1. `domain/model/TrackpadEvent.kt`의 sealed class에 새 `data class` 추가
2. `data/repository/TrackpadRepositoryImpl.kt`의 `sendEvent`의 `when(event)` 분기에 JSON 직렬화 추가 — AGENTS.md 섹션 4에 정의된 필드명을 그대로 사용 (임의 변경 금지)
3. 이벤트를 발생시키는 제스처 감지 로직을 `presentation/trackpad/TrackpadScreen.kt`에 추가 (아래 "제스처 감지 패턴" 참조)
4. 임계값(거리, 시간 등)이 필요하면 `presentation/util/GestureConfig.kt`에 상수로 추가 — 매직 넘버 하드코딩 금지
5. `server-dev`에게 SendMessage로 확정된 JSON 스펙 공유
6. `app/src/test/`에 유닛 테스트 추가 (아래 "테스트 작성 패턴" 참조)

## 제스처 감지 패턴

이 프로젝트는 Compose 1.4+의 `awaitEachGesture`를 사용한다 (구식 `detectDragGestures`/`detectTapGestures` 조합 대신). 이유: 탭과 드래그를 하나의 포인터 시퀀스 안에서 함께 판정해야 하고(이동 거리·시간 기준으로 탭/드래그를 사후 분기), 향후 멀티터치(2/3손가락) 확장 시 포인터 개수 변화를 세밀하게 감지해야 하기 때문이다.

핵심 판정 기준 (`GestureConfig` 참조):
- 이동 거리가 `MOVE_MIN_DISTANCE_PX` 미만이면 MOVE 이벤트를 보내지 않는다 (떨림 억제)
- 총 이동 거리가 `TAP_MAX_DISTANCE_PX` 이하이고 지속 시간이 `TAP_MAX_DURATION_MS` 이하이면 탭(CLICK)으로 판정
- 멀티터치 확장 시 손가락 개수 변화(예: 1→2)를 감지하면 진행 중이던 제스처를 취소하고 새 제스처로 재시작한다 (AGENTS.md 섹션 5 엣지 케이스)

## 테스트 작성 패턴

- `usecase`/`repository` 로직: JUnit + MockK로 순수 로직 검증. 예: `SendEventUseCase`가 올바른 `TrackpadEvent`를 `TrackpadRepository`에 전달하는지, `TrackpadRepositoryImpl`이 각 이벤트 타입을 올바른 JSON 문자열로 직렬화하는지.
- 제스처 판정 로직: `GestureConfig`의 상수를 기준값으로 사용하는 순수 함수로 분리 가능하면 분리해서 테스트한다 (Compose UI 테스트보다 유닛 테스트가 빠르고 안정적).
- 네트워크 계층(`TcpClient`/`UdpClient`)은 MockK로 소켓을 모킹하여 연결 실패/재시도 등 예외 경로를 검증한다.
- 테스트 파일 위치: `app/src/test/java/com/example/phone_pad_app/{domain 또는 data}/...`

## 흔한 실수

- ViewModel에서 `TcpClient`나 `TrackpadRepository`를 직접 주입받아 호출하는 것 — 반드시 UseCase를 거친다.
- 감도/임계값을 `TrackpadScreen.kt`에 직접 숫자로 적는 것 — `GestureConfig` 상수를 추가하고 참조한다.
- MOVE 이벤트를 TCP로 보내는 것 (Phase 2 이후 원칙 위반) — Phase 2 이후 작업에서는 반드시 UDP 클라이언트를 통해 보낸다.
