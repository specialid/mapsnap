package com.jason.mapsnap.presentation.component

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.jason.mapsnap.presentation.map.DrawingMode
import com.jason.mapsnap.presentation.map.MapSideEffect
import com.jason.mapsnap.presentation.map.MapViewModel
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.compose.ExperimentalNaverMapApi
import com.naver.maps.map.compose.MapEffect
import com.naver.maps.map.compose.MapProperties
import com.naver.maps.map.compose.MapUiSettings
import com.naver.maps.map.compose.Marker as NaverMarker
import com.naver.maps.map.compose.NaverMap
import com.naver.maps.map.compose.PathOverlay
import com.naver.maps.map.compose.rememberCameraPositionState
import com.naver.maps.map.compose.rememberUpdatedMarkerState
import com.naver.maps.map.overlay.OverlayImage
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect
import android.graphics.PointF
import com.jason.mapsnap.BuildConfig
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

private val SeoulCityHall = LatLng(37.5666, 126.9784)

// 단순화 강도(epsilon, degree)를 사용자용 5단계로 추상화하기 위한 매핑.
// 기존 기본값(보통)이 중앙(index 2)에 오도록 보간한다.
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

/**
 * snappedRoute를 markers 위치 기준으로 구간 분할
 * 각 구간은 최소 2개 포인트를 보장한다
 */
private fun segmentize(route: List<LatLng>, markers: List<LatLng>): List<List<LatLng>> {
    if (route.size < 2) return emptyList()
    if (markers.isEmpty()) return listOf(route)

    // 자기교차 경로 대비: 각 마커는 이전 마커 이후 구간에서만 탐색해
    // 같은 좌표가 경로 위 다른 지점에 재등장할 때의 오매칭을 방지
    val splitIndices = mutableListOf<Int>()
    var searchFrom = 1
    for (m in markers) {
        if (searchFrom > route.lastIndex) break
        val idxInTail = route.subList(searchFrom, route.size).indexOfFirst { it == m }
        if (idxInTail < 0) continue
        val absoluteIdx = searchFrom + idxInTail
        if (absoluteIdx >= route.lastIndex) continue
        splitIndices.add(absoluteIdx)
        searchFrom = absoluteIdx + 1
    }

    if (splitIndices.isEmpty()) return listOf(route)

    val segments = mutableListOf<List<LatLng>>()
    var prev = 0
    for (idx in splitIndices) {
        segments.add(route.subList(prev, idx + 1))
        prev = idx
    }
    segments.add(route.subList(prev, route.size))
    return segments.filter { it.size >= 2 }
}

enum class MarkerType { START, END, INTERMEDIATE }

// 동일 (타입, 라벨, 선택 여부) 조합은 항상 같은 비트맵이므로 캐시 — 드래그 중 매 프레임 재생성 방지
private val markerIconCache = mutableMapOf<Triple<MarkerType, String?, Boolean>, OverlayImage>()

