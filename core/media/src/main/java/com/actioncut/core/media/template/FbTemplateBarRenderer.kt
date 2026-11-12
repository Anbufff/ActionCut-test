package com.actioncut.core.media.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 🌟 16:9 மற்றும் 4:5-க்கான 100% அசல் Fty ANIMATED NEWS CRiCK WATERMARK LOGO RENDERER
 * பந்து சுழற்சி, ஸ்டம்ப் நடுக்கம், பெயில்கள் பறப்பது மற்றும் LED ஃப்ளாஷ் அனிமேஷன்கள் அனைத்தும் அடங்கியது.
 */
object NewsCrickWatermarkRenderer {

    private fun lerp(kfs: Array<FloatArray>, pct: Float, colIdx: Int = 1): Float {
        for (i in 0 until kfs.size - 1) {
            val t0 = kfs[i][0]
            val t1 = kfs[i + 1][0]
            if (pct in t0..t1) {
                val seg = if (t1 - t0 != 0f) t1 - t0 else 1f
                val f = (pct - t0) / seg
                val v0 = kfs[i][colIdx]
                val v1 = kfs[i + 1][colIdx]
                return v0 + (v1 - v0) * f
            }
        }
        return kfs.last()[colIdx]
    }

    private class LedColor(val fill: Int, val glow: Int?)

    private fun getLedColor(hue: String, v: Float): LedColor {
        if (v <= 0.05f) {
            val c = if (hue == "red") Color.parseColor("#1A0000") else Color.parseColor("#001A00")
            return LedColor(c, null)
        }
        val valClamped = v.coerceIn(0f, 1f)
        if (hue == "red") {
            val g = (20 + 90 * valClamped).roundToInt().coerceIn(0, 255)
            val b = (20 + 90 * valClamped).roundToInt().coerceIn(0, 255)
            return LedColor(Color.rgb(255, g, b), Color.parseColor("#FF0000"))
        }
        val r = (20 + 90 * valClamped).roundToInt().coerceIn(0, 255)
        val b = (20 + 70 * valClamped).roundToInt().coerceIn(0, 255)
        return LedColor(Color.rgb(r, 255, b), Color.parseColor("#00FF00"))
    }

