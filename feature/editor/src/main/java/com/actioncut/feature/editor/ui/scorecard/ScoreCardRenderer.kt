package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.actioncut.core.domain.scorecard.ScoreRowSplitter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which kind of scoreboard the screenshot is. Only changes the accent colour for now. */
enum class ScoreCardType(val label: String, val accent: Int) {
    BATTING("SCORE", 0xFF00E5FF.toInt()),
    BOWLING("BOWL", 0xFF76FF03.toInt()),
}

/**
 * Turns a cropped scoreboard screenshot into a broadcast-style card:
 * rows are split at the separator lines (see [ScoreRowSplitter]), each light row has its white
 * background punched out and is laid on its own pastel gradient pill with a coloured accent bar,
 * and a coloured header bar (e.g. the green innings title) is re-tinted to a deep navy gradient.
 * The result is a transparent-background ARGB bitmap.
 */
object ScoreCardRenderer {

    private val ROW_THEMES = intArrayOf(
        0xFFFFE3EF.toInt(), // pink
        0xFFDDF4FF.toInt(), // sky
        0xFFFFF2CC.toInt(), // sun
        0xFFE2FBD9.toInt(), // mint
        0xFFEBDFFF.toInt(), // lavender
        0xFFFFE5D1.toInt(), // peach
    )
    private const val HEADER_FROM = 0xFF0B1F4B.toInt()
    private const val HEADER_TO = 0xFF1E4FD8.toInt()

    /**
     * @param startFraction / [endFraction] vertical crop lines as fractions (0..1) of [source] height.
     */
    fun render(source: Bitmap, startFraction: Float, endFraction: Float, type: ScoreCardType): Bitmap {
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
        val accentW = max(6, w / 110).toFloat()
        val totalRowsH = rows.sumOf { it.last - it.first + 1 }
        val outW = w + pad * 2
        val outH = totalRowsH + gap * (rows.size - 1) + pad * 2

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        var y = pad
        rows.forEachIndexed { index, range ->
            val rowH = range.last - range.first + 1
            val rowPixels = IntArray(w * rowH)
            System.arraycopy(region, range.first * w, rowPixels, 0, w * rowH)

            val isHeader = hasColoredBackground(rowPixels, w, rowH)
            val rect = RectF(pad.toFloat(), y.toFloat(), (pad + w).toFloat(), (y + rowH).toFloat())
            val radius = min(rowH * 0.3f, max(10f, w / 40f))

            val gradFrom: Int
            val gradTo: Int
            if (isHeader) {
                gradFrom = HEADER_FROM
                gradTo = HEADER_TO
                removeBackground(rowPixels, w, rowH)
            } else {
                gradFrom = ROW_THEMES[index % ROW_THEMES.size]
                gradTo = blendWithWhite(gradFrom, 0.45f)
                whiteToTransparent(rowPixels)
            }

            val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)

            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, gradFrom, gradTo, Shader.TileMode.CLAMP)
            canvas.drawRect(rect, paint)
            paint.shader = null

            val rowBitmap = Bitmap.createBitmap(rowPixels, w, rowH, Bitmap.Config.ARGB_8888)
            canvas.drawBitmap(rowBitmap, rect.left, rect.top, bmpPaint)
            rowBitmap.recycle()

            paint.color = type.accent
            canvas.drawRect(rect.left, rect.top, rect.left + accentW, rect.bottom, paint)
            canvas.restore()

            y += rowH + gap
        }
        return out
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

    private fun blendWithWhite(color: Int, amount: Float): Int {
        fun ch(shift: Int): Int {
            val c = (color shr shift) and 0xFF
            return (c + (255 - c) * amount).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