/** 마커 아이콘: 타입 및 선택 여부에 따라 원형 비트맵(글자 포함) 생성 (결과는 캐시됨) */
private fun markerIcon(type: MarkerType, label: String?, selected: Boolean): OverlayImage {
    val cacheKey = Triple(type, label, selected)
    markerIconCache[cacheKey]?.let { return it }

    val size = 100
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    // 색상 결정
    val (bgColor, strokeColor) = when (type) {
        MarkerType.START -> {
            if (selected) {
                0xFFE65100.toInt() to 0xFFBF360C.toInt()
            } else {
                0xFF4CAF50.toInt() to 0xFF2E7D32.toInt() // Green
            }
        }
        MarkerType.END -> {
            if (selected) {
                0xFFE65100.toInt() to 0xFFBF360C.toInt()
            } else {
                0xFFF44336.toInt() to 0xFFC62828.toInt() // Red
            }
        }
        MarkerType.INTERMEDIATE -> {
            if (selected) {
                0xFFE65100.toInt() to 0xFFBF360C.toInt()
            } else {
                0xFFFFFFFF.toInt() to 0xFF1565C0.toInt() // White & Blue
            }
        }
    }

    // 채우기
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = bgColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paint)

    // 테두리
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 6f
    paint.color = strokeColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paint)

    // 텍스트 색상 및 텍스트 그리기
    val textColor = if (selected) {
        0xFFFFFFFF.toInt()
    } else if (type == MarkerType.INTERMEDIATE) {
        0xFF1565C0.toInt()
    } else {
        0xFFFFFFFF.toInt()
    }

    if (label != null) {
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = textColor
        paint.textSize = if (label.length > 1) 36f else 44f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.isFakeBoldText = true
        val textHeight = paint.descent() - paint.ascent()
        val textOffset = textHeight / 2 - paint.descent()
        canvas.drawText(label, size / 2f, size / 2f + textOffset, paint)
    }

    return OverlayImage.fromBitmap(bitmap).also { markerIconCache[cacheKey] = it }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalNaverMapApi::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel()
) {
    val state by viewModel.collectAsState()
    val context = LocalContext.current
    val cameraPositionState = rememberCameraPositionState()
    var naverMap by remember { mutableStateOf<com.naver.maps.map.NaverMap?>(null) }
    var showMockAdPlayer by remember { mutableStateOf(false) }
    var isMapMoveMode by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showRedrawConfirmDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onLocationPermissionResult(granted)
        if (granted) {
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location ->
                    val target = if (location != null) {
                        LatLng(location.latitude, location.longitude)
                    } else {
                        SeoulCityHall
                    }
                    viewModel.onLocationReceived(target)
                }
                .addOnFailureListener {
                    viewModel.onLocationReceived(SeoulCityHall)
                }
        } else {
            viewModel.onLocationReceived(SeoulCityHall)
            Toast.makeText(
                context,
                "위치 권한이 거부되었습니다. 설정에서 허용해 주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // 현재 위치로 이동: 권한 보유 시 즉시 조회·이동, 미보유 시 권한 재요청
    val moveToCurrentLocation: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        viewModel.onLocationReceived(LatLng(location.latitude, location.longitude))
                    }
                }
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        if (uri != null) {
            viewModel.onGpxUriSelected(uri, context.contentResolver)
        }
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    // 첫 실행 시 1회 도움말 자동 표시 (SharedPreferences 플래그, 디스크 I/O는 IO 디스패처)
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("mapsnap_prefs", Context.MODE_PRIVATE)
        val shown = withContext(Dispatchers.IO) { prefs.getBoolean("onboarding_shown", false) }
        if (!shown) {
            showHelpDialog = true
            withContext(Dispatchers.IO) {
                prefs.edit().putBoolean("onboarding_shown", true).apply()
            }
        }
    }

    LaunchedEffect(state.currentLocation) {
        state.currentLocation?.let { latLng ->
            cameraPositionState.animate(
                CameraUpdate.toCameraPosition(CameraPosition(latLng, 16.0))
            )
        }
    }

    LaunchedEffect(state.drawingMode) {
        if (state.drawingMode != DrawingMode.DRAWING) {
            isMapMoveMode = false
        }
    }

    viewModel.collectSideEffect { effect ->
        when (effect) {
            is MapSideEffect.ShowToast ->
                Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            MapSideEffect.ShowRewardedAd -> {
                showMockAdPlayer = true
            }
        }
    }

    val isDrawing = state.drawingMode == DrawingMode.DRAWING

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF1F1F23),
                drawerContentColor = Color.White
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "MapSnap",
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 28.dp),
                    color = Color(0x33FFFFFF)
                )
                Spacer(modifier = Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("현재 위치로 이동") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        moveToCurrentLocation()
                    },
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color.White,
                        unselectedIconColor = Color.White
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("설정") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showSettingsDialog = true
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color.White,
                        unselectedIconColor = Color.White
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("도움말") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showHelpDialog = true
                    },
                    icon = { Icon(Icons.Default.Help, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color.White,
                        unselectedIconColor = Color.White
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("정보") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showInfoDialog = true
                    },
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedContainerColor = Color.Transparent,
                        unselectedTextColor = Color.White,
                        unselectedIconColor = Color.White
                    ),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        NaverMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = state.mapType),
            uiSettings = MapUiSettings(
                isScrollGesturesEnabled = !isDrawing || isMapMoveMode,
                isZoomGesturesEnabled = !isDrawing || isMapMoveMode,
                isRotateGesturesEnabled = !isDrawing || isMapMoveMode,
                isTiltGesturesEnabled = !isDrawing || isMapMoveMode,
                isLocationButtonEnabled = !isDrawing || isMapMoveMode
            ),
            onMapClick = { _, _ ->
                viewModel.onMarkerDeselect()
            }
        ) {
            MapEffect(Unit) { map ->
                naverMap = map
                viewModel.onNaverMapLoaded()
            }
            // 경로를 마커 위치 기준으로 구간 분할 → 각 구간 별도 PathOverlay
            // 탭 → 삭제 다이얼로그 표시
            if (state.snappedRoute.size >= 2) {
                val segments = remember(state.snappedRoute, state.routeMarkers) {
                    segmentize(state.snappedRoute, state.routeMarkers)
                }
                segments.forEachIndexed { segIdx, seg ->
                    if (seg.size >= 2) {
                        val isSelected = state.selectedSegmentIndex == segIdx
                        key(segIdx) {
                            PathOverlay(
                                coords = seg,
                                color = when {
                                    isSelected -> Color(0xFFE65100)
                                    state.hasPendingEdits -> Color(0xFFFFB300) // 적용 대기(앰버)
                                    else -> Color(0xFF2196F3)
                                },
                                outlineColor = when {
                                    isSelected -> Color(0xFFBF360C)
                                    state.hasPendingEdits -> Color(0xFFFF6F00)
                                    else -> Color(0xFF0D47A1)
                                },
                                width = if (isSelected) 7.dp else 5.dp,
                                outlineWidth = 2.dp,
                                onClick = {
                                    viewModel.onSegmentTapped(segIdx)
                                    true
                                }
                            )
                        }
                    }
                }
            }

            // 출발 마커 (S)
            state.routeStart?.let { pos ->
                val isSelected = state.selectedMarkerIndex == -2
                key("start_marker") {
                    val markerState = rememberUpdatedMarkerState(position = pos)
                    LaunchedEffect(pos) {
                        markerState.position = pos
                    }
                    NaverMarker(
                        state = markerState,
                        icon = markerIcon(MarkerType.START, "S", isSelected),
                        anchor = Offset(0.5f, 0.5f),
                        onClick = {
                            viewModel.onStartMarkerTapped()
                            true
                        }
                    )
                }
            }

            // 도착 마커 (G)
            state.routeEnd?.let { pos ->
                val isSelected = state.selectedMarkerIndex == -3
                key("end_marker") {
                    val markerState = rememberUpdatedMarkerState(position = pos)
                    LaunchedEffect(pos) {
                        markerState.position = pos
                    }
                    NaverMarker(
                        state = markerState,
                        icon = markerIcon(MarkerType.END, "G", isSelected),
                        anchor = Offset(0.5f, 0.5f),
                        onClick = {
                            viewModel.onEndMarkerTapped()
                            true
                        }
                    )
                }
            }

            // 중간 마커: 탭 → 선택(주황)
            state.routeMarkers.forEachIndexed { index, pos ->
                val isSelected = state.selectedMarkerIndex == index
                key(index) {
                    val markerState = rememberUpdatedMarkerState(position = pos)
                    LaunchedEffect(pos) {
                        markerState.position = pos
                    }
                    NaverMarker(
                        state = markerState,
                        icon = markerIcon(MarkerType.INTERMEDIATE, (index + 1).toString(), isSelected),
                        anchor = Offset(0.5f, 0.5f),
                        onClick = {
                            viewModel.onMarkerTapped(index)
                            true
                        }
                    )
                }
            }
        }

        DrawingOverlay(
            drawingMode = if (isMapMoveMode) DrawingMode.IDLE else state.drawingMode,
            simplifiedPoints = state.simplifiedPoints,
            isLoop = state.isLoop,
            pendingStrokes = state.pendingStrokes,
            cameraPositionState = cameraPositionState,
            naverMap = naverMap,
            onDrawStart = viewModel::onDrawStart,
            onDrawPoint = viewModel::onDrawPoint,
            onDrawEnd = viewModel::onDrawEnd,
            modifier = Modifier.fillMaxSize()
        )

        // 드래그 가능한 조절점 오버레이 (선택 시 드래그 이동 제공 - 출발, 도착 및 중간 마커 대응)
        val selectedIndex = state.selectedMarkerIndex
        val markerPos = when (selectedIndex) {
            -2 -> state.routeStart
            -3 -> state.routeEnd
            in state.routeMarkers.indices -> state.routeMarkers[selectedIndex]
            else -> null
        }
        if (markerPos != null) {
            val currentMarkerPosState = rememberUpdatedState(markerPos) // 최신 마커 위치 캡처용 State
            val cameraPosition = cameraPositionState.position // 카메라 움직임 시 리컴포지션 유도
            naverMap?.let { map ->
                val projection = map.projection
                val screenPoint = projection.toScreenLocation(markerPos)
                val density = LocalContext.current.resources.displayMetrics.density

                // 더 큰 터치 조절점 크기 정의 (터치 영역 64dp, 시각 조절점 32dp)
                val touchSizeDp = 64.dp
                val touchSizePx = 64f * density

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (screenPoint.x - touchSizePx / 2).toInt(),
                                (screenPoint.y - touchSizePx / 2).toInt()
                            )
                        }
                        .size(touchSizeDp)
                        .pointerInput(selectedIndex) {
                            var accumulatedDrag: PointF? = null
                            detectDragGestures(
                                onDragStart = { _ ->
                                    viewModel.onDragStart()
                                    val startPos = currentMarkerPosState.value
                                    val startScreenPoint = projection.toScreenLocation(startPos)
                                    accumulatedDrag = PointF(startScreenPoint.x, startScreenPoint.y)
                                },

                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulatedDrag?.let { drag ->
                                        drag.x += dragAmount.x
                                        drag.y += dragAmount.y
                                        val newLatLng = projection.fromScreenLocation(drag)
                                        when (selectedIndex) {
                                            -2 -> viewModel.onStartMarkerDragged(newLatLng)
                                            -3 -> viewModel.onEndMarkerDragged(newLatLng)
                                            else -> viewModel.onMarkerDragged(selectedIndex, newLatLng)
                                        }
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // 시각 마커 (선택된 마커와 매칭되는 32dp 오렌지 원형 조절판)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFFE65100), shape = CircleShape)
                            .border(3.dp, Color(0xFFBF360C), shape = CircleShape)
                    )
                }

                if (selectedIndex >= 0) {
                    val bubbleSizeDp = 48.dp
                    val bubbleSizePx = 48f * density
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    // 마커 오른쪽 32dp 오프셋: 드래그 핸들과 공간 충돌 방지
                                    (screenPoint.x + 32f * density).toInt(),
                                    (screenPoint.y - bubbleSizePx / 2).toInt()
                                )
                            }
                            .size(bubbleSizeDp)
                            .background(Color(0xFFE53935), shape = CircleShape)
                            .border(2.dp, Color.White, shape = CircleShape)
                            .pointerInput(selectedIndex) {
                                detectTapGestures {
                                    viewModel.onDeleteMarkerTapped()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Marker",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // Floating Menu Button (Top-Left)
        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
            shape = CircleShape,
            colors = CardDefaults.cardColors(
                containerColor = Color(0xCC1F1F23)
            ),
            border = BorderStroke(1.dp, Color(0x33FFFFFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            IconButton(
                onClick = {
                    scope.launch { drawerState.open() }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "메뉴",
                    tint = Color.White
                )
            }
        }

        // Settings Dialog
        if (showSettingsDialog) {
            var tempInterval by remember { mutableStateOf(state.markerIntervalMeters) }
            var tempDrawnLevel by remember { mutableStateOf(nearestLevelIndex(state.epsilonDrawnDeg, DRAWN_LEVELS)) }
            var tempRouteLevel by remember { mutableStateOf(nearestLevelIndex(state.epsilonRouteDeg, ROUTE_LEVELS)) }
            var tempIncludeTimestamps by remember { mutableStateOf(state.includeTimestamps) }
            var tempPaceMinutes by remember { mutableStateOf((state.runningPaceSecPerKm / 60).toString()) }
            var tempPaceSeconds by remember { mutableStateOf((state.runningPaceSecPerKm % 60).toString()) }

            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
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
                        viewModel.onUpdateSettings(
                            tempInterval,
                            DRAWN_LEVELS[tempDrawnLevel],
                            ROUTE_LEVELS[tempRouteLevel],
                            tempIncludeTimestamps,
                            totalSec
                        )
                        showSettingsDialog = false
                    }) {
                        Text("적용")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSettingsDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        // Help Dialog
        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
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
                        Text("• 일괄 적용: 마커를 수정한 후 [적용] 버튼을 눌러 T-Map 실도로 스냅을 최종 업데이트합니다.", fontSize = 14.sp)
                        Text("• 실행 취소(Undo): 마커 조작 실수를 했을 때 [실행 취소] 버튼을 통해 이전 상태로 되돌릴 수 있습니다.", fontSize = 14.sp)
                        Text("• GPX 내보내기: 우측 [GPX] 버튼으로 경로를 gpx 파일로 스마트폰에 저장할 수 있습니다.", fontSize = 14.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHelpDialog = false }) {
                        Text("확인")
                    }
                }
            )
        }

        // Info Dialog
        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text("정보", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("MapSnap", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("버전 ${BuildConfig.VERSION_CODE}", fontSize = 14.sp)
                        Text("보행자 중심 도로 스냅 및 편집 애플리케이션", fontSize = 14.sp)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Powered by T-Map API & Naver Map SDK", fontSize = 12.sp, color = Color.Gray)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text("확인")
                    }
                }
            )
        }

        // Right Side Column (API Counter Card + Map Type Toggle FAB)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // DRAWING/PROCESSING 중에는 지도 면적 확보를 위해 카드 숨김
            AnimatedVisibility(
                visible = state.drawingMode == DrawingMode.IDLE || state.drawingMode == DrawingMode.DONE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
            run {
                val remaining = (state.tmapMaxLimitCount - state.tmapApiCallCount).coerceAtLeast(0)
                val quotaColor = when {
                    remaining <= 0 -> Color(0xFFE53935) // 소진: 빨강
                    remaining <= 5 -> Color(0xFFFF9800) // 임박: 주황
                    else -> Color.White
                }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xCC1F1F23)
                    ),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "남은 경로 분석",
                            color = Color.White,
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
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${remaining} / ${state.tmapMaxLimitCount}",
                                color = quotaColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        if (com.jason.mapsnap.BuildConfig.DEBUG) {
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(Color(0xFF4CAF50), shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Naver Map API: ",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "${state.naverMapApiCallCount} 회",
                                    color = Color.White,
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
                onClick = { viewModel.onToggleMapType() },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "지도 타입 변경",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // snappedRoute가 바뀔 때만 재계산 — 선택/지도타입 등 무관한 리컴포지션에서 haversine 합산 반복 방지
        val totalDistanceMeters = remember(state.snappedRoute) { state.totalDistanceMeters }

        BottomControls(
            drawingMode = state.drawingMode,
            hasPendingEdits = state.hasPendingEdits,
            canUndo = state.canUndo,
            totalDistanceMeters = totalDistanceMeters,
            routeMarkersCount = state.routeMarkers.size,
            onApplyEdits = viewModel::onApplyEdits,
            onUndo = viewModel::onUndo,
            onDrawToggle = {
                // DONE 모드의 "다시 그리기"는 전체 초기화 → 확인 후 진행
                if (state.drawingMode == DrawingMode.DONE) showRedrawConfirmDialog = true
                else viewModel.onDrawToggle()
            },
            onContinue = viewModel::onContinueDrawing,
            onClear = {
                // DONE 모드의 "지우기"는 전체 삭제 → 확인 후 진행
                if (state.drawingMode == DrawingMode.DONE) showClearConfirmDialog = true
                else viewModel.onClearDrawing()
            },
            onExportGpx = { createDocumentLauncher.launch("mapsnap_route.gpx") },
            isMapMoveMode = isMapMoveMode,
            onToggleMapMoveMode = { isMapMoveMode = !isMapMoveMode },
            onCancelProcessing = viewModel::onCancelProcessing,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 구간 삭제 확인 다이얼로그
        if (state.showDeleteSegmentDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.onDeleteSegmentDismissed() },
                title = { Text("구간 삭제") },
                text = { Text("선택한 구간을 삭제하고 경로를 재탐색할까요?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.onDeleteSegmentConfirmed() }) {
                        Text("삭제", color = Color(0xFFE65100))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDeleteSegmentDismissed() }) {
                        Text("취소")
                    }
                }
            )
        }

        // 중간 마커 삭제 확인 다이얼로그
        if (state.showDeleteMarkerDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.onDeleteMarkerDismissed() },
                title = { Text("마커 삭제") },
                text = { Text("선택한 중간 마커를 삭제하고 경로를 재탐색할까요?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.onDeleteMarkerConfirmed() }) {
                        Text("삭제", color = Color(0xFFE53935))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDeleteMarkerDismissed() }) {
                        Text("취소")
                    }
                }
            )
        }

        // 전체 지우기 확인 다이얼로그 (데이터 손실 방지)
        if (showClearConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmDialog = false },
                title = { Text("경로 전체 삭제", fontWeight = FontWeight.Bold) },
                text = { Text("현재 경로와 모든 편집 내용을 삭제합니다. 계속할까요?") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirmDialog = false
                        viewModel.onClearDrawing()
                    }) {
                        Text("삭제", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        // 다시 그리기 확인 다이얼로그 (기존 경로 폐기)
        if (showRedrawConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showRedrawConfirmDialog = false },
                title = { Text("다시 그리기", fontWeight = FontWeight.Bold) },
                text = { Text("현재 경로를 버리고 새로 그립니다. 계속할까요?") },
                confirmButton = {
                    TextButton(onClick = {
                        showRedrawConfirmDialog = false
                        viewModel.onDrawToggle()
                    }) {
                        Text("새로 그리기", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRedrawConfirmDialog = false }) {
                        Text("취소")
                    }
                }
            )
        }

        // 일일 API 호출 한도 초과 경고 (광고 유도)
        if (state.isAdPromptDialogVisible) {
            AlertDialog(
                onDismissRequest = { viewModel.onDismissAdPrompt() },
                title = { Text("일일 API 호출 한도 초과", fontWeight = FontWeight.Bold) },
                text = { Text("오늘 제공된 무료 API 호출 한도를 초과했습니다. 광고를 시청하고 10회 추가 이용하시겠습니까?") },
                confirmButton = {
                    TextButton(onClick = { viewModel.onWatchAdRequested() }) {
                        Text("광고 시청", color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDismissAdPrompt() }) {
                        Text("취소")
                    }
                }
            )
        }

        // 가상 광고 플레이어 다이얼로그
        if (showMockAdPlayer) {
            Dialog(onDismissRequest = {}) {
                LaunchedEffect(Unit) {
                    delay(3000L)
                    showMockAdPlayer = false
                    viewModel.onWatchAdCompleted()
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
                        androidx.compose.material3.CircularProgressIndicator(
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
    }
}
}
