package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64

object CricketAssets {
    // கார்ட்டூன் பேட்ஸ்மேன் பேஸ்64 (தேவையெனில் index.html-ல் உள்ள முழு பேஸ்64-ஐ இங்கே வைக்கலாம்)
    private const val BATTER_LOGO_B64 = ""
    private const val BOWLER_LOGO_B64 = ""

    private var batterBitmap: Bitmap? = null
    private var bowlerBitmap: Bitmap? = null

    fun getBatterLogo(): Bitmap {
        if (batterBitmap != null && !batterBitmap!!.isRecycled) return batterBitmap!!
        batterBitmap = runCatching {
            if (BATTER_LOGO_B64.length > 50) {
                val clean = BATTER_LOGO_B64.substringAfter("base64,").trim()
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
        }.getOrNull() ?: createFallbackIllustration("BATTER")
        return batterBitmap!!
    }

    fun getBowlerLogo(): Bitmap {
        if (bowlerBitmap != null && !bowlerBitmap!!.isRecycled) return bowlerBitmap!!
        bowlerBitmap = runCatching {
            if (BOWLER_LOGO_B64.length > 50) {
                val clean = BOWLER_LOGO_B64.substringAfter("base64,").trim()
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else null
        }.getOrNull() ?: createFallbackIllustration("BOWLER")
        return bowlerBitmap!!
    }

    private fun createFallbackIllustration(type: String): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // மஞ்சள் நிற வட்டப் பின்னணி (Yellow Sun Background)
        paint.color = 0xFFFFD700.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size * 0.44f, paint)

        // நீல நிற ஜெர்சி மற்றும் ஹெல்மெட்
        paint.color = 0xFF1976D2.toInt()
        canvas.drawCircle(size / 2f, size * 0.30f, size * 0.14f, paint)

        val bodyPath = Path().apply {
            moveTo(size * 0.28f, size * 0.85f)
            lineTo(size * 0.35f, size * 0.46f)
            lineTo(size * 0.65f, size * 0.46f)
            lineTo(size * 0.72f, size * 0.85f)
            close()
        }
        canvas.drawPath(bodyPath, paint)

        // மட்டை (Bat) / பந்து
        if (type == "BOWLER") {
            paint.color = 0xFFD32F2F.toInt()
            canvas.drawCircle(size * 0.72f, size * 0.35f, size * 0.08f, paint)
        } else {
            paint.color = 0xFF8D6E63.toInt()
            paint.strokeWidth = 24f
            paint.style = Paint.Style.STROKE
            canvas.drawLine(size * 0.65f, size * 0.58f, size * 0.86f, size * 0.26f, paint)
        }

        return bmp
    }
}
