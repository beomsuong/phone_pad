package com.example.phone_pad_app.domain.model

sealed class TrackpadEvent {
    data class Move(val dx: Float, val dy: Float) : TrackpadEvent()
    data class Click(val button: String = "left") : TrackpadEvent()

    /**
     * 1손가락 더블탭. `{"type":"DOUBLE_CLICK","button":"left"}` (TCP, AGENTS.md 섹션 4)
     *
     * [Click]을 두 번 보내는 대신 별도 이벤트로 두는 이유: 사람이 두 번 탭하면 좌표가 몇 px씩
     * 어긋나고, 그 어긋남이 MOVE로 새어나가면 PC 커서가 미세하게 움직인다. Windows의 네이티브
     * 더블클릭 판정은 커서가 거의 움직이지 않은 좁은 사각형(기본 4px) 안에서 두 클릭이 일어나야
     * 성립하므로, "CLICK을 빠르게 두 번 보내 OS가 알아서 묶게 하자"는 접근은 실기기에서
     * 신뢰할 수 없다. 대신 Android가 더블탭을 확정해 이 이벤트 하나만 보내고, 서버가 커서를
     * 전혀 움직이지 않은 채 클릭 두 번을 원자적으로(SendInput 1회) 실행한다.
     *
     * [button]은 [Click]과 같은 모양을 유지하기 위해 두지만, 현재 범위(1손가락 더블탭)에서는
     * 항상 `"left"`다.
     */
    data class DoubleClick(val button: String = "left") : TrackpadEvent()

    /**
     * 2손가락 드래그로 만들어진 휠 스크롤. `{"type":"SCROLL","dx":0,"dy":-3}` (TCP, AGENTS.md 섹션 4)
     *
     * [dx]/[dy]는 픽셀이 아니라 **휠 "노치" 개수(정수 스텝)** 다 — 픽셀→스텝 변환과 잔차 누적은
     * [com.example.phone_pad_app.presentation.trackpad.MultiTouchGestureTracker]가 이미 끝낸 뒤라서,
     * 이 모델부터 서버까지는 정수 스텝만 흐른다.
     *
     * 부호 규약: [dy] 양수 = 손가락이 아래로 이동, [dx] 양수 = 손가락이 오른쪽으로 이동.
     */
    data class Scroll(val dx: Int, val dy: Int) : TrackpadEvent()

    /**
     * 탭홀드로 승격된 드래그의 시작. `{"type":"DRAG_START"}` (TCP, AGENTS.md 섹션 4)
     *
     * 서버가 마우스 왼쪽 버튼을 **누른 채로 유지**하기 시작한다는 뜻이다. 필드가 하나도 없으므로
     * `data class`가 아니라 `object`다 — 같은 인스턴스를 재사용해도 의미가 달라지지 않는다.
     *
     * 드래그 중의 커서 이동은 새 이벤트가 아니라 기존 [Move](UDP)를 그대로 쓴다. "버튼 누름 +
     * 커서 이동 + 버튼 뗌"의 조합만으로 드래그 효과가 자연히 생기므로 서버의 이동 처리는
     * 손댈 필요가 없다.
     */
    object DragStart : TrackpadEvent()

    /**
     * 드래그의 종료. `{"type":"DRAG_END"}` (TCP, AGENTS.md 섹션 4)
     *
     * 손가락을 뗐을 때뿐 아니라 **손가락 개수가 바뀌거나 제스처가 취소된 경우에도** 반드시 보낸다.
     * 이 이벤트가 유실되면 PC의 왼쪽 버튼이 눌린 채로 남는 심각한 상태가 되기 때문이다
     * (서버도 TCP 연결이 끊길 때 강제로 버튼을 놓는 안전장치를 따로 가진다).
     */
    object DragEnd : TrackpadEvent()

    /**
     * 3손가락 수평 스와이프로 만들어진 가상 데스크톱 전환.
     * `{"type":"DESKTOP_SWITCH","direction":"left"}` (TCP, AGENTS.md 섹션 4)
     *
     * [direction]은 **손가락이 움직인 방향이 아니라 "전환 결과의 방향"** 이다
     * ([MultiTouchGestureTracker.DIRECTION_LEFT][com.example.phone_pad_app.presentation.trackpad.MultiTouchGestureTracker.DIRECTION_LEFT] /
     * [DIRECTION_RIGHT][com.example.phone_pad_app.presentation.trackpad.MultiTouchGestureTracker.DIRECTION_RIGHT]):
     * 서버는 받은 값을 `Ctrl+Win+Left` / `Ctrl+Win+Right`로 옮기기만 한다.
     *
     * 손가락 방향 → 와이어 방향 뒤집기는 **Android의 판정기 한 곳에서만** 한다
     * (`MultiTouchGestureTracker`). 양쪽이 각자 뒤집으면 원위치가 되므로, 스크롤 방향 규약
     * (AGENTS.md 섹션 10)과 같은 원칙으로 매핑 지점을 하나로 못 박는다.
     *
     * CLICK/DOUBLE_CLICK과 같은 등급의 저빈도 · 사용자 명시 이벤트라 전송 실패를 조용히
     * 버리지 않는다(MOVE/SCROLL과 다름).
     */
    data class DesktopSwitch(val direction: String) : TrackpadEvent()
}
