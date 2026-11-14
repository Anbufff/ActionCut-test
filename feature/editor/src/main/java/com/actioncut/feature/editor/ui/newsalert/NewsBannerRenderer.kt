package com.actioncut.feature.editor.ui.newsalert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Full 1920x1080 Studio Broadcast / Stadium Background + up to 4 Flash News Key-Point Boxes
 * (with active box pop-out & highlighting frames _0.._3 + .meta timing) + Higher Player Cutout.
 */
object NewsBannerRenderer {

    private val fileCounter = AtomicInteger(0)
    private val highlightWordRegex = Regex("[A-Za-z0-9]")

    fun createBannerBitmap(context: Context, rawText: String): String {
        val rawLines = rawText.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return if (rawLines.size >= 2) {
            val title = rawLines.first().trimStart('#', '*', '-', '•', 'o', ' ').trim()
            val bullets = rawLines.drop(1).map {
                it.trimStart('*', '-', '•', 'o', ' ').trim()
            }.filter { it.isNotEmpty() }.take(4)
            createSegmentBannerBitmap(context, title, bullets)
        } else {
            val sentences = rawText.split(Regex("(?<!\\d)\\.(?!\\d)\\s+"))
                .map { it.trim().trimEnd('.') }
                .filter { it.isNotEmpty() }
            val title = if (sentences.size >= 2) sentences.first().take(48) else "FLASH NEWS"
            val bullets = (if (sentences.size >= 2) sentences.drop(1) else sentences).take(4)
            createSegmentBannerBitmap(context, title, bullets)
        }
    }

