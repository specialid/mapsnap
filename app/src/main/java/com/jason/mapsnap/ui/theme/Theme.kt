package com.jason.mapsnap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 지도 위에 항상 반투명 다크 글래스 카드로 오버레이되는 UI라 시스템 라이트/다크와 무관하게 다크 스킴 고정
private val MapSnapColors = darkColorScheme(
    primary = Color(0xFFE65100),
    onPrimary = Color.White,
    secondary = Color(0xFFFFB300), // 편집 대기(pending) 강조색
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color.White,
    error = Color(0xFFE53935),
    onError = Color.White,
    background = Color(0xFF1F1F23),
    onBackground = Color.White,
    surface = Color(0xFF1F1F23),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0BEC5), // 카드 내 보조 텍스트
    outline = Color(0x55FFFFFF) // OutlinedButton 등 기본 테두리
)

/** 지도 오버레이 카드/구분선 전용 색상 — 반투명도가 핵심이라 MaterialTheme ColorScheme 슬롯에 억지로 맞추지 않음 */
object MapOverlayColors {
    val cardBackground = Color(0xCC1F1F23)
    val cardBorder = Color(0x33FFFFFF)
    val dividerFaint = Color(0x1AFFFFFF)
    val scrimStrong = Color(0xE61F1F23)
    val primaryOutline = Color(0xFFBF360C) // 선택된 마커/경로 강조색(primary)의 진한 테두리
}

@Composable
fun MapSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MapSnapColors,
        content = content
    )
}
