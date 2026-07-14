package com.jason.mapsnap.data.repository

import com.jason.mapsnap.di.OrsEngine
import com.jason.mapsnap.di.TmapEngine
import com.jason.mapsnap.domain.repository.RouteRepository
import com.jason.mapsnap.domain.repository.SettingsRepository
import com.naver.maps.geometry.LatLng
import javax.inject.Inject

/**
 * Phase 1 PoC 토글: 설정의 useOrsEngine 값에 따라 T-Map/ORS 구현으로 위임한다.
 * Phase 2 품질 PoC 판정 후 정리 대상 (docs/osm_migration_plan.md)
 */
class EngineSwitchingRouteRepository @Inject constructor(
    @TmapEngine private val tmapRepository: RouteRepository,
    @OrsEngine private val orsRepository: RouteRepository,
    private val settingsRepository: SettingsRepository
) : RouteRepository {

    override suspend fun getPedestrianRoute(waypoints: List<LatLng>): Result<RouteRepository.PedestrianRoute> {
        val useOrs = settingsRepository.getSettings().useOrsEngine
        return if (useOrs) orsRepository.getPedestrianRoute(waypoints) else tmapRepository.getPedestrianRoute(waypoints)
    }
}
