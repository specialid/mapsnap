package com.jason.mapsnap.presentation.component

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import android.graphics.PointF
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.naver.maps.map.compose.NaverMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.foundation.gestures.detectTapGestures
import com.naver.maps.map.compose.Marker as NaverMarker
import com.naver.maps.map.compose.PathOverlay
import com.naver.maps.map.compose.rememberCameraPositionState
import com.naver.maps.map.compose.rememberMarkerState
import com.naver.maps.map.overlay.OverlayImage
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SeoulCityHall = LatLng(37.5666, 126.9784)

/**
 * snappedRoute를 markers 위치 기준으로 구간 분할
 * 각 구간은 최소 2개 포인트를 보장한다
 */
private fun segmentize(route: List<LatLng>, markers: List<LatLng>): List<List<LatLng>> {
    if (route.size < 2) return emptyList()
    if (markers.isEmpty()) return listOf(route)

    val splitIndices = markers
        .mapNotNull { m -> route.indexOfFirst { it == m }.takeIf { it > 0 && it < route.lastIndex } }
        .sorted()
        .distinct()

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

/** 마커 아이콘: 타입 및 선택 여부에 따라 원형 비트맵(글자 포함) 생성 */
private fun markerIcon(type: MarkerType, label: String?, selected: Boolean): OverlayImage {
    val size = 80
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
        paint.textSize = if (label.length > 1) 32f else 38f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.isFakeBoldText = true
        val textHeight = paint.descent() - paint.ascent()
        val textOffset = textHeight / 2 - paint.descent()
        canvas.drawText(label, size / 2f, size / 2f + textOffset, paint)
    }

    return OverlayImage.fromBitmap(bitmap)
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

    LaunchedEffect(state.currentLocation) {
        state.currentLocation?.let { latLng ->
            cameraPositionState.animate(
                CameraUpdate.toCameraPosition(CameraPosition(latLng, 16.0))
            )
        }
    }

    viewModel.collectSideEffect { effect ->
        when (effect) {
            is MapSideEffect.ShowToast ->
                Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            MapSideEffect.RequestLocationPermission ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val isDrawing = state.drawingMode == DrawingMode.DRAWING

    Box(modifier = Modifier.fillMaxSize()) {
        NaverMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(),
            uiSettings = MapUiSettings(
                isScrollGesturesEnabled = !isDrawing,
                isZoomGesturesEnabled = !isDrawing,
                isRotateGesturesEnabled = !isDrawing,
                isTiltGesturesEnabled = !isDrawing,
                isLocationButtonEnabled = !isDrawing
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
                val segments = segmentize(state.snappedRoute, state.routeMarkers)
                segments.forEachIndexed { segIdx, seg ->
                    if (seg.size >= 2) {
                        val isSelected = state.selectedSegmentIndex == segIdx
                        key(segIdx) {
                            PathOverlay(
                                coords = seg,
                                color = if (isSelected) Color(0xFFE65100) else Color(0xFF2196F3),
                                outlineColor = if (isSelected) Color(0xFFBF360C) else Color(0xFF0D47A1),
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
                    val markerState = rememberMarkerState(position = pos)
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
                    val markerState = rememberMarkerState(position = pos)
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
                    val markerState = rememberMarkerState(position = pos)
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
            drawingMode = state.drawingMode,
            simplifiedPoints = state.simplifiedPoints,
            isLoop = state.isLoop,
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
                    val bubbleSizeDp = 40.dp
                    val bubbleSizePx = 40f * density
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (screenPoint.x - bubbleSizePx / 2).toInt(),
                                    (screenPoint.y - 48f * density - bubbleSizePx / 2).toInt()
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

        // API 호출 현황 top-right overlay counter (glassmorphism style)
        Card(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp),
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
                    text = "API 호출 현황",
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
                            .background(Color(0xFF2196F3), shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "T-Map API: ",
                        color = Color(0xFFB0BEC5),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${state.tmapApiCallCount} 회",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
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

        BottomControls(
            drawingMode = state.drawingMode,
            hasPendingEdits = state.hasPendingEdits,
            canUndo = state.canUndo,
            onApplyEdits = viewModel::onApplyEdits,
            onUndo = viewModel::onUndo,
            onDrawToggle = viewModel::onDrawToggle,
            onContinue = viewModel::onContinueDrawing,
            onClear = viewModel::onClearDrawing,
            onExportGpx = { createDocumentLauncher.launch("mapsnap_route.gpx") },
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
    }
}
