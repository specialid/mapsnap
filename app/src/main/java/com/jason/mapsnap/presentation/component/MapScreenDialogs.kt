package com.jason.mapsnap.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jason.mapsnap.domain.model.SavedRoute
import kotlinx.coroutines.delay

// 마커/드로잉 정밀도 5단계 라벨 및 degree 매핑 — 설정 다이얼로그 전용
private val DRAWN_LEVELS = listOf(0.00005, 0.0000925, 0.000135, 0.0002175, 0.0003)
private val ROUTE_LEVELS = listOf(0.00002, 0.000046, 0.000072, 0.000111, 0.00015)
private val LEVEL_LABELS = listOf("매우 약하게", "약하게", "보통", "강하게", "매우 강하게")

/** 현재 degree 값에 가장 가까운 단계 인덱스(0..4) 반환 */
private fun nearestLevelIndex(value: Double, levels: List<Double>): Int {
    var best = 0
    var bestDiff = Double.MAX_VALUE
    levels.forEachIndexed { i, lv ->
        val diff = kotlin.math.abs(lv - value)
        if (diff < bestDiff) { bestDiff = diff; best = i }
    }
    return best
}

@Composable
fun SettingsDialog(
    markerIntervalMeters: Double,
    epsilonDrawnDeg: Double,
    epsilonRouteDeg: Double,
    includeTimestamps: Boolean,
    runningPaceSecPerKm: Int,
    orsApiKey: String,
    onDismiss: () -> Unit,
    onOpenOrsGuide: () -> Unit,
    onApply: (
        markerIntervalMeters: Double,
        epsilonDrawnDeg: Double,
        epsilonRouteDeg: Double,
        includeTimestamps: Boolean,
        runningPaceSecPerKm: Int,
        orsApiKey: String
    ) -> Unit
) {
    var tempInterval by remember { mutableStateOf(markerIntervalMeters) }
    var tempDrawnLevel by remember { mutableStateOf(nearestLevelIndex(epsilonDrawnDeg, DRAWN_LEVELS)) }
    var tempRouteLevel by remember { mutableStateOf(nearestLevelIndex(epsilonRouteDeg, ROUTE_LEVELS)) }
    var tempIncludeTimestamps by remember { mutableStateOf(includeTimestamps) }
    var tempPaceMinutes by remember { mutableStateOf((runningPaceSecPerKm / 60).toString()) }
    var tempPaceSeconds by remember { mutableStateOf((runningPaceSecPerKm % 60).toString()) }
    var tempOrsApiKey by remember { mutableStateOf(orsApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("설정", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text("마커 생성 간격: ${tempInterval.toInt()}m", fontSize = 14.sp)
                    Slider(
                        value = tempInterval.toFloat(),
                        onValueChange = { newValue ->
                            tempInterval = (Math.round(newValue / 10.0) * 10.0).coerceIn(30.0, 150.0)
                        },
                        valueRange = 30f..150f,
                        steps = 11
                    )
                }
                Column {
                    Text("그리기 정밀도: ${LEVEL_LABELS[tempDrawnLevel]}", fontSize = 14.sp)
                    Slider(
                        value = tempDrawnLevel.toFloat(),
                        onValueChange = { tempDrawnLevel = it.toInt().coerceIn(0, 4) },
                        valueRange = 0f..4f,
                        steps = 3
                    )
                }
                Column {
                    Text("경로 정밀도: ${LEVEL_LABELS[tempRouteLevel]}", fontSize = 14.sp)
                    Slider(
                        value = tempRouteLevel.toFloat(),
                        onValueChange = { tempRouteLevel = it.toInt().coerceIn(0, 4) },
                        valueRange = 0f..4f,
                        steps = 3
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("타임스탬프 포함", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("일부 앱이 시각 정보를 요구할 때만 켜세요", fontSize = 12.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = tempIncludeTimestamps,
                        onCheckedChange = { tempIncludeTimestamps = it }
                    )
                }
                if (tempIncludeTimestamps) {
                    Column {
                        Text("러닝 페이스 (분:초)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = tempPaceMinutes,
                                onValueChange = {
                                    if (it.isEmpty() || it.all { c -> c.isDigit() }) {
                                        tempPaceMinutes = it.take(2)
                                    }
                                },
                                label = { Text("분", fontSize = 12.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                singleLine = true
                            )
                            Text(":", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            TextField(
                                value = tempPaceSeconds,
                                onValueChange = {
                                    if (it.isEmpty() || it.all { c -> c.isDigit() }) {
                                        tempPaceSeconds = it.take(2)
                                    }
                                },
                                label = { Text("초", fontSize = 12.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                singleLine = true
                            )
                        }
                        Text("범위: 4:00~8:00 (km당)", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                HorizontalDivider(color = Color(0x1AFFFFFF))
                Text(
                    "고급",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFB0BEC5)
                )
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("내 ORS API 키", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = onOpenOrsGuide) {
                            Text("발급 방법", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "비워두면 앱 기본 키를 함께 씁니다. 여러 명이 앱 기본 키를 같이 쓰면 한도가 금방 찰 수 있으니, 본인 키를 입력하는 걸 권장합니다.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    TextField(
                        value = tempOrsApiKey,
                        onValueChange = { tempOrsApiKey = it.trim() },
                        placeholder = { Text("발급받은 키를 붙여넣으세요") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(
                    onClick = {
                        tempInterval = 80.0
                        tempDrawnLevel = 2
                        tempRouteLevel = 2
                        tempIncludeTimestamps = false
                        tempPaceMinutes = "6"
                        tempPaceSeconds = "0"
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("기본값 복원")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minVal = tempPaceMinutes.toIntOrNull() ?: 6
                val secVal = tempPaceSeconds.toIntOrNull() ?: 0
                val totalSec = (minVal * 60 + secVal).coerceIn(240, 480)
                onApply(
                    tempInterval,
                    DRAWN_LEVELS[tempDrawnLevel],
                    ROUTE_LEVELS[tempRouteLevel],
                    tempIncludeTimestamps,
                    totalSec,
                    tempOrsApiKey
                )
            }) {
                Text("적용")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

/** ORS API 키 발급 방법 안내 다이얼로그 */
@Composable
fun OrsGuideDialog(
    onDismiss: () -> Unit,
    onOpenSignupPage: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ORS API 키 발급 방법", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("1. openrouteservice.org 접속 후 우측 상단 Sign Up으로 무료 회원가입", fontSize = 14.sp)
                Text("2. 가입 후 Dashboard(대시보드) 페이지로 이동", fontSize = 14.sp)
                Text("3. \"Request a token\" 버튼 클릭 → 서비스로 \"Directions\" 선택 후 토큰 생성", fontSize = 14.sp)
                Text("4. 생성된 토큰(긴 문자열)을 복사", fontSize = 14.sp)
                Text("5. MapSnap 설정 화면의 \"내 ORS API 키\"란에 붙여넣고 적용", fontSize = 14.sp)
                Text(
                    "무료 키는 분당 40회, 일 2,000회까지 무료로 사용할 수 있습니다.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSignupPage) {
                Text("가입 페이지 열기")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("도움말", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("• 경로 그리기: 화면의 그리기 버튼을 누르고 손가락으로 지도 위에 선을 그리면 보행자 도로로 자동 스냅됩니다.", fontSize = 14.sp)
                Text("• 마커 이동: 편집 모드(DONE)에서 마커를 탭한 후, 주황색 드래그 핸들을 눌러서 끌면 위치를 수정할 수 있습니다.", fontSize = 14.sp)
                Text("• 출발(S) 및 도착(G) 마커: 양 끝 마커를 드래그하여 이동시키면 경로가 그에 맞게 단축되거나 수정됩니다.", fontSize = 14.sp)
                Text("• 마커/구간 삭제: 중간 마커를 탭해 선택한 뒤 마커 오른쪽에 나타나는 빨간 휴지통 버튼을 누르면 마커가 삭제됩니다. 두 마커 사이의 파란색 경로(구간)를 탭하여 삭제할 수도 있습니다.", fontSize = 14.sp)
                Text("• 이어 그리기: [+] 버튼을 누르면 기존 경로 끝에서부터 이어서 선을 그릴 수 있습니다.", fontSize = 14.sp)
                Text("• 일괄 적용: 마커를 수정한 후 [적용] 버튼을 눌러 ORS 실도로 스냅을 최종 업데이트합니다.", fontSize = 14.sp)
                Text("• 실행 취소(Undo): 마커 조작 실수를 했을 때 [실행 취소] 버튼을 통해 이전 상태로 되돌릴 수 있습니다.", fontSize = 14.sp)
                Text("• GPX 내보내기: 우측 [GPX] 버튼으로 경로를 gpx 파일로 스마트폰에 저장할 수 있습니다.", fontSize = 14.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        }
    )
}

@Composable
fun InfoDialog(versionCode: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("정보", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MapSnap", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("버전 $versionCode", fontSize = 14.sp)
                Text("보행자 중심 도로 스냅 및 편집 애플리케이션", fontSize = 14.sp)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Powered by openrouteservice(OSM) & Naver Map SDK", fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        }
    )
}

/**
 * 삭제/초기화류 확인 다이얼로그 공통 컴포저블
 * (구간 삭제, 마커 삭제, 전체 삭제, 다시 그리기, 광고 시청 유도에서 재사용)
 */
@Composable
fun MapConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmBold: Boolean = false,
    dismissLabel: String = "취소"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = confirmColor,
                    fontWeight = if (confirmBold) FontWeight.Bold else FontWeight.Normal
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        }
    )
}

@Composable
fun SaveRouteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var routeName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("경로 저장", fontWeight = FontWeight.Bold) },
        text = {
            TextField(
                value = routeName,
                onValueChange = { routeName = it },
                label = { Text("경로 이름") },
                placeholder = { Text("예: 우리집 앞 하트 경로") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(routeName) }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

/** 가상 광고 재생 다이얼로그 — 3초 후 자동으로 onComplete 호출 */
@Composable
fun MockAdPlayerDialog(onComplete: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        LaunchedEffect(Unit) {
            delay(3000L)
            onComplete()
        }
        Box(
            modifier = Modifier
                .size(280.dp, 180.dp)
                .background(Color(0xE61F1F23), shape = RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x33FFFFFF), shape = RoundedCornerShape(16.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    color = Color(0xFFE65100),
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = "광고를 시청하는 중입니다...",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun LoadRouteDialog(
    savedRoutes: List<SavedRoute>,
    onSelect: (SavedRoute) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("저장된 경로", fontWeight = FontWeight.Bold) },
        text = {
            if (savedRoutes.isEmpty()) {
                Text("저장된 경로가 없습니다", color = Color.Gray)
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    savedRoutes.forEach { route ->
                        val dateStr = remember(route.createdAt) {
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(route.createdAt))
                        }
                        val distanceStr = if (route.distanceMeters >= 1000) {
                            String.format(java.util.Locale.getDefault(), "%.2fkm", route.distanceMeters / 1000.0)
                        } else {
                            String.format(java.util.Locale.getDefault(), "%dm", route.distanceMeters.toInt())
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(route) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(route.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("$dateStr · $distanceStr", fontSize = 12.sp, color = Color.Gray)
                            }
                            IconButton(onClick = { onDelete(route.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "삭제",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0x1AFFFFFF))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        }
    )
}
