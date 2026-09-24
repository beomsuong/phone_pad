package com.example.phone_pad_app.presentation.util

import com.example.phone_pad_app.domain.model.ConnectionErrorKind

/**
 * 연결 오류를 **사용자에게 보여줄 한국어 문구**로 바꾸는 표시 계층 순수 함수 모음.
 *
 * 왜 여기서만 바꾸는가: 내부 상태의 `message`(예: `"Heartbeat timeout"`,
 * `"failed to connect to /192.168.0.5 (port 9000) ... after 21000ms"`)는 진단용 원문이고
 * 재연결 로직·기존 테스트가 기대하는 계약이다. 그 문자열을 친절하게 고치면 계약이 깨지므로,
 * 상태는 그대로 두고 **화면에 그릴 때만** 종류([ConnectionErrorKind])를 보고 문구를 고른다.
 *
 * Compose/Android 프레임워크에 의존하지 않는다 — 문자열만 다루는 순수 함수라 JUnit으로
 * 전 분기를 고정할 수 있고, 리소스(strings.xml)를 쓰지 않는 것도 같은 이유다(이 앱은
 * 현재 한국어 단일 로케일이고, 다국어가 필요해지면 이 한 파일만 리소스로 옮기면 된다).
 */
object ConnectionErrorMessages {

    /** 원인을 특정하지 못했고 원문조차 없을 때의 최후 문구. */
    const val GENERIC =
        "연결에 실패했습니다. IP 주소와 PC 서버 상태를 확인하세요."

    /** [UNKNOWN][ConnectionErrorKind.UNKNOWN] 폴백에서 원문 앞에 붙이는 접두사. */
    const val UNKNOWN_PREFIX = "연결 실패: "

    /**
     * 사용자에게 보여줄 **주 메시지**. 무엇이 잘못됐는지 + 무엇을 확인해야 하는지를 담는다.
     *
     * @param kind 원인 종류
     * @param rawMessage 내부 진단 문자열. [ConnectionErrorKind.UNKNOWN]일 때만 문구에 포함된다
     *   (원문을 완전히 버리지 않기 위해). 그 외 종류에서는 [detail]로 따로 보여준다.
     */
    fun userMessage(kind: ConnectionErrorKind, rawMessage: String? = null): String = when (kind) {
        ConnectionErrorKind.CONNECTION_REFUSED ->
            "PC에서 Phone Pad 서버가 실행 중인지 확인하세요. " +
                "방화벽이 TCP ${GestureConfig.DEFAULT_PORT} 포트를 막고 있을 수도 있습니다."

        ConnectionErrorKind.TIMEOUT ->
            "연결 시간이 초과되었습니다. IP 주소가 맞는지, 폰과 PC가 같은 Wi-Fi에 있는지 확인하세요."

        ConnectionErrorKind.UNKNOWN_HOST ->
            "주소를 찾을 수 없습니다. 입력한 IP 주소를 다시 확인하세요."

        ConnectionErrorKind.NETWORK_UNREACHABLE ->
            "네트워크에 연결할 수 없습니다. 폰의 Wi-Fi가 켜져 있는지, PC와 같은 공유기에 붙어 있는지 확인하세요."

        ConnectionErrorKind.HANDSHAKE_FAILED ->
            "응답한 서버가 Phone Pad 서버가 아니거나 버전이 다릅니다. PC에서 Phone Pad 서버를 실행했는지 확인하세요."

        ConnectionErrorKind.AUTH_FAILED ->
            "PIN이 올바르지 않습니다. PC 화면에 표시된 PIN 6자리를 다시 확인해 입력하세요."

        ConnectionErrorKind.HEARTBEAT_TIMEOUT ->
            "PC 서버가 응답하지 않아 연결이 끊어졌습니다. PC가 절전 상태는 아닌지, Wi-Fi가 유지되는지 확인하세요."

        ConnectionErrorKind.CONNECTION_LOST ->
            "PC와의 연결이 끊어졌습니다. Wi-Fi와 PC 서버 상태를 확인한 뒤 다시 연결하세요."

        ConnectionErrorKind.RECONNECT_FAILED ->
            "자동 재연결에 실패했습니다. PC 서버와 Wi-Fi를 확인한 뒤 다시 연결하세요."

        // 다른 유실 문구와 달리 "자동으로 되돌아가지 않는다"를 반드시 말해야 한다 —
        // 이 종류만 자동 재연결을 하지 않으므로, 사용자가 기다리면 복구될 것으로 오해하면
        // 아무 일도 일어나지 않는 화면을 계속 보게 된다.
        ConnectionErrorKind.SESSION_REPLACED ->
            "다른 기기가 이 PC에 새로 연결되어 현재 연결이 끊겼습니다. " +
                "자동으로 다시 연결되지 않으니, 계속 사용하려면 직접 다시 연결하세요."

        ConnectionErrorKind.UNKNOWN -> {
            val raw = rawMessage?.trim()
            if (raw.isNullOrEmpty()) GENERIC else "$UNKNOWN_PREFIX$raw"
        }
    }

    /**
     * 주 메시지 아래에 작게 덧붙일 **원문**. 없으면 null.
     *
     * 원문을 숨기지 않는 이유: 사용자가 원인을 짚기 어려울 때 캡처 한 장으로 상황을 전달할 수
     * 있어야 한다. 다만 [ConnectionErrorKind.UNKNOWN]에서는 [userMessage]가 이미 원문을
     * 품고 있으므로 같은 문자열을 두 번 보여주지 않는다.
     */
    fun detail(kind: ConnectionErrorKind, rawMessage: String?): String? {
        if (kind == ConnectionErrorKind.UNKNOWN) return null
        val raw = rawMessage?.trim()
        return if (raw.isNullOrEmpty()) null else raw
    }
}
