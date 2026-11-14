package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 4K-crisp Broadcast Vector Renderer for CDN/JSON Cricbuzz Scorecards.
 * - Theme 0: Neon
 * - Theme 1: Royal Gold
 * - Theme 2: Pop (Left side list intact + Right side Star Performer broadcast layout)
 */
object CdnVectorScoreCardComposer {

    private const val CDN_LIST_LEFT = 24f
    private const val CDN_LIST_WIDTH = 816f
    private const val CDN_PANEL_LEFT = 956f // 116px clean gap for bouncing arrow

    private val POP_RUN_GRADIENTS = arrayOf(
        intArrayOf(0xFF0052D4.toInt(), 0xFF4364F7.toInt(), 0xFF00C6FF.toInt()),
        intArrayOf(0xFFFF416C.toInt(), 0xFFFF4B2B.toInt(), 0xFFFF9068.toInt()),
        intArrayOf(0xFF7F00FF.toInt(), 0xFFE100FF.toInt(), 0xFFFF5CA8.toInt()),
        intArrayOf(0xFF00B09B.toInt(), 0xFF28DF99.toInt(), 0xFF96C93D.toInt()),
        intArrayOf(0xFFFC00FF.toInt(), 0xFFFF007A.toInt(), 0xFFFF8A00.toInt()),
        intArrayOf(0xFFFF8008.toInt(), 0xFFFF512F.toInt(), 0xFFFFC837.toInt()),
    )

    private val NEON_RUN_GRADIENTS = arrayOf(
        intArrayOf(0xFF00F5FF.toInt(), 0xFF38BDF8.toInt(), 0xFF4DFF9E.toInt()),
        intArrayOf(0xFFFF6BD6.toInt(), 0xFFFF4081.toInt(), 0xFFFFD400.toInt()),
        intArrayOf(0xFFFFB300.toInt(), 0xFFFF6D00.toInt(), 0xFFFFEA00.toInt()),
        intArrayOf(0xFFEA80FC.toInt(), 0xFFB388FF.toInt(), 0xFF00E5FF.toInt()),
        intArrayOf(0xFF76FF03.toInt(), 0xFF00E676.toInt(), 0xFF00E5FF.toInt()),
        intArrayOf(0xFFFF5252.toInt(), 0xFFFF8A65.toInt(), 0xFFFFD54F.toInt()),
    )

    private val GOLD_PILL_GRADIENTS = arrayOf(
        intArrayOf(0xFF00897B.toInt(), 0xFF004D40.toInt()),
        intArrayOf(0xFF1E88E5.toInt(), 0xFF0D47A1.toInt()),
        intArrayOf(0xFFD81B60.toInt(), 0xFF880E4F.toInt()),
        intArrayOf(0xFF8E24AA.toInt(), 0xFF4A148C.toInt()),
        intArrayOf(0xFFF4511E.toInt(), 0xFFBF360C.toInt()),
        intArrayOf(0xFF00ACC1.toInt(), 0xFF006064.toInt()),
    )

    sealed interface VectorRow {
        data class Header(val leftTitle: String, val scorePart: String, val oversPart: String) : VectorRow
        data class Columns(val col0: String, val stats: List<String>) : VectorRow
        data class PlayerRow(
            val playerId: String?,
            val name: String,
            val subText: String,
            val primaryStat: String,
            val otherStats: List<String>,
            val imageId: String?,
        ) : VectorRow
    }

    fun buildRows(innings: CdnInnings, type: ScoreCardType): List<VectorRow> {
        val teamLabel = innings.teamShortName.ifBlank { innings.teamName }
        val decSuffix = if (innings.isDeclared) "d" else ""
        val scorePart = "${innings.score}-${innings.wickets}$decSuffix"
        val oversPart = "(${innings.overs} Ov)"

        return if (type == ScoreCardType.BATTING) {
            buildList {
                add(VectorRow.Header(teamLabel, scorePart, oversPart))
                add(VectorRow.Columns("BATTER", listOf("R", "B", "4s", "6s", "SR")))
                innings.batters.forEach { b ->
                    add(
                        VectorRow.PlayerRow(
                            playerId = b.id,
                            name = b.name,
                            subText = b.outDesc,
                            primaryStat = b.runs.toString(),
                            otherStats = listOf(b.balls.toString(), b.fours.toString(), b.sixes.toString(), b.sr),
                            imageId = b.imageId,
                        )
                    )
                }
            }
        } else {
            buildList {
                add(VectorRow.Header("$teamLabel BOWLING", scorePart, oversPart))
                add(VectorRow.Columns("BOWLER", listOf("O", "M", "R", "W", "ECO")))
                innings.bowlers.forEach { bw ->
                    add(
                        VectorRow.PlayerRow(
                            playerId = bw.id,
                            name = bw.name,
                            subText = "",
                            primaryStat = bw.wickets.toString(),
                            otherStats = listOf(bw.overs, bw.maidens.toString(), bw.runs.toString(), bw.econ),
                            imageId = bw.imageId,
                        )
                    )
                }
            }
        }
    }

