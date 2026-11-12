package com.actioncut.core.media.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fty HTML எடிட்டரின் `_fkbDrawTplBar` மற்றும் `_cnDrawFbTemplateImage`-ன் 1:1 அச்சு அசலான ஆண்ட்ராய்டு ரெண்டரர்.
 */
object FbTemplateBarRenderer {

    fun drawTplBar(canvas: Canvas, w: Float, h: Float, timeSeconds: Float) {
        val t = max(0f, timeSeconds)
        val cycleTime = 20f
        val tt = ((t % cycleTime) + cycleTime) % cycleTime

        // 1. அசல் Fty கிரேடியன்ட் பேக்ரவுண்ட்
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, h, w, 0f,
                intArrayOf(
                    Color.parseColor("#000000"),
                    Color.parseColor("#00061C"),
                    Color.parseColor("#0638B4"),
                    Color.parseColor("#0A3CFF"),
                    Color.parseColor("#00B4FF"),
                ),
                floatArrayOf(0f, 0.22f, 0.55f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val cy = h / 2f
        val cx = w * 0.118f

        // 🌟 2. இடதுபுறம்: Fty அசல் NEWS CRICK லோகோ அனிமேஷன்
        val logoH = h * 0.82f
        val logoW = logoH * 1.36f

        // (அ) தொடக்க Pop-in Bounce
        val intro = if (t < 0.55f) {
            val p = (t / 0.55f).coerceIn(0f, 1f)
            (1f - (1f - p).pow(3)) * (1f + 0.16f * sin(p * Math.PI.toFloat()))
        } else {
            1f
        }

        // (ஆ) வீடியோ ஓடும் போது தொடர்ச்சியாக 3.5 வினாடிக்கு ஒருமுறை துடிக்கும் Heartbeat Pulse
        val pulseCycle = 3.5f
        val pt = (t % pulseCycle)
        val pulse = if (pt < 0.40f) {
            val p = pt / 0.40f
            sin(p * Math.PI.toFloat() * 2f) * 0.055f * (1f - p)
        } else 0f

        val logoScale = intro * (1f + pulse)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(logoScale, logoScale)
        drawNewsCrickBadge(canvas, -logoW / 2f, -logoH / 2f, logoW, logoH, t)
        canvas.restore()

        // 3. மூன்று பட்டன்கள்: Like, Message, Share
        val ph = h * 0.40f
        val bx = arrayOf(
            floatArrayOf(0.259f, 0.455f),
            floatArrayOf(0.469f, 0.722f),
            floatArrayOf(0.741f, 0.938f),
        )
        val names = arrayOf("Like", "Message", "Share")
        val clicks = floatArrayOf(1.9f, 2.9f, 3.9f)

        for (i in 0 until 3) {
            val x0 = bx[i][0] * w
            val pw = (bx[i][1] - bx[i][0]) * w
            val ip = (1f - (1f - ((t - 0.25f - i * 0.12f) / 0.45f).coerceIn(0f, 1f)).pow(3))
            if (ip <= 0f) continue

            val dc = tt - clicks[i]
            val bump = if (dc > -0.12f && dc < 0.25f) {
                if (dc < 0.04f) (dc + 0.12f) / 0.16f else 1f - (dc - 0.04f) / 0.21f
            } else 0f

            val blue = if (dc >= 0f && dc < 0.9f) {
                if (dc < 0.5f) 1f else 1f - (dc - 0.5f) / 0.4f
            } else 0f

            canvas.save()
            val btnX = x0 + pw / 2f + (1f - ip) * w * 0.06f
            canvas.translate(btnX, cy)
            val sc = 1f - 0.08f * bump.coerceIn(0f, 1f)
            canvas.scale(sc, sc)

            val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (blue > 0f) {
                    val r = (255 - (255 - 24) * blue).roundToInt().coerceIn(0, 255)
                    val g = (255 - (255 - 119) * blue).roundToInt().coerceIn(0, 255)
                    val b = (255 - (255 - 242) * blue).roundToInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                } else Color.WHITE
            }
            canvas.drawRoundRect(RectF(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f), ph * 0.30f, ph * 0.30f, btnPaint)

            val inkColor = if (blue > 0.5f) Color.WHITE else Color.parseColor("#111111")
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = inkColor
                textSize = ph * 0.60f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }

            val ic = ph * 0.62f
            val gap = ph * 0.14f
            val tw = textPaint.measureText(names[i])
            val tot = ic + gap + tw
            val ix = -tot / 2f
            val iy = -ic / 2f

            drawButtonIcon(canvas, i, ix, iy, ic, inkColor)
            canvas.drawText(names[i], ix + ic + gap, ph * 0.20f, textPaint)
            canvas.restore()
        }

        // 4. மவுஸ் கர்சர் அனிமேஷன்
        val kp = arrayOf(
            floatArrayOf(1.0f, 1.02f, 1.25f),
            floatArrayOf(1.8f, 0.40f, 0.62f),
            floatArrayOf(2.7f, 0.40f, 0.62f),
            floatArrayOf(2.8f, 0.60f, 0.62f),
            floatArrayOf(3.7f, 0.60f, 0.62f),
            floatArrayOf(3.8f, 0.86f, 0.62f),
            floatArrayOf(4.4f, 0.86f, 0.62f),
            floatArrayOf(4.9f, 1.03f, 1.25f),
        )

