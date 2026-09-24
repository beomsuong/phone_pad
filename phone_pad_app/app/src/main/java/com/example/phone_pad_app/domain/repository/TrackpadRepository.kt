package com.example.phone_pad_app.domain.repository

import com.example.phone_pad_app.domain.model.ConnectionState
import com.example.phone_pad_app.domain.model.TrackpadEvent
import kotlinx.coroutines.flow.Flow

interface TrackpadRepository {
    val connectionState: Flow<ConnectionState>

    /**
     * @param pin 사용자가 입력한 PIN. TCP 연결 직후 AUTH 한 줄로 **항상** 전송된다
     *   (서버 인증이 꺼져 있어도 형식은 같다 — AGENTS.md 섹션 4). 자동 재연결도 같은 값을
     *   다시 쓴다.
     */
    suspend fun connect(host: String, port: Int, pin: String)

    /**
     * 진행 중인 **첫 연결 시도**를 사용자가 취소한다.
     *
     * [disconnect]와 다른 점:
     * - [ConnectionState.Connecting] 상태에서만 동작한다. 이미 붙은 연결이나 자동 재연결은
     *   건드리지 않는다(재연결 취소는 그대로 [disconnect]가 담당한다).
     * - 실패가 아니므로 [ConnectionState.Error]가 아니라 [ConnectionState.Disconnected]로
     *   되돌아간다 — 사용자가 스스로 그만둔 것을 오류로 보고하면 안 된다.
     *
     * 취소된 시도가 뒤늦게 성공하거나 실패해도 상태를 덮어쓰지 않는 것이 구현 계약이다.
     */
    suspend fun cancelConnect()

    suspend fun sendEvent(event: TrackpadEvent)
    suspend fun disconnect()
}
