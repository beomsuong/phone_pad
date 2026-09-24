package com.example.phone_pad_app.presentation.util

object GestureConfig {
    /**
     * 이동 감도 배율 — **사용자 조정 가능**(설정 화면).
     *
     * 이 값은 런타임에 바뀔 수 있는 값의 **기본값**이자 단일 출처다. 실제 제스처 판정에 쓰이는 값은
     * [GestureSettings][com.example.phone_pad_app.domain.model.GestureSettings]가 들고 있고,
     * `MultiTouchGestureTracker`는 그것을 생성자로 주입받는다 — 상수를 직접 읽지 않는다.
     * 허용 범위는 [MOVE_SENSITIVITY_MIN]..[MOVE_SENSITIVITY_MAX].
     */
    const val MOVE_SENSITIVITY = 1.5f

    /**
     * [MOVE_SENSITIVITY]의 사용자 조정 하한.
     *
     * 0에 가까워지면 커서가 사실상 움직이지 않아 "고장난 것처럼" 보이므로 0.5배 아래로는 내리지 않는다.
     */
    const val MOVE_SENSITIVITY_MIN = 0.5f

    /** [MOVE_SENSITIVITY]의 사용자 조정 상한 — 이 이상은 화면 폭을 한 번에 가로질러 조준이 불가능해진다. */
    const val MOVE_SENSITIVITY_MAX = 4.0f

    /** 탭으로 인정할 최대 이동 거리 (px) */
    const val TAP_MAX_DISTANCE_PX = 20f

    /** 탭으로 인정할 최대 지속 시간 (ms) */
    const val TAP_MAX_DURATION_MS = 200L

    /** 커서 이동 이벤트를 발생시킬 최소 이동 거리 (px) — 탭 중 미세 떨림 억제 */
    const val MOVE_MIN_DISTANCE_PX = 5f

    /**
     * 단일 포인터(1손가락) 구간으로 판정할 동시 포인터 개수.
     * 이 개수인 구간에서만 MOVE를 방출하며, 탭으로 끝나면 좌클릭(CLICK button="left")이다.
     */
    const val SINGLE_POINTER_COUNT = 1

    /**
     * 2손가락 구간으로 판정할 동시 포인터 개수.
     * 이 개수인 구간이 탭으로 끝나면 우클릭(CLICK button="right")이다 (AGENTS.md 섹션 5).
     */
    const val DOUBLE_POINTER_COUNT = 2

    /**
     * 3손가락 구간으로 판정할 동시 포인터 개수.
     *
     * **정확히** 이 개수인 구간에서만 수평 스와이프가 `DESKTOP_SWITCH`로 해석된다(4손가락은 범위 밖).
     * 동시에 "이 개수 **이상**이 한 번이라도 눌렸는가"는 제스처 단위 래치의 조건이기도 하다 —
     * 래치가 걸리면 그 제스처가 끝날 때까지 MOVE/SCROLL/클릭이 전부 억제된다
     * ([MultiTouchGestureTracker][com.example.phone_pad_app.presentation.trackpad.MultiTouchGestureTracker] 참조).
     */
    const val THREE_POINTER_COUNT = 3

    /**
     * 3손가락 수평 스와이프를 데스크톱 전환으로 인정할 최소 이동 거리 (px).
     *
     * 구간 시작 centroid로부터의 **수평** 이동 거리이며, 이 거리를 넘는 순간(손을 뗄 때가 아니라)
     * 곧바로 `DESKTOP_SWITCH`가 한 번 나간다.
     *
     * 기본값 120px의 근거:
     * - 반드시 [TAP_MAX_DISTANCE_PX](20px)보다 **충분히** 커야 한다. 데스크톱 전환은 되돌리기
     *   번거로운 전역 동작이라, 3손가락을 내려놓다 생기는 미세한 어긋남이나 손떨림이 전환으로
     *   새면 안 된다. 탭 한계의 6배로 두어 "의도적으로 쓸었다"는 것이 분명할 때만 발사한다.
     * - 한 구간에 한 번만 발사되므로(반복 전환 금지) 값을 넉넉히 잡아도 연속 전환이
     *   불가능해지지는 않는다 — 손을 떼었다 다시 쓸면 새 구간이 된다.
     */
    const val THREE_FINGER_SWIPE_MIN_DISTANCE_PX = 120f

