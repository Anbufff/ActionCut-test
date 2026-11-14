package com.actioncut.feature.editor.ui.pip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-device human cutout (ML Kit Selfie Segmentation + Studio Backdrop Flood-Fill).
 * Ensures small Cricbuzz gray-box portraits (~150px) also get a clean transparent cutout.
 */
object AiCutoutManager {
    private val mutex = Mutex()

    private fun cutoutFile(context: Context, imageUriStr: String): File =
        File(context.cacheDir, "cutout_${imageUriStr.hashCode()}.png")

    suspend fun getOrCreateCutoutUri(
        context: Context,
        imageUriStr: String,
        forceRefresh: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val outFile = cutoutFile(context, imageUriStr)
        if (!forceRefresh && outFile.exists() && outFile.length() > 0L) {
            return@withContext Uri.fromFile(outFile).toString()
        }

        mutex.withLock {
            if (!forceRefresh && outFile.exists() && outFile.length() > 0L) {
                return@withLock Uri.fromFile(outFile).toString()
            }

            val decoded = decodeUriToBitmap(context, imageUriStr) ?: return@withLock null
            // Upscale tiny Cricbuzz headshots (<512px) so ML Kit segmentation has crisp edges
            val workingBmp = ensureMinimumSize(decoded, 512)
            if (workingBmp !== decoded) decoded.recycle()

            val cutoutBmp = try {
                val mlResult = runSelfieSegmentation(workingBmp)
                if (mlResult != null) {
                    // Also strip any leftover uniform gray/white studio box around outer edges
                    stripUniformEdgeBackdrop(mlResult)
                    mlResult
                } else {
                    val copy = workingBmp.copy(Bitmap.Config.ARGB_8888, true)
                    stripUniformEdgeBackdrop(copy)
                    copy
                }
            } finally {
                workingBmp.recycle()
            }

            val tmpFile = File(context.cacheDir, "cutout_${imageUriStr.hashCode()}.png.tmp")
            val saved = runCatching {
                FileOutputStream(tmpFile).use { out ->
                    cutoutBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }.getOrDefault(false)
            cutoutBmp.recycle()

            if (!saved || !tmpFile.renameTo(outFile)) {
                tmpFile.delete()
                return@withLock null
            }
            Uri.fromFile(outFile).toString()
        }
    }

    private fun ensureMinimumSize(src: Bitmap, minLongEdge: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest >= minLongEdge || longest <= 0) return src
        val scale = minLongEdge.toFloat() / longest.toFloat()
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private suspend fun runSelfieSegmentation(sourceBmp: Bitmap): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            try {
                val options = SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build()
                val segmenter = Segmentation.getClient(options)
                continuation.invokeOnCancellation { runCatching { segmenter.close() } }

                val inputImage = InputImage.fromBitmap(sourceBmp, 0)

                segmenter.process(inputImage)
                    .addOnSuccessListener { mask ->
                        var result: Bitmap? = null
                        try {
                            val maskBuffer: ByteBuffer = mask.buffer
                            val maskWidth = mask.width
                            val maskHeight = mask.height

                            val scaledSrc =
                                if (sourceBmp.width != maskWidth || sourceBmp.height != maskHeight) {
                                    Bitmap.createScaledBitmap(sourceBmp, maskWidth, maskHeight, true)
                                } else sourceBmp

                            val pixels = IntArray(maskWidth * maskHeight)
                            scaledSrc.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
                            if (scaledSrc !== sourceBmp) scaledSrc.recycle()

                            maskBuffer.rewind()
                            for (i in pixels.indices) {
                                val confidence = maskBuffer.float
                                if (confidence < 0.50f) {
                                    pixels[i] = Color.TRANSPARENT
                                } else if (confidence < 0.85f) {
                                    val alpha = ((confidence - 0.50f) / 0.35f * 255).toInt().coerceIn(0, 255)
                                    val orig = pixels[i]
                                    pixels[i] = Color.argb(
                                        alpha,
                                        Color.red(orig),
                                        Color.green(orig),
                                        Color.blue(orig),
                                    )
                                }
                            }
                            val bmp = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                            bmp.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
                            result = bmp
                        } catch (_: Throwable) {
                            result = null
                        } finally {
                            runCatching { segmenter.close() }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                    .addOnFailureListener {
                        runCatching { segmenter.close() }
                        if (continuation.isActive) continuation.resume(null)
                    }
            } catch (_: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /**
     * Flood-fills inward from outer borders to remove uniform studio gray/white backdrops
     * (such as Cricbuzz headshots) when ML Kit leaves a box around the portrait.
     */
    private fun stripUniformEdgeBackdrop(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        if (w < 12 || h < 12) return

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val corners = intArrayOf(
            pixels[2 * w + 2],
            pixels[2 * w + (w - 3)],
        )
        val seed = corners.firstOrNull { Color.alpha(it) > 200 } ?: return
        val sr = Color.red(seed)
        val sg = Color.green(seed)
        val sb = Color.blue(seed)

        // Only run if top-left / top-right corners look like a uniform backdrop
        val c2 = pixels[2 * w + (w - 3)]
        if (Color.alpha(c2) > 200) {
            val diff = abs(Color.red(c2) - sr) + abs(Color.green(c2) - sg) + abs(Color.blue(c2) - sb)
            if (diff > 55) return
        }

        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun matchesBackdrop(px: Int): Boolean {
            val a = Color.alpha(px)
            if (a < 30) return true
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            val dist = abs(r - sr) + abs(g - sg) + abs(b - sb)
            return dist <= 52
        }

        fun enqueue(idx: Int) {
            if (!visited[idx] && matchesBackdrop(pixels[idx])) {
                visited[idx] = true
                queue[tail++] = idx
            }
        }

        for (x in 0 until w) {
            enqueue(x)
            enqueue((h - 1) * w + x)
        }
        for (y in 0 until h) {
            enqueue(y * w)
            enqueue(y * w + (w - 1))
        }

        while (head < tail) {
            val idx = queue[head++]
            pixels[idx] = Color.TRANSPARENT
            val x = idx % w
            val y = idx / w
            if (x > 0) enqueue(idx - 1)
            if (x < w - 1) enqueue(idx + 1)
            if (y > 0) enqueue(idx - w)
            if (y < h - 1) enqueue(idx + w)
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun decodeUriToBitmap(context: Context, uriStr: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriStr)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        var sampleSize = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sampleSize > 1280) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    }.getOrNull()
}
