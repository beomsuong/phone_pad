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

    /**
     * 연결에 실패했거나 유실됐고 자동 복구도 끝난 상태 — 사용자가 다시 연결해야 한다.
     *
     * @param message **내부 진단용 문자열.** 예외 원문이거나 고정 리터럴
     *   (`"Heartbeat timeout"`, `"Connection lost"`, `"Session handshake failed"`,
     *   `"Reconnect failed: ..."`)이다. 이 값은 계약이므로 사용자 친화적으로 고치지 않는다 —
     *   화면에 그대로 내보내지도 않는다.
     * @param kind 사용자에게 보여줄 문구를 고르기 위한 원인 종류.
     *   변환은 [ConnectionErrorMessages][com.example.phone_pad_app.presentation.util.ConnectionErrorMessages]가
     *   담당한다. 기본값이 [ConnectionErrorKind.UNKNOWN]이라 종류를 모르는 호출자는
     *   기존처럼 메시지 하나만 넘기면 되고, 그때 화면에는 원문이 그대로 붙은 폴백 문구가 나간다.
     */
    data class Error(
        val message: String,
        val kind: ConnectionErrorKind = ConnectionErrorKind.UNKNOWN,
    ) : ConnectionState()
}
