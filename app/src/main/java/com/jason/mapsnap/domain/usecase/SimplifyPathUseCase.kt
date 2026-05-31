package com.jason.mapsnap.domain.usecase

import com.naver.maps.geometry.LatLng
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.sqrt

class SimplifyPathUseCase @Inject constructor() {

    companion object {
        /** 손그림 노이즈 제거용: ~15m (흔들림·미끄러짐 제거) */
        const val EPSILON_DRAWN_DEG  = 0.000135
        /** T-Map 경로 직선화용: ~8m (교차로·꺾임 보존, 도로 미세 곡선 제거) */
        const val EPSILON_ROUTE_DEG  = 0.000072
    }

    operator fun invoke(
        points: List<LatLng>,
        epsilonDeg: Double = EPSILON_DRAWN_DEG
    ): List<LatLng> {
        if (points.size <= 2) return points
        return rdp(points, epsilonDeg)
    }

    private fun rdp(points: List<LatLng>, eps: Double): List<LatLng> {
        if (points.size <= 2) return points

        var maxDist = 0.0
        var maxIdx = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until points.size - 1) {
            val dist = perpendicularDistance(points[i], start, end)
            if (dist > maxDist) {
                maxDist = dist
                maxIdx = i
            }
        }

        return if (maxDist > eps) {
            val left = rdp(points.subList(0, maxIdx + 1), eps)
            val right = rdp(points.subList(maxIdx, points.size), eps)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(point: LatLng, lineStart: LatLng, lineEnd: LatLng): Double {
        // 경도는 cosLat 보정으로 동서 방향 비등방성 제거
        val cosLat = cos(Math.toRadians(lineStart.latitude))
        val dx = (lineEnd.longitude - lineStart.longitude) * cosLat
        val dy = lineEnd.latitude - lineStart.latitude
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) {
            val ex = (point.longitude - lineStart.longitude) * cosLat
            val ey = point.latitude - lineStart.latitude
            return sqrt(ex * ex + ey * ey)
        }
        val px = (point.longitude - lineStart.longitude) * cosLat
        val py = point.latitude - lineStart.latitude
        val t = (px * dx + py * dy) / lenSq
        val tc = t.coerceIn(0.0, 1.0)
        val fx = px - tc * dx
        val fy = py - tc * dy
        return sqrt(fx * fx + fy * fy)
    }
}
