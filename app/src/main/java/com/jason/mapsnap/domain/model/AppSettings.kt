package com.jason.mapsnap.domain.model

// 앱 재시작 후에도 유지되어야 하는 사용자 설정 값
data class AppSettings(
    val markerIntervalMeters: Double = 80.0,
    val epsilonDrawnDeg: Double = 0.000135,
    val epsilonRouteDeg: Double = 0.000072,
    val includeTimestamps: Boolean = false,
    val runningPaceSecPerKm: Int = 360,
    // ORS(OSM)를 메인 라우팅 엔진으로 사용, T-Map은 비노출 폴백으로 유지 (docs/osm_migration_plan.md)
    val useOrsEngine: Boolean = true
)
