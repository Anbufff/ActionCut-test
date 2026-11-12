package com.actioncut.feature.editor.ui.preview

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.actioncut.core.model.*
import com.actioncut.feature.editor.ui.pip.AiCutoutManager
import com.actioncut.feature.editor.ui.scorecard.ScorecardManager
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class DragTarget {
    NONE, MOVE_PIP, SCALE,
    CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR,
    EDGE_T, EDGE_B, EDGE_L, EDGE_R,
    PAN_CROP
}

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
    isCropModeActive: Boolean = true,
    fitMode: FitMode = FitMode.FILL,
    backgroundColorArgb: Int = 0xFF000000.toInt(),
    allClips: List<Clip> = emptyList(),
    onScorecardRowTapped: (String, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val resizeMode = when (fitMode) {
        FitMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        FitMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        FitMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
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

            // 1. பின்னணியில் Blur Effect
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

            // 2. Main Track Image
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

            // 3. Fty பாணியில் PiP லேயர் (அதிவேக 120fps Smooth Gesture Controller)
            if (pipPlayer != null) {
                PipLayer(
                    pipPlayer = pipPlayer,
                    pipClips = pipClips,
                    playheadMs = playheadMs,
                    selectedClipId = selectedClipId,
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
 * Buttery Smooth 120fps Gesture Controller:
 * ஒரே ஒரு Unified PointerInput மூலம் மோதலின்றி உடனுக்குடன் இயங்கும் Move & Crop அமைப்பு.
 */
@Composable
private fun PipLayer(
    pipPlayer: ExoPlayer,
    pipClips: List<Clip>,
    playheadMs: Long,
    selectedClipId: String?,
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
    val isAiCutoutOn = active.filter?.id?.contains("cutout") == true
    val isFlipped = active.filter?.id?.contains("flip") == true

    LaunchedEffect(active.id, active.mediaUri, isAiCutoutOn) {
        if (isAiCutoutOn && active.mediaUri != null && active.type == ClipType.IMAGE) {
            cutoutUri = AiCutoutManager.getOrCreateCutoutUri(context, active.mediaUri!!)
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
        val isSelected = active.id == selectedClipId
        val cyanColor = Color(0xFF00E5FF)

        var liveOffsetX by remember(active.id, t.offsetX) { mutableFloatStateOf(t.offsetX) }
        var liveOffsetY by remember(active.id, t.offsetY) { mutableFloatStateOf(t.offsetY) }
        var liveScale by remember(active.id, t.scale) { mutableFloatStateOf(t.scale) }

        val baseCrop = active.crop ?: CropRect(0f, 0f, 1f, 1f)
        var liveCrop by remember(active.id, baseCrop) { mutableStateOf(baseCrop) }

        val pipPixelW = (w * liveScale)
        val pipPixelH = (h * liveScale)

        val cL = pipPixelW * liveCrop.left
        val cT = pipPixelH * liveCrop.top
        val cR = pipPixelW * liveCrop.right
        val cB = pipPixelH * liveCrop.bottom
        val cW = (cR - cL).coerceAtLeast(10f)
        val cH = (cB - cT).coerceAtLeast(10f)
        val cCenterX = (cL + cR) / 2f
        val cCenterY = (cT + cB) / 2f

        var currentDragTarget by remember { mutableStateOf(DragTarget.NONE) }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size((pipPixelW / density).dp, (pipPixelH / density).dp)
                .graphicsLayer {
                    translationX = (liveOffsetX / 2f) * w
                    translationY = (liveOffsetY / 2f) * h
                    scaleX = if (isFlipped) -1f else 1f
                    rotationZ = t.rotationDegrees
                    alpha = props.opacity
                }
                .pointerInput(active.id, pipPixelW, pipPixelH, isSelected) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onSelect(active.id)
                            if (!isSelected) {
                                currentDragTarget = DragTarget.MOVE_PIP
                                return@detectDragGestures
                            }

                            val tx = offset.x
                            val ty = offset.y
                            val cornerTouchRadius = 40f * density
                            val edgeTouchRadius = 32f * density

                            currentDragTarget = when {
                                // 1. நான்கு மூலைகளின் தொடுதல் (4 Corners)
                                hypot(tx - cL, ty - cT) < cornerTouchRadius -> DragTarget.CORNER_TL
                                hypot(tx - cR, ty - cT) < cornerTouchRadius -> DragTarget.CORNER_TR
                                hypot(tx - cL, ty - cB) < cornerTouchRadius -> DragTarget.CORNER_BL
                                hypot(tx - cR, ty - cB) < cornerTouchRadius -> DragTarget.CORNER_BR

                                // 2. நான்கு பக்க விளிம்பு வட்டங்கள் (4 Edge Handles)
                                hypot(tx - cCenterX, ty - cT) < edgeTouchRadius -> DragTarget.EDGE_T
                                hypot(tx - cCenterX, ty - cB) < edgeTouchRadius -> DragTarget.EDGE_B
                                hypot(tx - cL, ty - cCenterY) < edgeTouchRadius -> DragTarget.EDGE_L
                                hypot(tx - cR, ty - cCenterY) < edgeTouchRadius -> DragTarget.EDGE_R

                                // 3. வலது கீழ் மூலை ஸ்கேலிங் கைப்பிடி (White Scale Circle)
                                hypot(tx - pipPixelW, ty - pipPixelH) < 42f * density -> DragTarget.SCALE

                                // 4. நடுப்பகுதி Crop Pan Zone
                                tx in (cL..cR) && ty in (cT..cB) -> DragTarget.PAN_CROP

                                // 5. பிற பகுதிகள் - PiP நகர்த்துதல் (Move PiP)
                                else -> DragTarget.MOVE_PIP
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dx = dragAmount.x
                            val dy = dragAmount.y
                            val dxFrac = dx / pipPixelW
                            val dyFrac = dy / pipPixelH

                            when (currentDragTarget) {
                                DragTarget.CORNER_TL -> {
                                    val newL = (liveCrop.left + dxFrac).coerceIn(0f, liveCrop.right - 0.05f)
                                    val newT = (liveCrop.top + dyFrac).coerceIn(0f, liveCrop.bottom - 0.05f)
                                    liveCrop = liveCrop.copy(left = newL, top = newT)
                                }
                                DragTarget.CORNER_TR -> {
                                    val newR = (liveCrop.right + dxFrac).coerceIn(liveCrop.left + 0.05f, 1f)
                                    val newT = (liveCrop.top + dyFrac).coerceIn(0f, liveCrop.bottom - 0.05f)
                                    liveCrop = liveCrop.copy(right = newR, top = newT)
                                }
                                DragTarget.CORNER_BL -> {
                                    val newL = (liveCrop.left + dxFrac).coerceIn(0f, liveCrop.right - 0.05f)
                                    val newB = (liveCrop.bottom + dyFrac).coerceIn(liveCrop.top + 0.05f, 1f)
                                    liveCrop = liveCrop.copy(left = newL, bottom = newB)
                                }
                                DragTarget.CORNER_BR -> {
                                    val newR = (liveCrop.right + dxFrac).coerceIn(liveCrop.left + 0.05f, 1f)
                                    val newB = (liveCrop.bottom + dyFrac).coerceIn(liveCrop.top + 0.05f, 1f)
                                    liveCrop = liveCrop.copy(right = newR, bottom = newB)
                                }
                                DragTarget.EDGE_T -> {
                                    val newT = (liveCrop.top + dyFrac).coerceIn(0f, liveCrop.bottom - 0.05f)
                                    liveCrop = liveCrop.copy(top = newT)
                                }
                                DragTarget.EDGE_B -> {
                                    val newB = (liveCrop.bottom + dyFrac).coerceIn(liveCrop.top + 0.05f, 1f)
                                    liveCrop = liveCrop.copy(bottom = newB)
                                }
                                DragTarget.EDGE_L -> {
                                    val newL = (liveCrop.left + dxFrac).coerceIn(0f, liveCrop.right - 0.05f)
                                    liveCrop = liveCrop.copy(left = newL)
                                }
                                DragTarget.EDGE_R -> {
                                    val newR = (liveCrop.right + dxFrac).coerceIn(liveCrop.left + 0.05f, 1f)
                                    liveCrop = liveCrop.copy(right = newR)
                                }
                                DragTarget.PAN_CROP -> {
                                    val cropWidth = liveCrop.right - liveCrop.left
                                    val cropHeight = liveCrop.bottom - liveCrop.top
                                    val newL = (liveCrop.left + dxFrac).coerceIn(0f, 1f - cropWidth)
                                    val newT = (liveCrop.top + dyFrac).coerceIn(0f, 1f - cropHeight)
                                    liveCrop = CropRect(newL, newT, newL + cropWidth, newT + cropHeight)
                                }
                                DragTarget.SCALE -> {
                                    liveScale = (liveScale + dx / w).coerceIn(0.15f, 2.5f)
                                }
                                DragTarget.MOVE_PIP -> {
                                    liveOffsetX = (liveOffsetX + dx / (w / 2f)).coerceIn(-1.8f, 1.8f)
                                    liveOffsetY = (liveOffsetY + dy / (h / 2f)).coerceIn(-1.8f, 1.8f)
                                }
                                DragTarget.NONE -> {}
                            }
                        },
                        onDragEnd = {
                            when (currentDragTarget) {
                                DragTarget.CORNER_TL, DragTarget.CORNER_TR, DragTarget.CORNER_BL, DragTarget.CORNER_BR,
                                DragTarget.EDGE_T, DragTarget.EDGE_B, DragTarget.EDGE_L, DragTarget.EDGE_R,
                                DragTarget.PAN_CROP -> onCropChange(active.id, liveCrop)

                                DragTarget.SCALE -> onScale(active.id, liveScale)

                                DragTarget.MOVE_PIP -> onMove(active.id, liveOffsetX, liveOffsetY)

                                DragTarget.NONE -> {}
                            }
                            currentDragTarget = DragTarget.NONE
                        },
                        onDragCancel = {
                            onCropChange(active.id, liveCrop)
                            onScale(active.id, liveScale)
                            onMove(active.id, liveOffsetX, liveOffsetY)
                            currentDragTarget = DragTarget.NONE
                        },
                    )
                },
        ) {
            // 1. பிம்பம் - Crop செய்யப்பட்டு வரைதல்
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val crop = liveCrop
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

            // 2. Fty பாணி சியான் டாஷ்ட் பார்டர், Brackets & Handles
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val stroke = Stroke(
                                width = 2.5f * density,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                            )
                            drawRoundRect(
                                color = cyanColor,
                                topLeft = Offset(cL, cT),
                                size = Size(cW, cH),
                                cornerRadius = CornerRadius(2f * density),
                                style = stroke,
                            )
                        },
                )

                // Top Edge Dot
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cCenterX - 9 * density).roundToInt(), (cT - 9 * density).roundToInt()) }
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(2.dp, Color.White, CircleShape),
                )

                // Bottom Edge Dot
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cCenterX - 9 * density).roundToInt(), (cB - 9 * density).roundToInt()) }
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(2.dp, Color.White, CircleShape),
                )

                // Left Edge Dot
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cL - 9 * density).roundToInt(), (cCenterY - 9 * density).roundToInt()) }
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(2.dp, Color.White, CircleShape),
                )

                // Right Edge Dot
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cR - 9 * density).roundToInt(), (cCenterY - 9 * density).roundToInt()) }
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(cyanColor)
                        .border(2.dp, Color.White, CircleShape),
                )

                // Top-Left Bracket
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cL - 4 * density).roundToInt(), (cT - 4 * density).roundToInt()) }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 4f * density
                            drawLine(cyanColor, Offset(0f, 0f), Offset(size.width, 0f), strokeW)
                            drawLine(cyanColor, Offset(0f, 0f), Offset(0f, size.height), strokeW)
                        },
                )

                // Top-Right Bracket
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cR - 16 * density).roundToInt(), (cT - 4 * density).roundToInt()) }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 4f * density
                            drawLine(cyanColor, Offset(0f, 0f), Offset(size.width, 0f), strokeW)
                            drawLine(cyanColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeW)
                        },
                )

                // Bottom-Left Bracket
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cL - 4 * density).roundToInt(), (cB - 16 * density).roundToInt()) }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 4f * density
                            drawLine(cyanColor, Offset(0f, size.height), Offset(size.width, size.height), strokeW)
                            drawLine(cyanColor, Offset(0f, 0f), Offset(0f, size.height), strokeW)
                        },
                )

                // Bottom-Right Bracket
                Box(
                    modifier = Modifier
                        .offset { IntOffset((cR - 16 * density).roundToInt(), (cB - 16 * density).roundToInt()) }
                        .size(20.dp)
                        .drawBehind {
                            val strokeW = 4f * density
                            drawLine(cyanColor, Offset(0f, size.height), Offset(size.width, size.height), strokeW)
                            drawLine(cyanColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeW)
                        },
                )

                // Fty Scale Circle (White with Cyan border)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, cyanColor, CircleShape),
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
    val bitmap = androidx.compose.runtime.produceState<ImageBitmap?>(
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
