// SnapToRoadUseCase의 왕복 스퍼 제거(removeSpurs) 동작을 검증하는 단위 테스트
package com.jason.mapsnap

import com.jason.mapsnap.domain.repository.RouteRepository
import com.jason.mapsnap.domain.usecase.SimplifyPathUseCase
import com.jason.mapsnap.domain.usecase.SnapToRoadUseCase
import com.naver.maps.geometry.LatLng
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SnapToRoadUseCaseTest {

    private val baseLat = 37.5
    private val baseLng = 127.0

    /** 기준점(37.5, 127.0)에서 동쪽/북쪽으로 지정 미터만큼 이동한 좌표 */
    private fun pt(eastMeters: Double, northMeters: Double): LatLng {
        val lat = baseLat + northMeters / 111_000.0
        val lng = baseLng + eastMeters / (111_000.0 * cos(Math.toRadians(baseLat)))
        return LatLng(lat, lng)
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val c = sinLat * sinLat +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinLon * sinLon
        return 2 * r * asin(sqrt(c))
    }

    private fun fakeRepository(route: List<LatLng>) = object : RouteRepository {
        override suspend fun getPedestrianRoute(waypoints: List<LatLng>): Result<RouteRepository.PedestrianRoute> =
            Result.success(RouteRepository.PedestrianRoute(points = route, apiCallCount = 1))
    }

    /** T-Map 응답 모사: 동서 직선 경로의 130m 지점에 북쪽 60m 왕복 스퍼가 낀 형태 (중간점 여러 개) */
    private fun routeWithSpur(spurTip: LatLng): List<LatLng> = buildList {
        for (i in 0..13) add(pt(i * 10.0, 0.0))
        add(pt(130.0, 20.0))
        add(pt(130.0, 40.0))
        add(spurTip)
        add(pt(131.0, 40.0))
        add(pt(131.0, 20.0))
        add(pt(132.0, 0.0))
        for (i in 14..26) add(pt(i * 10.0, 0.0))
    }

    /**
     * T-Map 응답 모사: 곧은 골목을 찔러 들어갔다 나오는 가장 흔한 형태 — 진입점(J)과 복귀점(J')이
     * 동일 좌표인 [J, T, J'] 3점 구조(중간점 1개). j >= i + 3 가드가 있던 시절에는 탐지되지 않던 케이스.
     * 직선 구간 간격을 20m로 둬 closeThresholdMeters(12m)와 우연히 겹치지 않도록 한다.
     */
    private fun routeWithSingleVertexSpur(spurTip: LatLng): List<LatLng> = buildList {
        for (i in 0..6) add(pt(i * 20.0, 0.0))   // 0..120m
        val junction = pt(140.0, 0.0)
        add(junction)
        add(spurTip)
        add(junction)
        for (i in 8..14) add(pt(i * 20.0, 0.0))  // 160..280m
    }

    /**
     * Case 1: 사용자는 동서 직선만 그렸는데 T-Map이 왕복 스퍼를 만든 경우 —
     * 그린 선에 반전 꼭짓점이 없으므로 아티팩트로 판정되어 제거되어야 한다
     */
    @Test
    fun artifactSpurIsRemoved() = runBlocking {
        val drawn = (0..26).map { pt(it * 10.0, 0.0) }
        val spurTip = pt(130.0, 60.0)
        val useCase = SnapToRoadUseCase(SimplifyPathUseCase(), fakeRepository(routeWithSpur(spurTip)))

        val result = useCase(drawn).getOrThrow().route

        assertTrue(
            "그린 선에 없는 왕복 스퍼는 제거되어야 한다",
            result.none { haversineMeters(it, spurTip) < 30.0 }
        )
    }

    /**
     * Case 2: 사용자가 실제로 북쪽으로 갔다 되돌아오는 획을 그린 경우(글자 획 등) —
     * 그린 선의 급반전 꼭짓점이 의도의 증거이므로 스퍼가 보존되어야 한다
     */
    @Test
    fun intendedRetraceIsKept() = runBlocking {
        val drawn = buildList {
            for (i in 0..13) add(pt(i * 10.0, 0.0))
            for (n in 1..6) add(pt(130.0, n * 10.0))       // 북쪽으로 60m 진출
            for (n in 5 downTo 1) add(pt(133.0, n * 10.0)) // 거의 같은 길로 복귀
            for (i in 14..26) add(pt(i * 10.0, 0.0))
        }
        val spurTip = pt(130.0, 60.0)
        val useCase = SnapToRoadUseCase(SimplifyPathUseCase(), fakeRepository(routeWithSpur(spurTip)))

        val result = useCase(drawn).getOrThrow().route

        assertTrue(
            "의도적으로 그린 왕복 획은 보존되어야 한다",
            result.any { haversineMeters(it, spurTip) < 30.0 }
        )
    }

    /**
     * Case 3 (회귀 방어선): 중간점이 1개뿐인 [진입점, 팁, 복귀점] 형태의 스퍼 —
     * j >= i + 3 가드가 있던 시절엔 탐지조차 되지 않던 가장 흔한 스퍼 형태.
     * 그린 선은 직선이라 반전 꼭짓점이 없으므로 아티팩트로 제거되어야 한다
     */
    @Test
    fun straightSinglePointSpurIsRemoved() = runBlocking {
        val drawn = (0..14).map { pt(it * 20.0, 0.0) }
        val spurTip = pt(140.0, 60.0)
        val useCase = SnapToRoadUseCase(SimplifyPathUseCase(), fakeRepository(routeWithSingleVertexSpur(spurTip)))

        val result = useCase(drawn).getOrThrow().route

        assertTrue(
            "중간점 1개짜리 직선 왕복 스퍼도 제거되어야 한다",
            result.none { haversineMeters(it, spurTip) < 30.0 }
        )
    }

    /**
     * Case 4: 중간점 1개짜리 스퍼라도, 그린 선이 실제로 그 지점까지 갔다 되돌아온 경우
     * (의도된 왕복 획)라면 보존되어야 한다 — 가드 완화로 새로 탐지되는 케이스가
     * 그린 선 반전 판별을 정상적으로 통과하는지 검증
     */
    @Test
    fun intendedSingleVertexRetraceIsKept() = runBlocking {
        val drawn = buildList {
            for (i in 0..6) add(pt(i * 20.0, 0.0))
            for (n in 1..6) add(pt(140.0, n * 10.0))       // 북쪽으로 60m 진출
            for (n in 5 downTo 1) add(pt(143.0, n * 10.0)) // 거의 같은 길로 복귀
            for (i in 8..14) add(pt(i * 20.0, 0.0))
        }
        val spurTip = pt(140.0, 60.0)
        val useCase = SnapToRoadUseCase(SimplifyPathUseCase(), fakeRepository(routeWithSingleVertexSpur(spurTip)))

        val result = useCase(drawn).getOrThrow().route

        assertTrue(
            "의도적으로 그린 단일 꼭짓점 왕복 획은 보존되어야 한다",
            result.any { haversineMeters(it, spurTip) < 30.0 }
        )
    }
}
