package com.example.phone_pad_app.domain.model

/**
 * UDP 브로드캐스트 탐색으로 발견한 PC 서버 한 대.
 *
 * @param name 표시용 이름(서버의 PC 호스트명). 화면에 그리는 용도일 뿐 연결에는 쓰지 않는다.
 *   위조 가능한 값이므로 길이·제어문자는 파서가 이미 정리한 뒤 들어온다.
 * @param host 서버 주소. **응답 본문이 아니라 응답 패킷의 발신 주소**다 —
 *   멀티 NIC/VPN 환경에서 서버가 자기 IP를 잘못 추정하는 문제를 피하기 위한 확정 스펙이다.
 * @param port 서버의 **TCP** 포트. UDP(MOVE) 포트는 응답에 담기지 않으므로 앱은 기존처럼
 *   [GestureConfig.UDP_PORT][com.example.phone_pad_app.presentation.util.GestureConfig.UDP_PORT]를 쓴다.
 */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
)
