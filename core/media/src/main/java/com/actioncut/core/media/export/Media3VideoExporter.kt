package com.actioncut.core.media.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.actioncut.core.domain.port.VideoExporter
import com.actioncut.core.media.template.FbTemplateBarRenderer
import com.actioncut.core.media.template.NewsCrickWatermarkRenderer
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.ExportSettings
import com.actioncut.core.model.ExportState
import com.actioncut.core.model.Project
import com.actioncut.core.model.Resolution
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.Transform
import com.actioncut.core.model.TransitionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt

class Media3VideoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reverser: com.actioncut.core.media.reverse.VideoReverser,
) : VideoExporter {

    override fun export(
        project: Project,
        settings: ExportSettings,
        outputFilePath: String,
    ): Flow<ExportState> = callbackFlow {
        val rawClips = project.timeline.videoTracks.firstOrNull()?.clips.orEmpty()
            .filter { it.mediaUri != null }

        if (rawClips.isEmpty()) {
            trySend(ExportState.Failed("Timeline has no exportable video clips"))
            close()
            return@callbackFlow
        }

        val aspect = settings.aspectRatio ?: project.aspectRatio
        val (targetWidth, targetHeight) = targetDimensions(aspect, settings.resolution)
        val isFbTpl = (project.canvas.isFbTemplateEnabled || project.timeline.canvas.isFbTemplateEnabled) && aspect == AspectRatio.RATIO_4_5
        val tplImageUri = project.canvas.fbTemplateImageUri ?: project.timeline.canvas.fbTemplateImageUri

        val clips = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            rawClips.map { clip ->
                if (clip.isReversed && clip.type == ClipType.VIDEO && clip.mediaUri != null) {
                    val reversedUri = reverser.reverse(clip.mediaUri!!, clip.sourceInMs, clip.sourceOutMs)
                    if (reversedUri != null) {
                        clip.copy(mediaUri = reversedUri, isReversed = false, sourceInMs = 0L, sourceOutMs = 0L)
                    } else {
                        clip
                    }
                } else if (clip.type == ClipType.IMAGE && clip.mediaUri != null) {
                    if (isFbTpl || clip.scorecard != null) {
                        clip
                    } else {
                        val blurredFrameUri = createBlurredBackdropImage(context, clip, targetWidth, targetHeight)
                        if (blurredFrameUri != null) {
                            clip.copy(mediaUri = blurredFrameUri)
                        } else {
                            clip
                        }
                    }
                } else {
                    clip
                }
            }
        }

        val handler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        var progressRunnable: Runnable? = null

        handler.post {
            runCatching {
                val rawOverlayClips = project.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }

                val preparedPipOverlays = rawOverlayClips.map { ov ->
                    if (ov.type == ClipType.IMAGE && ov.mediaUri != null && ov.mediaUri?.contains("news_banner") != true) {
                        val processedUri = renderExactPipOverlayToCanvas(context, ov, targetWidth, targetHeight)
                        ov.copy(
                            mediaUri = processedUri,
                            type = ClipType.STICKER,
                            transform = Transform(offsetX = 0f, offsetY = 0f, scale = 1f, rotationDegrees = 0f),
                            crop = null,
                        )
                    } else {
                        ov
                    }
                }

                val textOverlays = project.timeline.allClips.filter { it.type == ClipType.TEXT }
                val overlayClips = preparedPipOverlays + textOverlays

                val presentationLayout = if (isFbTpl) {
                    Presentation.LAYOUT_SCALE_TO_FIT
                } else {
                    when (project.canvas.fitMode) {
                        com.actioncut.core.model.FitMode.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
                        com.actioncut.core.model.FitMode.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                        com.actioncut.core.model.FitMode.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
                    }
                }

                val totalTimelineMs = project.timeline.durationMs
                val totalVideoMs = clips.sumOf { it.timelineDurationMs }

                val exportClips = if (totalTimelineMs > totalVideoMs && clips.isNotEmpty()) {
                    val blackFile = File(context.cacheDir, "black_gap.png")
                    if (!blackFile.exists()) {
                        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                        val cv = Canvas(bmp)
                        cv.drawColor(android.graphics.Color.BLACK)
                        FileOutputStream(blackFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    val blackClip = Clip(
                        id = "black_gap_${System.currentTimeMillis()}",
                        type = ClipType.IMAGE,
                        mediaUri = Uri.fromFile(blackFile).toString(),
                        timelineStartMs = totalVideoMs,
                        timelineEndMs = totalTimelineMs,
                    )
                    clips + blackClip
                } else {
                    clips
                }

                val sharedWatermarkBuffers = PingPongBuffers(targetWidth, targetHeight)
                val sharedTplBuffers = if (isFbTpl) PingPongBuffers(targetWidth, targetHeight) else null

                val tplOverlay = if (isFbTpl && sharedTplBuffers != null) {
                    FbTemplateOverlay(context, targetWidth, targetHeight, tplImageUri, sharedTplBuffers)
                } else null

                val editedItems = exportClips.mapIndexed { index, clip ->
                    val incoming = exportClips.getOrNull(index - 1)?.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS_MERGE }
                    val outgoing = clip.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS_MERGE }

                    clip.toEditedMediaItem(
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        settings = settings,
                        overlays = overlayClips,
                        presentationLayout = presentationLayout,
                        incoming = incoming,
                        outgoing = outgoing,
                        isFbTpl = isFbTpl,
                        tplOverlay = tplOverlay,
                        watermarkBuffers = sharedWatermarkBuffers,
                    )
                }
                val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)

                val sequences = mutableListOf(videoSequence)

                val pipVideoClips = project.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }
                    .filter { it.mediaUri != null && it.type == ClipType.VIDEO }
                    .sortedBy { it.timelineStartMs }
                if (pipVideoClips.isNotEmpty()) {
                    sequences += EditedMediaItemSequence.withVideoFrom(
                        pipVideoClips.map {
                            it.toEditedMediaItem(
                                targetWidth, targetHeight, settings, emptyList(),
                                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                                watermarkBuffers = sharedWatermarkBuffers,
                            )
                        },
                    )
                }

                val audioClips = project.timeline.audioTracks
                    .flatMap { it.clips }
                    .filter { it.mediaUri != null && it.volume > 0f }
                    .sortedBy { it.timelineStartMs }
                if (audioClips.isNotEmpty()) {
                    sequences += EditedMediaItemSequence.withAudioFrom(
                        audioClips.map { it.toAudioEditedMediaItem() },
                    )
                }

                val compositionBuilder = Composition.Builder(sequences)
                if (pipVideoClips.isNotEmpty()) {
                    compositionBuilder.setVideoCompositorSettings(pipCompositorSettings(pipVideoClips.first()))
                }
                val composition = compositionBuilder.build()

                val t = Transformer.Builder(context)
                    .setVideoMimeType(settings.format.mimeType)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            trySend(ExportState.Completed(outputFilePath))
                            close()
                        }

                        override fun onError(
                            composition: Composition,
                            result: ExportResult,
                            exception: ExportException,
                        ) {
                            val chain = generateSequence(exception as Throwable) { it.cause }
                                .mapNotNull { it.message?.trim() }
                                .filter { it.isNotEmpty() }
                                .distinct()
                                .toList()
                            trySend(ExportState.Failed(chain.lastOrNull() ?: chain.joinToString(" <- ").ifEmpty { "Export failed" }))
                            close()
                        }
                    })
                    .build()

                transformer = t
                t.start(composition, outputFilePath)
                trySend(ExportState.InProgress(0f))

                val holder = ProgressHolder()
                progressRunnable = object : Runnable {
                    override fun run() {
                        val current = transformer ?: return
                        val stateInt = current.getProgress(holder)
                        if (stateInt != Transformer.PROGRESS_STATE_NOT_STARTED) {
                            val progressFrac = (holder.progress / 100f).coerceIn(0f, 0.98f)
                            trySend(ExportState.InProgress(progressFrac))
                        }
                        handler.postDelayed(this, 200L)
                    }
                }
                handler.postDelayed(progressRunnable!!, 200L)
            }.onFailure {
                trySend(ExportState.Failed(it.message ?: "Failed to start export"))
                close()
            }
        }

        awaitClose {
            handler.post {
                progressRunnable?.let { handler.removeCallbacks(it) }
                transformer?.cancel()
                transformer = null
            }
        }
    }

    private fun renderExactPipOverlayToCanvas(
        context: Context,
        clip: Clip,
        targetWidth: Int,
        targetHeight: Int,
    ): String = runCatching {
        val uriStr = clip.mediaUri ?: return@runCatching ""

        val isAiCutout = clip.filter?.id?.contains("cutout") == true
        val cutoutFile = File(context.cacheDir, "cutout_${uriStr.hashCode()}.png")

        val srcBmp: Bitmap? = if (isAiCutout && cutoutFile.exists() && cutoutFile.length() > 0L) {
            BitmapFactory.decodeFile(cutoutFile.absolutePath)
        } else {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }
        }

        if (srcBmp == null) return@runCatching uriStr

        val fullCanvasBmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fullCanvasBmp)

        val t = if (clip.keyframes.isNotEmpty()) {
            com.actioncut.core.model.Keyframes.propsAt(clip, 0L).transform
        } else {
            clip.transform
        }

        val cx = targetWidth / 2f + (t.offsetX / 2f) * targetWidth
        val cy = targetHeight / 2f + (t.offsetY / 2f) * targetHeight

        val srcW = srcBmp.width.toFloat()
        val srcH = srcBmp.height.toFloat()
        val srcAspect = srcW / srcH
        val canvasAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val pw: Float
        val ph: Float
        if (srcAspect >= canvasAspect) {
            pw = targetWidth * t.scale
            ph = pw / srcAspect
        } else {
            ph = targetHeight * t.scale
            pw = ph * srcAspect
        }

        val left = cx - pw / 2f
        val top = cy - ph / 2f

        val crop = clip.crop ?: CropRect(0f, 0f, 1f, 1f)
        val cropRect = RectF(
            left + pw * crop.left,
            top + ph * crop.top,
            left + pw * crop.right,
            top + ph * crop.bottom,
        )

        canvas.save()
        canvas.clipRect(cropRect)

        val isFlipped = clip.filter?.id?.contains("flip") == true
        if (isFlipped) {
            canvas.scale(-1f, 1f, cx, cy)
        }
        val totalRot = clip.rotationDegrees + t.rotationDegrees
        if (totalRot != 0f) {
            canvas.rotate(totalRot, cx, cy)
        }

        val dstRect = RectF(left, top, left + pw, top + ph)

        canvas.drawBitmap(
            srcBmp,
            null,
            dstRect,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        canvas.restore()
        srcBmp.recycle()

        val outFile = File(context.cacheDir, "pip_rendered_${clip.id}.png")
        FileOutputStream(outFile).use { fos ->
            fullCanvasBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        fullCanvasBmp.recycle()

        Uri.fromFile(outFile).toString()
    }.getOrDefault(clip.mediaUri ?: "")

    private fun createBlurredBackdropImage(
        context: Context,
        clip: Clip,
        targetWidth: Int,
        targetHeight: Int,
    ): String? = runCatching {
        val uri = Uri.parse(clip.mediaUri)
        val src = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return@runCatching null

        val outBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        val small = Bitmap.createScaledBitmap(src, 64, 36, true)
        val blurredBg = Bitmap.createScaledBitmap(small, targetWidth, targetHeight, true)
        small.recycle()

        val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(blurredBg, 0f, 0f, bgPaint)
        blurredBg.recycle()

        canvas.drawColor(android.graphics.Color.argb(90, 0, 0, 0))

        val scaleFactor = minOf(
            targetWidth.toFloat() / src.width.toFloat(),
            targetHeight.toFloat() / src.height.toFloat(),
        ) * clip.transform.scale

        val fgW = src.width * scaleFactor
        val fgH = src.height * scaleFactor

        val centerX = targetWidth / 2f + (clip.transform.offsetX * targetWidth / 2f)
        val centerY = targetHeight / 2f + (clip.transform.offsetY * targetHeight / 2f)

        val dstRect = RectF(
            centerX - fgW / 2f,
            centerY - fgH / 2f,
            centerX + fgW / 2f,
            centerY + fgH / 2f,
        )

        canvas.save()
        if (clip.rotationDegrees != 0) {
            canvas.rotate(clip.rotationDegrees.toFloat(), centerX, centerY)
        }
        canvas.drawBitmap(src, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
        src.recycle()

        val outFile = File(context.cacheDir, "blur_export_${clip.id}.png")
        FileOutputStream(outFile).use { out ->
            outBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        outBitmap.recycle()

        Uri.fromFile(outFile).toString()
    }.getOrNull()

    private fun Clip.toEditedMediaItem(
        targetWidth: Int,
        targetHeight: Int,
        settings: ExportSettings,
        overlays: List<Clip>,
        presentationLayout: Int,
        incoming: com.actioncut.core.model.Transition? = null,
        outgoing: com.actioncut.core.model.Transition? = null,
        isFbTpl: Boolean = false,
        tplOverlay: FbTemplateOverlay? = null,
        watermarkBuffers: PingPongBuffers,
    ): EditedMediaItem {
        val mediaItemBuilder = MediaItem.Builder().setUri(mediaUri)
        if (type == ClipType.IMAGE) {
            mediaItemBuilder.setImageDurationMs(timelineDurationMs)
        } else {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(
                        if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE,
                    )
                    .build(),
            )
        }

        val templateOverlayEffect: Effect? = tplOverlay?.let {
            OverlayEffect(listOf<TextureOverlay>(it.forClip(timelineStartMs)))
        }

        val watermarkOverlayEffect: Effect = OverlayEffect(
            listOf<TextureOverlay>(
                NewsCrickWatermarkOverlay(targetWidth, targetHeight, timelineStartMs, watermarkBuffers),
            ),
        )

        val videoEffects = EffectMapper.videoEffects(
            clip = this,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            overlays = overlays,
            presentationLayout = presentationLayout,
            isFbTemplate = isFbTpl,
            templateOverlay = templateOverlayEffect,
        ).toMutableList()

        videoEffects += watermarkOverlayEffect

        val clipDurUs = timelineDurationMs * 1_000L
        incoming?.let { tr ->
            val edgeUs = (tr.durationMs * 1_000L).coerceAtMost(clipDurUs / 2)
            if (edgeUs > 0L) {
                videoEffects += TransitionEffect(
                    TransitionEdge.IN, TransitionEffect.kindFor(tr.type), edgeUs, clipDurUs,
                )
            }
        }
        outgoing?.let { tr ->
            val edgeUs = (tr.durationMs * 1_000L).coerceAtMost(clipDurUs / 2)
            if (edgeUs > 0L) {
                videoEffects += TransitionEffect(
                    TransitionEdge.OUT, TransitionEffect.kindFor(tr.type), edgeUs, clipDurUs,
                )
            }
        }

        val audioProcessors = EffectMapper.audioProcessors(this)

        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(Effects(audioProcessors, videoEffects))

        if (volume == 0f && type == ClipType.VIDEO) {
            builder.setRemoveAudio(true)
        }

        if (type == ClipType.IMAGE) {
            builder.setDurationUs(timelineDurationMs * 1_000)
            builder.setFrameRate(settings.frameRate.fps)
        }
        return builder.build()
    }

    private fun Clip.toAudioEditedMediaItem(): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE)
                    .build(),
            )
            .build()
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(EffectMapper.audioProcessors(this), emptyList()))
            .build()
    }

    private fun pipCompositorSettings(pip: Clip): VideoCompositorSettings =
        object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.first()
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
                if (inputId == 1) {
                    val props = com.actioncut.core.model.Keyframes.propsAt(pip, presentationTimeUs / 1000L)
                    val t = props.transform
                    StaticOverlaySettings.Builder()
                        .setScale(t.scale, t.scale)
                        .setOverlayFrameAnchor(0f, 0f)
                        .setBackgroundFrameAnchor(
                            t.offsetX.coerceIn(-1f, 1f),
                            -t.offsetY.coerceIn(-1f, 1f),
                        )
                        .build()
                } else {
                    StaticOverlaySettings.Builder().build()
                }
        }

    private fun targetDimensions(aspect: AspectRatio, resolution: Resolution): Pair<Int, Int> {
        val longEdge = resolution.longEdge
        return if (aspect.value >= 1f) {
            even(longEdge) to even((longEdge / aspect.value).roundToInt())
        } else {
            even((longEdge * aspect.value).roundToInt()) to even(longEdge)
        }
    }

    private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1
}