    fun createSegmentBannerBitmap(
        context: Context,
        title: String,
        bullets: List<String>,
        playerCutoutUri: String? = null,
        bulletEndFractions: List<Float>? = null,
    ): String {
        val isBroadcast = NewsTemplateStore.get(context) == NewsTemplateStore.BROADCAST_CARD
        val width = 1920
        val height = 1080
        val cleanTitle = title.trim().ifEmpty { "FLASH NEWS" }
        val cleanBullets = bullets.map { it.trim() }.filter { it.isNotEmpty() }.take(4)

        val baseBg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bgCanvas = Canvas(baseBg)
        if (isBroadcast) {
            drawNightBroadcastBackground(bgCanvas, width.toFloat(), height.toFloat())
        } else {
            drawDefaultStadiumBackground(bgCanvas, width.toFloat(), height.toFloat())
        }
        drawPlayerCutoutOnRight(bgCanvas, playerCutoutUri, width.toFloat(), height.toFloat(), isBroadcast)

        val dir = File(context.filesDir, "news_banners").apply { mkdirs() }
        val batchName = "news_banner_${System.currentTimeMillis()}_${fileCounter.incrementAndGet()}"
        val mainFile = File(dir, "$batchName.png")

        val frameCount = if (cleanBullets.size > 1) cleanBullets.size else 1
        try {
            for (activeIdx in 0 until frameCount) {
                val frameBmp = if (activeIdx == frameCount - 1) {
                    baseBg
                } else {
                    baseBg.copy(Bitmap.Config.ARGB_8888, true)
                }
                val canvas = Canvas(frameBmp)
                val highlightIdx = if (cleanBullets.size > 1) activeIdx else 0
                if (isBroadcast) {
                    drawBroadcastCards(canvas, cleanTitle, cleanBullets, highlightIdx)
                } else {
                    drawStadiumFlashCards(canvas, cleanTitle, cleanBullets, highlightIdx)
                }

                val targetFile = if (frameCount > 1) {
                    File(dir, "${batchName}_$activeIdx.png")
                } else {
                    mainFile
                }
                FileOutputStream(targetFile).use { out ->
                    frameBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (activeIdx == 0 && frameCount > 1) {
                    FileOutputStream(mainFile).use { out ->
                        frameBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                if (frameBmp !== baseBg) {
                    frameBmp.recycle()
                }
            }
        } finally {
            if (!baseBg.isRecycled) baseBg.recycle()
        }

        if (frameCount > 1) {
            val fracs = bulletEndFractions?.takeIf { it.size == frameCount }
                ?: List(frameCount) { i -> (i + 1).toFloat() / frameCount.toFloat() }
            val metaFile = File(dir, "$batchName.meta")
            runCatching {
                metaFile.writeText(fracs.joinToString(",") { "%.4f".format(java.util.Locale.US, it) })
            }
        }

        return Uri.fromFile(mainFile).toString()
    }

    private fun drawStadiumFlashCards(
        canvas: Canvas,
        cleanTitle: String,
        cleanBullets: List<String>,
        activeIndex: Int,
    ) {
        val cardLeft = 86f
        val cardWidth = 980f
        val goldWedgeW = 86f
        val tabWidth = 82f
        val skew = 32f
        val textPadL = 26f
        val textPadR = 28f
        val topMargin = 72f
        val maxBottom = 935f
        val rowGap = if (cleanBullets.size >= 3) 12f else 14f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 0f, 3f, AndroidColor.argb(185, 0, 0, 0))
        }
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 21f
            color = AndroidColor.parseColor("#FFD500")
            letterSpacing = 0.09f
        }
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var titleSize = 42f
        var bulletSize = if (cleanBullets.size >= 3) 29f else 33f
        var headerH = 140f
        var bulletHeights = emptyList<Float>()

        while (bulletSize >= 16f) {
            titlePaint.textSize = titleSize
            val titleMaxW = cardWidth - goldWedgeW - textPadL - textPadR - skew
            val titleLines = wrapLines(cleanTitle, titlePaint, titleMaxW)
            headerH = 56f + titleLines.size * (titleSize * 1.22f) + 22f

            bulletPaint.textSize = bulletSize
            val bLineH = bulletSize * 1.28f
            val bMaxW = cardWidth - tabWidth - textPadL - textPadR - skew
            bulletHeights = cleanBullets.map { b ->
                val bLines = wrapLines(b, bulletPaint, bMaxW)
                bLines.size * bLineH + 32f
            }

            val totalH = headerH + 16f + bulletHeights.sum() + (maxOf(0, cleanBullets.size - 1) * rowGap)
            if (topMargin + totalH <= maxBottom) break

            bulletSize -= 1.5f
            titleSize = (titleSize - 1.5f).coerceAtLeast(26f)
        }

        val totalBlockH = headerH + 16f + bulletHeights.sum() + maxOf(0, cleanBullets.size - 1) * rowGap
        var currY = topMargin + ((maxBottom - topMargin - totalBlockH).coerceAtLeast(0f) * 0.42f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(150, 0, 0, 0)
            setShadowLayer(20f, 5f, 9f, AndroidColor.argb(180, 0, 0, 0))
        }
        val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(200, 255, 213, 0)
            setShadowLayer(28f, 0f, 0f, AndroidColor.argb(240, 255, 213, 0))
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        val hdrSkew = skew * (headerH / 120f).coerceIn(0.85f, 1.3f)
        val wedgePath = slantedRect(cardLeft, currY, goldWedgeW, headerH, hdrSkew)
        val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardLeft, currY, cardLeft + goldWedgeW, currY + headerH,
                AndroidColor.parseColor("#FFE600"),
                AndroidColor.parseColor("#FF8800"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(wedgePath, shadowPaint)
        canvas.drawPath(wedgePath, wedgePaint)

        val hdrMainX = cardLeft + goldWedgeW - 2f
        val hdrMainW = cardWidth - goldWedgeW + 2f
        val hdrPath = slantedRect(hdrMainX, currY, hdrMainW, headerH, hdrSkew)
        val hdrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                hdrMainX, currY, hdrMainX + hdrMainW, currY + headerH,
                intArrayOf(
                    AndroidColor.parseColor("#18056B"),
                    AndroidColor.parseColor("#2E0CA8"),
                    AndroidColor.parseColor("#0C1C6B"),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(hdrPath, shadowPaint)
        canvas.drawPath(hdrPath, hdrPaint)

        // Subtle glass sheen across upper half of header
        canvas.save()
        canvas.clipPath(hdrPath)
        val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, currY, 0f, currY + headerH * 0.48f,
                AndroidColor.argb(38, 255, 255, 255),
                AndroidColor.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(hdrMainX - hdrSkew, currY, hdrMainX + hdrMainW + hdrSkew, currY + headerH * 0.48f, sheenPaint)
        canvas.restore()

        borderPaint.color = AndroidColor.parseColor("#E6F0FF")
        borderPaint.strokeWidth = 3f
        canvas.drawPath(slantedRect(cardLeft, currY, cardWidth, headerH, hdrSkew), borderPaint)

        val hdrTextX = hdrMainX + textPadL
        val pillRect = RectF(hdrTextX, currY + 14f, hdrTextX + 204f, currY + 46f)
        val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(135, 0, 0, 0) }
        canvas.drawRoundRect(pillRect, 16f, 16f, pillBg)
        borderPaint.color = AndroidColor.argb(140, 255, 213, 0)
        borderPaint.strokeWidth = 1.5f
        canvas.drawRoundRect(pillRect, 16f, 16f, borderPaint)
        canvas.drawText("⚡ FLASH NEWS", hdrTextX + 14f, currY + 38f, tagPaint)

        titlePaint.textSize = titleSize
        val titleLines = wrapLines(cleanTitle, titlePaint, cardWidth - goldWedgeW - textPadL - textPadR - hdrSkew)
        var ty = currY + 52f + titleSize
        titleLines.forEach { line ->
            drawHighlightedLine(
                canvas = canvas,
                line = line,
                startX = hdrTextX,
                y = ty,
                paint = titlePaint,
                tamilColor = AndroidColor.WHITE,
                highlightColor = AndroidColor.parseColor("#FFE600"),
            )
            ty += titleSize * 1.22f
        }

        currY += headerH + 16f

        bulletPaint.textSize = bulletSize
        val bLineH = bulletSize * 1.28f
        val bMaxW = cardWidth - tabWidth - textPadL - textPadR - skew

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 26f
            textAlign = Paint.Align.CENTER
        }
        val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        cleanBullets.forEachIndexed { idx, sentence ->
            val bh = bulletHeights.getOrElse(idx) { 74f }
            val rowSkew = skew * (bh / 100f).coerceIn(0.65f, 1.15f)
            val isActive = idx == activeIndex
            val rowLeft = if (isActive) cardLeft - 2f else cardLeft - 6f
            val rowWidth = if (isActive) cardWidth + 16f else cardWidth

            val fullCardPath = slantedRect(rowLeft, currY, rowWidth, bh, rowSkew)
            canvas.drawPath(fullCardPath, if (isActive) activeGlowPaint else shadowPaint)

            val tabPath = slantedRect(rowLeft, currY, tabWidth, bh, rowSkew)
            val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = if (isActive) {
                    LinearGradient(
                        rowLeft, currY, rowLeft + tabWidth, currY + bh,
                        AndroidColor.parseColor("#FF1744"),
                        AndroidColor.parseColor("#A80028"),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        rowLeft, currY, rowLeft + tabWidth, currY + bh,
                        AndroidColor.parseColor("#2A0A8F"),
                        AndroidColor.parseColor("#12044A"),
                        Shader.TileMode.CLAMP,
                    )
                }
            }
            canvas.drawPath(tabPath, tabPaint)

            stripPaint.color = if (isActive) AndroidColor.WHITE else AndroidColor.parseColor("#FFB300")
            val stripPath = slantedRect(rowLeft + tabWidth - 5f, currY, 6f, bh, rowSkew)
            canvas.drawPath(stripPath, stripPaint)

            numPaint.color = if (isActive) AndroidColor.WHITE else AndroidColor.parseColor("#FFE600")
            val numLabel = String.format("%02d", idx + 1)
            canvas.drawText(
                numLabel,
                rowLeft + tabWidth / 2f + rowSkew * 0.45f,
                currY + bh / 2f + 9f,
                numPaint,
            )

            val bodyX = rowLeft + tabWidth
            val bodyW = rowWidth - tabWidth
            val bodyPath = slantedRect(bodyX, currY, bodyW, bh, rowSkew)
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = if (isActive) {
                    LinearGradient(
                        bodyX, currY, bodyX + bodyW, currY + bh,
                        AndroidColor.parseColor("#FFF8B0"),
                        AndroidColor.parseColor("#FFD54F"),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        bodyX, currY, bodyX + bodyW, currY + bh,
                        AndroidColor.parseColor("#F5F7FC"),
                        AndroidColor.parseColor("#DCE5F5"),
                        Shader.TileMode.CLAMP,
                    )
                }
            }
            canvas.drawPath(bodyPath, bodyPaint)

