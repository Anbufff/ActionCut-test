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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.actioncut.core.model.TransitionType
import com.actioncut.feature.editor.ui.scorecard.ScorecardManager

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
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
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

            // 2. Main Track Image (Ken Burns Auto-Zoom)
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

                val transformGestureModifier = Modifier.pointerInput(currentMainClip.id, w, h) {
                    var currentScale = mainTransform.scale
                    var currentOx = mainTransform.offsetX
                    var currentOy = mainTransform.offsetY

                    detectTransformGestures { _, pan, zoom, _ ->
                        currentScale = (currentScale * zoom).coerceIn(0.5f, 3.5f)
                        currentOx = (currentOx + pan.x / (w / 2f)).coerceIn(-2.0f, 2.0f)
                        currentOy = (currentOy + pan.y / (h / 2f)).coerceIn(-2.0f, 2.0f)

                        onSelectOverlay(currentMainClip.id)
                        onScaleOverlay(currentMainClip.id, currentScale)
                        onMoveOverlay(currentMainClip.id, currentOx, currentOy)
                    }
                }

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
                        .then(transformGestureModifier)
                        .then(
                            if (isMainSelected) {
                                Modifier.border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.8f))
                            } else {
                                Modifier
                            },
                        ),
                )
            }

            // 3. Fty-style PiP Layer (வீடியோ & போட்டோ இரண்டையுமே இயக்கும்படி இணைக்கப்பட்டது)
            if (pipPlayer != null) {
                PipLayer(
                    pipPlayer = pipPlayer,
                    pipClips = pipClips,
                    playheadMs = playheadMs,
                    selectedClipId = selectedClipId,
                    onSelect = onSelectOverlay,
                    onMove = onMoveOverlay,
                    onScale = onScaleOverlay,
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
 * Fty Video Editor ஸ்கிரீன்ஷாட்டில் உள்ளபடியே PiP பாக்ஸ், சியான் டாஷ்ட் பார்டர், Cutout மற்றும் Free Crop
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
    modifier: Modifier = Modifier,
) {
    val active = pipClips.firstOrNull {
        playheadMs >= it.timelineStartMs && playheadMs < it.timelineEndMs
    } ?: return

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val props = com.actioncut.core.model.Keyframes.propsAt(active, playheadMs - active.timelineStartMs)
        val t = props.transform
        val density = LocalContext.current.resources.displayMetrics.density
        val pipW = (w * t.scale) / density
        val pipH = (h * t.scale) / density
        val isSelected = active.id == selectedClipId
        val cyanColor = Color(0xFF00E5FF)

        // சேஃப்-கால் மூலம் ஸ்மார்ட்-காஸ்ட் பிரச்சனை இன்றி Circle Cutout கண்டறிதல்
        val activeCrop = active.crop
        val isCircleCutout = active.filter?.id == "cutout_circle" ||
            (activeCrop?.left == 0.12f && activeCrop?.top == 0.08f)
        val cutoutShape = if (isCircleCutout) CircleShape else RectangleShape

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
                .then(
                    if (isSelected) {
                        Modifier.drawBehind {
                            val stroke = Stroke(
                                width = 2.5f * density,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                            )
                            if (isCircleCutout) {
                                drawCircle(color = cyanColor, style = stroke)
                            } else {
                                drawRoundRect(
                                    color = cyanColor,
                                    cornerRadius = CornerRadius(4f * density),
                                    style = stroke,
                                )
                            }
                        }
                    } else {
                        Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), cutoutShape)
                    }
                )
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
            // Free Crop & Cutout Shape Clamping
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val crop = activeCrop
                        if (crop != null && !crop.isFull) {
                            val cLeft = crop.left
                            val cTop = crop.top
                            val cRight = crop.right
                            val cBottom = crop.bottom
                            val clipRect = Rect(
                                left = size.width * cLeft,
                                top = size.height * cTop,
                                right = size.width * cRight,
                                bottom = size.height * cBottom,
                            )
                            drawContext.canvas.save()
                            drawContext.canvas.clipRect(clipRect)
                            drawContent()
                            drawContext.canvas.restore()
                        } else {
                            drawContent()
                        }
                    }
                    .clip(cutoutShape),
            ) {
                if (active.type == ClipType.VIDEO) {
                    AndroidView(
                        factory = { context ->
                            PlayerView(context).apply {
                                useController = false
                                this.player = pipPlayer
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { it.player = pipPlayer },
                        modifier = Modifier.fillMaxSize().clip(cutoutShape),
                    )
                } else {
                    AsyncImage(
                        model = active.mediaUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(cutoutShape),
                    )
                }
            }

            // Fty ஸ்கிரீன்ஷாட்டில் உள்ளபடியே தேர்வு செய்யப்பட்டால் தோன்றும் 4 பக்க வட்டங்கள் & ஸ்கேலிங் கைப்பிடி
            if (isSelected) {
                Box(modifier = Modifier.align(Alignment.TopCenter).size(12.dp).clip(CircleShape).background(cyanColor))
                Box(modifier = Modifier.align(Alignment.BottomCenter).size(12.dp).clip(CircleShape).background(cyanColor))
                Box(modifier = Modifier.align(Alignment.CenterStart).size(12.dp).clip(CircleShape).background(cyanColor))
                Box(modifier = Modifier.align(Alignment.CenterEnd).size(12.dp).clip(CircleShape).background(cyanColor))

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, cyanColor, CircleShape)
                        .pointerInput(active.id, w) {
                            var s = t.scale
                            detectDragGestures(
                                onDragStart = { onSelect(active.id); s = active.transform.scale },
                                onDrag = { change, drag ->
                                    change.consume()
                                    s = (s + drag.x / w).coerceIn(0.15f, 1.8f)
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
