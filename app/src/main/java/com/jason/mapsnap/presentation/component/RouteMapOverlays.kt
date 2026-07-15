package com.jason.mapsnap.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jason.mapsnap.presentation.map.MapState
import com.jason.mapsnap.ui.theme.MapOverlayColors
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.compose.ExperimentalNaverMapApi
import com.naver.maps.map.compose.Marker as NaverMarker
import com.naver.maps.map.compose.PathOverlay
import com.naver.maps.map.compose.rememberUpdatedMarkerState
import com.naver.maps.map.overlay.OverlayImage

enum class MarkerType { START, END, INTERMEDIATE }

// 미편집 상태 기본 경로색(파랑) — 브랜드 팔레트와 무관한 지도 경로 전용 색이라 테마 토큰화하지 않음
private val PathColorDefault = Color(0xFF2196F3)
private val PathOutlineColorDefault = Color(0xFF0D47A1)
private val PathOutlineColorPending = Color(0xFFFF6F00)

// 동일 (타입, 라벨, 선택 여부) 조합은 항상 같은 비트맵이므로 캐시 — 드래그 중 매 프레임 재생성 방지
private val markerIconCache = mutableMapOf<Triple<MarkerType, String?, Boolean>, OverlayImage>()

/** 마커 아이콘: 타입 및 선택 여부에 따라 원형 비트맵(글자 포함) 생성 (결과는 캐시됨) */
private fun markerIcon(type: MarkerType, label: String?, selected: Boolean): OverlayImage {
    val cacheKey = Triple(type, label, selected)
    markerIconCache[cacheKey]?.let { return it }

    val size = 100
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    // 색상 결정
    val (bgColor, strokeColor) = when (type) {
        MarkerType.START -> {
            if (selected) {
                0xFFE65100.toInt() to 0xFFBF360C.toInt()
            } else {
                0xFF4CAF50.toInt() to 0xFF2E7D32.toInt() // Green
            }
        }
        MarkerType.END -> {
            if (selected) {
                0xFFE65100.toInt() to 0xFFBF360C.toInt()
            } else {
                0xFFF44336.toInt() to 0xFFC62828.toInt() // Red
            }
        }
        MarkerType.INTERMEDIATE -> {
            if (selected) {
                0xFFE65100.toInt() to 0xFFBF360C.toInt()
            } else {
                0xFFFFFFFF.toInt() to 0xFF1565C0.toInt() // White & Blue
            }
        }
    }

    // 채우기
    paint.style = android.graphics.Paint.Style.FILL
    paint.color = bgColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paint)

    // 테두리
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = 6f
    paint.color = strokeColor
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paint)

    // 텍스트 색상 및 텍스트 그리기
    val textColor = if (selected) {
        0xFFFFFFFF.toInt()
    } else if (type == MarkerType.INTERMEDIATE) {
        0xFF1565C0.toInt()
    } else {
        0xFFFFFFFF.toInt()
    }

    if (label != null) {
        paint.style = android.graphics.Paint.Style.FILL
        paint.color = textColor
        paint.textSize = if (label.length > 1) 36f else 44f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.isFakeBoldText = true
        val textHeight = paint.descent() - paint.ascent()
        val textOffset = textHeight / 2 - paint.descent()
        canvas.drawText(label, size / 2f, size / 2f + textOffset, paint)
    }

    return OverlayImage.fromBitmap(bitmap).also { markerIconCache[cacheKey] = it }
}

/**
 * snappedRoute를 markers 위치 기준으로 구간 분할
 * 각 구간은 최소 2개 포인트를 보장한다
 */
