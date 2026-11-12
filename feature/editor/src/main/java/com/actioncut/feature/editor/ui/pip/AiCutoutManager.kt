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

/**
 * On-device human cutout (ML Kit Selfie Segmentation).
 *
 * IMPORTANT: the output file name `cutout_<uri.hashCode()>.png` is also computed by
 * PreviewPlayer.kt and Media3VideoExporter.kt, so do NOT change [cutoutFile]'s naming.
 *
 * Improvements over the previous version:
 *  - Result is found on disk even after the app restarts (old code re-ran the AI every launch).
 *  - Only one segmentation runs at a time (ViewModel + PreviewPlayer both trigger it).
 *  - File is written to a temp file and renamed, so the exporter / preview can never read a
 *    half-written PNG.
 *  - Segmenter is closed if the coroutine is cancelled; bitmaps are recycled.
 */
object AiCutoutManager {
    private val mutex = Mutex()

    private fun cutoutFile(context: Context, imageUriStr: String): File =
        File(context.cacheDir, "cutout_${imageUriStr.hashCode()}.png")

    suspend fun getOrCreateCutoutUri(context: Context, imageUriStr: String): String? =
        withContext(Dispatchers.IO) {
            val outFile = cutoutFile(context, imageUriStr)
            if (outFile.exists() && outFile.length() > 0L) {
                return@withContext Uri.fromFile(outFile).toString()
            }

            mutex.withLock {
                // Another caller may have finished while we waited for the lock.
                if (outFile.exists() && outFile.length() > 0L) {
                    return@withLock Uri.fromFile(outFile).toString()
                }

                val originalBmp = decodeUriToBitmap(context, imageUriStr)
                    ?: return@withLock null

                val cutoutBmp = try {
                    runSelfieSegmentation(originalBmp)
                } finally {
                    originalBmp.recycle()
                } ?: return@withLock null

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
                                if (confidence < 0.40f) {
                                    pixels[i] = Color.TRANSPARENT
                                } else if (confidence < 0.85f) {
                                    val alpha = ((confidence - 0.40f) / 0.45f * 255).toInt().coerceIn(0, 255)
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
                        } catch (t: Throwable) {
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
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** Decodes the image, down-sampled to at most ~1280px to avoid OutOfMemory. */
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
