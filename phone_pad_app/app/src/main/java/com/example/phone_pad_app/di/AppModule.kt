package com.example.phone_pad_app.di

import com.example.phone_pad_app.data.repository.TrackpadRepositoryImpl
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
}
