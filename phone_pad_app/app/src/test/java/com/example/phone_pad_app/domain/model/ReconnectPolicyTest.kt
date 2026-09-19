package com.example.phone_pad_app.domain.model

import com.example.phone_pad_app.presentation.util.GestureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 백오프 정책은 시간에 의존하지 않는 순수 계산이라 가상 시간조차 필요 없다.
 * (실제 대기는 `TrackpadRepositoryImpl`이 주입된 디스패처의 `delay`로 수행하며,
 * 그 타이밍은 `TrackpadRepositoryReconnectTest`가 가상 시간으로 검증한다.)
 */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy.Default

    @Test
    fun `기본 백오프는 1, 2, 4, 8초로 배가된다`() {
        assertEquals(1_000L, policy.delayBeforeAttempt(1))
        assertEquals(2_000L, policy.delayBeforeAttempt(2))
        assertEquals(4_000L, policy.delayBeforeAttempt(3))
        assertEquals(8_000L, policy.delayBeforeAttempt(4))
    }

    @Test
    fun `5회차부터는 상한 10초에 고정된다`() {
        // 지수 증가를 그대로 두면 8회차가 128초가 되어 "서버가 이미 돌아왔는데도 한참 못 붙는"
        // 구간이 생긴다. 상한에 걸린 뒤로는 같은 간격으로 계속 두드린다.
        assertEquals(GestureConfig.RECONNECT_MAX_DELAY_MS, policy.delayBeforeAttempt(5))
        assertEquals(GestureConfig.RECONNECT_MAX_DELAY_MS, policy.delayBeforeAttempt(6))
        assertEquals(GestureConfig.RECONNECT_MAX_DELAY_MS, policy.delayBeforeAttempt(8))
    }

    @Test
    fun `기본 정책의 총 대기 시간은 55초다`() {
        // 1+2+4+8+10+10+10+10 = 55s. 이 값이 "재연결을 포기하기까지"의 체감 시간이다.
        val total = (1..policy.maxAttempts).sumOf { policy.delayBeforeAttempt(it) }
        assertEquals(55_000L, total)
        assertEquals(GestureConfig.RECONNECT_MAX_ATTEMPTS, policy.maxAttempts)
    }

    @Test
    fun `아주 큰 시도 번호에서도 오버플로 없이 상한을 돌려준다`() {
        // shift 폭을 제한하지 않으면 Long이 뒤집혀 음수 지연이 나오고, delay()가 즉시
        // 반환해 재시도 폭주가 된다.
        assertEquals(GestureConfig.RECONNECT_MAX_DELAY_MS, policy.delayBeforeAttempt(31))
        assertEquals(GestureConfig.RECONNECT_MAX_DELAY_MS, policy.delayBeforeAttempt(64))
        assertEquals(GestureConfig.RECONNECT_MAX_DELAY_MS, policy.delayBeforeAttempt(Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `시도 번호는 1부터다 - 0은 거부한다`() {
        policy.delayBeforeAttempt(0)
    }

    @Test
    fun `shouldAttempt는 1부터 maxAttempts까지만 참이다`() {
        val p = ReconnectPolicy(maxAttempts = 3)
        assertFalse(p.shouldAttempt(0))
        assertTrue(p.shouldAttempt(1))
        assertTrue(p.shouldAttempt(3))
        assertFalse(p.shouldAttempt(4))
    }

    @Test
    fun `비활성 정책은 어떤 시도도 허용하지 않는다`() {
        assertFalse(ReconnectPolicy.Disabled.isActive)
        assertFalse(ReconnectPolicy.Disabled.shouldAttempt(1))
        // enabled=true라도 횟수가 0이면 재연결이 없는 것과 같다
        assertFalse(ReconnectPolicy(maxAttempts = 0).isActive)
    }

    @Test
    fun `상한이 기본 지연보다 작아도 음수나 역전이 생기지 않는다`() {
        val p = ReconnectPolicy(baseDelayMs = 5_000L, maxDelayMs = 1_000L)
        assertEquals(1_000L, p.delayBeforeAttempt(1))
        assertEquals(1_000L, p.delayBeforeAttempt(5))
    }

    @Test
    fun `기본 정책은 GestureConfig 상수를 단일 출처로 쓴다`() {
        assertTrue(policy.isActive)
        assertEquals(GestureConfig.RECONNECT_BASE_DELAY_MS, policy.baseDelayMs)
        assertEquals(GestureConfig.RECONNECT_MAX_DELAY_MS, policy.maxDelayMs)
        assertEquals(GestureConfig.RECONNECT_MAX_ATTEMPTS, policy.maxAttempts)
    }
}
