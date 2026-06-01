package com.jason.mapsnap.presentation.component

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jason.mapsnap.presentation.map.DrawingMode
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.compose.CameraPositionState
import kotlin.math.sqrt

@Composable
fun DrawingOverlay(
    drawingMode: DrawingMode,
    simplifiedPoints: List<LatLng>,
    isLoop: Boolean,
    routeMarkers: List<LatLng> = emptyList(),
    cameraPositionState: CameraPositionState,
    naverMap: com.naver.maps.map.NaverMap?,
    onDrawStart: (LatLng) -> Unit,
    onDrawPoint: (LatLng) -> Unit,
    onDrawEnd: () -> Unit,
    onMarkerDragEnd: (index: Int, newPos: LatLng) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val drawnLatLngs = remember { mutableStateListOf<LatLng>() }
    val canvasSize = remember { mutableStateListOf(IntSize.Zero) }

    // 드래그 중인 마커 로컬 상태 (API 호출 없이 즉각 시각 피드백)
    val draggingIdx = remember { mutableStateOf(-1) }
    val draggingOffset = remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(drawingMode) {
        if (drawingMode != DrawingMode.DRAWING) drawnLatLngs.clear()
    }

    val isDrawing = drawingMode == DrawingMode.DRAWING
    val isDone    = drawingMode == DrawingMode.DONE

    val activeModifier = when {
        // 그리기 모드: 손그림 캡처
        isDrawing -> Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    drawnLatLngs.clear()
                    screenToLatLng(offset, naverMap)?.let { latLng ->
                        drawnLatLngs.add(latLng)
                        onDrawStart(latLng)
                    }
                },
                onDrag = { change, _ ->
                    screenToLatLng(change.position, naverMap)?.let { latLng ->
                        drawnLatLngs.add(latLng)
                        onDrawPoint(latLng)
                    }
                },
                onDragEnd = { onDrawEnd() },
                onDragCancel = { onDrawEnd() }
            )
        }

        // 완료 모드: 마커 근처 터치만 소비해 드래그 처리, 나머지는 지도로 통과
        isDone && routeMarkers.isNotEmpty() -> Modifier.pointerInput(routeMarkers, naverMap) {
            val touchRadiusPx = 40.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                // 가장 가까운 마커 탐색
                val nearIdx = routeMarkers.indexOfFirst { latLng ->
                    val sp = latLngToScreen(latLng, naverMap) ?: return@indexOfFirst false
                    val dx = down.position.x - sp.x
                    val dy = down.position.y - sp.y
                    sqrt(dx * dx + dy * dy) <= touchRadiusPx
                }

                // 마커 근처가 아니면 이벤트 통과 (지도 스크롤/줌 허용)
                if (nearIdx < 0) return@awaitEachGesture

                down.consume()
                draggingIdx.value = nearIdx
                draggingOffset.value = down.position

                // 드래그 추적
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    change.consume()
                    draggingOffset.value = change.position

                    if (!change.pressed) {
                        // 드래그 완료 → ViewModel에 새 위치 전달
                        val newLatLng = screenToLatLng(change.position, naverMap)
                        if (newLatLng != null) onMarkerDragEnd(nearIdx, newLatLng)
                        draggingIdx.value = -1
                        break
                    }
                }
            }
        }

        else -> Modifier // 터치 이벤트 통과
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize[0] = it }
            .then(activeModifier)
    ) {
        @Suppress("UNUSED_EXPRESSION")
        cameraPositionState.position

        when {
            // 그리기 중: 손그림 원본 + 시작점 스냅존
            isDrawing && drawnLatLngs.size >= 2 -> {
                val rawPoints = drawnLatLngs.mapNotNull { latLngToScreen(it, naverMap) }
                if (rawPoints.size >= 2) {
                    drawStrokePath(rawPoints, freehand = true)
                    drawSnapZone(center = rawPoints.first())
                }
            }

            // 처리 중: RDP 직선화 선분 미리보기
            drawingMode == DrawingMode.PROCESSING && simplifiedPoints.size >= 2 -> {
                val snapPoints = simplifiedPoints.mapNotNull { latLngToScreen(it, naverMap) }
                if (snapPoints.size >= 2) drawStrokePath(snapPoints, freehand = false, loop = isLoop)
            }

            // 완료: 드래그 가능한 중간 마커 렌더링
            isDone && routeMarkers.isNotEmpty() -> {
                routeMarkers.forEachIndexed { index, latLng ->
                    val isDragging = index == draggingIdx.value
                    val screenPos = if (isDragging) draggingOffset.value
                                    else latLngToScreen(latLng, naverMap) ?: return@forEachIndexed
                    drawRouteMarker(screenPos, isDragging)
                }
            }
        }
    }
}

private fun DrawScope.drawRouteMarker(center: Offset, isDragging: Boolean) {
    val outerRadius = if (isDragging) 20.dp.toPx() else 12.dp.toPx()

    // 드래그 중: 반투명 후광으로 손가락 아래 가시성 확보
    if (isDragging) {
        drawCircle(color = Color(0x44E65100), radius = 32.dp.toPx(), center = center)
    }

    // 채우기
    drawCircle(
        color = if (isDragging) Color(0xFFE65100) else Color.White,
        radius = outerRadius,
        center = center
    )
    // 테두리
    drawCircle(
        color = if (isDragging) Color(0xFFBF360C) else Color(0xFF1565C0),
        radius = outerRadius,
        center = center,
        style = Stroke(width = 3.dp.toPx())
    )
}

private fun DrawScope.drawStrokePath(
    points: List<Offset>,
    freehand: Boolean,
    loop: Boolean = false
) {
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    drawPath(
        path = path,
        color = Color.White.copy(alpha = if (freehand) 0.5f else 0.8f),
        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    val lineColor = when {
        freehand -> Color(0xFF1565C0)
        loop     -> Color(0xFF2E7D32)
        else     -> Color(0xFFE65100)
    }
    drawPath(
        path = path,
        color = lineColor,
        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    if (!freehand) {
        points.forEach { pt ->
            drawCircle(color = lineColor, radius = 6.dp.toPx(), center = pt)
            drawCircle(color = Color.White, radius = 4.dp.toPx(), center = pt)
        }
    }
}

private fun DrawScope.drawSnapZone(center: Offset) {
    val radius = 28.dp.toPx()
    drawCircle(color = Color(0x332E7D32), radius = radius, center = center)
    drawCircle(color = Color(0xFF2E7D32), radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
}

private fun screenToLatLng(offset: Offset, naverMap: com.naver.maps.map.NaverMap?): LatLng? {
    val projection = naverMap?.projection ?: return null
    return projection.fromScreenLocation(PointF(offset.x, offset.y))
}

private fun latLngToScreen(latLng: LatLng, naverMap: com.naver.maps.map.NaverMap?): Offset? {
    val projection = naverMap?.projection ?: return null
    val pointF = projection.toScreenLocation(latLng)
    return Offset(pointF.x, pointF.y)
}
