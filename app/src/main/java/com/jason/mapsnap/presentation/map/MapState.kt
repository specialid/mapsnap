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
    val routeMarkers: List<LatLng> = emptyList(), // 결과 경로 위 편집 가능한 중간 마커
    val selectedMarkerIndex: Int = -1,            // -1 = 선택 없음
    val routeStart: LatLng? = null,               // 마커 재라우팅 시 고정 시작점
    val routeEnd: LatLng? = null,                 // 마커 재라우팅 시 고정 끝점
    val currentLocation: LatLng? = null,
    val isProcessing: Boolean = false,
    val error: String? = null
)

enum class DrawingMode { IDLE, DRAWING, PROCESSING, DONE }
