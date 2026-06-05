package com.jason.mapsnap.presentation.map

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jason.mapsnap.data.tracker.ApiCallTracker
import com.jason.mapsnap.domain.usecase.CheckApiLimitUseCase
import com.jason.mapsnap.domain.usecase.IncrementApiCountUseCase
import com.jason.mapsnap.domain.usecase.RechargeApiLimitUseCase
import com.jason.mapsnap.domain.usecase.ExportGpxUseCase
import com.jason.mapsnap.domain.usecase.SimplifyPathUseCase
import com.jason.mapsnap.domain.usecase.SnapToRoadUseCase
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.compose.MapType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@HiltViewModel
class MapViewModel @Inject constructor(
    private val snapToRoad: SnapToRoadUseCase,
    private val simplifyPath: SimplifyPathUseCase,
    private val exportGpx: ExportGpxUseCase,
    private val apiCallTracker: ApiCallTracker,
    private val checkApiLimitUseCase: CheckApiLimitUseCase,
    private val incrementApiCountUseCase: IncrementApiCountUseCase,
    private val rechargeApiLimitUseCase: RechargeApiLimitUseCase
) : ViewModel(), ContainerHost<MapState, MapSideEffect> {

    override val container = container<MapState, MapSideEffect>(MapState())

    companion object {
        const val LOOP_CLOSE_THRESHOLD_M = 30.0
        private const val REROUTE_DEBOUNCE_MS = 300L
        private const val DEFAULT_INTERVAL_METERS = 80.0
    }

    init {
        viewModelScope.launch {
            val result = checkApiLimitUseCase()
            val usage = when (result) {
                is CheckApiLimitUseCase.CheckResult.Allowed -> result.usage
                is CheckApiLimitUseCase.CheckResult.Blocked -> result.usage
            }
            intent {
                reduce { state.copy(tmapApiCallCount = usage.dailyCount, tmapMaxLimitCount = 30 + usage.rechargedCount) }
            }
        }
        viewModelScope.launch {
            apiCallTracker.naverCount.collect { count ->
                intent {
                    reduce { state.copy(naverMapApiCallCount = count) }
                }
            }
        }
    }

    fun onNaverMapLoaded() = intent {
        apiCallTracker.incrementNaver()
        Log.d("MapViewModel", "Naver Map API (Mobile Dynamic Map) 로딩 완료")
    }

    fun onToggleMapType() = intent {
        val nextType = if (state.mapType == MapType.Basic) {
            MapType.Satellite
        } else {
            MapType.Basic
        }
        reduce { state.copy(mapType = nextType) }
    }

    fun onUpdateSettings(interval: Double, epsilonDrawn: Double, epsilonRoute: Double) = intent {
        reduce {
            state.copy(
                markerIntervalMeters = interval,
                epsilonDrawnDeg = epsilonDrawn,
                epsilonRouteDeg = epsilonRoute
            )
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) intent { reduce { state.copy(currentLocation = null) } }
    }

    fun onGpxUriSelected(uri: Uri, contentResolver: ContentResolver) = intent {
        reduce { state.copy(isProcessing = true) }
        try {
            val gpxString = exportGpx(state.snappedRoute)
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(gpxString.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                } ?: throw Exception("Failed to open output stream for GPX export")
            }
            reduce { state.copy(isProcessing = false) }
            postSideEffect(MapSideEffect.ShowToast("GPX 파일이 성공적으로 저장되었습니다"))
        } catch (e: Exception) {
            Log.e("MapViewModel", "Failed to export GPX: ${e.message}", e)
            reduce { state.copy(isProcessing = false) }
            postSideEffect(MapSideEffect.ShowToast(e.message ?: "GPX 파일 저장에 실패했습니다"))
        }
    }

    fun onLocationReceived(latLng: LatLng) = intent {
        reduce { state.copy(currentLocation = latLng) }
    }

    fun onDrawToggle() = intent {
        when (state.drawingMode) {
            DrawingMode.IDLE, DrawingMode.DONE -> {
                // 완전 초기화 후 새로 그리기
                reduce {
                    state.copy(
                        drawingMode = DrawingMode.DRAWING,
                        isContinuing = false,
                        drawnPoints = emptyList(),
                        snappedRoute = emptyList(),
                        routeMarkers = emptyList(),
                        routeStart = null,
                        routeEnd = null,
                        editHistory = emptyList()
                    )
                }
            }
            DrawingMode.DRAWING -> snapCurrentPath()
            DrawingMode.PROCESSING -> { /* 처리 중 무시 */ }
        }
    }

    /** 기존 경로 끝에 이어서 그리기 — snappedRoute 유지, isContinuing = true */
    fun onContinueDrawing() = intent {
        if (state.drawingMode != DrawingMode.DONE) return@intent
        reduce {
            state.copy(
                drawingMode = DrawingMode.DRAWING,
                isContinuing = true,
                drawnPoints = emptyList(),
                simplifiedPoints = emptyList()
                // snappedRoute / routeMarkers / routeStart / routeEnd 유지
            )
        }
    }

    fun onDrawStart(point: LatLng) = intent {
        if (state.drawingMode != DrawingMode.DRAWING) return@intent
        reduce { state.copy(drawnPoints = listOf(point)) }
    }

    fun onDrawPoint(point: LatLng) = intent {
        if (state.drawingMode != DrawingMode.DRAWING) return@intent
        reduce { state.copy(drawnPoints = state.drawnPoints + point) }
    }

    fun onDrawEnd() = intent {
        if (state.drawingMode != DrawingMode.DRAWING) return@intent
        if (state.drawnPoints.size < 2) {
            reduce { state.copy(drawingMode = DrawingMode.IDLE, drawnPoints = emptyList()) }
            return@intent
        }
        snapCurrentPath()
    }

    fun onClearDrawing() = intent {
        reduce {
            state.copy(
                drawingMode = DrawingMode.IDLE,
                drawnPoints = emptyList(),
                simplifiedPoints = emptyList(),
                isLoop = false,
                isContinuing = false,
                snappedRoute = emptyList(),
                routeMarkers = emptyList(),
                selectedMarkerIndex = -1,
                selectedSegmentIndex = -1,
                showDeleteSegmentDialog = false,
                routeStart = null,
                routeEnd = null,
                error = null,
                editHistory = emptyList(),
                dirtyStart = null,
                dirtyEnd = null
            )
        }
    }

    /** 마커 탭: 미선택 → 선택 / 선택 중 재탭 → 삭제 다이얼로그 표시 */
    fun onMarkerTapped(index: Int) = intent {
        if (state.selectedMarkerIndex == index) {
            reduce { state.copy(showDeleteMarkerDialog = true) }
        } else {
            reduce { state.copy(selectedMarkerIndex = index) }
        }
    }

    fun onDeleteMarkerTapped() = intent {
        reduce { state.copy(showDeleteMarkerDialog = true) }
    }

    fun onDeleteMarkerConfirmed() = intent {
        val idx = state.selectedMarkerIndex
        if (idx !in state.routeMarkers.indices) {
            reduce { state.copy(showDeleteMarkerDialog = false) }
            return@intent
        }
        val start = state.routeStart ?: return@intent
        val end = state.routeEnd ?: return@intent
        val prev = if (idx == 0) start else state.routeMarkers[idx - 1]
        val next = if (idx == state.routeMarkers.lastIndex) end else state.routeMarkers[idx + 1]
        val updatedMarkers = state.routeMarkers.toMutableList().also { it.removeAt(idx) }
        val newRoute = spliceRoute(state.snappedRoute, prev, next, listOf(prev, next))
        val newHistory = state.editHistory + EditSnapshot(
            state.routeStart,
            state.routeEnd,
            state.routeMarkers,
            state.snappedRoute,
            state.dirtyStart,
            state.dirtyEnd
        )
        val controlPointIndex = idx
        val adjustedStart = state.dirtyStart?.let { ds ->
            val shifted = if (ds > idx + 1) ds - 1 else ds
            minOf(shifted, controlPointIndex)
        } ?: controlPointIndex
        val adjustedEnd = state.dirtyEnd?.let { de ->
            val shifted = if (de >= idx + 1) de - 1 else de
            maxOf(shifted, controlPointIndex + 1)
        } ?: (controlPointIndex + 1)
        reduce {
            state.copy(
                routeMarkers = updatedMarkers,
                snappedRoute = newRoute,
                selectedMarkerIndex = -1,
                showDeleteMarkerDialog = false,
                hasPendingEdits = true,
                editHistory = newHistory,
                dirtyStart = adjustedStart.coerceIn(0, updatedMarkers.size + 1),
                dirtyEnd = adjustedEnd.coerceIn(0, updatedMarkers.size + 1)
            )
        }
    }

    fun onDeleteMarkerDismissed() = intent {
        reduce { state.copy(showDeleteMarkerDialog = false) }
    }

    fun onStartMarkerTapped() = intent {
        val next = if (state.selectedMarkerIndex == -2) -1 else -2
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    fun onEndMarkerTapped() = intent {
        val next = if (state.selectedMarkerIndex == -3) -1 else -3
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    /** 마커 드래그 시 마커 위치 갱신 및 로컬 경로 업데이트 */
    fun onMarkerDragged(index: Int, latLng: LatLng) = intent {
        if (state.routeMarkers.getOrNull(index) == latLng) return@intent
        val markers = state.routeMarkers
        if (index < 0 || index >= markers.size) return@intent
        val updatedMarkers = markers.toMutableList().also { it[index] = latLng }
        val start = state.routeStart ?: return@intent
        val end = state.routeEnd ?: return@intent
        val prev = if (index == 0) start else updatedMarkers[index - 1]
        val next = if (index == updatedMarkers.lastIndex) end else updatedMarkers[index + 1]
        val newRoute = spliceRoute(state.snappedRoute, prev, next, listOf(prev, latLng, next))
        val controlPointIndex = index + 1
        val newDirtyStart = state.dirtyStart?.let { minOf(it, controlPointIndex) } ?: controlPointIndex
        val newDirtyEnd = state.dirtyEnd?.let { maxOf(it, controlPointIndex) } ?: controlPointIndex
        reduce {
            state.copy(
                routeMarkers = updatedMarkers,
                snappedRoute = newRoute,
                hasPendingEdits = true,
                dirtyStart = newDirtyStart,
                dirtyEnd = newDirtyEnd
            )
        }
    }

    fun onStartMarkerDragged(latLng: LatLng) = intent {
        if (state.routeStart == latLng) return@intent
        val start = state.routeStart ?: return@intent
        val next = state.routeMarkers.firstOrNull() ?: state.routeEnd ?: return@intent
        val newRoute = spliceRoute(state.snappedRoute, start, next, listOf(latLng, next))
        val controlPointIndex = 0
        val newDirtyStart = state.dirtyStart?.let { minOf(it, controlPointIndex) } ?: controlPointIndex
        val newDirtyEnd = state.dirtyEnd?.let { maxOf(it, controlPointIndex) } ?: controlPointIndex
        reduce {
            state.copy(
                routeStart = latLng,
                snappedRoute = newRoute,
                hasPendingEdits = true,
                dirtyStart = newDirtyStart,
                dirtyEnd = newDirtyEnd
            )
        }
    }

    fun onEndMarkerDragged(latLng: LatLng) = intent {
        if (state.routeEnd == latLng) return@intent
        val end = state.routeEnd ?: return@intent
        val prev = state.routeMarkers.lastOrNull() ?: state.routeStart ?: return@intent
        val newRoute = spliceRoute(state.snappedRoute, prev, end, listOf(prev, latLng))
        val controlPointIndex = state.routeMarkers.size + 1
        val newDirtyStart = state.dirtyStart?.let { minOf(it, controlPointIndex) } ?: controlPointIndex
        val newDirtyEnd = state.dirtyEnd?.let { maxOf(it, controlPointIndex) } ?: controlPointIndex
        reduce {
            state.copy(
                routeEnd = latLng,
                snappedRoute = newRoute,
                hasPendingEdits = true,
                dirtyStart = newDirtyStart,
                dirtyEnd = newDirtyEnd
            )
        }
    }

    /** 구간 탭 → 삭제 확인 다이얼로그 표시 */
    fun onSegmentTapped(index: Int) = intent {
        reduce {
            state.copy(
                selectedSegmentIndex = index,
                showDeleteSegmentDialog = true,
                selectedMarkerIndex = -1   // 마커 선택 해제
            )
        }
    }

    /** 구간 삭제 확인: 경계 마커를 제거하고 앞뒤 경계 사이 구간만 재탐색 */
    fun onDeleteSegmentConfirmed() = intent {
        val segIdx = state.selectedSegmentIndex
        val markers = state.routeMarkers
        if (segIdx < 0 || markers.isEmpty()) {
            reduce { state.copy(selectedSegmentIndex = -1, showDeleteSegmentDialog = false) }
            return@intent
        }
        val markerIdx = segIdx.coerceAtMost(markers.lastIndex)
        val start = state.routeStart ?: return@intent
        val end = state.routeEnd ?: return@intent
        val prev = if (markerIdx == 0) start else markers[markerIdx - 1]
        val next = if (markerIdx == markers.lastIndex) end else markers[markerIdx + 1]
        val updatedMarkers = markers.toMutableList().also { it.removeAt(markerIdx) }
        val newRoute = spliceRoute(state.snappedRoute, prev, next, listOf(prev, next))
        val newHistory = state.editHistory + EditSnapshot(
            state.routeStart,
            state.routeEnd,
            state.routeMarkers,
            state.snappedRoute,
            state.dirtyStart,
            state.dirtyEnd
        )
        val controlPointIndex = markerIdx
        val adjustedStart = state.dirtyStart?.let { ds ->
            val shifted = if (ds > markerIdx + 1) ds - 1 else ds
            minOf(shifted, controlPointIndex)
        } ?: controlPointIndex
        val adjustedEnd = state.dirtyEnd?.let { de ->
            val shifted = if (de >= markerIdx + 1) de - 1 else de
            maxOf(shifted, controlPointIndex + 1)
        } ?: (controlPointIndex + 1)
        reduce {
            state.copy(
                routeMarkers = updatedMarkers,
                snappedRoute = newRoute,
                selectedSegmentIndex = -1,
                showDeleteSegmentDialog = false,
                hasPendingEdits = true,
                editHistory = newHistory,
                dirtyStart = adjustedStart.coerceIn(0, updatedMarkers.size + 1),
                dirtyEnd = adjustedEnd.coerceIn(0, updatedMarkers.size + 1)
            )
        }
    }

    /** 구간 삭제 취소 */
    fun onDeleteSegmentDismissed() = intent {
        reduce { state.copy(selectedSegmentIndex = -1, showDeleteSegmentDialog = false) }
    }

    /** 지도 탭: 선택된 마커를 해당 위치로 이동 후 해당 구간만 재탐색 */
    fun onMapTapped(latLng: LatLng) = intent {
        val idx = state.selectedMarkerIndex
        val newHistory = state.editHistory + EditSnapshot(
            state.routeStart,
            state.routeEnd,
            state.routeMarkers,
            state.snappedRoute,
            state.dirtyStart,
            state.dirtyEnd
        )
        when (idx) {
            -2 -> {
                val start = state.routeStart ?: return@intent
                val next = state.routeMarkers.firstOrNull() ?: state.routeEnd ?: return@intent
                val newRoute = spliceRoute(state.snappedRoute, start, next, listOf(latLng, next))
                val controlPointIndex = 0
                val newDirtyStart = state.dirtyStart?.let { minOf(it, controlPointIndex) } ?: controlPointIndex
                val newDirtyEnd = state.dirtyEnd?.let { maxOf(it, controlPointIndex) } ?: controlPointIndex
                reduce {
                    state.copy(
                        routeStart = latLng,
                        snappedRoute = newRoute,
                        selectedMarkerIndex = -1,
                        hasPendingEdits = true,
                        editHistory = newHistory,
                        dirtyStart = newDirtyStart,
                        dirtyEnd = newDirtyEnd
                    )
                }
            }
            -3 -> {
                val end = state.routeEnd ?: return@intent
                val prev = state.routeMarkers.lastOrNull() ?: state.routeStart ?: return@intent
                val newRoute = spliceRoute(state.snappedRoute, prev, end, listOf(prev, latLng))
                val controlPointIndex = state.routeMarkers.size + 1
                val newDirtyStart = state.dirtyStart?.let { minOf(it, controlPointIndex) } ?: controlPointIndex
                val newDirtyEnd = state.dirtyEnd?.let { maxOf(it, controlPointIndex) } ?: controlPointIndex
                reduce {
                    state.copy(
                        routeEnd = latLng,
                        snappedRoute = newRoute,
                        selectedMarkerIndex = -1,
                        hasPendingEdits = true,
                        editHistory = newHistory,
                        dirtyStart = newDirtyStart,
                        dirtyEnd = newDirtyEnd
                    )
                }
            }
            else -> {
                val markers = state.routeMarkers
                if (idx < 0 || idx >= markers.size) return@intent
                val updatedMarkers = markers.toMutableList().also { it[idx] = latLng }
                val start = state.routeStart ?: return@intent
                val end = state.routeEnd ?: return@intent
                val prev = if (idx == 0) start else updatedMarkers[idx - 1]
                val next = if (idx == updatedMarkers.lastIndex) end else updatedMarkers[idx + 1]
                val newRoute = spliceRoute(state.snappedRoute, prev, next, listOf(prev, latLng, next))
                val controlPointIndex = idx + 1
                val newDirtyStart = state.dirtyStart?.let { minOf(it, controlPointIndex) } ?: controlPointIndex
                val newDirtyEnd = state.dirtyEnd?.let { maxOf(it, controlPointIndex) } ?: controlPointIndex
                reduce {
                    state.copy(
                        routeMarkers = updatedMarkers,
                        snappedRoute = newRoute,
                        selectedMarkerIndex = -1,
                        hasPendingEdits = true,
                        editHistory = newHistory,
                        dirtyStart = newDirtyStart,
                        dirtyEnd = newDirtyEnd
                    )
                }
            }
        }
    }

    /** 선택 해제 */
    fun onMarkerDeselect() = intent {
        reduce { state.copy(selectedMarkerIndex = -1) }
    }

    fun onDragStart() = intent {
        val currentSnapshot = EditSnapshot(
            routeStart = state.routeStart,
            routeEnd = state.routeEnd,
            routeMarkers = state.routeMarkers,
            snappedRoute = state.snappedRoute,
            dirtyStart = state.dirtyStart,
            dirtyEnd = state.dirtyEnd
        )
        reduce {
            state.copy(
                editHistory = state.editHistory + currentSnapshot
            )
        }
    }

    fun onUndo() = intent {
        val history = state.editHistory
        if (history.isEmpty()) return@intent
        val lastSnapshot = history.last()
        val updatedHistory = history.subList(0, history.size - 1)
        reduce {
            state.copy(
                routeStart = lastSnapshot.routeStart,
                routeEnd = lastSnapshot.routeEnd,
                routeMarkers = lastSnapshot.routeMarkers,
                snappedRoute = lastSnapshot.snappedRoute,
                editHistory = updatedHistory,
                hasPendingEdits = updatedHistory.isNotEmpty(),
                selectedMarkerIndex = -1,
                selectedSegmentIndex = -1,
                dirtyStart = lastSnapshot.dirtyStart,
                dirtyEnd = lastSnapshot.dirtyEnd
            )
        }
    }

    /**
     * 국소 재라우팅 처리: 변경된 마커 앞뒤 구간만 T-Map 1회 호출로 재탐색.
     * debounce를 통해 호출되므로 연속 편집 시 마지막 이벤트만 실행.
     */
    fun onApplyEdits() = intent {
        if (!state.hasPendingEdits) return@intent
        val start = state.routeStart ?: return@intent
        val end = state.routeEnd ?: return@intent
        val currentMarkers = state.routeMarkers
        val P_current = listOf(start) + currentMarkers + listOf(end)
        
        val dirtyStartVal = state.dirtyStart ?: 0
        val dirtyEndVal = state.dirtyEnd ?: (P_current.size - 1)
        
        val startIdx = maxOf(0, dirtyStartVal - 1)
        val endIdx = minOf(P_current.size - 1, dirtyEndVal + 1)
        
        val waypoints = P_current.subList(startIdx, endIdx + 1)
        if (waypoints.size < 2) {
            reduce {
                state.copy(
                    hasPendingEdits = false,
                    dirtyStart = null,
                    dirtyEnd = null
                )
            }
            return@intent
        }
        
        val checkResult = checkApiLimitUseCase()
        if (checkResult is CheckApiLimitUseCase.CheckResult.Blocked) {
            reduce { state.copy(isAdPromptDialogVisible = true) }
            return@intent
        }

        reduce { state.copy(isProcessing = true) }
        val result = withContext(Dispatchers.IO) { snapToRoad.fromWaypoints(waypoints, state.epsilonRouteDeg) }
        result.fold(
            onSuccess = { newSubRoute ->
                incrementApiCountUseCase()
                val updatedCheckResult = checkApiLimitUseCase()
                val usage = (updatedCheckResult as? CheckApiLimitUseCase.CheckResult.Allowed)?.usage ?: return@fold

                val fromPt = P_current[startIdx]
                val toPt = P_current[endIdx]
                
                val fromIdx = if (startIdx == 0) 0 else indexOfClosest(state.snappedRoute, fromPt)
                val toIdx = if (endIdx == P_current.size - 1) state.snappedRoute.size - 1 else indexOfClosest(state.snappedRoute, toPt)
                
                val newSnappedRoute = if (fromIdx >= 0 && toIdx >= 0 && fromIdx < toIdx) {
                    state.snappedRoute.subList(0, fromIdx) + newSubRoute + state.snappedRoute.subList(toIdx + 1, state.snappedRoute.size)
                } else {
                    state.snappedRoute
                }
                
                val newSubMarkers = sampleMarkers(newSubRoute, intervalMeters = state.markerIntervalMeters)
                
                val updatedMarkers = currentMarkers.subList(0, startIdx) + newSubMarkers + currentMarkers.subList(endIdx - 1, currentMarkers.size)
                
                reduce {
                    state.copy(
                        snappedRoute = newSnappedRoute,
                        routeMarkers = updatedMarkers,
                        routeStart = newSnappedRoute.firstOrNull() ?: state.routeStart,
                        routeEnd = newSnappedRoute.lastOrNull() ?: state.routeEnd,
                        hasPendingEdits = false,
                        dirtyStart = null,
                        dirtyEnd = null,
                        editHistory = emptyList(),
                        isProcessing = false,
                        error = null,
                        tmapApiCallCount = usage.dailyCount
                    )
                }
                postSideEffect(MapSideEffect.ShowToast("경로 재탐색이 완료되었습니다"))
            },
            onFailure = { e ->
                reduce {
                    state.copy(
                        isProcessing = false,
                        error = e.message
                    )
                }
                postSideEffect(MapSideEffect.ShowToast(e.message ?: "경로 재탐색에 실패했습니다"))
            }
        )
    }

    /**
     * snappedRoute 에서 fromPt~toPt 구간을 replacement 로 교체.
     * indexOf 로 경계점을 찾아 그 사이를 잘라내고 새 sub-route 를 삽입.
     * 경계점을 찾지 못하면 원본 경로 그대로 반환.
     */
    private fun indexOfClosest(list: List<LatLng>, point: LatLng): Int {
        var minDistance = Double.MAX_VALUE
        var minIdx = -1
        for (i in list.indices) {
            val dist = haversineMeters(list[i], point)
            if (dist < minDistance) {
                minDistance = dist
                minIdx = i
            }
        }
        return minIdx
    }

    private fun spliceRoute(
        full: List<LatLng>,
        fromPt: LatLng,
        toPt: LatLng,
        replacement: List<LatLng>
    ): List<LatLng> {
        val fromIdx = indexOfClosest(full, fromPt)
        val toIdx   = indexOfClosest(full, toPt)
        if (fromIdx < 0 || toIdx < 0 || fromIdx >= toIdx) return full
        // full[0..fromIdx-1] + replacement + full[toIdx+1..end]
        return full.subList(0, fromIdx) + replacement + full.subList(toIdx + 1, full.size)
    }

    /** 경로에서 [intervalMeters] 간격으로 중간 마커 좌표 추출 (시작·끝 제외) */
    private fun sampleMarkers(route: List<LatLng>, intervalMeters: Double): List<LatLng> {
        if (route.size < 2) return emptyList()
        val result = mutableListOf<LatLng>()
        var accumulated = 0.0
        for (i in 1 until route.size - 1) {
            accumulated += haversineMeters(route[i - 1], route[i])
            if (accumulated >= intervalMeters) {
                result.add(route[i])
                accumulated = 0.0
            }
        }
        return result
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val c = sinLat * sinLat +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLon * sinLon
        return 2 * R * asin(sqrt(c))
    }

    private fun snapCurrentPath() = intent {
        val raw = state.drawnPoints
        Log.d("SnapDebug", "snapCurrentPath: points.size=${raw.size}, continuing=${state.isContinuing}")
        if (raw.size < 2) {
            reduce { state.copy(drawingMode = if (state.isContinuing) DrawingMode.DONE else DrawingMode.IDLE) }
            return@intent
        }

        // 이어 그리기: 기존 경로 끝점을 첫 웨이포인트로 연결
        val basePoints = if (state.isContinuing && state.snappedRoute.isNotEmpty()) {
            listOf(state.snappedRoute.last()) + raw
        } else {
            raw
        }

        // 시작점·끝점이 LOOP_CLOSE_THRESHOLD_M 이내면 시작점을 끝에 추가해 경로를 닫음
        val isLoop = !state.isContinuing &&
                haversineMeters(basePoints.first(), basePoints.last()) <= LOOP_CLOSE_THRESHOLD_M
        val points = if (isLoop) basePoints + basePoints.first() else basePoints

        // 1단계: RDP 직선화 → 즉시 오버레이에 반영하여 사용자에게 피드백
        val simplified = withContext(Dispatchers.Default) { simplifyPath(points, state.epsilonDrawnDeg) }
        reduce {
            state.copy(
                drawingMode = DrawingMode.PROCESSING,
                isProcessing = true,
                isLoop = isLoop,
                simplifiedPoints = simplified
            )
        }

        // 2단계: 직선화된 경로를 T-Map에 전달하여 실도로 스냅
        val checkResult = checkApiLimitUseCase()
        if (checkResult is CheckApiLimitUseCase.CheckResult.Blocked) {
            reduce {
                state.copy(
                    isAdPromptDialogVisible = true,
                    drawingMode = if (state.snappedRoute.isNotEmpty()) DrawingMode.DONE else DrawingMode.IDLE,
                    isProcessing = false
                )
            }
            return@intent
        }

        val result = withContext(Dispatchers.IO) { snapToRoad(points, state.epsilonDrawnDeg, state.epsilonRouteDeg) }

        Log.d("SnapDebug", "snapToRoad result: isSuccess=${result.isSuccess}, error=${result.exceptionOrNull()?.message}")

        result.fold(
            onSuccess = { newRoute ->
                Log.d("SnapDebug", "route size=${newRoute.size}, continuing=${state.isContinuing}")

                incrementApiCountUseCase()
                val updatedCheckResult = checkApiLimitUseCase()
                val usage = (updatedCheckResult as? CheckApiLimitUseCase.CheckResult.Allowed)?.usage ?: return@fold

                // 이어 그리기: 기존 경로 뒤에 새 구간 연결 (연결점 중복 제거)
                val combined = if (state.isContinuing && state.snappedRoute.isNotEmpty()) {
                    state.snappedRoute + newRoute.drop(1)
                } else {
                    newRoute
                }

                val markers = sampleMarkers(combined, intervalMeters = state.markerIntervalMeters)
                reduce {
                    state.copy(
                        drawingMode = DrawingMode.DONE,
                        isContinuing = false,
                        snappedRoute = combined,
                        simplifiedPoints = emptyList(),
                        routeMarkers = markers,
                        selectedMarkerIndex = -1,
                        routeStart = combined.first(),
                        routeEnd = combined.last(),
                        editHistory = emptyList(),
                        isProcessing = false,
                        error = null,
                        dirtyStart = null,
                        dirtyEnd = null,
                        tmapApiCallCount = usage.dailyCount
                    )
                }
            },
            onFailure = { e ->
                Log.e("SnapDebug", "snapToRoad FAILED: ${e.message}", e)
                reduce {
                    state.copy(
                        drawingMode = if (state.snappedRoute.isNotEmpty()) DrawingMode.DONE else DrawingMode.IDLE,
                        isContinuing = false,
                        isProcessing = false,
                        error = e.message
                    )
                }
                postSideEffect(
                    MapSideEffect.ShowToast(e.message ?: "경로 탐색에 실패했습니다")
                )
            }
        )
    }

    fun onDismissAdPrompt() = intent { reduce { state.copy(isAdPromptDialogVisible = false) } }
    fun onWatchAdRequested() = intent {
        reduce { state.copy(isAdPromptDialogVisible = false) }
        postSideEffect(MapSideEffect.ShowRewardedAd)
    }
    fun onWatchAdCompleted() = intent {
        rechargeApiLimitUseCase()
        val checkResult = checkApiLimitUseCase()
        val usage = when(checkResult) {
            is CheckApiLimitUseCase.CheckResult.Allowed -> checkResult.usage
            is CheckApiLimitUseCase.CheckResult.Blocked -> checkResult.usage
        }
        reduce { state.copy(tmapMaxLimitCount = 30 + usage.rechargedCount, tmapApiCallCount = usage.dailyCount) }
        postSideEffect(MapSideEffect.ShowToast("API 한도가 10회 충전되었습니다"))
    }
}

