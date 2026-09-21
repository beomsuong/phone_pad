package com.example.phone_pad_app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 실소켓 왕복 1건 — 주입된 가짜 소켓([ServerDiscoveryClientTest])이 실제 UDP 동작을 잘못
 * 흉내내고 있지 않은지 확인한다. `DatagramSocket`의 `soTimeout`/`receive`/발신 주소 노출까지
 * 실제로 지난다.
 *
 * 브로드캐스트는 CI/개발 머신 네트워크에 의존하므로 대상 주소를 **루프백으로 주입**하고,
 * 창(`timeoutMs`)도 짧게 줄여 테스트가 1.5초를 기다리지 않게 한다(주입점의 존재 이유).
 */
class ServerDiscoveryLoopbackTest {

    @Test
    fun `실제 UDP 왕복으로 서버를 찾고 발신 주소를 host로 쓴다`() {
        val responder = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val started = CountDownLatch(1)
        val requests = mutableListOf<String>()

        val thread = Thread {
            runCatching {
                responder.soTimeout = 3_000
                started.countDown()
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)
                responder.receive(packet)
                synchronized(requests) {
                    requests += String(packet.data, 0, packet.length, Charsets.UTF_8)
                }
                val reply = """{"type":"SERVER","name":"LOOPBACK-PC","port":9000}"""
                    .toByteArray(Charsets.UTF_8)
                responder.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            }
        }.apply { isDaemon = true }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val client = ServerDiscoveryClient(Dispatchers.IO).apply {
            broadcastTargets = { listOf(InetAddress.getByName("127.0.0.1")) }
            discoveryPort = responder.localPort
            timeoutMs = 700
        }

        try {
            val result = runBlocking { client.discover() }

            assertEquals(1, result.size)
            val server = result.single()
            assertEquals("LOOPBACK-PC", server.name)
            assertEquals(9000, server.port)
            // 응답 본문에 주소가 없으므로, 이 값은 발신 주소에서만 올 수 있다.
            assertEquals("127.0.0.1", server.host)
            synchronized(requests) {
                assertEquals(DiscoveryProtocol.DISCOVER_REQUEST, requests.first())
            }
        } finally {
            responder.close()
        }
    }

    @Test
    fun `아무도 응답하지 않으면 창이 끝나고 빈 결과를 준다`() {
        // 패킷은 받지만 답하지 않는 소켓으로 보낸다. 닫힌 포트로 보내면 Windows에서
        // ICMP port unreachable이 다음 recv를 깨우는 변수가 생겨 "창이 끝날 때까지 기다린다"를
        // 재지 못한다.
        val silent = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

        val client = ServerDiscoveryClient(Dispatchers.IO).apply {
            broadcastTargets = { listOf(InetAddress.getByName("127.0.0.1")) }
            discoveryPort = silent.localPort
            timeoutMs = 500
        }

        try {
            val startedAt = System.currentTimeMillis()
            val result = runBlocking { client.discover() }

            assertTrue(result.isEmpty())
            assertTrue(System.currentTimeMillis() - startedAt >= 400)
        } finally {
            silent.close()
        }
    }

    @Test
    fun `취소하면 창이 끝나기 전에 빠져나오고 소켓을 닫는다`() {
        // 블로킹 receive는 코루틴 취소로 풀리지 않는다 — 짧은 폴링 + finally close가 유일한 탈출로다.
        val silent = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val realSockets = mutableListOf<DatagramSocket>()
        val client = ServerDiscoveryClient(Dispatchers.IO).apply {
            broadcastTargets = { listOf(InetAddress.getByName("127.0.0.1")) }
            discoveryPort = silent.localPort
            timeoutMs = 30_000
            socketFactory = {
                val raw = DatagramSocket().apply { broadcast = true }
                synchronized(realSockets) { realSockets += raw }
                object : DiscoverySocket {
                    override fun send(data: ByteArray, address: InetAddress, port: Int) {
                        raw.send(DatagramPacket(data, data.size, address, port))
                    }

                    override fun receive(timeoutMs: Int): DiscoveryPacket? {
                        raw.soTimeout = timeoutMs.coerceAtLeast(1)
                        val buffer = ByteArray(DiscoveryProtocol.MAX_RESPONSE_BYTES)
                        val packet = DatagramPacket(buffer, buffer.size)
                        return try {
                            raw.receive(packet)
                            DiscoveryPacket(buffer, packet.length, packet.address?.hostAddress)
                        } catch (e: java.net.SocketTimeoutException) {
                            null
                        }
                    }

                    override fun close() = raw.close()
                }
            }
        }

        val startedAt = System.currentTimeMillis()
        try {
            runBlocking {
                val job = launch(Dispatchers.IO) { client.discover() }
                // 소켓이 실제로 열려 수신 루프에 들어갈 시간을 준다.
                while (synchronized(realSockets) { realSockets.isEmpty() }) {
                    kotlinx.coroutines.delay(10)
                }
                kotlinx.coroutines.delay(50)
                job.cancel()
                job.join()
            }
            val elapsed = System.currentTimeMillis() - startedAt

            assertTrue("30초 창을 끝까지 기다렸다 ($elapsed ms)", elapsed < 5_000)
            synchronized(realSockets) {
                assertTrue(realSockets.isNotEmpty())
                assertTrue("취소 후 소켓이 열린 채 남았다", realSockets.all { it.isClosed })
            }
        } finally {
            silent.close()
        }
    }
}
