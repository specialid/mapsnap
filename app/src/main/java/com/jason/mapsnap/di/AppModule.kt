package com.jason.mapsnap.di

import com.jason.mapsnap.data.repository.RouteRepositoryImpl
import com.jason.mapsnap.data.repository.SavedRouteRepositoryImpl
import com.jason.mapsnap.data.repository.SettingsRepositoryImpl
import com.jason.mapsnap.domain.repository.RouteRepository
import com.jason.mapsnap.domain.repository.SavedRouteRepository
import com.jason.mapsnap.domain.repository.SettingsRepository
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
    abstract fun bindRouteRepository(impl: RouteRepositoryImpl): RouteRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindSavedRouteRepository(impl: SavedRouteRepositoryImpl): SavedRouteRepository
}
