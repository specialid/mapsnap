package com.jason.mapsnap.data.repository

import timber.log.Timber
import com.jason.mapsnap.data.remote.OrsService
import com.jason.mapsnap.data.remote.dto.OrsRequest
import com.jason.mapsnap.domain.repository.RouteRepository
import com.jason.mapsnap.domain.repository.SettingsRepository
import com.jason.mapsnap.domain.util.GeoUtils
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import javax.inject.Inject

class OrsRouteRepositoryImpl @Inject constructor(
    private val service: OrsService,
    private val settingsRepository: SettingsRepository
) : RouteRepository {

    companion object {
        /** 이보다 가까운 연속 웨이포인트는 병합 — 미세 왕복 방지 (시작/끝점은 항상 보존) */
        private const val MIN_WAYPOINT_SPACING_METERS = 10.0

        /** 동일 웨이포인트 조합 재요청 방지용 캐시 최대 개수 (LRU) */
        private const val ROUTE_CACHE_CAPACITY = 20
    }

    // 최근성 순서 유지(accessOrder=true) + 용량 초과 시 가장 오래된 항목 제거
    private val routeCache = object : LinkedHashMap<List<LatLng>, RouteRepository.PedestrianRoute>(
        ROUTE_CACHE_CAPACITY, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<LatLng>, RouteRepository.PedestrianRoute>): Boolean =
            size > ROUTE_CACHE_CAPACITY
    }

    override suspend fun getPedestrianRoute(
        waypoints: List<LatLng>
    ): Result<RouteRepository.PedestrianRoute> = runCatching {
        val uniqueWaypoints = mutableListOf<LatLng>()
        for (pt in waypoints) {
            if (uniqueWaypoints.isEmpty() ||
                GeoUtils.haversineMeters(uniqueWaypoints.last(), pt) > MIN_WAYPOINT_SPACING_METERS
            ) {
                uniqueWaypoints.add(pt)
            }
        }
        val destination = waypoints.last()
        if (uniqueWaypoints.isEmpty() || uniqueWaypoints.last() != destination) {
            if (uniqueWaypoints.isNotEmpty() &&
                GeoUtils.haversineMeters(uniqueWaypoints.last(), destination) <= MIN_WAYPOINT_SPACING_METERS
            ) {
                uniqueWaypoints[uniqueWaypoints.lastIndex] = destination
            } else {
                uniqueWaypoints.add(destination)
            }
        }

        require(uniqueWaypoints.size >= 2) { "최소 2개 이상의 포인트가 필요합니다" }

        synchronized(routeCache) { routeCache[uniqueWaypoints] }?.let { cached ->
            Timber.d("ORS 캐시 히트 - waypoints: ${uniqueWaypoints.size}, API 호출 생략")
            return@runCatching cached.copy(apiCallCount = 0)
        }

        val userApiKey = settingsRepository.getSettings().orsApiKey
        val apiKey = userApiKey.ifEmpty { com.jason.mapsnap.BuildConfig.ORS_API_KEY }
        if (apiKey.isEmpty()) {
            error("ORS API 키가 설정되지 않았습니다. 설정 화면에서 발급받은 키를 입력해 주세요.")
        }

        // ORS는 경유지 최대 50개/요청을 지원하므로 청킹 없이 1콜로 처리
        val request = OrsRequest(
            coordinates = uniqueWaypoints.map { listOf(it.longitude, it.latitude) }
        )

        Timber.d("Starting ORS API call - waypoints: ${uniqueWaypoints.size}")

        val response = service.getPedestrianRoute(apiKey = apiKey, request = request)
        val feature = response.features?.firstOrNull()

        Timber.d("Completed ORS API call - Success: ${feature != null}")

        val points = feature?.geometry?.coordinates?.map { LatLng(it[1], it[0]) } ?: emptyList()

        if (points.isEmpty()) {
            error("경로를 찾을 수 없습니다")
        }

        val route = RouteRepository.PedestrianRoute(points = points, apiCallCount = 1)
        synchronized(routeCache) { routeCache[uniqueWaypoints] = route }
        route
    }.recoverCatching { e ->
        if (e is HttpException && e.code() == 429) {
            error("경로 탐색 요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.")
        }
        throw e
    }.also { result ->
        val e = result.exceptionOrNull()
        if (e is CancellationException) throw e
    }
}
