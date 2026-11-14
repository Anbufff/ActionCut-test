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
 * Full 1920x1080 Stadium Background + up to 4 Flash News Key-Point Boxes (with active
 * box highlighting frames _0.._3 + .meta timing) + Higher Player Cutout on the Right Side.
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

        // Render background + higher player cutout ONCE and reuse for highlight states
        val baseBg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bgCanvas = Canvas(baseBg)
        if (isBroadcast) {
            drawNightBroadcastBackground(bgCanvas, width.toFloat(), height.toFloat())
        } else {
            drawDefaultStadiumBackground(bgCanvas, width.toFloat(), height.toFloat())
        }
        drawPlayerCutoutOnRight(bgCanvas, playerCutoutUri, width.toFloat(), height.toFloat())

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
        val cardLeft = 108f
        val cardWidth = 960f
        val goldWedgeW = 86f
        val tabWidth = 80f
        val skew = 34f
        val textPadL = 26f
        val textPadR = 28f
        val topMargin = 92f
        val maxBottom = 945f
        val rowGap = if (cleanBullets.size >= 3) 10f else 12f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 0f, 3f, AndroidColor.argb(180, 0, 0, 0))
        }
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 22f
            color = AndroidColor.parseColor("#FFD500")
            letterSpacing = 0.08f
        }
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var titleSize = 42f
        var bulletSize = if (cleanBullets.size >= 3) 29f else 32f
        var headerH = 136f
        var bulletHeights = emptyList<Float>()

        while (bulletSize >= 16f) {
            titlePaint.textSize = titleSize
            val titleMaxW = cardWidth - goldWedgeW - textPadL - textPadR - skew
            val titleLines = wrapLines(cleanTitle, titlePaint, titleMaxW)
            headerH = 50f + titleLines.size * (titleSize * 1.22f) + 20f

            bulletPaint.textSize = bulletSize
            val bLineH = bulletSize * 1.28f
            val bMaxW = cardWidth - tabWidth - textPadL - textPadR - skew
            bulletHeights = cleanBullets.map { b ->
                val bLines = wrapLines(b, bulletPaint, bMaxW)
                bLines.size * bLineH + 30f
            }

            val totalH = headerH + 14f + bulletHeights.sum() + (maxOf(0, cleanBullets.size - 1) * rowGap)
            if (topMargin + totalH <= maxBottom) break

            bulletSize -= 1.5f
            titleSize = (titleSize - 1.5f).coerceAtLeast(26f)
        }

        val totalBlockH = headerH + 14f + bulletHeights.sum() + maxOf(0, cleanBullets.size - 1) * rowGap
        var currY = topMargin + ((maxBottom - topMargin - totalBlockH).coerceAtLeast(0f) * 0.35f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(145, 0, 0, 0)
            setShadowLayer(20f, 5f, 9f, AndroidColor.argb(180, 0, 0, 0))
        }
        val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(190, 255, 213, 0)
            setShadowLayer(26f, 0f, 0f, AndroidColor.argb(235, 255, 213, 0))
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

        borderPaint.color = AndroidColor.parseColor("#E6F0FF")
        borderPaint.strokeWidth = 3f
        canvas.drawPath(slantedRect(cardLeft, currY, cardWidth, headerH, hdrSkew), borderPaint)

        val hdrTextX = hdrMainX + textPadL
        canvas.drawText("⚡ FLASH NEWS", hdrTextX, currY + 36f, tagPaint)

        titlePaint.textSize = titleSize
        titlePaint.color = AndroidColor.WHITE
        val titleLines = wrapLines(cleanTitle, titlePaint, cardWidth - goldWedgeW - textPadL - textPadR - hdrSkew)
        var ty = currY + 46f + titleSize
        titleLines.forEach { line ->
            canvas.drawText(line, hdrTextX, ty, titlePaint)
            ty += titleSize * 1.22f
        }

        currY += headerH + 14f

        bulletPaint.textSize = bulletSize
        val bLineH = bulletSize * 1.28f
        val bMaxW = cardWidth - tabWidth - textPadL - textPadR - skew

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 25f
            textAlign = Paint.Align.CENTER
        }
        val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        cleanBullets.forEachIndexed { idx, sentence ->
            val bh = bulletHeights.getOrElse(idx) { 74f }
            val rowSkew = skew * (bh / 100f).coerceIn(0.65f, 1.15f)
            val rowLeft = cardLeft - 6f
            val isActive = idx == activeIndex

            val fullCardPath = slantedRect(rowLeft, currY, cardWidth, bh, rowSkew)
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
            val bodyW = cardWidth - tabWidth
            val bodyPath = slantedRect(bodyX, currY, bodyW, bh, rowSkew)
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = if (isActive) {
                    LinearGradient(
                        bodyX, currY, bodyX + bodyW, currY + bh,
                        AndroidColor.parseColor("#FFF59D"),
                        AndroidColor.parseColor("#FFD54F"),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        bodyX, currY, bodyX + bodyW, currY + bh,
                        AndroidColor.parseColor("#ECEFF8"),
                        AndroidColor.parseColor("#D5DFF0"),
                        Shader.TileMode.CLAMP,
                    )
                }
            }
            canvas.drawPath(bodyPath, bodyPaint)

            borderPaint.color = if (isActive) AndroidColor.parseColor("#FFFF00") else AndroidColor.parseColor("#99FFFFFF")
            borderPaint.strokeWidth = if (isActive) 4.2f else 1.8f
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
                    tamilColor = if (isActive) AndroidColor.parseColor("#0A0424") else AndroidColor.parseColor("#1D2247"),
                    highlightColor = if (isActive) AndroidColor.parseColor("#C80028") else AndroidColor.parseColor("#9C1A3C"),
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
        val cardLeft = 108f
        val cardWidth = 960f
        val accentW = 16f
        val badgeArea = 92f
        val padL = 32f
        val padR = 28f
        val topMargin = 92f
        val maxBottom = 945f
        val radius = 20f
        val rowGap = if (cleanBullets.size >= 3) 10f else 12f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = AndroidColor.WHITE
            setShadowLayer(6f, 0f, 3f, AndroidColor.argb(180, 0, 0, 0))
        }
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 22f
            color = AndroidColor.parseColor("#FFD54F")
            letterSpacing = 0.10f
        }
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var titleSize = 42f
        var bulletSize = if (cleanBullets.size >= 3) 29f else 32f
        var headerH = 136f
        var bulletHeights = emptyList<Float>()

        while (bulletSize >= 16f) {
            titlePaint.textSize = titleSize
            val titleMaxW = cardWidth - accentW - padL - padR
            val titleLines = wrapLines(cleanTitle, titlePaint, titleMaxW)
            headerH = 52f + titleLines.size * (titleSize * 1.22f) + 20f

            bulletPaint.textSize = bulletSize
            val bLineH = bulletSize * 1.28f
            val bMaxW = cardWidth - accentW - badgeArea - padR
            bulletHeights = cleanBullets.map { b ->
                val bLines = wrapLines(b, bulletPaint, bMaxW)
                bLines.size * bLineH + 30f
            }

            val totalH = headerH + 14f + bulletHeights.sum() + (maxOf(0, cleanBullets.size - 1) * rowGap)
            if (topMargin + totalH <= maxBottom) break

            bulletSize -= 1.5f
            titleSize = (titleSize - 1.5f).coerceAtLeast(26f)
        }

        val totalBlockH = headerH + 14f + bulletHeights.sum() + maxOf(0, cleanBullets.size - 1) * rowGap
        var currY = topMargin + ((maxBottom - topMargin - totalBlockH).coerceAtLeast(0f) * 0.35f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(150, 0, 0, 0)
            setShadowLayer(22f, 4f, 10f, AndroidColor.argb(190, 0, 0, 0))
        }
        val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(195, 255, 213, 0)
            setShadowLayer(26f, 0f, 0f, AndroidColor.argb(235, 255, 193, 7))
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#FFC107")
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        val hdrRect = RectF(cardLeft, currY, cardLeft + cardWidth, currY + headerH)
        canvas.drawRoundRect(hdrRect, radius, radius, shadowPaint)
        val hdrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                hdrRect.left, hdrRect.top, hdrRect.right, hdrRect.bottom,
                intArrayOf(
                    AndroidColor.parseColor("#5A0012"),
                    AndroidColor.parseColor("#B80C2E"),
                    AndroidColor.parseColor("#6E0016"),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val hdrClip = Path().apply { addRoundRect(hdrRect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(hdrClip)
        canvas.drawRect(hdrRect, hdrPaint)
        canvas.drawRect(cardLeft, currY, cardLeft + accentW, currY + headerH, goldPaint)
        canvas.restore()
        borderPaint.color = AndroidColor.parseColor("#FFD54F")
        borderPaint.strokeWidth = 3f
        canvas.drawRoundRect(hdrRect, radius, radius, borderPaint)

        val hdrTextX = cardLeft + accentW + padL
        canvas.drawCircle(hdrTextX + 8f, currY + 30f, 8f, goldPaint)
        canvas.drawText("BREAKING FLASH NEWS", hdrTextX + 28f, currY + 37f, tagPaint)

        titlePaint.textSize = titleSize
        val titleLines = wrapLines(cleanTitle, titlePaint, cardWidth - accentW - padL - padR)
        var ty = currY + 48f + titleSize
        titleLines.forEach { line ->
            canvas.drawText(line, hdrTextX, ty, titlePaint)
            ty += titleSize * 1.22f
        }

        currY += headerH + 14f

        bulletPaint.textSize = bulletSize
        val bLineH = bulletSize * 1.28f
        val bMaxW = cardWidth - accentW - badgeArea - padR

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 27f
            textAlign = Paint.Align.CENTER
        }
        val badgeCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        cleanBullets.forEachIndexed { idx, sentence ->
            val bh = bulletHeights.getOrElse(idx) { 74f }
            val rowRect = RectF(cardLeft, currY, cardLeft + cardWidth, currY + bh)
            val isActive = idx == activeIndex

            canvas.drawRoundRect(rowRect, radius, radius, if (isActive) activeGlowPaint else shadowPaint)

            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = if (isActive) {
                    LinearGradient(
                        rowRect.left, rowRect.top, rowRect.right, rowRect.bottom,
                        intArrayOf(
                            AndroidColor.parseColor("#E5173F"),
                            AndroidColor.parseColor("#AD082A"),
                            AndroidColor.parseColor("#6E0016"),
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                } else {
                    LinearGradient(
                        rowRect.left, rowRect.top, rowRect.right, rowRect.bottom,
                        AndroidColor.parseColor("#DC0E0E14"),
                        AndroidColor.parseColor("#DC181520"),
                        Shader.TileMode.CLAMP,
                    )
                }
            }
            val rowClip = Path().apply { addRoundRect(rowRect, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(rowClip)
            canvas.drawRect(rowRect, bodyPaint)
            accentPaint.color = if (isActive) AndroidColor.parseColor("#FFE600") else AndroidColor.parseColor("#99E5173F")
            canvas.drawRect(cardLeft, currY, cardLeft + accentW, currY + bh, accentPaint)
            canvas.restore()

            borderPaint.color = if (isActive) AndroidColor.parseColor("#FFE600") else AndroidColor.argb(110, 255, 193, 7)
            borderPaint.strokeWidth = if (isActive) 4.2f else 1.6f
            canvas.drawRoundRect(rowRect, radius, radius, borderPaint)

            val badgeCx = cardLeft + accentW + badgeArea / 2f
            val badgeCy = currY + bh / 2f
            badgeCirclePaint.color = if (isActive) AndroidColor.WHITE else AndroidColor.parseColor("#D9A800")
            canvas.drawCircle(badgeCx, badgeCy, 27f, badgeCirclePaint)
            numPaint.color = if (isActive) AndroidColor.parseColor("#B80C2E") else AndroidColor.parseColor("#1A1A1A")
            canvas.drawText(String.format("%02d", idx + 1), badgeCx, badgeCy + 10f, numPaint)

            val lines = wrapLines(sentence, bulletPaint, bMaxW)
            var ly = currY + 18f + bulletSize * 0.9f
            val bxText = cardLeft + accentW + badgeArea
            lines.forEach { line ->
                drawHighlightedLine(
                    canvas = canvas,
                    line = line,
                    startX = bxText,
                    y = ly,
                    paint = bulletPaint,
                    tamilColor = if (isActive) AndroidColor.WHITE else AndroidColor.argb(200, 255, 255, 255),
                    highlightColor = if (isActive) AndroidColor.parseColor("#FFE600") else AndroidColor.parseColor("#FFD54F"),
                )
                ly += bLineH
            }

            currY += bh + rowGap
        }
    }

    /**
     * Composites the player cutout PNG HIGHER UP on the right side of the 1920x1080
     * News Alert banner so the head and shoulders sit prominently in the upper broadcast zone.
     */
    private fun drawPlayerCutoutOnRight(
        canvas: Canvas,
        cutoutUri: String?,
        w: Float,
        h: Float,
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
            val zoneBottom = h * 0.84f // Raised significantly higher above bottom pitch
            val zoneW = (zoneRight - zoneLeft).coerceAtLeast(100f)
            val zoneH = (zoneBottom - zoneTop).coerceAtLeast(100f)

            val scale = min(zoneW / faded.width.toFloat(), zoneH / faded.height.toFloat())
            val dw = faded.width * scale
            val dh = faded.height * scale
            val left = zoneLeft + (zoneW - dw) / 2f
            val top = zoneTop + (zoneH - dh) * 0.25f

            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    left + dw * 0.5f,
                    top + dh * 0.42f,
                    max(dw, dh) * 0.62f,
                    intArrayOf(AndroidColor.argb(95, 255, 215, 90), AndroidColor.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(left + dw * 0.5f, top + dh * 0.42f, max(dw, dh) * 0.62f, haloPaint)

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
                AndroidColor.parseColor("#050507"),
                AndroidColor.parseColor("#14070D"),
                AndroidColor.parseColor("#0B0B14"),
                AndroidColor.parseColor("#030304"),
            ),
            floatArrayOf(0f, 0.40f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        p.shader = RadialGradient(
            w * 0.20f, h * 0.30f, w * 0.38f,
            intArrayOf(AndroidColor.argb(120, 229, 23, 63), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.20f, h * 0.30f, w * 0.38f, p)

        p.shader = RadialGradient(
            w * 0.82f, h * 0.38f, w * 0.34f,
            intArrayOf(AndroidColor.argb(105, 255, 193, 7), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.82f, h * 0.38f, w * 0.34f, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = AndroidColor.argb(60, 255, 213, 79)
        val arch = Path().apply {
            moveTo(-100f, 560f)
            quadTo(w * 0.50f, 150f, w + 100f, 560f)
        }
        canvas.drawPath(arch, p)
        p.strokeWidth = 12f
        p.color = AndroidColor.argb(30, 229, 23, 63)
        val arch2 = Path().apply {
            moveTo(-80f, 680f)
            quadTo(w * 0.50f, 320f, w + 80f, 680f)
        }
        canvas.drawPath(arch2, p)
        p.style = Paint.Style.FILL

        p.color = AndroidColor.argb(60, 255, 235, 180)
        for (i in 0..38) {
            val bx = (i * 53f) % w
            val by = 560f + ((i * 31) % 250)
            canvas.drawCircle(bx, by, 2.5f + (i % 3), p)
        }

        val turfPath = Path().apply {
            moveTo(0f, 930f)
            quadTo(w * 0.50f, 800f, w, 930f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.save()
        canvas.clipPath(turfPath)
        p.shader = LinearGradient(
            0f, 780f, 0f, h,
            AndroidColor.parseColor("#14562B"),
            AndroidColor.parseColor("#031A0B"),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 760f, w, h, p)
        p.shader = null
        p.color = AndroidColor.argb(28, 255, 255, 255)
        var bandY = 800f
        while (bandY < h) {
            canvas.drawRect(0f, bandY, w, bandY + 22f, p)
            bandY += 44f
        }
        canvas.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = 7f
        p.color = AndroidColor.argb(220, 229, 23, 63)
        val rimPath = Path().apply {
            moveTo(0f, 930f)
            quadTo(w * 0.50f, 800f, w, 930f)
        }
        canvas.drawPath(rimPath, p)
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
            moveTo(0f, 920f)
            quadTo(w * 0.50f, 770f, w, 920f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.save()
        canvas.clipPath(turfPath)

        p.alpha = 255
        p.shader = RadialGradient(
            w * 0.50f, 830f, w * 0.68f,
            intArrayOf(
                AndroidColor.parseColor("#B8F238"),
                AndroidColor.parseColor("#5DC71C"),
                AndroidColor.parseColor("#1E750A"),
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 760f, w, h, p)
        p.shader = null

        val centerTopX = w * 0.50f
        val centerTopY = 740f
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
            moveTo(0f, 920f)
            quadTo(w * 0.50f, 770f, w, 920f)
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
