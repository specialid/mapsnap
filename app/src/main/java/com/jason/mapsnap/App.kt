package com.jason.mapsnap

import android.app.Application
import android.util.Log
import com.naver.maps.map.NaverMapSdk
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val sdk = NaverMapSdk.getInstance(this)
        sdk.client = NaverMapSdk.NcpKeyClient(BuildConfig.NAVER_CLIENT_ID)

        // 진단: 인증 실패 시 정확한 코드/메시지 출력
        sdk.setOnAuthFailedListener { exception ->
            Log.e(
                "NaverAuth",
                "=== 인증 실패: code=${exception.errorCode}, " +
                    "message=${exception.message}, " +
                    "type=${exception.javaClass.simpleName} ==="
            )
        }
    }
}
