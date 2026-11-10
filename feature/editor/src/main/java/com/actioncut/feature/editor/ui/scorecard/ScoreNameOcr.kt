package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Reads the player-name line of a scoreboard row with on-device ML Kit text recognition. */
object ScoreNameOcr {

    /** Crops the name area (left 62%, top 52% of the row) from [source] and returns the text. */
    suspend fun readName(source: Bitmap, row: RowInfo): String {
        val y0 = row.srcTop.coerceIn(0, source.height - 1)
        val h = ((row.srcBottom - row.srcTop) * 0.52f).toInt().coerceIn(1, source.height - y0)
        val w = (source.width * 0.62f).toInt().coerceIn(1, source.width)
        val crop = runCatching { Bitmap.createBitmap(source, 0, y0, w, h) }.getOrNull() ?: return ""
        return recognize(crop)
    }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                if (cont.isActive) cont.resume(result.text)
            }
            .addOnFailureListener {
                recognizer.close()
                if (cont.isActive) cont.resume("")
            }
    }
}
