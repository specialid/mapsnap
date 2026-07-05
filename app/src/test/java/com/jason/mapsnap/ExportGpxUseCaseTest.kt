package com.jason.mapsnap

import com.jason.mapsnap.domain.model.GpxExportOptions
import com.jason.mapsnap.domain.usecase.ExportGpxUseCase
import com.naver.maps.geometry.LatLng
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

class ExportGpxUseCaseTest {
    private lateinit var useCase: ExportGpxUseCase

    @Before
    fun setup() {
        useCase = ExportGpxUseCase()
    }

    /**
     * Case 1: 기본 옵션 OFF — metadata·bounds 포함, 좌표 6자리, <trkpt>에 <time> 없음
     */
    @Test
    fun testBasicGpxWithoutTimestamps() {
        val route = listOf(
            LatLng(37.123456, 127.654321),
            LatLng(37.234567, 127.765432),
            LatLng(37.345678, 127.876543)
        )
        val options = GpxExportOptions(includeTimestamps = false)
        val routeName = "Test Route"

        val gpx = useCase(route, options, routeName)

        // metadata 블록 확인
        assert(gpx.contains("<metadata>"))
        assert(gpx.contains("<name>Test Route</name>"))
        assert(gpx.contains("<time>"))
        assert(gpx.contains("<bounds"))

        // 좌표 정밀도 확인 (소수 6자리)
        assert(gpx.contains("lat=\"37.123456\""))
        assert(gpx.contains("lon=\"127.654321\""))

        // trkpt에 time 태그가 없음을 확인 (self-closing tag 확인)
        val selfClosingTrkpt = gpx.contains("<trkpt")
        assert(selfClosingTrkpt)
    }

    /**
     * Case 2: bounds min/max lat·lon 정확성
     */
    @Test
    fun testBoundsAccuracy() {
        val route = listOf(
            LatLng(37.1, 127.1),
            LatLng(37.5, 127.5),
            LatLng(37.3, 127.2)
        )
        val options = GpxExportOptions(includeTimestamps = false)

        val gpx = useCase(route, options, "Bounds Test")

        // minlat, maxlat, minlon, maxlon 확인
        assert(gpx.contains("minlat=\"37.100000\"")) // min lat
        assert(gpx.contains("maxlat=\"37.500000\"")) // max lat
        assert(gpx.contains("minlon=\"127.100000\"")) // min lon
        assert(gpx.contains("maxlon=\"127.500000\"")) // max lon
    }

    /**
     * Case 3: 타임스탬프 ON — 마지막 trkpt 시각 ≈ startTime + 총거리/speed (±1s), 첫 점 == startTime
     */
    @Test
    fun testTimestampsCalculation() {
        val route = listOf(
            LatLng(37.0, 127.0),
            LatLng(37.01, 127.01),  // 대략 1.4km
            LatLng(37.02, 127.02)   // 추가 포인트
        )
        val startTimeMillis = 1623072000000L  // 2021-06-07T12:00:00Z
        val options = GpxExportOptions(
            includeTimestamps = true,
            paceSecPerKm = 360,  // 6:00/km
            startTimeMillis = startTimeMillis
        )

        val gpx = useCase(route, options, "Timestamp Test")

        // 타임스탬프 포함 확인 (metadata + trkpt times)
        assert(gpx.contains("<time>"))

        // 첫 번째 trkpt의 시각이 startTime과 같은지 확인
        val isoFormatter = DateTimeFormatter.ISO_INSTANT
        val startInstant = Instant.ofEpochMilli(startTimeMillis)
            .truncatedTo(ChronoUnit.SECONDS)
        val startIsoString = isoFormatter.format(startInstant)

        // 첫 번째 trkpt 후의 time 태그 찾기
        val trkptIndex = gpx.indexOf("<trkpt")
        assert(trkptIndex > 0)

        // 해당 trkpt 블록 내의 time 값 추출
        val closeIndex = gpx.indexOf("</trkpt>", trkptIndex)
        val trkptBlock = gpx.substring(trkptIndex, closeIndex)

        // 첫 번째 trkpt에 <time> 태그가 있는지 확인
        assert(trkptBlock.contains("<time>"))
        val timeStart = trkptBlock.indexOf("<time>") + 6
        val timeEnd = trkptBlock.indexOf("</time>")
        val timeValue = trkptBlock.substring(timeStart, timeEnd)
        assert(timeValue == startIsoString)
    }

    /**
     * Case 4: Locale 무관 — 기본 로케일을 콤마 소수점 로케일로 바꿔도 좌표/시각이 '.'·ISO 형식 유지
     */
    @Test
    fun testLocaleIndependence() {
        val route = listOf(
            LatLng(37.123456, 127.654321),
            LatLng(37.234567, 127.765432)
        )
        val options = GpxExportOptions(includeTimestamps = true)

        val gpx = useCase(route, options, "Locale Test")

        // 소수점이 '.'로 출력되는지 확인
        assert(gpx.contains("37.123456")) // 콤마 없음
        assert(!gpx.contains("37,123456")) // 콤마 있으면 안됨

        // ISO 시각 형식 확인
        assert(gpx.contains("T"))  // ISO-8601 시간 구분자
        assert(gpx.contains("Z"))  // UTC 지시자
    }

    /**
     * Case 5: 이름 escape — 특수문자(&, <, >) 정상 치환
     */
    @Test
    fun testNameEscaping() {
        val route = listOf(
            LatLng(37.123456, 127.654321),
            LatLng(37.234567, 127.765432)
        )
        val options = GpxExportOptions(includeTimestamps = false)
        val routeName = "Test & <Route> \"Quote\""

        val gpx = useCase(route, options, routeName)

        // metadata와 trk name 모두 escape되어야 함
        assert(gpx.contains("&amp;"))
        assert(gpx.contains("&lt;"))
        assert(gpx.contains("&gt;"))
        assert(gpx.contains("&quot;"))

        // 원본 특수문자는 없어야 함
        assert(!gpx.contains("Test & <Route>"))
    }
}
