package com.jason.mapsnap.presentation.map

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jason.mapsnap.domain.usecase.ExportGpxUseCase
import com.jason.mapsnap.domain.usecase.SimplifyPathUseCase
import com.jason.mapsnap.domain.usecase.SnapToRoadUseCase
import com.naver.maps.geometry.LatLng
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
    private val exportGpx: ExportGpxUseCase
) : ViewModel(), ContainerHost<MapState, MapSideEffect> {

    override val container = container<MapState, MapSideEffect>(MapState())

    companion object {
        const val LOOP_CLOSE_THRESHOLD_M = 30.0
        private const val REROUTE_DEBOUNCE_MS = 300L
        private const val DEFAULT_INTERVAL_METERS = 80.0
    }

    // 편집 이벤트를 300ms debounce로 병합 — 빠른 연속 탭 시 마지막 1회만 API 호출
    private val _rerouteSignal = MutableSharedFlow<PartialRerouteEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        viewModelScope.launch {
            _rerouteSignal.debounce(REROUTE_DEBOUNCE_MS).collect { event ->
                handlePartialReroute(event)
            }
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
                        routeEnd = null
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
                error = null
            )
        }
    }

    /** 마커 탭: 미선택 → 선택 / 선택 중 재탭 → 해제 */
    fun onMarkerTapped(index: Int) = intent {
        val next = if (state.selectedMarkerIndex == index) -1 else index
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    fun onStartMarkerTapped() = intent {
        val next = if (state.selectedMarkerIndex == -2) -1 else -2
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    fun onEndMarkerTapped() = intent {
        val next = if (state.selectedMarkerIndex == -3) -1 else -3
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    /** 마커 드래그 시 마커 위치 갱신 및 국소 재라우팅 신호 전송 */
    fun onMarkerDragged(index: Int, latLng: LatLng) = intent {
        val markers = state.routeMarkers
        if (index < 0 || index >= markers.size) return@intent
        val updated = markers.toMutableList().also { it[index] = latLng }
        reduce { state.copy(routeMarkers = updated) }
        _rerouteSignal.emit(PartialRerouteEvent.MarkerMoved(index, latLng))
    }

    fun onStartMarkerDragged(latLng: LatLng) = intent {
        reduce { state.copy(routeStart = latLng) }
        _rerouteSignal.emit(PartialRerouteEvent.StartMoved(latLng))
    }

    fun onEndMarkerDragged(latLng: LatLng) = intent {
        reduce { state.copy(routeEnd = latLng) }
        _rerouteSignal.emit(PartialRerouteEvent.EndMoved(latLng))
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
        val updated = markers.toMutableList().also { it.removeAt(markerIdx) }
        reduce {
            state.copy(
                routeMarkers = updated,
                selectedSegmentIndex = -1,
                showDeleteSegmentDialog = false
            )
        }
        _rerouteSignal.emit(PartialRerouteEvent.MarkerRemoved(markerIdx))
    }

    /** 구간 삭제 취소 */
    fun onDeleteSegmentDismissed() = intent {
        reduce { state.copy(selectedSegmentIndex = -1, showDeleteSegmentDialog = false) }
    }

    /** 지도 탭: 선택된 마커를 해당 위치로 이동 후 해당 구간만 재탐색 */
    fun onMapTapped(latLng: LatLng) = intent {
        val idx = state.selectedMarkerIndex
        when (idx) {
            -2 -> {
                reduce { state.copy(routeStart = latLng, selectedMarkerIndex = -1) }
                _rerouteSignal.emit(PartialRerouteEvent.StartMoved(latLng))
            }
            -3 -> {
                reduce { state.copy(routeEnd = latLng, selectedMarkerIndex = -1) }
                _rerouteSignal.emit(PartialRerouteEvent.EndMoved(latLng))
            }
            else -> {
                if (idx < 0 || idx >= state.routeMarkers.size) return@intent
                val updated = state.routeMarkers.toMutableList().also { it[idx] = latLng }
                reduce { state.copy(routeMarkers = updated, selectedMarkerIndex = -1) }
                _rerouteSignal.emit(PartialRerouteEvent.MarkerMoved(idx, latLng))
            }
        }
    }

    /** 선택 해제 */
    fun onMarkerDeselect() = intent {
        reduce { state.copy(selectedMarkerIndex = -1) }
    }

    /**
     * 국소 재라우팅 처리: 변경된 마커 앞뒤 구간만 T-Map 1회 호출로 재탐색.
     * debounce를 통해 호출되므로 연속 편집 시 마지막 이벤트만 실행.
     */
    private fun handlePartialReroute(event: PartialRerouteEvent) = intent {
        val route   = state.snappedRoute
        val markers = state.routeMarkers
        val start   = route.firstOrNull() ?: return@intent
        val end     = route.lastOrNull() ?: return@intent
        if (route.size < 2) return@intent

        // 이벤트 종류에 따라 경계점 및 waypoints 결정
        val (prevBoundary, nextBoundary, waypoints) = when (event) {
            is PartialRerouteEvent.MarkerMoved -> {
                val idx = event.idx
                val prev = if (idx == 0) start else markers.getOrNull(idx - 1) ?: start
                val next = if (idx >= markers.lastIndex) end else markers.getOrNull(idx + 1) ?: end
                Triple(prev, next, listOf(prev, event.newPos, next))
            }
            is PartialRerouteEvent.MarkerRemoved -> {
                val idx = event.markerIdx
                // 삭제 후 갱신된 markers 기준: idx 위치의 앞뒤
                val prev = if (idx == 0) start else markers.getOrNull(idx - 1) ?: start
                val next = markers.getOrNull(idx) ?: end   // 삭제 후 idx에 있는 것이 다음 마커
                Triple(prev, next, listOf(prev, next))
            }
            is PartialRerouteEvent.StartMoved -> {
                val next = markers.firstOrNull() ?: end
                Triple(start, next, listOf(event.newPos, next))
            }
            is PartialRerouteEvent.EndMoved -> {
                val prev = markers.lastOrNull() ?: start
                Triple(prev, end, listOf(prev, event.newPos))
            }
        }

        reduce { state.copy(isProcessing = true) }
        Log.d("SnapDebug", "partialReroute: ${waypoints.size} waypoints")

        val result = withContext(Dispatchers.IO) { snapToRoad.fromWaypoints(waypoints) }

        result.fold(
            onSuccess = { subRoute ->
                val newRoute = spliceRoute(route, prevBoundary, nextBoundary, subRoute)
                reduce {
                    val currentMarkers = state.routeMarkers
                    val newMarkers = when (event) {
                        is PartialRerouteEvent.MarkerMoved -> {
                            val snappedPos = subRoute.minByOrNull { haversineMeters(it, event.newPos) } ?: event.newPos
                            currentMarkers.toMutableList().also {
                                if (event.idx in it.indices) {
                                    it[event.idx] = snappedPos
                                }
                                // 인접 마커도 Tmap API가 반환한 subRoute의 양끝점으로 스냅 업데이트
                                if (event.idx > 0 && event.idx - 1 in it.indices) {
                                    it[event.idx - 1] = subRoute.first()
                                }
                                if (event.idx < it.lastIndex && event.idx + 1 in it.indices) {
                                    it[event.idx + 1] = subRoute.last()
                                }
                            }
                        }
                        is PartialRerouteEvent.MarkerRemoved -> {
                            currentMarkers
                        }
                        is PartialRerouteEvent.StartMoved -> {
                            currentMarkers.toMutableList().also {
                                if (it.isNotEmpty()) {
                                    it[0] = subRoute.last()
                                }
                            }
                        }
                        is PartialRerouteEvent.EndMoved -> {
                            currentMarkers.toMutableList().also {
                                if (it.isNotEmpty()) {
                                    it[it.lastIndex] = subRoute.first()
                                }
                            }
                        }
                    }
                    state.copy(
                        snappedRoute = newRoute,
                        routeMarkers = newMarkers,
                        routeStart = if (event is PartialRerouteEvent.StartMoved) subRoute.first() else (newRoute.firstOrNull() ?: state.routeStart),
                        routeEnd = if (event is PartialRerouteEvent.EndMoved) subRoute.last() else (newRoute.lastOrNull() ?: state.routeEnd),
                        selectedMarkerIndex = -1,
                        isProcessing = false,
                        error = null
                    )
                }
            },
            onFailure = { e ->
                Log.e("SnapDebug", "partialReroute FAILED: ${e.message}", e)
                reduce { state.copy(isProcessing = false, error = e.message) }
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
        val simplified = withContext(Dispatchers.Default) { simplifyPath(points) }
        reduce {
            state.copy(
                drawingMode = DrawingMode.PROCESSING,
                isProcessing = true,
                isLoop = isLoop,
                simplifiedPoints = simplified
            )
        }

        // 2단계: 직선화된 경로를 T-Map에 전달하여 실도로 스냅
        val result = withContext(Dispatchers.IO) { snapToRoad(points) }

        Log.d("SnapDebug", "snapToRoad result: isSuccess=${result.isSuccess}, error=${result.exceptionOrNull()?.message}")

        result.fold(
            onSuccess = { newRoute ->
                Log.d("SnapDebug", "route size=${newRoute.size}, continuing=${state.isContinuing}")

                // 이어 그리기: 기존 경로 뒤에 새 구간 연결 (연결점 중복 제거)
                val combined = if (state.isContinuing && state.snappedRoute.isNotEmpty()) {
                    state.snappedRoute + newRoute.drop(1)
                } else {
                    newRoute
                }

                val markers = sampleMarkers(combined, intervalMeters = DEFAULT_INTERVAL_METERS)
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
                        isProcessing = false,
                        error = null
                    )
                }
            },
            onFailure = { e ->
                Log.e("SnapDebug", "snapToRoad FAILED: ${e.message}", e)
                reduce {
                    state.copy(
                        drawingMode = if (state.isContinuing) DrawingMode.DONE else DrawingMode.DONE,
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
}

/** 국소 재라우팅 이벤트 — debounce Flow 를 통해 handlePartialReroute 에 전달 */
sealed class PartialRerouteEvent {
    /** i번째 마커가 newPos 로 이동 */
    data class MarkerMoved(val idx: Int, val newPos: LatLng) : PartialRerouteEvent()
    /** markerIdx 번째 마커가 삭제됨 (삭제 후 routeMarkers 기준 인덱스) */
    data class MarkerRemoved(val markerIdx: Int)             : PartialRerouteEvent()
    /** 출발지가 newPos 로 이동 */
    data class StartMoved(val newPos: LatLng)                 : PartialRerouteEvent()
    /** 도착지가 newPos 로 이동 */
    data class EndMoved(val newPos: LatLng)                   : PartialRerouteEvent()
}
