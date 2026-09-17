package com.example.phone_pad_app.data.network

/**
 * TCP 연결 직후 서버가 보내는 세션 핸드셰이크 한 줄을 파싱한다.
 *
 * 서버가 보내는 형식 (AGENTS.md 섹션 4 / 확정 스펙):
 * ```
 * {"type":"SESSION","session":"<32자리 hex 토큰>"}\n
 * ```
 *
 * 소켓과 분리된 순수 함수로 두어 JVM 단위 테스트에서 그대로 검증할 수 있게 한다.
 */
object SessionHandshake {

    private val TYPE_REGEX = Regex("\"type\"\\s*:\\s*\"([^\"]*)\"")
    private val SESSION_REGEX = Regex("\"session\"\\s*:\\s*\"([^\"]*)\"")

    private const val SESSION_TYPE = "SESSION"

    /**
     * @param line 서버가 보낸 핸드셰이크 한 줄 (개행 제외). 연결이 끊겼으면 null.
     * @return 유효한 세션 토큰, 형식이 어긋나면 null.
     */
    fun parseSession(line: String?): String? {
        if (line.isNullOrBlank()) return null

        val type = TYPE_REGEX.find(line)?.groupValues?.getOrNull(1)
        if (type != SESSION_TYPE) return null

        val session = SESSION_REGEX.find(line)?.groupValues?.getOrNull(1)
        return session?.takeIf { it.isNotBlank() }
    }
}
