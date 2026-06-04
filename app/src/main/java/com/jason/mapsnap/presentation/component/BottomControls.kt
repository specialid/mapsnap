package com.jason.mapsnap.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jason.mapsnap.presentation.map.DrawingMode

@Composable
fun BottomControls(
    drawingMode: DrawingMode,
    hasPendingEdits: Boolean,
    canUndo: Boolean,
    onApplyEdits: () -> Unit,
    onUndo: () -> Unit,
    onDrawToggle: () -> Unit,
    onContinue: () -> Unit,
    onClear: () -> Unit,
    onExportGpx: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    // Reset expanded state if we leave the DONE mode (e.g. clear, redraw)
    if (drawingMode != DrawingMode.DONE) {
        isExpanded = false
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        // Left Action Group: More FAB + Speed Dial Stack
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(
                visible = drawingMode == DrawingMode.DONE && isExpanded,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
            ) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (canUndo) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                onUndo()
                                isExpanded = false
                            },
                            icon = { Icon(Icons.Default.Undo, contentDescription = null) },
                            text = { Text("실행 취소") },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    }
                    ExtendedFloatingActionButton(
                        onClick = {
                            onExportGpx()
                            isExpanded = false
                        },
                        icon = { Icon(Icons.Default.Share, contentDescription = null) },
                        text = { Text("GPX") },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            onClear()
                            isExpanded = false
                        },
                        icon = { Icon(Icons.Default.Clear, contentDescription = null) },
                        text = { Text("지우기") },
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                }
            }

            AnimatedVisibility(
                visible = drawingMode == DrawingMode.DONE,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FloatingActionButton(
                    onClick = { isExpanded = !isExpanded },
                    containerColor = if (isExpanded) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.Close else Icons.Default.MoreVert,
                        contentDescription = "더보기"
                    )
                }
            }
        }

        // Right Action Group: [다시 그리기/완료/스냅] + [+] (이어 그리기)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [적용] (Apply) Button
            AnimatedVisibility(
                visible = drawingMode == DrawingMode.DONE && hasPendingEdits,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = onApplyEdits,
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    text = { Text("적용") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            }

            // [+] (이어 그리기) Button
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

            // Draw State Button
            when (drawingMode) {
                DrawingMode.PROCESSING -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(56.dp)
                            .padding(8.dp),
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
}