private fun segmentize(route: List<LatLng>, markers: List<LatLng>): List<List<LatLng>> {
    if (route.size < 2) return emptyList()
    if (markers.isEmpty()) return listOf(route)

    // 자기교차 경로 대비: 각 마커는 이전 마커 이후 구간에서만 탐색해
    // 같은 좌표가 경로 위 다른 지점에 재등장할 때의 오매칭을 방지
    val splitIndices = mutableListOf<Int>()
    var searchFrom = 1
    for (m in markers) {
        if (searchFrom > route.lastIndex) break
        val idxInTail = route.subList(searchFrom, route.size).indexOfFirst { it == m }
        if (idxInTail < 0) continue
        val absoluteIdx = searchFrom + idxInTail
        if (absoluteIdx >= route.lastIndex) continue
        splitIndices.add(absoluteIdx)
        searchFrom = absoluteIdx + 1
    }

    if (splitIndices.isEmpty()) return listOf(route)

    val segments = mutableListOf<List<LatLng>>()
    var prev = 0
    for (idx in splitIndices) {
        segments.add(route.subList(prev, idx + 1))
        prev = idx
    }
    segments.add(route.subList(prev, route.size))
    return segments.filter { it.size >= 2 }
}

/** NaverMap 콘텐츠 슬롯 내부에서 호출 — 경로 구간(PathOverlay)과 출발/도착/중간 마커를 렌더링 */
@OptIn(ExperimentalNaverMapApi::class)
@Composable
fun RouteMapOverlays(
    state: MapState,
    onSegmentTapped: (Int) -> Unit,
    onStartMarkerTapped: () -> Unit,
    onEndMarkerTapped: () -> Unit,
    onMarkerTapped: (Int) -> Unit
) {
    // 경로를 마커 위치 기준으로 구간 분할 → 각 구간 별도 PathOverlay
    // 탭 → 삭제 다이얼로그 표시
    if (state.snappedRoute.size >= 2) {
        val segments = remember(state.snappedRoute, state.routeMarkers) {
            segmentize(state.snappedRoute, state.routeMarkers)
        }
        segments.forEachIndexed { segIdx, seg ->
            if (seg.size >= 2) {
                val isSelected = state.selectedSegmentIndex == segIdx
                key(segIdx) {
                    PathOverlay(
                        coords = seg,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            state.hasPendingEdits -> MaterialTheme.colorScheme.secondary // 적용 대기(앰버)
                            else -> PathColorDefault
                        },
                        outlineColor = when {
                            isSelected -> MapOverlayColors.primaryOutline
                            state.hasPendingEdits -> PathOutlineColorPending
                            else -> PathOutlineColorDefault
                        },
                        width = if (isSelected) 7.dp else 5.dp,
                        outlineWidth = 2.dp,
                        onClick = {
                            onSegmentTapped(segIdx)
                            true
                        }
                    )
                }
            }
        }
    }

    // 출발 마커 (S)
    state.routeStart?.let { pos ->
        val isSelected = state.selectedMarkerIndex == -2
        key("start_marker") {
            val markerState = rememberUpdatedMarkerState(position = pos)
            LaunchedEffect(pos) {
                markerState.position = pos
            }
            NaverMarker(
                state = markerState,
                icon = markerIcon(MarkerType.START, "S", isSelected),
                anchor = Offset(0.5f, 0.5f),
                onClick = {
                    onStartMarkerTapped()
                    true
                }
            )
        }
    }

    // 도착 마커 (G)
    state.routeEnd?.let { pos ->
        val isSelected = state.selectedMarkerIndex == -3
        key("end_marker") {
            val markerState = rememberUpdatedMarkerState(position = pos)
            LaunchedEffect(pos) {
                markerState.position = pos
            }
            NaverMarker(
                state = markerState,
                icon = markerIcon(MarkerType.END, "G", isSelected),
                anchor = Offset(0.5f, 0.5f),
                onClick = {
                    onEndMarkerTapped()
                    true
                }
            )
        }
    }

    // 중간 마커: 탭 → 선택(주황)
    state.routeMarkers.forEachIndexed { index, pos ->
        val isSelected = state.selectedMarkerIndex == index
        key(index) {
            val markerState = rememberUpdatedMarkerState(position = pos)
            LaunchedEffect(pos) {
                markerState.position = pos
            }
            NaverMarker(
                state = markerState,
                icon = markerIcon(MarkerType.INTERMEDIATE, (index + 1).toString(), isSelected),
                anchor = Offset(0.5f, 0.5f),
                onClick = {
                    onMarkerTapped(index)
                    true
                }
            )
        }
    }
}