/** Shared ping-pong 2-bitmap buffer so Media3 uploads animated overlays every frame with minimal RAM. */
private class PingPongBuffers(width: Int, height: Int) {
    private val bmps = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val canvases = Array(2) { i -> Canvas(bmps[i]) }
    private var idx = 0

    @Synchronized
    fun drawFrame(drawBlock: (Canvas) -> Unit): Bitmap {
        val i = idx
        idx = (idx + 1) and 1
        bmps[i].eraseColor(Color.TRANSPARENT)
        drawBlock(canvases[i])
        return bmps[i]
    }
}

private class NewsCrickWatermarkOverlay(
    targetWidth: Int,
    targetHeight: Int,
    private val clipStartMs: Long = 0L,
    private val buffers: PingPongBuffers,
) : BitmapOverlay() {

    private val defaultSettings: OverlaySettings = StaticOverlaySettings.Builder().build()
    private var baseUs = Long.MIN_VALUE

    private val logoW = targetWidth * 0.082f
    private val logoH = logoW * 0.75f
    private val marginX = targetWidth * 0.014f
    private val marginY = targetHeight * 0.018f
    private val logoX = targetWidth - logoW - marginX
    private val logoY = marginY

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
        val currentPlayheadMs = clipStartMs + ((presentationTimeUs - baseUs).coerceAtLeast(0L) / 1000L)
        val timeSec = currentPlayheadMs / 1000f

        return buffers.drawFrame { canvas ->
            NewsCrickWatermarkRenderer.renderLogo(canvas, logoX, logoY, logoW, logoH, timeSec)
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = defaultSettings
}

private class FbTemplateOverlay(
    private val context: Context,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val bottomImageUriStr: String?,
    private val buffers: PingPongBuffers,
    private val clipStartMs: Long = 0L,
) : BitmapOverlay() {

    private val defaultSettings: OverlaySettings = StaticOverlaySettings.Builder().build()
    private var cachedBottomBitmap: Bitmap? = null
    private var baseUs = Long.MIN_VALUE

    private val bgPaint = Paint().apply { color = android.graphics.Color.parseColor("#0B0B0B") }
    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val vH = (targetWidth * (9f / 16f)).roundToInt()
    private val barH = targetHeight - 2 * vH
    private val bottomY = vH + barH
    private val bottomRect = RectF(0f, bottomY.toFloat(), targetWidth.toFloat(), targetHeight.toFloat())

    init {
        if (!bottomImageUriStr.isNullOrBlank()) {
            runCatching {
                val uri = Uri.parse(bottomImageUriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    cachedBottomBitmap = BitmapFactory.decodeStream(stream)
                }
            }
        }
    }

    fun forClip(startMs: Long): FbTemplateOverlay {
        val copy = FbTemplateOverlay(context, targetWidth, targetHeight, bottomImageUriStr, buffers, startMs)
        copy.cachedBottomBitmap = this.cachedBottomBitmap
        return copy
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
        val currentPlayheadMs = clipStartMs + ((presentationTimeUs - baseUs).coerceAtLeast(0L) / 1000L)
        val timeSeconds = currentPlayheadMs / 1000f

        return buffers.drawFrame { canvas ->
            canvas.save()
            canvas.translate(0f, vH.toFloat())
            FbTemplateBarRenderer.drawTplBar(canvas, targetWidth.toFloat(), barH.toFloat(), timeSeconds)
            canvas.restore()

            canvas.drawRect(bottomRect, bgPaint)

            val src = cachedBottomBitmap
            if (src != null && !src.isRecycled) {
                val iw = src.width.toFloat()
                val ih = src.height.toFloat()
                val sc = minOf(targetWidth.toFloat() / iw, vH.toFloat() / ih)
                val dw = iw * sc
                val dh = ih * sc
                val dx = (targetWidth - dw) / 2f
                val dy = bottomY + (vH - dh) / 2f

                canvas.drawBitmap(src, null, RectF(dx, dy, dx + dw, dy + dh), imgPaint)
            }
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = defaultSettings
}
