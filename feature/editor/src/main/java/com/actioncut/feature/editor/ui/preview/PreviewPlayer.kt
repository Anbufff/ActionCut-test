package com.actioncut.feature.editor.ui.preview

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.actioncut.core.designsystem.component.SelectableChip
import com.actioncut.core.designsystem.component.rememberHaptic
import com.actioncut.core.media.template.FbTemplateBarRenderer
import com.actioncut.core.media.template.NewsCrickWatermarkRenderer
import com.actioncut.core.model.*
import com.actioncut.feature.editor.R
import com.actioncut.feature.editor.ui.pip.AiCutoutManager
import com.actioncut.feature.editor.ui.scorecard.ScorecardManager
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class DragTarget {
    NONE, MOVE_PIP, SCALE, ANIM_HANDLE, DELETE_HANDLE, FLIP_HANDLE
}

private data class BannerFrameSet(
    val fractions: List<Float>,
    val bitmaps: List<ImageBitmap>,
)

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
    onSetPipAnimation: (String, PipAnimation) -> Unit = { _, _ -> },
    onDeleteOverlay: (String) -> Unit = {},
    onFlipOverlay: (String) -> Unit = {},
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
    val context = LocalContext.current

    val mainClips = allClips.filter { it.type == ClipType.VIDEO || it.type == ClipType.IMAGE }
        .sortedBy { it.timelineStartMs }
    val currentMainIndex = mainClips.indexOfFirst { playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs }
    val currentMainClip = if (currentMainIndex >= 0) mainClips[currentMainIndex] else null

    val isScorecardActiveNow = allClips.any {
        it.scorecard != null && playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
    }

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
                            factory = { ctx ->
                                PlayerView(ctx).apply {
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
                        val tplMainReq = remember(currentMainClip.mediaUri) {
                            ImageRequest.Builder(context)
                                .data(currentMainClip.mediaUri)
                                .size(1280, 720)
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = tplMainReq,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    ScorecardLayer(
                        allClips = allClips,
                        playheadMs = playheadMs,
                        onScorecardRowTapped = onScorecardRowTapped,
                        modifier = Modifier.fillMaxSize(),
                    )
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
                        val tplBottomReq = remember(fbTemplateImageUri) {
                            ImageRequest.Builder(context)
                                .data(fbTemplateImageUri)
                                .size(1280, 720)
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = tplBottomReq,
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
                if (currentMainClip != null && currentMainClip.mediaUri != null && currentMainClip.scorecard == null) {
                    val blurReq = remember(currentMainClip.mediaUri) {
                        ImageRequest.Builder(context)
                            .data(currentMainClip.mediaUri)
                            .size(64, 36)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = blurReq,
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
                        factory = { ctx ->
                            PlayerView(ctx).apply {
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

                if (currentMainClip != null && currentMainClip.type == ClipType.IMAGE && currentMainClip.mediaUri != null && currentMainClip.scorecard == null) {
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
                    // Always-fresh values for the long-running gesture coroutine (so that selecting the
                    // clip on the first touch does NOT cancel the drag that is already in progress).
                    val isMainSelectedState by rememberUpdatedState(isMainSelected)
                    val onSelectOverlayState by rememberUpdatedState(onSelectOverlay)
                    val onMoveOverlayState by rememberUpdatedState(onMoveOverlay)
                    val onScaleOverlayState by rememberUpdatedState(onScaleOverlay)

                    val currentIdx = mainClips.indexOfFirst { it.id == currentMainClip.id }
                    val nextClip = if (currentIdx >= 0 && currentIdx + 1 < mainClips.size) mainClips[currentIdx + 1] else null

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

                    if (isInTransition && nextClip != null && nextClip.mediaUri != null && nextAlpha > 0f) {
                        val nextImgReq = remember(nextClip.mediaUri) {
                            ImageRequest.Builder(context)
                                .data(nextClip.mediaUri)
                                .size(1280, 1280)
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = nextImgReq,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = nextClip.transform.scale * nextScaleFactor
                                    scaleY = nextClip.transform.scale * nextScaleFactor
                                    alpha = nextAlpha
                                    translationX = nextTransX
                                    translationY = nextTransY
                                    rotationZ = (nextClip.rotationDegrees + nextClip.transform.rotationDegrees).toFloat()
                                },
                        )
                    }

                    val totalScale = autoZoomScale * mainLiveScale * currentScaleFactor
                    val curImgReq = remember(currentMainClip.mediaUri) {
                        ImageRequest.Builder(context)
                            .data(currentMainClip.mediaUri)
                            .size(1280, 1280)
                            .crossfade(false)
                            .build()
                    }

                    AsyncImage(
                        model = curImgReq,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(
                                currentMainClip.id,
                                mainTransform.offsetX,
                                mainTransform.offsetY,
                                mainTransform.scale,
                                w,
                                h,
                            ) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    if (!isMainSelectedState) {
                                        onSelectOverlayState(currentMainClip.id)
                                    }

                                    var prevPointers = 0
                                    var prevDistance = 0f

                                    do {
                                        val event = awaitPointerEvent()
                                        val pointers = event.changes.filter { it.pressed }
                                        if (pointers.isEmpty()) break

                                        if (pointers.size >= 2 && isMainSelectedState) {
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
                                        } else if (isMainSelectedState) {
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

                                    if (isMainSelectedState) {
                                        onMoveOverlayState(currentMainClip.id, mainLiveOffsetX, mainLiveOffsetY)
                                        onScaleOverlayState(currentMainClip.id, mainLiveScale)
                                    }
                                }
                            }
                            .graphicsLayer {
                                scaleX = totalScale
                                scaleY = totalScale
                                alpha = currentAlpha
                                translationX = currentTransX
                                translationY = currentTransY
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

                // 1. News Banner / Stadium Background Layer goes UNDER the Player Cutout (and NEVER over a Scorecard)
                val newsBannerOverlays = remember(overlays) {
                    overlays.filter { it.mediaUri?.contains("news_banner") == true }
                }
                val foregroundOverlays = remember(overlays) {
                    overlays.filter { it.mediaUri?.contains("news_banner") != true }
                }

                if (!isScorecardActiveNow) {
                    OverlayLayer(
                        overlays = newsBannerOverlays,
                        playheadMs = playheadMs,
                        selectedClipId = selectedClipId,
                        onSelect = onSelectOverlay,
                        onMove = onMoveOverlay,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 2. Player Cutout / PiP Layer sits ON TOP of the Stadium Background (AI cutouts hidden on Scorecard)
                if (pipPlayer != null) {
                    val allowedPipClips = if (isScorecardActiveNow) {
                        pipClips.filterNot { it.id.startsWith("pip_ai_") }
                    } else {
                        pipClips
                    }
                    PipLayer(
                        pipPlayer = pipPlayer,
                        pipClips = allowedPipClips,
                        playheadMs = playheadMs,
                        selectedClipId = selectedClipId,
                        onSelect = onSelectOverlay,
                        onMove = onMoveOverlay,
                        onScale = onScaleOverlay,
                        onSetPipAnimation = onSetPipAnimation,
                        onDelete = onDeleteOverlay,
                        onFlip = onFlipOverlay,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // 3. Text & Sticker Overlays sit above PiP
                OverlayLayer(
                    overlays = foregroundOverlays,
                    playheadMs = playheadMs,
                    selectedClipId = selectedClipId,
                    onSelect = onSelectOverlay,
                    onMove = onMoveOverlay,
                    modifier = Modifier.fillMaxSize(),
                )

                ScorecardLayer(
                    allClips = allClips,
                    playheadMs = playheadMs,
                    onScorecardRowTapped = onScorecardRowTapped,
                    modifier = Modifier.fillMaxSize(),
                )
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
    onSetPipAnimation: (String, PipAnimation) -> Unit,
    onDelete: (String) -> Unit,
    onFlip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = pipClips.firstOrNull {
        playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
    } ?: return

    val context = LocalContext.current
    val isAiCutoutOn = active.filter?.id?.contains("cutout") == true
    val isFlipped = active.filter?.id?.contains("flip") == true

    var showAnimMenu by remember(active.id) { mutableStateOf(false) }

    var cutoutUri by remember(active.id, active.filter?.id) {
        mutableStateOf(
            if (isAiCutoutOn && active.mediaUri != null) {
                val f = File(context.cacheDir, "cutout_${active.mediaUri.hashCode()}.png")
                if (f.exists() && f.length() > 0L) Uri.fromFile(f).toString() else null
            } else null
        )
    }

    LaunchedEffect(active.id, active.mediaUri, isAiCutoutOn) {
        if (isAiCutoutOn && active.mediaUri != null && active.type == ClipType.IMAGE) {
            cutoutUri = AiCutoutManager.getOrCreateCutoutUri(
                context = context,
                imageUriStr = active.mediaUri!!,
                softBottomFade = active.id.startsWith("pip_ai_"),
            )
        } else {
            cutoutUri = null
        }
    }

    val displayUri = if (isAiCutoutOn && cutoutUri != null) cutoutUri else active.mediaUri
    val cutoutStamp = remember(displayUri, isAiCutoutOn) {
        if (isAiCutoutOn && displayUri != null) {
            runCatching { File(Uri.parse(displayUri).path ?: "").lastModified() }.getOrDefault(0L)
        } else 0L
    }

    var mediaAspectRatio by remember(active.id, displayUri, cutoutStamp) {
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
        val elapsedMs = (playheadMs - active.timelineStartMs).coerceAtLeast(0L)
        val props = Keyframes.propsAt(active, elapsedMs)
        val t = props.transform
        val isSelected = active.id == selectedClipId
        val cyanColor = Color(0xFF00E5FF)

        if (!isSelected && showAnimMenu) {
            showAnimMenu = false
        }

        val autoAnimAlpha = if (active.id.startsWith("pip_ai_")) {
            val remaining = (active.timelineEndMs - playheadMs).coerceAtLeast(0L)
            val fadeMs = 280f
            when {
                elapsedMs < fadeMs -> (elapsedMs / fadeMs).coerceIn(0f, 1f)
                remaining < fadeMs -> (remaining / fadeMs).coerceIn(0f, 1f)
                else -> 1f
            }
        } else 1f

        var liveOffsetX by remember(active.id, t.offsetX) { mutableFloatStateOf(t.offsetX) }
        var liveOffsetY by remember(active.id, t.offsetY) { mutableFloatStateOf(t.offsetY) }
        var liveScale by remember(active.id, t.scale) { mutableFloatStateOf(t.scale) }
        var isDraggingPip by remember(active.id) { mutableStateOf(false) }
        var touchBoxCoords by remember(active.id) { mutableStateOf<LayoutCoordinates?>(null) }

        val liveCrop = active.crop ?: CropRect(0f, 0f, 1f, 1f)

        val canvasAspect = if (h > 0f) w / h else (16f / 9f)
        val effectiveAspect = if (mediaAspectRatio > 0f) mediaAspectRatio else canvasAspect

        fun pipDimsFor(scaleValue: Float): Pair<Float, Float> =
            if (effectiveAspect >= canvasAspect) {
                val pw = w * scaleValue
                pw to (pw / effectiveAspect)
            } else {
                val ph = h * scaleValue
                (ph * effectiveAspect) to ph
            }

        val (pipPixelW, pipPixelH) = pipDimsFor(liveScale)

        val cL = pipPixelW * liveCrop.left
        val cT = pipPixelH * liveCrop.top
        val cR = pipPixelW * liveCrop.right
        val cB = pipPixelH * liveCrop.bottom
        val cW = (cR - cL).coerceAtLeast(10f)
        val cH = (cB - cT).coerceAtLeast(10f)

        val eased = if (isDraggingPip || active.pipAnimation == PipAnimation.NONE) {
            1f
        } else {
            PipAnimations.easeOut(elapsedMs)
        }

        val restTx = (liveOffsetX / 2f) * w
        val restTy = (liveOffsetY / 2f) * h
        val restCL = (w / 2f) + restTx - (pipPixelW / 2f) + cL
        val restCT = (h / 2f) + restTy - (pipPixelH / 2f) + cT

        val animShiftX: Float
        val animShiftY: Float
        val animScale: Float
        when (active.pipAnimation) {
            PipAnimation.SLIDE_LEFT -> {
                animShiftX = -(restCL + cW) * (1f - eased)
                animShiftY = 0f
                animScale = 1f
            }
            PipAnimation.SLIDE_UP -> {
                animShiftX = 0f
                animShiftY = (h - restCT) * (1f - eased)
                animScale = 1f
            }
            PipAnimation.ZOOM_IN -> {
                animShiftX = 0f
                animShiftY = 0f
                animScale = 0.05f + 0.95f * eased
            }
            PipAnimation.NONE -> {
                animShiftX = 0f
                animShiftY = 0f
                animScale = 1f
            }
        }

        val visLeft = restCL + animShiftX
        val visTop = restCT + animShiftY

        fun applyScaleKeepingCenter(newScale: Float) {
            val clamped = newScale.coerceIn(0.10f, 20.0f)
            val (oldW, oldH) = pipDimsFor(liveScale)
            val (newW, newH) = pipDimsFor(clamped)
            val cropCxRel = (liveCrop.left + liveCrop.right) / 2f - 0.5f
            val cropCyRel = (liveCrop.top + liveCrop.bottom) / 2f - 0.5f
            val dOffX = cropCxRel * (newW - oldW)
            val dOffY = cropCyRel * (newH - oldH)
            liveScale = clamped
            if (w > 0f && h > 0f) {
                liveOffsetX = (liveOffsetX - dOffX / (w / 2f)).coerceIn(-12f, 12f)
                liveOffsetY = (liveOffsetY - dOffY / (h / 2f)).coerceIn(-12f, 12f)
            }
        }

        // Always-fresh selection flag for the gesture coroutine: selecting the PiP on the first touch
        // must not restart (= cancel) the drag that is already running.
        val isSelectedState by rememberUpdatedState(isSelected)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(align = Alignment.TopStart, unbounded = true),
        ) {
            // 1. Hardware-clipped (cW x cH) media container — impossible to leak outside crop during playback
            Box(
                modifier = Modifier
                    .offset { IntOffset(visLeft.roundToInt(), visTop.roundToInt()) }
                    .requiredSize((cW / density).dp, (cH / density).dp)
                    .graphicsLayer {
                        scaleX = animScale
                        scaleY = animScale
                        transformOrigin = TransformOrigin.Center
                        rotationZ = t.rotationDegrees
                        alpha = props.opacity * autoAnimAlpha
                    }
                    .clipToBounds()
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true),
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset((-cL).roundToInt(), (-cT).roundToInt()) }
                        .requiredSize((pipPixelW / density).dp, (pipPixelH / density).dp)
                        .graphicsLayer {
                            scaleX = if (isFlipped) -1f else 1f
                        },
                ) {
                    if (active.type == ClipType.VIDEO) {
                        AndroidView(
                            factory = { ctx ->
                                // TextureView-backed PlayerView: unlike a SurfaceView it follows the Compose
                                // clip / offset / scale / flip exactly, so the cropped PiP never shows the
                                // original uncropped frame while it is being moved.
                                (LayoutInflater.from(ctx)
                                    .inflate(R.layout.pip_texture_player_view, null, false) as PlayerView).apply {
                                    useController = false
                                    this.player = pipPlayer
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                }
                            },
                            update = { it.player = pipPlayer },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val pipImgReq = remember(displayUri, cutoutStamp, isAiCutoutOn) {
                            ImageRequest.Builder(context)
                                .data(displayUri)
                                .size(1024, 1024)
                                .crossfade(false)
                                .apply {
                                    if (isAiCutoutOn) {
                                        memoryCacheKey("${displayUri}_$cutoutStamp")
                                        diskCachePolicy(CachePolicy.DISABLED)
                                    }
                                }
                                .build()
                        }
                        // Pixel-exact crop: only the cropped source rectangle is ever drawn, so the
                        // uncropped picture can never show through (no dependence on view clipping).
                        val pipBitmap = produceState<ImageBitmap?>(
                            initialValue = null,
                            key1 = displayUri,
                            key2 = cutoutStamp,
                        ) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    val res = coil.Coil.imageLoader(context).execute(
                                        ImageRequest.Builder(context)
                                            .data(displayUri)
                                            .size(1024, 1024)
                                            .allowHardware(false)
                                            .crossfade(false)
                                            .apply {
                                                if (isAiCutoutOn) {
                                                    memoryCacheKey("${displayUri}_$cutoutStamp")
                                                    diskCachePolicy(CachePolicy.DISABLED)
                                                }
                                            }
                                            .build(),
                                    )
                                    ((res as? coil.request.SuccessResult)?.drawable
                                        as? android.graphics.drawable.BitmapDrawable)
                                        ?.bitmap?.asImageBitmap()
                                }.getOrNull()
                            }
                        }.value

                        LaunchedEffect(pipBitmap) {
                            pipBitmap?.let { b ->
                                if (b.width > 0 && b.height > 0) {
                                    mediaAspectRatio = b.width.toFloat() / b.height.toFloat()
                                }
                            }
                        }

                        if (pipBitmap != null) {
                            ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                                val srcL = if (isFlipped) 1f - liveCrop.right else liveCrop.left
                                val srcR = if (isFlipped) 1f - liveCrop.left else liveCrop.right
                                val bw = pipBitmap.width
                                val bh = pipBitmap.height
                                val sx = (srcL * bw).roundToInt().coerceIn(0, bw - 1)
                                val sy = (liveCrop.top * bh).roundToInt().coerceIn(0, bh - 1)
                                val sw = ((srcR - srcL) * bw).roundToInt().coerceIn(1, bw - sx)
                                val sh = ((liveCrop.bottom - liveCrop.top) * bh).roundToInt().coerceIn(1, bh - sy)
                                drawImage(
                                    image = pipBitmap,
                                    srcOffset = IntOffset(sx, sy),
                                    srcSize = IntSize(sw, sh),
                                    dstOffset = IntOffset(
                                        (size.width * srcL).roundToInt(),
                                        (size.height * liveCrop.top).roundToInt(),
                                    ),
                                    dstSize = IntSize(
                                        ((srcR - srcL) * size.width).roundToInt().coerceAtLeast(1),
                                        ((liveCrop.bottom - liveCrop.top) * size.height).roundToInt().coerceAtLeast(1),
                                    ),
                                    filterQuality = FilterQuality.High,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Selection dashed border + 4 corner handles (unclipped so corner circles stay crisp)
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(visLeft.roundToInt(), visTop.roundToInt()) }
                        .requiredSize((cW / density).dp, (cH / density).dp)
                        .graphicsLayer {
                            scaleX = animScale
                            scaleY = animScale
                            transformOrigin = TransformOrigin.Center
                            rotationZ = t.rotationDegrees
                        }
                        .drawBehind {
                            val stroke = Stroke(
                                width = 2f * density,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                            )
                            drawRoundRect(
                                color = cyanColor,
                                topLeft = Offset.Zero,
                                size = size,
                                cornerRadius = CornerRadius(2f * density),
                                style = stroke,
                            )
                        },
                )

                // Top-left ✕ Delete handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (visLeft - 14f * density).roundToInt(),
                                (visTop - 14f * density).roundToInt(),
                            )
                        }
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1F1F24))
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Delete",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Top-right Flip handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (visLeft + cW - 14f * density).roundToInt(),
                                (visTop - 14f * density).roundToInt(),
                            )
                        }
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Flip,
                        contentDescription = "Flip",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Bottom-left Animation Effects handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (visLeft - 14f * density).roundToInt(),
                                (visTop + cH - 14f * density).roundToInt(),
                            )
                        }
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Animation,
                        contentDescription = "Animation Effects",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }

                // Bottom-right Scale handle
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (visLeft + cW - 14f * density).roundToInt(),
                                (visTop + cH - 14f * density).roundToInt(),
                            )
                        }
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, cyanColor, CircleShape),
                )
            }

            // 3. Touch Box covering ONLY the visible cropped area + 24dp handle margin
            // Touch area = visible crop + 24dp margin, and never smaller than 120dp so that a small
            // PiP can still be grabbed / pinched comfortably with two fingers.
            val minTouchPx = 120f * density
            val padX = maxOf(24f * density, (minTouchPx - cW) / 2f)
            val padY = maxOf(24f * density, (minTouchPx - cH) / 2f)
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset((visLeft - padX).roundToInt(), (visTop - padY).roundToInt())
                    }
                    .requiredSize(((cW + 2f * padX) / density).dp, ((cH + 2f * padY) / density).dp)
                    .onGloballyPositioned { touchBoxCoords = it }
                    .pointerInput(
                        active.id,
                        t.offsetX,
                        t.offsetY,
                        t.scale,
                        liveCrop,
                        w,
                        h,
                        effectiveAspect,
                    ) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val wasSelectedAtDown = isSelectedState
                            onSelect(active.id)

                            val relL = padX
                            val relT = padY
                            val relR = size.width.toFloat() - padX
                            val relB = size.height.toFloat() - padY

                            val tx = down.position.x
                            val ty = down.position.y

                            val handleRadius = 26f * density
                            val scaleRadius = 38f * density

                            val target = when {
                                wasSelectedAtDown && hypot(tx - relL, ty - relT) <= handleRadius -> DragTarget.DELETE_HANDLE
                                wasSelectedAtDown && hypot(tx - relR, ty - relT) <= handleRadius -> DragTarget.FLIP_HANDLE
                                wasSelectedAtDown && hypot(tx - relL, ty - relB) <= handleRadius -> DragTarget.ANIM_HANDLE
                                wasSelectedAtDown && hypot(tx - relR, ty - relB) <= scaleRadius -> DragTarget.SCALE
                                else -> DragTarget.MOVE_PIP
                            }

                            val isTapHandle = target == DragTarget.ANIM_HANDLE ||
                                target == DragTarget.DELETE_HANDLE ||
                                target == DragTarget.FLIP_HANDLE

                            var prevRootPos = touchBoxCoords?.localToRoot(down.position) ?: down.position
                            var prevRootCentroid = prevRootPos
                            var prevPointers = 1
                            var prevDistance = 0f
                            var totalHandleMove = 0f

                            if (!isTapHandle) {
                                isDraggingPip = true
                            }

                            do {
                                val event = awaitPointerEvent()
                                val pointers = event.changes.filter { it.pressed }
                                if (pointers.isEmpty()) break

                                if (isTapHandle) {
                                    val pointer = pointers[0]
                                    val curRoot = touchBoxCoords?.localToRoot(pointer.position) ?: pointer.position
                                    totalHandleMove += hypot(curRoot.x - prevRootPos.x, curRoot.y - prevRootPos.y)
                                    prevRootPos = curRoot
                                    pointer.consume()
                                } else if (pointers.size >= 2) {
                                    val rp1 = touchBoxCoords?.localToRoot(pointers[0].position) ?: pointers[0].position
                                    val rp2 = touchBoxCoords?.localToRoot(pointers[1].position) ?: pointers[1].position
                                    val curDist = hypot(rp1.x - rp2.x, rp1.y - rp2.y)
                                    val curCentroid = (rp1 + rp2) / 2f

                                    if (prevPointers >= 2 && prevDistance > 10f) {
                                        val factor = (curDist / prevDistance).coerceIn(0.80f, 1.25f)
                                        applyScaleKeepingCenter(liveScale * factor)

                                        val pan = curCentroid - prevRootCentroid
                                        if (w > 0f && h > 0f) {
                                            liveOffsetX = (liveOffsetX + pan.x / (w / 2f)).coerceIn(-12f, 12f)
                                            liveOffsetY = (liveOffsetY + pan.y / (h / 2f)).coerceIn(-12f, 12f)
                                        }
                                    }
                                    prevDistance = curDist
                                    prevRootCentroid = curCentroid
                                    prevPointers = 2
                                    pointers.forEach { it.consume() }
                                } else if (target != DragTarget.NONE) {
                                    val pointer = pointers[0]
                                    val curRoot = touchBoxCoords?.localToRoot(pointer.position) ?: pointer.position
                                    if (prevPointers == 1) {
                                        val dx = curRoot.x - prevRootPos.x
                                        val dy = curRoot.y - prevRootPos.y
                                        when (target) {
                                            DragTarget.MOVE_PIP -> {
                                                if (w > 0f && h > 0f) {
                                                    liveOffsetX = (liveOffsetX + dx / (w / 2f)).coerceIn(-12f, 12f)
                                                    liveOffsetY = (liveOffsetY + dy / (h / 2f)).coerceIn(-12f, 12f)
                                                }
                                            }
                                            DragTarget.SCALE -> {
                                                val (curPipW, curPipH) = pipDimsFor(liveScale)
                                                val halfW = (curPipW * (liveCrop.right - liveCrop.left)) / 2f
                                                val halfH = (curPipH * (liveCrop.bottom - liveCrop.top)) / 2f
                                                val diag = hypot(halfW, halfH).coerceAtLeast(16f)
                                                val proj = (dx * halfW + dy * halfH) / diag
                                                val factor = ((diag + proj) / diag).coerceIn(0.75f, 1.35f)
                                                applyScaleKeepingCenter(liveScale * factor)
                                            }
                                            else -> {}
                                        }
                                    }
                                    prevRootPos = curRoot
                                    prevRootCentroid = curRoot
                                    prevDistance = 0f
                                    prevPointers = 1
                                    pointer.consume()
                                }
                            } while (true)

                            isDraggingPip = false
                            when (target) {
                                DragTarget.DELETE_HANDLE -> if (totalHandleMove < 16f * density) {
                                    showAnimMenu = false
                                    onDelete(active.id)
                                }
                                DragTarget.FLIP_HANDLE -> if (totalHandleMove < 16f * density) {
                                    onFlip(active.id)
                                }
                                DragTarget.ANIM_HANDLE -> if (totalHandleMove < 16f * density) {
                                    showAnimMenu = !showAnimMenu
                                }
                                else -> {
                                    onMove(active.id, liveOffsetX, liveOffsetY)
                                    onScale(active.id, liveScale)
                                }
                            }
                        }
                    },
            )
        }

        if (isSelected && showAnimMenu) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(PipAnimation.entries) { anim ->
                            SelectableChip(
                                label = anim.label,
                                selected = active.pipAnimation == anim,
                                onClick = { onSetPipAnimation(active.id, anim) },
                            )
                        }
                    }
                    IconButton(
                        onClick = { showAnimMenu = false },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close animation list",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                val props = Keyframes.propsAt(clip, playheadMs - clip.timelineStartMs)
                val t = props.transform
                val glyph = clip.text?.text ?: ""
                val baseSp = clip.text?.fontSizeSp ?: 24f
                val isNewsBanner = clip.mediaUri?.contains("news_banner") == true

                val bannerSlideFrac = if (isNewsBanner) {
                    val elapsed = (playheadMs - clip.timelineStartMs).coerceAtLeast(0L)
                    val remaining = (clip.timelineEndMs - playheadMs).coerceAtLeast(0L)
                    val animMs = 300f
                    when {
                        elapsed < animMs -> 1f - (elapsed / animMs)
                        remaining < animMs -> 1f - (remaining / animMs)
                        else -> 0f
                    }.coerceIn(0f, 1f)
                } else 0f

                val gestureModifier = Modifier
                    .graphicsLayer {
                        translationX = (t.offsetX / 2f) * w
                        translationY = (t.offsetY / 2f) * h
                        rotationZ = t.rotationDegrees
                        alpha = props.opacity * (1f - bannerSlideFrac)
                    }

                val imageUri = clip.mediaUri
                if (imageUri != null && clip.text == null) {
                    if (isNewsBanner) {
                        val clipDuration = (clip.timelineEndMs - clip.timelineStartMs).coerceAtLeast(1L)
                        val progress = ((playheadMs - clip.timelineStartMs).toFloat() / clipDuration.toFloat())
                            .coerceIn(0f, 1f)

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(gestureModifier),
                        ) {
                            NewsBannerImage(
                                uri = imageUri,
                                progress = progress,
                                widthPx = w,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.52f)
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
private fun NewsBannerImage(
    uri: String,
    progress: Float,
    widthPx: Float,
    modifier: Modifier = Modifier,
) {
    val frameSet = produceState<BannerFrameSet?>(
        initialValue = null,
        key1 = uri,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val path = Uri.parse(uri).path ?: return@runCatching null
                val basePath = if (path.endsWith(".png", ignoreCase = true)) {
                    path.substring(0, path.length - 4)
                } else {
                    path
                }
                val metaFile = File("$basePath.meta")
                val fractions = if (metaFile.exists()) {
                    metaFile.readText()
                        .split(",")
                        .mapNotNull { it.trim().toFloatOrNull() }
                } else {
                    emptyList()
                }

                fun decodePreviewBitmap(file: File): ImageBitmap? {
                    if (!file.exists() || file.length() <= 0L) return null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val sample = if (bounds.outWidth > 1280) 2 else 1
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    return BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
                }

                if (fractions.size > 1) {
                    val bitmaps = fractions.indices.mapNotNull { idx ->
                        decodePreviewBitmap(File("${basePath}_$idx.png"))
                            ?: decodePreviewBitmap(File(path))
                    }
                    if (bitmaps.isNotEmpty()) {
                        BannerFrameSet(fractions = fractions, bitmaps = bitmaps)
                    } else null
                } else {
                    val single = decodePreviewBitmap(File(path)) ?: return@runCatching null
                    BannerFrameSet(fractions = listOf(1f), bitmaps = listOf(single))
                }
            }.getOrNull()
        }
    }.value ?: return

    val activeIdx = if (frameSet.fractions.size > 1 && frameSet.bitmaps.size == frameSet.fractions.size) {
        frameSet.fractions.indexOfFirst { progress <= it }.let {
            if (it < 0) frameSet.bitmaps.lastIndex else it
        }
    } else {
        0
    }
    val bitmap = frameSet.bitmaps.getOrNull(activeIdx) ?: frameSet.bitmaps.firstOrNull() ?: return
    val density = LocalDensity.current

    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .width(with(density) { widthPx.toDp() })
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
    )
}

