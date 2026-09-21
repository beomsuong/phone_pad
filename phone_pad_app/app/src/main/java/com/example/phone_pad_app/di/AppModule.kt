package com.example.phone_pad_app.di

import com.example.phone_pad_app.data.repository.DataStoreSettingsRepository
import com.example.phone_pad_app.data.repository.ServerDiscoveryRepositoryImpl
import com.example.phone_pad_app.data.repository.TrackpadRepositoryImpl
import com.example.phone_pad_app.domain.repository.ServerDiscoveryRepository
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

    /** 서버 자동 탐색(UDP 9002 브로드캐스트) 저장소. 연결 저장소와 별개다. */
    @Binds
    @Singleton
    abstract fun bindServerDiscoveryRepository(
        impl: ServerDiscoveryRepositoryImpl
    ): ServerDiscoveryRepository
}
