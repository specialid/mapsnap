package com.jason.mapsnap.domain.repository

import com.naver.maps.geometry.LatLng

interface RouteRepository {
    /** @param apiCallCount 이번 요청에서 실제로 발생한 T-Map HTTP 호출(청크) 수 */
    data class PedestrianRoute(
        val points: List<LatLng>,
        val apiCallCount: Int
    )

    /**
     * 그린 경로의 웨이포인트들을 따라 보행로 경로를 생성한다.
     * @param waypoints 그린 순서대로의 좌표 리스트 (최소 2개)
     * @return 보행로에 스냅된 폴리라인 좌표와 실제 API 호출 수
     */
    suspend fun getPedestrianRoute(waypoints: List<LatLng>): Result<PedestrianRoute>
}
