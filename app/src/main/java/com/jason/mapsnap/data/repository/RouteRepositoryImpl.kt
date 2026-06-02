package com.jason.mapsnap.data.repository

import android.util.Log
import com.jason.mapsnap.data.remote.TmapService
import com.jason.mapsnap.data.remote.dto.TmapRequest
import com.jason.mapsnap.data.tracker.ApiCallTracker
import com.jason.mapsnap.domain.repository.RouteRepository
import com.naver.maps.geometry.LatLng
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
    }

    private fun haversine(a: LatLng, b: LatLng): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val c = sinLat * sinLat +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLon * sinLon
        return 2 * R * asin(sqrt(c))
    }

    override suspend fun getPedestrianRoute(
        waypoints: List<LatLng>
    ): Result<List<LatLng>> = runCatching {
        val uniqueWaypoints = mutableListOf<LatLng>()
        for (pt in waypoints) {
            if (uniqueWaypoints.isEmpty() || uniqueWaypoints.last() != pt) {
                uniqueWaypoints.add(pt)
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

            Log.d(
                "RouteRepositoryImpl",
                "Starting T-Map API call - Chunk: $chunkIndex/${chunks.size}, Start: (${start.latitude}, ${start.longitude}), End: (${end.latitude}, ${end.longitude}), passList: $passList"
            )

            apiCallTracker.incrementTmap()

            val response = service.getPedestrianRoute(appKey = apiKey, request = request)
            val features = response.features

            Log.d(
                "RouteRepositoryImpl",
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

            // 마지막 청크가 아니면 끝 20m 트리밍:
            // T-Map이 청크 경계 지점 주변을 맴도는 루프 아티팩트를 제거한다
            if (chunkIndex < chunks.lastIndex) {
                var trimmed = 0.0
                while (allPoints.size > 1 && trimmed < CHUNK_TRIM_METERS) {
                    trimmed += haversine(allPoints[allPoints.lastIndex - 1], allPoints.last())
                    allPoints.removeAt(allPoints.lastIndex)
                }
            }
        }

        if (allPoints.isEmpty()) {
            error("경로를 찾을 수 없습니다")
        }

        allPoints
    }
}
