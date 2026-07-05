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

    /** @param apiCallCount 이번 스냅에서 실제로 발생한 T-Map HTTP 호출(청크) 수 */
    data class SnapResult(
        val route: List<LatLng>,
        val apiCallCount: Int
    )

    // waypoint 수를 줄일수록 T-Map 자유도↑(더 직선적) / 늘릴수록 그린 경로 밀착↑
    // chunkSize=7 기준: 19 → 3청크, 13 → 2청크, 7 → 1청크(청크 연결 없음)
    private val maxWaypoints = 19

    suspend operator fun invoke(
        points: List<LatLng>,
        epsilonDrawnDeg: Double = SimplifyPathUseCase.EPSILON_DRAWN_DEG,
        epsilonRouteDeg: Double = SimplifyPathUseCase.EPSILON_ROUTE_DEG
    ): Result<SnapResult> {
        if (points.size < 2) {
            return Result.failure(IllegalArgumentException("최소 2개 이상의 포인트가 필요합니다"))
        }

        val simplified = withContext(Dispatchers.Default) { simplifyPath(points, epsilonDrawnDeg) }
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
        return routeResult.map { pedestrianRoute ->
            val despurred = withContext(Dispatchers.Default) {
                removeSpurs(pedestrianRoute.points, waypoints)
            }
            val route = withContext(Dispatchers.Default) {
                simplifyPath(despurred, epsilonRouteDeg)
            }
            SnapResult(route = route, apiCallCount = pedestrianRoute.apiCallCount)
        }
    }

    /**
     * 마커 편집 후 재라우팅 전용: RDP·샘플링 없이 waypoints를 그대로 T-Map에 전달
     * 이미 정제된 좌표(routeStart + 편집된 마커들 + routeEnd)를 받는다
     */
    suspend fun fromWaypoints(
        waypoints: List<LatLng>,
        epsilonRouteDeg: Double = SimplifyPathUseCase.EPSILON_ROUTE_DEG
    ): Result<SnapResult> {
        if (waypoints.size < 2) {
            return Result.failure(IllegalArgumentException("최소 2개 이상의 포인트가 필요합니다"))
        }
        val routeResult = repository.getPedestrianRoute(waypoints)
        return routeResult.map { pedestrianRoute ->
            val despurred = withContext(Dispatchers.Default) {
                removeSpurs(pedestrianRoute.points, waypoints)
            }
            val route = withContext(Dispatchers.Default) {
                simplifyPath(despurred, epsilonRouteDeg)
            }
            SnapResult(route = route, apiCallCount = pedestrianRoute.apiCallCount)
        }
    }

    /**
     * T-Map이 경유지 통과를 위해 만든 "막다른 길 왕복(스퍼)"을 제거한다.
     * i 지점에서 짧게 나갔다가 i 근처로 되돌아오는 구간을 발견하면 건너뛴다.
     * 단, [protectedPoints](사용자가 실제로 지정한 웨이포인트) 근처를 통과하는 구간은
     * 의도된 경로이므로 보존한다.
     */
    private fun removeSpurs(
        route: List<LatLng>,
        protectedPoints: List<LatLng>,
        maxSpurMeters: Double = 60.0,
        closeThresholdMeters: Double = 12.0
    ): List<LatLng> {
        if (route.size < 4) return route

        fun isProtected(pt: LatLng) = protectedPoints.any { haversineMeters(it, pt) <= closeThresholdMeters }

        val result = mutableListOf(route[0])
        var i = 0
        while (i < route.size - 1) {
            var traveled = 0.0
            var spurEnd = -1
            var j = i + 1
            while (j < route.size && traveled <= maxSpurMeters) {
                traveled += haversineMeters(route[j - 1], route[j])
                // 최소 2개의 중간점(꼭짓점)이 있어야 실제 왕복 스퍼로 간주 — 단순 인접점 오탐 방지
                if (j >= i + 3 && haversineMeters(route[i], route[j]) <= closeThresholdMeters) {
                    spurEnd = j
                    break
                }
                j++
            }
            if (spurEnd in (i + 1) until route.size &&
                (i + 1 until spurEnd).none { isProtected(route[it]) }
            ) {
                i = spurEnd
            } else {
                result.add(route[i + 1])
                i++
            }
        }
        if (result.last() != route.last()) {
            result.add(route.last())
        }
        return result
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
