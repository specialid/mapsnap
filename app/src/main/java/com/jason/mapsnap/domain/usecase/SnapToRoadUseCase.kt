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
import com.jason.mapsnap.domain.util.GeoUtils.haversineMeters
import timber.log.Timber

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
                // 그린 원본 선(simplified)을 함께 넘겨 의도된 왕복 획과 아티팩트 스퍼를 구별
                removeSpurs(pedestrianRoute.points, waypoints, drawnPath = simplified)
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

    private companion object {
        /** 왕복 스퍼로 간주할 최대 이동 거리(왕복 누적, m) — 도심 골목 왕복은 100~300m가 흔함 */
        const val MAX_SPUR_TRAVEL_METERS = 200.0
        /** 스퍼 복귀 판정 반경 및 웨이포인트 보호 반경(m) */
        const val SPUR_CLOSE_METERS = 12.0
        /** 나간 길과 돌아오는 길이 이 폭 안에 포개지면 순수 왕복(corridor)으로 판정(m) */
        const val SPUR_CORRIDOR_WIDTH_METERS = 18.0
        /** 그린 선 꼭짓점이 스퍼 팁에서 이 거리 안에 있어야 의도된 획일 가능성 인정(m) */
        const val SPUR_TIP_MATCH_METERS = 35.0
    }

    /**
     * T-Map이 경유지 통과를 위해 만든 "막다른 길 왕복(스퍼)"을 제거한다.
     * i 지점에서 나갔다가 i 근처로 같은 길을 되밟아 돌아오는 순수 왕복 구간을 찾아 건너뛴다.
     *
     * 보존 규칙.
     * - 폭이 있는 작은 고리(의도적으로 그린 루프)는 왕복이 아니므로 보존한다.
     * - [drawnPath]가 있으면(최초 스냅) 그린 선이 스퍼 팁 근처 꼭짓점에서 급반전하는 경우
     *   (글자 획처럼 의도된 왕복)만 보존한다. 웨이포인트는 그린 선에서 자동 샘플링된 점이라
     *   웨이포인트 근접만으로는 의도를 판별할 수 없다 — 스퍼를 유발한 웨이포인트가 자기
     *   자신을 보호하는 딜레마가 생기기 때문.
     * - [drawnPath]가 없으면(마커 재라우팅) 기존처럼 웨이포인트 근접 구간을 보존한다.
     */
    private fun removeSpurs(
        route: List<LatLng>,
        protectedPoints: List<LatLng>,
        drawnPath: List<LatLng>? = null,
        maxSpurMeters: Double = MAX_SPUR_TRAVEL_METERS,
        closeThresholdMeters: Double = SPUR_CLOSE_METERS
    ): List<LatLng> {
        if (route.size < 4) return route

        fun isNearWaypoint(pt: LatLng) = protectedPoints.any { haversineMeters(it, pt) <= closeThresholdMeters }

        // 나간 길과 돌아오는 길이 좁은 폭 안에 포개지는지 — 폭이 있으면 고리(루프)로 보고 스퍼가 아님
        fun isPureOutAndBack(interior: List<LatLng>, tipIdx: Int): Boolean {
            val outgoing = interior.subList(0, tipIdx + 1)
            for (k in tipIdx until interior.size) {
                val minDist = outgoing.minOf { haversineMeters(it, interior[k]) }
                if (minDist > SPUR_CORRIDOR_WIDTH_METERS) return false
            }
            return true
        }

        // 그린 원본 선이 팁 근처 꼭짓점에서 진행 방향을 급반전(120° 초과)하면 의도된 왕복 획으로 본다.
        // 의도된 왕복이라면 RDP 단순화가 반전 꼭짓점을 반드시 남기므로, 근처에 꼭짓점이 없으면 아티팩트다.
        fun drawnPathReversesNear(tip: LatLng): Boolean {
            val path = drawnPath ?: return false
            var nearestIdx = -1
            var nearestDist = Double.MAX_VALUE
            for (idx in path.indices) {
                val d = haversineMeters(path[idx], tip)
                if (d < nearestDist) {
                    nearestDist = d
                    nearestIdx = idx
                }
            }
            if (nearestDist > SPUR_TIP_MATCH_METERS) return false
            // 그린 선의 시작/끝 부근이면 방향 판단 근거가 없으므로 보수적으로 보존
            if (nearestIdx <= 0 || nearestIdx >= path.lastIndex) return true
            for (k in maxOf(1, nearestIdx - 2)..minOf(path.lastIndex - 1, nearestIdx + 2)) {
                if (turnCosine(path[k - 1], path[k], path[k + 1]) < -0.5) return true
            }
            return false
        }

        // keep=true(보존)/false(제거) 여부와 함께 진단 로그용 사유를 반환
        fun shouldKeepSpur(interior: List<LatLng>, anchor: LatLng): Pair<Boolean, String> {
            if (interior.isEmpty()) return false to "빈 구간"
            var tipIdx = 0
            var tipDist = -1.0
            interior.forEachIndexed { idx, pt ->
                val d = haversineMeters(anchor, pt)
                if (d > tipDist) {
                    tipDist = d
                    tipIdx = idx
                }
            }
            if (!isPureOutAndBack(interior, tipIdx)) return true to "면적 있는 고리(corridor 폭 초과) — 왕복 아님"
            return if (drawnPath != null) {
                if (drawnPathReversesNear(interior[tipIdx])) {
                    true to "그린 선 반전 꼭짓점 근접 — 의도된 왕복 획"
                } else {
                    false to "그린 선에 반전 꼭짓점 없음 — 아티팩트로 판정"
                }
            } else {
                if (interior.any { isNearWaypoint(it) }) {
                    true to "웨이포인트 근접 — 보존(마커 재라우팅 경로)"
                } else {
                    false to "웨이포인트 근접 없음 — 아티팩트로 판정"
                }
            }
        }

        val result = mutableListOf(route[0])
        var i = 0
        while (i < route.size - 1) {
            var traveled = 0.0
            var spurEnd = -1
            var j = i + 1
            while (j < route.size && traveled <= maxSpurMeters) {
                traveled += haversineMeters(route[j - 1], route[j])
                // T-Map 스퍼의 최빈 형태는 [진입점, 팁, 복귀점] 3점 구조(중간점 1개)이므로
                // j >= i + 2 부터 복귀 판정 — 최종 게이트는 corridor 검사·그린 선 반전 판별이라
                // 여기서 폭넓게 후보를 잡아도 아티팩트만 정확히 걸러진다.
                if (j >= i + 2 && haversineMeters(route[i], route[j]) <= closeThresholdMeters) {
                    spurEnd = j
                    break
                }
                j++
            }
            if (spurEnd in (i + 1) until route.size) {
                val interior = route.subList(i + 1, spurEnd)
                val (keep, reason) = shouldKeepSpur(interior, route[i])
                Timber.d(
                    "removeSpurs 후보: anchor=%d 왕복거리=%.1fm 중간점=%d keep=%s 사유=%s",
                    i, traveled, interior.size, keep, reason
                )
                if (!keep) {
                    i = spurEnd
                    continue
                }
            }
            result.add(route[i + 1])
            i++
        }
        if (result.last() != route.last()) {
            result.add(route.last())
        }
        return result
    }

    /** cur 꼭짓점에서의 진행 방향 변화 코사인 (경도는 cosLat 보정) — -1에 가까울수록 급반전 */
    private fun turnCosine(prev: LatLng, cur: LatLng, next: LatLng): Double {
        val cosLat = cos(Math.toRadians(cur.latitude))
        val ax = (cur.longitude - prev.longitude) * cosLat
        val ay = cur.latitude - prev.latitude
        val bx = (next.longitude - cur.longitude) * cosLat
        val by = next.latitude - cur.latitude
        val la = sqrt(ax * ax + ay * ay)
        val lb = sqrt(bx * bx + by * by)
        if (la == 0.0 || lb == 0.0) return 1.0
        return (ax * bx + ay * by) / (la * lb)
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
}
