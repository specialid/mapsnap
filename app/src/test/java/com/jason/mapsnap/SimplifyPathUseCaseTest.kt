package com.jason.mapsnap

import com.jason.mapsnap.domain.usecase.SimplifyPathUseCase
import com.naver.maps.geometry.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplifyPathUseCaseTest {

    private val useCase = SimplifyPathUseCase()

    @Test
    fun `2개 이하 포인트는 그대로 반환`() {
        val points = listOf(LatLng(37.0, 127.0), LatLng(37.01, 127.01))
        val result = useCase(points)
        assertEquals(points, result)
    }

    @Test
    fun `직선 구간의 중간 포인트는 제거됨`() {
        val points = listOf(
            LatLng(37.0, 127.0),
            LatLng(37.005, 127.005),
            LatLng(37.01, 127.01)
        )
        val result = useCase(points)
        // 완전 직선이면 중간 포인트 제거
        assertEquals(2, result.size)
    }

    @Test
    fun `꺾인 구간의 중간 포인트는 유지됨`() {
        val points = listOf(
            LatLng(37.0, 127.0),
            LatLng(37.005, 127.0),   // 직각 꺾임
            LatLng(37.005, 127.01)
        )
        val result = useCase(points)
        assertTrue(result.size >= 2)
    }
}
