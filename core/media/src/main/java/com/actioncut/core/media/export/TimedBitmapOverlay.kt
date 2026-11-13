package com.actioncut.core.media.export

import android.graphics.Bitmap
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay

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
