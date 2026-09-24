# 서버 GUI 디자인 현대화 — 작업 요약

## 진행 경위

server-dev 에이전트가 대부분 완료(`gui.py`/`fake_tk.py`/`test_gui_adapter.py` 리스타일링, 실제 Tk로 5장 스크린샷 캡처)한 직후 **네트워크 오류(API 서버 연결 불가)로 중단**되어 요약 파일을 쓰지 못했다. 리더가 결과를 검증하고 남은 실패 1건을 직접 고쳤다.

## 변경 파일 (에이전트)
- `pc_server/gui.py` — `tk.*` → `ttk.*` 전환, `ttk.Style().theme_use("vista")`(폴백 있음), PIN 전용 큰 등폭 스타일(`Pin.TLabel`/`Consolas`), 상태 색 점(`Dot.TLabel`, 회색/초록 — 트레이와 동일 의미), 강조 버튼(`Accent.TButton` 있으면 사용, 없으면 굵은 글씨 스타일로 폴백, `resolve_primary_style()`), 창/작업표시줄 아이콘(`make_dot_image()` — **Pillow 없이 순수 tkinter `PhotoImage`로 원을 그림**, 새 의존성 0).
- `pc_server/tests/fake_tk.py` — `ttk` 가짜 모듈 확장(Style/Frame/Label/Button).
- `pc_server/tests/test_gui_adapter.py` — 실제 Tk로 스타일/색/아이콘을 검증하는 테스트 추가.

## 리더가 발견·수정한 것 (에이전트가 못 끝낸 부분)

`python -m pytest`가 `1 failed, 715 passed`였다. 원인 2가지, 둘 다 `tests/test_gui_adapter.py`의 `test_real_tk_paints_the_status_dot_and_window_icon` 안에 있었다:

1. **PhotoImage 수명 순서** — 테스트가 `image = h.controller._icons[...]`로 로컬 변수에 참조를 하나 더 만들어 뒀는데, `finally: destroy()`가 끝난 뒤 테스트 함수가 반환되며 그 로컬 변수가 참조 카운트 0이 되어 `PhotoImage.__del__`이 **이미 파괴된 Tcl 인터프리터**를 호출 → `RuntimeError: main thread is not in main loop`(pytest가 실패로 잡음). `destroy()` 자체는 `self._icons`를 `root.destroy()` **전에** 비우고 있어 순서가 맞았다 — 문제는 테스트가 별도로 쥐고 있던 참조였다. `del image`로 해당 참조를 destroy() 이전에 없애 해결.
2. **Tcl 색 객체 비교** — 이 환경의 Tcl/Tk 버전은 `ttk.Label.cget("foreground")`가 평범한 `str`이 아니라 색 전용 래퍼 객체를 돌려준다(`repr`은 `'#808080'`으로 같아 보이지만 `==`이 str과 바로 성립하지 않음). `str(...)`로 감싸 비교하도록 수정(2곳).

수정 후 `python -m pytest`를 **3회 반복** 실행해 매번 `716 passed, 1 skipped`로 안정적임을 확인(테스트 개수가 1개 늘어난 건 순수 카운트 재확인일 뿐, 신규 테스트를 추가하지는 않음 — 원래 있던 실패 1건이 통과로 바뀐 것).

## 실측 확인 (스크린샷)

에이전트가 실제 창을 캡처한 스크린샷 5장(`01_idle.png` ~ `05_error_and_auth_off.png`, 스크래치패드)을 리더가 직접 봤다:
- **대기 중** 상태: 회색 점 + "Phone Pad - 대기 중", PIN이 큰 굵은 등폭 글씨(`760537`)로 또렷하게 보임, 접속 주소·정지/종료 버튼, 하단에 트레이 관련 안내 문구.
- **인증 꺼짐** 상태: PIN 자리에 "PIN: (사용 안 함)"이 작고 흐린 스타일(`STYLE_PIN_MUTED`)로 나와 큰 숫자 스타일과 명확히 구분됨.
- **시작 실패** 상태: 빨간 글씨로 `[WinError 10048] 각 소켓 주소에 대해 하나의 사용만이 허용됩니다` 오류 메시지가 창 안에 그대로 표시됨(크래시 없음).
- 전반적으로 클래식 회색 베벨 버튼 대신 Windows 네이티브(vista 테마) 톤으로 확실히 "덜 레트로"해 보임.
- 다만 "연결됨" 상태를 보여주려던 `02_connected.png`은 본문 라벨/점이 대기 중 그대로였다 — 데모 스크립트가 실제 연결 카운트를 갱신하지 않고 캡처한 것으로 보인다(자동 테스트 `test_real_tk_paints_the_status_dot_and_window_icon`가 `refresh()` 이후 점 색이 실제로 바뀜을 별도로 검증해 통과하므로, 기능 자체의 결함은 아니라고 판단).

## 스펙 준수
- 새 pip 의존성 없음(표준 라이브러리 `tkinter`/`tkinter.ttk`만) — 확인됨(아이콘까지 Pillow 없이 구현).
- 동작(버튼 콜백, `_vars` 키, X→트레이 축소, 갱신 주기) 무변경 — 기존 `test_gui_adapter.py`/`test_gui_state.py`/`test_server_gui.py` 테스트가 전부 무수정으로 통과.

## 미해결/한계
- `02_connected.png` 데모가 실제 연결 상태를 반영하지 못한 것으로 보임(위 설명) — 기능은 자동 테스트로 검증됐으나, 실제 폰 연결로 "연결됨" 화면을 눈으로 한 번 더 확인하면 좋음.
- PyInstaller 재빌드로 새 스타일이 exe에서도 그대로 보이는지는 미확인(범위 밖).
- 리더가 발견한 2가지 수정 모두 **이 머신의 Tcl/Tk 버전에 특화된 동작**(PhotoImage GC 타이밍, 색 객체 래핑)이라, 다른 Tcl/Tk 버전에서는 애초에 문제가 없었을 수도 있다 — 그래도 두 수정 다 더 안전한 방향이라 되돌릴 이유는 없다.

## 리더가 AGENTS.md에 반영할 내용
- 섹션 9(컨벤션)에 새 항목 2개: "tkinter 실제 위젯 테스트에서 `PhotoImage` 등 Tcl 객체를 지역 변수에 별도로 쥐고 있으면 `destroy()` 이후 GC 시점에 `__del__`이 죽은 인터프리터를 불러 `RuntimeError`가 난다 — 테스트가 끝나기 전에 `del`로 참조를 놓을 것", "Tcl/Tk 버전에 따라 `cget()`이 색상 등 일부 옵션값을 평범한 str이 아닌 래퍼 객체로 돌려줄 수 있다 — 비교 시 `str()`로 감쌀 것".
- 섹션 8의 "서버 창(기본 모드, ✅ 완료)" 문단에 "ttk + vista 테마로 리스타일링, PIN 강조 표시, 상태 색 점" 한 줄 추가 정도로 충분(새 모드가 아니라 기존 기본 모드의 겉모습만 바뀐 것).