    fun composeFrame(
        context: Context,
        innings: CdnInnings,
        type: ScoreCardType,
        selected: Int,
        library: PlayerLibrary,
        themeIdx: Int = 0,
        opponentName: String? = null,
        staticRunsTimeMs: Long? = null,
    ): ComposedCard {
        val theme = ScoreCardComposer.normTheme(themeIdx)
        val rows = buildRows(innings, type)
        val w = ScoreCardComposer.CANVAS_W
        val h = ScoreCardComposer.CANVAS_H
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        drawStadiumBackdrop(c, p, w, h)

        val listLeft = CDN_LIST_LEFT
        val listWidth = CDN_LIST_WIDTH
        val footerH = 52f
        val footerTop = h - 26f - footerH
        val gap = 8f

        // Pin Header (0) & Column Bar (1) at top so Team score is NEVER clipped
        val headerH = 76f
        val colsH = 44f
        val headerRect = RectF(listLeft, 26f, listLeft + listWidth, 26f + headerH)
        val colsRect = RectF(listLeft, headerRect.bottom + gap, listLeft + listWidth, headerRect.bottom + gap + colsH)

        val playersTop = colsRect.bottom + gap
        val playersBottom = footerTop - 10f
        val playersAreaH = (playersBottom - playersTop).coerceAtLeast(100f)

        val playerRows = rows.drop(2)
        val playerHeights = playerRows.map { r ->
            val pr = r as VectorRow.PlayerRow
            if (pr.subText.isNotBlank()) 106f else 78f
        }
        val totalPlayersH = playerHeights.sum() + gap * max(0, playerRows.size - 1)

        val selPlayerIdx = (selected - 2).coerceIn(0, max(0, playerRows.size - 1))
        val startY = if (totalPlayersH <= playersAreaH) {
            playersTop
        } else {
            var activeTop = 0f
            for (i in 0 until selPlayerIdx) {
                activeTop += playerHeights[i] + gap
            }
            val activeH = playerHeights.getOrElse(selPlayerIdx) { 106f }
            val desiredTop = activeTop - (playersAreaH - activeH) / 2f
            val maxScroll = totalPlayersH - playersAreaH
            playersTop - desiredTop.coerceIn(0f, maxScroll)
        }

        val renderedRowRects = ArrayList<RectF>(rows.size)
        renderedRowRects.add(RectF(headerRect))
        renderedRowRects.add(RectF(colsRect))

        // Draw scrollable player rows clipped strictly between Column Bar and Footer
        c.save()
        c.clipRect(listLeft - 12f, playersTop - 4f, listLeft + listWidth + 12f, playersBottom + 4f)
        var currY = startY
        playerRows.forEachIndexed { pIdx, row ->
            val rh = playerHeights[pIdx]
            val rowRect = RectF(listLeft, currY, listLeft + listWidth, currY + rh)
            renderedRowRects.add(RectF(rowRect))
            val isSel = (pIdx + 2) == selected
            val rInfo = RowInfo(rowRect, 0, 0, isHeader = false, isColumnHeader = false)
            ScoreCardComposer.drawRowContainer(c, p, theme, rowRect, rInfo, isSel, pIdx)
            drawVectorRowContent(c, p, rowRect, row, theme, isSel, type, pIdx)
            currY += rh + gap
        }
        c.restore()

        // Draw pinned Team Header & Column Header on top
        rows.getOrNull(0)?.let { hdr ->
            val rInfo = RowInfo(headerRect, 0, 0, isHeader = true, isColumnHeader = false)
            ScoreCardComposer.drawRowContainer(c, p, theme, headerRect, rInfo, false, 0)
            drawVectorRowContent(c, p, headerRect, hdr, theme, false, type, 0)
        }
        rows.getOrNull(1)?.let { col ->
            val rInfo = RowInfo(colsRect, 0, 0, isHeader = false, isColumnHeader = true)
            ScoreCardComposer.drawRowContainer(c, p, theme, colsRect, rInfo, false, 0)
            drawVectorRowContent(c, p, colsRect, col, theme, false, type, 0)
        }

        // Bottom Overs & Run rate footer bar
        val footerRect = RectF(listLeft, footerTop, listLeft + listWidth, footerTop + footerH)
        drawFooterBar(c, p, footerRect, innings, theme)

        val selectedRow = rows.getOrNull(selected) as? VectorRow.PlayerRow
        val allPlayers = library.all()
        val matchedPlayer = selectedRow?.let { ScoreNameOcr.matchPlayer(it.name, allPlayers) }
        val photo = matchedPlayer?.let { library.photo(it) }
            ?: CdnScorecardClient.cachedPlayerPhoto(
                context = context,
                imageId = selectedRow?.imageId,
                playerId = selectedRow?.playerId,
                playerName = selectedRow?.name,
            )

        if (theme == 2) {
            drawPopStarPerformerPanel(
                c = c,
                p = p,
                bmpPaint = bmpPaint,
                innings = innings,
                type = type,
                selectedRow = selectedRow,
                player = matchedPlayer,
                photo = photo,
                opponentName = opponentName,
            )
        } else {
            ScoreCardComposer.drawPlayerPanel(
                c = c,
                p = p,
                bmpPaint = bmpPaint,
                theme = theme,
                type = type,
                player = matchedPlayer,
                photo = photo,
                fallbackName = selectedRow?.name,
                panelLeft = CDN_PANEL_LEFT,
                reserveRightForRuns = (selectedRow != null),
            )
            if (staticRunsTimeMs != null && selectedRow != null) {
                val lbl = if (type == ScoreCardType.BOWLING) "WKTS" else "RUNS"
                ScoreCardComposer.drawAnimatedRuns3D(
                    canvas = c,
                    runsText = selectedRow.primaryStat,
                    labelText = lbl,
                    timeInClipMs = staticRunsTimeMs,
                    themeIdx = theme,
                    panelLeft = CDN_PANEL_LEFT,
                )
            }
        }

        return ComposedCard(out, renderedRowRects)
    }