        if (tt >= kp[0][0] && tt <= kp[kp.size - 1][0]) {
            var kIdx = 0
            for (k in 0 until kp.size - 1) {
                if (tt >= kp[k][0] && tt <= kp[k + 1][0]) {
                    kIdx = k
                    break
                }
            }
            val a = kp[kIdx]
            val b = kp[kIdx + 1]
            val span = b[0] - a[0]
            var u = if (span <= 0f) 1f else (tt - a[0]) / span
            u = u * u * (3f - 2f * u)

            val mx = (a[1] + (b[1] - a[1]) * u) * w
            val my = (a[2] + (b[2] - a[2]) * u) * h

            var press = 0f
            for (i in 0 until 3) {
                val d = tt - clicks[i]
                if (d > -0.1f && d < 0.2f) {
                    press = max(press, 1f - kotlin.math.abs(d - 0.05f) / 0.15f)
                }
            }
            val cs = (h / 134f) * 1.25f * (1f - 0.12f * press.coerceIn(0f, 1f))
            drawCursor(canvas, mx, my, cs)
        }
    }

    private fun drawButtonIcon(canvas: Canvas, type: Int, x: Float, y: Float, size: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.save()
        canvas.translate(x, y)
        val s = size / 24f
        canvas.scale(s, s)

        when (type) {
            0 -> { // Thumbs up (Like)
                val path = Path().apply {
                    moveTo(2f, 10f)
                    lineTo(6f, 10f)
                    lineTo(6f, 22f)
                    lineTo(2f, 22f)
                    close()
                    moveTo(8f, 10f)
                    lineTo(12f, 2f)
                    lineTo(14f, 2f)
                    lineTo(13f, 8f)
                    lineTo(21f, 8f)
                    lineTo(22f, 12f)
                    lineTo(19f, 22f)
                    lineTo(8f, 22f)
                    close()
                }
                canvas.drawPath(path, p)
            }
            1 -> { // Messenger (Message)
                p.color = if (color == Color.WHITE) Color.WHITE else Color.parseColor("#0A84FF")
                canvas.drawCircle(12f, 12f, 11f, p)
                val bolt = Path().apply {
                    moveTo(6f, 14f)
                    lineTo(11f, 8f)
                    lineTo(14f, 11f)
                    lineTo(18f, 8f)
                    lineTo(13f, 16f)
                    lineTo(10f, 13f)
                    close()
                }
                p.color = if (color == Color.WHITE) Color.parseColor("#1877F2") else Color.WHITE
                canvas.drawPath(bolt, p)
            }
            2 -> { // Share
                val path = Path().apply {
                    moveTo(4f, 18f)
                    quadTo(6f, 10f, 14f, 9f)
                    lineTo(14f, 4f)
                    lineTo(22f, 12f)
                    lineTo(14f, 20f)
                    lineTo(14f, 15f)
                    quadTo(8f, 15f, 4f, 18f)
                    close()
                }
                canvas.drawPath(path, p)
            }
        }
        canvas.restore()
    }

    private fun drawCursor(canvas: Canvas, x: Float, y: Float, scale: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)

        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 26f)
            lineTo(7f, 20f)
            lineTo(12f, 31f)
            lineTo(17f, 28.5f)
            lineTo(12f, 18f)
            lineTo(21f, 18f)
            close()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(6f, 0f, 2f, Color.parseColor("#80000000"))
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9AA4B5")
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        canvas.restore()
    }

    /**
     * 🌟 Fty 100% அசல் NEWS CRICK பேட்ஜ்:
     * - 3D Metallic Dark Navy Blue Gradient
     * - Dual Gold Beveled Border
     * - Red "• NEWS" Badge with Live Indicator
     * - 3D Gold "CRiCK" Title
     * - Shimmer Glint Beam Sweep Animation
     */
    private fun drawNewsCrickBadge(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, t: Float) {
        val r = h * 0.15f
        val rect = RectF(x, y, x + w, y + h)

        // 1. பேட்ஜின் கீழ் உள்ள 3D நிழல்
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
        }
        canvas.drawRoundRect(RectF(x, y + h * 0.04f, x + w, y + h * 1.04f), r, r, shadowPaint)

        // 2. 3D Metallic Dark Navy Blue Background Gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                x, y, x, y + h,
                intArrayOf(
                    Color.parseColor("#122A54"),
                    Color.parseColor("#1B3E7A"),
                    Color.parseColor("#0A1C3B")
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // 3. Dual Beveled Gold Border
        val borderW = h * 0.045f
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderW
            shader = LinearGradient(
                x, y, x + w, y + h,
                intArrayOf(
                    Color.parseColor("#FFF380"),
                    Color.parseColor("#FFD700"),
                    Color.parseColor("#FFA000"),
                    Color.parseColor("#FFD700")
                ),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, r, r, borderPaint)

        // 4. "NEWS" Top Red Pill Badge
        val newsW = w * 0.76f
        val newsH = h * 0.34f
        val newsX = x + (w - newsW) / 2f
        val newsY = y + h * 0.09f
        val newsR = newsH * 0.28f
        val newsRect = RectF(newsX, newsY, newsX + newsW, newsY + newsH)

        val newsBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                newsX, newsY, newsX, newsY + newsH,
                intArrayOf(Color.parseColor("#FF172A"), Color.parseColor("#C4000C")),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(newsRect, newsR, newsR, newsBgPaint)

        // NEWS Outline
        val newsStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = h * 0.018f
            color = Color.parseColor("#FF7A85")
        }
        canvas.drawRoundRect(newsRect, newsR, newsR, newsStrokePaint)

        // Live Blinking Indicator Dot
        val dotAlpha = (160 + 95 * sin(t * 8f)).roundToInt().coerceIn(0, 255)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(dotAlpha, 255, 255, 255)
        }
        val dotRadius = newsH * 0.13f
        val dotX = newsX + newsW * 0.14f
        val dotY = newsY + newsH * 0.50f
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)

        // NEWS Text
        val newsTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = newsH * 0.72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.06f
            setShadowLayer(4f, 0f, 1.5f, Color.parseColor("#66000000"))
        }
        canvas.drawText("NEWS", newsX + newsW * 0.56f, newsY + newsH * 0.76f, newsTextPaint)

        // 5. "CRiCK" Main Title with 3D Drop Shadow & Gold Gradient
        val crickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = h * 0.38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.08f
        }

        val crickX = x + w / 2f
        val crickY = y + h * 0.86f

        // 3D Black Outline
        crickTextPaint.style = Paint.Style.STROKE
        crickTextPaint.strokeWidth = h * 0.045f
        crickTextPaint.color = Color.BLACK
        canvas.drawText("CRiCK", crickX, crickY, crickTextPaint)

        // Bright Metallic Gold Gradient Fill
        crickTextPaint.style = Paint.Style.FILL
        crickTextPaint.shader = LinearGradient(
            x, crickY - h * 0.38f, x, crickY,
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#FFE853"),
                Color.parseColor("#FFC400"),
                Color.parseColor("#FFA000")
            ),
            floatArrayOf(0f, 0.25f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawText("CRiCK", crickX, crickY, crickTextPaint)
        crickTextPaint.shader = null

        // 6. 🌟 Shimmer / Glint Beam Sweep Animation (ஒவ்வொரு 4 வினாடிக்கும் ஒளிரும் வெள்ளைக்கற்றை)
        val shimmerPeriod = 4.0f
        val shimmerDuration = 0.85f
        val st = (t % shimmerPeriod)
        if (st < shimmerDuration) {
            val progress = st / shimmerDuration
            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(rect, r, r, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)

            val beamX = x - w * 0.4f + (w * 1.8f) * progress
            val beamW = w * 0.35f
            val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    beamX, y, beamX + beamW, y + h,
                    intArrayOf(
                        Color.argb(0, 255, 255, 255),
                        Color.argb(120, 255, 255, 255),
                        Color.argb(0, 255, 255, 255)
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(x, y, x + w, y + h, glintPaint)
            canvas.restore()
        }
    }

    /**
     * 1080x1350 கேன்வாஸில் அடியில் உள்ள 16:9 Image மற்றும் நடுவில் உள்ள Like Bar-ஐ வரையும் மெத்தட்
     */
    fun renderFull45TemplateOverlay(
        context: Context,
        targetWidth: Int,
        targetHeight: Int,
        timeSeconds: Float,
        bottomImageUriStr: String?,
    ): Bitmap {
        val outBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)

        val vH = (targetWidth * (9f / 16f)).roundToInt()
        val barH = targetHeight - 2 * vH
        val bottomY = vH + barH

        // 1. நடுவில் உள்ள Like/Message/Share Bar
        canvas.save()
        canvas.translate(0f, vH.toFloat())
        drawTplBar(canvas, targetWidth.toFloat(), barH.toFloat(), timeSeconds)
        canvas.restore()

        // 2. அடியில் உள்ள 16:9 புகைப்படம்
        val bottomRect = RectF(0f, bottomY.toFloat(), targetWidth.toFloat(), targetHeight.toFloat())
        val bgPaint = Paint().apply { color = Color.parseColor("#0B0B0B") }
        canvas.drawRect(bottomRect, bgPaint)

        if (!bottomImageUriStr.isNullOrBlank()) {
            runCatching {
                val uri = Uri.parse(bottomImageUriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val src = BitmapFactory.decodeStream(stream)
                    if (src != null) {
                        val iw = src.width.toFloat()
                        val ih = src.height.toFloat()
                        val sc = min(targetWidth.toFloat() / iw, vH.toFloat() / ih)
                        val dw = iw * sc
                        val dh = ih * sc
                        val dx = (targetWidth - dw) / 2f
                        val dy = bottomY + (vH - dh) / 2f

                        val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                        canvas.drawBitmap(src, null, RectF(dx, dy, dx + dw, dy + dh), imgPaint)
                        src.recycle()
                    }
                }
            }
        }

        return outBmp
    }
}
