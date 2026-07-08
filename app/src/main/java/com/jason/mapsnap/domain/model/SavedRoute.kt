package com.jason.mapsnap.domain.model

import com.naver.maps.geometry.LatLng

// 사용자가 이름을 붙여 저장한 경로 — 나중에 다시 불러와 이어서 편집하거나 GPX로 내보낼 수 있다
data class SavedRoute(
    val id: String,
    val name: String,
    val createdAt: Long,
    val routeStart: LatLng,
    val routeEnd: LatLng,
    val routeMarkers: List<LatLng>,
    val snappedRoute: List<LatLng>,
    val distanceMeters: Double
)