    /**
     * 3손가락 스와이프를 "수평"으로 인정할 우세 배수 — `|dx| >= 배수 * |dy|` 일 때만 인정한다.
     *
     * 대각선·수직 스와이프(작업 보기 등 다른 제스처의 자리)를 좌/우 전환으로 오인하지 않기 위한
     * 조건이다. 2배는 약 26.6도 안쪽의 스와이프만 수평으로 본다는 뜻이다.
     */
    const val THREE_FINGER_SWIPE_HORIZONTAL_DOMINANCE = 2f

    /**
     * 휠 스크롤 1스텝(노치)에 해당하는 centroid 이동 거리 (px).
     *
     * 2손가락 드래그의 픽셀 이동을 `{"type":"SCROLL","dx":..,"dy":..}`의 정수 스텝으로 나눌 때 쓰는
     * 나눗수다. 값이 작을수록 같은 거리에서 더 많은 스텝이 나가 스크롤이 빨라진다.
     *
     * 기본값 40px의 근거:
     * - 반드시 [TAP_MAX_DISTANCE_PX](20px)보다 커야 한다. 스크롤은 `isDrag`(= 탭 한계 초과) 이후에만
     *   시작되는데, 스텝 단위가 탭 한계보다 작으면 스크롤이 걸리는 바로 그 순간 이미 1스텝 이상이
     *   쌓여 있어서 "손을 대자마자 툭 튀는" 느낌이 된다. 2배로 두면 스크롤 진입이 0스텝에서 시작한다.
     * - Windows에서 휠 1노치는 보통 3줄이므로, 실사용에서 편한 400px 정도의 2손가락 스와이프가
     *   10스텝 ≈ 30줄 ≈ 텍스트 한 화면 분량이 된다.
     * 실기기 체감 튜닝은 별도 과제(Phase 3 감도 설정 UI)로 남긴다.
     *
     * **사용자 조정 가능**([SCROLL_PX_PER_STEP_MIN]..[SCROLL_PX_PER_STEP_MAX]). 이 상수는 기본값의
     * 단일 출처이며, 실제 변환에 쓰이는 값은 트래커가 생성자로 주입받는다.
     */
    const val SCROLL_SENSITIVITY_PX_PER_STEP = 40f

    /**
     * [SCROLL_SENSITIVITY_PX_PER_STEP]의 사용자 조정 하한 (= 가장 빠른 스크롤).
     *
     * **반드시 [TAP_MAX_DISTANCE_PX]보다 커야 한다.** 위 KDoc의 불변식을 슬라이더로도 깨지 못하게
     * 막는 값이다 — 사용자가 하한까지 내려도 "스크롤 진입 순간 이미 1스텝 이상 쌓여 툭 튀는" 현상이
     * 생기지 않아야 한다. 탭 한계(20px)보다 25%만 여유를 둔 값이므로, 탭 한계를 올릴 일이 생기면
     * 이 값도 함께 올려야 한다(`GestureConfigTest`가 고정).
     */
    const val SCROLL_PX_PER_STEP_MIN = 25f

    /** [SCROLL_SENSITIVITY_PX_PER_STEP]의 사용자 조정 상한 (= 가장 느린 스크롤). */
    const val SCROLL_PX_PER_STEP_MAX = 100f

    /**
     * 멀티터치 해제 유예 시간 (ms).
     *
     * 2손가락을 "동시에" 떼는 것은 물리적으로 불가능해서, 실제 기기에서는 거의 항상
     * `2손가락 → 1손가락 → 0` 순으로 이벤트가 들어온다. 마지막 구간만 보면 이 짧은
     * 1손가락 꼬리 때문에 2손가락 탭이 좌클릭으로 뒤집힌다. 마지막 구간이 **포인터 개수 감소로**
     * 시작됐고 이 시간 안에 제스처가 끝났다면, 꼬리를 무시하고 직전(더 많은 손가락) 구간으로 판정한다.
     *
     * 반드시 [TAP_MAX_DURATION_MS]보다 충분히 작아야 한다 — 그렇지 않으면 "손가락 하나를 떼고
     * 남은 손가락으로 탭"하는 정상 동작까지 삼켜버린다.
     */
    const val MULTI_TOUCH_RELEASE_GRACE_MS = 50L

