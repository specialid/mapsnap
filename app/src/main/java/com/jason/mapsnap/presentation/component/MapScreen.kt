package com.jason.mapsnap.presentation.component

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.naver.maps.map.compose.PathOverlay
import com.naver.maps.map.compose.rememberCameraPositionState
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

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
            onMapClick = { _, _ -> viewModel.onMarkerDeselect() }
        ) {
            MapEffect(Unit) { map ->
                naverMap = map
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
                                width = if (isSelected) 10.dp else 8.dp,
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

            // 마커는 DrawingOverlay Canvas에서 렌더링 + 드래그 처리
        }

        DrawingOverlay(
            drawingMode = state.drawingMode,
            simplifiedPoints = state.simplifiedPoints,
            isLoop = state.isLoop,
            routeMarkers = state.routeMarkers,
            cameraPositionState = cameraPositionState,
            naverMap = naverMap,
            onDrawStart = viewModel::onDrawStart,
            onDrawPoint = viewModel::onDrawPoint,
            onDrawEnd = viewModel::onDrawEnd,
            onMarkerDragEnd = viewModel::onMarkerDragEnd,
            modifier = Modifier.fillMaxSize()
        )

        BottomControls(
            drawingMode = state.drawingMode,
            onDrawToggle = viewModel::onDrawToggle,
            onContinue = viewModel::onContinueDrawing,
            onClear = viewModel::onClearDrawing,
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
    }
}
