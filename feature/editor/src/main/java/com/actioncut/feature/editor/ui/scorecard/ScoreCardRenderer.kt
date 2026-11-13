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
    /** The coloured innings/title bar (team name + score). */
    val isHeader: Boolean = false,
    /** The slim "Batter  R  B  4s  6s  SR" column-title strip. */
    val isColumnHeader: Boolean = false,
)

/**
 * The rendered rows card plus where each row sits.
 * [bitmap] holds only the row TEXT on a transparent background (the row containers are drawn by
 * [ScoreCardComposer] for the chosen design). [selectedBitmap] is the same text recoloured for the
 * highlighted row when that design needs different colours there (null = use [bitmap]).
 */
data class RenderedCard(val bitmap: Bitmap, val rows: List<RowInfo>, val selectedBitmap: Bitmap? = null)

/** Which kind of scoreboard the screenshot is. Only changes the accent colour for now. */
enum class ScoreCardType(val label: String, val accent: Int) {
    BATTING("SCORE", 0xFF00E5FF.toInt()),
    BOWLING("BOWL", 0xFF76FF03.toInt()),
}

/**
 * Turns a cropped scoreboard screenshot into the TEXT layer of a broadcast-style card:
 * rows are split at the separator lines (see [ScoreRowSplitter]), each row has its white
 * background punched out (a coloured header bar keeps only its text), and for the dark designs the
 * text is re-painted light. The row containers (glass / gold / pop) are drawn by [ScoreCardComposer].
 * The result is a transparent-background ARGB bitmap.
 */
object ScoreCardRenderer {

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Colours used to re-paint the (originally dark) row text on dark row designs. */
    private class TextScheme(val name: Int, val number: Int, val muted: Int)

    /** null = keep the screenshot's own (dark) text colours, used on light row designs. */
    private fun schemeFor(themeIdx: Int, selected: Boolean): TextScheme? = when (ScoreCardComposer.normTheme(themeIdx)) {
        0 -> if (selected) {
            TextScheme(WHITE, 0xFFFFD400.toInt(), 0xFFFFB3C0.toInt())
        } else {
            TextScheme(WHITE, WHITE, 0xFF8DA2CC.toInt())
        }
        1 -> if (selected) {
            TextScheme(0xFF1A1208.toInt(), 0xFF1A1208.toInt(), 0xFF6B4E12.toInt())
        } else {
            TextScheme(WHITE, WHITE, 0xFF9A9AA8.toInt())
        }
        else -> null
    }

    /**
     * @param startFraction / [endFraction] vertical crop lines as fractions (0..1) of [source] height.
     */
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

        // 1) where every row sits + what kind of row it is
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

        // 2) draw the row text only (transparent background), optionally recoloured
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

    /**
     * Dark text on a (made-transparent) white row -> light text for dark row designs.
     * Opacity comes from how dark the pixel was, so anti-aliased edges stay smooth and the pale
     * background haze disappears. Blue = names, near-black = numbers, grey = dismissal text.
     */
    private fun recolorForDark(px: IntArray, scheme: TextScheme) {
        for (i in px.indices) {
            val p = px[i]
            val a0 = (p ushr 24) and 0xFF
            if (a0 == 0) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val coverage = ((235 - lum) * 255 / 150).coerceIn(0, 255)
            val alpha = coverage * a0 / 255
            if (alpha <= 6) {
                px[i] = 0
                continue
            }
            val target = when {
                b - r > 40 -> scheme.name
                lum < 90 -> scheme.number
                else -> scheme.muted
            }
            px[i] = (alpha shl 24) or (target and 0x00FFFFFF)
        }
    }

    /** A row whose left/right edge is clearly tinted (e.g. the green innings header). */
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

    /** Near-white -> transparent so the pastel gradient behind shows through; soft edge for AA text. */
    private fun whiteToTransparent(px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            val m = minOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            if (m >= 246) {
                px[i] = 0
            } else if (m > 214) {
                val a = (246 - m) * 255 / 32
                px[i] = (a shl 24) or (p and 0x00FFFFFF)
            }
        }
    }

    /** Remove the row's own flat background colour (keep the text) so the navy gradient shows. */
    private fun removeBackground(px: IntArray, w: Int, h: Int) {
        val (br, bg, bb, _) = edgeAverage(px, w, h)
        for (i in px.indices) {
            val p = px[i]
            val d = abs(((p shr 16) and 0xFF) - br) + abs(((p shr 8) and 0xFF) - bg) + abs((p and 0xFF) - bb)
            if (d < 60) {
                px[i] = 0
            } else if (d < 120) {
                val a = (d - 60) * 255 / 60
                px[i] = (a shl 24) or (p and 0x00FFFFFF)
            }
        }
    }
}
