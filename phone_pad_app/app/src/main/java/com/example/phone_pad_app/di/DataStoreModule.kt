package com.example.phone_pad_app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.phone_pad_app.data.repository.DataStoreSettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * 감도 설정 DataStore 제공.
 *
 * `preferencesDataStore` 프로퍼티 위임(Context 확장) 대신 [PreferenceDataStoreFactory]를 직접 쓰는
 * 이유: 위임은 인스턴스를 파일 단위 전역에 숨겨 두기 때문에 Hilt의 `@Singleton`과 수명 주체가
 * 둘로 갈린다. 팩토리로 만들면 "싱글톤 하나만 존재한다"는 보장이 DI 그래프 안에서 끝난다
 * (DataStore는 같은 파일에 인스턴스가 둘이면 런타임에 예외를 던진다).
 *
 * 파일이 손상된 경우엔 빈 Preferences로 복구한다 — 감도 설정은 언제든 기본값으로 되돌려도
 * 사용자가 잃는 것이 거의 없는 데이터이고, 여기서 예외를 던지면 앱이 아예 못 뜬다.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = {
            context.preferencesDataStoreFile(DataStoreSettingsRepository.PREFERENCES_NAME)
        },
    )
}
