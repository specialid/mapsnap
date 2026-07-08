package com.jason.mapsnap.domain.model

// 앱 재시작 후에도 유지되어야 하는 사용자 설정 값
data class AppSettings(
    val markerIntervalMeters: Double = 80.0,
    val epsilonDrawnDeg: Double = 0.000135,
    val epsilonRouteDeg: Double = 0.000072,
    val includeTimestamps: Boolean = false,
    val runningPaceSecPerKm: Int = 360
)
