package com.jason.mapsnap.data.repository

import timber.log.Timber
import com.jason.mapsnap.data.remote.OrsService
import com.jason.mapsnap.data.remote.dto.OrsRequest
import com.jason.mapsnap.domain.repository.RouteRepository
import com.jason.mapsnap.domain.util.GeoUtils
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import javax.inject.Inject

class OrsRouteRepositoryImpl @Inject constructor(
    private val service: OrsService
) : RouteRepository {

    companion object {
        /** 이보다 가까운 연속 웨이포인트는 병합 — 미세 왕복 방지 (시작/끝점은 항상 보존) */
        private const val MIN_WAYPOINT_SPACING_METERS = 10.0
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

        val apiKey = com.jason.mapsnap.BuildConfig.ORS_API_KEY
        if (apiKey.isEmpty()) {
            error("ORS_API_KEY가 설정되지 않았습니다. local.properties에 추가해 주세요.")
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

        RouteRepository.PedestrianRoute(points = points, apiCallCount = 1)
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
