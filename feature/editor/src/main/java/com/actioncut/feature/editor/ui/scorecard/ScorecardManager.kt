package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import com.actioncut.core.model.ScorecardClipData
import com.actioncut.core.model.ScorecardKeyframe
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ScorecardManager {

    private val cardCache = mutableMapOf<String, MutableMap<Int, Bitmap>>()
    private val rowRectsCache = mutableMapOf<String, List<RectF>>()

    suspend fun prepareCard(
        context: Context,
        data: ScorecardClipData,
        library: PlayerLibrary
    ): String = withContext(Dispatchers.Default) {
        val srcBmp = runCatching {
            val stream = context.contentResolver.openInputStream(Uri.parse(data.sourceImageUri))
            BitmapFactory.decodeStream(stream)
        }.getOrNull() ?: return@withContext ""

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
            if (i == 0 || !rowRectsCache.containsKey(data.id)) {
                rowRectsCache[data.id] = composed.rowRects
            }
        }
        cardCache[data.id] = rowMap

        val initialRow = data.keyframes.firstOrNull()?.rowIndex ?: 0
        val baseBmp = rowMap[initialRow] ?: rowMap[0]
        val outFile = File(context.cacheDir, "card_${data.id}.png")
        if (baseBmp != null) {
            FileOutputStream(outFile).use { fos ->
                baseBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        }
        Uri.fromFile(outFile).toString()
    }

    fun getActiveRowIndex(data: ScorecardClipData, timeInClipMs: Long): Int {
        val sorted = data.keyframes.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return 0
        val activeKf = sorted.lastOrNull { it.timeMs <= timeInClipMs } ?: sorted.first()
        return activeKf.rowIndex
    }

    fun getActiveBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        return cardCache[clipId]?.get(activeRow) ?: cardCache[clipId]?.values?.firstOrNull()
    }

    fun hitTestRow(clipId: String, touchYFraction: Float): Int {
        val rects = rowRectsCache[clipId] ?: return -1
        val yInCanvas = touchYFraction * ScoreCardComposer.CANVAS_H
        return rects.indexOfFirst { rect ->
            yInCanvas >= rect.top && yInCanvas <= rect.bottom
        }
    }

    fun clearCache(clipId: String) {
        cardCache.remove(clipId)
        rowRectsCache.remove(clipId)
    }
}
