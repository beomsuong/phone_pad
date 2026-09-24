package com.example.phone_pad_app.presentation.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 채널 포트와 제스처 임계값은 코드 곳곳에 하드코딩되지 않고 여기서만 정의된다.
 * 값이 바뀌면 서버(pc_server) 및 AGENTS.md 섹션 4와 함께 갱신해야 하므로 회귀 테스트로 고정한다.
 */
class GestureConfigTest {

    @Test
    fun `TCP는 9000 UDP는 9001이며 서로 다른 포트다`() {
        assertEquals(9000, GestureConfig.DEFAULT_PORT)
        assertEquals(9001, GestureConfig.UDP_PORT)
        assertTrue(GestureConfig.DEFAULT_PORT != GestureConfig.UDP_PORT)
    }

    @Test
    fun `탐색 포트는 9002이며 TCP와 MOVE UDP 포트와 겹치지 않는다`() {
        // 확정 스펙: 탐색은 전용 포트를 쓴다. 9001(MOVE 전용, 세션 토큰 검증)에 섞으면
        // "UDP는 MOVE만"이라는 AGENTS.md 섹션 4 원칙이 무너진다.
        assertEquals(9002, GestureConfig.DISCOVERY_PORT)
        assertTrue(GestureConfig.DISCOVERY_PORT != GestureConfig.UDP_PORT)
        assertTrue(GestureConfig.DISCOVERY_PORT != GestureConfig.DEFAULT_PORT)
    }

    @Test
    fun `탐색 상수는 확정 스펙 값과 일치한다`() {
        assertEquals(1500L, GestureConfig.DISCOVERY_TIMEOUT_MS)
        assertEquals(3, GestureConfig.DISCOVERY_PROBE_COUNT)
        assertEquals(300L, GestureConfig.DISCOVERY_PROBE_INTERVAL_MS)
        assertEquals(8, GestureConfig.DISCOVERY_MAX_RESULTS)
        // 서버도 같은 값으로 이름을 자른다 (양쪽 동시 갱신 대상)
        assertEquals(64, GestureConfig.DISCOVERY_MAX_NAME_LENGTH)
    }

    @Test
    fun `마지막 재전송 뒤에도 응답을 기다릴 시간이 남는다`() {
        // 이 불변식이 깨지면 마지막 브로드캐스트가 나가는 순간(또는 나가지도 못하고) 창이 닫혀
        // 재전송이 유실 대비 장치로서 무의미해진다.
        val lastProbeAt =
            (GestureConfig.DISCOVERY_PROBE_COUNT - 1) * GestureConfig.DISCOVERY_PROBE_INTERVAL_MS
        assertTrue("마지막 재전송이 창 밖이다", lastProbeAt < GestureConfig.DISCOVERY_TIMEOUT_MS)
        // 왕복 여유: 마지막 재전송 후 최소 한 번의 폴링 주기 이상은 남아야 한다.
        assertTrue(
            GestureConfig.DISCOVERY_TIMEOUT_MS - lastProbeAt >
                GestureConfig.DISCOVERY_RECEIVE_POLL_MS
        )
    }

    @Test
    fun `수신 폴링 간격은 0이 아니고 총 창보다 짧다`() {
        // 0은 소켓 soTimeout에서 "무한 대기"를 의미한다 — 블로킹 receive가 취소로 풀리지 않는데
        // 무한 대기까지 걸리면 화면을 떠난 뒤에도 소켓이 남는다.
        assertTrue(GestureConfig.DISCOVERY_RECEIVE_POLL_MS > 0)
        assertTrue(
            GestureConfig.DISCOVERY_RECEIVE_POLL_MS < GestureConfig.DISCOVERY_TIMEOUT_MS
        )
        // 재전송 시각을 놓치지 않으려면 폴링이 재전송 간격보다 촘촘해야 한다.
        assertTrue(
            GestureConfig.DISCOVERY_RECEIVE_POLL_MS <= GestureConfig.DISCOVERY_PROBE_INTERVAL_MS
        )
    }

    @Test
    fun `탐색 재전송은 2회 이상이고 결과 상한은 양수다`() {
        // 1회면 UDP 단발 유실이 곧 탐색 실패다.
        assertTrue(GestureConfig.DISCOVERY_PROBE_COUNT >= 2)
        assertTrue(GestureConfig.DISCOVERY_MAX_RESULTS > 0)
        assertTrue(GestureConfig.DISCOVERY_MAX_NAME_LENGTH > 0)
    }

