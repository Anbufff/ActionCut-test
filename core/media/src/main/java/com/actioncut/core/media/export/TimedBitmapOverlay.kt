package com.actioncut.core.media.export

import android.graphics.Bitmap
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings

/**
 * A static bitmap overlay that is only visible between [startUs] and [endUs]
 * (microseconds on the clip's own effect timeline); outside that window it is drawn fully
 * transparent. Used for image stickers such as the cricket score card.
 */
internal class TimedBitmapOverlay(
    private val bitmap: Bitmap,
    private val visible: OverlaySettings,
    private val hidden: OverlaySettings,
    private val startUs: Long,
    private val endUs: Long,
) : BitmapOverlay() {

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        if (presentationTimeUs in startUs until endUs) visible else hidden
}
