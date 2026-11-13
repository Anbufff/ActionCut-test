package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.actioncut.core.domain.scorecard.ScoreRowSplitter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One scoreboard row: its rectangle inside the rendered card, and its pixel range in the source screenshot. */
data class RowInfo(
    val rect: RectF,
    val srcTop: Int,
    val srcBottom: Int,
    val isHeader: Boolean = false,
    val isColumnHeader: Boolean = false,
)

data class RenderedCard(val bitmap: Bitmap, val rows: List<RowInfo>, val selectedBitmap: Bitmap? = null)

enum class ScoreCardType(val label: String, val accent: Int) {
    BATTING("SCORE", 0xFF00E5FF.toInt()),
    BOWLING("BOWL", 0xFF76FF03.toInt()),
}

object ScoreCardRenderer {

    private const val WHITE = 0xFFFFFFFF.toInt()

    private class TextScheme(val name: Int, val number: Int, val muted: Int)

    private fun schemeFor(themeIdx: Int, selected: Boolean): TextScheme? = when (ScoreCardComposer.normTheme(themeIdx)) {
        0 -> if (selected) {
            TextScheme(WHITE, 0xFFFFD400.toInt(), 0xFFFFC4CE.toInt())
        } else {
            TextScheme(WHITE, WHITE, 0xFF9FB4DC.toInt())
        }
        1 -> if (selected) {
            TextScheme(0xFF1A1208.toInt(), 0xFF1A1208.toInt(), 0xFF5C420E.toInt())
        } else {
            TextScheme(WHITE, WHITE, 0xFFAAB0C2.toInt())
        }
        else -> null
    }

    fun render(
        source: Bitmap,
        startFraction: Float,
        endFraction: Float,
        type: ScoreCardType,
        themeIdx: Int = 0,
    ): Bitmap = renderWithRows(source, startFraction, endFraction, type, themeIdx).bitmap

    fun renderWithRows(
        source: Bitmap,
        startFraction: Float,
        endFraction: Float,
        type: ScoreCardType,
        themeIdx: Int = 0,
    ): RenderedCard {
        val w = source.width
        val top = (source.height * min(startFraction, endFraction)).toInt()
            .coerceIn(0, source.height - 2)
        val bottom = (source.height * max(startFraction, endFraction)).toInt()
            .coerceIn(top + 2, source.height)
        val regionH = bottom - top

        val region = IntArray(w * regionH)
        source.getPixels(region, 0, w, 0, top, w, regionH)
        val rows = ScoreRowSplitter.rows(region, w, regionH, source.height)

        val gap = max(4, w / 200)
        val pad = max(6, w / 90)
        val rowHeights = rows.map { it.last - it.first + 1 }
        val totalRowsH = rowHeights.sum()
        val outW = w + pad * 2
        val outH = totalRowsH + gap * (rows.size - 1) + pad * 2
        val median = if (rowHeights.isEmpty()) 0 else rowHeights.sorted()[rowHeights.size / 2]

        val isHeaderRow = BooleanArray(rows.size)
        val infos = ArrayList<RowInfo>()
        var y = pad
        rows.forEachIndexed { index, range ->
            val rowH = rowHeights[index]
            val rowPixels = IntArray(w * rowH)
            System.arraycopy(region, range.first * w, rowPixels, 0, w * rowH)
            val isHeader = hasColoredBackground(rowPixels, w, rowH)
            isHeaderRow[index] = isHeader
            val isColumnHeader = !isHeader && median > 0 && rowH < median * 0.7f
            val rect = RectF(pad.toFloat(), y.toFloat(), (pad + w).toFloat(), (y + rowH).toFloat())
            infos.add(RowInfo(rect, top + range.first, top + range.last + 1, isHeader, isColumnHeader))
            y += rowH + gap
        }

        fun drawText(scheme: TextScheme?): Bitmap {
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
            rows.forEachIndexed { index, range ->
                val rowH = rowHeights[index]
                val rowPixels = IntArray(w * rowH)
                System.arraycopy(region, range.first * w, rowPixels, 0, w * rowH)
                if (isHeaderRow[index]) {
                    removeBackground(rowPixels, w, rowH)
                } else {
                    whiteToTransparent(rowPixels)
                    if (scheme != null) recolorForDark(rowPixels, scheme)
                }
                val rowBitmap = Bitmap.createBitmap(rowPixels, w, rowH, Bitmap.Config.ARGB_8888)
                val rect = infos[index].rect
                canvas.drawBitmap(rowBitmap, rect.left, rect.top, bmpPaint)
                rowBitmap.recycle()
            }
            return out
        }

        val normalScheme = schemeFor(themeIdx, selected = false)
        val selectedScheme = schemeFor(themeIdx, selected = true)
        val bitmap = drawText(normalScheme)
        val selectedBitmap = if (selectedScheme != null) drawText(selectedScheme) else null
        return RenderedCard(bitmap, infos, selectedBitmap)
    }

    private fun recolorForDark(px: IntArray, scheme: TextScheme) {
        for (i in px.indices) {
            val p = px[i]
            val a0 = (p ushr 24) and 0xFF
            if (a0 == 0) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val coverage = ((230 - lum) * 255 / 135).coerceIn(0, 255)
            val alpha = coverage * a0 / 255
            if (alpha <= 6) {
                px[i] = 0
                continue
            }
            val target = when {
                b - r > 35 -> scheme.name
                lum < 95 -> scheme.number
                else -> scheme.muted
            }
            px[i] = (alpha shl 24) or (target and 0x00FFFFFF)
        }
    }

    private fun hasColoredBackground(px: IntArray, w: Int, h: Int): Boolean {
        val (r, g, b, n) = edgeAverage(px, w, h)
        if (n == 0) return false
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        return spread > 40
    }

    private data class Rgbn(val r: Int, val g: Int, val b: Int, val n: Int)

    private fun edgeAverage(px: IntArray, w: Int, h: Int): Rgbn {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        val xs = intArrayOf(1, 2, 3, 4, w - 5, w - 4, w - 3, w - 2)
        var y = 0
        while (y < h) {
            for (x in xs) {
                if (x < 0 || x >= w) continue
                val p = px[y * w + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
            y += 2
        }
        if (n == 0) return Rgbn(255, 255, 255, 0)
        return Rgbn((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), n)
    }

    private fun whiteToTransparent(px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val m = minOf(r, g, b)
            val spread = maxOf(r, g, b) - m
            if (m >= 244 || (m >= 224 && spread < 12)) {
                px[i] = 0
            } else if (m > 205 && spread < 14) {
                val a = (224 - m).coerceAtLeast(0) * 255 / 19
                px[i] = (a shl 24) or (p and 0x00FFFFFF)
            }
        }
    }

    private fun removeBackground(px: IntArray, w: Int, h: Int) {
        val (br, bg, bb, _) = edgeAverage(px, w, h)
        val bgSum = br + bg + bb
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val d = abs(r - br) + abs(g - bg) + abs(b - bb)
            if (d < 55) {
                px[i] = 0
            } else {
                val alpha = ((d - 55) * 255 / 140).coerceIn(0, 255)
                val outRgb = if (r + g + b > bgSum + 30) 0x00FFFFFF else (p and 0x00FFFFFF)
                px[i] = (alpha shl 24) or outRgb
            }
        }
    }
}