    @Test
    fun `탐색 창은 연결 타임아웃보다 짧다`() {
        // 탐색은 "잠깐 훑어보는" 동작이다. 연결 시도보다 오래 걸리면 수동 입력이 더 빨라진다.
        assertTrue(
            GestureConfig.DISCOVERY_TIMEOUT_MS < GestureConfig.CONNECT_TIMEOUT_MS.toLong()
        )
    }

    @Test
    fun `세션 핸드셰이크 타임아웃은 양수다`() {
        assertTrue(GestureConfig.SESSION_HANDSHAKE_TIMEOUT_MS > 0)
    }

    @Test
    fun `연결 타임아웃은 확정 스펙 값 5초다`() {
        assertEquals(5000, GestureConfig.CONNECT_TIMEOUT_MS)
    }

    @Test
    fun `연결 타임아웃은 OS 기본값에 맡기지 않을 만큼 짧고 사용자를 가두지 않는다`() {
        // 0이면 java.net.Socket.connect가 "무한 대기"로 해석한다 — OS 기본값에 맡기는 것과 같아
        // 애초에 이 상수를 만든 이유가 사라진다.
        assertTrue("0은 무한 대기를 의미한다", GestureConfig.CONNECT_TIMEOUT_MS > 0)
        // 연결(5초) + 핸드셰이크(3초)가 직렬로 붙으므로 최악 대기가 10초를 넘지 않아야 한다.
        assertTrue(
            "연결+핸드셰이크 최악 대기가 너무 길다",
            GestureConfig.CONNECT_TIMEOUT_MS + GestureConfig.SESSION_HANDSHAKE_TIMEOUT_MS <= 10_000
        )
        // 혼잡한 Wi-Fi의 TCP 초기 재전송(RTO 약 1초, 이후 2초)을 덮을 만큼은 길어야 한다.
        assertTrue("TCP 재전송을 덮지 못한다", GestureConfig.CONNECT_TIMEOUT_MS >= 3_000)
    }

    @Test
    fun `heartbeat 상수는 확정 스펙 값과 일치한다`() {
        // 서버(pc_server) HEARTBEAT_INTERVAL_S = 5.0 / HEARTBEAT_MISS_LIMIT = 3 과 대칭
        assertEquals(5000L, GestureConfig.HEARTBEAT_INTERVAL_MS)
        assertEquals(3, GestureConfig.HEARTBEAT_MISS_LIMIT)
        // 5초 × 3회 ≈ 15초 (AGENTS.md 섹션 6)
        assertEquals(
            15_000L,
            GestureConfig.HEARTBEAT_INTERVAL_MS * GestureConfig.HEARTBEAT_MISS_LIMIT
        )
        // 소켓 soTimeout(Int)으로 그대로 쓸 수 있는 범위여야 한다
        assertTrue(GestureConfig.HEARTBEAT_INTERVAL_MS <= Int.MAX_VALUE.toLong())
    }

    @Test
    fun `제스처 임계값은 MOVE 최소 거리가 탭 최대 거리보다 작다`() {
        assertTrue(GestureConfig.MOVE_MIN_DISTANCE_PX < GestureConfig.TAP_MAX_DISTANCE_PX)
        assertTrue(GestureConfig.MOVE_SENSITIVITY > 0f)
        assertTrue(GestureConfig.TAP_MAX_DURATION_MS > 0L)
    }

    @Test
    fun `단일 포인터 구간 기준은 1손가락이다`() {
        // MultiTouchGestureTracker가 MOVE/CLICK을 방출하는 유일한 구간 조건
        assertEquals(1, GestureConfig.SINGLE_POINTER_COUNT)
    }

    @Test
    fun `2손가락 구간 기준은 2손가락이며 단일 포인터와 다르다`() {
        // 우클릭(CLICK button="right") 판정의 유일한 구간 조건 (AGENTS.md 섹션 5)
        assertEquals(2, GestureConfig.DOUBLE_POINTER_COUNT)
        assertTrue(GestureConfig.DOUBLE_POINTER_COUNT > GestureConfig.SINGLE_POINTER_COUNT)
    }

