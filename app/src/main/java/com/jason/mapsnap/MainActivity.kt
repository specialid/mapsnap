package com.jason.mapsnap

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jason.mapsnap.presentation.component.MapScreen
import com.jason.mapsnap.ui.theme.MapSnapTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 진단: 매니페스트에 주입된 실제 CLIENT_ID 확인
        try {
            val meta = packageManager
                .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                .metaData
            val clientId = meta?.getString("com.naver.maps.map.CLIENT_ID") ?: "NOT_FOUND"
            Log.d("NaverDebug", "=== Naver Client ID in Manifest: '$clientId' ===")
            Log.d("NaverDebug", "=== BuildConfig Client ID: '${BuildConfig.NAVER_CLIENT_ID}' ===")
        } catch (e: Exception) {
            Log.e("NaverDebug", "Meta-data 읽기 실패", e)
        }

        enableEdgeToEdge()
        setContent {
            MapSnapTheme {
                MapScreen()
            }
        }
    }
}
