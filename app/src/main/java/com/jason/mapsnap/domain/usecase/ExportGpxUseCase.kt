package com.jason.mapsnap.domain.usecase

import com.naver.maps.geometry.LatLng
import javax.inject.Inject

class ExportGpxUseCase @Inject constructor() {
    operator fun invoke(route: List<LatLng>, routeName: String = "MapSnap Route"): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MapSnap\" ")
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        sb.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        sb.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <trk>\n")
        
        val escapedName = routeName
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        sb.append("    <name>").append(escapedName).append("</name>\n")
        
        sb.append("    <trkseg>\n")
        for (pt in route) {
            sb.append("      <trkpt lat=\"")
                .append(pt.latitude)
                .append("\" lon=\"")
                .append(pt.longitude)
                .append("\"/>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>")
        return sb.toString()
    }
}
