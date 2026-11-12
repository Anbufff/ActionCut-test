package com.actioncut.feature.editor.ui.preview

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.actioncut.core.media.template.FbTemplateBarRenderer
import com.actioncut.core.media.template.NewsCrickWatermarkRenderer
import com.actioncut.core.model.*
import com.actioncut.feature.editor.ui.pip.AiCutoutManager
import com.actioncut.feature.editor.ui.scorecard.ScorecardManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class DragTarget {
    NONE, MOVE_PIP, SCALE,
    CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR,
    EDGE_T, EDGE_B, EDGE_L, EDGE_R
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
    isCropModeActive: Boolean = false,
    fitMode: FitMode = FitMode.FILL,
    backgroundColorArgb: Int = 0xFF000000.toInt(),
    allClips: List<Clip> = emptyList(),
    onScorecardRowTapped: (String, Int) -> Unit = { _, _ -> },
    isFbTemplateEnabled: Boolean = false,
    fbTemplateImageUri: String? = null,
    onPickTplImage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val resizeMode = when (fitMode) {
        FitMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        FitMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        FitMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    val isTplMode = aspectRatio == AspectRatio.RATIO_4_5 && isFbTemplateEnabled

    val mainClips = allClips.filter { it.type == ClipType.VIDEO || it.type == ClipType.IMAGE }
        .sortedBy { it.timelineStartMs }
    val currentMainIndex = mainClips.indexOfFirst { playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs }
    val currentMainClip = if (currentMainIndex >= 0) mainClips[currentMainIndex] else null
    val nextMainClip = if (currentMainIndex >= 0 && currentMainIndex + 1 < mainClips.size) mainClips[currentMainIndex + 1] else null

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
            val density = LocalDensity.current.density

            if (isTplMode) {
                val vH = w * (9f / 16f)
                val barH = h - 2 * vH
                val bottomY = vH + barH

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((vH / density).dp)
                        .align(Alignment.TopCenter),
                ) {
                    if (currentMainClip?.type == ClipType.VIDEO) {
                        AndroidView(
                            factory = { context ->
                                PlayerView(context).apply {
                                    useController = false
                                    this.player = player
                                    this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            update = {
                                it.player = player
                                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (currentMainClip != null && currentMainClip.mediaUri != null) {
                        AsyncImage(
                            model = currentMainClip.mediaUri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((barH / density).dp)
                        .offset(y = (vH / density).dp)
                        .align(Alignment.TopCenter),
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        drawIntoCanvas { canvas ->
                            val timeSeconds = playheadMs / 1000f
                            FbTemplateBarRenderer.drawTplBar(canvas.nativeCanvas, size.width, size.height, timeSeconds)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((vH / density).dp)
                        .offset(y = (bottomY / density).dp)
                        .align(Alignment.TopCenter)
                        .background(Color(0xFF0B0B0B))
                        .clickable { onPickTplImage() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (!fbTemplateImageUri.isNullOrBlank()) {
                        AsyncImage(
                            model = fbTemplateImageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add image",
                                tint = Color(0xFF7FB8D8),
                                modifier = Modifier.size(32.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "படம் சேர்க்க (16:9)",
                                color = Color(0xFF7FB8D8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

            } else {
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

                    val isMainSelected = currentMainClip.id == selectedClipId
                    val mainTransform = currentMainClip.transform

                    var mainLiveOffsetX by remember(currentMainClip.id, mainTransform.offsetX) {
                        mutableFloatStateOf(mainTransform.offsetX)
                    }
                    var mainLiveOffsetY by remember(currentMainClip.id, mainTransform.offsetY) {
                        mutableFloatStateOf(mainTransform.offsetY)
                    }
                    var mainLiveScale by remember(currentMainClip.id, mainTransform.scale) {
                        mutableFloatStateOf(mainTransform.scale)
                    }

                    val currentIdx = mainClips.indexOfFirst { it.id == currentMainClip.id }
                    val nextMainClip = if (currentIdx >= 0 && currentIdx + 1 < mainClips.size) mainClips[currentIdx + 1] else null

                    val transition = currentMainClip.transitionToNext
                    val trDuration = transition?.durationMs ?: 0L
                    val remainingTime = currentMainClip.timelineEndMs - playheadMs

                    val isInTransition = transition != null &&
                        transition.type != TransitionType.SEAMLESS_MERGE &&
                        transition.type != TransitionType.NONE &&
                        trDuration > 0L &&
                        remainingTime in 1..trDuration

                    val trProgress = if (isInTransition) {
                        (1f - remainingTime.toFloat() / trDuration.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    val trType = transition?.type ?: TransitionType.NONE

                    var currentAlpha = 1.0f
                    var currentTransX = (mainLiveOffsetX / 2f) * w
                    var currentTransY = (mainLiveOffsetY / 2f) * h
                    var currentScaleFactor = 1.0f

                    var nextAlpha = 0f
                    var nextTransX = 0f
                    var nextTransY = 0f
                    var nextScaleFactor = 1.0f

                    if (isInTransition) {
                        when (trType) {
                            TransitionType.CROSSFADE, TransitionType.DISSOLVE -> {
                                currentAlpha = 1f - trProgress
                                nextAlpha = trProgress
                            }
                            TransitionType.FADE -> {
                                currentAlpha = (1f - trProgress * 2f).coerceIn(0f, 1f)
                                nextAlpha = ((trProgress - 0.5f) * 2f).coerceIn(0f, 1f)
                            }
                            TransitionType.SLIDE_LEFT -> {
                                currentTransX -= trProgress * w
                                nextTransX = (1f - trProgress) * w
                                nextAlpha = 1f
                            }
                            TransitionType.SLIDE_RIGHT -> {
                                currentTransX += trProgress * w
                                nextTransX = -(1f - trProgress) * w
                                nextAlpha = 1f
                            }
                            TransitionType.SLIDE_UP -> {
                                currentTransY -= trProgress * h
                                nextTransY = (1f - trProgress) * h
                                nextAlpha = 1f
                            }
                            TransitionType.SLIDE_DOWN -> {
                                currentTransY += trProgress * h
                                nextTransY = -(1f - trProgress) * h
                                nextAlpha = 1f
                            }
                            TransitionType.ZOOM_IN -> {
                                currentScaleFactor = 1f + trProgress * 0.35f
                                currentAlpha = 1f - trProgress
                                nextAlpha = trProgress
                            }
                            TransitionType.ZOOM_OUT -> {
                                currentScaleFactor = (1f - trProgress * 0.35f).coerceAtLeast(0.1f)
                                currentAlpha = 1f - trProgress
                                nextAlpha = trProgress
                            }
                            TransitionType.BLUR, TransitionType.WIPE -> {
                                currentAlpha = 1f - trProgress
                                nextAlpha = trProgress
                            }
                            else -> {
                                currentAlpha = 1.0f
                                nextAlpha = 0f
                            }
                        }
                    }

                    if (isInTransition && nextMainClip != null && nextMainClip.mediaUri != null && nextAlpha > 0f) {
                        AsyncImage(
                            model = nextMainClip.mediaUri,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = nextMainClip.transform.scale * nextScaleFactor
                                    scaleY = nextMainClip.transform.scale * nextScaleFactor
                                    alpha = nextAlpha
                                    translationX = nextTransX
                                    translationY = nextTransY
                                    rotationZ = (nextMainClip.rotationDegrees + nextMainClip.transform.rotationDegrees).toFloat()
                                },
                        )
                    }

                    val totalScale = autoZoomScale * mainLiveScale * currentScaleFactor

                    AsyncImage(
                        model = currentMainClip.mediaUri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = totalScale
                                scaleY = totalScale
                                alpha = currentAlpha
                                translationX = currentTransX
                                translationY = currentTransY
                                rotationZ = (currentMainClip.rotationDegrees + mainTransform.rotationDegrees).toFloat()
                            }
                            .pointerInput(currentMainClip.id, isMainSelected, w, h) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!isMainSelected) {
                                        onSelectOverlay(currentMainClip.id)
                                    }

                                    var prevPointers = 0
                                    var prevDistance = 0f

                                    do {
                                        val event = awaitPointerEvent()
                                        val pointers = event.changes.filter { it.pressed }
                                        if (pointers.isEmpty()) break

                                        if (pointers.size >= 2 && isMainSelected) {
                                            val p1 = pointers[0].position
                                            val p2 = pointers[1].position
                                            val currentDistance = hypot(p1.x - p2.x, p1.y - p2.y)

                                            if (prevPointers >= 2 && prevDistance > 10f) {
                                                val rawZoom = currentDistance / prevDistance
                                                val smoothZoom = rawZoom.coerceIn(0.80f, 1.25f)
                                                mainLiveScale = (mainLiveScale * smoothZoom).coerceIn(0.2f, 5.0f)
                                            }
                                            prevDistance = currentDistance
                                            prevPointers = 2

                                            val centroid = (p1 + p2) / 2f
                                            val prevCentroid = (pointers[0].previousPosition + pointers[1].previousPosition) / 2f
                                            val pan = centroid - prevCentroid
                                            mainLiveOffsetX = (mainLiveOffsetX + pan.x / (w / 2f)).coerceIn(-3.0f, 3.0f)
                                            mainLiveOffsetY = (mainLiveOffsetY + pan.y / (h / 2f)).coerceIn(-3.0f, 3.0f)

                                            pointers.forEach { it.consume() }
                                        } else if (isMainSelected) {
                                            prevPointers = 1
                                            prevDistance = 0f
                                            val pointer = pointers[0]
                                            val dx = pointer.position.x - pointer.previousPosition.x
                                            val dy = pointer.position.y - pointer.previousPosition.y

                                            mainLiveOffsetX = (mainLiveOffsetX + dx / (w / 2f)).coerceIn(-3.0f, 3.0f)
                                            mainLiveOffsetY = (mainLiveOffsetY + dy / (h / 2f)).coerceIn(-3.0f, 3.0f)
                                            pointer.consume()
                                        }
                                    } while (true)

                                    if (isMainSelected) {
                                        onMoveOverlay(currentMainClip.id, mainLiveOffsetX, mainLiveOffsetY)
                                        onScaleOverlay(currentMainClip.id, mainLiveScale)
                                    }
                                }
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
                        isCropModeActive = isCropModeActive,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                OverlayLayer(
                    overlays = overlays,
                    playheadMs = playheadMs,
                    selectedClipId = selectedClipId,
                    onSelect = onSelectOverlay,
                    onMove = onMoveOverlay,
                    modifier = Modifier.fillMaxSize(),
                )

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
            }

            val logoW = (w * 0.082f).coerceIn(30f * density, 55f * density)
            val logoH = logoW * 0.75f
            val logoMargin = 5f * density

            ComposeCanvas(
                modifier = Modifier
                    .size((logoW / density).dp, (logoH / density).dp)
                    .align(Alignment.TopEnd)
                    .offset(x = (-logoMargin / density).dp, y = (logoMargin / density).dp),
            ) {
                drawIntoCanvas { canvas ->
                    val timeSec = playheadMs / 1000f
                    NewsCrickWatermarkRenderer.renderLogo(
                        canvas.nativeCanvas,
                        0f,
                        0f,
                        size.width,
                        size.height,
                        timeSec
                    )
                }
            }

            val isPipSelectedNow = selectedClipId != null && pipClips.any { it.id == selectedClipId }
            if (!isPlaying && !isPipSelectedNow) {
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
    isCropModeActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val active = pipClips.firstOrNull {
        playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
    } ?: return

    val context = LocalContext.current
    val isAiCutoutOn = active.filter?.id?.contains("cutout") == true
    val isFlipped = active.filter?.id?.contains("flip") == true

    var cutoutUri by remember(active.id, active.filter?.id) {
        mutableStateOf(
            if (isAiCutoutOn && active.mediaUri != null) {
                val f = java.io.File(context.cacheDir, "cutout_${active.mediaUri.hashCode()}.png")
                if (f.exists() && f.length() > 0L) Uri.fromFile(f).toString() else null
            } else null
        )
    }

    LaunchedEffect(active.id, active.mediaUri, isAiCutoutOn) {
        if (isAiCutoutOn && active.mediaUri != null && active.type == ClipType.IMAGE) {
            cutoutUri = AiCutoutManager.getOrCreateCutoutUri(context, active.mediaUri!!)
        } else {
            cutoutUri = null
        }
    }

    val displayUri = if (isAiCutoutOn && cutoutUri != null) cutoutUri else active.mediaUri

    var mediaAspectRatio by remember(active.id, displayUri) {
        mutableFloatStateOf(
            runCatching {
                val uriStr = displayUri ?: ""
                val uri = Uri.parse(uriStr)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (uri.scheme == "content") {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, options)
                    }
                } else {
                    val path = uri.path ?: uriStr
                    BitmapFactory.decodeFile(path, options)
                }
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight.toFloat()
                } else 0f
            }.getOrDefault(0f)
        )
    }

    LaunchedEffect(pipPlayer, active.id) {
        if (active.type == ClipType.VIDEO) {
            val vSize = pipPlayer.videoSize
            if (vSize.width > 0 && vSize.height > 0) {
                mediaAspectRatio = vSize.width.toFloat() / vSize.height.toFloat()
            }
        }
    }

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current.density
        val t = active.transform
        val isSelected = active.id == selectedClipId
        val cyanColor = Color(0xFF00E5FF)

        var liveOffsetX by remember(active.id, t.offsetX) { mutableFloatStateOf(t.offsetX) }
        var liveOffsetY by remember(active.id, t.offsetY) { mutableFloatStateOf(t.offsetY) }
        var liveScale by remember(active.id, t.scale) { mutableFloatStateOf(t.scale) }

        val baseCrop = active.crop ?: CropRect(0f, 0f, 1f, 1f)
        var liveCrop by remember(active.id, baseCrop) { mutableStateOf(baseCrop) }

        val canvasAspect = if (h > 0f) w / h else (16f / 9f)
        val effectiveAspect = if (mediaAspectRatio > 0f) mediaAspectRatio else canvasAspect

        val (pipPixelW, pipPixelH) = if (effectiveAspect >= canvasAspect) {
            val pw = w * liveScale
            pw to (pw / effectiveAspect)
        } else {
            val ph = h * liveScale
            (ph * effectiveAspect) to ph
        }

        val cL = pipPixelW * liveCrop.left
        val cT = pipPixelH * liveCrop.top
        val cR = pipPixelW * liveCrop.right
        val cB = pipPixelH * liveCrop.bottom
        val cW = (cR - cL).coerceAtLeast(10f)
        val cH = (cB - cT).coerceAtLeast(10f)
        val cCenterX = (cL + cR) / 2f
        val cCenterY = (cT + cB) / 2f

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size((pipPixelW / density).dp, (pipPixelH / density).dp)
                .graphicsLayer {
                    translationX = (liveOffsetX / 2f) * w
                    translationY = (liveOffsetY / 2f) * h
                    rotationZ = t.rotationDegrees
                    alpha = active.opacity
                }
                .pointerInput(active.id, isSelected, isCropModeActive) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onSelect(active.id)

                        val pw = size.width.toFloat()
                        val ph = size.height.toFloat()

                        val curL = pw * liveCrop.left
                        val curT = ph * liveCrop.top
                        val curR = pw * liveCrop.right
                        val curB = ph * liveCrop.bottom

                        val tx = down.position.x
                        val ty = down.position.y

                        val cropSide = minOf(curR - curL, curB - curT).coerceAtLeast(1f)
                        val cornerRadius = minOf(38f * density, cropSide * 0.22f)
                        val edgeRadius = minOf(32f * density, cropSide * 0.16f)
                        val scaleRadius = minOf(42f * density, cropSide * 0.25f)

                        val target = if (isCropModeActive) {
                            when {
                                hypot(tx - curL, ty - curT) < cornerRadius -> DragTarget.CORNER_TL
                                hypot(tx - curR, ty - curT) < cornerRadius -> DragTarget.CORNER_TR
                                hypot(tx - curL, ty - curB) < cornerRadius -> DragTarget.CORNER_BL
                                hypot(tx - curR, ty - curB) < cornerRadius -> DragTarget.CORNER_BR

                                abs(ty - curT) < edgeRadius && tx in (curL - edgeRadius)..(curR + edgeRadius) -> DragTarget.EDGE_T
                                abs(ty - curB) < edgeRadius && tx in (curL - edgeRadius)..(curR + edgeRadius) -> DragTarget.EDGE_B
                                abs(tx - curL) < edgeRadius && ty in (curT - edgeRadius)..(curB + edgeRadius) -> DragTarget.EDGE_L
                                abs(tx - curR) < edgeRadius && ty in (curT - edgeRadius)..(curB + edgeRadius) -> DragTarget.EDGE_R

                                tx in curL..curR && ty in curT..curB -> DragTarget.MOVE_PIP
                                else -> DragTarget.NONE
                            }
                        } else {
                            when {
                                hypot(tx - pw, ty - ph) < scaleRadius -> DragTarget.SCALE
                                else -> DragTarget.MOVE_PIP
                            }
                        }

                        var prevPointers = 0
                        var prevDistance = 0f

                        do {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.filter { it.pressed }
                            if (pointers.isEmpty()) break

                            if (pointers.size >= 2 && !isCropModeActive) {
                                val p1 = pointers[0].position
                                val p2 = pointers[1].position
                                val currentDistance = hypot(p1.x - p2.x, p1.y - p2.y)

                                if (prevPointers >= 2 && prevDistance > 10f) {
                                    val rawZoom = currentDistance / prevDistance
                                    val smoothZoom = rawZoom.coerceIn(0.85f, 1.20f)
                                    liveScale = (liveScale * smoothZoom).coerceIn(0.15f, 4.0f)
                                }
                                prevDistance = currentDistance
                                prevPointers = 2

                                val centroid = (p1 + p2) / 2f
                                val prevCentroid = (pointers[0].previousPosition + pointers[1].previousPosition) / 2f
                                val pan = centroid - prevCentroid
                                liveOffsetX = (liveOffsetX + pan.x / (w / 2f)).coerceIn(-2.5f, 2.5f)
                                liveOffsetY = (liveOffsetY + pan.y / (h / 2f)).coerceIn(-2.5f, 2.5f)

                                pointers.forEach { it.consume() }
                            } else if (target != DragTarget.NONE) {
                                prevPointers = 1
                                prevDistance = 0f
                                val pointer = pointers[0]
                                val dx = pointer.position.x - pointer.previousPosition.x
                                val dy = pointer.position.y - pointer.previousPosition.y
                                val dxFrac = if (pw > 0f) dx / pw else 0f
                                val dyFrac = if (ph > 0f) dy / ph else 0f

                                when (target) {
                                    DragTarget.MOVE_PIP -> {
                                        liveOffsetX = (liveOffsetX + dx / (w / 2f)).coerceIn(-2.5f, 2.5f)
                                        liveOffsetY = (liveOffsetY + dy / (h / 2f)).coerceIn(-2.5f, 2.5f)
                                        pointer.consume()
                                    }
                                    DragTarget.SCALE -> {
                                        liveScale = (liveScale + (dx + dy) / (2f * w)).coerceIn(0.15f, 4.0f)
                                        pointer.consume()
                                    }
                                    DragTarget.CORNER_TL -> {
                                        val newL = (liveCrop.left + dxFrac).coerceIn(0f, liveCrop.right - 0.05f)
                                        val newT = (liveCrop.top + dyFrac).coerceIn(0f, liveCrop.bottom - 0.05f)
                                        liveCrop = liveCrop.copy(left = newL, top = newT)
                                        pointer.consume()
                                    }
                                    DragTarget.CORNER_TR -> {
                                        val newR = (liveCrop.right + dxFrac).coerceIn(liveCrop.left + 0.05f, 1f)
                                        val newT = (liveCrop.top + dyFrac).coerceIn(0f, liveCrop.bottom - 0.05f)
                                        liveCrop = liveCrop.copy(right = newR, top = newT)
                                        pointer.consume()
                                    }
                                    DragTarget.CORNER_BL -> {
                                        val newL = (liveCrop.left + dxFrac).coerceIn(0f, liveCrop.right - 0.05f)
                                        val newB = (liveCrop.bottom + dyFrac).coerceIn(liveCrop.top + 0.05f, 1f)
                                        liveCrop = liveCrop.copy(left = newL, bottom = newB)
                                        pointer.consume()
                                    }
                                    DragTarget.CORNER_BR -> {
                                        val newR = (liveCrop.right + dxFrac).coerceIn(liveCrop.left + 0.05f, 1f)
                                        val newB = (liveCrop.bottom + dyFrac).coerceIn(liveCrop.top + 0.05f, 1f)
                                        liveCrop = liveCrop.copy(right = newR, bottom = newB)
                                        pointer.consume()
                                    }
                                    DragTarget.EDGE_T -> {
                                        val newT = (liveCrop.top + dyFrac).coerceIn(0f, liveCrop.bottom - 0.05f)
                                        liveCrop = liveCrop.copy(top = newT)
                                        pointer.consume()
                                    }
                                    DragTarget.EDGE_B -> {
                                        val newB = (liveCrop.bottom + dyFrac).coerceIn(liveCrop.top + 0.05f, 1f)
                                        liveCrop = liveCrop.copy(bottom = newB)
                                        pointer.consume()
                                    }
                                    DragTarget.EDGE_L -> {
                                        val newL = (liveCrop.left + dxFrac).coerceIn(0f, liveCrop.right - 0.05f)
                                        liveCrop = liveCrop.copy(left = newL)
                                        pointer.consume()
                                    }
                                    DragTarget.EDGE_R -> {
                                        val newR = (liveCrop.right + dxFrac).coerceIn(liveCrop.left + 0.05f, 1f)
                                        liveCrop = liveCrop.copy(right = newR)
                                        pointer.consume()
                                    }
                                    else -> {}
                                }
                            }
                        } while (true)

                        onMove(active.id, liveOffsetX, liveOffsetY)
                        onScale(active.id, liveScale)
                        if (isCropModeActive) {
                            onCropChange(active.id, liveCrop)
                        }
                    }
                },
        ) {
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
                if (active.type == ClipType.VIDEO) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                this.player = pipPlayer
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        },
                        update = { it.player = pipPlayer },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (isFlipped) -1f else 1f
                            },
                    )
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(displayUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onSuccess = { state ->
                            val d = state.result.drawable
                            if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                                mediaAspectRatio = d.intrinsicWidth.toFloat() / d.intrinsicHeight.toFloat()
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (isFlipped) -1f else 1f
                            },
                    )
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val stroke = Stroke(
                                width = 2f * density,
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

                if (isCropModeActive) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cCenterX - 11 * density).roundToInt(), (cT - 11 * density).roundToInt()) }
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(cyanColor)
                            .border(2.5.dp, Color.White, CircleShape),
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cCenterX - 11 * density).roundToInt(), (cB - 11 * density).roundToInt()) }
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(cyanColor)
                            .border(2.5.dp, Color.White, CircleShape),
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cL - 11 * density).roundToInt(), (cCenterY - 11 * density).roundToInt()) }
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(cyanColor)
                            .border(2.5.dp, Color.White, CircleShape),
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cR - 11 * density).roundToInt(), (cCenterY - 11 * density).roundToInt()) }
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(cyanColor)
                            .border(2.5.dp, Color.White, CircleShape),
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cL - 5 * density).roundToInt(), (cT - 5 * density).roundToInt()) }
                            .size(24.dp)
                            .drawBehind {
                                val strokeW = 4.5f * density
                                drawLine(cyanColor, Offset(0f, 0f), Offset(size.width, 0f), strokeW)
                                drawLine(cyanColor, Offset(0f, 0f), Offset(0f, size.height), strokeW)
                            },
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cR - 19 * density).roundToInt(), (cT - 5 * density).roundToInt()) }
                            .size(24.dp)
                            .drawBehind {
                                val strokeW = 4.5f * density
                                drawLine(cyanColor, Offset(0f, 0f), Offset(size.width, 0f), strokeW)
                                drawLine(cyanColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeW)
                            },
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cL - 5 * density).roundToInt(), (cB - 19 * density).roundToInt()) }
                            .size(24.dp)
                            .drawBehind {
                                val strokeW = 4.5f * density
                                drawLine(cyanColor, Offset(0f, size.height), Offset(size.width, size.height), strokeW)
                                drawLine(cyanColor, Offset(0f, 0f), Offset(0f, size.height), strokeW)
                            },
                    )

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((cR - 19 * density).roundToInt(), (cB - 19 * density).roundToInt()) }
                            .size(24.dp)
                            .drawBehind {
                                val strokeW = 4.5f * density
                                drawLine(cyanColor, Offset(0f, size.height), Offset(size.width, size.height), strokeW)
                                drawLine(cyanColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeW)
                            },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 8.dp, y = 8.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(3.5.dp, cyanColor, CircleShape),
                    )
                }
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
                BitmapFactory.decodeFile(Uri.parse(uri).path)?.asImageBitmap()
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
