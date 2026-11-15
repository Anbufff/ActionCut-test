package com.actioncut.core.media.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.actioncut.core.model.Clip
import com.actioncut.core.model.Keyframes
import com.actioncut.core.model.PipAnimation
import com.actioncut.core.model.PipAnimations

/**
 * A static bitmap overlay that is only visible between [startUs] and [endUs]
 * (microseconds measured from the START OF THIS CLIP); outside that window it is drawn fully
 * transparent. Used for image stickers such as the cricket score card and the PiP photo.
 *
 * Media3 may hand effects timestamps that already include the clip's offset inside the whole
 * sequence (so the 2nd/3rd photo sees 8.8s, 17s ... instead of 0s). To be independent of that,
 * the first timestamp this overlay sees is treated as clip time 0.
 */
internal class TimedBitmapOverlay(
    private val bitmap: Bitmap,
    private val visible: OverlaySettings,
    private val hidden: OverlaySettings,
    private val startUs: Long,
    private val endUs: Long,
) : BitmapOverlay() {

    private var baseUs = Long.MIN_VALUE

    private fun clipTimeUs(presentationTimeUs: Long): Long {
        if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
        return (presentationTimeUs - baseUs).coerceAtLeast(0L)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        clipTimeUs(presentationTimeUs)
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (clipTimeUs(presentationTimeUs) in startUs until endUs) visible else hidden
}

/**
 * Animated bitmap overlay for PiP images on export. Renders [bitmap] (already cropped, flipped,
 * and scaled for `scale = 1f` relative to [targetWidth] x [targetHeight]) onto a full-frame
 * canvas so entrance animations (`SLIDE_LEFT`, `SLIDE_UP`, `ZOOM_IN`, `NONE`) and keyframes
 * play identically to the Compose preview, including off-screen slide start positions.
 */
internal class AnimatedPipBitmapOverlay(
    private val bitmap: Bitmap,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val clip: Clip,
    private val hostClipStartMs: Long,
    private val startUs: Long,
    private val endUs: Long,
) : BitmapOverlay() {

    private val visibleSettings: OverlaySettings = StaticOverlaySettings.Builder().build()
    private val hiddenSettings: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    private val placeholderBmp: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    private var baseUs = Long.MIN_VALUE
    private val animBmps = arrayOfNulls<Bitmap>(2)
    private var animIdx = 0
    private var staticFinalBmp: Bitmap? = null

    private fun clipTimeUs(presentationTimeUs: Long): Long {
        if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
        return (presentationTimeUs - baseUs).coerceAtLeast(0L)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val tUs = clipTimeUs(presentationTimeUs)
        if (tUs !in startUs until endUs) {
            if (tUs >= endUs) {
                animBmps[0]?.let { if (!it.isRecycled) it.recycle() }
                animBmps[0] = null
                animBmps[1]?.let { if (!it.isRecycled) it.recycle() }
                animBmps[1] = null
                staticFinalBmp?.let { if (!it.isRecycled) it.recycle() }
                staticFinalBmp = null
            }
            return placeholderBmp
        }

        val timelineMs = hostClipStartMs + (tUs / 1_000L)
        val timeInPipMs = (timelineMs - clip.timelineStartMs).coerceAtLeast(0L)
        val isAnimatedNow =
            (clip.pipAnimation != PipAnimation.NONE && timeInPipMs < PipAnimations.DURATION_MS) ||
                clip.keyframes.isNotEmpty()

        if (!isAnimatedNow) {
            staticFinalBmp?.let { if (!it.isRecycled) return it }
        }

        val props = Keyframes.propsAt(clip, timeInPipMs)
        val t = props.transform
        val drawW = bitmap.width * t.scale
        val drawH = bitmap.height * t.scale
        val targetCx = targetWidth / 2f + (t.offsetX / 2f) * targetWidth
        val targetCy = targetHeight / 2f + (t.offsetY / 2f) * targetHeight
        val eased = if (clip.pipAnimation == PipAnimation.NONE) 1f else PipAnimations.easeOut(timeInPipMs)

        val cx: Float
        val cy: Float
        val animScale: Float
        when (clip.pipAnimation) {
            PipAnimation.SLIDE_LEFT -> {
                val startCx = -drawW / 2f
                cx = startCx + (targetCx - startCx) * eased
                cy = targetCy
                animScale = 1f
            }
            PipAnimation.SLIDE_UP -> {
                cx = targetCx
                val startCy = targetHeight + drawH / 2f
                cy = startCy + (targetCy - startCy) * eased
                animScale = 1f
            }
            PipAnimation.ZOOM_IN -> {
                cx = targetCx
                cy = targetCy
                animScale = 0.05f + 0.95f * eased
            }
            PipAnimation.NONE -> {
                cx = targetCx
                cy = targetCy
                animScale = 1f
            }
        }

        val outBmp = if (isAnimatedNow) {
            val idx = animIdx
            animIdx = (animIdx + 1) and 1
            var b = animBmps[idx]
            if (b == null || b.isRecycled || b.width != targetWidth || b.height != targetHeight) {
                b = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                animBmps[idx] = b
            }
            b
        } else {
            val b = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            staticFinalBmp = b
            b
        }

        outBmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(outBmp)
        canvas.save()
        canvas.translate(cx, cy)
        if (t.rotationDegrees != 0f) {
            canvas.rotate(t.rotationDegrees)
        }
        val finalW = (drawW * animScale).coerceAtLeast(1f)
        val finalH = (drawH * animScale).coerceAtLeast(1f)
        val dst = RectF(-finalW / 2f, -finalH / 2f, finalW / 2f, finalH / 2f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (props.opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(bitmap, null, dst, paint)
        canvas.restore()

        return outBmp
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (clipTimeUs(presentationTimeUs) in startUs until endUs) visibleSettings else hiddenSettings
}
