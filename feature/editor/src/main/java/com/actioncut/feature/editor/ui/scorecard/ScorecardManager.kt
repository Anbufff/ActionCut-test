package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import com.actioncut.core.model.ScorecardClipData
import com.actioncut.core.model.ScorecardKeyframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ScorecardManager {

    // clipId -> (rowIndex -> Composed Bitmap)
    private val cardCache = mutableMapOf<String, MutableMap<Int, Bitmap>>()
    // clipId -> list of row hit-test rectangles
    private val rowRectsCache = mutableMapOf<String, List<RectF>>()

    /**
     * ஸ்கோர்கார்டின் அனைத்து வரிகளுக்குமான படங்களை ஒரே நேரத்தில் தயாரித்து Cache-ல் வைக்கும்.
     */
    suspend fun prepareCard(
        context: Context,
        data: ScorecardClipData,
        library: PlayerLibrary
    ) = withContext(Dispatchers.Default) {
        val srcBmp = runCatching {
            val stream = context.contentResolver.openInputStream(Uri.parse(data.sourceImageUri))
            BitmapFactory.decodeStream(stream)
        }.getOrNull() ?: return@withContext

        val scoreType = if (data.type == "BOWLING") ScoreCardType.BOWLING else ScoreCardType.BATTING
        val rendered = ScoreCardRenderer.renderWithRows(srcBmp, data.cropStartF, data.cropEndF, scoreType)

        val rowMap = mutableMapOf<Int, Bitmap>()
        val allPlayers = library.all()

        for (i in data.rows.indices) {
            val row = data.rows[i]
            val player = allPlayers.find { it.name.equals(row.matchedPlayerName, ignoreCase = true) }
            val photoBmp = player?.let { library.photo(it) }

            val composed = ScoreCardComposer.compose(
                card = rendered,
                type = scoreType,
                selected = i,
                player = player,
                photo = photoBmp,
                fallbackName = row.fallbackName
            )
            rowMap[i] = composed.bitmap
            if (i == 0) {
                rowRectsCache[data.id] = composed.rowRects
            }
        }
        cardCache[data.id] = rowMap
    }

    /**
     * குறிப்பிட்ட வினாடியில் (timeInClipMs) எந்த வரி ஆக்டிவாக இருக்க வேண்டும் எனக் கணக்கிடும்.
     */
    fun getActiveRowIndex(data: ScorecardClipData, timeInClipMs: Long): Int {
        val sorted = data.keyframes.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return 0
        val activeKf = sorted.lastOrNull { it.timeMs <= timeInClipMs } ?: sorted.first()
        return activeKf.rowIndex
    }

    /**
     * ப்ளேஹெட் இருக்கும் நேரத்திற்குரிய சரியான பிட்மேப்பை வழங்கும் (Live Preview & Export).
     */
    fun getActiveBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        return cardCache[clipId]?.get(activeRow)
    }

    /**
     * ஸ்கிரீனில் தொடும் போது (Tap) எந்த வரியைத் தொட்டுள்ளார்கள் என்பதைக் கண்டறியும்.
     */
    fun hitTestRow(clipId: String, touchYFraction: Float): Int {
        val rects = rowRectsCache[clipId] ?: return -1
        val firstRect = rects.firstOrNull() ?: return -1
        val lastRect = rects.lastOrNull() ?: return -1
        val totalH = lastRect.bottom - firstRect.top
        if (totalH <= 0f) return -1

        val yInCard = firstRect.top + (touchYFraction * totalH)
        return rects.indexOfFirst { yInCard >= it.top && yInCard <= it.bottom }
    }

    fun clearCache(clipId: String) {
        cardCache.remove(clipId)?.values?.forEach { it.recycle() }
        rowRectsCache.remove(clipId)
    }
}
