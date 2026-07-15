package com.jason.mapsnap.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jason.mapsnap.presentation.map.DrawingMode
import com.jason.mapsnap.ui.theme.MapOverlayColors

@Composable
fun BottomControls(
    drawingMode: DrawingMode,
    hasPendingEdits: Boolean,
    canUndo: Boolean,
    totalDistanceMeters: Double,
    routeMarkersCount: Int,
    onApplyEdits: () -> Unit,
    onUndo: () -> Unit,
    onDrawToggle: () -> Unit,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    onExportGpx: () -> Unit,
    isMapMoveMode: Boolean = false,
    onToggleMapMoveMode: () -> Unit = {},
    onCancelProcessing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 지도 이동 모드 시 카드 테두리를 주황색으로 강조해 현재 상태를 화면 수준에서 알림
    val cardBorder = if (isMapMoveMode && drawingMode == DrawingMode.DRAWING) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MapOverlayColors.cardBorder)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MapOverlayColors.cardBackground
        ),
        shape = RoundedCornerShape(20.dp),
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (drawingMode) {
                DrawingMode.IDLE -> {
                    // IDLE: 안내 텍스트 위 + 그리기 시작 버튼 전폭 48dp
                    Text(
                        text = "손가락으로 지도 위에 경로를 그려보세요",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onDrawToggle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "그리기 시작",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("그리기 시작", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                DrawingMode.DRAWING -> {
                    // Row 1: 상태 텍스트 + 지도 이동 토글
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMapMoveMode) "지도를 조작할 수 있습니다" else "지도 위에 선을 그려주세요",
                            color = if (isMapMoveMode) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = onToggleMapMoveMode,
                            border = BorderStroke(
                                1.dp,
                                if (isMapMoveMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isMapMoveMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
                                contentColor = if (isMapMoveMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.height(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(if (isMapMoveMode) "그리기 전환" else "지도 이동", fontSize = 12.sp)
                        }
                    }

                    // Row 2: 취소(보조, weight 1) + 완료(주, weight 2, 48dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onClear,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("취소", fontSize = 14.sp)
                        }
                        Button(
                            onClick = onDrawToggle,
                            modifier = Modifier
                                .weight(2f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("완료", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                DrawingMode.PROCESSING -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "도로 스냅 경로 계산 중...",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        OutlinedButton(
                            onClick = onCancelProcessing,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.height(36.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("취소", fontSize = 13.sp)
                        }
                    }
                }

                DrawingMode.DONE -> {
                    val distanceStr = if (totalDistanceMeters >= 1000) {
                        String.format(java.util.Locale.getDefault(), "%.2f km", totalDistanceMeters / 1000.0)
                    } else {
                        String.format(java.util.Locale.getDefault(), "%d m", totalDistanceMeters.toInt())
                    }
                    val estimatedTimeMin = (totalDistanceMeters / 66.67).toInt()
                    val timeStr = if (estimatedTimeMin >= 60) {
                        "${estimatedTimeMin / 60}시간 ${estimatedTimeMin % 60}분"
                    } else {
                        "${estimatedTimeMin}분"
                    }

                    // Row 1: 통계 3열
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "거리", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = distanceStr, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(MapOverlayColors.cardBorder))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "예상 시간", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = timeStr, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(MapOverlayColors.cardBorder))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "마커 수", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = "${routeMarkersCount}개", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = MapOverlayColors.dividerFaint)

                    // Row 2: 주 액션 버튼
                    if (hasPendingEdits) {
                        Button(
                            onClick = onApplyEdits,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "수정 완료 적용",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("수정 완료 적용", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onContinue,
                                modifier = Modifier.weight(1f).height(48.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "이어 그리기",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("이어 그리기")
                            }
                            Button(
                                onClick = onExportGpx,
                                modifier = Modifier.weight(1f).height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "GPX 저장",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("GPX 저장")
                            }
                        }
                    }

                    // Row 3: 유틸 버튼 — 실행 취소(좌) / 다시 그리기(가운데) / 지우기(우) 3분할 이격
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 좌: 실행 취소 (있을 때만)
                        Box(modifier = Modifier.weight(1f)) {
                            if (canUndo) {
                                TextButton(
                                    onClick = onUndo,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Undo,
                                        contentDescription = "실행 취소",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("실행 취소", fontSize = 13.sp)
                                }
                            }
                        }
                        // 가운데: 다시 그리기
                        TextButton(
                            onClick = onDrawToggle,
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                        ) {
                            Text("다시 그리기", fontSize = 13.sp)
                        }
                        // 우: 지우기 (에러색, 의도적 이격)
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            TextButton(
                                onClick = onClear,
                                modifier = Modifier.heightIn(min = 48.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("지우기", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
