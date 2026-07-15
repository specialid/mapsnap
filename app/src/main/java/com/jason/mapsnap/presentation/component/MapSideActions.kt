package com.jason.mapsnap.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jason.mapsnap.BuildConfig
import com.jason.mapsnap.presentation.map.DrawingMode
import com.jason.mapsnap.ui.theme.MapOverlayColors

// API 잔여 횟수 임박 경고색 — 브랜드 primary(주황)와 구분되는 별도 경고 색상이라 테마 토큰화하지 않음
private val QuotaWarningColor = Color(0xFFFF9800)
private val DebugOkColor = Color(0xFF4CAF50)

/** 화면 우측 상단: 남은 API 호출 횟수 카드 + 지도 타입 토글/경로 저장 FAB */
@Composable
fun MapSideActions(
    drawingMode: DrawingMode,
    tmapApiCallCount: Int,
    tmapMaxLimitCount: Int,
    naverMapApiCallCount: Int,
    onToggleMapType: () -> Unit,
    onOpenSaveRouteDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // DRAWING/PROCESSING 중에는 지도 면적 확보를 위해 카드 숨김
        AnimatedVisibility(
            visible = drawingMode == DrawingMode.IDLE || drawingMode == DrawingMode.DONE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            run {
                val remaining = (tmapMaxLimitCount - tmapApiCallCount).coerceAtLeast(0)
                val quotaColor = when {
                    remaining <= 0 -> MaterialTheme.colorScheme.error // 소진: 빨강
                    remaining <= 5 -> QuotaWarningColor // 임박: 주황
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MapOverlayColors.cardBackground
                    ),
                    border = BorderStroke(1.dp, MapOverlayColors.cardBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "남은 경로 분석",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(quotaColor, shape = CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "오늘 남은 횟수: ",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${remaining} / ${tmapMaxLimitCount}",
                                color = quotaColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        if (BuildConfig.DEBUG) {
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(DebugOkColor, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Naver Map API: ",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${naverMapApiCallCount} 회",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        } // AnimatedVisibility 닫기

        // Map Type Toggle FAB
        FloatingActionButton(
            onClick = onToggleMapType,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Layers,
                contentDescription = "지도 타입 변경",
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        // 경로 저장 FAB — 완성된 경로가 있을 때(DONE)만 노출
        AnimatedVisibility(
            visible = drawingMode == DrawingMode.DONE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FloatingActionButton(
                onClick = onOpenSaveRouteDialog,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "경로 저장",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
