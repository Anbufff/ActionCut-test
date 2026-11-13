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

object ScoreCardComposer {
    const val CANVAS_W = 1920f
    const val CANVAS_H = 1080f
    const val THEME_COUNT = 3

    internal const val LIST_LEFT = 24f
    internal const val LIST_WIDTH = 860f
    internal const val PANEL_LEFT = LIST_LEFT + LIST_WIDTH // 884f — 0px gap between names & player photo
    internal const val TEXT_PAD_LEFT = 36f
    internal const val TEXT_PAD_RIGHT = 14f

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

        val listLeft = LIST_LEFT
        val listWidth = LIST_WIDTH
        val listTop = 28f
        val listBottom = CANVAS_H - 28f
        val listHeight = listBottom - listTop
        val textWidth = listWidth - TEXT_PAD_LEFT - TEXT_PAD_RIGHT

        val rowHeights = card.rows.map { r ->
            val srcW = r.rect.width().coerceAtLeast(1f)
            val srcH = r.rect.height().coerceAtLeast(1f)
            textWidth * (srcH / srcW)
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
        c.clipRect(listLeft - 10f, listTop - 10f, listLeft + listWidth + 10f, listBottom + 10f)

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
            val dstRect = RectF(
                rowRect.left + TEXT_PAD_LEFT,
                rowRect.top,
                rowRect.right - TEXT_PAD_RIGHT,
                rowRect.bottom
            )

            val isSel = i == selected
            val accentIdx = if (!rInfo.isHeader && !rInfo.isColumnHeader) playerCounter++ else 0

            drawRowContainer(c, p, theme, rowRect, rInfo, isSel, accentIdx)

            val textBmp = if (isSel && selBitmap != null) selBitmap else card.bitmap
            c.drawBitmap(textBmp, srcRect, dstRect, bmpPaint)

            currY += rh + gap
        }
        c.restore()

        drawPlayerPanel(c, p, bmpPaint, theme, type, player, photo, fallbackName)