    /**
     * 두 탭을 하나의 더블탭으로 묶을 최대 간격 (ms) — 첫 탭 종료 ~ 둘째 탭 종료.
     *
     * 이 값은 동시에 **모든 1손가락 탭(좌클릭)이 겪는 지연 시간**이기도 하다. 탭이 끝난 즉시
     * CLICK을 보내버리면 뒤이어 오는 두 번째 탭과 병합할 기회가 사라지므로, 이 시간만큼
     * 기다렸다가 두 번째 탭이 없을 때 비로소 CLICK을 보낸다 (`TrackpadScreen`의 지연 클릭 job).
     * 더블클릭을 지원하는 구조에서 피할 수 없는 트레이드오프이며, 값을 키우면 더블탭 인식은
     * 관대해지지만 단일 클릭 체감 지연이 그만큼 늘어난다. 실기기 미검증 — 조정 가능.
     */
    const val DOUBLE_TAP_INTERVAL_MS = 300L

    /**
     * 두 탭의 중심 좌표 사이 최대 허용 거리 (px).
     *
     * [TAP_MAX_DISTANCE_PX](20px)의 2배 — 사람이 같은 자리를 두 번 탭해도 몇 px씩 어긋나므로,
     * "탭 하나로 인정되는 이동 거리"보다 넉넉하게 잡아야 두 번째 탭이 더블탭으로 묶인다.
     * 이 좌표 판정은 폰 화면 좌표계에서만 이루어지며 PC 커서 위치와는 무관하다 —
     * 서버는 커서를 전혀 움직이지 않고 DOUBLE_CLICK 하나만 처리한다 (AGENTS.md 섹션 4).
     */
    const val DOUBLE_TAP_DISTANCE_PX = 40f

    /**
     * 1손가락을 제자리에 유지해야 드래그 홀드로 승격되는 시간 (ms) — `DRAG_START` 전송 시점.
     *
     * [TAP_MAX_DURATION_MS]와 **정확히 같은 값**이어야 하므로 숫자를 따로 적지 않고 참조한다:
     * "탭으로 인정되지 않게 되는 바로 그 시점"이 "드래그 홀드가 되는 시점"과 일치해야
     * 사각지대(탭도 드래그 홀드도 아닌 구간)가 생기지 않는다. 동시에 이 등식 덕분에
     * [MultiTouchGestureTracker][com.example.phone_pad_app.presentation.trackpad.MultiTouchGestureTracker]의
     * 탭 판정(`elapsed < TAP_MAX_DURATION_MS`)과 승격 판정(`elapsed >= DRAG_HOLD_THRESHOLD_MS`)이
     * 구조적으로 상호 배타가 된다.
     *
     * 값을 바꾸려면 탭 판정 시간과 함께 움직여야 한다 — 한쪽만 바꾸면 위 성질이 깨진다.
     */
    const val DRAG_HOLD_THRESHOLD_MS: Long = TAP_MAX_DURATION_MS

    /** PC 서버 기본 포트 (TCP — CLICK/SCROLL/DRAG/HEARTBEAT 및 세션 핸드셰이크) */
    const val DEFAULT_PORT = 9000

    /** PC 서버 UDP 포트 (MOVE 전용) */
    const val UDP_PORT = 9001

    /**
     * 서버 자동 탐색 전용 UDP 포트 (브로드캐스트 DISCOVER / 유니캐스트 SERVER 응답).
     *
     * [UDP_PORT](MOVE 전용)와 **섞지 않는다**. 9001은 세션 토큰으로 인증된 이동 좌표만 받는
     * 채널이고, 탐색은 인증 이전 단계라 같은 LAN의 누구나 보낼 수 있다. 한 포트에 두 성격을
     * 얹으면 "UDP는 MOVE만"이라는 섹션 4 원칙이 무너진다.
     */
    const val DISCOVERY_PORT = 9002

