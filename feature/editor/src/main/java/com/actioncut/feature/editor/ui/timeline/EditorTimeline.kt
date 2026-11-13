package com.actioncut.feature.editor.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.actioncut.core.designsystem.component.clickableNoRipple
import com.actioncut.core.designsystem.theme.LaneAudio
import com.actioncut.core.designsystem.theme.LaneText
import com.actioncut.core.designsystem.theme.LaneVideo
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.Track
import com.actioncut.core.model.TrackType
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TRACK_HEIGHT = 52
private const val RULER_HEIGHT = 22
private const val MIN_ZOOM_PX_PER_SEC = 24f
private const val MAX_ZOOM_PX_PER_SEC = 320f

@Composable
fun EditorTimeline(
    timeline: Timeline,
    playheadMs: Long,
    durationMs: Long,
    pxPerSecond: Float,
    isPlaying: Boolean,
    selectedClipId: String?,
    onScrub: (Long) -> Unit,
    onSelectClip: (String?) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimCommit: () -> Unit = {},
    onMoveClip: (String, Long) -> Unit = { _, _ -> },
    onMoveCommit: () -> Unit = {},
    onLoadWaveform: (suspend (String) -> FloatArray)? = null,
    onAddMedia: () -> Unit = {},
    onPause: () -> Unit = {},
    onScrubStart: () -> Unit = {},
    onScrubEnd: (Long) -> Unit = {},
    onZoomChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    val extraPaddingMs = with(density) { (90.dp.toPx() / pxPerSecond * 1000f).toLong() }
    val layoutDurationMs = (durationMs + extraPaddingMs).coerceAtLeast(3_000L)

    var isPointerDown by remember { mutableStateOf(false) }
    var isPinching by remember { mutableStateOf(false) }
    var pinchAnchorMs by remember { mutableLongStateOf(0L) }
    var wasScrubbing by remember { mutableStateOf(false) }
    var lastScrubbedMs by remember { mutableLongStateOf(-1L) }

    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val currentPxPerSecond by rememberUpdatedState(pxPerSecond)
    val currentPlayheadMs by rememberUpdatedState(playheadMs)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnPause by rememberUpdatedState(onPause)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)

    fun msToPxF(ms: Long): Float = ms / 1000f * pxPerSecond
    fun pxToMs(px: Int): Long = (px / currentPxPerSecond * 1000f).roundToLong()

    LaunchedEffect(scrollState) {
        snapshotFlow {
            Triple(scrollState.value, scrollState.isScrollInProgress, isPointerDown)
        }.collect { (value, inProgress, pointerDown) ->
            if (isPinching) return@collect
            val active = inProgress || pointerDown
            if (active && currentIsPlaying) {
                currentOnPause()
            }
            if (active && !wasScrubbing) {
                wasScrubbing = true
                currentOnScrubStart()
            }
            if (inProgress) {
                val targetMs = pxToMs(value).coerceIn(0L, currentDurationMs)
                lastScrubbedMs = targetMs
                currentOnScrub(targetMs)
            }
            if (!active && wasScrubbing) {
                wasScrubbing = false
                val finalMs = pxToMs(value).coerceIn(0L, currentDurationMs)
                lastScrubbedMs = finalMs
                currentOnScrubEnd(finalMs)
            }
        }
    }

    LaunchedEffect(pxPerSecond) {
        lastScrubbedMs = -1L
        if (isPinching) {
            val targetPx = (pinchAnchorMs / 1000f * pxPerSecond).roundToInt()
            scrollState.scrollTo(targetPx)
        }
    }

    LaunchedEffect(playheadMs, isPlaying, pxPerSecond) {
        if (!scrollState.isScrollInProgress && !isPointerDown && !isPinching) {
            if (isPlaying || playheadMs != lastScrubbedMs) {
                val target = msToPxF(playheadMs).roundToInt()
                if (abs(scrollState.value - target) > 1) {
                    scrollState.scrollTo(target)
                }
            }
        }
    }

    val displayTracks = remember(timeline, selectedClipId) {
        val videoTrack = timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
        val audioTrack = timeline.tracks.firstOrNull { it.type == TrackType.AUDIO }
        val overlayTrack = timeline.tracks.firstOrNull { it.type == TrackType.OVERLAY }
        val isOverlaySelected = overlayTrack?.clips?.any { it.id == selectedClipId } == true

        buildList {
            if (videoTrack != null) add(videoTrack)
            if (isOverlaySelected && overlayTrack != null) {
                add(overlayTrack)
            } else if (audioTrack != null) {
                add(audioTrack)
            } else if (overlayTrack != null && overlayTrack.clips.isNotEmpty()) {
                add(overlayTrack)
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (currentIsPlaying) {
                        currentOnPause()
                    }
                    isPointerDown = true
                    var prevPinchDist = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                if (!isPinching) {
                                    isPinching = true
                                    pinchAnchorMs = currentPlayheadMs
                                    wasScrubbing = false
                                }
                                val p1 = pressed[0].position
                                val p2 = pressed[1].position
                                val dist = hypot(p1.x - p2.x, p1.y - p2.y)
                                if (prevPinchDist > 12f && dist > 12f) {
                                    val factor = (dist / prevPinchDist).coerceIn(0.85f, 1.20f)
                                    if (abs(factor - 1f) > 0.002f) {
                                        val nextPx = (currentPxPerSecond * factor).coerceIn(
                                            MIN_ZOOM_PX_PER_SEC,
                                            MAX_ZOOM_PX_PER_SEC,
                                        )
                                        currentOnZoomChange(nextPx)
                                        prevPinchDist = dist
                                    }
                                } else {
                                    prevPinchDist = dist
                                }
                                pressed.forEach { it.consume() }
                            } else {
                                prevPinchDist = 0f
                                if (isPinching) {
                                    pressed.forEach { it.consume() }
                                }
                            }
                        }
                    } finally {
                        isPointerDown = false
                        isPinching = false
                    }
                }
            },
    ) {
        val viewportPx = constraints.maxWidth
        val halfViewportDp = with(density) { (viewportPx / 2).toDp() }
        val contentWidthDp = with(density) { msToPxF(layoutDurationMs).toDp() }

        Row(modifier = Modifier.horizontalScroll(scrollState)) {
            Spacer(Modifier.width(halfViewportDp))
            Column {
                TimeRuler(layoutDurationMs, pxPerSecond, contentWidthDp)

                if (displayTracks.isEmpty()) {
                    Spacer(Modifier.width(contentWidthDp).height(TRACK_HEIGHT.dp))
                }

                displayTracks.forEach { track ->
                    TrackLane(
                        track = track,
                        pxPerSecond = pxPerSecond,
                        contentWidthDp = contentWidthDp,
                        selectedClipId = selectedClipId,
                        onSelectClip = onSelectClip,
                        onTrimStart = onTrimStart,
                        onTrimEnd = onTrimEnd,
                        onTrimCommit = onTrimCommit,
                        onMoveClip = onMoveClip,
                        onMoveCommit = onMoveCommit,
                        onLoadWaveform = onLoadWaveform,
                        onAddMedia = onAddMedia,
                        playheadMsProvider = { currentPlayheadMs },
                    )
                }
            }
            Spacer(Modifier.width(halfViewportDp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun TimeRuler(durationMs: Long, pxPerSecond: Float, widthDp: androidx.compose.ui.unit.Dp) {
    val totalSeconds = (durationMs / 1000).toInt()
    val step = when {
        pxPerSecond >= 120 -> 1
        pxPerSecond >= 60 -> 2
        else -> 5
    }
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(RULER_HEIGHT.dp),
    ) {
        val density = LocalDensity.current
        for (second in 0..totalSeconds step step) {
            val xDp = with(density) { (second * pxPerSecond).toDp() }
            Text(
                text = "${second}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset(x = xDp).padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun TrackLane(
    track: Track,
    pxPerSecond: Float,
    contentWidthDp: androidx.compose.ui.unit.Dp,
    selectedClipId: String?,
    onSelectClip: (String?) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimCommit: () -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onMoveCommit: () -> Unit,
    onLoadWaveform: (suspend (String) -> FloatArray)?,
    onAddMedia: () -> Unit,
    playheadMsProvider: () -> Long,
) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(contentWidthDp)
            .height(TRACK_HEIGHT.dp)
            .padding(vertical = 3.dp),
    ) {
        track.clips.forEach { clip ->
            val xDp = with(density) { (clip.timelineStartMs / 1000f * pxPerSecond).toDp() }
            Box(modifier = Modifier.offset(x = xDp).fillMaxHeight()) {
                ClipBlock(
                    clip = clip,
                    trackType = track.type,
                    pxPerSecond = pxPerSecond,
                    selected = clip.id == selectedClipId,
                    onSelect = { onSelectClip(clip.id) },
                    onTrimStart = onTrimStart,
                    onTrimEnd = onTrimEnd,
                    onTrimCommit = onTrimCommit,
                    onMoveClip = onMoveClip,
                    onMoveCommit = onMoveCommit,
                    onLoadWaveform = onLoadWaveform,
                    playheadMsProvider = playheadMsProvider,
                )
            }
        }

        if (track.type == TrackType.VIDEO) {
            val addBtnOffsetDp = with(density) { (track.durationMs / 1000f * pxPerSecond).toDp() + 14.dp }
            Box(
                modifier = Modifier
                    .offset(x = addBtnOffsetDp)
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222222))
                    .drawBehind {
                        val stroke = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                        )
                        drawRoundRect(
                            color = Color(0xFFFFCC00),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = stroke,
                        )
                    }
                    .clickable { onAddMedia() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add Media",
                    tint = Color(0xFFFFCC00),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun ClipBlock(
    clip: Clip,
    trackType: TrackType,
    pxPerSecond: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimCommit: () -> Unit,
    onMoveClip: (String, Long) -> Unit = { _, _ -> },
    onMoveCommit: () -> Unit = {},
    onLoadWaveform: (suspend (String) -> FloatArray)? = null,
    playheadMsProvider: () -> Long = { 0L },
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val widthDp = with(density) { (clip.timelineDurationMs / 1000f * pxPerSecond).toDp() }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent

    val currentClipStartMs by rememberUpdatedState(clip.timelineStartMs)
    val currentPxPerSecond by rememberUpdatedState(pxPerSecond)
    val currentOnMoveClip by rememberUpdatedState(onMoveClip)
    val currentOnMoveCommit by rememberUpdatedState(onMoveCommit)

    var moveAnchor by remember(clip.id) { mutableLongStateOf(clip.timelineStartMs) }
    var moveAcc by remember(clip.id) { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .background(laneColor(clip, trackType))
            .border(2.dp, borderColor, RoundedCornerShape(6.dp))
            .clickableNoRipple { onSelect() }
            .pointerInput(clip.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        onSelect()
                        moveAnchor = currentClipStartMs
                        moveAcc = 0f
                    },
                    onDrag = { _, drag ->
                        moveAcc += drag.x
                        val deltaMs = (moveAcc / currentPxPerSecond * 1000f).toLong()
                        currentOnMoveClip(clip.id, moveAnchor + deltaMs)
                    },
                    onDragEnd = { currentOnMoveCommit() },
                    onDragCancel = { currentOnMoveCommit() },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if ((clip.type == ClipType.IMAGE || clip.type == ClipType.VIDEO) &&
            clip.mediaUri != null &&
            clip.scorecard == null &&
            clip.mediaUri?.contains("news_banner") != true
        ) {
            val thumbRequest = remember(clip.id, clip.mediaUri, clip.type) {
                ImageRequest.Builder(context)
                    .data(clip.mediaUri)
                    .size(240, 120)
                    .apply {
                        if (clip.type == ClipType.VIDEO) {
                            videoFrameMillis(500L)
                            decoderFactory(VideoFrameDecoder.Factory())
                        }
                    }
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = thumbRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.65f),
            )
        }

        if (clip.type == ClipType.AUDIO && clip.mediaUri != null && onLoadWaveform != null) {
            AudioWaveform(
                uri = clip.mediaUri!!,
                loader = onLoadWaveform,
                modifier = Modifier.matchParentSize(),
            )
        }

        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = clipIcon(clip),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = clipLabel(clip, trackType),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val hasOverlayKfs = trackType == TrackType.OVERLAY && clip.keyframes.isNotEmpty()
        val scKfs = clip.scorecard?.keyframes
        if (hasOverlayKfs || !scKfs.isNullOrEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val curPlayhead = playheadMsProvider()
                val pxPerMs = pxPerSecond / 1000f
                val cy = size.height / 2f

                if (hasOverlayKfs) {
                    val side = 11.dp.toPx()
                    clip.keyframes.forEach { kf ->
                        val cx = kf.timeMs * pxPerMs
                        val isCurrent = abs(curPlayhead - (clip.timelineStartMs + kf.timeMs)) <= 150L
                        val color = if (isCurrent) Color(0xFFFF3B5C) else Color(0xFFFFD700)
                        drawRotatedDiamond(cx, cy, side, color)
                    }
                }

                if (!scKfs.isNullOrEmpty()) {
                    val side = 10.dp.toPx()
                    scKfs.forEach { kf ->
                        val cx = kf.timeMs * pxPerMs
                        val isCurrent = abs(curPlayhead - (clip.timelineStartMs + kf.timeMs)) <= 200L
                        val color = if (isCurrent) Color(0xFFFF3B5C) else Color(0xFFFFE300)
                        drawRotatedDiamond(cx, cy, side, color)
                    }
                }
            }
        }

        if (selected) {
            ClipTrimHandles(
                clip = clip,
                pxPerSecond = pxPerSecond,
                onTrimStart = onTrimStart,
                onTrimEnd = onTrimEnd,
                onTrimCommit = onTrimCommit,
            )
        }
    }
}

private fun DrawScope.drawRotatedDiamond(cx: Float, cy: Float, sidePx: Float, color: Color) {
    val half = sidePx / 2f
    val topLeft = Offset(cx - half, cy - half)
    val boxSize = Size(sidePx, sidePx)
    rotate(degrees = 45f, pivot = Offset(cx, cy)) {
        drawRect(color = color, topLeft = topLeft, size = boxSize)
        drawRect(color = Color.Black, topLeft = topLeft, size = boxSize, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun AudioWaveform(
    uri: String,
    loader: suspend (String) -> FloatArray,
    modifier: Modifier = Modifier,
) {
    var amplitudes by remember(uri) { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(uri) {
        amplitudes = runCatching { loader(uri) }.getOrNull() ?: FloatArray(0)
    }
    val waveColor = Color.White.copy(alpha = 0.38f)
    Canvas(modifier = modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
        val amps = amplitudes
        if (amps.isEmpty()) return@Canvas
        val barWidth = size.width / amps.size
        val midY = size.height / 2f
        for (i in amps.indices) {
            val barHeight = (amps[i] * size.height).coerceAtLeast(2f)
            val x = i * barWidth + barWidth / 2f
            drawLine(
                color = waveColor,
                start = Offset(x, midY - barHeight / 2f),
                end = Offset(x, midY + barHeight / 2f),
                strokeWidth = (barWidth * 0.55f).coerceAtLeast(1f),
            )
        }
    }
}

@Composable
private fun ClipTrimHandles(
    clip: Clip,
    pxPerSecond: Float,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimCommit: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        DragHandle(
            modifier = Modifier.align(Alignment.CenterStart),
            isLeft = true,
            clipId = clip.id,
            getInitialMs = { clip.timelineStartMs },
            pxPerSecond = pxPerSecond,
            onValue = { onTrimStart(clip.id, it) },
            onCommit = onTrimCommit,
        )
        DragHandle(
            modifier = Modifier.align(Alignment.CenterEnd),
            isLeft = false,
            clipId = clip.id,
            getInitialMs = { clip.timelineEndMs },
            pxPerSecond = pxPerSecond,
            onValue = { onTrimEnd(clip.id, it) },
            onCommit = onTrimCommit,
        )
    }
}

@Composable
private fun DragHandle(
    modifier: Modifier,
    isLeft: Boolean,
    clipId: String,
    getInitialMs: () -> Long,
    pxPerSecond: Float,
    onValue: (Long) -> Unit,
    onCommit: () -> Unit,
) {
    val currentGetInitialMs by rememberUpdatedState(getInitialMs)
    val currentOnValue by rememberUpdatedState(onValue)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentPxPerSecond by rememberUpdatedState(pxPerSecond)

    var anchorMs by remember(clipId) { mutableLongStateOf(0L) }
    var accPx by remember(clipId) { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .width(36.dp)
            .fillMaxHeight()
            .pointerInput(clipId) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        anchorMs = currentGetInitialMs()
                        accPx = 0f
                    },
                    onHorizontalDrag = { _, delta ->
                        accPx += delta
                        val deltaMs = (accPx / currentPxPerSecond * 1000f).toLong()
                        currentOnValue(anchorMs + deltaMs)
                    },
                    onDragEnd = { currentOnCommit() },
                    onDragCancel = { currentOnCommit() },
                )
            },
        contentAlignment = if (isLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .fillMaxHeight()
                .clip(
                    if (isLeft) RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                    else RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp),
                )
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 22.dp)
                    .background(Color.White, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private fun laneColor(clip: Clip, trackType: TrackType): Color {
    if (clip.mediaUri?.contains("news_banner") == true) {
        return Color(0xFF8E0614)
    }
    return when (clip.type) {
        ClipType.AUDIO -> LaneAudio
        ClipType.TEXT -> LaneText
        ClipType.STICKER -> Color(0xFF7B1FA2)
        ClipType.VIDEO, ClipType.IMAGE -> {
            if (trackType == TrackType.VIDEO) LaneVideo else Color(0xFF7B1FA2)
        }
    }
}

private fun clipIcon(clip: Clip) = when {
    clip.mediaUri?.contains("news_banner") == true -> Icons.Filled.Campaign
    clip.type == ClipType.AUDIO -> Icons.Filled.Audiotrack
    clip.type == ClipType.TEXT -> Icons.Filled.TextFields
    clip.type == ClipType.VIDEO -> Icons.Filled.Movie
    else -> Icons.Filled.Image
}

private fun clipLabel(clip: Clip, trackType: TrackType): String = when {
    clip.mediaUri?.contains("news_banner") == true -> "NEWS ALERT"
    clip.type == ClipType.TEXT -> clip.text?.text ?: "Text"
    clip.type == ClipType.AUDIO -> "Audio"
    clip.type == ClipType.VIDEO -> if (trackType == TrackType.VIDEO) "Video" else "PiP Video"
    else -> if (trackType == TrackType.VIDEO) "Photo" else "PiP Photo"
}
