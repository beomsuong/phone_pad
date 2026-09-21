package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 탐색 와이어 포맷의 경계면 계약 (확정 스펙).
 *
 * 핵심 원칙 두 가지를 이 파일이 지킨다:
 * 1. **서버 주소는 발신 주소가 유일한 출처다** — 응답 본문의 어떤 필드도 주소로 쓰지 않는다.
 * 2. **어떤 입력에도 예외를 던지지 않는다** — 탐색은 인증 이전 단계라 같은 LAN의 누구나
 *    응답을 위조할 수 있고, 위조가 할 수 있는 최대치는 "목록에 이상한 줄 하나"여야 한다.
 */
class DiscoveryProtocolTest {

    private val sender = "192.168.0.7"

    private fun parse(json: String, from: String? = sender) =
        DiscoveryProtocol.parseResponse(json.toByteArray(Charsets.UTF_8), from)

    // --- 요청 리터럴 --------------------------------------------------------------------

    @Test
    fun `요청 리터럴은 확정 스펙과 정확히 일치한다`() {
        // 서버는 이 형태에만 응답한다. 공백 하나만 달라도 탐색이 통째로 죽는다.
        assertEquals("""{"type":"DISCOVER"}""", DiscoveryProtocol.DISCOVER_REQUEST)
        assertEquals(19, DiscoveryProtocol.DISCOVER_REQUEST.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `요청은 서버의 256바이트 상한보다 훨씬 작다`() {
        assertTrue(DiscoveryProtocol.DISCOVER_REQUEST.toByteArray(Charsets.UTF_8).size < 256)
    }

    // --- 정상 응답 ----------------------------------------------------------------------

    @Test
    fun `정상 응답은 이름과 포트를 읽고 주소는 발신 주소를 쓴다`() {
        val server = parse("""{"type":"SERVER","name":"MY-PC","port":9000}""")

        assertNotNull(server)
        assertEquals("MY-PC", server!!.name)
        assertEquals("192.168.0.7", server.host)
        assertEquals(9000, server.port)
    }

    @Test
    fun `응답 본문에 host나 ip 필드가 있어도 주소로 쓰지 않는다`() {
        // 확정 스펙: 주소는 발신 주소만. 이게 깨지면 위조 응답이 사용자를 임의의 호스트로 보낸다.
        val server = parse(
            """{"type":"SERVER","name":"MY-PC","port":9000,"host":"10.0.0.1","ip":"8.8.8.8"}"""
        )

        assertEquals("192.168.0.7", server?.host)
    }

    @Test
    fun `키 순서나 공백이 달라도 파싱된다`() {
        val server = parse("""{ "port" : 9000 , "name" : "PC-2" , "type" : "SERVER" }""")

        assertEquals("PC-2", server?.name)
        assertEquals(9000, server?.port)
    }

    @Test
    fun `세션 토큰 같은 여분 필드는 무시되고 결과에 새지 않는다`() {
        val server = parse(
            """{"type":"SERVER","name":"PC","port":9000,"session":"deadbeef"}"""
        )

        assertNotNull(server)
        assertEquals("PC", server!!.name)
        assertTrue("세션 토큰이 이름으로 새어들어갔다", !server.name.contains("deadbeef"))
    }

    // --- 포트 ---------------------------------------------------------------------------

    @Test
    fun `포트는 1에서 65535 사이만 받는다`() {
        assertEquals(1, parse("""{"type":"SERVER","name":"n","port":1}""")?.port)
        assertEquals(65535, parse("""{"type":"SERVER","name":"n","port":65535}""")?.port)
        assertNull(parse("""{"type":"SERVER","name":"n","port":0}"""))
        assertNull(parse("""{"type":"SERVER","name":"n","port":65536}"""))
        assertNull(parse("""{"type":"SERVER","name":"n","port":-1}"""))
    }

    @Test
    fun `포트가 정수가 아니면 무시한다`() {
        assertNull("문자열 포트", parse("""{"type":"SERVER","name":"n","port":"9000"}"""))
        assertNull("실수 포트", parse("""{"type":"SERVER","name":"n","port":9000.5}"""))
        assertNull("지수 표기", parse("""{"type":"SERVER","name":"n","port":9e3}"""))
        assertNull("null 포트", parse("""{"type":"SERVER","name":"n","port":null}"""))
        assertNull("불리언 포트", parse("""{"type":"SERVER","name":"n","port":true}"""))
        assertNull("포트 없음", parse("""{"type":"SERVER","name":"n"}"""))
    }

    @Test
    fun `포트가 TCP 기본값과 다르면 그 값을 그대로 쓴다`() {
        // 서버가 비표준 TCP 포트로 떴을 때 목록에서 고른 값이 실제 연결에 쓰여야 한다.
        val server = parse("""{"type":"SERVER","name":"n","port":9100}""")

        assertEquals(9100, server?.port)
        assertTrue(server!!.port != GestureConfig.DEFAULT_PORT)
    }

    // --- type ---------------------------------------------------------------------------

    @Test
    fun `type이 SERVER가 아니면 무시한다`() {
        assertNull("소문자", parse("""{"type":"server","name":"n","port":9000}"""))
        assertNull("다른 타입", parse("""{"type":"SESSION","name":"n","port":9000}"""))
        assertNull("type 없음", parse("""{"name":"n","port":9000}"""))
        assertNull("type이 숫자", parse("""{"type":1,"name":"n","port":9000}"""))
        assertNull("앞뒤 공백 포함", parse("""{"type":" SERVER ","name":"n","port":9000}"""))
    }

    @Test
    fun `자기 요청의 에코는 무시한다`() {
        // 일부 환경에서 브로드캐스트가 자기 소켓으로 되돌아온다.
        assertNull(parse(DiscoveryProtocol.DISCOVER_REQUEST))
    }

    // --- name ---------------------------------------------------------------------------

    @Test
    fun `이름은 64자로 자른다`() {
        val long = "A".repeat(200)
        val server = parse("""{"type":"SERVER","name":"$long","port":9000}""")

        assertEquals(GestureConfig.DISCOVERY_MAX_NAME_LENGTH, server?.name?.length)
    }

    @Test
    fun `이름의 제어문자는 제거한다`() {
        // 개행/탭이 섞인 이름이 목록 한 줄을 여러 줄로 밀어내지 못하게 한다.
        val server = parse("""{"type":"SERVER","name":"A\nB\tC\u0000D","port":9000}""")

        assertEquals("ABCD", server?.name)
    }

    @Test
    fun `이름이 비어 있으면 발신 주소를 이름으로 쓴다`() {
        assertEquals(sender, parse("""{"type":"SERVER","name":"","port":9000}""")?.name)
        assertEquals(sender, parse("""{"type":"SERVER","name":"   ","port":9000}""")?.name)
    }

    @Test
    fun `이름이 문자열이 아니거나 없으면 무시한다`() {
        assertNull(parse("""{"type":"SERVER","name":123,"port":9000}"""))
        assertNull(parse("""{"type":"SERVER","port":9000}"""))
    }

    @Test
    fun `이름의 유니코드 이스케이프를 푼다`() {
        // 파이썬 json.dumps는 기본값(ensure_ascii=True)이라 한글 PC 이름이 유니코드
        // 이스케이프(역슬래시 + u + 네 자리)로 온다.
        val server = parse("""{"type":"SERVER","name":"한글-PC","port":9000}""")

        assertEquals("한글-PC", server?.name)
    }

    @Test
    fun `이름의 따옴표 이스케이프를 푼다`() {
        val server = parse("""{"type":"SERVER","name":"A\"B\\C","port":9000}""")

        assertEquals("A\"B\\C", server?.name)
    }

    @Test
    fun `깨진 이스케이프는 무시한다`() {
        assertNull(parse("""{"type":"SERVER","name":"A\qB","port":9000}"""))
        assertNull(parse("""{"type":"SERVER","name":"A\u12","port":9000}"""))
    }

    // --- 잘못된 입력 --------------------------------------------------------------------

    @Test
    fun `깨진 JSON은 무시한다`() {
        assertNull("닫히지 않음", parse("""{"type":"SERVER","name":"n","port":9000"""))
        assertNull("중괄호 없음", parse(""""type":"SERVER","port":9000"""))
        assertNull("빈 문자열", parse(""))
        assertNull("빈 객체", parse("{}"))
    }

    @Test
    fun `JSON 객체가 아닌 값은 무시한다`() {
        assertNull("배열", parse("""["SERVER",9000]"""))
        assertNull("문자열", parse(""""SERVER""""))
        assertNull("숫자", parse("9000"))
        assertNull("null", parse("null"))
    }

    @Test
    fun `UTF-8이 아닌 바이트는 무시한다`() {
        // 0xFF는 UTF-8에서 나올 수 없는 바이트다. 대체 문자로 뭉개고 파싱하지 않는다.
        val bytes = byteArrayOf(0x7B, 0xFF.toByte(), 0xFE.toByte(), 0x7D)

        assertNull(DiscoveryProtocol.parseResponse(bytes, sender))
    }

    @Test
    fun `과대 패킷은 무시한다`() {
        val huge = """{"type":"SERVER","name":"${"A".repeat(2000)}","port":9000}"""

        assertNull(parse(huge))
    }

    @Test
    fun `길이 인자가 실제 패킷 길이로 쓰인다`() {
        // 수신 버퍼는 재사용되므로, 버퍼 뒤쪽의 옛 바이트가 파싱에 섞이면 안 된다.
        val json = """{"type":"SERVER","name":"PC","port":9000}"""
        val buffer = ByteArray(DiscoveryProtocol.MAX_RESPONSE_BYTES)
        val bytes = json.toByteArray(Charsets.UTF_8)
        bytes.copyInto(buffer)

        assertEquals(9000, DiscoveryProtocol.parseResponse(buffer, bytes.size, sender)?.port)
        // 버퍼 전체(뒤가 0으로 채워짐)를 넘기면 JSON이 아니므로 무시된다.
        assertNull(DiscoveryProtocol.parseResponse(buffer, buffer.size, sender))
        assertNull(DiscoveryProtocol.parseResponse(buffer, 0, sender))
        assertNull(DiscoveryProtocol.parseResponse(buffer, -1, sender))
        assertNull(DiscoveryProtocol.parseResponse(buffer, buffer.size + 1, sender))
    }

    @Test
    fun `서버가 자른 최악의 이름(비BMP 64자)도 상한 안에 들어와 파싱된다`() {
        // QA F-1: 서버는 이름을 문자 수 64자로 자르고 ensure_ascii로 escape한다. 이모지 64자는
        // 서로게이트 쌍 escape(12바이트)가 64번이라 807바이트 — 예전 상한 512에서는 서버가 사라졌다.
        val escaped = "\\ud83d\\ude00".repeat(64)
        val json = """{"type":"SERVER","name":"$escaped","port":9000}"""
        val bytes = json.toByteArray(Charsets.UTF_8)

        assertTrue("최악 응답 ${bytes.size}B", bytes.size > 512)
        assertTrue("최악 응답 ${bytes.size}B", bytes.size <= DiscoveryProtocol.MAX_RESPONSE_BYTES)
        val server = parse(json)
        assertNotNull(server)
        assertEquals(9000, server!!.port)
    }

    @Test
    fun `부호가 붙은 위조 유니코드 escape는 이름을 통째로 거부한다`() {
        // QA W-1: toIntOrNull(16)은 "-12f"를 허용한다. 정상 서버 출력에는 절대 없는 형태.
        assertNull(parse("""{"type":"SERVER","name":"a\u-12fb","port":9000}"""))
        assertNull(parse("""{"type":"SERVER","name":"a\u+12fb","port":9000}"""))
        assertEquals("aé", parse("""{"type":"SERVER","name":"a\u00e9","port":9000}""")?.name)
    }

    // --- 발신 주소 ----------------------------------------------------------------------

    @Test
    fun `발신 주소가 IPv4가 아니면 무시한다`() {
        val json = """{"type":"SERVER","name":"n","port":9000}"""

        assertNull("IPv6", parse(json, from = "fe80::1"))
        assertNull("스코프 표기", parse(json, from = "192.168.0.7%wlan0"))
        assertNull("호스트명", parse(json, from = "my-pc.local"))
        assertNull("범위 초과", parse(json, from = "999.1.1.1"))
        assertNull("null", parse(json, from = null))
        assertNull("빈 문자열", parse(json, from = ""))
    }

    @Test
    fun `유효한 IPv4 발신 주소는 그대로 host가 된다`() {
        val json = """{"type":"SERVER","name":"n","port":9000}"""

        assertEquals("10.0.0.1", parse(json, from = "10.0.0.1")?.host)
        assertEquals("172.16.31.254", parse(json, from = "172.16.31.254")?.host)
    }
}
