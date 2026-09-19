package com.example.phone_pad_app.domain.model

import com.example.phone_pad_app.presentation.util.GestureConfig

/**
 * 연결 유실 후 자동 재연결의 **정책**(언제·몇 번·얼마나 기다렸다 다시 붙을지)만 담는 순수 클래스.
 *
 * 시간/코루틴/소켓에 전혀 의존하지 않는다 — 실제 대기와 재접속은
 * [TrackpadRepositoryImpl][com.example.phone_pad_app.data.repository.TrackpadRepositoryImpl]이
 * 주입된 디스패처 위에서 수행하고, 이 클래스는 "다음 시도까지 몇 ms"만 계산한다.
 * 덕분에 백오프 수열은 가상 시간조차 필요 없는 평범한 단위 테스트로 고정할 수 있다.
 *
 * 생성자 주입으로 쓰는 이유는 테스트에서 [Disabled]를 넣어 **재연결 이전의 동작**
 * (유실 → `Error`)을 그대로 재현하기 위해서다. 기존 heartbeat/직렬화 테스트들은
 * 그 의미를 검증하고 있으므로 정책만 바꿔 끼워 보존한다.
 *
 * @param enabled false면 유실 시 기존처럼 곧바로 `Error(message)`로 간다.
 * @param maxAttempts 총 시도 횟수. 소진되면 `Error("Reconnect failed: ...")`.
 * @param baseDelayMs 1회차 시도 전 대기 시간. 이후 시도마다 2배.
 * @param maxDelayMs 지연 상한 — 지수 증가를 여기서 클램프한다.
 */
data class ReconnectPolicy(
    val enabled: Boolean = true,
    val maxAttempts: Int = GestureConfig.RECONNECT_MAX_ATTEMPTS,
    val baseDelayMs: Long = GestureConfig.RECONNECT_BASE_DELAY_MS,
    val maxDelayMs: Long = GestureConfig.RECONNECT_MAX_DELAY_MS,
) {

    /** 재연결을 시도할 여지가 조금이라도 있는 정책인가. */
    val isActive: Boolean
        get() = enabled && maxAttempts > 0

    /**
     * [attempt]번째(1-based) 시도를 하기 **전에** 기다릴 시간 (ms).
     *
     * 기본값 기준 수열: 1s, 2s, 4s, 8s, 10s, 10s, ... ([maxDelayMs] 클램프)
     *
     * @throws IllegalArgumentException [attempt]가 1 미만일 때 — 호출자의 인덱싱 실수를
     *   조용히 0ms 재시도 폭주로 바꾸지 않기 위해 방어한다.
     */
    fun delayBeforeAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based, got $attempt" }
        val cap = maxDelayMs.coerceAtLeast(0L)
        // 2^30배를 넘어가면 어차피 상한에 걸린다. shift 폭을 제한해 Long 오버플로를 막는다
        // (오버플로로 음수가 나오면 delay()가 즉시 반환해 재시도 폭주가 된다).
        val shift = attempt - 1
        if (shift >= MAX_SHIFT) return cap
        val raw = baseDelayMs.coerceAtLeast(0L) shl shift
        return raw.coerceIn(0L, cap)
    }

    /** [attempt]번째 시도를 해도 되는가. */
    fun shouldAttempt(attempt: Int): Boolean = isActive && attempt in 1..maxAttempts

    companion object {
        private const val MAX_SHIFT = 31

        /** 앱 기본 정책 ([GestureConfig]의 상수 그대로). */
        val Default = ReconnectPolicy()

        /** 자동 재연결 없음 — 유실 시 즉시 `Error`. */
        val Disabled = ReconnectPolicy(enabled = false, maxAttempts = 0)
    }
}