            // Glossy top bevel on box
            canvas.save()
            canvas.clipPath(fullCardPath)
            val rowSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.argb(if (isActive) 60 else 45, 255, 255, 255)
            }
            canvas.drawRect(rowLeft, currY, rowLeft + rowWidth + rowSkew, currY + bh * 0.14f, rowSheen)
            canvas.restore()

            borderPaint.color = if (isActive) AndroidColor.parseColor("#FFFF00") else AndroidColor.parseColor("#AAFFFFFF")
            borderPaint.strokeWidth = if (isActive) 4.4f else 1.8f
            canvas.drawPath(fullCardPath, borderPaint)

            val lines = wrapLines(sentence, bulletPaint, bMaxW)
            var ly = currY + 18f + bulletSize * 0.9f
            val bxText = bodyX + textPadL + rowSkew * 0.32f
            lines.forEach { line ->
                drawHighlightedLine(
                    canvas = canvas,
                    line = line,
                    startX = bxText,
                    y = ly,
                    paint = bulletPaint,
                    tamilColor = if (isActive) AndroidColor.parseColor("#0A0424") else AndroidColor.parseColor("#1A2044"),
                    highlightColor = if (isActive) AndroidColor.parseColor("#C80028") else AndroidColor.parseColor("#B01238"),
                )
                ly += bLineH
            }

            currY += bh + rowGap
        }
    }

    private fun drawBroadcastCards(
        canvas: Canvas,
        cleanTitle: String,
        cleanBullets: List<String>,
        activeIndex: Int,
    ) {
        val cardLeft = 86f
        val cardWidth = 980f
        val accentW = 16f
        val badgeArea = 94f
        val padL = 32f
        val padR = 28f
        val topMargin = 72f
        val maxBottom = 935f
        val radius = 20f
        val rowGap = if (cleanBullets.size >= 3) 12f else 14f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 0f, 3f, AndroidColor.argb(185, 0, 0, 0))
        }
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 20f
            color = AndroidColor.parseColor("#FFE600")
            letterSpacing = 0.11f
        }
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var titleSize = 42f
        var bulletSize = if (cleanBullets.size >= 3) 29f else 33f
        var headerH = 140f
        var bulletHeights = emptyList<Float>()

        while (bulletSize >= 16f) {
            titlePaint.textSize = titleSize
            val titleMaxW = cardWidth - accentW - padL - padR
            val titleLines = wrapLines(cleanTitle, titlePaint, titleMaxW)
            headerH = 58f + titleLines.size * (titleSize * 1.22f) + 22f

            bulletPaint.textSize = bulletSize
            val bLineH = bulletSize * 1.28f
            val bMaxW = cardWidth - accentW - badgeArea - padR
            bulletHeights = cleanBullets.map { b ->
                val bLines = wrapLines(b, bulletPaint, bMaxW)
                bLines.size * bLineH + 32f
            }

            val totalH = headerH + 16f + bulletHeights.sum() + (maxOf(0, cleanBullets.size - 1) * rowGap)
            if (topMargin + totalH <= maxBottom) break

            bulletSize -= 1.5f
            titleSize = (titleSize - 1.5f).coerceAtLeast(26f)
        }

        val totalBlockH = headerH + 16f + bulletHeights.sum() + maxOf(0, cleanBullets.size - 1) * rowGap
        var currY = topMargin + ((maxBottom - topMargin - totalBlockH).coerceAtLeast(0f) * 0.42f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(160, 0, 0, 0)
            setShadowLayer(22f, 4f, 10f, AndroidColor.argb(195, 0, 0, 0))
        }
        val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(205, 255, 213, 0)
            setShadowLayer(28f, 0f, 0f, AndroidColor.argb(240, 255, 193, 7))
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#FFC107")
        }
        val liveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#FF2E4D")
            setShadowLayer(8f, 0f, 0f, AndroidColor.parseColor("#FF2E4D"))
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Header Card
        val hdrRect = RectF(cardLeft, currY, cardLeft + cardWidth, currY + headerH)
        canvas.drawRoundRect(hdrRect, radius, radius, shadowPaint)
        val hdrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                hdrRect.left, hdrRect.top, hdrRect.right, hdrRect.bottom,
                intArrayOf(
                    AndroidColor.parseColor("#540011"),
                    AndroidColor.parseColor("#B80C2E"),
                    AndroidColor.parseColor("#6B0015"),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val hdrClip = Path().apply { addRoundRect(hdrRect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(hdrClip)
        canvas.drawRect(hdrRect, hdrPaint)
        // Top glass highlight
        val hdrSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, currY, 0f, currY + headerH * 0.45f,
                AndroidColor.argb(42, 255, 255, 255),
                AndroidColor.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(cardLeft, currY, cardLeft + cardWidth, currY + headerH * 0.45f, hdrSheen)
        canvas.drawRect(cardLeft, currY, cardLeft + accentW, currY + headerH, goldPaint)
        canvas.restore()

        borderPaint.color = AndroidColor.parseColor("#FFD54F")
        borderPaint.strokeWidth = 3.2f
        canvas.drawRoundRect(hdrRect, radius, radius, borderPaint)

        // Pill badge for "BREAKING FLASH NEWS"
        val hdrTextX = cardLeft + accentW + padL
        val badgeRect = RectF(hdrTextX, currY + 14f, hdrTextX + 324f, currY + 46f)
        val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(155, 10, 5, 12) }
        canvas.drawRoundRect(badgeRect, 16f, 16f, badgeBgPaint)
        borderPaint.color = AndroidColor.argb(150, 255, 213, 79)
        borderPaint.strokeWidth = 1.5f
        canvas.drawRoundRect(badgeRect, 16f, 16f, borderPaint)
        canvas.drawCircle(badgeRect.left + 20f, badgeRect.centerY(), 6.5f, liveDotPaint)
        canvas.drawText("BREAKING FLASH NEWS", badgeRect.left + 36f, badgeRect.centerY() + 7f, tagPaint)

        // Title with English Player/Team names highlighted in Gold
        titlePaint.textSize = titleSize
        val titleLines = wrapLines(cleanTitle, titlePaint, cardWidth - accentW - padL - padR)
        var ty = currY + 54f + titleSize
        titleLines.forEach { line ->
            drawHighlightedLine(
                canvas = canvas,
                line = line,
                startX = hdrTextX,
                y = ty,
                paint = titlePaint,
                tamilColor = AndroidColor.WHITE,
                highlightColor = AndroidColor.parseColor("#FFE600"),
            )
            ty += titleSize * 1.22f
        }

        currY += headerH + 16f

        bulletPaint.textSize = bulletSize
        val bLineH = bulletSize * 1.28f
        val bMaxW = cardWidth - accentW - badgeArea - padR

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 27f
            textAlign = Paint.Align.CENTER
        }
        val badgeCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val badgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        cleanBullets.forEachIndexed { idx, sentence ->
            val bh = bulletHeights.getOrElse(idx) { 74f }
            val isActive = idx == activeIndex
            val rowW = if (isActive) cardWidth + 16f else cardWidth
            val rowRect = RectF(cardLeft, currY, cardLeft + rowW, currY + bh)

            canvas.drawRoundRect(rowRect, radius, radius, if (isActive) activeGlowPaint else shadowPaint)

            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = if (isActive) {
                    LinearGradient(
                        rowRect.left, rowRect.top, rowRect.right, rowRect.bottom,
                        intArrayOf(
                            AndroidColor.parseColor("#EE1842"),
                            AndroidColor.parseColor("#AE0729"),
                            AndroidColor.parseColor("#680015"),
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        rowRect.left, rowRect.top, rowRect.right, rowRect.bottom,
                        AndroidColor.parseColor("#EB11121A"),
                        AndroidColor.parseColor("#E61A1B27"),
                        Shader.TileMode.CLAMP,
                    )
                }
            }
            val rowClip = Path().apply { addRoundRect(rowRect, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(rowClip)
            canvas.drawRect(rowRect, bodyPaint)

            // Glass top sheen
            val rowSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, currY, 0f, currY + bh * 0.42f,
                    AndroidColor.argb(if (isActive) 34 else 18, 255, 255, 255),
                    AndroidColor.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(rowRect.left, currY, rowRect.right, currY + bh * 0.42f, rowSheen)

            accentPaint.color = if (isActive) AndroidColor.parseColor("#FFE600") else AndroidColor.parseColor("#99E5173F")
            canvas.drawRect(cardLeft, currY, cardLeft + accentW, currY + bh, accentPaint)
            canvas.restore()

            borderPaint.color = if (isActive) AndroidColor.parseColor("#FFE600") else AndroidColor.argb(120, 255, 193, 7)
            borderPaint.strokeWidth = if (isActive) 4.4f else 1.6f
            canvas.drawRoundRect(rowRect, radius, radius, borderPaint)

            val badgeCx = cardLeft + accentW + badgeArea / 2f
            val badgeCy = currY + bh / 2f
            badgeCirclePaint.color = if (isActive) AndroidColor.parseColor("#FFE600") else AndroidColor.parseColor("#2A2633")
            canvas.drawCircle(badgeCx, badgeCy, 27f, badgeCirclePaint)
            badgeRingPaint.strokeWidth = 2.5f
            badgeRingPaint.color = if (isActive) AndroidColor.WHITE else AndroidColor.parseColor("#D9A800")
            canvas.drawCircle(badgeCx, badgeCy, 27f, badgeRingPaint)

            numPaint.color = if (isActive) AndroidColor.parseColor("#9A0020") else AndroidColor.parseColor("#FFE082")
            canvas.drawText(String.format("%02d", idx + 1), badgeCx, badgeCy + 10f, numPaint)

            val lines = wrapLines(sentence, bulletPaint, bMaxW)
            var ly = currY + 18f + bulletSize * 0.9f
            val bxText = cardLeft + accentW + badgeArea
            if (isActive) {
                bulletPaint.setShadowLayer(4f, 0f, 2f, AndroidColor.argb(150, 0, 0, 0))
            } else {
                bulletPaint.clearShadowLayer()
            }
            lines.forEach { line ->
                drawHighlightedLine(
                    canvas = canvas,
                    line = line,
                    startX = bxText,
                    y = ly,
                    paint = bulletPaint,
                    tamilColor = if (isActive) AndroidColor.WHITE else AndroidColor.argb(232, 240, 244, 255),
                    highlightColor = if (isActive) AndroidColor.parseColor("#FFE600") else AndroidColor.parseColor("#FFD54F"),
                )
                ly += bLineH
            }
            bulletPaint.clearShadowLayer()

            currY += bh + rowGap
        }
    }

    /**
     * Composites the player cutout PNG HIGHER UP on the right side of the 1920x1080
     * News Alert banner with dual studio rim spotlight & broadcast ring behind the shoulders.
     */
    private fun drawPlayerCutoutOnRight(
        canvas: Canvas,
        cutoutUri: String?,
        w: Float,
        h: Float,
        isBroadcast: Boolean,
    ) {
        if (cutoutUri.isNullOrBlank()) return
        val path = Uri.parse(cutoutUri).path ?: cutoutUri
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return

        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return
        val faded = applySoftBottomFadeCopy(decoded, 0.16f)
        if (faded !== decoded) decoded.recycle()

        try {
            val zoneLeft = 1095f
            val zoneRight = w - 32f
            val zoneTop = 36f
            val zoneBottom = h * 0.85f
            val zoneW = (zoneRight - zoneLeft).coerceAtLeast(100f)
            val zoneH = (zoneBottom - zoneTop).coerceAtLeast(100f)

            val scale = min(zoneW / faded.width.toFloat(), zoneH / faded.height.toFloat())
            val dw = faded.width * scale
            val dh = faded.height * scale
            val left = zoneLeft + (zoneW - dw) / 2f
            val top = zoneTop + (zoneH - dh) * 0.24f
            val cx = left + dw * 0.5f
            val cy = top + dh * 0.44f
            val haloR = max(dw, dh) * 0.65f

            // Studio backlight halo + broadcast orbital ring
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx,
                    cy,
                    haloR,
                    intArrayOf(
                        if (isBroadcast) AndroidColor.argb(125, 255, 193, 7) else AndroidColor.argb(125, 90, 210, 255),
                        if (isBroadcast) AndroidColor.argb(55, 229, 23, 63) else AndroidColor.argb(45, 0, 140, 255),
                        AndroidColor.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(cx, cy, haloR, haloPaint)

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = if (isBroadcast) AndroidColor.argb(65, 255, 213, 79) else AndroidColor.argb(75, 110, 215, 255)
            }
            canvas.drawCircle(cx, cy, haloR * 0.64f, ringPaint)
            ringPaint.strokeWidth = 1.5f
            ringPaint.color = if (isBroadcast) AndroidColor.argb(38, 229, 23, 63) else AndroidColor.argb(42, 255, 255, 255)
            canvas.drawCircle(cx, cy, haloR * 0.78f, ringPaint)

            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(faded, null, RectF(left, top, left + dw, top + dh), bmpPaint)
        } finally {
            faded.recycle()
        }
    }

    private fun applySoftBottomFadeCopy(src: Bitmap, fadeFraction: Float): Bitmap {
        val copy = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val bw = copy.width
        val bh = copy.height
        val fadeRows = (bh * fadeFraction).toInt().coerceAtLeast(1)
        val startY = bh - fadeRows
        val row = IntArray(bw)
        for (y in startY until bh) {
            val factor = (1f - (y - startY).toFloat() / fadeRows.toFloat()).coerceIn(0f, 1f)
            val smooth = factor * factor
            copy.getPixels(row, 0, bw, 0, y, bw, 1)
            for (x in 0 until bw) {
                val p = row[x]
                val a = AndroidColor.alpha(p)
                if (a > 0) {
                    val na = (a * smooth).toInt().coerceIn(0, 255)
                    row[x] = (na shl 24) or (p and 0x00FFFFFF)
                }
            }
            copy.setPixels(row, 0, bw, 0, y, bw, 1)
        }
        return copy
    }

    private fun drawNightBroadcastBackground(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                AndroidColor.parseColor("#040407"),
                AndroidColor.parseColor("#16070E"),
                AndroidColor.parseColor("#0A0B15"),
                AndroidColor.parseColor("#020204"),
            ),
            floatArrayOf(0f, 0.40f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        p.shader = RadialGradient(
            w * 0.20f, h * 0.30f, w * 0.40f,
            intArrayOf(AndroidColor.argb(128, 229, 23, 63), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.20f, h * 0.30f, w * 0.40f, p)

        p.shader = RadialGradient(
            w * 0.80f, h * 0.38f, w * 0.36f,
            intArrayOf(AndroidColor.argb(115, 255, 193, 7), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.80f, h * 0.38f, w * 0.36f, p)
        p.shader = null

        // Subtle stadium spotlight beams
        p.shader = LinearGradient(
            240f, 0f, 700f, 840f,
            intArrayOf(AndroidColor.argb(45, 255, 213, 79), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        val beamL = Path().apply {
            moveTo(90f, 0f)
            lineTo(320f, 0f)
            lineTo(790f, 860f)
            lineTo(350f, 860f)
            close()
        }
        canvas.drawPath(beamL, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = AndroidColor.argb(65, 255, 213, 79)
        val arch = Path().apply {
            moveTo(-100f, 560f)
            quadTo(w * 0.50f, 150f, w + 100f, 560f)
        }
        canvas.drawPath(arch, p)
        p.strokeWidth = 12f
        p.color = AndroidColor.argb(34, 229, 23, 63)
        val arch2 = Path().apply {
            moveTo(-80f, 680f)
            quadTo(w * 0.50f, 320f, w + 80f, 680f)
        }
        canvas.drawPath(arch2, p)
        p.style = Paint.Style.FILL

        p.color = AndroidColor.argb(65, 255, 235, 180)
        for (i in 0..38) {
            val bx = (i * 53f) % w
            val by = 560f + ((i * 31) % 250)
            canvas.drawCircle(bx, by, 2.5f + (i % 3), p)
        }

        // Perspective night stadium turf
        val turfPath = Path().apply {
            moveTo(0f, 945f)
            quadTo(w * 0.50f, 820f, w, 945f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.save()
        canvas.clipPath(turfPath)
        p.shader = RadialGradient(
            w * 0.50f, 860f, w * 0.65f,
            intArrayOf(
                AndroidColor.parseColor("#1D6E38"),
                AndroidColor.parseColor("#0E3C1D"),
                AndroidColor.parseColor("#041409"),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 800f, w, h, p)
        p.shader = null
        val centerTopX = w * 0.50f
        val centerTopY = 780f
        for (s in -8..8 step 2) {
            p.color = AndroidColor.argb(24, 255, 255, 255)
            val stripe = Path().apply {
                moveTo(centerTopX + s * 65f, centerTopY)
                lineTo(centerTopX + (s + 1) * 65f, centerTopY)
                lineTo(centerTopX + (s + 1) * 210f, h + 40f)
                lineTo(centerTopX + s * 210f, h + 40f)
                close()
            }
            canvas.drawPath(stripe, p)
        }
        canvas.restore()

        // Glowing crimson + gold horizon rim line
        p.style = Paint.Style.STROKE
        p.strokeWidth = 7f
        p.shader = LinearGradient(
            0f, 900f, w, 900f,
            intArrayOf(
                AndroidColor.parseColor("#B80C2E"),
                AndroidColor.parseColor("#FFD54F"),
                AndroidColor.parseColor("#E5173F"),
            ),
            null, Shader.TileMode.CLAMP,
        )
        val rimPath = Path().apply {
            moveTo(0f, 945f)
            quadTo(w * 0.50f, 820f, w, 945f)
        }
        canvas.drawPath(rimPath, p)
        p.shader = null
        p.style = Paint.Style.FILL
    }

    private fun drawDefaultStadiumBackground(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.alpha = 255
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                AndroidColor.parseColor("#02083D"),
                AndroidColor.parseColor("#0B2A9E"),
                AndroidColor.parseColor("#091E73"),
                AndroidColor.parseColor("#030A36"),
            ),
            floatArrayOf(0f, 0.38f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = AndroidColor.argb(55, 110, 195, 255)
        val arch1 = Path().apply {
            moveTo(-100f, 520f)
            quadTo(w * 0.50f, 120f, w + 100f, 520f)
        }
        canvas.drawPath(arch1, p)

        p.strokeWidth = 10f
        p.color = AndroidColor.argb(34, 0, 210, 255)
        val arch2 = Path().apply {
            moveTo(-80f, 650f)
            quadTo(w * 0.50f, 290f, w + 80f, 650f)
        }
        canvas.drawPath(arch2, p)

        p.style = Paint.Style.FILL
        p.color = AndroidColor.argb(120, 2, 10, 56)
        val leftRoof = Path().apply {
            moveTo(0f, 180f)
            lineTo(540f, 470f)
            lineTo(0f, 700f)
            close()
        }
        canvas.drawPath(leftRoof, p)

        val rightRoof = Path().apply {
            moveTo(w, 160f)
            lineTo(w - 580f, 470f)
            lineTo(w, 720f)
            close()
        }
        canvas.drawPath(rightRoof, p)

        p.alpha = 255
        p.shader = RadialGradient(
            w * 0.28f, h * 0.34f, w * 0.36f,
            intArrayOf(AndroidColor.argb(115, 45, 165, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.28f, h * 0.34f, w * 0.36f, p)

        p.shader = RadialGradient(
            w * 0.76f, h * 0.42f, w * 0.35f,
            intArrayOf(AndroidColor.argb(165, 70, 185, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.76f, h * 0.42f, w * 0.35f, p)

        p.shader = LinearGradient(
            180f, 0f, 660f, 840f,
            intArrayOf(AndroidColor.argb(95, 130, 230, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        val beamL = Path().apply {
            moveTo(60f, 0f)
            lineTo(360f, 0f)
            lineTo(840f, 880f)
            lineTo(320f, 880f)
            close()
        }
        canvas.drawPath(beamL, p)

        p.shader = LinearGradient(
            w - 180f, 0f, w - 660f, 840f,
            intArrayOf(AndroidColor.argb(105, 130, 230, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        val beamR = Path().apply {
            moveTo(w - 380f, 0f)
            lineTo(w - 60f, 0f)
            lineTo(w - 300f, 880f)
            lineTo(w - 860f, 880f)
            close()
        }
        canvas.drawPath(beamR, p)
        p.shader = null

        p.color = AndroidColor.argb(48, 160, 220, 255)
        for (i in 0..42) {
            val bx = (i * 47f) % w
            val by = 540f + ((i * 29) % 270)
            canvas.drawCircle(bx, by, 2.5f + (i % 4), p)
        }

        val turfPath = Path().apply {
            moveTo(0f, 940f)
            quadTo(w * 0.50f, 800f, w, 940f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.save()
        canvas.clipPath(turfPath)

        p.alpha = 255
        p.shader = RadialGradient(
            w * 0.50f, 845f, w * 0.68f,
            intArrayOf(
                AndroidColor.parseColor("#B8F238"),
                AndroidColor.parseColor("#5DC71C"),
                AndroidColor.parseColor("#1E750A"),
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 780f, w, h, p)
        p.shader = null

        val centerTopX = w * 0.50f
        val centerTopY = 760f
        for (s in -8..8 step 2) {
            p.color = AndroidColor.argb(40, 255, 255, 255)
            val stripe = Path().apply {
                moveTo(centerTopX + s * 65f, centerTopY)
                lineTo(centerTopX + (s + 1) * 65f, centerTopY)
                lineTo(centerTopX + (s + 1) * 210f, h + 40f)
                lineTo(centerTopX + s * 210f, h + 40f)
                close()
            }
            canvas.drawPath(stripe, p)
        }
        canvas.restore()

        p.alpha = 255
        p.style = Paint.Style.STROKE
        p.strokeWidth = 7f
        p.color = AndroidColor.argb(200, 215, 255, 95)
        val rimPath = Path().apply {
            moveTo(0f, 940f)
            quadTo(w * 0.50f, 800f, w, 940f)
        }
        canvas.drawPath(rimPath, p)
        p.style = Paint.Style.FILL
    }

    private fun slantedRect(x: Float, y: Float, w: Float, h: Float, skew: Float): Path =
        Path().apply {
            moveTo(x + skew, y)
            lineTo(x + w + skew, y)
            lineTo(x + w, y + h)
            lineTo(x, y + h)
            close()
        }

    private fun drawHighlightedLine(
        canvas: Canvas,
        line: String,
        startX: Float,
        y: Float,
        paint: Paint,
        tamilColor: Int,
        highlightColor: Int,
    ) {
        val tokens = line.split(" ")
        var cx = startX
        val spaceW = paint.measureText(" ")
        tokens.forEachIndexed { idx, tok ->
            paint.color = if (highlightWordRegex.containsMatchIn(tok)) highlightColor else tamilColor
            canvas.drawText(tok, cx, y, paint)
            cx += paint.measureText(tok)
            if (idx < tokens.lastIndex) cx += spaceW
        }
    }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val test = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(test) > maxWidth && current.isNotEmpty()) {
                lines.add(current)
                current = word
            } else {
                current = test
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }
}