    fun renderLogo(canvas: Canvas, x: Float, y: Float, Sw: Float, Sh: Float, t: Float) {
        val cqi = { v: Float -> (v / 100f) * Sw }
        val p8 = (((t % 8.0f) / 8.0f) * 100.0f)
        val seamAngle = (((t % 1.5f) / 1.5f) * (2.0f * Math.PI.toFloat()))

        canvas.save()
        canvas.translate(x, y)

        val r = cqi(3f)
        val cardRect = RectF(0f, 0f, Sw, Sh)

        // 1. கார்டு க்ளிப்
        val clipPath = Path().apply { addRoundRect(cardRect, r, r, Path.Direction.CW) }
        canvas.clipPath(clipPath)

        // கார்டு பேக்ரவுண்ட்
        val cardGrad = RadialGradient(
            Sw * 0.32f, Sh * 0.22f, max(Sw, Sh) * 0.8f,
            intArrayOf(
                Color.parseColor("#234A82"),
                Color.parseColor("#14294F"),
                Color.parseColor("#081326"),
                Color.parseColor("#010204")
            ),
            floatArrayOf(0f, 0.38f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = cardGrad }
        canvas.drawRect(cardRect, cardPaint)

        // எம்பர் க்ளோ (Ember Glow)
        val emberGrad = RadialGradient(
            Sw * 0.3f, Sh * 0.82f, max(Sw, Sh) * 0.6f,
            Color.argb(96, 170, 20, 20), Color.argb(0, 170, 20, 20),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cardRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = emberGrad })

        // டாப் கிளாஸ் ஷீன் (Glass Sheen)
        val sheenGrad = LinearGradient(
            0f, 0f, 0f, Sh * 0.4f,
            intArrayOf(Color.argb(40, 255, 255, 255), Color.argb(10, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, Sw, Sh * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sheenGrad })

        // தங்க டாப் ஸ்ட்ரைப்
        val topGold = LinearGradient(
            0f, 0f, Sw, 0f,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#D9AC2E"), Color.parseColor("#FFE27A"), Color.parseColor("#D9AC2E"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, Sw, max(1f, cqi(1.1f)), Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = topGold })

        // தங்க பார்டர்
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, cqi(0.7f))
            shader = LinearGradient(
                0f, 0f, Sw, Sh,
                intArrayOf(Color.parseColor("#FFD778"), Color.parseColor("#F0C433"), Color.parseColor("#C39E37")),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(cardRect, r, r, borderPaint)

        // 2. "NEWS" Badge
        val newsFS = cqi(16.5f)
        val newsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = newsFS
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val newsLetters = arrayOf("N", "E", "W", "S")
        val newsSpacing = cqi(3.2f)
        val newsPadX = cqi(4.2f)
        val newsPadY = cqi(1.3f)
        var newsLettersW = 0f
        newsLetters.forEach { newsLettersW += newsPaint.measureText(it) }
        val newsW = newsLettersW + newsSpacing * (newsLetters.size - 1) + newsPadX * 2f
        val newsH = newsFS + newsPadY * 2f
        val newsX = Sw / 2f - newsW / 2f
        val newsY = cqi(8f)

        val newsGrad = LinearGradient(
            0f, newsY, 0f, newsY + newsH,
            intArrayOf(Color.parseColor("#D10000"), Color.parseColor("#8B0000"), Color.parseColor("#5C0000")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(newsX, newsY, newsX + newsW, newsY + newsH, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = newsGrad })

        newsPaint.color = Color.WHITE
        var nLx = newsX + newsPadX
        newsLetters.forEach { ch ->
            canvas.drawText(ch, nLx, newsY + newsH * 0.78f, newsPaint)
            nLx += newsPaint.measureText(ch) + newsSpacing
        }

        // 3. "CRiCK" Main Title
        val letterFS = cqi(38f)
        val crickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = letterFS
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val midY = cqi(60f)
        val gapW = cqi(13f)
        val spacing = cqi(1f)

        val wC1 = crickPaint.measureText("C")
        val wR = crickPaint.measureText("R")
        val wC2 = crickPaint.measureText("C")
        val wK = crickPaint.measureText("K")
        val totalW = wC1 + spacing + wR + spacing + gapW + spacing + wC2 + spacing + wK

        val titleMaxW = Sw * 0.94f
        val titleFit = if (totalW > titleMaxW) max(0.4f, titleMaxW / totalW) else 1f

        canvas.save()
        canvas.translate(Sw / 2f, 0f)
        canvas.scale(titleFit, 1f)
        canvas.translate(-Sw / 2f, 0f)

        var curX = Sw / 2f - totalW / 2f

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = letterFS
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            style = Paint.Style.STROKE
            strokeWidth = max(1f, cqi(0.8f))
            color = Color.parseColor("#140A00")
        }

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = letterFS
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, midY - letterFS, 0f, midY,
                intArrayOf(
                    Color.parseColor("#FFF8E1"), Color.parseColor("#F5D060"), Color.parseColor("#C8991A"),
                    Color.parseColor("#F0C433"), Color.parseColor("#A87720"), Color.parseColor("#F5D060"),
                    Color.parseColor("#FFF8E1")
                ),
                floatArrayOf(0f, 0.18f, 0.35f, 0.50f, 0.65f, 0.80f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        // C
        canvas.drawText("C", curX, midY, strokePaint)
        canvas.drawText("C", curX, midY, fillPaint)
        curX += wC1 + spacing

        // R
        canvas.drawText("R", curX, midY, strokePaint)
        canvas.drawText("R", curX, midY, fillPaint)
        val gCenterX = curX + wR + spacing + gapW / 2f
        curX += wR + spacing + gapW + spacing

        // C
        canvas.drawText("C", curX, midY, strokePaint)
        canvas.drawText("C", curX, midY, fillPaint)
        curX += wC2 + spacing

        // K
        canvas.drawText("K", curX, midY, strokePaint)
        canvas.drawText("K", curX, midY, fillPaint)

        // 4. நடுவில் உள்ள அனிமேஷன் (ஸ்டம்புகள் + பெயில்கள் + பந்து)
        val groundY = midY
        val stumpFullH = letterFS * 0.50f
        val stumpsTopY = groundY - stumpFullH
        val graphicsTopY = stumpsTopY - cqi(9f)
        val stumpW = cqi(1.6f * 0.9f)
        val stumpGapPx = cqi(1.2f * 0.9f)

        // LED Intensity
        val ledKF = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(19f, 0f), floatArrayOf(20f, 1f),
            floatArrayOf(22f, 0.75f), floatArrayOf(85f, 0.75f), floatArrayOf(86f, 0f), floatArrayOf(100f, 0f)
        )
        val ledVal = lerp(ledKF, p8)

        // Stump Shake
        val shakeKF = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(19f, 0f), floatArrayOf(20f, -4f),
            floatArrayOf(21f, 5f), floatArrayOf(22f, -3f), floatArrayOf(23f, 0f), floatArrayOf(100f, 0f)
        )
        val shakeTxKF = arrayOf(
            floatArrayOf(0f, 0f), floatArrayOf(19f, 0f), floatArrayOf(20f, -0.5f),
            floatArrayOf(21f, 0.5f), floatArrayOf(22f, -0.3f), floatArrayOf(23f, 0f), floatArrayOf(100f, 0f)
        )
        val shakeRot = lerp(shakeKF, p8)
        val shakeTx = lerp(shakeTxKF, p8)

        canvas.save()
        canvas.translate(gCenterX + cqi(shakeTx), groundY)
        canvas.rotate(shakeRot)

        for (i in -1..1) {
            val sx = i * (stumpW + stumpGapPx) - stumpW / 2f
            val stumpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    sx, 0f, sx + stumpW, 0f,
                    intArrayOf(Color.parseColor("#C8B87A"), Color.parseColor("#F5EDC0"), Color.parseColor("#A08840")),
                    null, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(sx, -stumpFullH, sx + stumpW, 0f, stumpPaint)

            // LED ஸ்ட்ரிப் (நடுவில் ரெட், ஓரங்களில் க்ரீன்)
            val ledInfo = getLedColor(if (i == 0) "red" else "green", ledVal)
            val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ledInfo.fill }
            canvas.drawRect(sx + stumpW * 0.25f, -stumpFullH * 0.86f, sx + stumpW * 0.75f, -stumpFullH * 0.36f, ledPaint)
        }
        canvas.restore()

        // Bails Animation (பறக்கும் பெயில்கள்)
        val bDxKF = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(19f, 0f), floatArrayOf(21f, 3f), floatArrayOf(40f, 7f), floatArrayOf(55f, 10f), floatArrayOf(85f, 10f), floatArrayOf(90f, 0f), floatArrayOf(100f, 0f))
        val bDyKF = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(19f, 0f), floatArrayOf(21f, -5f), floatArrayOf(40f, -14f), floatArrayOf(55f, -20f), floatArrayOf(85f, -20f), floatArrayOf(90f, 0f), floatArrayOf(100f, 0f))
        val bRotKF = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(19f, 0f), floatArrayOf(21f, 90f), floatArrayOf(40f, 220f), floatArrayOf(55f, 360f), floatArrayOf(85f, 360f), floatArrayOf(90f, 0f), floatArrayOf(100f, 0f))
        val bOpKF = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(19f, 1f), floatArrayOf(21f, 1f), floatArrayOf(40f, 1f), floatArrayOf(55f, 0f), floatArrayOf(85f, 0f), floatArrayOf(90f, 1f), floatArrayOf(100f, 1f))

        val bDx = lerp(bDxKF, p8)
        val bDy = lerp(bDyKF, p8)
        val bRot = lerp(bRotKF, p8)
        val bOp = lerp(bOpKF, p8)
        val bailW = cqi(3.4f)
        val bailH = cqi(0.8f)

        arrayOf(-1, 1).forEach { side ->
            val nomX = gCenterX + side * (bailW / 2f + cqi(0.1f))
            val nomY = stumpsTopY
            canvas.save()
            val bailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (bOp * 255).roundToInt().coerceIn(0, 255)
            }
            canvas.translate(nomX + side * cqi(bDx), nomY + cqi(bDy))
            canvas.rotate(side * bRot)

            val bailColorInfo = getLedColor(if (side == -1) "red" else "green", ledVal)
            bailPaint.color = bailColorInfo.fill
            canvas.drawRoundRect(RectF(-bailW / 2f, -bailH / 2f, bailW / 2f, bailH / 2f), bailH / 2f, bailH / 2f, bailPaint)
            canvas.restore()
        }

        // Ball Flight Animation (சுழன்று வரும் டியூக்ஸ் பந்து)
        val ballTxKF = arrayOf(floatArrayOf(0f, -50f), floatArrayOf(5f, -50f), floatArrayOf(8f, -50f), floatArrayOf(14f, -120f), floatArrayOf(20f, -50f), floatArrayOf(25f, -50f), floatArrayOf(85f, -50f), floatArrayOf(88f, -50f), floatArrayOf(100f, -50f))
        val ballTyKF = arrayOf(floatArrayOf(0f, 48f), floatArrayOf(5f, 48f), floatArrayOf(8f, 48f), floatArrayOf(14f, 25f), floatArrayOf(20f, 0f), floatArrayOf(25f, -5.5f), floatArrayOf(85f, -5.5f), floatArrayOf(88f, -5.5f), floatArrayOf(100f, 48f))
        val ballScKF = arrayOf(floatArrayOf(0f, 2.5f), floatArrayOf(5f, 2.5f), floatArrayOf(8f, 2.5f), floatArrayOf(14f, 1.6f), floatArrayOf(20f, 1.0f), floatArrayOf(25f, 2.3f), floatArrayOf(85f, 2.3f), floatArrayOf(88f, 2.3f), floatArrayOf(100f, 2.5f))
        val ballOpKF = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(5f, 0f), floatArrayOf(8f, 1f), floatArrayOf(14f, 1f), floatArrayOf(20f, 1f), floatArrayOf(25f, 1f), floatArrayOf(85f, 1f), floatArrayOf(88f, 0f), floatArrayOf(100f, 0f))

        val btx = lerp(ballTxKF, p8)
        val bty = lerp(ballTyKF, p8)
        val bsc = lerp(ballScKF, p8)
        val bop = lerp(ballOpKF, p8)
        val ballW = cqi(7.5f)
        val ballCenterX = gCenterX + (btx / 100f) * ballW + ballW / 2f
        val ballCenterY = graphicsTopY + cqi(1.2f) + ballW / 2f + cqi(bty)
        val ballR = (ballW / 2f) * bsc

        if (bop > 0.01f && ballR > 0.3f) {
            canvas.save()
            val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (bop * 255).roundToInt().coerceIn(0, 255)
                shader = RadialGradient(
                    ballCenterX - ballR * 0.3f, ballCenterY - ballR * 0.3f, ballR * 1.2f,
                    intArrayOf(Color.parseColor("#FF6060"), Color.parseColor("#CC0000"), Color.parseColor("#800000"), Color.parseColor("#3A0000")),
                    floatArrayOf(0f, 0.4f, 0.75f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(ballCenterX, ballCenterY, ballR, ballPaint)

            // Spinning Seam
            canvas.save()
            val seamClip = Path().apply { addCircle(ballCenterX, ballCenterY, ballR, Path.Direction.CW) }
            canvas.clipPath(seamClip)
            canvas.translate(ballCenterX, ballCenterY)
            canvas.scale(max(0.08f, abs(cos(seamAngle))), 1f)

            val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E6FFFFFF")
                strokeWidth = max(0.6f, ballR * 0.12f)
                style = Paint.Style.STROKE
            }
            canvas.drawLine(0f, -ballR, 0f, ballR, seamPaint)
            canvas.restore()
            canvas.restore()
        }

        canvas.restore() // Title scale pop
        canvas.restore() // Card clip pop
    }
}

