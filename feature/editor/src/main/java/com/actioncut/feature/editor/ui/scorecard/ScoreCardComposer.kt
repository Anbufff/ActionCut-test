package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Final 16:9 card and the row rectangles (in its pixel coordinates) for tap hit-testing. */
data class ComposedCard(val bitmap: Bitmap, val rowRects: List<RectF>)

/**
 * Broadcast layout like the web editor: rows list on the left, a full-height player photo panel
 * on the right ("IN FOCUS" badge, name bar, cyan border) and a red arrow from the selected row
 * pointing into the photo.
 */
object ScoreCardComposer {
    private const val MAX_W = 3840f

    fun compose(
        card: RenderedCard,
        type: ScoreCardType,
        selected: Int,
        player: Player?,
        photo: Bitmap?,
        fallbackName: String? = null,
    ): ComposedCard {
        val cw = card.bitmap.width.toFloat()
        val ch = card.bitmap.height.toFloat()
        val lw = cw / 0.43f
        val lh = max(ch + lw * 0.03f, lw * 9f / 16f)
        val s = min(1f, MAX_W / lw)
        val out = Bitmap.createBitmap(
            (lw * s).roundToInt().coerceAtLeast(1),
            (lh * s).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(out)
        c.scale(s, s)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Pitch-green backdrop
        val bg = RectF(0f, 0f, lw, lh)
        p.shader = LinearGradient(0f, 0f, 0f, lh, 0xFF0E2E11.toInt(), 0xFF050F06.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(bg, lw * 0.01f, lw * 0.01f, p)
        p.shader = null

        // Rows (left, vertically centred)
        val ox = lw * 0.012f
        val oy = (lh - ch) / 2f
        c.drawBitmap(card.bitmap, ox, oy, bmpPaint)
        val rects = card.rows.map {
            RectF(it.rect.left + ox, it.rect.top + oy, it.rect.right + ox, it.rect.bottom + oy)
        }

        // Selected row highlight
        if (selected in rects.indices) {
            val r = rects[selected]
            p.style = Paint.Style.STROKE
            p.strokeWidth = lw / 500f
            p.color = 0xFFFF1744.toInt()
            p.strokeWidth = lw / 380f
            c.drawRoundRect(r, lw / 200f, lw / 200f, p)
            p.style = Paint.Style.FILL
        }

        // Photo panel (right)
        val pl = lw * 0.47f
        val pr = lw * 0.988f
        val pt = lh * 0.015f
        val pb = lh * 0.985f
        val pw = pr - pl
        val ph = pb - pt
        val panel = RectF(pl, pt, pr, pb)
        val corner = lw / 120f
        p.shader = RadialGradient(
            pl + pw / 2f, pt + ph * 0.25f, pw * 0.8f,
            intArrayOf(0xFF183B6B.toInt(), 0xFF0C1C38.toInt(), 0xFF030814.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(panel, corner, corner, p)
        p.shader = null

        c.save()
        c.clipPath(Path().apply { addRoundRect(panel, corner, corner, Path.Direction.CW) })
        if (photo != null && photo.width > 0 && photo.height > 0) {
            val scale = max(pw / photo.width, ph / photo.height)
            val dw = photo.width * scale
            val dh = photo.height * scale
            val dl = pl + (pw - dw) / 2f
            c.drawBitmap(photo, null, RectF(dl, pt, dl + dw, pt + dh), bmpPaint)
        } else {
            // Placeholder silhouette
            p.color = 0x33FFFFFF
            c.drawCircle(pl + pw / 2f, pt + ph * 0.36f, pw * 0.09f, p)
            c.drawOval(RectF(pl + pw * 0.34f, pt + ph * 0.50f, pl + pw * 0.66f, pt + ph * 0.95f), p)
        }

        // Name bar (rising dark vignette + name + gold line)
        val barTop = pb - ph * 0.24f
        p.shader = LinearGradient(0f, barTop, 0f, pb, 0x00000000, 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(pl, barTop, pr, pb, p)
        p.shader = null
        val shownName = player?.name ?: fallbackName
        if (!shownName.isNullOrBlank()) {
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.LEFT
            // "KEY BATSMAN" / "KEY BOWLER" label (as in the web editor)
            p.color = type.accent
            p.textSize = ph * 0.034f
            p.letterSpacing = 0.18f
            c.drawText(if (type == ScoreCardType.BOWLING) "KEY BOWLER" else "KEY BATSMAN", pl + pw * 0.05f, pb - ph * 0.125f, p)
            p.letterSpacing = 0.04f
            p.color = 0xFFFFFFFF.toInt()
            p.textSize = ph * 0.065f
            val nm = shownName.uppercase()
            while (p.measureText(nm) > pw * 0.9f && p.textSize > 8f) p.textSize -= 2f
            c.drawText(nm, pl + pw * 0.05f, pb - ph * 0.06f, p)
            p.letterSpacing = 0f
        }
        p.color = 0xFFFF6D00.toInt()
        c.drawRect(pl + pw * 0.05f, pb - ph * 0.032f, pl + pw * 0.30f, pb - ph * 0.022f, p)
        p.color = type.accent
        c.drawRect(pl + pw * 0.32f, pb - ph * 0.032f, pl + pw * 0.95f, pb - ph * 0.022f, p)

        // "IN FOCUS" badge
        val bw = pw * 0.2f
        val bh = ph * 0.065f
        val bx = pl + pw * 0.03f
        val by = pt + ph * 0.04f
        p.color = 0xFFE53935.toInt()
        c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 4f, bh / 4f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        p.textSize = bh * 0.55f
        c.drawText("NEWS CRICK", bx + bw / 2f, by + bh * 0.7f, p)
        c.restore()

        // Panel border
        p.style = Paint.Style.STROKE
        p.strokeWidth = lw / 450f
        p.color = type.accent
        c.drawRoundRect(panel, corner, corner, p)
        p.style = Paint.Style.FILL

        // Arrow: from the photo, pointing LEFT at the selected row (as in the web editor)
        if (selected in rects.indices) {
            val r = rects[selected]
            val cy = r.centerY()
            val ah = min(r.height() * 0.95f, lh * 0.15f)
            val tipX = r.right + lw * 0.003f
            val tailX = pl + lw * 0.075f
            val headLen = ah * 0.85f
            val half = ah * 0.2f
            val arrow = Path().apply {
                moveTo(tipX, cy)
                lineTo(tipX + headLen, cy - ah / 2f)
                lineTo(tipX + headLen, cy - half)
                lineTo(tailX, cy - half)
                lineTo(tailX, cy + half)
                lineTo(tipX + headLen, cy + half)
                lineTo(tipX + headLen, cy + ah / 2f)
                close()
            }
            p.color = 0xFFFF1744.toInt()
            c.drawPath(arrow, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ah * 0.07f
            p.color = 0xFF111111.toInt()
            c.drawPath(arrow, p)
            p.style = Paint.Style.FILL
        }

        return ComposedCard(out, rects.map { RectF(it.left * s, it.top * s, it.right * s, it.bottom * s) })
    }
}
