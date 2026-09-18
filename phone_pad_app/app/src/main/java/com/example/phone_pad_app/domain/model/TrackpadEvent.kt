package com.example.phone_pad_app.domain.model

sealed class TrackpadEvent {
    data class Move(val dx: Float, val dy: Float) : TrackpadEvent()
    data class Click(val button: String = "left") : TrackpadEvent()

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
