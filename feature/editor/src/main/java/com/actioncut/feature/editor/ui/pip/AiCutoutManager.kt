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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

object AiCutoutManager {
    private val cutoutCache = mutableMapOf<String, String>()

    suspend fun getOrCreateCutoutUri(context: Context, imageUriStr: String): String? = withContext(Dispatchers.IO) {
        if (cutoutCache.containsKey(imageUriStr)) {
            val cachedPath = cutoutCache[imageUriStr]
            if (cachedPath != null && File(cachedPath).exists()) {
                return@withContext Uri.fromFile(File(cachedPath)).toString()
            }
        }

        val originalBmp = decodeUriToBitmap(context, imageUriStr) ?: return@withContext null

        val cutoutBmp = runSelfieSegmentation(originalBmp) ?: originalBmp

        val outFile = File(context.cacheDir, "cutout_${imageUriStr.hashCode()}.png")
        FileOutputStream(outFile).use { out ->
            cutoutBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        cutoutCache[imageUriStr] = outFile.absolutePath
        return@withContext Uri.fromFile(outFile).toString()
    }

    private suspend fun runSelfieSegmentation(sourceBmp: Bitmap): Bitmap? = suspendCancellableCoroutine { continuation ->
        try {
            // கூகுள் ML Kit-ன் சரியான மெத்தட்: setDetectorMode
            val options = SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
            val segmenter = Segmentation.getClient(options)
            val inputImage = InputImage.fromBitmap(sourceBmp, 0)

            segmenter.process(inputImage)
                .addOnSuccessListener { mask ->
                    val maskBuffer: ByteBuffer = mask.buffer
                    val maskWidth = mask.width
                    val maskHeight = mask.height

                    val resultBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(maskWidth * maskHeight)
                    val scaledSrc = if (sourceBmp.width != maskWidth || sourceBmp.height != maskHeight) {
                        Bitmap.createScaledBitmap(sourceBmp, maskWidth, maskHeight, true)
                    } else sourceBmp

                    scaledSrc.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

                    maskBuffer.rewind()
                    for (i in pixels.indices) {
                        val confidence = maskBuffer.float
                        if (confidence < 0.45f) {
                            pixels[i] = Color.TRANSPARENT
                        } else if (confidence < 0.85f) {
                            val alpha = ((confidence - 0.45f) / 0.4f * 255).toInt().coerceIn(0, 255)
                            val origColor = pixels[i]
                            pixels[i] = Color.argb(
                                alpha,
                                Color.red(origColor),
                                Color.green(origColor),
                                Color.blue(origColor),
                            )
                        }
                    }
                    resultBitmap.setPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
                    segmenter.close()
                    if (continuation.isActive) continuation.resume(resultBitmap)
                }
                .addOnFailureListener {
                    segmenter.close()
                    if (continuation.isActive) continuation.resume(null)
                }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun decodeUriToBitmap(context: Context, uriStr: String): Bitmap? = runCatching {
        val uri = Uri.parse(uriStr)
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    }.getOrNull()
}
