package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

data class ComposedCard(val bitmap: Bitmap, val rowRects: List<RectF>)

object ScoreCardComposer {
    const val CANVAS_W = 1920f
    const val CANVAS_H = 1080f

    fun compose(
        card: RenderedCard,
        type: ScoreCardType,
        selected: Int,
        player: Player?,
        photo: Bitmap?,
        fallbackName: String? = null,
    ): ComposedCard {
        val out = Bitmap.createBitmap(CANVAS_W.toInt(), CANVAS_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // 1. மைதானப் புல்வெளி பின்னணி
        p.shader = LinearGradient(0f, 0f, 0f, CANVAS_H, 0xFF0E2E11.toInt(), 0xFF050F06.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, CANVAS_W, CANVAS_H, p)
        p.shader = null

        p.color = 0x0DFFFFFF
        p.strokeWidth = 35f
        var sx = -CANVAS_H
        while (sx < CANVAS_W + CANVAS_H) {
            c.drawLine(sx, CANVAS_H, sx + CANVAS_H * 0.8f, 0f, p)
            sx += 70f
        }

        // 2. இடதுபுறம்: ஸ்கோர்கார்டு வரிகள்
        val listLeft = 32f
        val listWidth = 835f
        val listTop = 32f
        val listBottom = CANVAS_H - 32f
        val listHeight = listBottom - listTop

        val cardW = card.bitmap.width.toFloat()
        val rowHeights = card.rows.map { r ->
            val rh = (r.srcBottom - r.srcTop).toFloat()
            listWidth * (rh / cardW)
        }
        val gap = 6f
        val totalH = rowHeights.sum() + gap * (card.rows.size - 1)

        val cY = if (totalH <= listHeight) {
            listTop + (listHeight - totalH) / 2f
        } else {
            var activeTop = 0f
            for (i in 0 until selected.coerceIn(0, card.rows.size - 1)) {
                activeTop += rowHeights[i] + gap
            }
            val activeH = rowHeights.getOrElse(selected) { 40f }
            val desiredTop = activeTop - (listHeight - activeH) / 2f
            val maxScroll = totalH - listHeight
            val scrollOffset = desiredTop.coerceIn(0f, maxScroll)
            listTop - scrollOffset
        }

        val renderedRowRects = ArrayList<RectF>()
        c.save()
        c.clipRect(listLeft - 10f, listTop, listLeft + listWidth + 20f, listBottom)

        var currY = cY
        card.rows.forEachIndexed { i, rInfo ->
            val rh = rowHeights[i]
            val rowRect = RectF(listLeft, currY, listLeft + listWidth, currY + rh)
            renderedRowRects.add(RectF(rowRect))

            val srcRect = Rect(
                rInfo.rect.left.toInt(),
                rInfo.rect.top.toInt(),
                rInfo.rect.right.toInt(),
                rInfo.rect.bottom.toInt()
            )
            val dstRect = RectF(rowRect)

            c.drawBitmap(card.bitmap, srcRect, dstRect, bmpPaint)

            // தேர்ந்தெடுக்கப்பட்ட வரியின் சிவப்புக் கோடு
            if (i == selected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 5f
                p.color = 0xFFFF1744.toInt()
                c.drawRoundRect(rowRect, 10f, 10f, p)
                p.style = Paint.Style.FILL
            }

            currY += rh + gap
        }
        c.restore()

        // 3. வலதுபுற பிளேயர் பேனல்
        val pl = 902f
        val pr = CANVAS_W - 32f
        val pt = 32f
        val pb = CANVAS_H - 32f
        val pw = pr - pl
        val ph = pb - pt
        val panelRect = RectF(pl, pt, pr, pb)
        val corner = 20f

        p.shader = RadialGradient(
            pl + pw * 0.5f, pt + ph * 0.25f, pw * 0.8f,
            intArrayOf(0xFF183B6B.toInt(), 0xFF0C1C38.toInt(), 0xFF030814.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRoundRect(panelRect, corner, corner, p)
        p.shader = null

        c.save()
        c.clipPath(Path().apply { addRoundRect(panelRect, corner, corner, Path.Direction.CW) })

        val playerBmp = photo ?: if (type == ScoreCardType.BOWLING) {
            CricketAssets.getBowlerLogo()
        } else {
            CricketAssets.getBatterLogo()
        }

        if (playerBmp.width > 0 && playerBmp.height > 0) {
            val scale = max(pw / playerBmp.width, ph / playerBmp.height)
            val dw = playerBmp.width * scale
            val dh = playerBmp.height * scale
            val dl = pl + (pw - dw) / 2f
            c.drawBitmap(playerBmp, null, RectF(dl, pt, dl + dw, pt + dh), bmpPaint)
        }

        val barTop = pb - ph * 0.32f
        p.shader = LinearGradient(0f, barTop, 0f, pb, 0x00000000, 0xFA02060F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(pl, barTop, pr, pb, p)
        p.shader = null

        val displayName = (player?.name ?: fallbackName ?: if (type == ScoreCardType.BOWLING) "BOWLER" else "BATTER").trim()
        val tagText = if (type == ScoreCardType.BOWLING) "KEY BOWLER" else "KEY BATSMAN"

        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        p.color = 0xFF00E5FF.toInt()
        p.textSize = ph * 0.034f
        p.letterSpacing = 0.15f
        c.drawText(tagText, pl + pw * 0.05f, pb - ph * 0.13f, p)

        p.color = 0xFFFFFFFF.toInt()
        p.textSize = ph * 0.068f
        p.letterSpacing = 0.04f
        val nm = displayName.uppercase()
        while (p.measureText(nm) > pw * 0.88f && p.textSize > 18f) {
            p.textSize -= 2f
        }
        c.drawText(nm, pl + pw * 0.05f, pb - ph * 0.062f, p)
        p.letterSpacing = 0f

        p.color = 0xFFFFCC00.toInt()
        c.drawRoundRect(RectF(pl + pw * 0.05f, pb - ph * 0.035f, pl + pw * 0.32f, pb - ph * 0.027f), 3f, 3f, p)
        p.color = 0xFF00E5FF.toInt()
        c.drawRoundRect(RectF(pl + pw * 0.34f, pb - ph * 0.035f, pr - pw * 0.05f, pb - ph * 0.029f), 2f, 2f, p)

        val bw = pw * 0.30f
        val bh = ph * 0.055f
        val bx = pl + pw * 0.035f
        val by = pt + ph * 0.035f
        p.color = 0xFFD50000.toInt()
        c.drawRoundRect(RectF(bx, by, bx + bw, by + bh), 6f, 6f, p)
        p.color = 0xFFFFE600.toInt()
        c.drawRect(bx, by, bx + 5f, by + bh, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textAlign = Paint.Align.CENTER
        p.textSize = bh * 0.58f
        c.drawText("⚡ NEWS CRICK", bx + bw / 2f + 2f, by + bh * 0.72f, p)

        c.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = 0xFF00E5FF.toInt()
        c.drawRoundRect(panelRect, corner, corner, p)
        p.style = Paint.Style.FILL

        // 4. HTML எடிட்டரில் உள்ள அசல் 3D Red Arrow Mark டிசைன்
        if (selected in renderedRowRects.indices) {
            val targetRow = renderedRowRects[selected]
            val cy = targetRow.centerY()
            val tipX = targetRow.right + 4f
            val tailX = pl + pw * 0.12f // வலது பேனலுக்குள் நுழையும் வால் பகுதி
            val arrowH = min(targetRow.height() * 0.95f, 75f)
            val headLen = arrowH * 0.82f
            val stemH = arrowH * 0.36f

            val arrowPath = Path().apply {
                moveTo(tipX, cy)
                lineTo(tipX + headLen, cy - arrowH / 2f)
                lineTo(tipX + headLen, cy - stemH / 2f)
                lineTo(tailX, cy - stemH / 2f)
                lineTo(tailX, cy + stemH / 2f)
                lineTo(tipX + headLen, cy + stemH / 2f)
                lineTo(tipX + headLen, cy + arrowH / 2f)
                close()
            }

            // கருப்பு நிழல் மற்றும் அவுட்லைன் (3D Border)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 6f
            p.color = 0xFF000000.toInt()
            c.drawPath(arrowPath, p)

            // அசல் வைப்ரண்ட் சிவப்பு நிற நிரப்பல் (Pure Red Fill)
            p.style = Paint.Style.FILL
            p.color = 0xFFFF1744.toInt()
            c.drawPath(arrowPath, p)
        }

        return ComposedCard(out, renderedRowRects)
    }
}
