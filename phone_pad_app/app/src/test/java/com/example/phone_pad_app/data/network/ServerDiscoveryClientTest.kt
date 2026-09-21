package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * [ServerDiscoveryClient]의 동작 계약 — **실제 브로드캐스트도, 실제 1.5초 대기도 없다.**
 *
 * 소켓([DiscoverySocket])과 시간([ServerDiscoveryClient.nowMs])을 주입해
 * "몇 번·언제 보냈는지", "응답을 어떻게 걸러 담는지", "어느 경로에서든 소켓을 닫는지"를
 * 결정적으로 고정한다. 실소켓 왕복은 [ServerDiscoveryLoopbackTest]가 1건 담당한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerDiscoveryClientTest {

    /** 주입 가능한 가짜 시계 — `receive` 타임아웃만큼만 흐른다. */
    private class FakeClock(var now: Long = 0L)

    private class Sent(val body: String, val host: String, val port: Int, val atMs: Long)

    private class FakeDiscoverySocket(private val clock: FakeClock) : DiscoverySocket {
        val sends = mutableListOf<Sent>()
        val receiveTimeouts = mutableListOf<Int>()
        val queue = ArrayDeque<DiscoveryPacket>()

        /** 이 주소로의 전송만 실패시킨다(제한 브로드캐스트를 버리는 기기 재현). */
        var blockedHosts: Set<String> = emptySet()
        var closed = false

        /** n번째 `receive`에서 던질 예외 (1-based). */
        var throwAtReceive: Int = -1
        var receiveError: Exception = IOException("socket blew up")
        private var receiveCount = 0

        override fun send(data: ByteArray, address: InetAddress, port: Int) {
            val host = address.hostAddress ?: ""
            if (host in blockedHosts) throw IOException("blocked: $host")
            sends += Sent(String(data, Charsets.UTF_8), host, port, clock.now)
        }

        override fun receive(timeoutMs: Int): DiscoveryPacket? {
            receiveCount += 1
            receiveTimeouts += timeoutMs
            if (receiveCount == throwAtReceive) throw receiveError
            val packet = queue.removeFirstOrNull()
            if (packet == null) {
                // 아무것도 오지 않았다 = 타임아웃만큼 시간이 흘렀다.
                clock.now += timeoutMs
                return null
            }
            return packet
        }

        override fun close() {
            closed = true
        }

        fun enqueue(json: String, from: String) {
            val bytes = json.toByteArray(Charsets.UTF_8)
            queue.addLast(DiscoveryPacket(bytes, bytes.size, from))
        }
    }

    private val clock = FakeClock()
    private val socket = FakeDiscoverySocket(clock)

    /**
     * 모든 테스트가 **하나의** 디스패처(=하나의 스케줄러)를 공유해야 한다.
     * `runTest`에 다른 스케줄러가 섞이면 `Detected use of different schedulers`로 죽는다.
     */
    private val dispatcher = StandardTestDispatcher()

    private fun client(
        targets: List<String> = listOf("255.255.255.255"),
    ): ServerDiscoveryClient {
        return ServerDiscoveryClient(dispatcher).apply {
            socketFactory = { socket }
            broadcastTargets = { targets.map { InetAddress.getByName(it) } }
            nowMs = { clock.now }
        }
    }

    private fun serverJson(name: String = "MY-PC", port: Int = 9000) =
        """{"type":"SERVER","name":"$name","port":$port}"""

    // --- 전송 -------------------------------------------------------------------------

    @Test
    fun `DISCOVER를 3회 보내고 대상 주소마다 한 개씩 보낸다`() = runTest(dispatcher) {
        val target = client(targets = listOf("255.255.255.255", "192.168.0.255"))

        target.discover()

        assertEquals(GestureConfig.DISCOVERY_PROBE_COUNT * 2, socket.sends.size)
        assertTrue(socket.sends.all { it.body == DiscoveryProtocol.DISCOVER_REQUEST })
        assertTrue(socket.sends.all { it.port == GestureConfig.DISCOVERY_PORT })
        assertEquals(
            setOf("255.255.255.255", "192.168.0.255"),
            socket.sends.map { it.host }.toSet(),
        )
    }

    @Test
    fun `재전송은 0 300 600ms 시점에 나간다`() = runTest(dispatcher) {
        client().discover()

        val interval = GestureConfig.DISCOVERY_PROBE_INTERVAL_MS
        val expected = (0 until GestureConfig.DISCOVERY_PROBE_COUNT).map { it * interval }
        // 폴링 간격(200ms)의 배수로 시간이 흐르므로 정확히 300ms에 깨어나지는 않는다 —
        // "재전송 시각을 지나면 곧바로 보낸다"를 폴링 간격 오차로 고정한다.
        socket.sends.forEachIndexed { index, sent ->
            val target = expected[index]
            assertTrue(
                "재전송 $index 가 $target ms 전에 나갔다 (${sent.atMs})",
                sent.atMs >= target,
            )
            assertTrue(
                "재전송 $index 가 너무 늦게 나갔다 (${sent.atMs})",
                sent.atMs < target + GestureConfig.DISCOVERY_RECEIVE_POLL_MS,
            )
        }
        // 마지막 재전송 후에도 응답을 기다릴 시간이 남아야 한다 (GestureConfig 불변식과 같은 계약)
        assertTrue(socket.sends.last().atMs < GestureConfig.DISCOVERY_TIMEOUT_MS)
    }

    @Test
    fun `한 대상의 전송 실패는 나머지 대상을 막지 않는다`() = runTest(dispatcher) {
        socket.blockedHosts = setOf("255.255.255.255")
        val target = client(targets = listOf("255.255.255.255", "192.168.0.255"))
        socket.enqueue(serverJson(), from = "192.168.0.10")

        val result = target.discover()

        assertEquals(GestureConfig.DISCOVERY_PROBE_COUNT, socket.sends.size)
        assertTrue(socket.sends.all { it.host == "192.168.0.255" })
        assertEquals(1, result.size)
    }

    @Test
    fun `모든 전송이 실패하면 창을 다 기다리지 않고 빈 결과로 끝낸다`() = runTest(dispatcher) {
        socket.blockedHosts = setOf("255.255.255.255")

        val result = client().discover()

        assertTrue(result.isEmpty())
        assertTrue("소켓이 닫히지 않았다", socket.closed)
        assertTrue(
            "전송이 전부 실패했는데도 창을 끝까지 기다렸다 (${clock.now}ms)",
            clock.now < GestureConfig.DISCOVERY_TIMEOUT_MS,
        )
    }

    @Test
    fun `대상 주소가 없으면 소켓을 열지 않고 빈 결과를 준다`() = runTest(dispatcher) {
        var factoryCalls = 0
        val target = client(targets = emptyList()).apply {
            socketFactory = { factoryCalls += 1; socket }
        }

        assertTrue(target.discover().isEmpty())
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `소켓 생성이 실패하면 예외 대신 빈 결과를 준다`() = runTest(dispatcher) {
        val target = client().apply { socketFactory = { throw IOException("no socket") } }

        assertTrue(target.discover().isEmpty())
    }

    // --- 수신 -------------------------------------------------------------------------

    @Test
    fun `응답을 발견 순서대로 담는다`() = runTest(dispatcher) {
        val target = client()
        socket.enqueue(serverJson(name = "FIRST"), from = "192.168.0.11")
        socket.enqueue(serverJson(name = "SECOND"), from = "192.168.0.12")

        val result = target.discover()

        assertEquals(listOf("FIRST", "SECOND"), result.map { it.name })
        assertEquals(listOf("192.168.0.11", "192.168.0.12"), result.map { it.host })
    }

    @Test
    fun `같은 host와 port의 응답은 한 번만 담는다`() = runTest(dispatcher) {
        val target = client()
        socket.enqueue(serverJson(name = "A"), from = "192.168.0.11")
        socket.enqueue(serverJson(name = "A-again"), from = "192.168.0.11")

        val result = target.discover()

        assertEquals(1, result.size)
        assertEquals("A", result.single().name)
    }

    @Test
    fun `같은 host라도 port가 다르면 별개 서버다`() = runTest(dispatcher) {
        val target = client()
        socket.enqueue(serverJson(port = 9000), from = "192.168.0.11")
        socket.enqueue(serverJson(port = 9100), from = "192.168.0.11")

        assertEquals(listOf(9000, 9100), target.discover().map { it.port })
    }

    @Test
    fun `결과는 상한에서 멈추고 그 뒤 응답은 읽지 않는다`() = runTest(dispatcher) {
        val target = client()
        val overflow = GestureConfig.DISCOVERY_MAX_RESULTS + 3
        repeat(overflow) { index ->
            socket.enqueue(serverJson(name = "PC-$index"), from = "192.168.1.${index + 1}")
        }

        val result = target.discover()

        assertEquals(GestureConfig.DISCOVERY_MAX_RESULTS, result.size)
        // 상한에 닿는 순간 루프를 끝낸다 — 남은 응답을 계속 읽으면 위조 응답이 창을 점유한다.
        assertTrue(socket.queue.isNotEmpty())
    }

    @Test
    fun `잘못된 응답은 무시하고 뒤에 오는 정상 응답은 담는다`() = runTest(dispatcher) {
        val target = client()
        socket.enqueue("not json at all", from = "192.168.0.11")
        socket.enqueue("""{"type":"SESSION","session":"deadbeef"}""", from = "192.168.0.12")
        socket.enqueue(DiscoveryProtocol.DISCOVER_REQUEST, from = "192.168.0.13")
        socket.enqueue(serverJson(name = "REAL"), from = "192.168.0.14")

        val result = target.discover()

        assertEquals(1, result.size)
        assertEquals("REAL", result.single().name)
        assertEquals("192.168.0.14", result.single().host)
    }

    @Test
    fun `IPv6 발신 주소의 응답은 무시한다`() = runTest(dispatcher) {
        val target = client()
        socket.enqueue(serverJson(), from = "fe80::1")

        assertTrue(target.discover().isEmpty())
    }

    @Test
    fun `수신 타임아웃은 0이 아니고 폴링 간격을 넘지 않는다`() = runTest(dispatcher) {
        // soTimeout 0은 "무한 대기"다 — 한 번이라도 0이 넘어가면 취소가 풀리지 않는다.
        client().discover()

        assertTrue(socket.receiveTimeouts.isNotEmpty())
        assertTrue(socket.receiveTimeouts.all { it in 1..GestureConfig.DISCOVERY_RECEIVE_POLL_MS })
    }

    @Test
    fun `창이 끝나면 스스로 멈추고 소켓을 닫는다`() = runTest(dispatcher) {
        val result = client().discover()

        assertTrue(result.isEmpty())
        assertTrue(socket.closed)
        assertTrue(clock.now >= GestureConfig.DISCOVERY_TIMEOUT_MS)
    }

    // --- 실패·취소 경로 ----------------------------------------------------------------

    @Test
    fun `예상 못 한 소켓 오류는 부분 결과를 남기고 소켓을 닫는다`() = runTest(dispatcher) {
        val target = client()
        socket.enqueue(serverJson(name = "FOUND-BEFORE-ERROR"), from = "192.168.0.11")
        socket.throwAtReceive = 2

        val result = target.discover()

        assertEquals(1, result.size)
        assertEquals("FOUND-BEFORE-ERROR", result.single().name)
        assertTrue("오류 경로에서 소켓이 닫히지 않았다", socket.closed)
    }

    @Test
    fun `취소는 삼키지 않고 올리며 소켓은 닫는다`() = runTest(dispatcher) {
        // 블로킹 receive 중 취소가 터진 상황. 취소를 결과로 바꿔치기하면 상위(ViewModel)가
        // 취소된 탐색을 "못 찾음"으로 표시해 버린다.
        val target = client()
        socket.throwAtReceive = 1
        socket.receiveError = CancellationException("cancelled while blocked")

        val thrown = runCatching { target.discover() }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is CancellationException)
        assertTrue("취소 경로에서 소켓이 닫히지 않았다", socket.closed)
    }

    @Test
    fun `탐색은 부작용 없이 반복 호출할 수 있다`() = runTest(dispatcher) {
        val target = client()
        socket.enqueue(serverJson(name = "A"), from = "192.168.0.11")
        val first = target.discover()

        clock.now = 0
        socket.sends.clear()
        socket.enqueue(serverJson(name = "B"), from = "192.168.0.12")
        val second = target.discover()

        assertEquals("A", first.single().name)
        assertEquals("B", second.single().name)
        assertFalse(second.any { it.name == "A" })
    }
}
