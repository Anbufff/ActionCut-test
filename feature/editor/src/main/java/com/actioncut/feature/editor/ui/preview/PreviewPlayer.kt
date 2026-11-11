package com.actioncut.feature.editor.ui.preview

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.TransitionType
import com.actioncut.feature.editor.ui.pip.AiCutoutManager
import com.actioncut.feature.editor.ui.scorecard.ScorecardManager
import kotlin.math.roundToInt

@Composable
fun PreviewPlayer(
    player: ExoPlayer,
    aspectRatio: AspectRatio,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    overlays: List<Clip> = emptyList(),
    playheadMs: Long = 0L,
    selectedClipId: String? = null,
    onSelectOverlay: (String) -> Unit = {},
    onMoveOverlay: (String, Float, Float) -> Unit = { _, _, _ -> },
    pipPlayer: ExoPlayer? = null,
    pipClips: List<Clip> = emptyList(),
    onScaleOverlay: (String, Float) -> Unit = { _, _ -> },
    onCropChange: (String, CropRect) -> Unit = { _, _ -> },
    isCropModeActive: Boolean = false,
    fitMode: com.actioncut.core.model.FitMode = com.actioncut.core.model.FitMode.FILL,
    backgroundColorArgb: Int = 0xFF000000.toInt(),
    allClips: List<Clip> = emptyList(),
    onScorecardRowTapped: (String, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val resizeMode = when (fitMode) {
        com.actioncut.core.model.FitMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        com.actioncut.core.model.FitMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        com.actioncut.core.model.FitMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    val mainClips = allClips.filter { it.type == ClipType.VIDEO || it.type == ClipType.IMAGE }
        .sortedBy { it.timelineStartMs }
    val currentMainClip = mainClips.firstOrNull { playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .aspectRatio(aspectRatio.value)
                .clipToBounds()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()

            // 1. பின்னணியில் அதே படத்தின் Blur Effect
            if (currentMainClip != null && currentMainClip.mediaUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(currentMainClip.mediaUri)
                        .size(64, 36)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier.graphicsLayer {
                                    renderEffect = android.graphics.RenderEffect.createBlurEffect(
                                        45f, 45f, android.graphics.Shader.TileMode.CLAMP,
                                    ).asComposeRenderEffect()
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                )
            }

            if (currentMainClip?.type == ClipType.VIDEO) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            this.player = player
                            this.resizeMode = resizeMode
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    update = {
                        it.player = player
                        it.resizeMode = resizeMode
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 2. Main Track Image Auto-Zoom
            if (currentMainClip != null && currentMainClip.type == ClipType.IMAGE && currentMainClip.mediaUri != null) {
                val timeInClip = (playheadMs - currentMainClip.timelineStartMs).coerceAtLeast(0L)
                val duration = currentMainClip.timelineDurationMs.coerceAtLeast(1L)
                val halfDuration = duration / 2f

                val autoZoomScale = if (timeInClip <= halfDuration) {
                    val p = (timeInClip.toFloat() / halfDuration).coerceIn(0f, 1f)
                    1.0f + (0.10f * p)
                } else {
                    val p = ((timeInClip.toFloat() - halfDuration) / halfDuration).coerceIn(0f, 1f)
                    1.10f - (0.10f * p)
                }

                val transition = currentMainClip.transitionToNext
                val trDuration = transition?.durationMs ?: 0L
                val remainingTime = currentMainClip.timelineEndMs - playheadMs

                val alphaVal = if (transition != null && transition.type != TransitionType.SEAMLESS_MERGE &&
                    transition.type != TransitionType.NONE && remainingTime in 1..trDuration
                ) {
                    (remainingTime.toFloat() / trDuration.toFloat()).coerceIn(0f, 1f)
                } else {
                    1.0f
                }

                val isMainSelected = currentMainClip.id == selectedClipId
                val mainTransform = currentMainClip.transform

                val totalScale = autoZoomScale * mainTransform.scale

                AsyncImage(
                    model = currentMainClip.mediaUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = totalScale
                            scaleY = totalScale
                            alpha = alphaVal
                            translationX = (mainTransform.offsetX / 2f) * w
                            translationY = (mainTransform.offsetY / 2f) * h
                            rotationZ = (currentMainClip.rotationDegrees + mainTransform.rotationDegrees).toFloat()
                        }
                        .then(
                            if (isMainSelected) {
                                Modifier.border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.8f))
                            } else {
                                Modifier
                            },
                        ),
                )
            }

            // 3. Fty HTML Video Editor பாணியில் PiP Layer (Free Crop + AI Cutout)
            if (pipPlayer != null) {
                PipLayer(
                    pipPlayer = pipPlayer,
                    pipClips = pipClips,
                    playheadMs = playheadMs,
                    selectedClipId = selectedClipId,
                    isCropModeActive = isCropModeActive,
                    onSelect = onSelectOverlay,
                    onMove = onMoveOverlay,
                    onScale = onScaleOverlay,
                    onCropChange = onCropChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Overlays Layer (News Alert)
            OverlayLayer(
                overlays = overlays,
                playheadMs = playheadMs,
                selectedClipId = selectedClipId,
                onSelect = onSelectOverlay,
                onMove = onMoveOverlay,
                modifier = Modifier.fillMaxSize(),
            )

            // Dynamic Scorecard Layer
            val activeScoreClip = allClips.firstOrNull {
                it.scorecard != null && playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
            }

            if (activeScoreClip != null) {
                val sc = activeScoreClip.scorecard
                if (sc != null) {
                    val currentPlayheadMs by rememberUpdatedState(playheadMs)
                    val timeInClip = (playheadMs - activeScoreClip.timelineStartMs).coerceAtLeast(0L)
                    val activeBmp = ScorecardManager.getActiveBitmap(activeScoreClip.id, sc, timeInClip)

                    if (activeBmp != null) {
                        Image(
                            bitmap = activeBmp.asImageBitmap(),
                            contentDescription = "Scorecard",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(activeScoreClip.id) {
                                    detectTapGestures { offset ->
                                        val curTimeInClip = (currentPlayheadMs - activeScoreClip.timelineStartMs).coerceAtLeast(0L)
                                        val touchYFraction = offset.y / size.height
                                        val touchXFraction = offset.x / size.width
                                        val hitRow = ScorecardManager.hitTestRow(
                                            activeScoreClip.id,
                                            sc,
                                            curTimeInClip,
                                            touchYFraction,
                                            touchXFraction,
                                        )
                                        if (hitRow >= 0) {
                                            onScorecardRowTapped(activeScoreClip.id, hitRow)
                                        }
                                    }
                                },
                        )
                    }
                }
            }

            if (!isPlaying) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable { onTogglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp).padding(start = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Fty Video Editor ஸ்கிரீன்ஷாட்டில் உள்ளபடியே:
 * 1. சியான் டாஷ்ட் பார்டர் (Cyan Dashed Border)
 * 2. 4 மூலைகளில் L-வடிவ Corner Brackets
 * 3. 4 பக்கங்களில் வட்ட வடிவ Edge Handles (Top, Bottom, Left, Right)
 * 4. வலது கீழ் மூலையில் வெள்ளை நிற Resize Handle
 * 5. Google ML Kit On-Device AI Human Cutout Display
 */
@Composable
private fun PipLayer(
    pipPlayer: ExoPlayer,
    pipClips: List<Clip>,
    playheadMs: Long,
    selectedClipId: String?,
    isCropModeActive: Boolean,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onScale: (String, Float) -> Unit,
    onCropChange: (String, CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = pipClips.firstOrNull {
        playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
    } ?: return

    val context = LocalContext.current
    var cutoutUri by remember(active.id, active.filter?.id) { mutableStateOf<String?>(null) }
    val isAiCutoutOn = active.filter?.id == "ai_cutout"

    LaunchedEffect(active.id, active.mediaUri, isAiCutoutOn) {
        if (isAiCutoutOn && active.mediaUri != null && active.type == ClipType.IMAGE) {
            val res = AiCutoutManager.getOrCreateCutoutUri(context, active.mediaUri!!)
            cutoutUri = res
        } else {
            cutoutUri = null
        }
    }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current.density
        val props = com.actioncut.core.model.Keyframes.propsAt(active, playheadMs - active.timelineStartMs)
        val t = props.transform
        val pipW = (w * t.scale) / density
        val pipH = (h * t.scale) / density
        val isSelected = active.id == selectedClipId
        val cyanColor = Color(0xFF00E5FF)

        val currentCrop = active.crop ?: CropRect(0f, 0f, 1f, 1f)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(pipW.dp, pipH.dp)
                .graphicsLayer {
                    translationX = (t.offsetX / 2f) * w
                    translationY = (t.offsetY / 2f) * h
                    rotationZ = t.rotationDegrees
                    alpha = props.opacity
                }
                .pointerInput(active.id, w, h) {
                    var ox = t.offsetX
                    var oy = t.offsetY
                    detectDragGestures(
                        onDragStart = { onSelect(active.id); ox = active.transform.offsetX; oy = active.transform.offsetY },
                        onDrag = { change, drag ->
                            change.consume()
                            ox = (ox + drag.x / (w / 2f)).coerceIn(-1.5f, 1.5f)
                            oy = (oy + drag.y / (h / 2f)).coerceIn(-1.5f, 1.5f)
                            onMove(active.id, ox, oy)
                        },
                    )
                },
        ) {
            // 1. பிம்பம் - Crop Clip செய்யப்பட்டு வரையப்படுதல்
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val crop = currentCrop
                        val clipRect = Rect(
                            left = size.width * crop.left,
                            top = size.height * crop.top,
                            right = size.width * crop.right,
                            bottom = size.height * crop.bottom,
                        )
                        drawContext.canvas.save()
                        drawContext.canvas.clipRect(clipRect)
                        drawContent()
                        drawContext.canvas.restore()
                    },
            ) {
                val displayUri = if (isAiCutoutOn && cutoutUri != null) cutoutUri else active.mediaUri
                if (active.type == ClipType.VIDEO) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                this.player = pipPlayer
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        },
                        update = { it.player = pipPlayer },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AsyncImage(
                        model = displayUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 2. Fty HTML Video Editor பாணி Live On-Screen Free Crop Bounding Box & Handles
            if (isSelected) {
                // Fty சியான் நிற டாஷ்ட் பார்டர் (Cyan Dashed Border) Crop அளவைச் சுற்றி துல்லியமாக வரைதல்
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val cL = size.width * currentCrop.left
                            val cT = size.height * currentCrop.top
                            val cW = size.width * (currentCrop.right - currentCrop.left)
                            val cH = size.height * (currentCrop.bottom - currentCrop.top)

                            val dashStroke = Stroke(
                                width = 2.5f * density,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                            )
                            drawRoundRect(
                                color = cyanColor,
                                topLeft = Offset(cL, cT),
                                size = Size(cW, cH),
                                cornerRadius = CornerRadius(2f * density),
                                style = dashStroke,
                            )
                        },
                )

                // Fty 4 பக்க வட்ட வடிவ கைப்பிடிகள் (Top, Bottom, Left, Right Edge Circles)
                val pipPixelW = pipW * density
                val pipPixelH = pipH * density

                // Top Edge Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * ((currentCrop.left + currentCrop.right) / 2f) - 8 * density).roundToInt(),
                                (pipPixelH * currentCrop.top - 8 * density).roundToInt(),
                            )
                        }
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(1.5.dp, Color.White, CircleShape)
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dy = drag.y / pipPixelH
                                val newTop = (currentCrop.top + dy).coerceIn(0f, currentCrop.bottom - 0.08f)
                                onCropChange(active.id, currentCrop.copy(top = newTop))
                            }
                        },
                )

                // Bottom Edge Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * ((currentCrop.left + currentCrop.right) / 2f) - 8 * density).roundToInt(),
                                (pipPixelH * currentCrop.bottom - 8 * density).roundToInt(),
                            )
                        }
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(1.5.dp, Color.White, CircleShape)
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dy = drag.y / pipPixelH
                                val newBottom = (currentCrop.bottom + dy).coerceIn(currentCrop.top + 0.08f, 1f)
                                onCropChange(active.id, currentCrop.copy(bottom = newBottom))
                            }
                        },
                )

                // Left Edge Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * currentCrop.left - 8 * density).roundToInt(),
                                (pipPixelH * ((currentCrop.top + currentCrop.bottom) / 2f) - 8 * density).roundToInt(),
                            )
                        }
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(1.5.dp, Color.White, CircleShape)
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / pipPixelW
                                val newLeft = (currentCrop.left + dx).coerceIn(0f, currentCrop.right - 0.08f)
                                onCropChange(active.id, currentCrop.copy(left = newLeft))
                            }
                        },
                )

                // Right Edge Handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * currentCrop.right - 8 * density).roundToInt(),
                                (pipPixelH * ((currentCrop.top + currentCrop.bottom) / 2f) - 8 * density).roundToInt(),
                            )
                        }
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(1.5.dp, Color.White, CircleShape)
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / pipPixelW
                                val newRight = (currentCrop.right + dx).coerceIn(currentCrop.left + 0.08f, 1f)
                                onCropChange(active.id, currentCrop.copy(right = newRight))
                            }
                        },
                )

                // Fty பாணி 4 மூலை L-வடிவ பிராக்கெட்டுகள் (4 Corner Brackets: TL, TR, BL, BR)
                // Top-Left Corner
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * currentCrop.left - 4 * density).roundToInt(),
                                (pipPixelH * currentCrop.top - 4 * density).roundToInt(),
                            )
                        }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 3.5f * density
                            drawLine(cyanColor, Offset(0f, 0f), Offset(size.width, 0f), strokeW)
                            drawLine(cyanColor, Offset(0f, 0f), Offset(0f, size.height), strokeW)
                        }
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / pipPixelW
                                val dy = drag.y / pipPixelH
                                val newLeft = (currentCrop.left + dx).coerceIn(0f, currentCrop.right - 0.08f)
                                val newTop = (currentCrop.top + dy).coerceIn(0f, currentCrop.bottom - 0.08f)
                                onCropChange(active.id, currentCrop.copy(left = newLeft, top = newTop))
                            }
                        },
                )

                // Top-Right Corner
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * currentCrop.right - 16 * density).roundToInt(),
                                (pipPixelH * currentCrop.top - 4 * density).roundToInt(),
                            )
                        }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 3.5f * density
                            drawLine(cyanColor, Offset(0f, 0f), Offset(size.width, 0f), strokeW)
                            drawLine(cyanColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeW)
                        }
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / pipPixelW
                                val dy = drag.y / pipPixelH
                                val newRight = (currentCrop.right + dx).coerceIn(currentCrop.left + 0.08f, 1f)
                                val newTop = (currentCrop.top + dy).coerceIn(0f, currentCrop.bottom - 0.08f)
                                onCropChange(active.id, currentCrop.copy(right = newRight, top = newTop))
                            }
                        },
                )

                // Bottom-Left Corner
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * currentCrop.left - 4 * density).roundToInt(),
                                (pipPixelH * currentCrop.bottom - 16 * density).roundToInt(),
                            )
                        }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 3.5f * density
                            drawLine(cyanColor, Offset(0f, size.height), Offset(size.width, size.height), strokeW)
                            drawLine(cyanColor, Offset(0f, 0f), Offset(0f, size.height), strokeW)
                        }
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / pipPixelW
                                val dy = drag.y / pipPixelH
                                val newLeft = (currentCrop.left + dx).coerceIn(0f, currentCrop.right - 0.08f)
                                val newBottom = (currentCrop.bottom + dy).coerceIn(currentCrop.top + 0.08f, 1f)
                                onCropChange(active.id, currentCrop.copy(left = newLeft, bottom = newBottom))
                            }
                        },
                )

                // Bottom-Right Corner
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (pipPixelW * currentCrop.right - 16 * density).roundToInt(),
                                (pipPixelH * currentCrop.bottom - 16 * density).roundToInt(),
                            )
                        }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 3.5f * density
                            drawLine(cyanColor, Offset(0f, size.height), Offset(size.width, size.height), strokeW)
                            drawLine(cyanColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeW)
                        }
                        .pointerInput(active.id) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val dx = drag.x / pipPixelW
                                val dy = drag.y / pipPixelH
                                val newRight = (currentCrop.right + dx).coerceIn(currentCrop.left + 0.08f, 1f)
                                val newBottom = (currentCrop.bottom + dy).coerceIn(currentCrop.top + 0.08f, 1f)
                                onCropChange(active.id, currentCrop.copy(right = newRight, bottom = newBottom))
                            }
                        },
                )

                // 3. Fty வலது கீழ் மூலையில் உள்ள பெரிய வெள்ளை நிற Scaling Handle
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.5.dp, cyanColor, CircleShape)
                        .pointerInput(active.id, w) {
                            var s = t.scale
                            detectDragGestures(
                                onDragStart = { onSelect(active.id); s = active.transform.scale },
                                onDrag = { change, drag ->
                                    change.consume()
                                    s = (s + drag.x / w).coerceIn(0.15f, 2.0f)
                                    onScale(active.id, s)
                                },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun OverlayLayer(
    overlays: List<Clip>,
    playheadMs: Long,
    selectedClipId: String?,
    onSelect: (String) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalContext.current.resources.displayMetrics.density

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        overlays
            .filter { playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs }
            .forEach { clip ->
                val props = com.actioncut.core.model.Keyframes.propsAt(clip, playheadMs - clip.timelineStartMs)
                val t = props.transform
                val glyph = clip.text?.text ?: ""
                val baseSp = clip.text?.fontSizeSp ?: 24f
                val isSelected = clip.id == selectedClipId
                val isNewsBanner = clip.mediaUri?.contains("news_banner") == true

                val gestureModifier = Modifier
                    .graphicsLayer {
                        translationX = (t.offsetX / 2f) * w
                        translationY = (t.offsetY / 2f) * h
                        rotationZ = t.rotationDegrees
                        alpha = props.opacity
                    }

                val imageUri = clip.mediaUri
                if (imageUri != null && clip.text == null) {
                    if (isNewsBanner) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(gestureModifier),
                        ) {
                            StickerImage(
                                uri = imageUri,
                                widthPx = w,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .align(Alignment.Center),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.48f)
                                    .align(Alignment.CenterStart)
                                    .clickable { onSelect(clip.id) },
                            )
                        }
                    } else {
                        StickerImage(
                            uri = imageUri,
                            widthPx = t.scale * w,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .then(gestureModifier)
                                .clickable { onSelect(clip.id) },
                        )
                    }
                } else {
                    Text(
                        text = glyph,
                        fontSize = (baseSp * t.scale).sp,
                        color = Color(clip.text?.colorArgb ?: 0xFFFFFFFF.toInt()),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .then(gestureModifier)
                            .clickable { onSelect(clip.id) },
                    )
                }
            }
    }
}

@Composable
private fun StickerImage(uri: String, widthPx: Float, modifier: Modifier = Modifier) {
    val bitmap = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = uri,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(android.net.Uri.parse(uri).path)?.asImageBitmap()
            }.getOrNull()
        }
    }.value ?: return
    val density = androidx.compose.ui.platform.LocalDensity.current
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .width(with(density) { widthPx.toDp() })
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
    )
}
