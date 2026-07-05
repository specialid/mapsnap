package com.jason.mapsnap

import android.app.Application
import com.naver.maps.map.NaverMapSdk
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import com.jason.mapsnap.BuildConfig

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        val sdk = NaverMapSdk.getInstance(this)
        sdk.client = NaverMapSdk.NcpKeyClient(BuildConfig.NAVER_CLIENT_ID)

        // 진단: 인증 실패 시 정확한 코드/메시지 출력
        sdk.setOnAuthFailedListener { exception ->
            Timber.e(
                "=== 인증 실패: code=${exception.errorCode}, " +
                    "message=${exception.message}, " +
                    "type=${exception.javaClass.simpleName} ==="
            )
        }
    }
}
