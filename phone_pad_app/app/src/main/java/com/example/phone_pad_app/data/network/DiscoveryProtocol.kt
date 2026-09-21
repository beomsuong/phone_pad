package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.presentation.util.GestureConfig
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 서버 자동 탐색(UDP [GestureConfig.DISCOVERY_PORT])의 **와이어 포맷**을 다루는 순수 코드.
 *
 * 확정 스펙 (AGENTS.md 섹션 4에 추가될 내용):
 * ```jsonc
 * // 클라이언트 → 서버 (브로드캐스트, 패킷 1개, 개행 없음)
 * {"type":"DISCOVER"}
 * // 서버 → 클라이언트 (발신 주소로 유니캐스트, 패킷 1개, 개행 없음)
 * {"type":"SERVER","name":"MY-PC","port":9000}
 * ```
 *
 * 소켓과 분리해 두는 이유는 [SessionHandshake]와 같다 — 경계면 규칙 전부를 JVM 단위 테스트로
 * 고정할 수 있어야 한다. 설계상 **이 파서는 응답을 보낸 쪽을 전혀 믿지 않는다**:
 * 탐색은 인증 이전 단계라 같은 LAN의 누구나 응답을 위조할 수 있고, 위조된 값이 할 수 있는 일은
 * "목록에 이상한 줄이 하나 뜨는 것"까지여야 한다. 그래서 어떤 입력에도 예외를 던지지 않고
 * `null`(= 조용히 무시)로만 답한다.
 *
 * **서버 주소는 응답 본문에서 읽지 않는다.** 발신 주소(`senderHost`)가 유일한 출처다 —
 * 멀티 NIC/VPN 환경에서 서버가 자기 IP를 잘못 추정하는 문제를 피하기 위한 확정 스펙이며,
 * 동시에 "응답 본문의 주소 필드로 엉뚱한 호스트를 가리키게 만드는" 위조도 원천 차단한다.
 */
object DiscoveryProtocol {

    /** 브로드캐스트로 보내는 요청 1개. 개행 없음. 서버는 이 형태에만 응답한다. */
    const val DISCOVER_REQUEST = """{"type":"DISCOVER"}"""

    /** 응답의 `type` 값. 이 값이 정확히 아니면(대소문자 포함) 무시한다. */
    const val RESPONSE_TYPE = "SERVER"

    /**
     * 받아들일 응답 패킷의 최대 바이트 수.
     *
     * 서버는 이름을 **문자 수 64자**로 자르고 JSON을 `ensure_ascii`(기본값)로 만들어 비ASCII를
     * 유니코드 escape 시퀀스로 내보낸다. 그래서 바이트 수는 문자 종류에 따라 커진다 —
     * ASCII 64자 ≈ 103B, 한글(BMP) 64자 ≈ 423B, 비BMP(서로게이트 쌍) 64자 ≈ 807B(QA 실측).
     * 최악(807B)이 들어가도록 1024로 잡는다(512였을 때는 비BMP 40자부터 수신 버퍼에서 잘려
     * 서버가 목록에서 사라졌다). 상한을 두는 이유는 위조 응답이 파서에 큰 문자열을 밀어 넣지
     * 못하게 하기 위해서다. 수신 버퍼도 이 크기로 잡아 더 큰 패킷은 애초에 잘린 채(= 깨진 JSON)
     * 들어와 무시된다.
     */
    const val MAX_RESPONSE_BYTES = 1024