        return ComposedCard(out, renderedRowRects)
    }

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

    private fun drawGlassHighlight(c: Canvas, p: Paint, r: RectF, rad: Float, alphaTop: Int = 0x22) {
        val h = r.height()
        val topRect = RectF(r.left + 1.5f, r.top + 1.5f, r.right - 1.5f, r.top + h * 0.44f)
        gradRR(
            c, p, topRect, max(rad - 2f, 4f),
            intArrayOf((alphaTop shl 24) or 0xFFFFFF, 0x00FFFFFF),
            null, false
        )
    }

    internal fun drawRowContainer(
        c: Canvas,
        p: Paint,
        theme: Int,
        rect: RectF,
        info: RowInfo,
        selected: Boolean,
        accentIdx: Int,
    ) {
        val h = rect.height()
        val rad = min(h * 0.22f, 20f)
        val barRect = RectF(rect.left + 12f, rect.top + h * 0.18f, rect.left + 18f, rect.bottom - h * 0.18f)

        when (theme) {
            0 -> { // Neon Night Broadcast
                if (selected) {
                    glowRR(c, p, rect, rad, 0xCCFF2D4A.toInt(), 18f, 3f, 0f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFF7A1236.toInt(), 0xFF3E0F40.toInt(), 0xFF150A30.toInt()),
                        floatArrayOf(0f, 0.55f, 1f), true
                    )
                    drawGlassHighlight(c, p, rect, rad, 0x26)
                    strokeRR(c, p, rect, rad, 0xFFFF3B5C.toInt(), 3.5f)
                    val innerBevel = RectF(rect.left + 3f, rect.top + 3f, rect.right - 3f, rect.bottom - 3f)
                    strokeRR(c, p, innerBevel, max(rad - 2f, 4f), 0x38FFFFFF, 1.2f)
                    fillRR(c, p, barRect, 3f, 0xFFFFD400.toInt())
                } else if (info.isHeader) {
                    glowRR(c, p, rect, rad, 0xAA2563FF.toInt(), 16f, 1f, 0f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFF071B73.toInt(), 0xFF1446C8.toInt(), 0xFF009CE8.toInt()),
                        floatArrayOf(0f, 0.58f, 1f), true
                    )
                    drawGlassHighlight(c, p, rect, rad, 0x2A)
                    strokeRR(c, p, rect, rad, 0x6600E5FF, 1.8f)
                    fillRR(c, p, barRect, 3f, 0xFF00E5FF.toInt())
                    gradRR(
                        c, p,
                        RectF(rect.left + 26f, rect.bottom - 4f, rect.right - 20f, rect.bottom - 1.5f),
                        1.5f,
                        intArrayOf(0xFF00E5FF.toInt(), 0x8000E5FF.toInt(), 0x0000E5FF),
                        floatArrayOf(0f, 0.5f, 1f), true
                    )
                } else if (info.isColumnHeader) {
                    gradRR(c, p, rect, rad, intArrayOf(0xD2081228.toInt(), 0xC80F1E42.toInt()), null, true)
                    strokeRR(c, p, rect, rad, 0x3300E5FF, 1.5f)
                } else {
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xEB09132B.toInt(), 0xE20F1D3D.toInt()),
                        null, true
                    )
                    drawGlassHighlight(c, p, rect, rad, 0x0E)
                    strokeRR(c, p, rect, rad, 0x24FFFFFF, 1.5f)
                    fillRR(c, p, barRect, 3f, NEON_ACCENTS[accentIdx % NEON_ACCENTS.size])
                }
            }
            1 -> { // Royal Gold Broadcast
                val goldA = 0xFFFFE28A.toInt()
                val goldB = 0xFFC68A20.toInt()
                if (selected) {
                    glowRR(c, p, rect, rad, 0xB8FFC83D.toInt(), 18f, 3f, 0f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFFFFEEAC.toInt(), 0xFFF3C65F.toInt(), 0xFFD4972C.toInt()),
                        floatArrayOf(0f, 0.5f, 1f), true
                    )
                    drawGlassHighlight(c, p, rect, rad, 0x36)
                    strokeRR(c, p, rect, rad, 0xFFFFF8DC.toInt(), 3f)
                    fillRR(c, p, barRect, 3f, 0xFF1A1208.toInt())
                } else if (info.isHeader) {
                    glowRR(c, p, rect, rad, 0xA8000000.toInt(), 14f, 0f, 5f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFF7D152E.toInt(), 0xFF470B1A.toInt(), 0xFF22050C.toInt()),
                        floatArrayOf(0f, 0.55f, 1f), true
                    )
                    drawGlassHighlight(c, p, rect, rad, 0x1A)
                    strokeRR(c, p, rect, rad, 0x88F6D77A.toInt(), 1.8f)
                    fillRR(c, p, barRect, 3f, goldA)
                    gradRR(
                        c, p,
                        RectF(rect.left + 26f, rect.bottom - 5f, rect.right - 22f, rect.bottom - 2f),
                        1.5f,
                        intArrayOf(0x00C8922A, goldA, goldB, 0x00C8922A),
                        floatArrayOf(0f, 0.25f, 0.75f, 1f), true
                    )
                } else if (info.isColumnHeader) {
                    fillRR(c, p, rect, rad, 0xD80D0D12.toInt())
                    strokeRR(c, p, rect, rad, 0x60C8922A, 1.5f)
                } else {
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xEC121218.toInt(), 0xE61B1920.toInt()),
                        null, true
                    )
                    drawGlassHighlight(c, p, rect, rad, 0x0C)
                    strokeRR(c, p, rect, rad, 0x60C8922A, 1.5f)
                    gradRR(c, p, barRect, 3f, intArrayOf(goldA, goldB), null, false)
                }
            }
            else -> { // Sunset Pop Broadcast
                val from = POP_FROM[accentIdx % POP_FROM.size]
                val to = POP_TO[accentIdx % POP_TO.size]
                if (selected) {
                    glowRR(c, p, rect, rad, 0xCEFF7A2D.toInt(), 20f, 3f, 0f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFFFFC107.toInt(), 0xFFFF4D4D.toInt(), 0xFFE61E8C.toInt()),
                        floatArrayOf(0f, 0.5f, 1f), true
                    )
                    val inner = RectF(rect.left + 4.5f, rect.top + 4.5f, rect.right - 4.5f, rect.bottom - 4.5f)
                    fillRR(c, p, inner, max(rad - 2f, 4f), 0xFFFFFFFF.toInt())
                    gradRR(c, p, barRect, 3f, intArrayOf(0xFFFF8A00.toInt(), 0xFFE61E8C.toInt()), null, false)
                } else if (info.isHeader) {
                    glowRR(c, p, rect, rad, 0xA4C026D3.toInt(), 16f, 0f, 0f)
                    gradRR(
                        c, p, rect, rad,
                        intArrayOf(0xFF4F24E8.toInt(), 0xFFC026D3.toInt(), 0xFFFF6A3D.toInt()),
                        floatArrayOf(0f, 0.55f, 1f), true
                    )
                    drawGlassHighlight(c, p, rect, rad, 0x26)
                    strokeRR(c, p, rect, rad, 0x55FFFFFF, 1.8f)
                    fillRR(c, p, barRect, 3f, 0xFFFFE600.toInt())
                } else if (info.isColumnHeader) {
                    fillRR(c, p, rect, rad, 0xEAFFFFFF.toInt())
                    strokeRR(c, p, rect, rad, 0x336C2BD9, 1.5f)
                } else {
                    glowRR(c, p, rect, rad, 0x5A000000, 10f, 0f, 4f)
                    fillRR(c, p, rect, rad, 0xF5FFFFFF.toInt())
                    strokeRR(c, p, rect, rad, 0x1E000000, 1.2f)
                    gradRR(c, p, barRect, 3f, intArrayOf(from, to), null, false)
                }
            }
        }
        p.style = Paint.Style.FILL
        p.shader = null
        p.maskFilter = null
    }

    internal fun drawPlayerPanel(
        c: Canvas,
        p: Paint,
        bmpPaint: Paint,
        theme: Int,
        type: ScoreCardType,
        player: Player?,
        photo: Bitmap?,
        fallbackName: String?,
    ) {
        val pl = PANEL_LEFT
        val pr = CANVAS_W - 28f
        val pt = 28f
        val pb = CANVAS_H - 28f
        val pw = pr - pl
        val ph = pb - pt
        val panelRect = RectF(pl, pt, pr, pb)
        val corner = when (theme) {
            0 -> 22f
            1 -> 26f
            else -> 32f
        }

        when (theme) {
            0 -> glowRR(c, p, panelRect, corner, 0x9600E5FF.toInt(), 18f, 0f, 0f)
            1 -> glowRR(c, p, panelRect, corner, 0xAA000000.toInt(), 16f, 0f, 6f)
            else -> glowRR(c, p, panelRect, corner, 0x96E61E8C.toInt(), 20f, 0f, 0f)
        }

        when (theme) {
            0 -> {
                p.shader = RadialGradient(
                    pl + pw * 0.5f, pt + ph * 0.30f, pw * 0.85f,
                    intArrayOf(0xFF1B4580.toInt(), 0xFF0C1D3B.toInt(), 0xFF030814.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawRoundRect(panelRect, corner, corner, p)
                p.shader = null
            }
            1 -> {
                p.shader = RadialGradient(
                    pl + pw * 0.5f, pt + ph * 0.30f, pw * 0.85f,
                    intArrayOf(0xFF3A101E.toInt(), 0xFF1A070E.toInt(), 0xFF0B0306.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawRoundRect(panelRect, corner, corner, p)
                p.shader = null
            }
            else -> {
                p.shader = RadialGradient(
                    pl + pw * 0.5f, pt + ph * 0.30f, pw * 0.85f,
                    intArrayOf(0xFF3B1373.toInt(), 0xFF1E0B42.toInt(), 0xFF0E0522.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
                c.drawRoundRect(panelRect, corner, corner, p)
                p.shader = null
            }
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

        p.shader = RadialGradient(
            pl + pw / 2f, pt + ph * 0.45f, max(pw, ph) * 0.72f,
            intArrayOf(0x00000000, 0x00000000, 0x88000000.toInt()),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(panelRect, p)
        p.shader = null

        val barTop = pb - ph * 0.36f
        val plateEndColor = when (theme) {
            0 -> 0xFC030816.toInt()
            1 -> 0xFC100409.toInt()
            else -> 0xFC15082E.toInt()
        }
        p.shader = LinearGradient(0f, barTop, 0f, pb, 0x00000000, plateEndColor, Shader.TileMode.CLAMP)
        c.drawRect(pl, barTop, pr, pb, p)
        p.shader = null

        val npLeft = pl + pw * 0.035f
        val npRight = pr - pw * 0.035f
        val npTop = pb - ph * 0.21f
        val npBottom = pb - ph * 0.03f
        val npRect = RectF(npLeft, npTop, npRight, npBottom)
        fillRR(c, p, npRect, 16f, 0xA8050914.toInt())
        strokeRR(
            c, p, npRect, 16f,
            when (theme) {
                0 -> 0x5500E5FF
                1 -> 0x66F6D77A
                else -> 0x55FFFFFF
            },
            1.8f
        )

        val displayName = (player?.name ?: fallbackName ?: if (type == ScoreCardType.BOWLING) "BOWLER" else "BATTER").trim()
        val tagText = if (type == ScoreCardType.BOWLING) "KEY BOWLER" else "KEY BATSMAN"

        val roleColor = when (theme) {
            0 -> 0xFF00E5FF.toInt()
            1 -> 0xFFF6D77A.toInt()
            else -> 0xFFFFD84D.toInt()
        }
        p.style = Paint.Style.FILL
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        p.color = roleColor
        p.textSize = ph * 0.030f
        p.letterSpacing = 0.16f
        c.drawText("● $tagText", npLeft + pw * 0.035f, npTop + ph * 0.052f, p)

        p.color = 0xFFFFFFFF.toInt()
        p.textSize = ph * 0.064f
        p.letterSpacing = 0.03f
        val nm = displayName.uppercase()
        while (p.measureText(nm) > (npRect.width() - pw * 0.07f) && p.textSize > 18f) {
            p.textSize -= 2f
        }
        c.drawText(nm, npLeft + pw * 0.035f, npTop + ph * 0.125f, p)
        p.letterSpacing = 0f

        val lineY1 = npBottom - ph * 0.025f
        val lineY2 = npBottom - ph * 0.017f
        when (theme) {
            0 -> {
                fillRR(c, p, RectF(npLeft + pw * 0.035f, lineY1, npLeft + pw * 0.30f, lineY2), 3f, 0xFFFFCC00.toInt())
                fillRR(c, p, RectF(npLeft + pw * 0.32f, lineY1, npRight - pw * 0.035f, lineY2 - 1f), 2f, 0xFF00E5FF.toInt())
            }
            1 -> gradRR(
                c, p,
                RectF(npLeft + pw * 0.035f, lineY1, npRight - pw * 0.035f, lineY2),
                3f,
                intArrayOf(0xFFF6D77A.toInt(), 0xFFC8922A.toInt(), 0x00C8922A),
                floatArrayOf(0f, 0.65f, 1f),
                true,
            )
            else -> {
                fillRR(c, p, RectF(npLeft + pw * 0.035f, lineY1, npLeft + pw * 0.30f, lineY2), 4f, 0xFFFF8A00.toInt())
                fillRR(c, p, RectF(npLeft + pw * 0.32f, lineY1, npRight - pw * 0.035f, lineY2 - 1f), 3f, 0xFFE61E8C.toInt())
            }
        }

        val bw = pw * 0.30f
        val bh = ph * 0.055f
        val bx = pl + pw * 0.035f
        val by = pt + ph * 0.035f
        val badge = RectF(bx, by, bx + bw, by + bh)
        glowRR(c, p, badge, 8f, 0x80000000.toInt(), 10f, 0f, 3f)
        val badgeTextColor: Int
        when (theme) {
            0 -> {
                gradRR(c, p, badge, 8f, intArrayOf(0xFFE50000.toInt(), 0xFFA80000.toInt()), null, true)
                p.color = 0xFFFFE600.toInt()
                c.drawRect(bx, by, bx + 5f, by + bh, p)
                badgeTextColor = 0xFFFFFFFF.toInt()
            }
            1 -> {
                gradRR(c, p, badge, bh / 2f, intArrayOf(0xFFFFE28A.toInt(), 0xFFC68A20.toInt()), null, true)
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
        p.textSize = bh * 0.56f
        c.drawText("⚡ NEWS CRICK", bx + bw / 2f + 2f, by + bh * 0.71f, p)

        c.restore()

        when (theme) {
            0 -> {
                strokeRR(c, p, panelRect, corner, 0xFF00E5FF.toInt(), 4.5f)
                val inner = RectF(pl + 10f, pt + 10f, pr - 10f, pb - 10f)
                strokeRR(c, p, inner, corner - 6f, 0x33FFFFFF, 1.2f)
            }
            1 -> {
                strokeRR(c, p, panelRect, corner, 0xFFF6D77A.toInt(), 5f)
                val inner = RectF(pl + 12f, pt + 12f, pr - 12f, pb - 12f)
                strokeRR(c, p, inner, corner - 6f, 0x6EF6D77A, 1.5f)
            }
            else -> {
                strokeRR(c, p, panelRect, corner, 0xFFFFFFFF.toInt(), 5.5f)
                val inner = RectF(pl + 11f, pt + 11f, pr - 11f, pb - 11f)
                strokeRR(c, p, inner, corner - 6f, 0x44FF8A00, 1.5f)
            }
        }
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
    }

    fun drawBouncingArrow(canvas: Canvas, targetRow: RectF, timeInClipMs: Long, themeIdx: Int = 0) {
        val theme = normTheme(themeIdx)
        val cy = targetRow.centerY()

        val bounce = ((1.0 - sin(timeInClipMs * 2.0 * Math.PI / 750.0)) / 2.0 * 22.0).toFloat()
        val tipX = targetRow.right + 4f + bounce

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

        val topHalfPath = Path().apply {
            moveTo(tipX, cy)
            lineTo(tipX + headLen, cy - halfHeadH)
            lineTo(tipX + headLen, cy - halfStemH)
            lineTo(tailX, cy - halfStemH)
            lineTo(tailX, cy)
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
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x38FFFFFF
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = outlineColor
            strokeWidth = if (theme == 2) 6f else 5f
            strokeJoin = Paint.Join.ROUND
        }

        canvas.drawPath(arrowPath, glowPaint)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(topHalfPath, highlightPaint)
        canvas.drawPath(arrowPath, strokePaint)
    }
}
