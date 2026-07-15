package com.jason.mapsnap.presentation.component

import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.jason.mapsnap.presentation.map.MapState
import com.jason.mapsnap.ui.theme.MapOverlayColors
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.compose.CameraPositionState

/**
 * 선택된 마커(출발/도착/중간)의 드래그 이동 조절점과 삭제 버블을 지도 위에 오버레이한다.
 * 화면 좌표 변환에 NaverMap projection이 필요해 naverMap이 로드된 이후에만 표시된다.
 */
@Composable
fun MarkerDragHandleOverlay(
    state: MapState,
    naverMap: com.naver.maps.map.NaverMap?,
    cameraPositionState: CameraPositionState,
    mapContainerWidthPx: Int,
    onDragStart: () -> Unit,
    onStartMarkerDragged: (LatLng) -> Unit,
    onEndMarkerDragged: (LatLng) -> Unit,
    onMarkerDragged: (Int, LatLng) -> Unit,
    onDeleteMarkerTapped: () -> Unit
) {
    val selectedIndex = state.selectedMarkerIndex
    val markerPos = when (selectedIndex) {
        -2 -> state.routeStart
        -3 -> state.routeEnd
        in state.routeMarkers.indices -> state.routeMarkers[selectedIndex]
        else -> null
    }
    if (markerPos != null) {
        val currentMarkerPosState = rememberUpdatedState(markerPos) // 최신 마커 위치 캡처용 State
        val cameraPosition = cameraPositionState.position // 카메라 움직임 시 리컴포지션 유도
        naverMap?.let { map ->
            val projection = map.projection
            val screenPoint = projection.toScreenLocation(markerPos)
            val density = LocalContext.current.resources.displayMetrics.density

            // 더 큰 터치 조절점 크기 정의 (터치 영역 64dp, 시각 조절점 32dp)
            val touchSizeDp = 64.dp
            val touchSizePx = 64f * density

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (screenPoint.x - touchSizePx / 2).toInt(),
                            (screenPoint.y - touchSizePx / 2).toInt()
                        )
                    }
                    .size(touchSizeDp)
                    .pointerInput(selectedIndex) {
                        var accumulatedDrag: PointF? = null
                        detectDragGestures(
                            onDragStart = { _ ->
                                onDragStart()
                                val startPos = currentMarkerPosState.value
                                val startScreenPoint = projection.toScreenLocation(startPos)
                                accumulatedDrag = PointF(startScreenPoint.x, startScreenPoint.y)
                            },

                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulatedDrag?.let { drag ->
                                    drag.x += dragAmount.x
                                    drag.y += dragAmount.y
                                    val newLatLng = projection.fromScreenLocation(drag)
                                    when (selectedIndex) {
                                        -2 -> onStartMarkerDragged(newLatLng)
                                        -3 -> onEndMarkerDragged(newLatLng)
                                        else -> onMarkerDragged(selectedIndex, newLatLng)
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // 시각 마커 (선택된 마커와 매칭되는 32dp 오렌지 원형 조절판)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                        .border(3.dp, MapOverlayColors.primaryOutline, shape = CircleShape)
                )
            }

            if (selectedIndex >= 0) {
                val bubbleSizeDp = 48.dp
                val bubbleSizePx = 48f * density
                // 마커가 화면 우측 가장자리에 가까우면 버블이 잘리므로 왼쪽으로 뒤집는다
                val rightOffsetPx = screenPoint.x + 32f * density
                val fitsOnRight = mapContainerWidthPx == 0 ||
                    rightOffsetPx + bubbleSizePx <= mapContainerWidthPx
                val bubbleX = if (fitsOnRight) {
                    rightOffsetPx
                } else {
                    screenPoint.x - 32f * density - bubbleSizePx
                }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                bubbleX.toInt(),
                                (screenPoint.y - bubbleSizePx / 2).toInt()
                            )
                        }
                        .size(bubbleSizeDp)
                        .background(MaterialTheme.colorScheme.error, shape = CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.onError, shape = CircleShape)
                        .pointerInput(selectedIndex) {
                            detectTapGestures {
                                onDeleteMarkerTapped()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "마커 삭제",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
