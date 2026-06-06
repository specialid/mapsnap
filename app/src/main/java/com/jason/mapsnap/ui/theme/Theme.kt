package com.jason.mapsnap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 편집 강조색(주황)을 브랜드 primary 로 승격하여 버튼/강조 UI 색을 통일
private val MapSnapColors = lightColorScheme(
    primary = Color(0xFFE65100),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color.White
)

@Composable
fun MapSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MapSnapColors,
        content = content
    )
}
