package com.jason.mapsnap.presentation.map

import androidx.compose.runtime.Immutable
import com.naver.maps.geometry.LatLng

@Immutable
data class MapState(
    val drawingMode: DrawingMode = DrawingMode.IDLE,
    val drawnPoints: List<LatLng> = emptyList(),
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
    val naverMapApiCallCount: Int = 0,
    val hasPendingEdits: Boolean = false
)

enum class DrawingMode { IDLE, DRAWING, PROCESSING, DONE }
