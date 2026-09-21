# server-dev 작업 요약 — DESKTOP_SWITCH (가상 데스크톱 전환)

## 변경 파일

| 파일 | 변경 |
|------|------|
| `pc_server/input_controller.py` | 키보드 주입(`KEYBDINPUT`) 추가, `handle_event`에 `DESKTOP_SWITCH` 분기, `_desktop_switch()` / `_release_desktop_switch_keys()` / `_key_input()` 신규 (+79줄) |
| `pc_server/tests/test_desktop_switch.py` | **신규** — 39개 테스트 |

`server.py`는 **수정하지 않았다**(병렬 discovery 세션과 충돌 방지). TCP 경로는 이미
`handle_client`가 dict JSON을 화이트리스트 없이 `controller.handle_event(event)`로
넘기고 예외를 이벤트 단위로 잡으므로(`server.py:160-175`) 새 type이 그대로 통과한다 —
`handle_client`를 실제로 구동하는 end-to-end 테스트 7건으로 확인했다.

## 처리하는 이벤트

```jsonc
// TCP 9000, newline-delimited, session 필드 없음
{"type":"DESKTOP_SWITCH","direction":"left"}   // -> Ctrl+Win+Left
{"type":"DESKTOP_SWITCH","direction":"right"}  // -> Ctrl+Win+Right
```

- `direction`은 **"전환 결과의 방향"**. 서버는 손가락 방향 → 와이어 방향 매핑을 모르고,
  받은 값을 그대로 키로 바꾼다(매핑은 Android 한 곳에만 — 두 사이드가 같이 뒤집는 사고 방지).
  이 계약을 `test_handle_event_passes_direction_verbatim_without_translating`으로 고정했다.
- **`"left"`/`"right"` 소문자 정확 일치만 허용.** 그 외(누락·`None`·`"LEFT"`·`" left"`·숫자·
  `True`·리스트·딕트·임의 객체 18종)는 **아무 키도 보내지 않고** 조용히 무시, 예외 전파 없음,
  실패 카운터도 증가하지 않음. 잘못된 줄 뒤에 오는 정상 이벤트가 그대로 처리되는 것까지 확인.

## 구현 핵심

1. **원자적 6-INPUT, `SendInput` 1회**: `Ctrl↓ → Win↓ → Arrow↓ → Arrow↑ → Win↑ → Ctrl↑`.
   나눠 보내면 그 사이 UDP MOVE 등이 끼어들어 수정 키가 눌린 상태로 다른 동작이 난다.
   화살표에만 `KEYEVENTF_EXTENDEDKEY`, 키 업에 `KEYEVENTF_KEYUP`.
2. **`ctypes.sizeof(INPUT)` 불변 = 40** (x64). `KEYBDINPUT`(24) < `MOUSEINPUT`(32)이라
   union 크기가 안 변한다. 변경 전/후 실측으로 40 동일 확인 + 테스트 2건으로 고정.
   이 값이 틀리면 **마우스 포함 모든 주입이 통째로 실패**한다.
3. **수정 키 고착 방지**: `injected != 6`이면 키 업 3개(화살표→Win→Ctrl, 누른 역순)를
   `kind="DESKTOP_SWITCH_CLEANUP"`으로 best-effort 1회만 재전송. 재귀/재시도 없음,
   정리 호출의 예외는 삼킨다. **성공 경로에서는 정리 호출 없음**(`call_count == 1` 단언).
4. `SendInput` 직접 호출 없음 — 전부 기존 `_send_input(count, inputs, kind)` 창구 경유
   (AGENTS.md 섹션 9). 실패 로그는 기존 창구의 ASCII 문구를 그대로 쓴다(cp949 안전, 테스트로 고정).
5. **드래그 상태 무간섭**: 드래그 활성 중 `DESKTOP_SWITCH`를 받아도 거부하지 않고
   `_drag_active`를 건드리지 않는다. 연결 종료 시 강제 해제 안전장치도 그대로 동작(회귀 테스트).

## 테스트 결과 (실제 실행)

```
기준선:  246 passed, 1 skipped
변경 후: 285 passed, 1 skipped   (신규 39, 회귀 0)
```
`cd C:\Github\phone_pad\pc_server && python -m pytest`

**변이 검사(mutation test)로 테스트가 실제로 버그를 잡는지 확인** — 8종 변이 전부 검출:

| 주입한 버그 | 실패한 테스트 수 |
|---|---|
| 실패 시 정리 호출 제거 | 9 |
| 화살표에서 EXTENDEDKEY 누락 | 11 |
| left/right 매핑 뒤바꿈 | 14 |
| `direction.lower()`로 대소문자 허용 | 3 |
| 마지막 Ctrl↑ 누락 | 12 |
| union 크기 변경(sizeof 40→다른 값) | 2 |
| 6개를 SendInput 2회로 분할 | 23 |
| 정리 시퀀스를 키 다운으로 | 4 |

변이 후 원본 복원 + 전체 스위트 재실행으로 285 passed 재확인.

## 실측한 것 / 안 한 것

- **실제 데스크톱 전환은 실행하지 않았다.** 모든 테스트는 `tests/send_input_stub.py`의
  `patch_send_input()`으로 모킹된다.
