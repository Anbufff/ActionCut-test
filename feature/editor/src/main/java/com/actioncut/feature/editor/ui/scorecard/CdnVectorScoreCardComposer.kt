package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
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
 * Features Neon (Theme 0), Royal Gold (Theme 1), and Sunset Pop (Theme 2)
 * with Overs/Run-Rate footer and clean arrow gap before the player card.
 */
object CdnVectorScoreCardComposer {

    private const val CDN_LIST_LEFT = 24f
    private const val CDN_LIST_WIDTH = 816f
    private const val CDN_PANEL_LEFT = 956f // 116px clean gap for bouncing arrow

    private val NEON_ACCENTS = intArrayOf(
        0xFF00E5FF.toInt(),
        0xFFB388FF.toInt(),
        0xFF7CFF6B.toInt(),
        0xFFFF9F43.toInt(),
        0xFFFF6BD6.toInt(),
        0xFF38BDF8.toInt(),
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
    ): ComposedCard {
        val theme = ScoreCardComposer.normTheme(themeIdx)
        val rows = buildRows(innings, type)
        val w = ScoreCardComposer.CANVAS_W
        val h = ScoreCardComposer.CANVAS_H
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // Deep stadium emerald background matching broadcast look
        drawStadiumBackdrop(c, p, w, h)

        val listLeft = CDN_LIST_LEFT
        val listWidth = CDN_LIST_WIDTH
        val footerH = 54f
        val footerTop = h - 28f - footerH
        val listTop = 28f
        val listBottom = footerTop - 10f
        val listHeight = listBottom - listTop

        val rowHeights = rows.map { r ->
            when (r) {
                is VectorRow.Header -> 78f
                is VectorRow.Columns -> 46f
                is VectorRow.PlayerRow -> if (r.subText.isNotBlank()) 104f else 76f
            }
        }
        val gap = 9f
        val totalH = rowHeights.sum() + gap * max(0, rows.size - 1)

        val cY = if (totalH <= listHeight) {
            listTop + (listHeight - totalH) / 2f
        } else {
            var activeTop = 0f
            for (i in 0 until selected.coerceIn(0, rows.size - 1)) {
                activeTop += rowHeights[i] + gap
            }
            val activeH = rowHeights.getOrElse(selected) { 96f }
            val desiredTop = activeTop - (listHeight - activeH) / 2f
            val maxScroll = totalH - listHeight
            listTop - desiredTop.coerceIn(0f, maxScroll)
        }

        val renderedRowRects = ArrayList<RectF>()
        c.save()
        c.clipRect(listLeft - 12f, listTop - 12f, listLeft + listWidth + 12f, listBottom + 12f)

        var currY = cY
        var playerCounter = 0
        rows.forEachIndexed { i, row ->
            val rh = rowHeights[i]
            val rowRect = RectF(listLeft, currY, listLeft + listWidth, currY + rh)
            renderedRowRects.add(RectF(rowRect))

            val isSel = i == selected
            val isHeader = row is VectorRow.Header
            val isCol = row is VectorRow.Columns
            val pIdx = if (!isHeader && !isCol) playerCounter++ else 0

            val rInfo = RowInfo(rowRect, 0, 0, isHeader = isHeader, isColumnHeader = isCol)
            ScoreCardComposer.drawRowContainer(c, p, theme, rowRect, rInfo, isSel, pIdx)
            drawVectorRowContent(c, p, rowRect, row, theme, isSel, type, pIdx)

            currY += rh + gap
        }
        c.restore()

        // Bottom Overs & Run rate footer bar
        val footerRect = RectF(listLeft, footerTop, listLeft + listWidth, footerTop + footerH)
        drawFooterBar(c, p, footerRect, innings, theme)

        val selectedRow = rows.getOrNull(selected) as? VectorRow.PlayerRow
        val allPlayers = library.all()
        val matchedPlayer = selectedRow?.let { ScoreNameOcr.matchPlayer(it.name, allPlayers) }
        val photo = matchedPlayer?.let { library.photo(it) }
            ?: CdnScorecardClient.cachedPlayerPhoto(context, selectedRow?.imageId, selectedRow?.playerId)

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
        )

