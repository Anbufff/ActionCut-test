package com.actioncut.feature.editor.ui.pip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.actioncut.core.designsystem.component.ToolItem
import com.actioncut.core.model.Clip
import com.actioncut.core.model.CropRect
import kotlin.math.min

/**
 * Fty Video Editor ஸ்கிரீன்ஷாட்டில் உள்ளபடியே PiP தேர்ந்தெடுக்கப்படும் போது தோன்றும் டூல்பார்:
 * [ CROP ] [ TRIM R ] [ CUTOUT ] [ DELETE ] [ SETTINGS ]
 */
@Composable
fun PipSelectedBar(
    onCrop: () -> Unit,
    onTrimRight: () -> Unit,
    onCutout: () -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToolItem(label = "CROP", icon = Icons.Filled.Crop, onClick = onCrop)
            ToolItem(label = "TRIM R", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = onTrimRight)
            ToolItem(label = "CUTOUT", icon = Icons.Filled.Face, onClick = onCutout)
            ToolItem(label = "DELETE", icon = Icons.Filled.Delete, onClick = onDelete)
            ToolItem(label = "SETTINGS", icon = Icons.Filled.Settings, onClick = onSettings)
        }
    }
}

/**
 * Fty HTML PWA எடிட்டரில் உள்ளது போன்ற 100% Freeform 8-புள்ளிகள் கொண்ட Interactive Crop Box
 */
