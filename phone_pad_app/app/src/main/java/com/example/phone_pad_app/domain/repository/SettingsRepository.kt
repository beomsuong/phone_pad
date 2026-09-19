package com.example.phone_pad_app.domain.repository

import com.example.phone_pad_app.domain.model.GestureSettings
import kotlinx.coroutines.flow.Flow

/**
 * 감도 설정의 영속 저장소.
 *
 * 구현(`DataStoreSettingsRepository`)이 DataStore를 쓰지만 이 인터페이스는 그 사실을 전혀 드러내지
 * 않는다 — ViewModel과 제스처 계층은 "설정 스트림"만 알면 되고, 저장 매체는 data 계층의 사정이다.
 *
 * [settings]는 **항상 유효한 값**을 방출한다(범위 밖/손상된 값은 구현이 보정한다). 저장된 값이
 * 없으면 [GestureSettings.DEFAULT]를 방출한다 — 비어 있음을 호출부가 따로 처리할 필요는 없다.
 */
interface SettingsRepository {

    /** 현재 설정. 값이 바뀌면 다시 방출된다(설정 화면에서 바꾸면 트랙패드 화면도 함께 갱신). */
    val settings: Flow<GestureSettings>

    /** 설정을 저장한다. 범위 밖 값이 들어와도 구현이 보정해서 저장한다. */
    suspend fun update(settings: GestureSettings)

    /** 저장된 값을 모두 지워 [GestureSettings.DEFAULT] 상태로 되돌린다 ("기본값으로 복원"). */
    suspend fun reset()
}
