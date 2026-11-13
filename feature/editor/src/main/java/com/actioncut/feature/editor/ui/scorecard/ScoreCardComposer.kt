package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import kotlin.math.sin

data class ComposedCard(val bitmap: Bitmap, val rowRects: List<RectF>)

/**
 * Draws the whole scorecard frame (rows list on the left, key-player panel on the right) in one of
 * three designs: 0 = Neon Night (default), 1 = Royal Gold, 2 = Sunset Pop.
 * The background of the frame is left fully transparent so the match video shows through.
 */
object ScoreCardComposer {
    const val CANVAS_W = 1920f
    const val CANVAS_H = 1080f
    const val THEME_COUNT = 3

    fun normTheme(idx: Int): Int = ((idx % THEME_COUNT) + THEME_COUNT) % THEME_COUNT

    fun themeName(idx: Int): String = when (normTheme(idx)) {
        0 -> "Neon"
        1 -> "Gold"
        else -> "Pop"
    }

    private val NEON_ACCENTS = intArrayOf(
        0xFF00E5FF.toInt(), 0xFFB388FF.toInt(), 0xFF7CFF6B.toInt(), 0xFFFF9F43.toInt(), 0xFFFF6BD6.toInt(),
    )
    private val POP_FROM = intArrayOf(
        0xFF2F80FF.toInt(), 0xFFFF8A00.toInt(), 0xFF8E3BFF.toInt(), 0xFF00B86B.toInt(), 0xFFFF3D9A.toInt(), 0xFFFFB300.toInt(),
    )
    private val POP_TO = intArrayOf(
        0xFF00C2FF.toInt(), 0xFFFF4D4D.toInt(), 0xFFC94BFF.toInt(), 0xFF5BE37D.toInt(), 0xFFFF7AB8.toInt(), 0xFFFF8A00.toInt(),
    )