@Composable
private fun StickerImage(uri: String, widthPx: Float, modifier: Modifier = Modifier) {
    val bitmap = produceState<ImageBitmap?>(
        initialValue = null,
        key1 = uri,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(Uri.parse(uri).path)?.asImageBitmap()
            }.getOrNull()
        }
    }.value ?: return
    val density = LocalDensity.current
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .width(with(density) { widthPx.toDp() })
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()),
    )
}

@Composable
private fun ScorecardLayer(
    allClips: List<Clip>,
    playheadMs: Long,
    onScorecardRowTapped: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeScoreClip = allClips.firstOrNull {
        it.scorecard != null && playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
    } ?: return
    val sc = activeScoreClip.scorecard ?: return
    val context = LocalContext.current
    val haptic = rememberHaptic()
    var prepTick by remember(sc.id, sc.themeIdx) { mutableIntStateOf(0) }
    LaunchedEffect(sc.id, sc.themeIdx) {
        ScorecardManager.ensurePrepared(context, sc)
        prepTick++
    }
    @Suppress("UNUSED_EXPRESSION")
    prepTick
    val currentPlayheadMs by rememberUpdatedState(playheadMs)
    val currentSc by rememberUpdatedState(sc)
    val currentClipStartMs by rememberUpdatedState(activeScoreClip.timelineStartMs)
    val currentOnRowTapped by rememberUpdatedState(onScorecardRowTapped)
    val timeInClip = (playheadMs - activeScoreClip.timelineStartMs).coerceAtLeast(0L)
    val activeBmp = ScorecardManager.getActiveBitmap(activeScoreClip.id, sc, timeInClip) ?: return

    Image(
        bitmap = activeBmp.asImageBitmap(),
        contentDescription = "Scorecard",
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .pointerInput(activeScoreClip.id) {
                val maxTapMovePx = 36.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastPos = down.position
                    var maxMove = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            lastPos = change.position
                            break
                        }
                        lastPos = change.position
                        maxMove = maxOf(maxMove, (lastPos - down.position).getDistance())
                    }
                    if (maxMove < maxTapMovePx && size.width > 0 && size.height > 0) {
                        val curTimeInClip = (currentPlayheadMs - currentClipStartMs).coerceAtLeast(0L)
                        val touchYFraction = (lastPos.y / size.height).coerceIn(0f, 1f)
                        val touchXFraction = (lastPos.x / size.width).coerceIn(0f, 1f)
                        val hitRow = ScorecardManager.hitTestRow(
                            activeScoreClip.id,
                            currentSc,
                            curTimeInClip,
                            touchYFraction,
                            touchXFraction,
                        )
                        if (hitRow >= 0) {
                            haptic.tick()
                            currentOnRowTapped(activeScoreClip.id, hitRow)
                        }
                    }
                }
            },
    )
}
