package com.actioncut.feature.editor.ui.pip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.actioncut.core.designsystem.component.clickableNoRipple
import com.actioncut.core.designsystem.component.rememberHaptic
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.CropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_CROP_FRAC = 0.10f

/**
 * Compact PiP Sub-Toolbar with visible rounded tool-icon tiles and slim height.
 */
@Composable
fun PipSelectedBar(
    onCrop: () -> Unit = {},
    onTrimRight: () -> Unit = {},
    onCutout: () -> Unit = {},
    onDelete: () -> Unit = {},
    onSettings: () -> Unit = {},
    onKeyframeToggle: () -> Unit = {},
    onClipEnd: () -> Unit = {},
    onVidEnd: () -> Unit = {},
    onFlip: () -> Unit = {},
    isKeyframeMode: Boolean = false,
    isCropActive: Boolean = false,
    isCutoutActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF141114),
        tonalElevation = 1.dp,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                PipSubToolItem(
                    label = "KF +/-",
                    icon = Icons.Filled.AutoAwesome,
                    selected = isKeyframeMode,
                    onClick = onKeyframeToggle,
                )
            }
            item {
                PipSubToolItem(
                    label = "VID END",
                    icon = Icons.Filled.FastForward,
                    onClick = onVidEnd,
                )
            }
            item {
                PipSubToolItem(
                    label = "CROP",
                    icon = Icons.Filled.Crop,
                    selected = isCropActive,
                    onClick = onCrop,
                )
            }
            item {
                PipSubToolItem(
                    label = "TRIM R",
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onClick = onTrimRight,
                )
            }
            item {
                PipSubToolItem(
                    label = "CUTOUT",
                    icon = Icons.Filled.Face,
                    selected = isCutoutActive,
                    onClick = onCutout,
                )
            }
            item {
                PipSubToolItem(
                    label = "DELETE",
                    icon = Icons.Filled.Delete,
                    onClick = onDelete,
                )
            }
            item {
                PipSubToolItem(
                    label = "SETTINGS",
                    icon = Icons.Filled.Settings,
                    onClick = onSettings,
                )
            }
        }
    }
}

@Composable
private fun PipSubToolItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val haptic = rememberHaptic()
    val tileColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF282226)
    val iconColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .clickableNoRipple { haptic.tick(); onClick() }
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tileColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Freeform Crop (Free Crop) mode on top of the selected clip/PiP.
 * Shows the full original image/video frame, 4 pink L-shaped corner handles,
 * dimmed outside area, 3x3 grid only while dragging, Reset (circular arrow),
 * and bottom bar (✕ cancel, Crop, ✓ apply) positioned comfortably above the nav bar.
 */
