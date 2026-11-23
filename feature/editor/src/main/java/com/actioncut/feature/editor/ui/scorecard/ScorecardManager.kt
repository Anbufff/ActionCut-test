package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
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
import kotlin.math.PI
import kotlin.math.sin

object ScorecardManager {

    private const val MAX_CACHED_CARDS = 1
    private const val PREVIEW_W = 960
    private const val PREVIEW_H = 540
    private const val PREVIEW_SCALE = 0.5f

    // ---- CDN card TV-style entrance animation (preview + export share the same code) ----
    private const val ENTRANCE_MS = 1000L
    private const val CDN_SPLIT_X = 900f        // between list (ends 840) and player panel (starts 956)
    private const val CDN_PLAYERS_TOP = 162f
    private const val CDN_PLAYERS_BOTTOM = 992f

    private val cardCache = object : LinkedHashMap<String, MutableMap<Int, Bitmap>>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, Bitmap>>?): Boolean {
            return size > MAX_CACHED_CARDS
        }
    }
    private val rowRectsCache = object : LinkedHashMap<String, MutableMap<Int, List<RectF>>>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, List<RectF>>>?): Boolean {
            return size > MAX_CACHED_CARDS
        }
    }
    private val rowStatCache = object : LinkedHashMap<String, MutableMap<Int, Pair<String, String>>>(2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, Pair<String, String>>>?): Boolean {
            return size > MAX_CACHED_CARDS
        }
    }

    @Volatile
    private var appContext: Context? = null
    private val prepLock = Any()

    private val previewFrameLock = Any()
    private val previewFrames = arrayOfNulls<Bitmap>(2)
    private var previewFrameIdx = 0
    private var cachedPreviewKey: String? = null
    private var cachedPreviewRow: Int = -1
    private var cachedPreviewBmp: Bitmap? = null

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
        val statMap = mutableMapOf<Int, Pair<String, String>>()

        if (isCdnVector) {
            val mode = CdnViewMode.fromTypeKey(data.type)
            val bundle = CdnVectorScoreCardComposer.loadSavedBundle(context, data.sourceImageUri)
                ?: return@withContext ""
            val idx = data.cropStartF.toInt().coerceAtLeast(0)
            val inn = bundle.match?.innings?.getOrNull(idx) ?: bundle.match?.innings?.firstOrNull()
            val opp = if (inn != null) {
                bundle.match?.innings?.firstOrNull { it.inningsId != inn.inningsId }?.let {
                    it.teamName.ifBlank { it.teamShortName }
                }
            } else null
            val ptGroup = bundle.pointsTable?.groups?.getOrNull(idx) ?: bundle.pointsTable?.groups?.firstOrNull()
            val ptSeriesName = bundle.pointsTable?.seriesName
            val sqTeam = bundle.squads?.teams?.getOrNull(idx) ?: bundle.squads?.teams?.firstOrNull()
            val sqOpp = if (sqTeam != null) {
                bundle.squads?.teams?.firstOrNull { it !== sqTeam }?.let {
                    it.teamName.ifBlank { it.teamShortName }
                }
            } else opp

            val vRows = CdnVectorScoreCardComposer.buildRowsForMode(
                mode = mode,
                innings = inn,
                pointsGroup = ptGroup,
                pointsSeriesName = ptSeriesName,
                squadTeam = sqTeam,
            )

            for (i in data.rows.indices) {
                val composed = CdnVectorScoreCardComposer.composeModeFrame(
                    context = context,
                    mode = mode,
                    selected = i,
                    library = library,
                    themeIdx = data.themeIdx,
                    innings = inn,
                    pointsGroup = ptGroup,
                    pointsSeriesName = ptSeriesName,
                    squadTeam = sqTeam,
                    opponentName = if (mode == CdnViewMode.SQUADS) sqOpp else opp,
                    staticRunsTimeMs = null,
                )
                rowMap[i] = composed.bitmap
                rectsMap[i] = composed.rowRects
                val vr = vRows.getOrNull(i) as? CdnVectorScoreCardComposer.VectorRow.PlayerRow
                if (vr != null) {
                    statMap[i] = CdnVectorScoreCardComposer.animatedBadgeForMode(mode, vr)
                }
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
            rowStatCache[key] = statMap
        }
        synchronized(previewFrameLock) {
            if (cachedPreviewKey == key) {
                cachedPreviewBmp = null
                cachedPreviewRow = -1
            }
        }

        val outFile = File(context.cacheDir, "card_${data.id}.png")
        if (!outFile.exists()) {
            val tinyPlaceholder = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
            FileOutputStream(outFile).use { fos ->
                tinyPlaceholder.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            tinyPlaceholder.recycle()
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

    private data class ResolvedRowFrame(
        val activeRow: Int,
        val baseBmp: Bitmap,
        val targetRect: RectF?,
        val statPair: Pair<String, String>?,
        val rowRects: List<RectF>? = null,
    )

    private fun easeOutCubic(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val i = 1f - c
        return 1f - i * i * i
    }

    /**
     * Entrance: list slides in from the left, player card from the right (+150ms), and every list
     * row (header, columns, players) reveals one after another. [k] = output px per 1920-canvas px.
     */
    private fun drawEntranceBase(canvas: Canvas, base: Bitmap, k: Float, rects: List<RectF>?, t: Long) {
        val w = base.width.toFloat()
        val h = base.height.toFloat()
        val split = CDN_SPLIT_X * k
        val tf = t.toFloat()

        // Player card (right slice)
        val pR = easeOutCubic((tf - 150f) / 450f)
        if (pR > 0f) {
            val dx = (1f - pR) * (w - split)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255f * pR).toInt().coerceIn(0, 255) }
            canvas.save()
            canvas.clipRect(split + dx, 0f, w, h)
            canvas.drawBitmap(base, dx, 0f, paint)
            canvas.restore()
        }

        // Row bands (header, columns, visible players) in output pixels
        val bands = ArrayList<RectF>()
        rects?.forEachIndexed { idx, r ->
            var top = r.top
            var bottom = r.bottom
            if (idx >= 2) {
                top = maxOf(top, CDN_PLAYERS_TOP)
                bottom = minOf(bottom, CDN_PLAYERS_BOTTOM)
            }
            if (bottom - top < 4f) return@forEachIndexed
            bands.add(RectF((r.left - 12f) * k, top * k, (r.right + 12f) * k, bottom * k))
        }

        // List background (everything in the left slice that is not a row) slides in
        val pL = easeOutCubic(tf / 450f)
        val dxL = -(1f - pL) * split
        canvas.save()
        canvas.clipRect(dxL, 0f, split + dxL, h)
        bands.forEach { b -> canvas.clipOutRect(RectF(b.left + dxL, b.top, b.right + dxL, b.bottom)) }
        canvas.drawBitmap(base, dxL, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()

        // Rows reveal one by one
        val step = minOf(70f, 450f / maxOf(1, bands.size))
        bands.forEachIndexed { i, b ->
            val pi = easeOutCubic((tf - 100f - i * step) / 320f)
            if (pi <= 0f) return@forEachIndexed
            val ox = dxL - (1f - pi) * 60f * k
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255f * pi).toInt().coerceIn(0, 255) }
            canvas.save()
            canvas.clipRect(b.left + ox, b.top, b.right + ox, b.bottom)
            canvas.drawBitmap(base, ox, 0f, paint)
            canvas.restore()
        }
    }

    /** Soft pulsing glow around the highlighted row (canvas must already be in 1920x1080 space). */
    private fun drawRowGlow(canvas: Canvas, rect: RectF, t: Long) {
        val ramp = ((t - 400L) / 300f).coerceIn(0f, 1f)
        if (ramp <= 0f) return
        val pulse = 0.5f + 0.5f * sin(2.0 * PI * t / 2000.0).toFloat()
        val a = (255f * (0.18f + 0.30f * pulse) * ramp).toInt().coerceIn(0, 255)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = 0xFFFFFFFF.toInt()
            alpha = a
            maskFilter = BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(rect.left - 3f, rect.top - 3f, rect.right + 3f, rect.bottom + 3f),
            18f, 18f, paint,
        )
    }

    private fun arrowAlpha(t: Long): Int = (255f * ((t - 500L) / 300f).coerceIn(0f, 1f)).toInt()

    private fun resolveRowState(data: ScorecardClipData, timeInClipMs: Long): ResolvedRowFrame? {
        val key = cacheKey(data)
        var baseMap: Map<Int, Bitmap>?
        var rectsMap: Map<Int, List<RectF>>?
        var statMap: Map<Int, Pair<String, String>>?
        synchronized(prepLock) {
            baseMap = cardCache[key]
            rectsMap = rowRectsCache[key]
            statMap = rowStatCache[key]
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
                    statMap = rowStatCache[key]
                }
            }
        }
        val map = baseMap ?: return null
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        val baseBmp = map[activeRow] ?: map.values.firstOrNull() ?: return null
        if (baseBmp.isRecycled) return null
        val targetRect = rectsMap?.get(activeRow)?.getOrNull(activeRow)
        val statPair = statMap?.get(activeRow)
        return ResolvedRowFrame(activeRow, baseBmp, targetRect, statPair, rectsMap?.get(activeRow))
    }

    /** Preview-optimized 16:9 frame (960x540) — 4x faster & 75% less RAM/GPU bandwidth without touching export quality. */
    fun getActiveBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        val resolved = resolveRowState(data, timeInClipMs) ?: return null
        val isCdnVector = data.sourceImageUri.endsWith(".json", ignoreCase = true)
        val key = cacheKey(data)
        synchronized(previewFrameLock) {
            val currentScaled = cachedPreviewBmp
            if (currentScaled == null ||
                currentScaled.isRecycled ||
                cachedPreviewKey != key ||
                cachedPreviewRow != resolved.activeRow
            ) {
                cachedPreviewBmp = Bitmap.createScaledBitmap(resolved.baseBmp, PREVIEW_W, PREVIEW_H, true)
                cachedPreviewKey = key
                cachedPreviewRow = resolved.activeRow
            }
            val scaledBase = cachedPreviewBmp ?: return null

            val idx = previewFrameIdx
            previewFrameIdx = (previewFrameIdx + 1) and 1
            var out = previewFrames[idx]
            if (out == null || out.isRecycled || out.width != PREVIEW_W || out.height != PREVIEW_H) {
                out = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888)
                previewFrames[idx] = out
            }
            val canvas = Canvas(out)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            val entrance = isCdnVector && timeInClipMs < ENTRANCE_MS
            if (entrance) {
                drawEntranceBase(canvas, scaledBase, PREVIEW_SCALE, resolved.rowRects, timeInClipMs)
            } else {
                canvas.drawBitmap(scaledBase, 0f, 0f, null)
            }

            canvas.save()
            canvas.scale(PREVIEW_SCALE, PREVIEW_SCALE)
            if (isCdnVector && resolved.targetRect != null) {
                drawRowGlow(canvas, resolved.targetRect, timeInClipMs)
            }
            if (resolved.targetRect != null) {
                val arrowLayer = if (entrance && timeInClipMs < ENTRANCE_MS) arrowAlpha(timeInClipMs) else 255
                if (arrowLayer > 0) {
                    val saved = if (arrowLayer < 255) canvas.saveLayerAlpha(0f, 0f, ScoreCardComposer.CANVAS_W, ScoreCardComposer.CANVAS_H, arrowLayer) else -1
                    ScoreCardComposer.drawBouncingArrow(
                        canvas,
                        resolved.targetRect,
                        timeInClipMs,
                        data.themeIdx,
                        compact = isCdnVector,
                    )
                    if (saved >= 0) canvas.restoreToCount(saved)
                }
            }
            if (ScoreCardComposer.normTheme(data.themeIdx) != 2 && resolved.statPair != null) {
                ScoreCardComposer.drawAnimatedRuns3D(
                    canvas = canvas,
                    runsText = resolved.statPair.first,
                    labelText = resolved.statPair.second,
                    timeInClipMs = timeInClipMs,
                    themeIdx = data.themeIdx,
                    panelLeft = if (isCdnVector) 956f else ScoreCardComposer.PANEL_LEFT,
                )
            }
            canvas.restore()
            return out
        }
    }

    /** Full-HD 1920x1080 export frame — 100% original crisp export quality. */
    fun getExportBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        val resolved = resolveRowState(data, timeInClipMs) ?: return null
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
            val entrance = isCdnVector && timeInClipMs < ENTRANCE_MS
            if (entrance) {
                drawEntranceBase(canvas, resolved.baseBmp, 1f, resolved.rowRects, timeInClipMs)
            } else {
                canvas.drawBitmap(resolved.baseBmp, 0f, 0f, null)
            }
            if (isCdnVector && resolved.targetRect != null) {
                drawRowGlow(canvas, resolved.targetRect, timeInClipMs)
            }
            if (resolved.targetRect != null) {
                val arrowLayer = if (entrance) arrowAlpha(timeInClipMs) else 255
                if (arrowLayer > 0) {
                    val saved = if (arrowLayer < 255) canvas.saveLayerAlpha(0f, 0f, ScoreCardComposer.CANVAS_W, ScoreCardComposer.CANVAS_H, arrowLayer) else -1
                    ScoreCardComposer.drawBouncingArrow(
                        canvas,
                        resolved.targetRect,
                        timeInClipMs,
                        data.themeIdx,
                        compact = isCdnVector,
                    )
                    if (saved >= 0) canvas.restoreToCount(saved)
                }
            }
            if (ScoreCardComposer.normTheme(data.themeIdx) != 2 && resolved.statPair != null) {
                ScoreCardComposer.drawAnimatedRuns3D(
                    canvas = canvas,
                    runsText = resolved.statPair.first,
                    labelText = resolved.statPair.second,
                    timeInClipMs = timeInClipMs,
                    themeIdx = data.themeIdx,
                    panelLeft = if (isCdnVector) 956f else ScoreCardComposer.PANEL_LEFT,
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
        if (touchXFraction > 0.48f) return -1
        val key = cacheKey(data)
        val activeRow = getActiveRowIndex(data, timeInClipMs)
        val rects = synchronized(prepLock) { rowRectsCache[key]?.get(activeRow) } ?: return -1
        val yInCanvas = touchYFraction * ScoreCardComposer.CANVAS_H
        val isCdn = data.sourceImageUri.endsWith(".json", ignoreCase = true)

        // CDN card: the team header bar (row 0, e.g. "NSW 195-7") is tappable too.
        if (isCdn) {
            val header = rects.getOrNull(0)
            if (header != null && yInCanvas >= header.top - 8f && yInCanvas <= header.bottom + 8f) {
                return 0
            }
        }

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        rects.forEachIndexed { idx, rect ->
            if (isCdn && idx < 2) return@forEachIndexed
            if (rect.bottom < 120f || rect.top > 1020f) return@forEachIndexed
            val dist = when {
                yInCanvas < rect.top -> rect.top - yInCanvas
                yInCanvas > rect.bottom -> yInCanvas - rect.bottom
                else -> 0f
            }
            if (dist <= 36f && dist < bestDist) {
                bestDist = dist
                bestIdx = idx
            }
        }
        return bestIdx
    }

    fun clearCache(clipId: String) {
        synchronized(prepLock) {
            val keys = cardCache.keys.filter { it.startsWith("${clipId}_") || it == clipId }
            keys.forEach {
                cardCache.remove(it)
                rowRectsCache.remove(it)
                rowStatCache.remove(it)
            }
        }
        synchronized(previewFrameLock) {
            if (cachedPreviewKey?.startsWith("${clipId}_") == true || cachedPreviewKey == clipId) {
                cachedPreviewBmp = null
                cachedPreviewKey = null
                cachedPreviewRow = -1
            }
        }
    }
}