    /**
     * 탐색 1회의 총 수신 창 (ms) — 첫 브로드캐스트부터 결과를 확정하기까지.
     *
     * 같은 LAN 안의 서버는 보통 수 ms 안에 응답하므로 1.5초는 두 자릿수 여유다. 이 값은
     * 동시에 "서버를 못 찾았다"고 사용자에게 말하기까지의 대기 시간이기도 해서, 체감을 해치지
     * 않는 선(수동 IP 입력이라는 fallback이 늘 있다)에서 짧게 잡았다.
     * 반드시 마지막 재전송 시각([DISCOVERY_PROBE_COUNT] · [DISCOVERY_PROBE_INTERVAL_MS])보다
     * 충분히 커야 한다 — 마지막 패킷을 보내고 답을 기다릴 시간이 남지 않으면 재전송이 무의미하다
     * (`GestureConfigTest`가 강제).
     */
    const val DISCOVERY_TIMEOUT_MS = 1500L

    /**
     * 한 번의 탐색에서 DISCOVER를 브로드캐스트하는 횟수.
     *
     * UDP는 재전송이 없어 브로드캐스트 한 발은 AP가 조용히 버리면 그대로 유실된다.
     * 3회면 단발 유실 확률이 사실상 사라지고, 패킷 수는 (대상 주소 수 × 3)으로 여전히 무시할 수준이다.
     */
    const val DISCOVERY_PROBE_COUNT = 3

    /** 재전송 간격 (ms) — 0 / 300 / 600ms 시점에 보낸다. */
    const val DISCOVERY_PROBE_INTERVAL_MS = 300L

    /**
     * 수신 루프 1회의 소켓 타임아웃 (ms).
     *
     * 블로킹 `receive()`는 코루틴 취소로 풀리지 않는다. 짧게 끊어 반복해야 취소 확인과
     * 재전송 시각 확인을 할 수 있고, 화면을 떠난 뒤에도 소켓이 남아 있는 시간이 이 값으로 제한된다.
     * 너무 짧으면 의미 없는 깨어남만 늘어나므로 총 창의 1/7 수준으로 둔다.
     */
    const val DISCOVERY_RECEIVE_POLL_MS = 200

    /**
     * 목록에 담을 최대 서버 수.
     *
     * 가정용 LAN에 Phone Pad 서버가 8대를 넘을 일은 없다. 상한을 두는 진짜 이유는 응답을
     * 위조하는 쪽이 목록을 무한히 부풀려 화면과 메모리를 채우지 못하게 하기 위해서다.
     */
    const val DISCOVERY_MAX_RESULTS = 8

    /**
     * 표시용 서버 이름의 최대 길이 (자).
     *
     * 서버도 같은 값으로 자르지만([DISCOVERY_PORT] 스펙), 클라이언트는 **서버를 믿지 않는다** —
     * 응답은 누구나 위조할 수 있으므로 화면을 밀어내는 긴 이름은 이쪽에서도 자른다.
     */
    const val DISCOVERY_MAX_NAME_LENGTH = 64

    /**
     * TCP 연결(3-way handshake)을 포기하기까지의 최대 시간 (ms).
     *
     * 이 값이 없으면(= `Socket(host, port)`) OS 기본 타임아웃에 맡기게 되는데, Android에서는
     * 흔히 20초 이상이라 **오타 난 IP나 꺼져 있는 PC**를 입력했을 때 "연결 중..." 화면에
     * 수십 초 동안 갇힌다. 그래서 명시적으로 짧게 끊는다.
     *
     * 5초의 근거:
     * - 같은 LAN 안의 살아있는 서버는 3-way handshake가 보통 수 ms ~ 수십 ms다. 5초는 그보다
     *   두 자릿수 넉넉하다.
     * - 혼잡한 Wi-Fi에서 첫 SYN이 유실되어도 TCP 초기 재전송(RTO ≈ 1초, 이후 2초)까지는
     *   덮어야 "가끔 실패하는" 연결이 되지 않는다. 5초면 재전송 2~3회를 포함한다.
     * - 실패했을 때 사용자가 IP를 고쳐 다시 시도하기까지의 체감 대기를 짧게 유지한다.
     *
     * [SESSION_HANDSHAKE_TIMEOUT_MS](3초)와는 **별개로 직렬 적용**된다 — 최악의 경우
     * 연결 5초 + 핸드셰이크 3초 = 8초 뒤에 실패가 확정된다. 그 전에라도 사용자가
     * "취소"로 빠져나올 수 있다(`ConnectingPanel`).
     */
    const val CONNECT_TIMEOUT_MS = 5000

