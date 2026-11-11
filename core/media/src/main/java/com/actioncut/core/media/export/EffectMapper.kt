package com.actioncut.core.media.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.ScorecardClipData
import com.actioncut.core.model.SpeedRamp
import com.actioncut.core.model.SpeedRamps
import com.actioncut.core.model.VisualEffect
import com.actioncut.core.model.VisualEffectType
import com.google.common.collect.ImmutableList

object ScorecardBridge {
    var bitmapProvider: ((clipId: String, data: ScorecardClipData, timeInClipMs: Long) -> Bitmap?)? = null

    fun getActiveBitmap(clipId: String, data: ScorecardClipData, timeInClipMs: Long): Bitmap? {
        return bitmapProvider?.invoke(clipId, data, timeInClipMs)
    }
}

object EffectMapper {

    fun videoEffects(clip: Clip, targetWidth: Int, targetHeight: Int): List<Effect> =
        videoEffects(clip, targetWidth, targetHeight, emptyList())

    fun previewVideoEffects(clip: Clip): List<Effect> {
        val effects = mutableListOf<Effect>()

        val rotation = clip.rotationDegrees + clip.transform.rotationDegrees
        if (rotation % 360f != 0f) {
            effects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(rotation)
                .build()
        }
        clip.crop?.takeUnless { it.isFull }?.let { effects += cropEffect(it) }

        // Media3 MatrixTransformation API-க்கு ஏற்றவாறு android.graphics.Matrix ரிட்டர்ன் செய்யும் பிழை திருத்தப்பட்ட வடிவம்
        if (clip.transform.offsetX != 0f || clip.transform.offsetY != 0f) {
            val transMatrix = Matrix().apply {
                postTranslate(clip.transform.offsetX, -clip.transform.offsetY)
            }
            effects += MatrixTransformation { _ -> transMatrix }
        }

        val adj = clip.adjustments
        if (adj.brightness != 0f) effects += Brightness(adj.brightness.coerceIn(-1f, 1f))
        if (adj.contrast != 0f) effects += Contrast(adj.contrast.coerceIn(-1f, 1f))
        if (adj.saturation != 0f) {
            effects += HslAdjustment.Builder()
                .adjustSaturation((adj.saturation * 100f).coerceIn(-100f, 100f))
                .build()
        }
        if (adj.warmth != 0f) {
            val w = adj.warmth.coerceIn(-1f, 1f)
            effects += RgbAdjustment.Builder()
                .setRedScale(1f + w * 0.3f)
                .setBlueScale(1f - w * 0.3f)
                .build()
        }

        clip.filter?.let { filter ->
            FilterLuts.cubeFor(filter.id, filter.intensity)?.let { cube ->
                effects += SingleColorLut.createFromCube(cube)
            }
        }

        clip.effects.forEach { ve -> visualEffect(ve)?.let { effects += it } }

        return effects
    }

    fun videoEffects(
        clip: Clip,
        targetWidth: Int,
        targetHeight: Int,
        overlays: List<Clip>,
        presentationLayout: Int = Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
    ): List<Effect> {
        val effects = previewVideoEffects(clip).toMutableList()

        if (clip.type == ClipType.VIDEO) {
            if (clip.speedRamp != SpeedRamp.NONE) {
                val durUs = (clip.sourceDurationMs.takeIf { it > 0L } ?: clip.timelineDurationMs) * 1000L
                effects += SpeedChangeEffect(RampSpeedProvider(clip.speedRamp, clip.speed, durUs))
            } else if (clip.speed != 1f) {
                effects += SpeedChangeEffect(clip.speed)
            }
        }

        effects += Presentation.createForWidthAndHeight(
            targetWidth,
            targetHeight,
            presentationLayout,
        )

        if (clip.scorecard != null) {
            val scOverlay = DynamicScorecardOverlay(clip.id, clip.scorecard!!)
            effects += OverlayEffect(ImmutableList.of(scOverlay))
        }

        overlayEffect(clip, overlays, targetWidth)?.let { effects += it }
        return effects
    }