- **단 하나 실측**: 모킹으로는 절대 알 수 없는 "KEYBDINPUT을 union에 넣은 뒤에도 OS가
  구조체를 읽어 주입에 성공하는가"를, request.md 서버 스펙 9번이 허용한 **무해한 키 업 단독**
  호출로 확인했다(pytest 밖 일회성 스크립트, 저장소에 남기지 않음).
  - `VK_LCONTROL` **키 업 하나만** 전송(키 다운 없음, Win·화살표 없음) → `injected 1 of 1`.
    눌린 적 없는 Ctrl을 놓는 것은 no-op이며 데스크톱 전환도 Win 고착도 일어나지 않는다.
  - 대조군으로 일부러 틀린 `cbSize`를 주면 `injected 0 of 1` → sizeof 테스트가 지키는
    실패 모드가 실재함을 확인.
  - **리더 주의**: 이 한 건은 리더 지시문의 "실제 키 주입 실행 금지"보다 request.md 9번
    ("실측이 필요하면 `KEYEVENTF_KEYUP` 단독 같은 무해한 호출로 반환값 계약만 확인")을 따랐다.
    지시문이 든 두 위험(데스크톱 전환, Win 키 잔류) 어느 쪽도 발생할 수 없는 호출이다.

## 미해결 이슈 / 한계

| 항목 | 내용 |
|---|---|
| 실제 전환 동작 미검증 | Ctrl+Win+Left/Right가 이 PC에서 정말 데스크톱을 넘기는지는 **미검증**(고의). 키 코드·플래그·순서·구조체 크기·주입 성공까지는 고정했으나, "Windows가 이 조합을 데스크톱 전환으로 해석하는가"는 사람이 한 번 손으로 확인해야 한다. 가상 데스크톱이 1개뿐이면 아무 일도 안 일어나는 것이 정상 |
| 정리 호출도 실패 카운터에 집계 | 실패 1회당 `input_failures`가 **2** 증가한다(본 시퀀스 + 정리). `_send_input` 창구를 지나는 이상 불가피하며, 카운터는 진단용 누적값이라 그대로 뒀다(`test_injection_failure_is_counted`에 명시) |
| 정리 자체가 실패하면 끝 | 입력 데스크톱이 계속 막혀 있으면 키 업도 안 들어간다. 재시도하면 무한 루프가 되므로 1회로 제한. 다만 입력이 막힌 상황이면 애초에 Ctrl/Win도 안 눌렸을 가능성이 높다 |
| UIPI 감지 불가(기존 한계 그대로) | 관리자 권한 창에 주입이 차단되면 반환값도 `GetLastError`도 실패를 알리지 않는다 → 정리 로직이 돌지 않는다. `SendInput` 전반의 기존 한계(섹션 10) |
| `wScan` 미사용 | 가상 키 코드 방식(`wVk`)만 쓴다. 일부 게임/안티치트가 스캔 코드 없는 입력을 무시할 수 있으나 데스크톱 전환 용도에서는 무관 |
| 다중 세션 | 두 기기가 동시에 연결되면 양쪽 다 데스크톱을 전환할 수 있다(기존 "다중 기기 연결" 항목과 같은 뿌리, 상태가 없어 드래그보다는 덜 위험) |

## 리더가 AGENTS.md에 반영할 내용

**섹션 4 (통신 프로토콜)** — TCP 이벤트 목록에 추가:

```jsonc
// 가상 데스크톱 전환 - 3손가락 수평 스와이프. session 필드 없음, TCP.
// direction은 "전환 결과의 방향"(손가락 방향이 아님). 손가락→방향 매핑은
// Android(MultiTouchGestureTracker) 한 곳에만 있고 서버는 받은 값을 그대로
// 키 조합으로 바꾼다 - 두 사이드가 같이 뒤집으면 원위치된다(스크롤 방향 규약과 동일 원칙).
// 서버는 Ctrl+Win+Left/Right 6개 INPUT을 SendInput 1회로 원자적으로 보내고,
// 부분 주입이면 Ctrl/Win/화살표 키 업 3개를 best-effort로 한 번 더 보낸다(수정 키 고착 방지).
// "left"/"right" 소문자 정확 일치만 허용 - 그 외 값은 아무 키도 보내지 않고 조용히 무시.
{"type":"DESKTOP_SWITCH","direction":"left"}
{"type":"DESKTOP_SWITCH","direction":"right"}
```

**섹션 9 (코딩 컨벤션)** — 한 줄 추가 제안:

> `ctypes.sizeof(INPUT)`은 `SendInput`의 cbSize 인자이므로 **절대 변해서는 안 된다**.
> `_INPUTunion`에 새 구조체를 추가할 때는 그 구조체가 `MOUSEINPUT`보다 작은지 확인하고,
> 크기를 고정하는 테스트를 함께 둔다. 이 값이 틀리면 키보드뿐 아니라 마우스 주입까지
> 전부 실패하며(실측: 틀린 cbSize → `injected 0 of 1`) 예외는 나지 않아 조용히 죽는다.

**섹션 10 (미결 사항)** — 새 행:

> | 데스크톱 전환 실기기 미검증 | 키 코드·플래그·순서·`sizeof(INPUT)`·주입 성공은 테스트와 실측으로 고정했으나, Ctrl+Win+Left/Right가 실제로 데스크톱을 넘기는지는 사람이 한 번 확인해야 한다(테스트 중 실제 전환은 고의로 실행하지 않음 — 검증 중 데스크톱이 바뀌거나 부분 주입 시 Win 키가 눌린 채 남는 위험). 주입이 부분 성공한 뒤 정리 키 업마저 실패하는 경우는 코드로 더 막을 수 없다 |

**섹션 6 (로드맵)** — Phase 5 "3손가락 제스처" 중 좌/우 데스크톱 전환 완료.
수직 스와이프(작업 보기)·4손가락은 범위 밖으로 남음.
