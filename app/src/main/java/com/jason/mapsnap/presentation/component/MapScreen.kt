package com.jason.mapsnap.presentation.component

import android.Manifest
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.jason.mapsnap.presentation.map.DrawingMode
import com.jason.mapsnap.presentation.map.MapSideEffect
import com.jason.mapsnap.presentation.map.MapViewModel
import com.jason.mapsnap.ui.theme.MapOverlayColors
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.compose.ExperimentalNaverMapApi
import com.naver.maps.map.compose.MapEffect
import com.naver.maps.map.compose.MapProperties
import com.naver.maps.map.compose.MapUiSettings
import com.naver.maps.map.compose.NaverMap
import com.naver.maps.map.compose.rememberCameraPositionState
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect
import com.jason.mapsnap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

private val SeoulCityHall = LatLng(37.5666, 126.9784)

/** MapScreen 로컬 모달(서로 배타적으로 열림) 상태 — ORS 안내는 설정 위에 중첩 표시되므로 별도 플래그로 유지 */
private sealed interface MapDialog {
    data object Settings : MapDialog
    data object Help : MapDialog
    data object Info : MapDialog
    data object ClearConfirm : MapDialog
    data object RedrawConfirm : MapDialog
    data object MockAdPlayer : MapDialog
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
    var isMapMoveMode by remember { mutableStateOf(false) }