    /** TCP 연결 직후 세션 핸드셰이크 한 줄을 기다리는 최대 시간 (ms) */
    const val SESSION_HANDSHAKE_TIMEOUT_MS = 3000

    /**
     * AUTH 줄에 실어 보낼 PIN 문자열의 최대 길이 (자).
     *
     * 서버가 생성하는 PIN은 6자리 숫자이므로 실사용에는 전혀 걸리지 않는다. 이 상한의 목적은
     * **와이어 크기 방어**다 — 입력란에 붙여넣기 등으로 아주 긴 문자열이 들어와도 그대로
     * 첫 줄에 실려 나가지 않도록 클라이언트가 전송 전에 자른다
     * ([AuthHandshake][com.example.phone_pad_app.data.network.AuthHandshake.buildAuthLine]).
     * 서버는 어차피 불일치로 거부하므로 자르는 것이 값을 바꿔 보내는 것보다 나쁘지 않다.
     */
    const val AUTH_PIN_MAX_LENGTH = 32

    /**
     * TCP heartbeat 전송 주기 (ms).
     * 연결 유지 중 이 간격으로 `{"type":"HEARTBEAT"}`를 보내고,
     * 동일한 값을 소켓 `soTimeout`으로 사용해 수신 루프의 1회 대기 시간으로 삼는다.
     */
    const val HEARTBEAT_INTERVAL_MS = 5000L

    /** 연속 미응답(읽기 타임아웃) 허용 횟수 — 이 횟수에 도달하면 연결이 끊긴 것으로 판정한다. */
    const val HEARTBEAT_MISS_LIMIT = 3

    /**
     * 연결 유실 후 자동 재연결을 포기하기까지의 총 시도 횟수.
     *
     * 아래 백오프 기본값과 함께 총 대기 시간이 결정된다: 1+2+4+8+10+10+10+10 = 55초.
     * WiFi AP 재연결(보통 수 초 ~ 20초)과 PC 서버 재시작(수 초)을 덮을 만큼 길되,
     * 사용자를 무한정 "재연결 중" 화면에 가두지 않는 선으로 잡은 값이다. 소진되면
     * `Error("Reconnect failed: ...")`로 넘어가 수동 연결 화면을 준다.
     */
    const val RECONNECT_MAX_ATTEMPTS = 8

    /**
     * 첫 재연결 시도 전 대기 시간 (ms). 이후 시도마다 2배씩 늘어난다(지수 백오프).
     *
     * 0이 아닌 이유: 유실 직후에는 소켓/AP가 아직 정리되지 않은 경우가 많아 즉시 재시도는
     * 거의 항상 실패한다. 1초는 "사용자가 기다린다고 느끼지 않는" 상한에 가깝다.
     */
    const val RECONNECT_BASE_DELAY_MS = 1_000L

    /**
     * 재연결 백오프 지연의 상한 (ms).
     *
     * 지수 증가를 그대로 두면 8회째 대기가 128초가 되어 "복구됐는데도 한참 못 붙는" 구간이
     * 생긴다. [HEARTBEAT_INTERVAL_MS]의 2배 = 서버가 옛 세션을 타임아웃으로 회수하는
     * 시간(5초 × 3회 ≈ 15초)과 같은 자릿수라, 상한에 걸린 재시도들은 옛 세션이 회수된
     * 뒤에도 충분히 여러 번 문을 두드린다.
     */
    const val RECONNECT_MAX_DELAY_MS = 10_000L
}
