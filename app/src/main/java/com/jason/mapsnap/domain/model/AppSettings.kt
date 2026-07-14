package com.jason.mapsnap.domain.model

// 앱 재시작 후에도 유지되어야 하는 사용자 설정 값
data class AppSettings(
    val markerIntervalMeters: Double = 80.0,
    val epsilonDrawnDeg: Double = 0.000135,
    val epsilonRouteDeg: Double = 0.000072,
    val includeTimestamps: Boolean = false,
    val runningPaceSecPerKm: Int = 360,
    // Phase 1 PoC 토글: T-Map/ORS 라우팅 엔진 비교용 (docs/osm_migration_plan.md)
    val useOrsEngine: Boolean = false
)