    @Test
    fun `3손가락 구간 기준은 3손가락이며 1,2손가락과 구분된다`() {
        // DESKTOP_SWITCH 판정의 유일한 구간 조건 (AGENTS.md 섹션 5)
        assertEquals(3, GestureConfig.THREE_POINTER_COUNT)
        assertTrue(GestureConfig.THREE_POINTER_COUNT > GestureConfig.DOUBLE_POINTER_COUNT)
        assertTrue(GestureConfig.DOUBLE_POINTER_COUNT > GestureConfig.SINGLE_POINTER_COUNT)
    }

    @Test
    fun `3손가락 스와이프 임계는 확정 스펙 값과 일치한다`() {
        assertEquals(120f, GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX, 0.001f)
        assertEquals(2f, GestureConfig.THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE, 0.001f)
    }

    @Test
    fun `3손가락 스와이프 임계는 탭 최대 이동 거리보다 충분히 크다`() {
        // 데스크톱 전환은 되돌리기 번거로운 전역 동작이다. 3손가락을 내려놓을 때 생기는
        // 어긋남이나 손떨림(탭 한계 수준)이 전환으로 새면 안 되므로 여유가 커야 한다.
        assertTrue(
            GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX >
                GestureConfig.TAP_MAX_DISTANCE_PX * 2f
        )
        // 더블탭 허용 거리(탭 한계의 2배)보다도 커야 "확실히 쓸었다"는 신호가 된다.
        assertTrue(
            GestureConfig.THREE_FINGER_SWIPE_MIN_DISTANCE_PX >
                GestureConfig.DOUBLE_TAP_DISTANCE_PX
        )
    }

    @Test
    fun `수평 우세 배수는 1보다 커서 대각선을 실제로 걸러낸다`() {
        // 1 이하면 |dx| >= |dy|인 45도 대각선까지 좌우 전환으로 해석된다.
        assertTrue(GestureConfig.THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE > 1f)
    }

    @Test
    fun `스크롤 1스텝 거리는 탭 최대 이동 거리보다 크다`() {
        // 스크롤은 isDrag(= 탭 최대 이동 거리 초과) 이후에만 시작된다. 스텝 단위가 그보다 작으면
        // 스크롤이 걸리는 순간 이미 1스텝 이상이 쌓여 있어 첫 프레임에 툭 튄다.
        assertTrue(GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP > 0f)
        assertTrue(
            GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP > GestureConfig.TAP_MAX_DISTANCE_PX
        )
    }

    @Test
    fun `사용자 조정 범위는 기본값을 포함한다`() {
        // 기본값이 범위 밖이면 설정 화면을 한 번 열기만 해도 값이 경계로 끌려가 동작이 바뀐다.
        assertTrue(GestureConfig.MOVE_SENSITIVITY_MIN < GestureConfig.MOVE_SENSITIVITY_MAX)
        assertTrue(GestureConfig.MOVE_SENSITIVITY >= GestureConfig.MOVE_SENSITIVITY_MIN)
        assertTrue(GestureConfig.MOVE_SENSITIVITY <= GestureConfig.MOVE_SENSITIVITY_MAX)

        assertTrue(GestureConfig.SCROLL_PX_PER_STEP_MIN < GestureConfig.SCROLL_PX_PER_STEP_MAX)
        assertTrue(GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP >= GestureConfig.SCROLL_PX_PER_STEP_MIN)
        assertTrue(GestureConfig.SCROLL_SENSITIVITY_PX_PER_STEP <= GestureConfig.SCROLL_PX_PER_STEP_MAX)
    }

    @Test
    fun `이동 감도 하한은 0보다 크다`() {
        // 0이면 커서가 전혀 움직이지 않아 "고장난 앱"이 된다 — 슬라이더로도 도달 불가여야 한다.
        assertTrue(GestureConfig.MOVE_SENSITIVITY_MIN > 0f)
    }

    @Test
    fun `스크롤 스텝 거리 하한도 탭 최대 이동 거리보다 크다`() {
        // 위 `스크롤 1스텝 거리는...` 테스트와 같은 불변식을 **사용자가 도달 가능한 최솟값**에
        // 대해서도 강제한다. 하한을 낮추는 순간 슬라이더를 끝까지 내린 사용자만 스크롤 진입이
        // 튀는, 재현하기 까다로운 버그가 된다.
        assertTrue(
            GestureConfig.SCROLL_PX_PER_STEP_MIN > GestureConfig.TAP_MAX_DISTANCE_PX
        )
    }

