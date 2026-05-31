package com.jason.mapsnap.domain.repository

import com.naver.maps.geometry.LatLng

interface RouteRepository {
    /**
     * 그린 경로의 웨이포인트들을 따라 보행로 경로를 생성한다.
     * @param waypoints 그린 순서대로의 좌표 리스트 (최소 2개)
     * @return 보행로에 스냅된 폴리라인 좌표
     */
    suspend fun getPedestrianRoute(waypoints: List<LatLng>): Result<List<LatLng>>
}
