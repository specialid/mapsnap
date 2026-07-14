// 야외 GPX 경로 생성 화면 - 지도 오버레이(FAB/BottomSheet/TopAppBar)와 상태관리를 담당
package com.jason.mapsnap.ui.gpx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- Material 3 컬러: 아웃도어 오렌지 primary, 고대비 우선 ----------
private val OutdoorOrange = Color(0xFFFF5A36)
private val OutdoorOrangeDark = Color(0xFFCC4527)
private val SurfaceDark = Color(0xFF1A1C1E)
private val OnSurfaceDark = Color(0xFFF5F5F5)

// ---------- 화면 상태 ----------
enum class DrawTool { NONE, WAYPOINT, PATH, ERASER }

data class RouteStats(
    val distanceKm: Double = 0.0,
    val elevationGainM: Int = 0,
    val elevationLossM: Int = 0,
    val durationMin: Int = 0,
)

data class GpxMapUiState(
    val activeTool: DrawTool = DrawTool.NONE,
    val isRecording: Boolean = false,
    val stats: RouteStats = RouteStats(),
    val isStatsSheetExpanded: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxMapScreen(
    uiState: GpxMapUiState,
    onToolSelected: (DrawTool) -> Unit,
    onSaveRoute: () -> Unit,
    onAddWaypoint: () -> Unit,
    onToggleRecording: () -> Unit,
    onBackClick: () -> Unit,
    mapContent: @Composable BoxScope.() -> Unit,
) {
    val statsSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = statsSheetState,
    )

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 96.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            RouteStatsSheetContent(stats = uiState.stats)
        },
        topBar = {
            GpxTopAppBar(onBackClick = onBackClick)
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 지도 본체 - 뒤로 가기 제스처 충돌 방지를 위해 가장자리 16dp 여백 확보
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                content = mapContent,
            )

            // 좌측 상단: 드로잉 도구 팔레트 (지도 위 컨트롤, 48dp 이상 터치영역)
            DrawToolPalette(
                activeTool = uiState.activeTool,
                onToolSelected = onToolSelected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 16.dp),
            )

            // 우측 하단: 엄지 조작 영역 - 핵심 액션 FAB 그룹
            ThumbZoneActions(
                isRecording = uiState.isRecording,
                onAddWaypoint = onAddWaypoint,
                onToggleRecording = onToggleRecording,
                onSaveRoute = onSaveRoute,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 112.dp), // BottomSheet peek 위로 배치
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpxTopAppBar(onBackClick: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                text = "경로 그리기",
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로 가기",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

// ---------- 좌측 상단 드로잉 도구 팔레트 ----------
@Composable
private fun DrawToolPalette(
    activeTool: DrawTool,
    onToolSelected: (DrawTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolIconButton(
                icon = Icons.Default.Place,
                contentDescription = "웨이포인트 도구",
                selected = activeTool == DrawTool.WAYPOINT,
                onClick = { onToolSelected(DrawTool.WAYPOINT) },
            )
            ToolIconButton(
                icon = Icons.Default.Timeline,
                contentDescription = "경로 그리기 도구",
                selected = activeTool == DrawTool.PATH,
                onClick = { onToolSelected(DrawTool.PATH) },
            )
            ToolIconButton(
                icon = Icons.Default.Clear,
                contentDescription = "지우개 도구",
                selected = activeTool == DrawTool.ERASER,
                onClick = { onToolSelected(DrawTool.ERASER) },
            )
        }
    }
}

@Composable
private fun ToolIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (selected) OutdoorOrange else Color.Transparent
    val tintColor = if (selected) Color.White else MaterialTheme.colorScheme.onSurface

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp) // 최소 48dp x 48dp 터치 영역
            .background(color = bgColor, shape = RoundedCornerShape(12.dp)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tintColor,
        )
    }
}

// ---------- 우측 하단 엄지 조작 액션 그룹 ----------
@Composable
private fun ThumbZoneActions(
    isRecording: Boolean,
    onAddWaypoint: () -> Unit,
    onToggleRecording: () -> Unit,
    onSaveRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 보조 액션: 웨이포인트 추가
        SmallFloatingActionButton(
            onClick = onAddWaypoint,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AddLocationAlt,
                contentDescription = "웨이포인트 추가",
            )
        }

        // 보조 액션: 기록 시작/중지
        SmallFloatingActionButton(
            onClick = onToggleRecording,
            containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surface,
            contentColor = if (isRecording) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isRecording) "기록 일시정지" else "기록 시작",
            )
        }

        // 핵심 액션: 경로 저장 (Primary FAB)
        FloatingActionButton(
            onClick = onSaveRoute,
            containerColor = OutdoorOrange,
            contentColor = Color.White,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = "경로 저장",
            )
        }
    }
}

// ---------- 하단 BottomSheet: 고도/거리 정보 (고대비 그래프 영역) ----------
@Composable
private fun RouteStatsSheetContent(stats: RouteStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // 드래그 핸들 아래 요약 지표 행
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem(
                icon = Icons.Default.Straighten,
                label = "거리",
                value = "%.1f km".format(stats.distanceKm),
            )
            StatItem(
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                label = "상승",
                value = "${stats.elevationGainM} m",
            )
            StatItem(
                icon = Icons.AutoMirrored.Filled.TrendingDown,
                label = "하강",
                value = "${stats.elevationLossM} m",
            )
            StatItem(
                icon = Icons.Default.Schedule,
                label = "예상 시간",
                value = "${stats.durationMin} 분",
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 고도 그래프 영역 - 야외 직사광선 대비 고대비 배경 + 선명한 선 색상
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 실제 구현 시 Canvas로 고도 프로필을 OutdoorOrange 선으로 그림
            Text(
                text = "고도 프로필",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OutdoorOrange,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Material 3 ColorScheme 정의 ----------
val GpxLightColorScheme = lightColorScheme(
    primary = OutdoorOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0900),
    secondary = Color(0xFF2E5D50),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDE0DB),
    onSurfaceVariant = Color(0xFF52443E),
)

val GpxDarkColorScheme = darkColorScheme(
    primary = OutdoorOrange,
    onPrimary = Color.White,
    primaryContainer = OutdoorOrangeDark,
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFB1CCBE),
    background = SurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF34302E),
    onSurfaceVariant = Color(0xFFD7C2BA),
)