    @Test
    fun `더블탭 상수는 확정 스펙 값과 일치한다`() {
        assertEquals(300L, GestureConfig.DOUBLE_TAP_INTERVAL_MS)
        assertEquals(40f, GestureConfig.DOUBLE_TAP_DISTANCE_PX, 0.001f)
    }

    @Test
    fun `더블탭 허용 거리는 탭 최대 이동 거리보다 크다`() {
        // 사람이 같은 자리를 두 번 탭해도 몇 px씩 어긋난다. "탭 하나로 인정되는 이동 거리"보다
        // 넉넉해야 두 번째 탭이 더블탭으로 묶인다 (확정 스펙: TAP_MAX_DISTANCE_PX의 2배).
        assertTrue(
            GestureConfig.DOUBLE_TAP_DISTANCE_PX > GestureConfig.TAP_MAX_DISTANCE_PX
        )
        assertEquals(
            GestureConfig.TAP_MAX_DISTANCE_PX * 2f,
            GestureConfig.DOUBLE_TAP_DISTANCE_PX,
            0.001f,
        )
    }

    @Test
    fun `더블탭 간격은 탭 최대 지속 시간보다 길다`() {
        // 두 번째 탭은 "탭으로 끝난 뒤"에야 판정에 들어온다. 간격이 탭 지속 시간보다 짧으면
        // 정상 속도로 두 번 탭해도 두 번째 탭이 시작되기 전에 지연 클릭이 먼저 나가버린다.
        assertTrue(
            GestureConfig.DOUBLE_TAP_INTERVAL_MS > GestureConfig.TAP_MAX_DURATION_MS
        )
    }

    @Test
    fun `드래그 홀드 임계는 탭 최대 지속 시간과 정확히 같다`() {
        // 확정 스펙: "탭으로 인정되지 않게 되는 시점" == "드래그 홀드가 되는 시점".
        // 두 값이 어긋나면 탭도 드래그도 아닌 사각지대(또는 둘 다 발사되는 구간)가 생긴다.
        assertEquals(GestureConfig.TAP_MAX_DURATION_MS, GestureConfig.DRAG_HOLD_THRESHOLD_MS)
        assertEquals(200L, GestureConfig.DRAG_HOLD_THRESHOLD_MS)
    }

    @Test
    fun `드래그 홀드 임계는 멀티터치 해제 유예보다 길다`() {
        // 그렇지 않으면 손가락을 어긋나게 떼는 꼬리 구간이 드래그 홀드로 승격될 수 있다.
        assertTrue(
            GestureConfig.DRAG_HOLD_THRESHOLD_MS > GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS
        )
    }

    @Test
    fun `드래그 홀드 임계는 더블탭 간격보다 짧다`() {
        // 홀드로 승격되는 순간 대기 중이던 지연 클릭을 flush 해야 하는데, 임계가 더블탭 간격보다
        // 길면 그 클릭이 이미 스스로 나간 뒤라 flush 의미가 없어진다(= 드래그 도중 클릭이 도착).
        assertTrue(
            GestureConfig.DRAG_HOLD_THRESHOLD_MS < GestureConfig.DOUBLE_TAP_INTERVAL_MS
        )
    }

    @Test
    fun `PIN 길이 상한은 서버가 생성하는 6자리를 넉넉히 담는다`() {
        // 상한의 목적은 와이어 크기 방어이지 형식 검증이 아니다. 6자리보다 작아지면 정상 PIN이
        // 잘려 나가 "맞는 PIN인데 항상 실패"하는 버그가 된다 (AuthHandshake가 전송 전에 자른다).
        assertTrue(GestureConfig.AUTH_PIN_MAX_LENGTH >= 6)
        assertEquals(32, GestureConfig.AUTH_PIN_MAX_LENGTH)
    }

    @Test
    fun `멀티터치 해제 유예는 탭 최대 지속 시간의 절반보다 짧다`() {
        // 이 여유가 없으면 "손가락 하나를 떼고 남은 손가락으로 탭"하는 동작까지
        // 2손가락 탭으로 삼켜버린다 (MultiTouchGestureTracker의 꼬리 보정 전제 조건)
        assertTrue(GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS > 0L)
        assertTrue(
            GestureConfig.MULTI_TOUCH_RELEASE_GRACE_MS < GestureConfig.TAP_MAX_DURATION_MS / 2
        )
    }
}