@Composable
fun FreeCropDialog(
    clip: Clip,
    onDismiss: () -> Unit,
    onApplyCrop: (CropRect) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val initialCrop = clip.crop ?: CropRect(0f, 0f, 1f, 1f)
    var leftRatio by remember { mutableFloatStateOf(initialCrop.left) }
    var topRatio by remember { mutableFloatStateOf(initialCrop.top) }
    var rightRatio by remember { mutableFloatStateOf(initialCrop.right) }
    var bottomRatio by remember { mutableFloatStateOf(initialCrop.bottom) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.95f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White)
                    }
                    Text(
                        text = "Free Crop",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = {
                            onApplyCrop(
                                CropRect(
                                    left = leftRatio,
                                    top = topRatio,
                                    right = rightRatio,
                                    bottom = bottomRatio,
                                ),
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D55)),
                    ) {
                        Text("Done", color = Color.White)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(20.dp)
                        .onGloballyPositioned { containerSize = it.size },
                    contentAlignment = Alignment.Center,
                ) {
                    if (containerSize.width > 0 && containerSize.height > 0) {
                        val cWidth = containerSize.width.toFloat()
                        val cHeight = containerSize.height.toFloat()

                        AsyncImage(
                            model = clip.mediaUri,
                            contentDescription = "Crop target",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    var activeHandle: Int? = null

                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            val l = leftRatio * cWidth
                                            val t = topRatio * cHeight
                                            val r = rightRatio * cWidth
                                            val b = bottomRatio * cHeight
                                            val radius = 60f

                                            activeHandle = when {
                                                (offset - Offset(l, t)).getDistance() < radius -> 0
                                                (offset - Offset(r, t)).getDistance() < radius -> 1
                                                (offset - Offset(r, b)).getDistance() < radius -> 2
                                                (offset - Offset(l, b)).getDistance() < radius -> 3
                                                offset.y in t..b && kotlin.math.abs(offset.x - l) < radius -> 4
                                                offset.y in t..b && kotlin.math.abs(offset.x - r) < radius -> 5
                                                offset.x in l..r && kotlin.math.abs(offset.y - t) < radius -> 6
                                                offset.x in l..r && kotlin.math.abs(offset.y - b) < radius -> 7
                                                else -> null
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val dx = dragAmount.x / cWidth
                                            val dy = dragAmount.y / cHeight

                                            when (activeHandle) {
                                                0 -> {
                                                    leftRatio = (leftRatio + dx).coerceIn(0f, rightRatio - 0.08f)
                                                    topRatio = (topRatio + dy).coerceIn(0f, bottomRatio - 0.08f)
                                                }
                                                1 -> {
                                                    rightRatio = (rightRatio + dx).coerceIn(leftRatio + 0.08f, 1f)
                                                    topRatio = (topRatio + dy).coerceIn(0f, bottomRatio - 0.08f)
                                                }
                                                2 -> {
                                                    rightRatio = (rightRatio + dx).coerceIn(leftRatio + 0.08f, 1f)
                                                    bottomRatio = (bottomRatio + dy).coerceIn(topRatio + 0.08f, 1f)
                                                }
                                                3 -> {
                                                    leftRatio = (leftRatio + dx).coerceIn(0f, rightRatio - 0.08f)
                                                    bottomRatio = (bottomRatio + dy).coerceIn(topRatio + 0.08f, 1f)
                                                }
                                                4 -> leftRatio = (leftRatio + dx).coerceIn(0f, rightRatio - 0.08f)
                                                5 -> rightRatio = (rightRatio + dx).coerceIn(leftRatio + 0.08f, 1f)
                                                6 -> topRatio = (topRatio + dy).coerceIn(0f, bottomRatio - 0.08f)
                                                7 -> bottomRatio = (bottomRatio + dy).coerceIn(topRatio + 0.08f, 1f)
                                            }
                                        },
                                    )
                                },
                        ) {
                            val l = leftRatio * size.width
                            val t = topRatio * size.height
                            val r = rightRatio * size.width
                            val b = bottomRatio * size.height
                            val rectW = r - l
                            val rectH = b - t

                            drawPath(
                                path = Path().apply {
                                    addRect(Rect(0f, 0f, size.width, size.height))
                                    addRect(Rect(l, t, r, b))
                                    fillType = PathFillType.EvenOdd
                                },
                                color = Color.Black.copy(alpha = 0.65f),
                            )

                            drawRect(
                                color = Color.White,
                                topLeft = Offset(l, t),
                                size = Size(rectW, rectH),
                                style = Stroke(width = 2.5.dp.toPx()),
                            )

                            drawLine(Color.White.copy(0.4f), Offset(l + rectW / 3, t), Offset(l + rectW / 3, b), 1.dp.toPx())
                            drawLine(Color.White.copy(0.4f), Offset(l + 2 * rectW / 3, t), Offset(l + 2 * rectW / 3, b), 1.dp.toPx())
                            drawLine(Color.White.copy(0.4f), Offset(l, t + rectH / 3), Offset(r, t + rectH / 3), 1.dp.toPx())
                            drawLine(Color.White.copy(0.4f), Offset(l, t + 2 * rectH / 3), Offset(r, t + 2 * rectH / 3), 1.dp.toPx())

                            val dotRadius = 7.dp.toPx()
                            drawCircle(Color(0xFF00E5FF), dotRadius, Offset(l, t))
                            drawCircle(Color(0xFF00E5FF), dotRadius, Offset(r, t))
                            drawCircle(Color(0xFF00E5FF), dotRadius, Offset(r, b))
                            drawCircle(Color(0xFF00E5FF), dotRadius, Offset(l, b))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    OutlinedButton(
                        onClick = {
                            leftRatio = 0f
                            topRatio = 0f
                            rightRatio = 1f
                            bottomRatio = 1f
                        },
                    ) {
                        Text("Reset", color = Color.White)
                    }
                    OutlinedButton(
                        onClick = {
                            val side = min(containerSize.width, containerSize.height).toFloat()
                            val rw = side / containerSize.width
                            val rh = side / containerSize.height
                            leftRatio = (1f - rw) / 2f
                            rightRatio = leftRatio + rw
                            topRatio = (1f - rh) / 2f
                            bottomRatio = topRatio + rh
                        },
                    ) {
                        Text("1:1 Square", color = Color.White)
                    }
                }
            }
        }
    }
}