    /**
     * Design 3 (Pop) Right Panel — Star Performer broadcast graphic:
     * Full uncropped player photo cleanly visible at the top (or glossy 3D "NC" cricket ball
     * emblem when no photo is available), hot-pink STAR PERFORMER band, and dark emerald score strip below.
     */
    private fun drawPopStarPerformerPanel(
        c: Canvas,
        p: Paint,
        bmpPaint: Paint,
        innings: CdnInnings,
        type: ScoreCardType,
        selectedRow: VectorRow.PlayerRow?,
        player: Player?,
        photo: Bitmap?,
        opponentName: String?,
    ) {
        val pl = CDN_PANEL_LEFT
        val pr = ScoreCardComposer.CANVAS_W - 28f
        val pt = 28f
        val pb = ScoreCardComposer.CANVAS_H - 28f
        val pw = pr - pl
        val ph = pb - pt
        val panelRect = RectF(pl, pt, pr, pb)
        val corner = 24f

        val splitY = pt + ph * 0.575f
        val bandH = 38f
        val bandBottom = splitY + bandH

        c.save()
        c.clipPath(Path().apply { addRoundRect(panelRect, corner, corner, Path.Direction.CW) })

        val displayName = (player?.name ?: selectedRow?.name ?: if (type == ScoreCardType.BOWLING) "BOWLER" else "BATTER")
            .trim()
            .uppercase()

        // 1. Seamless studio backdrop + FULL uncropped player photo (or "NC" emblem if photo == null)
        if (photo != null && photo.width > 0 && photo.height > 0) {
            val leftEdge = sampleEdgeColor(photo, isLeft = true)
            val rightEdge = sampleEdgeColor(photo, isLeft = false)

            if (leftEdge != null && rightEdge != null) {
                p.shader = LinearGradient(
                    pl, 0f, pr, 0f,
                    intArrayOf(darkenColor(leftEdge, 0.90f), leftEdge, rightEdge, darkenColor(rightEdge, 0.90f)),
                    floatArrayOf(0f, 0.22f, 0.78f, 1f),
                    Shader.TileMode.CLAMP,
                )
                c.drawRect(pl, pt, pr, splitY, p)
                p.shader = null
            } else {
                p.shader = RadialGradient(
                    pl + pw * 0.5f, pt + (splitY - pt) * 0.4f, pw * 0.8f,
                    intArrayOf(0xFF1B4D3E.toInt(), 0xFF0A241C.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
                c.drawRect(pl, pt, pr, splitY, p)
                p.shader = null
            }

            val photoH = splitY - pt
            val scale = min(pw / photo.width, photoH / photo.height)
            val dw = photo.width * scale
            val dh = photo.height * scale
            val dl = pl + (pw - dw) / 2f
            val dt = splitY - dh
            c.save()
            c.clipRect(pl, pt, pr, splitY)
            c.drawBitmap(photo, null, RectF(dl, dt, dl + dw, dt + dh), bmpPaint)
            c.restore()
        } else {
            p.shader = RadialGradient(
                pl + pw * 0.5f, pt + (splitY - pt) * 0.45f, pw * 0.75f,
                intArrayOf(0xFF1B4D3E.toInt(), 0xFF0A241C.toInt(), 0xFF04140F.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(pl, pt, pr, splitY, p)
            p.shader = null

            ScoreCardComposer.drawNewsCrickNoPhotoEmblem(
                c = c,
                p = p,
                area = RectF(pl, pt + 22f, pr, splitY - 10f),
                theme = 2,
                isBowling = (type == ScoreCardType.BOWLING),
                playerName = displayName,
            )
        }

        // Subtle studio edge vignette on outer sides
        p.shader = LinearGradient(
            pl, 0f, pr, 0f,
            intArrayOf(0x28000000, 0x00000000, 0x00000000, 0x28000000),
            floatArrayOf(0f, 0.16f, 0.84f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(pl, pt, pr, splitY, p)
        p.shader = null

        // Top-left match pill badge
        val badgeW = pw * 0.26f
        val badgeH = 42f
        val badgeRect = RectF(pl + 20f, pt + 20f, pl + 20f + badgeW, pt + 20f + badgeH)
        p.style = Paint.Style.FILL
        p.color = 0xE6052D22.toInt()
        c.drawRoundRect(badgeRect, 12f, 12f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0xFFD4FF33.toInt()
        c.drawRoundRect(badgeRect, 12f, 12f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 21f
        p.textAlign = Paint.Align.CENTER
        c.drawText("⚡ NEWS CRICK", badgeRect.centerX(), badgeRect.centerY() + 7f, p)

        // 2. Hot-pink STAR PERFORMER divider band
        p.color = 0xFFFF007A.toInt()
        c.drawRect(pl, splitY, pr, bandBottom, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 18f
        p.letterSpacing = 0.08f
        p.textAlign = Paint.Align.CENTER
        c.drawText(
            "STAR PERFORMER  •  STAR PERFORMER  •  STAR PERFORMER  •  STAR PERFORMER",
            pl + pw / 2f,
            splitY + bandH * 0.68f,
            p,
        )
        p.letterSpacing = 0f

        // 3. Bottom dark emerald green graphic area
        p.color = 0xFF042D23.toInt()
        c.drawRect(pl, bandBottom, pr, pb, p)

        // Decorative subtle stadium curves in emerald section
        p.color = 0xFF073E30.toInt()
        val curve1 = Path().apply {
            moveTo(pl, bandBottom + 80f)
            quadTo(pl + pw * 0.45f, bandBottom - 10f, pr, bandBottom + 110f)
            lineTo(pr, pb)
            lineTo(pl, pb)
            close()
        }
        c.drawPath(curve1, p)

        p.color = 0xFF0A4A3A.toInt()
        val curve2 = Path().apply {
            moveTo(pl + pw * 0.25f, pb)
            quadTo(pl + pw * 0.65f, bandBottom + 40f, pr, bandBottom + 170f)
            lineTo(pr, pb)
            close()
        }
        c.drawPath(curve2, p)

        // Bat / Ball icon + Player Name + VS Team
        val iconCx = pl + 110f
        val iconCy = bandBottom + 88f
        drawCricketBadgeIcon(c, p, iconCx, iconCy, isBowling = (type == ScoreCardType.BOWLING))

        val subLine = if (!opponentName.isNullOrBlank()) {
            "VS ${opponentName.trim().uppercase()}"
        } else {
            val team = innings.teamName.ifBlank { innings.teamShortName }.uppercase()
            if (type == ScoreCardType.BOWLING) "$team  •  KEY BOWLER" else "$team  •  KEY BATSMAN"
        }

        val nameX = pl + 184f
        val maxNameW = pr - nameX - 28f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        p.color = 0xFFD4FF33.toInt()
        p.textSize = 48f
        while (p.measureText(displayName) > maxNameW && p.textSize > 22f) {
            p.textSize -= 2f
        }
        c.drawText(displayName, nameX, bandBottom + 82f, p)

        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 26f
        while (p.measureText(subLine) > maxNameW && p.textSize > 16f) {
            p.textSize -= 1.5f
        }
        c.drawText(subLine, nameX, bandBottom + 118f, p)

        // 4. 5-Column Stat Strip (alternating Hot Pink #FF007A & Emerald Transparent)
        val statCols: List<Pair<String, String>> = if (type == ScoreCardType.BATTING) {
            val r = selectedRow?.primaryStat ?: "0"
            val b = selectedRow?.otherStats?.getOrElse(0) { "0" } ?: "0"
            val f4 = selectedRow?.otherStats?.getOrElse(1) { "0" } ?: "0"
            val s6 = selectedRow?.otherStats?.getOrElse(2) { "0" } ?: "0"
            val sr = selectedRow?.otherStats?.getOrElse(3) { "0.0" } ?: "0.0"
            listOf(r to "RUNS", b to "BALLS", f4 to "FOURS", s6 to "SIXES", sr to "STRIKE RATE")
        } else {
            val o = selectedRow?.otherStats?.getOrElse(0) { "0" } ?: "0"
            val m = selectedRow?.otherStats?.getOrElse(1) { "0" } ?: "0"
            val r = selectedRow?.otherStats?.getOrElse(2) { "0" } ?: "0"
            val w = selectedRow?.primaryStat ?: "0"
            val eco = selectedRow?.otherStats?.getOrElse(3) { "0.0" } ?: "0.0"
            listOf(w to "WICKETS", o to "OVERS", m to "MAIDENS", r to "RUNS", eco to "ECONOMY")
        }

        val stripLeft = pl + 24f
        val stripRight = pr - 24f
        val stripTop = bandBottom + 160f
        val stripBottom = pb - 24f
        val colGap = 6f
        val colW = (stripRight - stripLeft - colGap * 4) / 5f

        statCols.forEachIndexed { idx, (value, label) ->
            val bx0 = stripLeft + idx * (colW + colGap)
            val bx1 = bx0 + colW
            val boxRect = RectF(bx0, stripTop, bx1, stripBottom)

            p.style = Paint.Style.FILL
            if (idx % 2 == 0) {
                p.color = 0xFFFF007A.toInt()
                c.drawRoundRect(boxRect, 6f, 6f, p)
            } else {
                p.color = 0x44031F18
                c.drawRoundRect(boxRect, 6f, 6f, p)
            }

            p.color = 0xFFFFFFFF.toInt()
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            p.textSize = 54f
            while (p.measureText(value) > (colW - 14f) && p.textSize > 24f) {
                p.textSize -= 2f
            }
            c.drawText(value, boxRect.centerX(), boxRect.top + boxRect.height() * 0.56f, p)

            p.textSize = 19f
            while (p.measureText(label) > (colW - 10f) && p.textSize > 13f) {
                p.textSize -= 1f
            }
            c.drawText(label, boxRect.centerX(), boxRect.top + boxRect.height() * 0.84f, p)
        }

        c.restore()

        // Crisp outer frame border
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = 0xCCFFFFFF.toInt()
        c.drawRoundRect(panelRect, corner, corner, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
    }

    private fun sampleEdgeColor(bmp: Bitmap, isLeft: Boolean): Int? {
        val w = bmp.width
        val h = bmp.height
        if (w < 6 || h < 6) return null
        val x = if (isLeft) 2 else w - 3
        val ys = intArrayOf(4, h / 8, h / 4, h / 3)
        var aSum = 0L
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        for (y in ys) {
            val px = bmp.getPixel(x, y.coerceIn(0, h - 1))
            val a = Color.alpha(px)
            if (a < 240) return null
            aSum += a
            rSum += Color.red(px)
            gSum += Color.green(px)
            bSum += Color.blue(px)
        }
        val n = ys.size
        return Color.argb((aSum / n).toInt(), (rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }

    private fun darkenColor(argb: Int, factor: Float): Int {
        val a = Color.alpha(argb)
        val r = (Color.red(argb) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(argb) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(argb) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun drawCricketBadgeIcon(c: Canvas, p: Paint, cx: Float, cy: Float, isBowling: Boolean) {
        p.style = Paint.Style.FILL
        p.color = 0xFFD4FF33.toInt()
        if (isBowling) {
            c.drawCircle(cx, cy, 34f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = 0xFF042D23.toInt()
            c.drawLine(cx - 22f, cy - 22f, cx + 22f, cy + 22f, p)
            p.style = Paint.Style.FILL
        } else {
            c.save()
            c.translate(cx, cy)
            c.rotate(42f)
            c.drawRoundRect(RectF(-12f, -12f, 12f, 44f), 4f, 4f, p)
            c.drawRoundRect(RectF(-4f, -44f, 4f, -12f), 2f, 2f, p)
            c.restore()
        }
    }

    private fun drawStadiumBackdrop(c: Canvas, p: Paint, w: Float, h: Float) {
        p.maskFilter = null
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF0A2418.toInt(), 0xFF0E3322.toInt(), 0xFF05140D.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, p)

        p.shader = RadialGradient(
            w * 0.42f, h * 0.15f, w * 0.65f,
            intArrayOf(0x2E22995A, 0x00000000),
            null,
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, p)

        val beamPath = Path().apply {
            moveTo(w * 0.25f, 0f)
            lineTo(w * 0.45f, 0f)
            lineTo(w * 0.20f, h)
            lineTo(0f, h)
            close()
        }
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x16FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )
        c.drawPath(beamPath, p)
        p.shader = null
    }

    private fun drawFooterBar(c: Canvas, p: Paint, rect: RectF, innings: CdnInnings, theme: Int) {
        val rad = 14f
        p.shader = null
        p.maskFilter = null
        p.style = Paint.Style.FILL

        when (theme) {
            0 -> {
                p.color = 0xD8071022.toInt()
                c.drawRoundRect(rect, rad, rad, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = 0x4400E5FF
                c.drawRoundRect(rect, rad, rad, p)
            }
            1 -> {
                p.color = 0xE2101015.toInt()
                c.drawRoundRect(rect, rad, rad, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = 0x60C8922A
                c.drawRoundRect(rect, rad, rad, p)
            }
            else -> {
                p.color = 0xEEFFFFFF.toInt()
                c.drawRoundRect(rect, rad, rad, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = 0x336C2BD9
                c.drawRoundRect(rect, rad, rad, p)
            }
        }

        val oversStr = if (innings.overs.contains(".")) innings.overs else "${innings.overs}.0"
        val decimalOvers = parseDecimalOvers(innings.overs)
        val runRate = if (decimalOvers > 0.0) {
            String.format(Locale.US, "%.2f", innings.score / decimalOvers)
        } else {
            "0.00"
        }

        val labelColor = when (theme) {
            0 -> 0xFF9FB4DC.toInt()
            1 -> 0xFFB8B2A2.toInt()
            else -> 0xFF5C6475.toInt()
        }
        val valueColor = when (theme) {
            0 -> 0xFFFFFFFF.toInt()
            1 -> 0xFFF6D77A.toInt()
            else -> 0xFF141824.toInt()
        }

        val cy = rect.centerY() + 8f
        p.style = Paint.Style.FILL
        p.textSize = 23f
        p.textAlign = Paint.Align.LEFT

        val leftStart = rect.left + 28f
        p.typeface = Typeface.DEFAULT
        p.color = labelColor
        val oversLbl = "Overs  "
        c.drawText(oversLbl, leftStart, cy, p)

        p.typeface = Typeface.DEFAULT_BOLD
        p.color = valueColor
        c.drawText(oversStr, leftStart + p.measureText(oversLbl), cy, p)

        val rightEnd = rect.right - 26f
        p.typeface = Typeface.DEFAULT_BOLD
        val rrValW = p.measureText(runRate)
        p.typeface = Typeface.DEFAULT
        val rrLbl = "Run rate  "
        val rrLblW = p.measureText(rrLbl)

        p.color = labelColor
        c.drawText(rrLbl, rightEnd - rrValW - rrLblW, cy, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = valueColor
        c.drawText(runRate, rightEnd - rrValW, cy, p)
    }

    private fun parseDecimalOvers(overs: String): Double {
        val trimmed = overs.trim()
        val parts = trimmed.split(".")
        val whole = parts.getOrNull(0)?.toIntOrNull() ?: return 0.0
        val balls = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return whole + (balls.coerceIn(0, 5) / 6.0)
    }

    private fun drawVectorRowContent(
        c: Canvas,
        p: Paint,
        rect: RectF,
        row: VectorRow,
        theme: Int,
        selected: Boolean,
        type: ScoreCardType,
        playerIndex: Int,
    ) {
        val leftX = rect.left + if (theme == 1 && row !is VectorRow.Header) 80f else 34f
        val rightX = rect.right - 24f
        val statXs = floatArrayOf(
            rect.right - 318f,
            rect.right - 228f,
            rect.right - 168f,
            rect.right - 110f,
            rightX,
        )

        val mainColor = when {
            theme == 2 && row !is VectorRow.Header -> 0xFF141824.toInt()
            theme == 1 && selected -> 0xFF1A1208.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val subColor = when {
            theme == 2 && !selected -> 0xFF5C6475.toInt()
            theme == 2 && selected -> 0xFF4A4E5D.toInt()
            theme == 1 && selected -> 0xFF4D350A.toInt()
            selected && theme == 0 -> 0xFFFFC4CE.toInt()
            theme == 0 -> 0xFF9FB4DC.toInt()
            else -> 0xFFAAB0C2.toInt()
        }

        p.shader = null
        p.maskFilter = null
        p.clearShadowLayer()
        p.style = Paint.Style.FILL
        p.letterSpacing = 0f

        when (row) {
            is VectorRow.Header -> {
                p.textSize = 24f
                p.typeface = Typeface.DEFAULT
                val ovText = " ${row.oversPart}"
                val ovW = p.measureText(ovText)

                p.textSize = 38f
                p.typeface = Typeface.DEFAULT_BOLD
                val scW = p.measureText(row.scorePart)

                val rightTotalW = scW + ovW
                val titleLeft = rect.left + 34f
                val maxTitleW = (rightX - titleLeft - rightTotalW - 20f).coerceAtLeast(120f)

                p.textSize = 35f
                p.color = if (theme == 1) 0xFFF6D77A.toInt() else 0xFFFFFFFF.toInt()
                p.textAlign = Paint.Align.LEFT
                c.drawText(ellipsize(row.leftTitle, p, maxTitleW), titleLeft, rect.centerY() + 12f, p)

                p.textAlign = Paint.Align.RIGHT
                p.textSize = 24f
                p.typeface = Typeface.DEFAULT
                p.color = if (theme == 1) 0xFFE8D8B0.toInt() else 0xFFDCEBFF.toInt()
                c.drawText(ovText, rightX, rect.centerY() + 10f, p)

                p.textSize = 38f
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = 0xFFFFFFFF.toInt()
                c.drawText(row.scorePart, rightX - ovW, rect.centerY() + 12f, p)
            }

            is VectorRow.Columns -> {
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = if (theme == 1) 0xFFD6B363.toInt() else subColor
                p.textSize = 21f
                p.textAlign = Paint.Align.LEFT
                c.drawText(row.col0, leftX, rect.centerY() + 8f, p)

                row.stats.forEachIndexed { idx, label ->
                    if (idx < statXs.size) {
                        p.textAlign = if (idx == 4) Paint.Align.RIGHT else Paint.Align.CENTER
                        c.drawText(label, statXs[idx], rect.centerY() + 8f, p)
                    }
                }
            }

            is VectorRow.PlayerRow -> {
                if (theme == 1) {
                    val cx = rect.left + 48f
                    val cy = rect.centerY()
                    val radius = 18f
                    if (selected) {
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 2.5f
                        p.color = 0xFF1A1208.toInt()
                        c.drawCircle(cx, cy, radius, p)
                    } else {
                        p.style = Paint.Style.FILL
                        p.color = 0xFF181820.toInt()
                        c.drawCircle(cx, cy, radius, p)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 1.8f
                        p.color = 0xAAC8922A.toInt()
                        c.drawCircle(cx, cy, radius, p)
                    }
                    p.style = Paint.Style.FILL
                    p.typeface = Typeface.DEFAULT_BOLD
                    p.textSize = 19f
                    p.textAlign = Paint.Align.CENTER
                    p.color = if (selected) 0xFF1A1208.toInt() else 0xFFF6D77A.toInt()
                    c.drawText((playerIndex + 1).toString(), cx, cy + 7f, p)
                }

                // Bigger, prominent Player Name across all templates
                val maxNameW = (statXs[0] - 48f) - leftX - 10f
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = mainColor
                p.textSize = 36f
                while (p.measureText(row.name) > maxNameW && p.textSize > 24f) {
                    p.textSize -= 1.5f
                }
                p.textAlign = Paint.Align.LEFT
                val nameY = if (row.subText.isNotBlank()) rect.top + rect.height() * 0.45f else rect.centerY() + 12f
                c.drawText(ellipsize(row.name, p, maxNameW), leftX, nameY, p)

                if (row.subText.isNotBlank()) {
                    p.typeface = Typeface.DEFAULT
                    p.color = subColor
                    p.textSize = 20f
                    c.drawText(ellipsize(row.subText, p, maxNameW), leftX, rect.top + rect.height() * 0.80f, p)
                }

                val allCols = if (type == ScoreCardType.BATTING) {
                    listOf(row.primaryStat) + row.otherStats
                } else {
                    listOf(
                        row.otherStats.getOrElse(0) { "0" },
                        row.otherStats.getOrElse(1) { "0" },
                        row.otherStats.getOrElse(2) { "0" },
                        row.primaryStat,
                        row.otherStats.getOrElse(3) { "0.00" }
                    )
                }
                val primaryIdx = if (type == ScoreCardType.BATTING) 0 else 3

                allCols.forEachIndexed { idx, v ->
                    if (idx >= statXs.size) return@forEachIndexed
                    val isPrimary = idx == primaryIdx
                    val colX = statXs[idx]

                    if (isPrimary && theme == 1) {
                        val pillW = 82f
                        val pillH = 62f
                        val pillRect = RectF(
                            colX - pillW / 2f,
                            rect.centerY() - pillH / 2f,
                            colX + pillW / 2f,
                            rect.centerY() + pillH / 2f,
                        )
                        val pillColors = if (selected) {
                            intArrayOf(0xFFB0124A.toInt(), 0xFF4A0A52.toInt())
                        } else {
                            GOLD_PILL_GRADIENTS[playerIndex % GOLD_PILL_GRADIENTS.size]
                        }
                        p.style = Paint.Style.FILL
                        p.shader = LinearGradient(
                            pillRect.left, pillRect.top, pillRect.right, pillRect.bottom,
                            pillColors, null, Shader.TileMode.CLAMP,
                        )
                        c.drawRoundRect(pillRect, 14f, 14f, p)
                        p.shader = null

                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 2f
                        p.color = if (selected) 0xFF1A1208.toInt() else 0xFFF6D77A.toInt()
                        c.drawRoundRect(pillRect, 14f, 14f, p)
                        p.style = Paint.Style.FILL

                        p.typeface = Typeface.DEFAULT_BOLD
                        p.textSize = 44f
                        while (p.measureText(v) > pillW - 12f && p.textSize > 28f) {
                            p.textSize -= 2f
                        }
                        p.textAlign = Paint.Align.CENTER
                        p.shader = LinearGradient(
                            0f, pillRect.top + 8f, 0f, pillRect.bottom - 8f,
                            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE680.toInt(), 0xFFFFB300.toInt()),
                            null, Shader.TileMode.CLAMP,
                        )
                        p.setShadowLayer(4f, 0f, 2f, 0x66000000)
                        c.drawText(v, colX, rect.centerY() + p.textSize * 0.35f, p)
                        p.clearShadowLayer()
                        p.shader = null
                    } else if (isPrimary) {
                        p.typeface = Typeface.DEFAULT_BOLD
                        p.textSize = 48f
                        while (p.measureText(v) > 84f && p.textSize > 32f) {
                            p.textSize -= 2f
                        }
                        p.textAlign = Paint.Align.CENTER

                        val gradColors = if (theme == 0) {
                            if (selected) {
                                intArrayOf(0xFFFFF59D.toInt(), 0xFFFFD400.toInt(), 0xFFFF5252.toInt())
                            } else {
                                NEON_RUN_GRADIENTS[playerIndex % NEON_RUN_GRADIENTS.size]
                            }
                        } else {
                            if (selected) {
                                intArrayOf(0xFFFF007A.toInt(), 0xFFFF6D00.toInt(), 0xFF7B1FA2.toInt())
                            } else {
                                POP_RUN_GRADIENTS[playerIndex % POP_RUN_GRADIENTS.size]
                            }
                        }

                        p.shader = LinearGradient(
                            colX - 28f, rect.top + 14f,
                            colX + 28f, rect.bottom - 14f,
                            gradColors, null, Shader.TileMode.CLAMP,
                        )
                        val shadowCol = if (theme == 0) {
                            (0x88 shl 24) or (gradColors[0] and 0x00FFFFFF)
                        } else {
                            0x33000000
                        }
                        p.setShadowLayer(if (theme == 0) 8f else 4f, 0f, if (theme == 0) 0f else 2f, shadowCol)
                        c.drawText(v, colX, rect.centerY() + p.textSize * 0.35f, p)
                        p.clearShadowLayer()
                        p.shader = null
                    } else {
                        val isSixesHighlight = theme == 0 && type == ScoreCardType.BATTING && idx == 3 && (v.toIntOrNull() ?: 0) >= 3
                        p.typeface = if (theme == 1 && selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        p.color = if (isSixesHighlight) 0xFFFFD400.toInt() else mainColor
                        p.textSize = 23f
                        p.textAlign = if (idx == 4) Paint.Align.RIGHT else Paint.Align.CENTER
                        c.drawText(v, colX, rect.centerY() + 8f, p)
                    }
                }
            }
        }
        p.clearShadowLayer()
        p.shader = null
        p.textAlign = Paint.Align.LEFT
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    fun loadInnings(context: Context, jsonUri: String, inningsIdx: Int): CdnInnings? =
        loadInningsWithOpponent(context, jsonUri, inningsIdx)?.first

    fun loadInningsWithOpponent(context: Context, jsonUri: String, inningsIdx: Int): Pair<CdnInnings, String?>? {
        val raw = runCatching {
            val uri = Uri.parse(jsonUri)
            val file = File(uri.path ?: jsonUri)
            if (file.exists()) file.readText() else null
        }.getOrNull() ?: return null
        val match = runCatching { CdnScorecardClient.parseJson(raw) }.getOrNull() ?: return null
        val inn = match.innings.getOrNull(inningsIdx) ?: match.innings.firstOrNull() ?: return null
        val opp = match.innings.firstOrNull { it.inningsId != inn.inningsId }?.let {
            it.teamName.ifBlank { it.teamShortName }
        }
        return inn to opp
    }
}
