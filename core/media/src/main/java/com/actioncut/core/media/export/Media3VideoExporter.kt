package com.actioncut.core.media.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.actioncut.core.domain.port.VideoExporter
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ExportSettings
import com.actioncut.core.model.ExportState
import com.actioncut.core.model.Project
import com.actioncut.core.model.Resolution
import com.actioncut.core.model.TrackType
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
                    val blurredFrameUri = createBlurredBackdropImage(context, clip, targetWidth, targetHeight)
                    if (blurredFrameUri != null) {
                        clip.copy(mediaUri = blurredFrameUri)
                    } else {
                        clip
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
                // OVERLAY டிராக்கில் உள்ள PiP Photo, Text மற்றும் Stickers அனைத்தையும் சேகரித்தல்
                val rawOverlayClips = project.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }

                // PiP Photo-க்களை Crop மற்றும் Cutout உடன் எக்ஸ்போர்ட் செய்யத் தயார் செய்தல்
                val preparedPipOverlays = rawOverlayClips.map { ov ->
                    if (ov.type == ClipType.IMAGE && ov.mediaUri != null && ov.mediaUri?.contains("news_banner") != true) {
                        val processedUri = prepareExportOverlayImage(context, ov)
                        ov.copy(mediaUri = processedUri, type = ClipType.STICKER)
                    } else {
                        ov
                    }
                }

                val textOverlays = project.timeline.allClips.filter { it.type == ClipType.TEXT }
                val overlayClips = preparedPipOverlays + textOverlays

                val presentationLayout = when (project.canvas.fitMode) {
                    com.actioncut.core.model.FitMode.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
                    com.actioncut.core.model.FitMode.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    com.actioncut.core.model.FitMode.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
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

                val editedItems = exportClips.mapIndexed { index, clip ->
                    val incoming = exportClips.getOrNull(index - 1)?.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS_MERGE }
                    val outgoing = clip.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS_MERGE }

                    clip.toEditedMediaItem(
                        targetWidth, targetHeight, settings, overlayClips, presentationLayout,
                        incoming, outgoing,
                    )
                }
                val videoSequence = EditedMediaItemSequence(editedItems)

                val sequences = mutableListOf(videoSequence)

                val pipVideoClips = project.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }
                    .filter { it.mediaUri != null && it.type == ClipType.VIDEO }
                    .sortedBy { it.timelineStartMs }
                if (pipVideoClips.isNotEmpty()) {
                    sequences += EditedMediaItemSequence(
                        pipVideoClips.map {
                            it.toEditedMediaItem(
                                targetWidth, targetHeight, settings, emptyList(),
                                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                            )
                        },
                    )
                }

                val audioClips = project.timeline.audioTracks
                    .flatMap { it.clips }
                    .filter { it.mediaUri != null && it.volume > 0f }
                    .sortedBy { it.timelineStartMs }
                if (audioClips.isNotEmpty()) {
                    sequences += EditedMediaItemSequence(audioClips.map { it.toAudioEditedMediaItem() })
                }

                val compositionBuilder = Composition.Builder(sequences)
                    .experimentalSetForceAudioTrack(true)
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

    /**
     * PiP Photo-வின் Crop மற்றும் AI Cutout-ஐப் பயன்படுத்தி எக்ஸ்போர்ட் செய்வதற்கான பிம்பத்தை உருவாக்குதல்
     */
    private fun prepareExportOverlayImage(context: Context, clip: Clip): String = runCatching {
        val uriStr = clip.mediaUri ?: return@runCatching ""
        val uri = Uri.parse(uriStr)

        val srcBmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return@runCatching uriStr

        val crop = clip.crop
        val croppedBmp = if (crop != null && !crop.isFull) {
            val cL = (srcBmp.width * crop.left).roundToInt().coerceIn(0, srcBmp.width - 1)
            val cT = (srcBmp.height * crop.top).roundToInt().coerceIn(0, srcBmp.height - 1)
            val cR = (srcBmp.width * crop.right).roundToInt().coerceIn(cL + 1, srcBmp.width)
            val cB = (srcBmp.height * crop.bottom).roundToInt().coerceIn(cT + 1, srcBmp.height)
            Bitmap.createBitmap(srcBmp, cL, cT, cR - cL, cB - cT)
        } else {
            srcBmp
        }

        val isFlipped = clip.filter?.id?.contains("flip") == true
        val finalBmp = if (isFlipped) {
            val matrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(croppedBmp, 0, 0, croppedBmp.width, croppedBmp.height, matrix, true)
        } else {
            croppedBmp
        }

        val outFile = File(context.cacheDir, "pip_export_${clip.id}.png")
        FileOutputStream(outFile).use { out ->
            finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
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
    ): EditedMediaItem {
        val mediaItemBuilder = MediaItem.Builder().setUri(mediaUri)
        if (type != ClipType.IMAGE) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(
                        if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE,
                    )
                    .build(),
            )
        }

        val videoEffects = EffectMapper.videoEffects(this, targetWidth, targetHeight, overlays, presentationLayout)
            .toMutableList()

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
                    OverlaySettings.Builder()
                        .setScale(t.scale, t.scale)
                        .setOverlayFrameAnchor(0f, 0f)
                        .setBackgroundFrameAnchor(
                            t.offsetX.coerceIn(-1f, 1f),
                            -t.offsetY.coerceIn(-1f, 1f),
                        )
                        .build()
                } else {
                    OverlaySettings.Builder().build()
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
