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
import com.actioncut.core.model.ScorecardKeyframe
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ScorecardManager {

    private val cardCache = mutableMapOf<String, MutableMap<Int, Bitmap>>()
    private val rowRectsCache = mutableMapOf<String, MutableMap<Int, List<RectF>>>()

    // லேக் இல்லாமல் அதியுயர் வேகத்தில் அனிமேஷன் பிரேம்களை உருவாக்க உதவும் ரீயூசபிள் கேன்வாஸ்
    private val dynamicFrameLock = Any()
    private var dynamicFrameBitmap: Bitmap? = null
    private var dynamicFrameCanvas: Canvas? = null

    init {
        ScorecardBridge.bitmapProvider = { clipId, data, timeInClipMs ->
            getActiveBitmap(clipId, data, timeInClipMs)
        }
    }

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
        val rendered = ScoreCardRenderer.renderWithRows(srcBmp, data.cropStartF, data.cropEndF, scoreType, data.themeIdx)

        val rowMap = mutableMapOf<Int, Bitmap>()
        val rectsMap = mutableMapOf<Int, List<RectF>>()
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
        cardCache[data.id] = rowMap
        rowRectsCache[data.id] = rectsMap

        val initialRow = data.keyframes.firstOrNull()?.rowIndex ?: 0
        val baseBmp = rowMap[initialRow] ?: rowMap[0]
        val outFile = File(context.cacheDir, "card_${data.id}.png")
        if (baseBmp != null) {
            // இந்த PNG அடிப்படை (base) லேயராக மட்டுமே பயன்படும். அம்பு மற்றும் வரிகள் அனைத்தும்
            // getActiveBitmap() மூலம் மேலே வரையப்படுவதால், இங்கே முழுவதும் வெளிப்படையான (transparent)
            // படத்தைச் சேமிக்கிறோம் - இல்லையெனில் இரண்டாவது அம்பு / இரட்டை லேயர் தெரியும்.
            val previewOut = Bitmap.createBitmap(baseBmp.width, baseBmp.height, Bitmap.Config.ARGB_8888)
            FileOutputStream(outFile).use { fos ->
                previewOut.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            previewOut.recycle()
        }
        Uri.fromFile(outFile).toString()
    }

    fun getActiveRowIndex(data: ScorecardClipData, timeInClipMs: Long): Int {
        val sorted = data.keyframes.sortedBy { it.timeMs }
        if (sorted.isEmpty()) return 0
        val activeKf = sorted.lastOrNull { it.timeMs <= timeInClipMs } ?: sorted.first()
        return activeKf.rowIndex
    }

    /**
     * Preview மற்றும் Video Export இரண்டிற்கும் அம்பு முன்னும் பின்னும் துடிக்கும் அனிமேஷனை வழங்கும்
     */
    fun getActiveBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        val baseBmp = cardCache[clipId]?.get(activeRow) ?: return null
        val targetRect = rowRectsCache[clipId]?.get(activeRow)?.getOrNull(activeRow)

        synchronized(dynamicFrameLock) {
            if (dynamicFrameBitmap == null || dynamicFrameBitmap?.isRecycled == true) {
                dynamicFrameBitmap = Bitmap.createBitmap(
                    ScoreCardComposer.CANVAS_W.toInt(),
                    ScoreCardComposer.CANVAS_H.toInt(),
                    Bitmap.Config.ARGB_8888
                )
                dynamicFrameCanvas = Canvas(dynamicFrameBitmap!!)
            }
            val canvas = dynamicFrameCanvas ?: return baseBmp
            // பழைய பிரேமை முழுமையாக அழிக்கவும் (இல்லையெனில் பழைய அம்புகளும் சிவப்புக் கோடுகளும் தங்கிவிடும்)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(baseBmp, 0f, 0f, null)
            if (targetRect != null) {
                ScoreCardComposer.drawBouncingArrow(canvas, targetRect, timeInClipMs, data.themeIdx)
            }
            return dynamicFrameBitmap
        }
    }

    /**
     * விரலால் தொடும்போது துல்லியமாகவும் மென்மையாகவும் பெயரைத் தேர்ந்தெடுக்கும் செயல்பாடு
     */
    fun hitTestRow(
        clipId: String,
        data: ScorecardClipData,
        timeInClipMs: Long,
        touchYFraction: Float,
        touchXFraction: Float
    ): Int {
        // இடதுபுறம் பெயர்கள் உள்ள பகுதியில் தொடும்போது மட்டுமே இயங்கும்
        if (touchXFraction > 0.52f) return -1
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        val rects = rowRectsCache[clipId]?.get(activeRow) ?: return -1
        val yInCanvas = touchYFraction * ScoreCardComposer.CANVAS_H

        // வரிகளுக்கு இடையே விரல் பட்டாலும் விடுபடாமல் இருக்க 5px இடைவெளி சலுகை
        return rects.indexOfFirst { rect ->
            yInCanvas >= (rect.top - 5f) && yInCanvas <= (rect.bottom + 5f)
        }
    }

    fun clearCache(clipId: String) {
        cardCache.remove(clipId)
        rowRectsCache.remove(clipId)
    }
}