    private fun overlayEffect(clip: Clip, overlays: List<Clip>, targetWidth: Int): Effect? {
        val overlapping = overlays.filter { ov ->
            ov.timelineStartMs < clip.timelineEndMs && ov.timelineEndMs > clip.timelineStartMs
        }
        val active = overlapping.filter { !it.text?.text.isNullOrEmpty() }
        val images = overlapping.filter { it.mediaUri != null && it.text?.text.isNullOrEmpty() }
        if (active.isEmpty() && images.isEmpty()) return null

        val textOverlays: List<TextureOverlay> = active.map { ov ->
            val props = ov.text!!
            val span = SpannableString(props.text)
            val sizePx = (props.fontSizeSp * ov.transform.scale * 2.5f).toInt().coerceAtLeast(24)
            span.setSpan(AbsoluteSizeSpan(sizePx), 0, span.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            span.setSpan(ForegroundColorSpan(props.colorArgb), 0, span.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val settings = OverlaySettings.Builder()
                .setBackgroundFrameAnchor(
                    ov.transform.offsetX.coerceIn(-1f, 1f),
                    -ov.transform.offsetY.coerceIn(-1f, 1f),
                )
                .setRotationDegrees(ov.transform.rotationDegrees)
                .build()
            TextOverlay.createStaticTextOverlay(span, settings)
        }
        val imageOverlays: List<TextureOverlay> = images.mapNotNull { imageOverlay(it, clip, targetWidth) }
        val textureOverlays = textOverlays + imageOverlays
        if (textureOverlays.isEmpty()) return null
        return OverlayEffect(ImmutableList.copyOf(textureOverlays))
    }

    private fun imageOverlay(ov: Clip, clip: Clip, targetWidth: Int): TextureOverlay? {
        val path = ov.mediaUri?.let { Uri.parse(it).path } ?: return null
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val factor = (targetWidth * ov.transform.scale) / bitmap.width.toFloat()
        val visible = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                ov.transform.offsetX.coerceIn(-1f, 1f),
                -ov.transform.offsetY.coerceIn(-1f, 1f),
            )
            .setScale(factor, factor)
            .setRotationDegrees(ov.transform.rotationDegrees)
            .build()
        val hidden = OverlaySettings.Builder().setAlphaScale(0f).build()
        val startUs = (ov.timelineStartMs - clip.timelineStartMs).coerceAtLeast(0L) * 1_000L
        val endUs = (ov.timelineEndMs - clip.timelineStartMs).coerceAtLeast(0L) * 1_000L
        return TimedBitmapOverlay(bitmap, visible, hidden, startUs, endUs)
    }

    fun audioProcessors(clip: Clip): List<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        if (clip.speed != 1f && (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO)) {
            processors += SonicAudioProcessor().apply { setSpeed(clip.speed) }
        }
        val volume = clip.volume
        if (volume != 1f && volume != 0f) {
            processors += ChannelMixingAudioProcessor().apply {
                putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(volume))
                putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(volume))
            }
        }
        return processors
    }

    private fun cropEffect(crop: CropRect): Crop {
        val left = crop.left * 2f - 1f
        val right = crop.right * 2f - 1f
        val top = 1f - crop.top * 2f
        val bottom = 1f - crop.bottom * 2f
        return Crop(left, right, bottom, top)
    }

    private fun visualEffect(effect: VisualEffect): Effect? = when (effect.type) {
        VisualEffectType.GAUSSIAN_BLUR,
        VisualEffectType.BOKEH -> GaussianBlur(1f + effect.intensity * 8f)
        VisualEffectType.RADIAL_BLUR -> GaussianBlur(1f + effect.intensity * 6f)
        else -> Shaders.fragmentFor(effect.type)?.let { frag ->
            ShaderEffect(frag, effect.intensity.coerceIn(0f, 1f))
        }
    }
}

private class DynamicScorecardOverlay(
    private val clipId: String,
    private val data: ScorecardClipData
) : BitmapOverlay() {

    private val defaultSettings = OverlaySettings.Builder().build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeInClipMs = presentationTimeUs / 1000L
        return ScorecardBridge.getActiveBitmap(clipId, data, timeInClipMs)
            ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        return defaultSettings
    }
}

private class RampSpeedProvider(
    private val ramp: SpeedRamp,
    private val base: Float,
    private val durationUs: Long,
    private val stepUs: Long = 100_000L,
) : SpeedProvider {

    override fun getSpeed(timeUs: Long): Float {
        if (durationUs <= 0L) return base.coerceIn(0.1f, 8f)
        val stepStart = (timeUs / stepUs) * stepUs
        val progress = stepStart.toFloat() / durationUs.toFloat()
        return SpeedRamps.multiplierAt(ramp, progress, base)
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (durationUs <= 0L) return C.TIME_UNSET
        val next = ((timeUs / stepUs) + 1) * stepUs
        return if (next >= durationUs) C.TIME_UNSET else next
    }
}
