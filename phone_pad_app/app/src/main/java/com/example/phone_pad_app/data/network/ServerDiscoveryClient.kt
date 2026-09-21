package com.example.phone_pad_app.data.network

import com.example.phone_pad_app.di.IoDispatcher
import com.example.phone_pad_app.domain.model.DiscoveredServer
import com.example.phone_pad_app.presentation.util.GestureConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** 수신한 UDP 패킷 하나 — 소켓 구현을 테스트에서 갈아끼우기 위한 최소 표현. */
internal class DiscoveryPacket(
    val data: ByteArray,
    val length: Int,
    val senderHost: String?,
)

/**
 * 탐색에 쓰는 UDP 소켓의 최소 계약.
 *
 * `DatagramSocket`을 직접 쓰지 않고 한 겹 씌우는 이유는 오직 테스트다 — 실제 브로드캐스트 없이
 * "몇 번 보냈는지", "어떤 패킷을 받았을 때 어떻게 되는지", "어느 경로에서든 닫히는지"를
 * 결정적으로 검증할 수 있어야 한다.
 */
internal interface DiscoverySocket {
    /** 실패하면 예외를 던진다 — 대상 주소별로 호출자가 개별적으로 삼킨다. */
    fun send(data: ByteArray, address: InetAddress, port: Int)

    /** @return 받은 패킷, [timeoutMs] 안에 아무것도 없으면 null. */
    fun receive(timeoutMs: Int): DiscoveryPacket?

    fun close()
}

/** 실제 UDP 소켓. 브로드캐스트 송신 허용 + 타임아웃 수신. */
private class RealDiscoverySocket(private val socket: DatagramSocket) : DiscoverySocket {

    override fun send(data: ByteArray, address: InetAddress, port: Int) {
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    override fun receive(timeoutMs: Int): DiscoveryPacket? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val buffer = ByteArray(DiscoveryProtocol.MAX_RESPONSE_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            DiscoveryPacket(buffer, packet.length, packet.address?.hostAddress)
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        socket.close()
    }
}

/**
 * UDP 브로드캐스트로 같은 LAN의 PC 서버를 찾는 클라이언트 (Phase 4).
 *
 * 흐름: `{"type":"DISCOVER"}`를 [GestureConfig.DISCOVERY_PORT]로 브로드캐스트 →
 * 서버가 유니캐스트로 `{"type":"SERVER","name":..,"port":..}` 응답 → 발신 주소를 서버 주소로 삼는다.
 *
 * **설계 메모**
 * - 대상 주소는 `255.255.255.255`**와** 인터페이스별 서브넷 브로드캐스트를 **모두** 쓴다.
 *   일부 기기/AP는 제한 브로드캐스트를 버리고, 반대로 서브넷 브로드캐스트만 막힌 환경도 있다.
 *   둘 다 추가 권한이 필요 없다(INTERNET 하나로 충분).
 * - UDP는 재전송이 없어 브로드캐스트 한 발이 조용히 사라질 수 있으므로 0/300/600ms에 3회 보낸다.
 * - **블로킹 `receive()`는 코루틴 취소로 풀리지 않는다**([TcpClient]의 `connect()`와 같은 문제).
 *   그래서 [GestureConfig.DISCOVERY_RECEIVE_POLL_MS]짜리 짧은 타임아웃으로 쪼개 돌며 매 회차
 *   [yield]로 취소를 확인하고, 어떤 경로로 빠져나가든 `finally`에서 소켓을 닫는다.
 * - 어떤 실패도 위로 올리지 않는다(취소 제외). 네트워크 열거 실패·전송 실패·깨진 응답은
 *   사용자 입장에서 전부 "못 찾음"이고, 수동 IP 입력이라는 fallback이 늘 살아 있다.
 */
@Singleton
class ServerDiscoveryClient @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 소켓 생성 지점. **테스트 전용 주입점**이며 프로덕션은 기본값을 쓴다
     * ([TcpClient.socketFactory]와 같은 규약).
     */
    internal var socketFactory: () -> DiscoverySocket = {
        RealDiscoverySocket(DatagramSocket().apply { broadcast = true })
    }

    /** 브로드캐스트 대상 목록 제공자. 테스트에서는 루프백 하나만 주입한다. */
    internal var broadcastTargets: () -> List<InetAddress> = { defaultBroadcastTargets() }

    /** 대상 포트. 테스트에서 임시 포트로 바꾸기 위한 주입점. */
    internal var discoveryPort: Int = GestureConfig.DISCOVERY_PORT

    /** 총 수신 창 (ms). 실제 1.5초를 기다리는 테스트를 만들지 않기 위한 주입점. */
    internal var timeoutMs: Long = GestureConfig.DISCOVERY_TIMEOUT_MS

