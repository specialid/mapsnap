package com.jason.mapsnap.data.model

// SharedPreferences에 Gson으로 직렬화해 저장하기 위한 순수 데이터 형태 (LatLng 대신 위경도 리스트 사용)
data class SavedRouteEntity(
    val id: String,
    val name: String,
    val createdAt: Long,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val markerLats: List<Double>,
    val markerLons: List<Double>,
    val routeLats: List<Double>,
    val routeLons: List<Double>,
    val distanceMeters: Double
)
