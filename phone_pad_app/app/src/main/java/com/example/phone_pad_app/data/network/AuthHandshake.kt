package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.presentation.util.GestureConfig

/**
 * TCP 연결 직후 주고받는 **PIN 인증 한 왕복**의 와이어 포맷 (AGENTS.md 섹션 4 / 확정 스펙).
 *
 * ```
 * 클라이언트 -> 서버 (연결 직후, 다른 어떤 것보다도 먼저 나가는 첫 줄)
 * {"type":"AUTH","pin":"483920"}
 *
 * 서버 -> 클라이언트 (성공)   {"type":"SESSION","session":"<32자리 hex>"}
 * 서버 -> 클라이언트 (실패)   {"type":"AUTH_FAIL","reason":"invalid_pin"}
 * ```
 *
 * **AUTH는 서버 설정과 무관하게 항상 보낸다.** 서버가 PIN을 요구하는지 클라이언트는 미리 알 수
 * 없으므로, 와이어 형식이 서버 설정에 따라 갈라지면 안 된다(인증이 꺼진 서버는 `pin` 값을 무시하고
 * 그대로 SESSION을 준다).
 *
 * 소켓과 분리된 순수 함수로 두어 JVM 단위 테스트에서 그대로 검증한다 — [SessionHandshake]와 같은 방식.
 */
object AuthHandshake {

    /** 클라이언트가 보내는 첫 줄의 `type`. */
    const val AUTH_TYPE = "AUTH"

    /** 서버가 PIN 불일치를 알릴 때의 `type`. 이 줄을 받으면 서버는 곧바로 연결을 닫는다. */
    const val AUTH_FAIL_TYPE = "AUTH_FAIL"

    private val TYPE_REGEX = Regex("\"type\"\\s*:\\s*\"([^\"]*)\"")
    private val REASON_REGEX = Regex("\"reason\"\\s*:\\s*\"([^\"]*)\"")

    /**
     * 전송할 AUTH 한 줄을 만든다 (개행 없음 — 호출자가 `println`으로 붙인다).
     *
     * 방어 두 가지:
     * - 길이를 [GestureConfig.AUTH_PIN_MAX_LENGTH]로 **자른다**. 6자리 PIN에는 영향이 없고,
     *   입력란에 붙여넣기 등으로 들어온 비정상적으로 긴 값이 그대로 와이어에 실리는 것을 막는다.
     * - JSON 문자열을 깨뜨릴 수 있는 문자를 처리한다([escapeJsonString]). 서버는 어차피
     *   6자리 숫자만 통과시키지만, 깨진 JSON을 보내면 서버가 "형식 오류"로 **조용히** 연결을
     *   닫아 사용자에게는 원인 없는 실패로 보인다. 최소한 유효한 JSON은 보내고 "PIN 불일치"라는
     *   답을 받는 편이 낫다.
     */
    fun buildAuthLine(pin: String): String {
        val trimmed = pin.take(GestureConfig.AUTH_PIN_MAX_LENGTH)
        return """{"type":"$AUTH_TYPE","pin":"${escapeJsonString(trimmed)}"}"""
    }

    /** 서버가 보낸 첫 줄이 인증 거부인가? (형식이 어긋나거나 다른 type이면 false) */
    fun isAuthFail(line: String?): Boolean {
        if (line.isNullOrBlank()) return false
        return TYPE_REGEX.find(line)?.groupValues?.getOrNull(1) == AUTH_FAIL_TYPE
    }

    /** 인증 거부 줄의 `reason` (진단용). 없거나 비어 있으면 null. */
    fun parseFailReason(line: String?): String? {
        if (line.isNullOrBlank()) return null
        return REASON_REGEX.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * 큰따옴표와 백슬래시는 이스케이프하고, 제어문자는 **버린다**.
     *
     * 제어문자를 버리는 이유가 요점이다: 개행이 섞이면 한 줄이 두 줄로 쪼개져 서버가 AUTH
     * 다음 줄을 이벤트로 읽으려 하게 된다("한 줄 = 한 메시지" 규약 위반). 이스케이프해서
     * 살려 둘 가치가 있는 값이 아니므로(PIN은 숫자다) 제거가 가장 안전하다.
     */
    private fun escapeJsonString(value: String): String = buildString(value.length) {
        for (c in value) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c.code < 0x20 || c.code == 0x7F -> Unit
                else -> append(c)
            }
        }
    }
}