@Composable
fun FreeCropDialog(
    clip: Clip,
    playheadMs: Long = clip.timelineStartMs,
    onDismiss: () -> Unit,
    onApplyCrop: (CropRect) -> Unit,
) {
    val context = LocalContext.current
    val initialCrop = clip.crop ?: CropRect(0f, 0f, 1f, 1f)

    var leftRatio by remember(clip.id) { mutableFloatStateOf(initialCrop.left.coerceIn(0f, 1f - MIN_CROP_FRAC)) }
    var topRatio by remember(clip.id) { mutableFloatStateOf(initialCrop.top.coerceIn(0f, 1f - MIN_CROP_FRAC)) }
    var rightRatio by remember(clip.id) {
        mutableFloatStateOf(initialCrop.right.coerceIn(leftRatio + MIN_CROP_FRAC, 1f))
    }
    var bottomRatio by remember(clip.id) {
        mutableFloatStateOf(initialCrop.bottom.coerceIn(topRatio + MIN_CROP_FRAC, 1f))
    }

    var originalBitmap by remember(clip.id, clip.mediaUri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(clip.id, clip.mediaUri) { mutableStateOf(false) }

    LaunchedEffect(clip.id, clip.mediaUri) {
        val bmp = withContext(Dispatchers.IO) {
            loadOriginalFrameBitmap(context, clip, playheadMs)
        }
        if (bmp == null) failed = true else originalBitmap = bmp
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = maxOf(navInset + 24.dp, 84.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0E0E10),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = bottomSpace),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .onSizeChanged { containerSize = it },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = originalBitmap
                    when {
                        failed -> Text(
                            text = "Could not load clip frame.",
                            color = Color.White,
                        )
                        bmp == null -> CircularProgressIndicator(color = Color(0xFFFF2E5B))
                        else -> {
                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(bmp, containerSize) {
                                        var activeHandle: Int? = null
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                val r = fitRect(containerSize, bmp)
                                                if (r.width <= 0f || r.height <= 0f) {
                                                    activeHandle = null
                                                    return@detectDragGestures
                                                }
                                                val l = r.left + leftRatio * r.width
                                                val t = r.top + topRatio * r.height
                                                val ri = r.left + rightRatio * r.width
                                                val b = r.top + bottomRatio * r.height
                                                val cornerHit = 36.dp.toPx()
                                                val edgeHit = 22.dp.toPx()

                                                activeHandle = when {
                                                    (offset - Offset(l, t)).getDistance() <= cornerHit -> 0
                                                    (offset - Offset(ri, t)).getDistance() <= cornerHit -> 1
                                                    (offset - Offset(ri, b)).getDistance() <= cornerHit -> 2
                                                    (offset - Offset(l, b)).getDistance() <= cornerHit -> 3
                                                    offset.y in t..b && abs(offset.x - l) <= edgeHit -> 4
                                                    offset.y in t..b && abs(offset.x - ri) <= edgeHit -> 5
                                                    offset.x in l..ri && abs(offset.y - t) <= edgeHit -> 6
                                                    offset.x in l..ri && abs(offset.y - b) <= edgeHit -> 7
                                                    offset.x in l..ri && offset.y in t..b -> 8
                                                    else -> null
                                                }
                                                isDragging = activeHandle != null
                                            },
                                            onDrag = { change, dragAmount ->
                                                val r = fitRect(containerSize, bmp)
                                                if (r.width <= 0f || r.height <= 0f || activeHandle == null) {
                                                    return@detectDragGestures
                                                }
                                                change.consume()
                                                val dx = dragAmount.x / r.width
                                                val dy = dragAmount.y / r.height

                                                when (activeHandle) {
                                                    0 -> {
                                                        leftRatio = (leftRatio + dx).coerceIn(0f, rightRatio - MIN_CROP_FRAC)
                                                        topRatio = (topRatio + dy).coerceIn(0f, bottomRatio - MIN_CROP_FRAC)
                                                    }
                                                    1 -> {
                                                        rightRatio = (rightRatio + dx).coerceIn(leftRatio + MIN_CROP_FRAC, 1f)
                                                        topRatio = (topRatio + dy).coerceIn(0f, bottomRatio - MIN_CROP_FRAC)
                                                    }
                                                    2 -> {
                                                        rightRatio = (rightRatio + dx).coerceIn(leftRatio + MIN_CROP_FRAC, 1f)
                                                        bottomRatio = (bottomRatio + dy).coerceIn(topRatio + MIN_CROP_FRAC, 1f)
                                                    }
                                                    3 -> {
                                                        leftRatio = (leftRatio + dx).coerceIn(0f, rightRatio - MIN_CROP_FRAC)
                                                        bottomRatio = (bottomRatio + dy).coerceIn(topRatio + MIN_CROP_FRAC, 1f)
                                                    }
                                                    4 -> {
                                                        leftRatio = (leftRatio + dx).coerceIn(0f, rightRatio - MIN_CROP_FRAC)
                                                    }
                                                    5 -> {
                                                        rightRatio = (rightRatio + dx).coerceIn(leftRatio + MIN_CROP_FRAC, 1f)
                                                    }
                                                    6 -> {
                                                        topRatio = (topRatio + dy).coerceIn(0f, bottomRatio - MIN_CROP_FRAC)
                                                    }
                                                    7 -> {
                                                        bottomRatio = (bottomRatio + dy).coerceIn(topRatio + MIN_CROP_FRAC, 1f)
                                                    }
                                                    8 -> {
                                                        val boxW = rightRatio - leftRatio
                                                        val boxH = bottomRatio - topRatio
                                                        val newLeft = (leftRatio + dx).coerceIn(0f, 1f - boxW)
                                                        val newTop = (topRatio + dy).coerceIn(0f, 1f - boxH)
                                                        leftRatio = newLeft
                                                        rightRatio = newLeft + boxW
                                                        topRatio = newTop
                                                        bottomRatio = newTop + boxH
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                activeHandle = null
                                                isDragging = false
                                            },
                                            onDragCancel = {
                                                activeHandle = null
                                                isDragging = false
                                            },
                                        )
                                    },
                            ) {
                                val r = fitRect(containerSize, bmp)
                                if (r.width <= 0f || r.height <= 0f) return@Canvas

                                drawImage(
                                    image = imageBitmap,
                                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                                    dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                                )

                                val l = r.left + leftRatio * r.width
                                val t = r.top + topRatio * r.height
                                val ri = r.left + rightRatio * r.width
                                val b = r.top + bottomRatio * r.height
                                val rectW = ri - l
                                val rectH = b - t

                                // Dim outside the crop box
                                drawPath(
                                    path = Path().apply {
                                        addRect(Rect(r.left, r.top, r.right, r.bottom))
                                        addRect(Rect(l, t, ri, b))
                                        fillType = PathFillType.EvenOdd
                                    },
                                    color = Color.Black.copy(alpha = 0.65f),
                                )

                                // Thin white frame
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset(l, t),
                                    size = Size(rectW, rectH),
                                    style = Stroke(width = 1.5.dp.toPx()),
                                )

                                // 3x3 grid only while dragging
                                if (isDragging) {
                                    val gridColor = Color.White.copy(alpha = 0.6f)
                                    val gridW = 1.dp.toPx()
                                    drawLine(gridColor, Offset(l + rectW / 3f, t), Offset(l + rectW / 3f, b), gridW)
                                    drawLine(gridColor, Offset(l + 2f * rectW / 3f, t), Offset(l + 2f * rectW / 3f, b), gridW)
                                    drawLine(gridColor, Offset(l, t + rectH / 3f), Offset(ri, t + rectH / 3f), gridW)
                                    drawLine(gridColor, Offset(l, t + 2f * rectH / 3f), Offset(ri, t + 2f * rectH / 3f), gridW)
                                }

                                // 4 pink L-shaped corner handles
                                val pink = Color(0xFFFF2E5B)
                                val arm = minOf(22.dp.toPx(), rectW * 0.35f, rectH * 0.35f)
                                val strokePx = 4.dp.toPx()

                                // Top-left L
                                drawLine(pink, Offset(l, t), Offset(l + arm, t), strokePx, StrokeCap.Square)
                                drawLine(pink, Offset(l, t), Offset(l, t + arm), strokePx, StrokeCap.Square)
                                // Top-right L
                                drawLine(pink, Offset(ri - arm, t), Offset(ri, t), strokePx, StrokeCap.Square)
                                drawLine(pink, Offset(ri, t), Offset(ri, t + arm), strokePx, StrokeCap.Square)
                                // Bottom-left L
                                drawLine(pink, Offset(l, b), Offset(l + arm, b), strokePx, StrokeCap.Square)
                                drawLine(pink, Offset(l, b - arm), Offset(l, b), strokePx, StrokeCap.Square)
                                // Bottom-right L
                                drawLine(pink, Offset(ri - arm, b), Offset(ri, b), strokePx, StrokeCap.Square)
                                drawLine(pink, Offset(ri, b - arm), Offset(ri, b), strokePx, StrokeCap.Square)
                            }
                        }
                    }
                }

                // Reset (circular arrow icon) row above bottom bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            leftRatio = 0f
                            topRatio = 0f
                            rightRatio = 1f
                            bottomRatio = 1f
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reset crop",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }

                // Bottom bar: ✕ cancel, Crop title, ✓ apply
                Surface(
                    color = Color(0xFF16161A),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Text(
                            text = "Crop",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(
                            enabled = originalBitmap != null,
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
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Apply crop",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fitRect(size: IntSize, bitmap: Bitmap): Rect {
    if (size.width <= 0 || size.height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return Rect.Zero
    val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private fun loadOriginalFrameBitmap(context: Context, clip: Clip, playheadMs: Long): Bitmap? {
    val uriStr = clip.mediaUri ?: return null
    val uri = Uri.parse(uriStr)

    if (clip.type == ClipType.VIDEO) {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            val timeInClipMs = (playheadMs - clip.timelineStartMs).coerceAtLeast(0L)
            val sourceTimeMs = (clip.sourceInMs + (timeInClipMs * clip.speed).toLong()).coerceAtLeast(0L)
            val frame = retriever.getFrameAtTime(sourceTimeMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime()
                ?: return@runCatching null
            downscaleIfNeeded(frame, 1920)
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, bounds)
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 1920) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }

        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, opts) else null
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }.getOrNull()
}

private fun downscaleIfNeeded(src: Bitmap, maxLongEdge: Int): Bitmap {
    val longest = max(src.width, src.height)
    if (longest <= maxLongEdge) return src
    val scale = maxLongEdge.toFloat() / longest.toFloat()
    val w = (src.width * scale).roundToInt().coerceAtLeast(1)
    val h = (src.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, h, true).also {
        if (it !== src) src.recycle()
    }
}
