package com.jason.mapsnap.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jason.mapsnap.presentation.map.DrawingMode

@Composable
fun BottomControls(
    drawingMode: DrawingMode,
    onDrawToggle: () -> Unit,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    onExportGpx: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 지우기 버튼 (DONE 상태일 때만 표시)
        AnimatedVisibility(
            visible = drawingMode == DrawingMode.DONE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FloatingActionButton(onClick = onClear) {
                Icon(imageVector = Icons.Default.Clear, contentDescription = "지우기")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // GPX 내보내기 버튼 (DONE 상태일 때만 표시)
        AnimatedVisibility(
            visible = drawingMode == DrawingMode.DONE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            ExtendedFloatingActionButton(
                onClick = onExportGpx,
                icon = { Icon(imageVector = Icons.Default.Share, contentDescription = null) },
                text = { Text("GPX") },
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        }

        // 이어 그리기 버튼 (DONE 상태일 때만 표시)
        AnimatedVisibility(
            visible = drawingMode == DrawingMode.DONE,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FloatingActionButton(
                onClick = onContinue,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "이어 그리기")
            }
        }

        // 그리기/완료/처리중 버튼
        when (drawingMode) {
            DrawingMode.PROCESSING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp).padding(8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            DrawingMode.DRAWING -> {
                ExtendedFloatingActionButton(
                    onClick = onDrawToggle,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("스냅") },
                    containerColor = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                ExtendedFloatingActionButton(
                    onClick = onDrawToggle,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = {
                        Text(if (drawingMode == DrawingMode.DONE) "다시 그리기" else "그리기")
                    }
                )
            }
        }
    }
}