    fun compose(
        card: RenderedCard,
        type: ScoreCardType,
        selected: Int,
        player: Player?,
        photo: Bitmap?,
        fallbackName: String? = null,
        themeIdx: Int = 0,
    ): ComposedCard {
        val theme = normTheme(themeIdx)
        val out = Bitmap.createBitmap(CANVAS_W.toInt(), CANVAS_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // 1. பின்னணி வெளிப்படையாக (transparent) - அசல் மைதான வீடியோ பின்னால் தெரியும்

        // 2. இடதுபுறம்: ஸ்கோர்கார்டு வரிகள்
        val listLeft = 24f
        val listWidth = 820f
        val listTop = 32f
        val listBottom = CANVAS_H - 32f
        val listHeight = listBottom - listTop

        val rowHeights = card.rows.map { r ->
            val srcW = r.rect.width().coerceAtLeast(1f)
            val srcH = r.rect.height().coerceAtLeast(1f)
            listWidth * (srcH / srcW)
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
        c.clipRect(listLeft - 10f, listTop - 12f, listLeft + listWidth + 20f, listBottom + 12f)

        val selBitmap = card.selectedBitmap
        var currY = cY
        var playerCounter = 0
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

            val isSel = i == selected
            val accentIdx = if (!rInfo.isHeader && !rInfo.isColumnHeader) playerCounter++ else 0

            // வரியின் பின்னணி (தேர்ந்தெடுத்த டிசைனுக்கு ஏற்ப)
            drawRowContainer(c, p, theme, rowRect, rInfo, isSel, accentIdx)

            // வரியின் எழுத்துகள்
            val textBmp = if (isSel && selBitmap != null) selBitmap else card.bitmap
            c.drawBitmap(textBmp, srcRect, dstRect, bmpPaint)

            currY += rh + gap
        }
        c.restore()

        // 3. வலதுபுறம்: பிளேயர் பேனல்
        drawPlayerPanel(c, p, bmpPaint, theme, type, player, photo, fallbackName)

        return ComposedCard(out, renderedRowRects)
    }

    // ------------------------------------------------------------------------------------------
    //  Row containers
    // ------------------------------------------------------------------------------------------

    private fun fillRR(c: Canvas, p: Paint, r: RectF, rad: Float, color: Int) {
        p.shader = null
        p.maskFilter = null
        p.style = Paint.Style.FILL
        p.color = color
        c.drawRoundRect(r, rad, rad, p)
    }

    private fun gradRR(c: Canvas, p: Paint, r: RectF, rad: Float, colors: IntArray, positions: FloatArray?, horizontal: Boolean) {
        p.maskFilter = null
        p.style = Paint.Style.FILL
        p.shader = if (horizontal) {
            LinearGradient(r.left, 0f, r.right, 0f, colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, r.top, 0f, r.bottom, colors, positions, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(r, rad, rad, p)
        p.shader = null
    }

    private fun strokeRR(c: Canvas, p: Paint, r: RectF, rad: Float, color: Int, width: Float) {
        p.shader = null
        p.maskFilter = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.color = color
        c.drawRoundRect(r, rad, rad, p)
        p.style = Paint.Style.FILL
    }

    private fun glowRR(c: Canvas, p: Paint, r: RectF, rad: Float, color: Int, blur: Float, grow: Float, dy: Float) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        val g = RectF(r.left - grow, r.top - grow + dy, r.right + grow, r.bottom + grow + dy)
        c.drawRoundRect(g, rad + grow, rad + grow, p)
        p.maskFilter = null
    }

    private fun drawRowContainer(
        c: Canvas,
        p: Paint,
        theme: Int,
        rect: RectF,
        info: RowInfo,
        selected: Boolean,
        accentIdx: Int,
    ) {
        val h = rect.height()
        val rad = min(h * 0.22f, 22f)
        val barRect = RectF(rect.left + 14f, rect.top + h * 0.2f, rect.left + 21f, rect.bottom - h * 0.2f)
        when (theme) {
            0 -> { // Neon Night
                if (selected) {
                    glowRR(c, p, rect, rad, 0xAAFF2D4A.toInt(), 16f, 2f, 0f)
                    gradRR(c, p, rect, rad, intArrayOf(0xFF58102A.toInt(), 0xFF1B0B33.toInt()), null, true)
                    strokeRR(c, p, rect, rad, 0xFFFF3B5C.toInt(), 4f)
                    fillRR(c, p, barRect, 3.5f, 0xFFFFD400.toInt())
                } else if (info.isHeader) {
                    glowRR(c, p, rect, rad, 0x962563FF.toInt(), 14f, 0f, 0f)
                    gradRR(c, p, rect, rad, intArrayOf(0xFF0A2596.toInt(), 0xFF2B6BFF.toInt()), null, true)
                    fillRR(c, p, RectF(rect.left, rect.top, rect.right, rect.top + h * 0.45f), rad, 0x1AFFFFFF)
                    fillRR(c, p, barRect, 3.5f, 0xFF00E5FF.toInt())
                } else if (info.isColumnHeader) {
                    fillRR(c, p, rect, rad, 0xB90A1430.toInt())
                    strokeRR(c, p, rect, rad, 0x18FFFFFF, 1.5f)
                } else {
                    fillRR(c, p, rect, rad, 0xDE0A1430.toInt())
                    strokeRR(c, p, rect, rad, 0x1EFFFFFF, 1.5f)
                    fillRR(c, p, barRect, 3.5f, NEON_ACCENTS[accentIdx % NEON_ACCENTS.size])
                }
            }
            1 -> { // Royal Gold
                val goldA = 0xFFF6D77A.toInt()
                val goldB = 0xFFC8922A.toInt()
                if (selected) {
                    glowRR(c, p, rect, rad, 0xA0FFC83D.toInt(), 18f, 2f, 0f)
                    gradRR(c, p, rect, rad, intArrayOf(0xFFFBE49A.toInt(), 0xFFD6A03A.toInt()), null, true)
                    strokeRR(c, p, rect, rad, 0xFFFFF6D6.toInt(), 3f)
                    fillRR(c, p, barRect, 3.5f, 0xFF1A1208.toInt())
                } else if (info.isHeader) {
                    glowRR(c, p, rect, rad, 0xA0000000.toInt(), 12f, 0f, 5f)
                    gradRR(c, p, rect, rad, intArrayOf(0xFF6E1429.toInt(), 0xFF2A0710.toInt()), null, true)
                    fillRR(c, p, RectF(rect.left, rect.top, rect.right, rect.top + h * 0.42f), rad, 0x12FFFFFF)
                    gradRR(
                        c, p,
                        RectF(rect.left + 26f, rect.bottom - 6f, rect.right - 26f, rect.bottom - 3f),
                        1.5f,
                        intArrayOf(0x00C8922A, goldA, goldB, 0x00C8922A),
                        floatArrayOf(0f, 0.25f, 0.75f, 1f),
                        true,
                    )
                } else if (info.isColumnHeader) {
                    fillRR(c, p, rect, rad, 0xCD0C0C10.toInt())
                    strokeRR(c, p, rect, rad, 0x50C8922A, 1.5f)
                } else {
                    fillRR(c, p, rect, rad, 0xE4101015.toInt())
                    strokeRR(c, p, rect, rad, 0x55C8922A, 1.5f)
                    gradRR(c, p, barRect, 3.5f, intArrayOf(goldA, goldB), null, false)
                }
            }
            else -> { // Sunset Pop
                val from = POP_FROM[accentIdx % POP_FROM.size]
                val to = POP_TO[accentIdx % POP_TO.size]
                if (selected) {
                    glowRR(c, p, rect, rad, 0xBEFF7A2D.toInt(), 20f, 2f, 0f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFFFFB300.toInt(), 0xFFFF4D4D.toInt(), 0xFFE61E8C.toInt()),
                        floatArrayOf(0f, 0.5f, 1f),
                        true,
                    )
                    val inner = RectF(rect.left + 5f, rect.top + 5f, rect.right - 5f, rect.bottom - 5f)
                    fillRR(c, p, inner, max(rad - 2f, 4f), 0xFFFFFFFF.toInt())
                    gradRR(c, p, barRect, 3.5f, intArrayOf(0xFFFF8A00.toInt(), 0xFFE61E8C.toInt()), null, false)
                } else if (info.isHeader) {
                    glowRR(c, p, rect, rad, 0x96C026D3.toInt(), 16f, 0f, 0f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFF5B2BFF.toInt(), 0xFFC026D3.toInt(), 0xFFFF6A3D.toInt()),
                        floatArrayOf(0f, 0.55f, 1f),
                        true,
                    )
                    fillRR(c, p, RectF(rect.left, rect.top, rect.right, rect.top + h * 0.45f), rad, 0x22FFFFFF)
                } else if (info.isColumnHeader) {
                    fillRR(c, p, rect, rad, 0xE1FFFFFF.toInt())
                } else {
                    glowRR(c, p, rect, rad, 0x5A000000, 10f, 0f, 5f)
                    fillRR(c, p, rect, rad, 0xF0FFFFFF.toInt())
                    gradRR(c, p, barRect, 3.5f, intArrayOf(from, to), null, false)
                }
            }
        }
        p.style = Paint.Style.FILL
        p.shader = null
        p.maskFilter = null
    }

