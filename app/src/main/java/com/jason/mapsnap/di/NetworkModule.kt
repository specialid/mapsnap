package com.jason.mapsnap.di

import com.jason.mapsnap.data.remote.OrsService
import com.jason.mapsnap.data.remote.TmapService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = if (com.jason.mapsnap.BuildConfig.DEBUG)
                    HttpLoggingInterceptor.Level.BASIC
                else
                    HttpLoggingInterceptor.Level.NONE
            }
        )
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://apis.openapi.sk.com/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideTmapService(retrofit: Retrofit): TmapService =
        retrofit.create(TmapService::class.java)

    @Provides
    @Singleton
    @OrsEngine
    fun provideOrsRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        // api.openrouteservice.org는 2026-04-28 deprecated, 2026-08-24 차단 예정 (HeiGIT 공지)
        .baseUrl("https://api.heigit.org/openrouteservice/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides
    @Singleton
    fun provideOrsService(@OrsEngine retrofit: Retrofit): OrsService =
        retrofit.create(OrsService::class.java)
}
