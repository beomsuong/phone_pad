package com.example.phone_pad_app.di

import com.example.phone_pad_app.domain.model.ReconnectPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 자동 재연결 정책 제공.
 *
 * 값 객체라 `@Inject` 생성자를 붙일 수 없어(기본값을 가진 data class) 모듈로 제공한다.
 * 테스트는 이 모듈을 거치지 않고 생성자에 직접 원하는 정책(예: [ReconnectPolicy.Disabled])을 넘긴다.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReconnectModule {

    @Provides
    @Singleton
    fun provideReconnectPolicy(): ReconnectPolicy = ReconnectPolicy.Default
}
