package com.jason.mapsnap.domain.usecase

import com.jason.mapsnap.domain.repository.RouteRepository
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import javax.inject.Inject

class SnapToRoadUseCase @Inject constructor(
    private val simplifyPath: SimplifyPathUseCase,
    private val repository: RouteRepository
) {

    // waypoint 수를 줄일수록 T-Map 자유도↑(더 직선적) / 늘릴수록 그린 경로 밀착↑
    // chunkSize=7 기준: 19 → 3청크, 13 → 2청크, 7 → 1청크(청크 연결 없음)
    private val maxWaypoints = 19

    suspend operator fun invoke(points: List<LatLng>): Result<List<LatLng>> {
        if (points.size < 2) {
            return Result.failure(IllegalArgumentException("최소 2개 이상의 포인트가 필요합니다"))
        }

        val simplified = withContext(Dispatchers.Default) { simplifyPath(points) }
        if (simplified.size < 2) {
            return Result.failure(IllegalStateException("경로 단순화 후 포인트가 부족합니다"))
        }

        var totalDist = 0.0
        for (i in 0 until simplified.size - 1) {
            totalDist += haversineMeters(simplified[i], simplified[i + 1])
        }
        val dynamicMaxWaypoints = (totalDist / 50.0).toInt().coerceIn(2, 19)

        // 아크-길이 기반 균등 샘플링: 코너 밀집 구간 과탈락 방지
        val waypoints = sampleByArcLength(simplified, dynamicMaxWaypoints)

        val routeResult = repository.getPedestrianRoute(waypoints)

        // T-Map 결과의 미세 우회 구간 제거 — 도로 꺾임은 보존, 불필요한 곡선만 정리
        return routeResult.map { route ->
            withContext(Dispatchers.Default) {
                simplifyPath(route, SimplifyPathUseCase.EPSILON_ROUTE_DEG)
            }
        }
    }

    /**
     * 마커 편집 후 재라우팅 전용: RDP·샘플링 없이 waypoints를 그대로 T-Map에 전달
     * 이미 정제된 좌표(routeStart + 편집된 마커들 + routeEnd)를 받는다
     */
    suspend fun fromWaypoints(waypoints: List<LatLng>): Result<List<LatLng>> {
        if (waypoints.size < 2) {
            return Result.failure(IllegalArgumentException("최소 2개 이상의 포인트가 필요합니다"))
        }
        val routeResult = repository.getPedestrianRoute(waypoints)
        return routeResult.map { route ->
            withContext(Dispatchers.Default) {
                simplifyPath(route, SimplifyPathUseCase.EPSILON_ROUTE_DEG)
            }
        }
    }

    private fun sampleByArcLength(points: List<LatLng>, maxCount: Int): List<LatLng> {
        if (points.size <= maxCount) return points

        val cumDist = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumDist[i] = cumDist[i - 1] + haversineMeters(points[i - 1], points[i])
        }
        val totalDist = cumDist.last()
        val step = totalDist / (maxCount - 1)

        val result = ArrayList<LatLng>(maxCount)
        result.add(points.first())
        var j = 1
        for (i in 1 until maxCount - 1) {
            val target = step * i
            while (j < points.size - 1 && cumDist[j] < target) j++
            result.add(points[j])
        }
        result.add(points.last())
        return result
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val c = sinLat * sinLat +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLon * sinLon
        return 2 * R * asin(sqrt(c))
    }
}