    private val TYPE_REGEX = Regex("\"type\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val NAME_REGEX = Regex("\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    /**
     * `port`는 **JSON 정수**여야 한다.
     * - `"9000"`(문자열)은 여는 따옴표 때문에 매치되지 않는다.
     * - `9000.5`/`9e3`은 뒤따르는 문자를 부정형 전방탐색으로 걸러 매치되지 않는다
     *   (`\d+`만 잡고 나머지를 버리면 실수 9000.5가 정수 9000으로 둔갑한다).
     */
    private val PORT_REGEX = Regex("\"port\"\\s*:\\s*(-?\\d+)(?![\\d.eE])")

    /** 점 4개 IPv4 표기만 허용한다. IPv6·스코프 표기(`%wlan0`)·호스트명은 전부 거른다. */
    private val IPV4_REGEX = Regex(
        "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$"
    )

    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535

    /**
     * 응답 패킷 하나를 [DiscoveredServer]로 해석한다.
     *
     * @param data 수신 버퍼
     * @param length 이 패킷의 실제 길이 (`DatagramPacket.getLength()`)
     * @param senderHost 발신 주소 문자열 (`DatagramPacket.getAddress().getHostAddress()`)
     * @return 규칙을 모두 만족하면 서버 정보, 하나라도 어긋나면 null(= 조용히 무시)
     *
     * 무시하는 경우: 빈/과대 패킷, UTF-8이 아닌 바이트, JSON 객체가 아닌 값, 깨진 JSON,
     * 자기 요청의 에코, `type != "SERVER"`, `port`가 정수가 아니거나 1..65535 밖,
     * `name`이 문자열이 아니거나 없는 경우, 발신 주소가 IPv4가 아닌 경우.
     */
    fun parseResponse(data: ByteArray, length: Int, senderHost: String?): DiscoveredServer? {
        if (senderHost == null || !IPV4_REGEX.matches(senderHost)) return null
        if (length <= 0 || length > MAX_RESPONSE_BYTES || length > data.size) return null

        val text = decodeUtf8(data, length) ?: return null
        val trimmed = text.trim()
        // JSON 객체만 받는다 — 배열/문자열/숫자로 감싼 응답은 스펙 밖이다.
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        // 자기 브로드캐스트가 되돌아온 경우(일부 환경에서 발생). type 검사로도 걸리지만,
        // "에코는 서버가 아니다"라는 규칙을 코드에 남겨 둔다.
        if (trimmed == DISCOVER_REQUEST) return null

        val type = TYPE_REGEX.find(trimmed)?.groupValues?.get(1)?.let { unescape(it) }
        if (type != RESPONSE_TYPE) return null

        val port = PORT_REGEX.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (port < MIN_PORT || port > MAX_PORT) return null

        // 이름은 표시용이지만 "문자열이 아니면 무시"는 지킨다 — 타입이 어긋난 응답은
        // 우리가 아는 그 서버가 아니라고 보는 편이 안전하다.
        val rawName = NAME_REGEX.find(trimmed)?.groupValues?.get(1) ?: return null
        val name = sanitizeName(unescape(rawName) ?: return null, senderHost)

        return DiscoveredServer(name = name, host = senderHost, port = port)
    }

    /** 편의 오버로드 — 버퍼 전체가 패킷일 때. */
    fun parseResponse(data: ByteArray, senderHost: String?): DiscoveredServer? =
        parseResponse(data, data.size, senderHost)

    /**
     * 표시용 이름 정리: 제어문자 제거 → 공백 정리 → [GestureConfig.DISCOVERY_MAX_NAME_LENGTH]자 절단.
     * 남는 것이 없으면 발신 주소를 이름 대신 쓴다(이름 없는 줄이 목록에 뜨지 않도록).
     */
    private fun sanitizeName(raw: String, fallback: String): String {
        val cleaned = raw
            .filter { it.code >= 0x20 && it.code != 0x7F }
            .trim()
            .take(GestureConfig.DISCOVERY_MAX_NAME_LENGTH)
        return cleaned.ifBlank { fallback }
    }

    /**
     * 엄격한 UTF-8 디코딩.
     *
     * `String(bytes, UTF_8)`은 깨진 바이트를 U+FFFD로 **대체**해 버려서 "UTF-8이 아니면 무시"를
     * 지킬 수 없다. 그래서 REPORT 모드 디코더로 직접 푼다.
     */
    private fun decodeUtf8(data: ByteArray, length: Int): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, 0, length))
            .toString()
    }.getOrNull()

    /**
     * JSON 문자열 이스케이프를 푼다: 따옴표·역슬래시·슬래시, b/f/n/r/t, 그리고 4자리 16진
     * 유니코드 이스케이프(역슬래시 + u + 네 자리).
     *
     * 파이썬 서버의 `json.dumps`는 기본값(`ensure_ascii=True`)이라 한글 PC 이름이 유니코드
     * 이스케이프로 온다 — 이걸 풀지 않으면 목록에 역슬래시 범벅인 문자열이 그대로 찍힌다.
     *
     * (이 KDoc에 실제 이스케이프 문자를 적지 않는 이유: kapt이 생성하는 Java 스텁의 주석에
     * 그대로 복사되어 `illegal unicode escape` 컴파일 오류가 난다 — 실제로 한 번 겪었다.)
     *
     * @return 알 수 없는/깨진 이스케이프가 있으면 null(= 패킷 무시).
     */
    private fun unescape(raw: String): String? {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\') {
                sb.append(c)
                i += 1
                continue
            }
            if (i + 1 >= raw.length) return null
            when (val esc = raw[i + 1]) {
                '"', '\\', '/' -> sb.append(esc)
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 5 >= raw.length) return null
                    val hex = raw.substring(i + 2, i + 6)
                    // toIntOrNull(16)은 부호("-12f", "+12f")를 허용해 위조 escape가 엉뚱한 문자가 된다.
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    val code = hex.toInt(16)
                    sb.append(code.toChar())
                    i += 4
                }
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }
}
