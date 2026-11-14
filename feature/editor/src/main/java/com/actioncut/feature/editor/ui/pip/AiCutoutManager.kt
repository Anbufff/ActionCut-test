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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device human cutout (ML Kit Selfie Segmentation + Shaded Studio Backdrop Remover + Bottom Fade).
 * Cleanly removes shaded gray Cricbuzz studio boxes without eating white collars, skin, or jerseys.
 */
object AiCutoutManager {
    private val mutex = Mutex()

    private fun cutoutFile(context: Context, imageUriStr: String): File =
        File(context.cacheDir, "cutout_${imageUriStr.hashCode()}.png")

    suspend fun getOrCreateCutoutUri(
        context: Context,
        imageUriStr: String,
        forceRefresh: Boolean = false,
        softBottomFade: Boolean = false,
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
            val workingBmp = ensureMinimumSize(decoded, 512)
            if (workingBmp !== decoded) decoded.recycle()

            val cutoutBmp = try {
                val mlResult = runSelfieSegmentation(workingBmp)
                val baseCutout = if (mlResult != null) {
                    stripShadedStudioBackdrop(mlResult, workingBmp)
                    mlResult
                } else {
                    val copy = workingBmp.copy(Bitmap.Config.ARGB_8888, true)
                    stripShadedStudioBackdrop(copy, workingBmp)
                    copy
                }
                if (softBottomFade) {
                    applyBottomFade(baseCutout, fadeFraction = 0.14f)
                }
                baseCutout
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
                                if (confidence < 0.52f) {
                                    pixels[i] = Color.TRANSPARENT
                                } else if (confidence < 0.86f) {
                                    val alpha = ((confidence - 0.52f) / 0.34f * 255).toInt().coerceIn(0, 255)
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
     * Removes shaded studio gray/white backgrounds (such as Cricbuzz portraits where the left
     * side is #DFDFDF and the right side is #B6B6B6) using neighbor-adaptive flood-fill from
     * top and side outer edges, while protecting human skin, hair, and bright white shirts.
     */
    private fun stripShadedStudioBackdrop(bmp: Bitmap, originalSource: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val src = if (originalSource.width == w && originalSource.height == h) {
            originalSource
        } else {
            Bitmap.createScaledBitmap(originalSource, w, h, true)
        }
        val origPixels = IntArray(w * h)
        src.getPixels(origPixels, 0, w, 0, 0, w, h)
        if (src !== originalSource) src.recycle()

        fun isNeutralBackdrop(px: Int, allowWhite: Boolean): Boolean {
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            val spread = maxOf(r, g, b) - minOf(r, g, b)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val maxLum = if (allowWhite) 252 else 230
            return spread <= 24 && lum in 95..maxLum
        }

        val tl = origPixels[2 * w + 2]
        val tr = origPixels[2 * w + (w - 3)]
        val tlWhite = ((Color.red(tl) + Color.green(tl) + Color.blue(tl)) / 3) >= 234
        val trWhite = ((Color.red(tr) + Color.green(tr) + Color.blue(tr)) / 3) >= 234
        val allowWhite = tlWhite && trWhite

        if (!isNeutralBackdrop(tl, allowWhite = true) && !isNeutralBackdrop(tr, allowWhite = true)) {
            return
        }

        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0

        fun colorDist(p1: Int, p2: Int): Int =
            abs(Color.red(p1) - Color.red(p2)) +
                abs(Color.green(p1) - Color.green(p2)) +
                abs(Color.blue(p1) - Color.blue(p2))

        fun seedAt(idx: Int) {
            if (visited[idx]) return
            val op = origPixels[idx]
            if (Color.alpha(pixels[idx]) < 25 || isNeutralBackdrop(op, allowWhite = true)) {
                visited[idx] = true
                queue[tail++] = idx
            }
        }

        // Seed from top edge and upper 75% of left/right edges (avoids shirt at bottom corners)
        for (x in 0 until w) {
            seedAt(x)
        }
        val sideLimit = (h * 0.78f).toInt().coerceAtLeast(1)
        for (y in 0 until sideLimit) {
            seedAt(y * w)
            seedAt(y * w + (w - 1))
        }

        while (head < tail) {
            val idx = queue[head++]
            val curOrig = origPixels[idx]
            pixels[idx] = Color.TRANSPARENT

            val x = idx % w
            val y = idx / w

            fun tryNeighbor(nIdx: Int) {
                if (visited[nIdx]) return
                val np = pixels[nIdx]
                if (Color.alpha(np) < 25) {
                    visited[nIdx] = true
                    queue[tail++] = nIdx
                    return
                }
                val no = origPixels[nIdx]
                if (isNeutralBackdrop(no, allowWhite) && colorDist(curOrig, no) <= 34) {
                    visited[nIdx] = true
                    queue[tail++] = nIdx
                }
            }

            if (x > 0) tryNeighbor(idx - 1)
            if (x < w - 1) tryNeighbor(idx + 1)
            if (y > 0) tryNeighbor(idx - w)
            if (y < h - 1) tryNeighbor(idx + w)
        }

        // Smooth 1px anti-aliased fringe next to erased background pixels
        val copy = pixels.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val px = copy[idx]
                if (Color.alpha(px) > 80) {
                    val hasTransparentNeighbor =
                        Color.alpha(copy[idx - 1]) == 0 ||
                            Color.alpha(copy[idx + 1]) == 0 ||
                            Color.alpha(copy[idx - w]) == 0 ||
                            Color.alpha(copy[idx + w]) == 0
                    if (hasTransparentNeighbor && isNeutralBackdrop(origPixels[idx], allowWhite)) {
                        pixels[idx] = Color.argb(
                            (Color.alpha(px) * 0.35f).toInt(),
                            Color.red(px),
                            Color.green(px),
                            Color.blue(px),
                        )
                    }
                }
            }
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** Softly fades the bottom edge of the portrait so it blends smoothly into the stadium grass. */
    private fun applyBottomFade(bmp: Bitmap, fadeFraction: Float) {
        val w = bmp.width
        val h = bmp.height
        val fadeRows = (h * fadeFraction).toInt().coerceAtLeast(1)
        val startY = h - fadeRows
        val row = IntArray(w)
        for (y in startY until h) {
            val factor = (1f - (y - startY).toFloat() / fadeRows.toFloat()).coerceIn(0f, 1f)
            val smooth = factor * factor
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val a = Color.alpha(p)
                if (a > 0) {
                    val na = (a * smooth).toInt().coerceIn(0, 255)
                    row[x] = (na shl 24) or (p and 0x00FFFFFF)
                }
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
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
