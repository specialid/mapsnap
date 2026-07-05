package com.jason.mapsnap.domain.usecase

import com.jason.mapsnap.domain.model.GpxExportOptions
import com.jason.mapsnap.domain.util.GeoUtils
import com.naver.maps.geometry.LatLng
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

class ExportGpxUseCase @Inject constructor() {
    operator fun invoke(
        route: List<LatLng>,
        options: GpxExportOptions = GpxExportOptions(),
        routeName: String = "MapSnap Route"
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MapSnap\" ")
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        sb.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")

        // metadata 블록
        sb.append("  <metadata>\n")
        sb.append("    <name>").append(escapeXml(routeName)).append("</name>\n")

        val startInstant = Instant.ofEpochMilli(options.startTimeMillis)
            .truncatedTo(ChronoUnit.SECONDS)
        val isoFormatter = DateTimeFormatter.ISO_INSTANT
        sb.append("    <time>").append(isoFormatter.format(startInstant)).append("</time>\n")

        // bounds 계산
        if (route.isNotEmpty()) {
            var minLat = route[0].latitude
            var maxLat = route[0].latitude
            var minLon = route[0].longitude
            var maxLon = route[0].longitude

            for (pt in route) {
                minLat = minOf(minLat, pt.latitude)
                maxLat = maxOf(maxLat, pt.latitude)
                minLon = minOf(minLon, pt.longitude)
                maxLon = maxOf(maxLon, pt.longitude)
            }

            sb.append("    <bounds ")
            sb.append("minlat=\"").append(String.format(Locale.US, "%.6f", minLat)).append("\" ")
            sb.append("minlon=\"").append(String.format(Locale.US, "%.6f", minLon)).append("\" ")
            sb.append("maxlat=\"").append(String.format(Locale.US, "%.6f", maxLat)).append("\" ")
            sb.append("maxlon=\"").append(String.format(Locale.US, "%.6f", maxLon)).append("\"/>\n")
        }

        sb.append("  </metadata>\n")

        // trk 블록
        sb.append("  <trk>\n")
        sb.append("    <name>").append(escapeXml(routeName)).append("</name>\n")
        sb.append("    <trkseg>\n")

        // 타임스탬프 계산 (거리 기반 등속)
        val timestamps = if (options.includeTimestamps && route.isNotEmpty()) {
            calculateTimestamps(route, options)
        } else {
            emptyMap<Int, Long>()
        }

        for (i in route.indices) {
            val pt = route[i]
            sb.append("      <trkpt ")
            sb.append("lat=\"").append(String.format(Locale.US, "%.6f", pt.latitude)).append("\" ")
            sb.append("lon=\"").append(String.format(Locale.US, "%.6f", pt.longitude)).append("\"")

            if (timestamps.containsKey(i)) {
                sb.append(">\n")
                val tMillis = timestamps[i]!!
                val instant = Instant.ofEpochMilli(tMillis)
                    .truncatedTo(ChronoUnit.SECONDS)
                val isoFormatter = DateTimeFormatter.ISO_INSTANT
                sb.append("        <time>").append(isoFormatter.format(instant)).append("</time>\n")
                sb.append("      </trkpt>\n")
            } else {
                sb.append("/>\n")
            }
        }

        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>")
        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun calculateTimestamps(route: List<LatLng>, options: GpxExportOptions): Map<Int, Long> {
        val timestamps = mutableMapOf<Int, Long>()
        timestamps[0] = options.startTimeMillis

        val pace = options.paceSecPerKm.coerceAtLeast(1)
        val speed = 1000.0 / pace  // m/s
        var cum = 0.0

        for (i in 1 until route.size) {
            cum += GeoUtils.haversineMeters(route[i - 1], route[i])
            val tMillis = options.startTimeMillis + (cum / speed * 1000).toLong()
            timestamps[i] = tMillis
        }

        return timestamps
    }
}
