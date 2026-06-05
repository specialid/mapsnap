package com.jason.mapsnap.di

import com.jason.mapsnap.data.repository.DeviceUsageRepositoryImpl
import com.jason.mapsnap.domain.repository.DeviceUsageRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseModule {

    @Binds
    @Singleton
    abstract fun bindDeviceUsageRepository(
        impl: DeviceUsageRepositoryImpl
    ): DeviceUsageRepository
}