    // ------------------------------------------------------------------------------------------
    //  Right-hand key player panel
    // ------------------------------------------------------------------------------------------

    private fun drawPlayerPanel(
        c: Canvas,
        p: Paint,
        bmpPaint: Paint,
        theme: Int,
        type: ScoreCardType,
        player: Player?,
        photo: Bitmap?,
        fallbackName: String?,
    ) {
        val pl = 960f
        val pr = CANVAS_W - 28f
        val pt = 28f
        val pb = CANVAS_H - 28f
        val pw = pr - pl
        val ph = pb - pt
        val panelRect = RectF(pl, pt, pr, pb)
        val corner = when (theme) {
            0 -> 22f
            1 -> 26f
            else -> 34f
        }

        // வெளிப்புற ஒளி / நிழல்
        when (theme) {
            0 -> glowRR(c, p, panelRect, corner, 0x9600E5FF.toInt(), 18f, 0f, 0f)
            1 -> glowRR(c, p, panelRect, corner, 0xAA000000.toInt(), 16f, 0f, 6f)
            else -> glowRR(c, p, panelRect, corner, 0x96E61E8C.toInt(), 20f, 0f, 0f)
        }

        // பின்னணி
        when (theme) {
            0 -> {
                p.shader = RadialGradient(
                    pl + pw * 0.5f, pt + ph * 0.25f, pw * 0.8f,
                    intArrayOf(0xFF183B6B.toInt(), 0xFF0C1C38.toInt(), 0xFF030814.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawRoundRect(panelRect, corner, corner, p)
                p.shader = null
            }
            1 -> fillRR(c, p, panelRect, corner, 0xFF14070C.toInt())
            else -> fillRR(c, p, panelRect, corner, 0xFF1A0B3B.toInt())
        }

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

        // படத்தின் மேல் இருள் படலம்
        when (theme) {
            0 -> {
                val barTop = pb - ph * 0.32f
                p.shader = LinearGradient(0f, barTop, 0f, pb, 0x00000000, 0xFA02060F.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(pl, barTop, pr, pb, p)
                p.shader = null
            }
            1 -> {
                p.shader = RadialGradient(
                    pl + pw / 2f, pt + ph / 2f, max(pw, ph) * 0.75f,
                    intArrayOf(0x00000000, 0x00000000, 0xAA000000.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawRect(panelRect, p)
                val barTop = pb - ph * 0.40f
                p.shader = LinearGradient(0f, barTop, 0f, pb, 0x00000000, 0xFC0E0408.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(pl, barTop, pr, pb, p)
                p.shader = null
            }
            else -> {
                val barTop = pb - ph * 0.38f
                p.shader = LinearGradient(0f, barTop, 0f, pb, 0x00000000, 0xF81A0B3B.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(pl, barTop, pr, pb, p)
                p.shader = null
            }
        }

        val displayName = (player?.name ?: fallbackName ?: if (type == ScoreCardType.BOWLING) "BOWLER" else "BATTER").trim()
        val tagText = if (type == ScoreCardType.BOWLING) "KEY BOWLER" else "KEY BATSMAN"

        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        p.color = when (theme) {
            0 -> 0xFF00E5FF.toInt()
            1 -> 0xFFF6D77A.toInt()
            else -> 0xFFFFD84D.toInt()
        }
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

        // பெயருக்குக் கீழ் கோடுகள்
        when (theme) {
            0 -> {
                fillRR(c, p, RectF(pl + pw * 0.05f, pb - ph * 0.035f, pl + pw * 0.32f, pb - ph * 0.027f), 3f, 0xFFFFCC00.toInt())
                fillRR(c, p, RectF(pl + pw * 0.34f, pb - ph * 0.035f, pr - pw * 0.05f, pb - ph * 0.029f), 2f, 0xFF00E5FF.toInt())
            }
            1 -> gradRR(
                c, p,
                RectF(pl + pw * 0.05f, pb - ph * 0.036f, pr - pw * 0.05f, pb - ph * 0.030f),
                3f,
                intArrayOf(0xFFF6D77A.toInt(), 0xFFC8922A.toInt(), 0x00C8922A),
                floatArrayOf(0f, 0.6f, 1f),
                true,
            )
            else -> {
                fillRR(c, p, RectF(pl + pw * 0.05f, pb - ph * 0.037f, pl + pw * 0.30f, pb - ph * 0.029f), 4f, 0xFFFF8A00.toInt())
                fillRR(c, p, RectF(pl + pw * 0.32f, pb - ph * 0.037f, pr - pw * 0.05f, pb - ph * 0.031f), 3f, 0xFFE61E8C.toInt())
            }
        }

        // மேலே "NEWS CRICK" பேட்ஜ்
        val bw = pw * 0.30f
        val bh = ph * 0.055f
        val bx = pl + pw * 0.035f
        val by = pt + ph * 0.035f
        val badge = RectF(bx, by, bx + bw, by + bh)
        val badgeTextColor: Int
        when (theme) {
            0 -> {
                fillRR(c, p, badge, 6f, 0xFFD50000.toInt())
                p.color = 0xFFFFE600.toInt()
                c.drawRect(bx, by, bx + 5f, by + bh, p)
                badgeTextColor = 0xFFFFFFFF.toInt()
            }
            1 -> {
                gradRR(c, p, badge, bh / 2f, intArrayOf(0xFFF6D77A.toInt(), 0xFFC8922A.toInt()), null, true)
                badgeTextColor = 0xFF2A0710.toInt()
            }
            else -> {
                fillRR(c, p, badge, bh / 2f, 0xFFFFFFFF.toInt())
                badgeTextColor = 0xFFB0126E.toInt()
            }
        }
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = badgeTextColor
        p.textAlign = Paint.Align.CENTER
        p.textSize = bh * 0.58f
        c.drawText("⚡ NEWS CRICK", bx + bw / 2f + 2f, by + bh * 0.72f, p)

        c.restore()

        // பேனல் சட்டம் (frame)
        when (theme) {
            0 -> strokeRR(c, p, panelRect, corner, 0xFF00E5FF.toInt(), 5f)
            1 -> {
                strokeRR(c, p, panelRect, corner, 0xFFF6D77A.toInt(), 5f)
                val inner = RectF(pl + 12f, pt + 12f, pr - 12f, pb - 12f)
                strokeRR(c, p, inner, corner - 6f, 0x6EF6D77A, 1.5f)
            }
            else -> strokeRR(c, p, panelRect, corner, 0xFFFFFFFF.toInt(), 6f)
        }
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
    }

    /**
     * தேர்ந்தெடுத்த வரியின் வலது பக்கம் முன்னும் பின்னும் துடிக்கும் பெரிய அம்பு.
     * ஒவ்வொரு டிசைனுக்கும் தனி நிறம்.
     */
    fun drawBouncingArrow(canvas: Canvas, targetRow: RectF, timeInClipMs: Long, themeIdx: Int = 0) {
        val theme = normTheme(themeIdx)
        val cy = targetRow.centerY()

        // 0.75 நொடி இடைவெளியில் முன்னும் பின்னும் 22 பிக்சல் அளவுக்குத் துடிக்கும்
        val bounce = ((1.0 - sin(timeInClipMs * 2.0 * Math.PI / 750.0)) / 2.0 * 22.0).toFloat()
        val tipX = targetRow.right + 4f + bounce

        // பெரிய அம்பு அளவுகள் (128px அகலம், 84px உயரம்)
        val arrowW = 128f
        val arrowH = 84f
        val headLen = 52f
        val halfHeadH = arrowH / 2f
        val halfStemH = 17f
        val tailX = tipX + arrowW

        val arrowPath = Path().apply {
            moveTo(tipX, cy)
            lineTo(tipX + headLen, cy - halfHeadH)
            lineTo(tipX + headLen, cy - halfStemH)
            lineTo(tailX, cy - halfStemH)
            lineTo(tailX, cy + halfStemH)
            lineTo(tipX + headLen, cy + halfStemH)
            lineTo(tipX + headLen, cy + halfHeadH)
            close()
        }

        val fillColor: Int
        val outlineColor: Int
        val glowColor: Int
        when (theme) {
            0 -> {
                fillColor = 0xFFFF1030.toInt()
                outlineColor = 0xFF050505.toInt()
                glowColor = 0xC8FF1030.toInt()
            }
            1 -> {
                fillColor = 0xFFFFD34D.toInt()
                outlineColor = 0xFF1A0F00.toInt()
                glowColor = 0xBEFFC83D.toInt()
            }
            else -> {
                fillColor = 0xFFFF2A3D.toInt()
                outlineColor = 0xFFFFFFFF.toInt()
                glowColor = 0xAAFF2A3D.toInt()
            }
        }

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = glowColor
            maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = outlineColor
            strokeWidth = if (theme == 2) 6f else 5f
            strokeJoin = Paint.Join.ROUND
        }

        canvas.drawPath(arrowPath, glowPaint)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
    }
}
