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
}
