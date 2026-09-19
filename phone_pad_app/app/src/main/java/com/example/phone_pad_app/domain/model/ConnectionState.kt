package com.example.phone_pad_app.domain.model

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val host: String) : ConnectionState()

    /**
     * 한 번 [Connected]였던 세션이 유실되어 자동 재연결을 진행 중인 상태.
     *
     * [Error]와 구분되는 이유: 사용자가 할 일이 없는 "복구 시도 중"이라 IP 입력 화면으로
     * 되돌릴 이유가 없고, 유실 직후 [Error]를 한 프레임이라도 거치면 화면이 깜빡인다.
     * 재시도 횟수가 소진되면 비로소 [Error]("Reconnect failed: ...")로 넘어간다.
     *
     * @param host 재연결 대상 — 마지막으로 **연결에 성공한** 호스트
     * @param attempt 진행 중인 시도 번호 (1-based)
     * @param maxAttempts 이 정책이 허용하는 총 시도 횟수
     */
    data class Reconnecting(
        val host: String,
        val attempt: Int,
        val maxAttempts: Int,
    ) : ConnectionState()

    data class Error(val message: String) : ConnectionState()
}
