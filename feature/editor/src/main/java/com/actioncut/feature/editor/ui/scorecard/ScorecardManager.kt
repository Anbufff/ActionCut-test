package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.RectF
import android.net.Uri
import com.actioncut.core.media.export.ScorecardBridge
import com.actioncut.core.model.ScorecardClipData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ScorecardManager {

    private const val MAX_CACHED_CARDS = 3

    private val cardCache = object : LinkedHashMap<String, MutableMap<Int, Bitmap>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, Bitmap>>?): Boolean {
            return size > MAX_CACHED_CARDS
        }
    }
    private val rowRectsCache = object : LinkedHashMap<String, MutableMap<Int, List<RectF>>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, List<RectF>>>?): Boolean {
            return size > MAX_CACHED_CARDS
        }
    }

    @Volatile
    private var appContext: Context? = null
    private val prepLock = Any()

    // Preview மற்றும் Export இரண்டும் தனித்தனி Ping-Pong Bitmaps பயன்படுத்தும் (0% flicker / black frame, Media3 always uploads fresh frame)
    private val previewFrameLock = Any()
    private val previewFrames = arrayOfNulls<Bitmap>(2)
    private var previewFrameIdx = 0

    private val exportFrameLock = Any()
    private val exportFrames = arrayOfNulls<Bitmap>(2)
    private var exportFrameIdx = 0

    init {
        ScorecardBridge.bitmapProvider = { clipId, data, timeInClipMs ->
            getExportBitmap(clipId, data, timeInClipMs)
        }
    }

    private fun cacheKey(data: ScorecardClipData): String =
        "${data.id}_${ScoreCardComposer.normTheme(data.themeIdx)}"

    suspend fun ensurePrepared(context: Context, data: ScorecardClipData) {
        appContext = context.applicationContext
        val key = cacheKey(data)
        val hasCache = synchronized(prepLock) { cardCache.containsKey(key) }
        if (!hasCache) {
            prepareCard(context.applicationContext, data, PlayerLibrary(context.applicationContext))
        }
    }

    suspend fun prepareCard(
        context: Context,
        data: ScorecardClipData,
        library: PlayerLibrary
    ): String = withContext(Dispatchers.Default) {
        appContext = context.applicationContext
        val isCdnVector = data.sourceImageUri.endsWith(".json", ignoreCase = true)
        val rowMap = mutableMapOf<Int, Bitmap>()
        val rectsMap = mutableMapOf<Int, List<RectF>>()

        if (isCdnVector) {
            val inningsIdx = data.cropStartF.toInt().coerceAtLeast(0)
            val inn = CdnVectorScoreCardComposer.loadInnings(context, data.sourceImageUri, inningsIdx)
                ?: return@withContext ""
            val scoreType = if (data.type == "BOWLING") ScoreCardType.BOWLING else ScoreCardType.BATTING
            for (i in data.rows.indices) {
                val composed = CdnVectorScoreCardComposer.composeFrame(
                    context = context,
                    innings = inn,
                    type = scoreType,
                    selected = i,
                    library = library,
                    themeIdx = data.themeIdx,
                )
                rowMap[i] = composed.bitmap
                rectsMap[i] = composed.rowRects
            }
        } else {
            val srcBmp = runCatching {
                val stream = context.contentResolver.openInputStream(Uri.parse(data.sourceImageUri))
                BitmapFactory.decodeStream(stream)
            }.getOrNull() ?: return@withContext ""

            val scoreType = if (data.type == "BOWLING") ScoreCardType.BOWLING else ScoreCardType.BATTING
            val rendered = ScoreCardRenderer.renderWithRows(srcBmp, data.cropStartF, data.cropEndF, scoreType, data.themeIdx)
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
                    fallbackName = row.fallbackName,
                    themeIdx = data.themeIdx
                )
                rowMap[i] = composed.bitmap
                rectsMap[i] = composed.rowRects
            }
        }

        val key = cacheKey(data)
        synchronized(prepLock) {
            cardCache[key] = rowMap
            rowRectsCache[key] = rectsMap
        }

        val initialRow = data.keyframes.firstOrNull()?.rowIndex ?: 0
        val baseBmp = rowMap[initialRow] ?: rowMap[0]
        val outFile = File(context.cacheDir, "card_${data.id}.png")
        if (baseBmp != null) {
            val previewOut = Bitmap.createBitmap(baseBmp.width, baseBmp.height, Bitmap.Config.ARGB_8888)
            FileOutputStream(outFile).use { fos ->
                previewOut.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            previewOut.recycle()
        }
        Uri.fromFile(outFile).toString()
    }

    private fun getOrRebuildEntry(data: ScorecardClipData): Pair<Bitmap, RectF?>? {
        val key = cacheKey(data)
        var baseMap: Map<Int, Bitmap>?
        var rectsMap: Map<Int, List<RectF>>?
        synchronized(prepLock) {
            baseMap = cardCache[key]
            rectsMap = rowRectsCache[key]
        }
        if (baseMap == null) {
            val ctx = appContext
            if (ctx != null) {
                runCatching {
                    runBlocking { prepareCard(ctx, data, PlayerLibrary(ctx)) }
                }
                synchronized(prepLock) {
                    baseMap = cardCache[key]
                    rectsMap = rowRectsCache[key]
                }
            }
        }
        val map = baseMap ?: return null
        val activeRow = getActiveRowIndex(data, 0L)
        val b = map[activeRow] ?: map.values.firstOrNull() ?: return null
        return b to rectsMap?.get(activeRow)?.getOrNull(activeRow)
    }

    fun getActiveRowIndex(data: ScorecardClipData, timeInClipMs: Long): Int {
        val sorted = data.keyframes.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return 0
        val activeKf = sorted.lastOrNull { it.timeMs <= timeInClipMs } ?: sorted.first()
        return activeKf.rowIndex
    }

    private fun resolveRowBitmapAndRect(data: ScorecardClipData, timeInClipMs: Long): Pair<Bitmap, RectF?>? {
        val key = cacheKey(data)
        var baseMap: Map<Int, Bitmap>?
        var rectsMap: Map<Int, List<RectF>>?
        synchronized(prepLock) {
            baseMap = cardCache[key]
            rectsMap = rowRectsCache[key]
        }
        if (baseMap == null) {
            val ctx = appContext
            if (ctx != null) {
                runCatching {
                    runBlocking { prepareCard(ctx, data, PlayerLibrary(ctx)) }
                }
                synchronized(prepLock) {
                    baseMap = cardCache[key]
                    rectsMap = rowRectsCache[key]
                }
            }
        }
        val map = baseMap ?: return null
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        val baseBmp = map[activeRow] ?: map.values.firstOrNull() ?: return null
        if (baseBmp.isRecycled) return null
        val targetRect = rectsMap?.get(activeRow)?.getOrNull(activeRow)
        return baseBmp to targetRect
    }

    fun getActiveBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        val (baseBmp, targetRect) = resolveRowBitmapAndRect(data, timeInClipMs) ?: return null
        val isCdnVector = data.sourceImageUri.endsWith(".json", ignoreCase = true)
        synchronized(previewFrameLock) {
            val idx = previewFrameIdx
            previewFrameIdx = (previewFrameIdx + 1) and 1
            var out = previewFrames[idx]
            if (out == null || out.isRecycled) {
                out = Bitmap.createBitmap(
                    ScoreCardComposer.CANVAS_W.toInt(),
                    ScoreCardComposer.CANVAS_H.toInt(),
                    Bitmap.Config.ARGB_8888
                )
                previewFrames[idx] = out
            }
            val canvas = Canvas(out)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(baseBmp, 0f, 0f, null)
            if (targetRect != null) {
                ScoreCardComposer.drawBouncingArrow(
                    canvas,
                    targetRect,
                    timeInClipMs,
                    data.themeIdx,
                    compact = isCdnVector,
                )
            }
            return out
        }
    }

    fun getExportBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        val (baseBmp, targetRect) = resolveRowBitmapAndRect(data, timeInClipMs) ?: return null
        val isCdnVector = data.sourceImageUri.endsWith(".json", ignoreCase = true)
        synchronized(exportFrameLock) {
            val idx = exportFrameIdx
            exportFrameIdx = (exportFrameIdx + 1) and 1
            var out = exportFrames[idx]
            if (out == null || out.isRecycled) {
                out = Bitmap.createBitmap(
                    ScoreCardComposer.CANVAS_W.toInt(),
                    ScoreCardComposer.CANVAS_H.toInt(),
                    Bitmap.Config.ARGB_8888
                )
                exportFrames[idx] = out
            }
            val canvas = Canvas(out)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(baseBmp, 0f, 0f, null)
            if (targetRect != null) {
                ScoreCardComposer.drawBouncingArrow(
                    canvas,
                    targetRect,
                    timeInClipMs,
                    data.themeIdx,
                    compact = isCdnVector,
                )
            }
            return out
        }
    }

    fun hitTestRow(
        clipId: String,
        data: ScorecardClipData,
        timeInClipMs: Long,
        touchYFraction: Float,
        touchXFraction: Float
    ): Int {
        if (touchXFraction > 0.52f) return -1
        val key = cacheKey(data)
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        val rects = synchronized(prepLock) { rowRectsCache[key]?.get(activeRow) } ?: return -1
        val yInCanvas = touchYFraction * ScoreCardComposer.CANVAS_H

        return rects.indexOfFirst { rect ->
            yInCanvas >= (rect.top - 5f) && yInCanvas <= (rect.bottom + 5f)
        }
    }

    fun clearCache(clipId: String) {
        synchronized(prepLock) {
            val keys = cardCache.keys.filter { it.startsWith("${clipId}_") || it == clipId }
            keys.forEach {
                cardCache.remove(it)
                rowRectsCache.remove(it)
            }
        }
    }
}