    /**
     * 시간 소스. 창 종료와 재전송 시각 판정에 쓴다.
     *
     * 주입 가능한 이유는 [timeoutMs]와 같다 — 테스트가 실제 시간을 기다리지 않고
     * "1.5초가 지났다"를 만들 수 있어야 한다.
     */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /**
     * 한 번 탐색하고 발견한 서버 목록을 돌려준다(발견 순서 유지, `host:port` 중복 제거,
     * 최대 [GestureConfig.DISCOVERY_MAX_RESULTS]개).
     */
    suspend fun discover(): List<DiscoveredServer> = withContext(ioDispatcher) {
        val targets = runCatching { broadcastTargets() }.getOrDefault(emptyList())
        if (targets.isEmpty()) return@withContext emptyList()

        val socket = runCatching { socketFactory() }.getOrNull() ?: return@withContext emptyList()
        val request = DiscoveryProtocol.DISCOVER_REQUEST.toByteArray(Charsets.UTF_8)
        val found = LinkedHashMap<String, DiscoveredServer>()

        try {
            val startedAt = nowMs()
            val deadline = startedAt + timeoutMs
            var probesSent = 0
            var successfulSends = 0

            while (true) {
                // 취소 확인 지점. 여기서 CancellationException이 나가면 finally가 소켓을 닫는다.
                yield()

                val now = nowMs()
                if (now >= deadline) break

                // 재전송 시각이 됐으면 한 회차를 보낸다 (대상 주소 전부에 1개씩).
                if (probesSent < GestureConfig.DISCOVERY_PROBE_COUNT &&
                    now >= startedAt + probesSent * GestureConfig.DISCOVERY_PROBE_INTERVAL_MS
                ) {
                    successfulSends += sendProbe(socket, targets, request)
                    probesSent += 1
                    // 모든 회차를 보냈는데 단 한 번도 성공하지 못했다면 응답이 올 리 없다.
                    // 창이 끝나기를 기다릴 이유가 없으므로 즉시 빈 결과로 끝낸다.
                    if (probesSent >= GestureConfig.DISCOVERY_PROBE_COUNT && successfulSends == 0) break
                    continue
                }

                val packet = socket.receive(pollTimeoutMs(now, startedAt, probesSent, deadline))
                    ?: continue
                val server = DiscoveryProtocol.parseResponse(
                    data = packet.data,
                    length = packet.length,
                    senderHost = packet.senderHost,
                ) ?: continue

                val key = "${server.host}:${server.port}"
                if (!found.containsKey(key)) {
                    found[key] = server
                    if (found.size >= GestureConfig.DISCOVERY_MAX_RESULTS) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 예상 못 한 소켓 오류. 이미 찾은 것은 그대로 돌려준다 — 탐색은 편의 기능이라
            // 부분 결과가 예외보다 낫다.
        } finally {
            runCatching { socket.close() }
        }

        found.values.toList()
    }

    /**
     * 수신 1회의 대기 시간: 폴링 간격 / 남은 창 / 다음 재전송까지 중 가장 짧은 값.
     * 최소 1ms — `soTimeout = 0`은 "무한 대기"라 절대 넘겨서는 안 된다.
     */
    private fun pollTimeoutMs(now: Long, startedAt: Long, probesSent: Int, deadline: Long): Int {
        val untilDeadline = deadline - now
        val untilNextProbe = if (probesSent < GestureConfig.DISCOVERY_PROBE_COUNT) {
            startedAt + probesSent * GestureConfig.DISCOVERY_PROBE_INTERVAL_MS - now
        } else {
            Long.MAX_VALUE
        }
        val wait = minOf(
            GestureConfig.DISCOVERY_RECEIVE_POLL_MS.toLong(),
            untilDeadline,
            untilNextProbe,
        )
        return wait.coerceAtLeast(1L).toInt()
    }

    /**
     * 대상 주소 전부에 DISCOVER를 한 개씩 보낸다.
     * 실패는 **주소별로 개별적으로** 삼킨다 — 하나가 막혔다고 나머지를 포기하면,
     * 제한 브로드캐스트를 버리는 기기에서 탐색이 통째로 죽는다.
     *
     * @return 성공한 전송 수
     */
    private fun sendProbe(
        socket: DiscoverySocket,
        targets: List<InetAddress>,
        request: ByteArray,
    ): Int {
        var sent = 0
        targets.forEach { target ->
            if (runCatching { socket.send(request, target, discoveryPort) }.isSuccess) sent += 1
        }
        return sent
    }

    private companion object {

        /** 제한 브로드캐스트 주소 — 라우터를 넘지 않고 같은 링크에만 뿌려진다. */
        const val LIMITED_BROADCAST = "255.255.255.255"

        /**
         * 제한 브로드캐스트 + 활성 IPv4 인터페이스의 서브넷 브로드캐스트 목록.
         *
         * 열거는 기기/시점에 따라 얼마든지 실패할 수 있으므로(인터페이스가 내려가는 중 등)
         * 인터페이스 단위로 `runCatching`을 씌워 하나가 실패해도 나머지를 계속 모은다.
         */
        fun defaultBroadcastTargets(): List<InetAddress> {
            val targets = LinkedHashSet<InetAddress>()
            runCatching { targets.add(InetAddress.getByName(LIMITED_BROADCAST)) }
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                    runCatching {
                        if (!nif.isUp || nif.isLoopback) return@runCatching
                        nif.interfaceAddresses.forEach { address ->
                            address?.broadcast?.let { targets.add(it) }
                        }
                    }
                }
            }
            return targets.toList()
        }
    }
}
