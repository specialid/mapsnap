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
import com.naver.maps.map.compose.Marker
import com.naver.maps.map.compose.PathOverlay
import com.naver.maps.map.compose.rememberMarkerState
import com.naver.maps.map.overlay.OverlayImage
import androidx.compose.runtime.key
import com.naver.maps.map.compose.rememberCameraPositionState
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect

private val SeoulCityHall = LatLng(37.5666, 126.9784)

/** 마커 아이콘: 선택 여부에 따라 주황(선택) / 흰색(기본) 원형 비트맵 */
private fun markerIcon(selected: Boolean): OverlayImage {
    val size = 40
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    // 채우기
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = if (selected) 0xFFE65100.toInt() else 0xFFFFFFFF.toInt()
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)

    // 테두리
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 4f
    paint.color = if (selected) 0xFFBF360C.toInt() else 0xFF1565C0.toInt()
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4f, paint)

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
            onMapClick = { _, latLng ->
                if (state.selectedMarkerIndex >= 0) {
                    viewModel.onMapTapped(latLng)   // 선택 중 → 해당 위치로 마커 이동
                } else {
                    viewModel.onMarkerDeselect()
                }
            }
        ) {
            MapEffect(Unit) { map ->
                naverMap = map
            }
            if (state.snappedRoute.size >= 2) {
                PathOverlay(
                    coords = state.snappedRoute,
                    color = Color(0xFF2196F3),
                    outlineColor = Color(0xFF0D47A1),
                    width = 8.dp,
                    outlineWidth = 2.dp
                )
            }

            // 편집 가능한 중간 마커
            // 탭 → 선택(주황), 선택된 마커 재탭 → 삭제, 선택 중 지도 탭 → 이동
            state.routeMarkers.forEachIndexed { index, pos ->
                val isSelected = state.selectedMarkerIndex == index
                key(pos) {
                    val markerState = rememberMarkerState(position = pos)
                    Marker(
                        state = markerState,
                        icon = markerIcon(selected = isSelected),
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

        BottomControls(
            drawingMode = state.drawingMode,
            onDrawToggle = viewModel::onDrawToggle,
            onContinue = viewModel::onContinueDrawing,
            onClear = viewModel::onClearDrawing,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
