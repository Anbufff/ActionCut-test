package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * Pure 4K-crisp Vector Renderer for CDN/JSON Cricbuzz Scorecards.
 * Uses the exact same 3 Broadcast Themes, 0px seam, and bouncing arrow from [ScoreCardComposer].
 */
object CdnVectorScoreCardComposer {

    sealed interface VectorRow {
        data class Header(val leftTitle: String, val rightScore: String) : VectorRow
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
        val scoreStr = "${innings.score}-${innings.wickets}$decSuffix (${innings.overs} Ov)"

        return if (type == ScoreCardType.BATTING) {
            buildList {
                add(VectorRow.Header(teamLabel, scoreStr))
                add(VectorRow.Columns("Batter", listOf("R", "B", "4s", "6s", "SR")))
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
                add(VectorRow.Header("$teamLabel BOWLING", scoreStr))
                add(VectorRow.Columns("Bowler", listOf("O", "M", "R", "W", "ECO")))
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
        val out = Bitmap.createBitmap(
            ScoreCardComposer.CANVAS_W.toInt(),
            ScoreCardComposer.CANVAS_H.toInt(),
            Bitmap.Config.ARGB_8888
        )
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val listLeft = ScoreCardComposer.LIST_LEFT
        val listWidth = ScoreCardComposer.LIST_WIDTH
        val listTop = 28f
        val listBottom = ScoreCardComposer.CANVAS_H - 28f
        val listHeight = listBottom - listTop

        val rowHeights = rows.map { r ->
            when (r) {
                is VectorRow.Header -> 72f
                is VectorRow.Columns -> 46f
                is VectorRow.PlayerRow -> if (r.subText.isNotBlank()) 82f else 66f
            }
        }
        val gap = 6f
        val totalH = rowHeights.sum() + gap * max(0, rows.size - 1)

        val cY = if (totalH <= listHeight) {
            listTop + (listHeight - totalH) / 2f
        } else {
            var activeTop = 0f
            for (i in 0 until selected.coerceIn(0, rows.size - 1)) {
                activeTop += rowHeights[i] + gap
            }
            val activeH = rowHeights.getOrElse(selected) { 72f }
            val desiredTop = activeTop - (listHeight - activeH) / 2f
            val maxScroll = totalH - listHeight
            listTop - desiredTop.coerceIn(0f, maxScroll)
        }

        val renderedRowRects = ArrayList<RectF>()
        c.save()
        c.clipRect(listLeft - 10f, listTop - 10f, listLeft + listWidth + 10f, listBottom + 10f)

        var currY = cY
        var playerCounter = 0
        rows.forEachIndexed { i, row ->
            val rh = rowHeights[i]
            val rowRect = RectF(listLeft, currY, listLeft + listWidth, currY + rh)
            renderedRowRects.add(RectF(rowRect))

            val isSel = i == selected
            val isHeader = row is VectorRow.Header
            val isCol = row is VectorRow.Columns
            val accentIdx = if (!isHeader && !isCol) playerCounter++ else 0

            val rInfo = RowInfo(rowRect, 0, 0, isHeader = isHeader, isColumnHeader = isCol)
            ScoreCardComposer.drawRowContainer(c, p, theme, rowRect, rInfo, isSel, accentIdx)
            drawVectorRowText(c, p, rowRect, row, theme, isSel, type)

            currY += rh + gap
        }
        c.restore()

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
        )

        return ComposedCard(out, renderedRowRects)
    }

    private fun drawVectorRowText(
        c: Canvas,
        p: Paint,
        rect: RectF,
        row: VectorRow,
        theme: Int,
        selected: Boolean,
        type: ScoreCardType,
    ) {
        val leftX = rect.left + ScoreCardComposer.TEXT_PAD_LEFT
        val rightX = rect.right - 22f
        val statXs = floatArrayOf(
            rect.right - 340f,
            rect.right - 262f,
            rect.right - 190f,
            rect.right - 122f,
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
            theme == 1 && selected -> 0xFF5C420E.toInt()
            selected && theme == 0 -> 0xFFFFC4CE.toInt()
            theme == 0 -> 0xFF9FB4DC.toInt()
            else -> 0xFFAAB0C2.toInt()
        }
        val highlightStatColor = when {
            theme == 0 && selected -> 0xFFFFD400.toInt()
            theme == 1 && selected -> 0xFF1A1208.toInt()
            theme == 2 -> 0xFF0A1020.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

        p.shader = null
        p.maskFilter = null
        p.style = Paint.Style.FILL
        p.letterSpacing = 0f

        when (row) {
            is VectorRow.Header -> {
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = 0xFFFFFFFF.toInt()
                p.textSize = 31f
                p.textAlign = Paint.Align.LEFT
                c.drawText(row.leftTitle, leftX, rect.centerY() + 11f, p)

                p.textAlign = Paint.Align.RIGHT
                c.drawText(row.rightScore, rightX, rect.centerY() + 11f, p)
            }
            is VectorRow.Columns -> {
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = subColor
                p.textSize = 21f
                p.textAlign = Paint.Align.LEFT
                c.drawText(row.col0, leftX, rect.centerY() + 8f, p)

                p.textAlign = Paint.Align.RIGHT
                row.stats.forEachIndexed { idx, label ->
                    if (idx < statXs.size) {
                        c.drawText(label, statXs[idx], rect.centerY() + 8f, p)
                    }
                }
            }
            is VectorRow.PlayerRow -> {
                val maxNameW = statXs[0] - leftX - 42f
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = mainColor
                p.textSize = 26f
                p.textAlign = Paint.Align.LEFT
                val nameY = if (row.subText.isNotBlank()) rect.top + rect.height() * 0.43f else rect.centerY() + 9f
                c.drawText(ellipsize(row.name, p, maxNameW), leftX, nameY, p)

                if (row.subText.isNotBlank()) {
                    p.typeface = Typeface.DEFAULT
                    p.color = subColor
                    p.textSize = 19.5f
                    c.drawText(ellipsize(row.subText, p, maxNameW), leftX, rect.top + rect.height() * 0.77f, p)
                }

                p.textAlign = Paint.Align.RIGHT
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
                val boldIdx = if (type == ScoreCardType.BATTING) 0 else 3
                allCols.forEachIndexed { idx, v ->
                    if (idx < statXs.size) {
                        p.typeface = if (idx == boldIdx) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        p.color = if (idx == boldIdx) highlightStatColor else mainColor
                        p.textSize = if (idx == boldIdx) 26f else 23f
                        c.drawText(v, statXs[idx], rect.centerY() + 9f, p)
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
