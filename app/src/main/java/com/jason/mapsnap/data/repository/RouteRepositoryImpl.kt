package com.jason.mapsnap.data.repository

import timber.log.Timber
import com.jason.mapsnap.data.remote.TmapService
import com.jason.mapsnap.data.remote.dto.TmapRequest
import com.jason.mapsnap.data.tracker.ApiCallTracker
import com.jason.mapsnap.domain.repository.RouteRepository
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RouteRepositoryImpl @Inject constructor(
    private val service: TmapService,
    private val apiCallTracker: ApiCallTracker
) : RouteRepository {

    companion object {
        /** 청크 경계 루프 아티팩트 제거를 위해 각 청크 끝에서 트리밍할 거리 (미터) */
        private const val CHUNK_TRIM_METERS = 25.0
        /** 이보다 가까운 연속 웨이포인트는 병합 — 청크 수·왕복 유발 방지 (시작/끝점은 항상 보존) */
        private const val MIN_WAYPOINT_SPACING_METERS = 10.0
    }

    private fun haversine(a: LatLng, b: LatLng): Double {
        return com.jason.mapsnap.domain.util.GeoUtils.haversineMeters(a, b)
    }

    override suspend fun getPedestrianRoute(
        waypoints: List<LatLng>
    ): Result<RouteRepository.PedestrianRoute> = runCatching {
        // 완전 일치가 아니어도 너무 가까운 연속 웨이포인트는 병합 — 청크 수 절감 및 미세 왕복 방지
        val uniqueWaypoints = mutableListOf<LatLng>()
        for (pt in waypoints) {
            if (uniqueWaypoints.isEmpty() || haversine(uniqueWaypoints.last(), pt) > MIN_WAYPOINT_SPACING_METERS) {
                uniqueWaypoints.add(pt)
            }
        }
        // 실제 목적지(원본 마지막 웨이포인트)는 병합으로 소실되지 않도록 항상 보존
        val destination = waypoints.last()
        if (uniqueWaypoints.isEmpty() || uniqueWaypoints.last() != destination) {
            if (uniqueWaypoints.isNotEmpty() && haversine(uniqueWaypoints.last(), destination) <= MIN_WAYPOINT_SPACING_METERS) {
                uniqueWaypoints[uniqueWaypoints.lastIndex] = destination
            } else {
                uniqueWaypoints.add(destination)
            }
        }

        require(uniqueWaypoints.size >= 2) { "최소 2개 이상의 포인트가 필요합니다" }

        val apiKey = com.jason.mapsnap.BuildConfig.TMAP_API_KEY
        if (apiKey.isEmpty()) {
            error("TMAP_API_KEY가 설정되지 않았습니다. local.properties에 추가해 주세요.")
        }

        // T-Map 보행자 API: 호출당 경유지 최대 5개 → 시작 + 경유지 5개 + 끝 = 7포인트씩 분할
        val chunks = mutableListOf<List<LatLng>>()
        var startIndex = 0
        val chunkSize = 7 
        while (startIndex < uniqueWaypoints.size - 1) {
            val endIndex = (startIndex + chunkSize - 1).coerceAtMost(uniqueWaypoints.size - 1)
            chunks.add(uniqueWaypoints.subList(startIndex, endIndex + 1))
            startIndex = endIndex
        }

        val allPoints = mutableListOf<LatLng>()

        for ((chunkIndex, chunk) in chunks.withIndex()) {
            val start = chunk.first()
            val end = chunk.last()
            val passList = if (chunk.size > 2) {
                chunk.subList(1, chunk.size - 1).joinToString("_") { "${it.longitude},${it.latitude}" }
            } else {
                null
            }

            val request = TmapRequest(
                startX = start.longitude,
                startY = start.latitude,
                endX = end.longitude,
                endY = end.latitude,
                passList = passList
            )

            Timber.d(
                "Starting T-Map API call - Chunk: $chunkIndex/${chunks.size}, Start: (${start.latitude}, ${start.longitude}), End: (${end.latitude}, ${end.longitude}), passList: $passList"
            )

            apiCallTracker.incrementTmap()

            val response = service.getPedestrianRoute(appKey = apiKey, request = request)
            val features = response.features

            Timber.d(
                "Completed T-Map API call - Chunk: $chunkIndex/${chunks.size}, Success: ${features != null}, Features size: ${features?.size ?: 0}"
            )

            if (features == null) continue

            for (feature in features) {
                if (feature.geometry.type == "LineString") {
                    val array = feature.geometry.coordinates.asJsonArray
                    for (element in array) {
                        val pointArray = element.asJsonArray
                        val lng = pointArray[0].asDouble
                        val lat = pointArray[1].asDouble
                        val point = LatLng(lat, lng)

                        // 연속된 중복 좌표 방지하며 리스트에 누적
                        if (allPoints.isEmpty() || allPoints.last() != point) {
                            allPoints.add(point)
                        }
                    }
                }
            }

            // 마지막 청크가 아니면 끝부분이 실제 루프 아티팩트일 때만 트리밍한다
            // (단순 전진 경로까지 잘라내면 직선 점프가 생기므로 조건부로만 적용)
            if (chunkIndex < chunks.lastIndex) {
                trimBoundaryArtifactIfLooping(allPoints)
            }
        }

        if (allPoints.isEmpty()) {
            error("경로를 찾을 수 없습니다")
        }

        RouteRepository.PedestrianRoute(points = allPoints, apiCallCount = chunks.size)
    }.also { result ->
        // runCatching은 CancellationException도 삼키므로, 구조적 취소가 깨지지 않도록 재던짐
        val e = result.exceptionOrNull()
        if (e is CancellationException) throw e
    }

    /**
     * 청크 경계 끝부분이 실제로 되돌아오는 루프 아티팩트일 때만 트리밍한다.
     * 직선 거리(direct)가 이동 거리(traveled)의 절반을 넘으면(즉 대체로 전진 중이면) 정상 경로로 보고 그대로 둔다.
     * 무조건 트리밍하면 건물·하천을 관통하는 직선 점프를 만들 수 있어 조건부로만 적용한다.
     */
    private fun trimBoundaryArtifactIfLooping(points: MutableList<LatLng>) {
        if (points.size < 3) return
        val endIdx = points.lastIndex
        var traveled = 0.0
        var far = endIdx
        while (far > 0 && traveled < CHUNK_TRIM_METERS) {
            traveled += haversine(points[far - 1], points[far])
            far--
        }
        if (far == endIdx) return

        val direct = haversine(points[far], points[endIdx])
        if (direct > traveled * 0.5) return // 대체로 전진 중 — 아티팩트 아님, 트리밍하지 않음

        var trimmed = 0.0
        while (points.size > 1 && trimmed < CHUNK_TRIM_METERS) {
            trimmed += haversine(points[points.lastIndex - 1], points.last())
            points.removeAt(points.lastIndex)
        }
    }
}
