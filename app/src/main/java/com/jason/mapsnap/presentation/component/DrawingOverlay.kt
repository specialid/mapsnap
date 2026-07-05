package com.jason.mapsnap.presentation.component

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jason.mapsnap.presentation.map.DrawingMode
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.compose.CameraPositionState

@Composable
fun DrawingOverlay(
    drawingMode: DrawingMode,
    simplifiedPoints: List<LatLng>,
    isLoop: Boolean,
    pendingStrokes: List<List<LatLng>> = emptyList(),
    cameraPositionState: CameraPositionState,
    naverMap: com.naver.maps.map.NaverMap?,
    onDrawStart: (LatLng) -> Unit,
    onDrawPoint: (LatLng) -> Unit,
    onDrawEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val drawnLatLngs = remember { mutableStateListOf<LatLng>() }
    val canvasSize = remember { mutableStateListOf(IntSize.Zero) }

    LaunchedEffect(drawingMode) {
        // 그리기가 끝나면 손그림 원본 제거 — PROCESSING 이후엔 직선화 경로로 대체
        if (drawingMode != DrawingMode.DRAWING) drawnLatLngs.clear()
    }

    val isDrawing = drawingMode == DrawingMode.DRAWING

    // pointerInput은 그리기 모드일 때만 추가 — 아닐 때 모든 터치를 NaverMap에 통과시킴
    val touchModifier = if (isDrawing) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { offset ->
                    drawnLatLngs.clear()
                    screenToLatLng(offset, naverMap)?.let { latLng ->
                        drawnLatLngs.add(latLng)
                        onDrawStart(latLng)
                    }
                },
                onDrag = { change, _ ->
                    val offset = change.position
                    screenToLatLng(offset, naverMap)?.let { latLng ->
                        drawnLatLngs.add(latLng)
                        onDrawPoint(latLng)
                    }
                },
                onDragEnd = { onDrawEnd() },
                onDragCancel = { onDrawEnd() }
            )
        }
    } else {
        Modifier  // 터치 핸들러 없음 → 이벤트 통과
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize[0] = it }
            .then(touchModifier)
    ) {
        // 카메라 상태 변화를 감지하여 캔버스 무효화(Redraw) 트리거
        @Suppress("UNUSED_EXPRESSION")
        cameraPositionState.position

        when {
            // 그리기 중: 완료된 이전 스트로크 + 현재 손그림 원본 + 시작점 스냅존 원 표시
            isDrawing -> {
                // 배치 스냅 대기 중인 이전 스트로크들을 계속 표시 — 완료 전까지 사라지지 않음을 시각적으로 보장
                pendingStrokes.forEach { stroke ->
                    val strokePoints = stroke.mapNotNull { latLngToScreen(it, naverMap) }
                    if (strokePoints.size >= 2) drawStrokePath(strokePoints, freehand = true)
                }
                if (drawnLatLngs.size >= 2) {
                    val rawPoints = drawnLatLngs.mapNotNull { latLngToScreen(it, naverMap) }
                    if (rawPoints.size >= 2) {
                        drawStrokePath(rawPoints, freehand = true)
                        // 시작점 주변에 스냅존 안내 원 (30m 기준 시각 가이드)
                        drawSnapZone(center = rawPoints.first())
                    }
                }
            }
            // 처리 중: RDP 직선화된 선분들을 표시 (T-Map으로 전달되는 경로 미리보기)
            drawingMode == DrawingMode.PROCESSING && simplifiedPoints.size >= 2 -> {
                val snapPoints = simplifiedPoints.mapNotNull { latLngToScreen(it, naverMap) }
                if (snapPoints.size >= 2) drawStrokePath(snapPoints, freehand = false, loop = isLoop)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(
    points: List<Offset>,
    freehand: Boolean,
    loop: Boolean = false
) {
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    // 외곽선
    drawPath(
        path = path,
        color = Color.White.copy(alpha = if (freehand) 0.5f else 0.8f),
        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    // 내선 — 직선화 후엔 주황색, 루프면 초록색으로 구분
    val lineColor = when {
        freehand -> Color(0xFF1565C0)
        loop     -> Color(0xFF2E7D32)
        else     -> Color(0xFFE65100)
    }
    drawPath(
        path = path,
        color = lineColor,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 직선화 경로의 꺾임 지점(waypoint) 표시
    if (!freehand) {
        points.forEach { pt ->
            drawCircle(color = lineColor, radius = 6.dp.toPx(), center = pt)
            drawCircle(color = Color.White, radius = 4.dp.toPx(), center = pt)
        }
    }
}

/** 그리기 중 시작점 주변에 표시하는 스냅존 안내 원 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnapZone(center: Offset) {
    val radius = 28.dp.toPx()
    drawCircle(
        color = Color(0x332E7D32), // 반투명 초록 채우기
        radius = radius,
        center = center
    )
    drawCircle(
        color = Color(0xFF2E7D32),
        radius = radius,
        center = center,
        style = Stroke(width = 2.dp.toPx())
    )
}

// 네이버 지도 SDK Projection을 사용하여 화면 픽셀 좌표를 위경도(LatLng)로 변환
private fun screenToLatLng(
    offset: Offset,
    naverMap: com.naver.maps.map.NaverMap?
): LatLng? {
    val projection = naverMap?.projection ?: return null
    return projection.fromScreenLocation(PointF(offset.x, offset.y))
}

// 네이버 지도 SDK Projection을 사용하여 위경도(LatLng)를 화면 픽셀 좌표로 변환
private fun latLngToScreen(
    latLng: LatLng,
    naverMap: com.naver.maps.map.NaverMap?
): Offset? {
    val projection = naverMap?.projection ?: return null
    val pointF = projection.toScreenLocation(latLng)
    return Offset(pointF.x, pointF.y)
}