        return ComposedCard(out, renderedRowRects)
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

        // Subtle stadium light beams
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

        // Right-aligned Run rate
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
            rect.right - 330f, // col 0 (R / O) - centered
            rect.right - 248f, // col 1 (B / M)
            rect.right - 184f, // col 2 (4s / R)
            rect.right - 122f, // col 3 (6s / W)
            rightX,            // col 4 (SR / ECO) - right aligned
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
        p.style = Paint.Style.FILL
        p.letterSpacing = 0f

        when (row) {
            is VectorRow.Header -> {
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = if (theme == 1) 0xFFF6D77A.toInt() else 0xFFFFFFFF.toInt()
                p.textSize = 35f

                // Measure right side: big scorePart + smaller oversPart
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

                // Draw score + overs on right
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
                p.color = when (theme) {
                    1 -> 0xFFD6B363.toInt()
                    else -> subColor
                }
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
                // Theme 1 (Gold): Draw numbered circle badge (1), (2), (3)...
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

                // Player name + dismissal on left
                val maxNameW = (statXs[0] - 46f) - leftX - 10f
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = mainColor
                p.textSize = 27f
                p.textAlign = Paint.Align.LEFT
                val nameY = if (row.subText.isNotBlank()) rect.top + rect.height() * 0.42f else rect.centerY() + 9f
                c.drawText(ellipsize(row.name, p, maxNameW), leftX, nameY, p)

                if (row.subText.isNotBlank()) {
                    p.typeface = Typeface.DEFAULT
                    p.color = subColor
                    p.textSize = 19.5f
                    c.drawText(ellipsize(row.subText, p, maxNameW), leftX, rect.top + rect.height() * 0.77f, p)
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
                        // Theme 1 (Gold): Rounded pill badge around primary stat (R / W)
                        val pillW = 74f
                        val pillH = 52f
                        val pillRect = RectF(
                            colX - pillW / 2f,
                            rect.centerY() - pillH / 2f,
                            colX + pillW / 2f,
                            rect.centerY() + pillH / 2f,
                        )
                        p.style = Paint.Style.FILL
                        if (selected) {
                            p.color = 0xFF1A1208.toInt()
                            c.drawRoundRect(pillRect, 12f, 12f, p)
                            p.color = 0xFFFFE28A.toInt()
                        } else {
                            p.shader = LinearGradient(
                                pillRect.left, pillRect.top, pillRect.right, pillRect.bottom,
                                intArrayOf(0xFFF6D77A.toInt(), 0xFFD4972C.toInt()),
                                null, Shader.TileMode.CLAMP,
                            )
                            c.drawRoundRect(pillRect, 12f, 12f, p)
                            p.shader = null
                            p.color = 0xFF141008.toInt()
                        }
                        p.typeface = Typeface.DEFAULT_BOLD
                        p.textSize = 29f
                        p.textAlign = Paint.Align.CENTER
                        c.drawText(v, colX, rect.centerY() + 10f, p)
                    } else if (isPrimary) {
                        // Theme 0 (Neon): Large bold color-coded stat; Theme 2 (Pop): crisp bold
                        p.typeface = Typeface.DEFAULT_BOLD
                        p.textSize = 34f
                        p.textAlign = Paint.Align.CENTER
                        p.color = when {
                            theme == 0 && selected -> 0xFFFFD400.toInt()
                            theme == 0 -> NEON_ACCENTS[playerIndex % NEON_ACCENTS.size]
                            else -> 0xFF0A1020.toInt()
                        }
                        c.drawText(v, colX, rect.centerY() + 12f, p)
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

    fun loadInnings(context: Context, jsonUri: String, inningsIdx: Int): CdnInnings? {
        val raw = runCatching {
            val uri = Uri.parse(jsonUri)
            val file = File(uri.path ?: jsonUri)
            if (file.exists()) file.readText() else null
        }.getOrNull() ?: return null
        val match = runCatching { CdnScorecardClient.parseJson(raw) }.getOrNull() ?: return null
        return match.innings.getOrNull(inningsIdx) ?: match.innings.firstOrNull()
    }
}
