package com.example.phone_pad_app.di

import com.example.phone_pad_app.data.repository.DataStoreSettingsRepository
import com.example.phone_pad_app.data.repository.TrackpadRepositoryImpl
import com.example.phone_pad_app.domain.repository.SettingsRepository
import com.example.phone_pad_app.domain.repository.TrackpadRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindTrackpadRepository(
        impl: TrackpadRepositoryImpl
    ): TrackpadRepository

    /** 감도 설정 저장소 — DataStore 인스턴스 자체는 [DataStoreModule]이 제공한다. */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: DataStoreSettingsRepository
    ): SettingsRepository
}