    var activeDialog by remember { mutableStateOf<MapDialog?>(null) }
    var showOrsGuideDialog by remember { mutableStateOf(false) }
    var mapContainerWidthPx by remember { mutableStateOf(0) }

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
            activeDialog = MapDialog.Help
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
                activeDialog = MapDialog.MockAdPlayer
            }
        }
    }

    val isDrawing = state.drawingMode == DrawingMode.DRAWING

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            MapNavigationDrawerContent(
                onMoveToCurrentLocation = { scope.launch { drawerState.close() }; moveToCurrentLocation() },
                onOpenLoadRoute = { scope.launch { drawerState.close() }; viewModel.onOpenLoadRouteDialog() },
                onOpenSettings = { scope.launch { drawerState.close() }; activeDialog = MapDialog.Settings },
                onOpenHelp = { scope.launch { drawerState.close() }; activeDialog = MapDialog.Help },
                onOpenInfo = { scope.launch { drawerState.close() }; activeDialog = MapDialog.Info }
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().onSizeChanged { mapContainerWidthPx = it.width }) {
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
            RouteMapOverlays(
                state = state,
                onSegmentTapped = viewModel::onSegmentTapped,
                onStartMarkerTapped = viewModel::onStartMarkerTapped,
                onEndMarkerTapped = viewModel::onEndMarkerTapped,
                onMarkerTapped = viewModel::onMarkerTapped
            )
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
            onDrawCancel = viewModel::onDrawCancel,
            modifier = Modifier.fillMaxSize()
        )

        MarkerDragHandleOverlay(
            state = state,
            naverMap = naverMap,
            cameraPositionState = cameraPositionState,
            mapContainerWidthPx = mapContainerWidthPx,
            onDragStart = viewModel::onDragStart,
            onStartMarkerDragged = viewModel::onStartMarkerDragged,
            onEndMarkerDragged = viewModel::onEndMarkerDragged,
            onMarkerDragged = viewModel::onMarkerDragged,
            onDeleteMarkerTapped = viewModel::onDeleteMarkerTapped
        )

        // Floating Menu Button (Top-Left)
        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp),
            shape = CircleShape,
            colors = CardDefaults.cardColors(
                containerColor = MapOverlayColors.cardBackground
            ),
            border = BorderStroke(1.dp, MapOverlayColors.cardBorder),
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
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Settings Dialog
        if (activeDialog == MapDialog.Settings) {
            SettingsDialog(
                markerIntervalMeters = state.markerIntervalMeters,
                epsilonDrawnDeg = state.epsilonDrawnDeg,
                epsilonRouteDeg = state.epsilonRouteDeg,
                includeTimestamps = state.includeTimestamps,
                runningPaceSecPerKm = state.runningPaceSecPerKm,
                orsApiKey = state.orsApiKey,
                onDismiss = { activeDialog = null },
                onOpenOrsGuide = { showOrsGuideDialog = true },
                onApply = { interval, drawnEps, routeEps, includeTs, paceSec, orsKey ->
                    viewModel.onUpdateSettings(interval, drawnEps, routeEps, includeTs, paceSec, true, orsKey)
                    activeDialog = null
                }
            )
        }

        // ORS API 키 발급 방법 안내 다이얼로그
        if (showOrsGuideDialog) {
            OrsGuideDialog(
                onDismiss = { showOrsGuideDialog = false },
                onOpenSignupPage = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://openrouteservice.org/dev/#/signup")
                    )
                    context.startActivity(intent)
                }
            )
        }

        // Help Dialog
        if (activeDialog == MapDialog.Help) {
            HelpDialog(onDismiss = { activeDialog = null })
        }

        // Info Dialog
        if (activeDialog == MapDialog.Info) {
            InfoDialog(versionCode = BuildConfig.VERSION_CODE, onDismiss = { activeDialog = null })
        }

        MapSideActions(
            drawingMode = state.drawingMode,
            tmapApiCallCount = state.tmapApiCallCount,
            tmapMaxLimitCount = state.tmapMaxLimitCount,
            naverMapApiCallCount = state.naverMapApiCallCount,
            onToggleMapType = viewModel::onToggleMapType,
            onOpenSaveRouteDialog = viewModel::onOpenSaveRouteDialog,
            modifier = Modifier.align(Alignment.TopEnd)
        )

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
                if (state.drawingMode == DrawingMode.DONE) activeDialog = MapDialog.RedrawConfirm
                else viewModel.onDrawToggle()
            },
            onContinue = viewModel::onContinueDrawing,
            onClear = {
                // DONE 모드의 "지우기"는 전체 삭제 → 확인 후 진행
                if (state.drawingMode == DrawingMode.DONE) activeDialog = MapDialog.ClearConfirm
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
            MapConfirmDialog(
                title = "구간 삭제",
                message = "선택한 구간을 삭제하고 경로를 재탐색할까요?",
                confirmLabel = "삭제",
                confirmColor = MaterialTheme.colorScheme.primary,
                onConfirm = { viewModel.onDeleteSegmentConfirmed() },
                onDismiss = { viewModel.onDeleteSegmentDismissed() }
            )
        }

        // 중간 마커 삭제 확인 다이얼로그
        if (state.showDeleteMarkerDialog) {
            MapConfirmDialog(
                title = "마커 삭제",
                message = "선택한 중간 마커를 삭제하고 경로를 재탐색할까요?",
                confirmLabel = "삭제",
                confirmColor = MaterialTheme.colorScheme.error,
                onConfirm = { viewModel.onDeleteMarkerConfirmed() },
                onDismiss = { viewModel.onDeleteMarkerDismissed() }
            )
        }

        // 전체 지우기 확인 다이얼로그 (데이터 손실 방지)
        if (activeDialog == MapDialog.ClearConfirm) {
            MapConfirmDialog(
                title = "경로 전체 삭제",
                message = "현재 경로와 모든 편집 내용을 삭제합니다. 계속할까요?",
                confirmLabel = "삭제",
                confirmColor = MaterialTheme.colorScheme.error,
                onConfirm = {
                    activeDialog = null
                    viewModel.onClearDrawing()
                },
                onDismiss = { activeDialog = null }
            )
        }

        // 다시 그리기 확인 다이얼로그 (기존 경로 폐기)
        if (activeDialog == MapDialog.RedrawConfirm) {
            MapConfirmDialog(
                title = "다시 그리기",
                message = "현재 경로를 버리고 새로 그립니다. 계속할까요?",
                confirmLabel = "새로 그리기",
                confirmColor = MaterialTheme.colorScheme.error,
                onConfirm = {
                    activeDialog = null
                    viewModel.onDrawToggle()
                },
                onDismiss = { activeDialog = null }
            )
        }

        // 일일 API 호출 한도 초과 경고 (광고 유도)
        if (state.isAdPromptDialogVisible) {
            MapConfirmDialog(
                title = "일일 API 호출 한도 초과",
                message = "오늘 제공된 무료 API 호출 한도를 초과했습니다. 광고를 시청하고 10회 추가 이용하시겠습니까?",
                confirmLabel = "광고 시청",
                confirmColor = MaterialTheme.colorScheme.primary,
                confirmBold = true,
                onConfirm = { viewModel.onWatchAdRequested() },
                onDismiss = { viewModel.onDismissAdPrompt() }
            )
        }

        // 가상 광고 플레이어 다이얼로그
        if (activeDialog == MapDialog.MockAdPlayer) {
            MockAdPlayerDialog(
                onComplete = {
                    activeDialog = null
                    viewModel.onWatchAdCompleted()
                }
            )
        }

        // 경로 저장 다이얼로그 — 이름 입력
        if (state.showSaveRouteDialog) {
            SaveRouteDialog(
                onDismiss = { viewModel.onSaveRouteDismissed() },
                onConfirm = { name -> viewModel.onSaveRouteConfirmed(name) }
            )
        }

        // 저장된 경로 불러오기 다이얼로그 — 목록 + 삭제
        if (state.showLoadRouteDialog) {
            LoadRouteDialog(
                savedRoutes = state.savedRoutes,
                onSelect = { route -> viewModel.onLoadRouteSelected(route) },
                onDelete = { id -> viewModel.onDeleteSavedRoute(id) },
                onDismiss = { viewModel.onLoadRouteDialogDismissed() }
            )
        }
    }
}
}
