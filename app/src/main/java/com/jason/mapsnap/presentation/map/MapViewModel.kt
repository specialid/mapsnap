package com.jason.mapsnap.presentation.map

import android.util.Log
import androidx.lifecycle.ViewModel
import com.jason.mapsnap.domain.usecase.SimplifyPathUseCase
import com.jason.mapsnap.domain.usecase.SnapToRoadUseCase
import com.naver.maps.geometry.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    private val simplifyPath: SimplifyPathUseCase
) : ViewModel(), ContainerHost<MapState, MapSideEffect> {

    override val container = container<MapState, MapSideEffect>(MapState())

    companion object {
        /** 시작점과 끝점이 이 거리(미터) 이내면 루프로 자동 연결 */
        const val LOOP_CLOSE_THRESHOLD_M = 30.0
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) intent { reduce { state.copy(currentLocation = null) } }
    }

    fun onLocationReceived(latLng: LatLng) = intent {
        reduce { state.copy(currentLocation = latLng) }
    }

    fun onDrawToggle() = intent {
        when (state.drawingMode) {
            DrawingMode.IDLE, DrawingMode.DONE -> {
                reduce {
                    state.copy(
                        drawingMode = DrawingMode.DRAWING,
                        drawnPoints = emptyList(),
                        snappedRoute = emptyList()
                    )
                }
            }
            DrawingMode.DRAWING -> {
                // 그리기 모드에서 다시 누르면 현재까지 그린 것으로 스냅 시도
                snapCurrentPath()
            }
            DrawingMode.PROCESSING -> { /* 처리 중 무시 */ }
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
                snappedRoute = emptyList(),
                routeMarkers = emptyList(),
                selectedMarkerIndex = -1,
                routeStart = null,
                routeEnd = null,
                error = null
            )
        }
    }

    /** 마커 탭: 미선택 → 선택 / 이미 선택된 마커 탭 → 삭제 후 재라우팅 */
    fun onMarkerTapped(index: Int) = intent {
        if (state.selectedMarkerIndex == index) {
            val updated = state.routeMarkers.toMutableList().also { it.removeAt(index) }
            reduce { state.copy(routeMarkers = updated, selectedMarkerIndex = -1) }
            rerouteWithMarkers()
        } else {
            reduce { state.copy(selectedMarkerIndex = index) }
        }
    }

    /** 지도 탭: 선택된 마커가 있으면 해당 위치로 이동 후 재라우팅 */
    fun onMapTapped(latLng: LatLng) = intent {
        val idx = state.selectedMarkerIndex
        if (idx < 0 || idx >= state.routeMarkers.size) return@intent
        val updated = state.routeMarkers.toMutableList().also { it[idx] = latLng }
        reduce { state.copy(routeMarkers = updated, selectedMarkerIndex = -1) }
        rerouteWithMarkers()
    }

    /** 선택 해제 */
    fun onMarkerDeselect() = intent {
        reduce { state.copy(selectedMarkerIndex = -1) }
    }

    /** 편집된 마커 목록으로 T-Map 재라우팅 */
    private fun rerouteWithMarkers() = intent {
        val start = state.routeStart ?: return@intent
        val end   = state.routeEnd   ?: return@intent
        val waypoints = listOf(start) + state.routeMarkers + listOf(end)

        reduce { state.copy(isProcessing = true) }

        val result = withContext(Dispatchers.IO) { snapToRoad.fromWaypoints(waypoints) }

        result.fold(
            onSuccess = { route ->
                reduce {
                    state.copy(
                        snappedRoute = route,
                        routeMarkers = sampleMarkers(route, intervalMeters = 30.0),
                        selectedMarkerIndex = -1,
                        routeStart = route.first(),
                        routeEnd = route.last(),
                        isProcessing = false,
                        error = null
                    )
                }
            },
            onFailure = { e ->
                reduce { state.copy(isProcessing = false, error = e.message) }
                postSideEffect(MapSideEffect.ShowToast(e.message ?: "경로 재탐색에 실패했습니다"))
            }
        )
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
        Log.d("SnapDebug", "snapCurrentPath: points.size=${raw.size}")
        if (raw.size < 2) {
            reduce { state.copy(drawingMode = DrawingMode.IDLE) }
            return@intent
        }

        // 시작점·끝점이 LOOP_CLOSE_THRESHOLD_M 이내면 시작점을 끝에 추가해 경로를 닫음
        val isLoop = haversineMeters(raw.first(), raw.last()) <= LOOP_CLOSE_THRESHOLD_M
        val points = if (isLoop) raw + raw.first() else raw

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
            onSuccess = { route ->
                Log.d("SnapDebug", "route size=${route.size}, first=${route.firstOrNull()}")
                val markers = sampleMarkers(route, intervalMeters = 30.0)
                reduce {
                    state.copy(
                        drawingMode = DrawingMode.DONE,
                        snappedRoute = route,
                        simplifiedPoints = emptyList(),
                        routeMarkers = markers,
                        selectedMarkerIndex = -1,
                        routeStart = route.first(),
                        routeEnd = route.last(),
                        isProcessing = false,
                        error = null
                    )
                }
            },
            onFailure = { e ->
                Log.e("SnapDebug", "snapToRoad FAILED: ${e.message}", e)
                reduce {
                    state.copy(
                        drawingMode = DrawingMode.DONE,
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
