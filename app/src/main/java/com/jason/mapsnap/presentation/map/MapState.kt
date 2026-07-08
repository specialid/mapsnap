package com.jason.mapsnap.presentation.map

import androidx.compose.runtime.Immutable
import com.jason.mapsnap.domain.model.SavedRoute
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.compose.MapType
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import com.jason.mapsnap.domain.util.GeoUtils.haversineMeters

@Immutable
data class EditSnapshot(
    val routeStart: LatLng?,
    val routeEnd: LatLng?,
    val routeMarkers: List<LatLng>,
    val snappedRoute: List<LatLng>,
    val dirtyRanges: List<IntRange> = emptyList()
)

@Immutable
data class MapState(
    val drawingMode: DrawingMode = DrawingMode.IDLE,
    val drawnPoints: List<LatLng> = emptyList(),
    val pendingStrokes: List<List<LatLng>> = emptyList(), // 완료 전 로컬 누적된 이전 스트로크들 — 배치 스냅용
    val simplifiedPoints: List<LatLng> = emptyList(), // RDP 직선화 결과 — PROCESSING 중 오버레이에 표시
    val snappedRoute: List<LatLng> = emptyList(),
    val isLoop: Boolean = false,
    val isContinuing: Boolean = false,            // 기존 경로에 이어 그리는 중
    val routeMarkers: List<LatLng> = emptyList(), // 결과 경로 위 편집 가능한 중간 마커
    val selectedMarkerIndex: Int = -1,            // -1 = 선택 없음
    val selectedSegmentIndex: Int = -1,           // 탭된 구간 인덱스 (-1 = 없음)
    val showDeleteSegmentDialog: Boolean = false,
    val showDeleteMarkerDialog: Boolean = false,
    val routeStart: LatLng? = null,               // 마커 재라우팅 시 고정 시작점
    val routeEnd: LatLng? = null,                 // 마커 재라우팅 시 고정 끝점
    val currentLocation: LatLng? = null,
    val isProcessing: Boolean = false,
    val error: String? = null,
    val tmapApiCallCount: Int = 0,
    val tmapMaxLimitCount: Int = 30,
    val isAdPromptDialogVisible: Boolean = false,
    val naverMapApiCallCount: Int = 0,
    val hasPendingEdits: Boolean = false,
    val editHistory: List<EditSnapshot> = emptyList(),
    // 편집 클러스터별 재라우팅 대상 구간(관리 포인트 인덱스, inclusive) — 떨어진 편집은 별도 구간으로 유지되어
    // Apply 시 서로 다른 클러스터 사이 미편집 구간까지 재라우팅에 포함되지 않는다
    val dirtyRanges: List<IntRange> = emptyList(),
    val mapType: MapType = MapType.Basic,
    val markerIntervalMeters: Double = 80.0,
    val epsilonDrawnDeg: Double = 0.000135,
    val epsilonRouteDeg: Double = 0.000072,
    val includeTimestamps: Boolean = false,
    val runningPaceSecPerKm: Int = 360,
    val savedRoutes: List<SavedRoute> = emptyList(),
    val showSaveRouteDialog: Boolean = false,
    val showLoadRouteDialog: Boolean = false
) {
    val canUndo: Boolean get() = editHistory.isNotEmpty()

    val totalDistanceMeters: Double get() = computeTotalDistance(snappedRoute)
}

private fun computeTotalDistance(route: List<LatLng>): Double {
    if (route.size < 2) return 0.0
    var dist = 0.0
    for (i in 0 until route.size - 1) {
        dist += haversineMeters(route[i], route[i + 1])
    }
    return dist
}

enum class DrawingMode { IDLE, DRAWING, PROCESSING, DONE }