object FbTemplateBarRenderer {

    fun drawTplBar(canvas: Canvas, w: Float, h: Float, timeSeconds: Float) {
        val t = max(0f, timeSeconds)
        val cycleTime = 20f
        val tt = ((t % cycleTime) + cycleTime) % cycleTime

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
        val logoH = h * 0.82f
        val logoW = logoH * 1.36f

        canvas.save()
        NewsCrickWatermarkRenderer.renderLogo(canvas, cx - logoW / 2f, cy - logoH / 2f, logoW, logoH, t)
        canvas.restore()

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
                    press = max(press, 1f - abs(d - 0.05f) / 0.15f)
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
            0 -> {
                val path = Path().apply {
                    moveTo(2f, 10f); lineTo(6f, 10f); lineTo(6f, 22f); lineTo(2f, 22f); close()
                    moveTo(8f, 10f); lineTo(12f, 2f); lineTo(14f, 2f); lineTo(13f, 8f); lineTo(21f, 8f)
                    lineTo(22f, 12f); lineTo(19f, 22f); lineTo(8f, 22f); close()
                }
                canvas.drawPath(path, p)
            }
            1 -> {
                p.color = if (color == Color.WHITE) Color.WHITE else Color.parseColor("#0A84FF")
                canvas.drawCircle(12f, 12f, 11f, p)
                val bolt = Path().apply {
                    moveTo(6f, 14f); lineTo(11f, 8f); lineTo(14f, 11f); lineTo(18f, 8f); lineTo(13f, 16f); lineTo(10f, 13f); close()
                }
                p.color = if (color == Color.WHITE) Color.parseColor("#1877F2") else Color.WHITE
                canvas.drawPath(bolt, p)
            }
            2 -> {
                val path = Path().apply {
                    moveTo(4f, 18f); quadTo(6f, 10f, 14f, 9f); lineTo(14f, 4f); lineTo(22f, 12f); lineTo(14f, 20f); lineTo(14f, 15f); quadTo(8f, 15f, 4f, 18f); close()
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
            moveTo(0f, 0f); lineTo(0f, 26f); lineTo(7f, 20f); lineTo(12f, 31f); lineTo(17f, 28.5f); lineTo(12f, 18f); lineTo(21f, 18f); close()
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
}
