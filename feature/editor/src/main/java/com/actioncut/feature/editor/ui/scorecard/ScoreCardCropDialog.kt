package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.actioncut.core.designsystem.component.SelectableChip
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_SPAN = 0.03f

/**
 * Full-screen "Cricket Score Card" sheet: shows the picked scoreboard screenshot with a
 * green START line and a red END line you drag to keep only the rows you need, then renders
 * the broadcast-style card (see [ScoreCardRenderer]) to a PNG in app storage.
 *
 * [onDone] receives a `file://` URI of the PNG and the card's width/height aspect ratio.
 */
@Composable
fun ScoreCardCropDialog(
    sourceUri: String,
    onDismiss: () -> Unit,
    onDone: (uri: String, aspect: Float) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(ScoreCardType.BATTING) }
    var startF by remember { mutableFloatStateOf(0.05f) }
    var endF by remember { mutableFloatStateOf(0.95f) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri) {
        val bmp = withContext(Dispatchers.IO) { decodeBitmap(context, Uri.parse(sourceUri)) }
        if (bmp == null) failed = true else bitmap = bmp
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Cricket Score Card", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScoreCardType.values().forEach { t ->
                        SelectableChip(label = t.label, selected = type == t, onClick = { type = t })
                    }
                }
                Text(
                    "Drag the green START line and red END line to keep only the rows you need.",
                    color = Color(0xFFBBBBBB),
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val bmp = bitmap
                    when {
                        failed -> Text("Could not open that image.", color = Color.White, modifier = Modifier.align(Alignment.Center))
                        bmp == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        else -> CropCanvas(
                            bitmap = bmp,
                            startF = startF,
                            endF = endF,
                            onChange = { s, e -> startF = s; endF = e },
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        enabled = bitmap != null && !busy,
                        onClick = {
                            val src = bitmap ?: return@Button
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    runCatching {
                                        val card = ScoreCardRenderer.render(src, startF, endF, type)
                                        val dir = File(context.filesDir, "scorecards").apply { mkdirs() }
                                        val file = File(dir, "scorecard_${System.currentTimeMillis()}.png")
                                        FileOutputStream(file).use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                        Pair(Uri.fromFile(file).toString(), card.width.toFloat() / card.height.toFloat())
                                    }
                                }
                                busy = false
                                result.onSuccess { (uri, aspect) -> onDone(uri, aspect) }
                                result.onFailure {
                                    Toast.makeText(context, "Score card failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    ) { Text(if (busy) "Working..." else "Add to video") }
                }
            }
        }
    }
}

@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    startF: Float,
    endF: Float,
    onChange: (Float, Float) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val curStart by rememberUpdatedState(startF)
    val curEnd by rememberUpdatedState(endF)
    val curOnChange by rememberUpdatedState(onChange)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(canvasSize, bitmap) {
                var dragging = 0 // 1 = start line, 2 = end line
                detectDragGestures(
                    onDragStart = { offset ->
                        val r = fitRect(canvasSize, bitmap)
                        val ys = r.top + curStart * r.height
                        val ye = r.top + curEnd * r.height
                        dragging = if (abs(offset.y - ys) <= abs(offset.y - ye)) 1 else 2
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val r = fitRect(canvasSize, bitmap)
                        if (r.height > 0f) {
                            val f = ((change.position.y - r.top) / r.height).coerceIn(0f, 1f)
                            if (dragging == 1) {
                                curOnChange(min(f, curEnd - MIN_SPAN).coerceAtLeast(0f), curEnd)
                            } else if (dragging == 2) {
                                curOnChange(curStart, max(f, curStart + MIN_SPAN).coerceAtMost(1f))
                            }
                        }
                    },
                    onDragEnd = { dragging = 0 },
                    onDragCancel = { dragging = 0 },
                )
            },
    ) {
        val r = fitRect(canvasSize, bitmap)
        if (r.width <= 0f || r.height <= 0f) return@Canvas
        drawImage(
            image = image,
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
        )
        val ys = r.top + startF * r.height
        val ye = r.top + endF * r.height
        val dim = Color(0xAA000000)
        drawRect(dim, topLeft = Offset(r.left, r.top), size = Size(r.width, ys - r.top))
        drawRect(dim, topLeft = Offset(r.left, ye), size = Size(r.width, r.bottom - ye))
        val green = Color(0xFF4CAF50)
        val red = Color(0xFFF44336)
        drawLine(green, Offset(r.left, ys), Offset(r.right, ys), strokeWidth = 5f)
        drawLine(red, Offset(r.left, ye), Offset(r.right, ye), strokeWidth = 5f)
        drawCircle(green, radius = 16f, center = Offset(r.right - 28f, ys))
        drawCircle(red, radius = 16f, center = Offset(r.right - 28f, ye))
    }
}

/** The rectangle the bitmap occupies when fitted (contain) inside [size]. */
private fun fitRect(size: IntSize, bitmap: Bitmap): Rect {
    if (size.width <= 0 || size.height <= 0) return Rect.Zero
    val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private fun decodeBitmap(context: android.content.Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > 2400) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()
