package com.actioncut.core.media.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
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
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.actioncut.core.domain.port.VideoExporter
import com.actioncut.core.media.template.FbTemplateBarRenderer
import com.actioncut.core.media.template.NewsCrickWatermarkRenderer
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.ExportSettings
import com.actioncut.core.model.ExportState
import com.actioncut.core.model.Keyframes
import com.actioncut.core.model.PipAnimation
import com.actioncut.core.model.PipAnimations
import com.actioncut.core.model.Project
import com.actioncut.core.model.Resolution
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.TransitionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

class Media3VideoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reverser: com.actioncut.core.media.reverse.VideoReverser,
) : VideoExporter {

    private data class PipVideoSpec(
        val uncropW1: Float,
        val uncropH1: Float,
        val basePipW: Int,
        val basePipH: Int,
    )

    private data class PreparedExportData(
        val clips: List<Clip>,
        val overlays: List<Clip>,
        val pipSpecs: Map<String, PipVideoSpec>,
        val blackGapUri: String?,
    )

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

        // Draw news_banner (with Stadium Background) FIRST so Player Cutout PiP renders ON TOP of it
        val rawOverlayClips = project.timeline.tracks
            .filter { it.type == TrackType.OVERLAY }
            .flatMap { it.clips }
            .sortedByDescending { it.mediaUri?.contains("news_banner") == true }

        val pipVideoClips = rawOverlayClips
            .filter { it.mediaUri != null && it.type == ClipType.VIDEO }
            .sortedBy { it.timelineStartMs }

        val totalTimelineMs = project.timeline.durationMs

        val prepared = withContext(Dispatchers.Default) {
            coroutineScope {
                val clipsDeferred = rawClips.map { clip ->
                    async(Dispatchers.Default) {
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

                val overlaysDeferred = rawOverlayClips.map { ov ->
                    async(Dispatchers.Default) {
                        if (ov.type == ClipType.IMAGE && ov.mediaUri != null && ov.mediaUri?.contains("news_banner") != true) {
                            preparePipImageOverlay(context, ov, targetWidth, targetHeight)
                        } else {
                            ov
                        }
                    }
                }

                val specsDeferred = pipVideoClips.map { pip ->
                    async(Dispatchers.IO) {
                        pip.id to probePipVideoSpec(context, pip, targetWidth, targetHeight)
                    }
                }

                val processedClips = clipsDeferred.awaitAll()
                val processedOverlays = overlaysDeferred.awaitAll()
                val specs = specsDeferred.awaitAll().toMap()

                val totalVideoMs = processedClips.sumOf { it.timelineDurationMs }
                val blackGapUri = if (totalTimelineMs > totalVideoMs && processedClips.isNotEmpty()) {
                    ensureBlackGapUri(context)
                } else {
                    null
                }

                PreparedExportData(
                    clips = processedClips,
                    overlays = processedOverlays,
                    pipSpecs = specs,
                    blackGapUri = blackGapUri,
                )
            }
        }

        val clips = prepared.clips
        val preparedPipOverlays = prepared.overlays
        val pipVideoSpecs = prepared.pipSpecs

        val handler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        var progressRunnable: Runnable? = null

        handler.post {
            runCatching {
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

                val totalVideoMs = clips.sumOf { it.timelineDurationMs }
                val exportClips = if (totalTimelineMs > totalVideoMs && clips.isNotEmpty() && prepared.blackGapUri != null) {
                    val blackClip = Clip(
                        id = "black_gap_${System.currentTimeMillis()}",
                        type = ClipType.IMAGE,
                        mediaUri = prepared.blackGapUri,
                        timelineStartMs = totalVideoMs,
                        timelineEndMs = totalTimelineMs,
                    )
                    clips + blackClip
                } else {
                    clips
                }

                // Compact watermark buffer (~158x118 instead of full 1920x1080) -> ~111x fewer pixels/frame
                val logoW = (targetWidth * 0.082f).roundToInt().coerceAtLeast(16)
                val logoH = (logoW * 0.75f).roundToInt().coerceAtLeast(12)
                val sharedWatermarkBuffers = PingPongBuffers(logoW, logoH)

                val vH = (targetWidth * (9f / 16f)).roundToInt()
                val barH = (targetHeight - 2 * vH).coerceAtLeast(2)
                val tplBottomOverlay = if (isFbTpl) {
                    FbTemplateBottomOverlay(context, targetWidth, targetHeight, tplImageUri)
                } else null
                val tplBarOverlay = if (isFbTpl) {
                    FbTemplateBarOverlay(targetWidth, targetHeight, vH, barH, PingPongBuffers(targetWidth, barH))
                } else null

                val editedItems = exportClips.mapIndexed { index, clip ->
                    val incoming = exportClips.getOrNull(index - 1)?.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS_MERGE }
                    val outgoing = clip.transitionToNext
                        ?.takeIf { it.type != TransitionType.NONE && it.type != TransitionType.SEAMLESS_MERGE }

                    // Never draw Flash News banners or AI Player Cutouts over any CRIC / CRIC CDN Scorecard!
                    val overlaysForClip = if (clip.scorecard != null) {
                        overlayClips.filterNot { ov ->
                            ov.mediaUri?.contains("news_banner") == true || ov.id.startsWith("pip_ai_")
                        }
                    } else {
                        overlayClips
                    }

                    clip.toEditedMediaItem(
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        logoW = logoW,
                        logoH = logoH,
                        settings = settings,
                        overlays = overlaysForClip,
                        presentationLayout = presentationLayout,
                        incoming = incoming,
                        outgoing = outgoing,
                        isFbTpl = isFbTpl,
                        tplBottomOverlay = tplBottomOverlay,
                        tplBarOverlay = tplBarOverlay,
                        watermarkBuffers = sharedWatermarkBuffers,
                    )
                }
                val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)

                val sequences = mutableListOf(videoSequence)

                if (pipVideoClips.isNotEmpty()) {
                    sequences += EditedMediaItemSequence.withVideoFrom(
                        pipVideoClips.map { pip ->
                            val spec = pipVideoSpecs[pip.id]
                                ?: PipVideoSpec(targetWidth.toFloat(), targetHeight.toFloat(), targetWidth, targetHeight)
                            pip.toPipVideoEditedMediaItem(spec)
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
                    val primaryPip = pipVideoClips.first()
                    val spec = pipVideoSpecs[primaryPip.id]
                        ?: PipVideoSpec(targetWidth.toFloat(), targetHeight.toFloat(), targetWidth, targetHeight)
                    compositionBuilder.setVideoCompositorSettings(
                        pipCompositorSettings(primaryPip, spec, targetWidth, targetHeight),
                    )
                }
                val composition = compositionBuilder.build()

                val videoEncoderSettings = VideoEncoderSettings.Builder()
                    .setBitrate(settings.effectiveBitrate())
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(videoEncoderSettings)
                    .setEnableFallback(true)
                    .build()

                val t = Transformer.Builder(context)
                    .setVideoMimeType(settings.format.mimeType)
                    .setEncoderFactory(encoderFactory)
                    .experimentalSetMaxFramesInEncoder(8)
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

    private fun ensureBlackGapUri(context: Context): String {
        val blackFile = File(context.cacheDir, "black_gap.png")
        if (!blackFile.exists() || blackFile.length() == 0L) {
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(Color.BLACK)
            FileOutputStream(blackFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        return Uri.fromFile(blackFile).toString()
    }

    private fun preparePipImageOverlay(
        context: Context,
        clip: Clip,
        targetWidth: Int,
        targetHeight: Int,
    ): Clip = runCatching {
        val uriStr = clip.mediaUri ?: return@runCatching clip

        val isAiCutout = clip.filter?.id?.contains("cutout") == true
        val cutoutFile = File(context.cacheDir, "cutout_${uriStr.hashCode()}.png")

        val maxSide = max(targetWidth, targetHeight) * 2
        val srcBmp: Bitmap? = if (isAiCutout && cutoutFile.exists() && cutoutFile.length() > 0L) {
            BitmapFactory.decodeFile(cutoutFile.absolutePath)
        } else {
            val uri = Uri.parse(uriStr)
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists()) decodeSampledFile(f.absolutePath, maxSide) else null
            } else {
                decodeSampledUri(context, uri, maxSide)
            }
        }

        if (srcBmp == null) return@runCatching clip

        val isFlipped = clip.filter?.id?.contains("flip") == true
        val flippedBmp = if (isFlipped) {
            val m = Matrix().apply { preScale(-1f, 1f) }
            Bitmap.createBitmap(srcBmp, 0, 0, srcBmp.width, srcBmp.height, m, true).also {
                if (it !== srcBmp) srcBmp.recycle()
            }
        } else {
            srcBmp
        }

        val baseBmp = if (clip.rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(clip.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(flippedBmp, 0, 0, flippedBmp.width, flippedBmp.height, m, true).also {
                if (it !== flippedBmp) flippedBmp.recycle()
            }
        } else {
            flippedBmp
        }

        val uncroppedAspect = baseBmp.width.toFloat() / baseBmp.height.toFloat().coerceAtLeast(1f)
        val canvasAspect = targetWidth.toFloat() / targetHeight.toFloat().coerceAtLeast(1f)
        val (uncropW1, uncropH1) = if (uncroppedAspect >= canvasAspect) {
            targetWidth.toFloat() to (targetWidth / uncroppedAspect)
        } else {
            (targetHeight * uncroppedAspect) to targetHeight.toFloat()
        }

        val crop = clip.crop ?: CropRect(0f, 0f, 1f, 1f)
        val sx = (crop.left * baseBmp.width).roundToInt().coerceIn(0, baseBmp.width - 1)
        val sy = (crop.top * baseBmp.height).roundToInt().coerceIn(0, baseBmp.height - 1)
        val sw = ((crop.right - crop.left) * baseBmp.width).roundToInt().coerceIn(1, baseBmp.width - sx)
        val sh = ((crop.bottom - crop.top) * baseBmp.height).roundToInt().coerceIn(1, baseBmp.height - sy)

        val croppedBmp = Bitmap.createBitmap(baseBmp, sx, sy, sw, sh)
        if (croppedBmp !== baseBmp) baseBmp.recycle()

        val visW1 = (uncropW1 * (crop.right - crop.left)).roundToInt().coerceAtLeast(2)
        val visH1 = (uncropH1 * (crop.bottom - crop.top)).roundToInt().coerceAtLeast(2)
        val finalBmp = if (croppedBmp.width != visW1 || croppedBmp.height != visH1) {
            Bitmap.createScaledBitmap(croppedBmp, visW1, visH1, true).also {
                if (it !== croppedBmp) croppedBmp.recycle()
            }
        } else {
            croppedBmp
        }

        val outFile = File(context.cacheDir, "pip_rendered_${clip.id}.png")
        FileOutputStream(outFile).use { fos ->
            finalBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        finalBmp.recycle()

        val shiftRatioX = ((crop.left + crop.right - 1f) * uncropW1) / targetWidth
        val shiftRatioY = ((crop.top + crop.bottom - 1f) * uncropH1) / targetHeight

        val adjTransform = clip.transform.copy(
            offsetX = clip.transform.offsetX + shiftRatioX * clip.transform.scale,
            offsetY = clip.transform.offsetY + shiftRatioY * clip.transform.scale,
        )
        val adjKeyframes = clip.keyframes.map { kf ->
            kf.copy(
                offsetX = kf.offsetX + shiftRatioX * kf.scale,
                offsetY = kf.offsetY + shiftRatioY * kf.scale,
            )
        }

        clip.copy(
            mediaUri = Uri.fromFile(outFile).toString(),
            type = ClipType.STICKER,
            rotationDegrees = 0,
            crop = null,
            transform = adjTransform,
            keyframes = adjKeyframes,
        )
    }.getOrDefault(clip)

    private fun probePipVideoSpec(
        context: Context,
        clip: Clip,
        targetWidth: Int,
        targetHeight: Int,
    ): PipVideoSpec {
        var natW = targetWidth
        var natH = targetHeight
        var metaRot = 0
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(context, Uri.parse(clip.mediaUri))
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val r = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (w != null && h != null && w > 0 && h > 0) {
                natW = w
                natH = h
                metaRot = r
            }
        }
        runCatching { retriever.release() }

        val totalFixedRot = ((metaRot + clip.rotationDegrees) % 360 + 360) % 360
        val effW = if (totalFixedRot == 90 || totalFixedRot == 270) natH else natW
        val effH = if (totalFixedRot == 90 || totalFixedRot == 270) natW else natH

        val uncroppedAspect = effW.toFloat() / effH.toFloat().coerceAtLeast(1f)
        val canvasAspect = targetWidth.toFloat() / targetHeight.toFloat().coerceAtLeast(1f)
        val (uncropW1, uncropH1) = if (uncroppedAspect >= canvasAspect) {
            targetWidth.toFloat() to (targetWidth / uncroppedAspect)
        } else {
            (targetHeight * uncroppedAspect) to targetHeight.toFloat()
        }
        val crop = clip.crop ?: CropRect(0f, 0f, 1f, 1f)
        val basePipW = even((uncropW1 * (crop.right - crop.left)).roundToInt().coerceAtLeast(16))
        val basePipH = even((uncropH1 * (crop.bottom - crop.top)).roundToInt().coerceAtLeast(16))
        return PipVideoSpec(uncropW1, uncropH1, basePipW, basePipH)
    }

    private fun Clip.toPipVideoEditedMediaItem(spec: PipVideoSpec): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE)
                    .build(),
            )
            .build()

        val videoEffects = EffectMapper.previewVideoEffects(this).toMutableList()
        videoEffects += Presentation.createForWidthAndHeight(
            spec.basePipW,
            spec.basePipH,
            Presentation.LAYOUT_STRETCH_TO_FIT,
        )
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()
    }

    private fun createBlurredBackdropImage(
        context: Context,
        clip: Clip,
        targetWidth: Int,
        targetHeight: Int,
    ): String? = runCatching {
        val uri = Uri.parse(clip.mediaUri)
        val maxSide = max(targetWidth, targetHeight)
        val src = if (uri.scheme == "file") {
            decodeSampledFile(uri.path ?: "", maxSide)
        } else {
            decodeSampledUri(context, uri, maxSide)
        } ?: return@runCatching null

        val outBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        val small = Bitmap.createScaledBitmap(src, 64, 36, true)
        val blurredBg = Bitmap.createScaledBitmap(small, targetWidth, targetHeight, true)
        small.recycle()

        val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(blurredBg, 0f, 0f, bgPaint)
        blurredBg.recycle()

        canvas.drawColor(Color.argb(90, 0, 0, 0))

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

        val outFile = File(context.cacheDir, "blur_export_${clip.id}.jpg")
        FileOutputStream(outFile).use { out ->
            outBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        outBitmap.recycle()

        Uri.fromFile(outFile).toString()
    }.getOrNull()

    private fun decodeSampledUri(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun decodeSampledFile(path: String, maxSide: Int): Bitmap? {
        val f = File(path)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun computeInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= maxSide) {
            sample *= 2
        }
        return sample
    }

    private fun Clip.toEditedMediaItem(
        targetWidth: Int,
        targetHeight: Int,
        logoW: Int,
        logoH: Int,
        settings: ExportSettings,
        overlays: List<Clip>,
        presentationLayout: Int,
        incoming: com.actioncut.core.model.Transition? = null,
        outgoing: com.actioncut.core.model.Transition? = null,
        isFbTpl: Boolean = false,
        tplBottomOverlay: FbTemplateBottomOverlay? = null,
        tplBarOverlay: FbTemplateBarOverlay? = null,
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

        val templateOverlayEffect: Effect? = if (isFbTpl && tplBottomOverlay != null && tplBarOverlay != null) {
            OverlayEffect(listOf<TextureOverlay>(tplBottomOverlay, tplBarOverlay.forClip(timelineStartMs)))
        } else null

        val watermarkOverlayEffect: Effect = OverlayEffect(
            listOf<TextureOverlay>(
                NewsCrickWatermarkOverlay(targetWidth, targetHeight, logoW, logoH, timelineStartMs, watermarkBuffers),
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

    private fun pipCompositorSettings(
        pip: Clip,
        spec: PipVideoSpec,
        targetWidth: Int,
        targetHeight: Int,
    ): VideoCompositorSettings =
        object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes.first()
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings =
                if (inputId == 1) {
                    val timeInClipMs = (presentationTimeUs / 1_000L).coerceAtLeast(0L)
                    val props = Keyframes.propsAt(pip, timeInClipMs)
                    val t = props.transform
                    val crop = pip.crop ?: CropRect(0f, 0f, 1f, 1f)
                    val targetOffX =
                        (t.offsetX + ((crop.left + crop.right - 1f) * spec.uncropW1 * t.scale) / targetWidth)
                            .coerceIn(-1f, 1f)
                    val targetOffY =
                        (t.offsetY + ((crop.top + crop.bottom - 1f) * spec.uncropH1 * t.scale) / targetHeight)
                            .coerceIn(-1f, 1f)
                    val eased = if (pip.pipAnimation == PipAnimation.NONE) 1f else PipAnimations.easeOut(timeInClipMs)

                    val ox: Float
                    val oy: Float
                    val bx: Float
                    val by: Float
                    val animScale: Float
                    when (pip.pipAnimation) {
                        PipAnimation.SLIDE_LEFT -> {
                            ox = 1f - eased
                            oy = 0f
                            bx = -1f + (targetOffX + 1f) * eased
                            by = -targetOffY
                            animScale = 1f
                        }
                        PipAnimation.SLIDE_UP -> {
                            ox = 0f
                            oy = 1f - eased
                            bx = targetOffX
                            by = -1f + ((-targetOffY) + 1f) * eased
                            animScale = 1f
                        }
                        PipAnimation.ZOOM_IN -> {
                            ox = 0f
                            oy = 0f
                            bx = targetOffX
                            by = -targetOffY
                            animScale = 0.05f + 0.95f * eased
                        }
                        PipAnimation.NONE -> {
                            ox = 0f
                            oy = 0f
                            bx = targetOffX
                            by = -targetOffY
                            animScale = 1f
                        }
                    }

                    val finalScale = (t.scale * animScale).coerceAtLeast(0.01f)
                    StaticOverlaySettings.Builder()
                        .setScale(finalScale, finalScale)
                        .setOverlayFrameAnchor(ox, oy)
                        .setBackgroundFrameAnchor(bx, by)
                        .setAlphaScale(props.opacity.coerceIn(0f, 1f))
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
    private val logoW: Int,
    private val logoH: Int,
    private val clipStartMs: Long = 0L,
    private val buffers: PingPongBuffers,
) : BitmapOverlay() {

    private val defaultSettings: OverlaySettings = run {
        val marginX = targetWidth * 0.014f
        val marginY = targetHeight * 0.018f
        StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(
                1f - (2f * marginX / targetWidth.toFloat()),
                1f - (2f * marginY / targetHeight.toFloat()),
            )
            .build()
    }
    private var baseUs = Long.MIN_VALUE

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
        val currentPlayheadMs = clipStartMs + ((presentationTimeUs - baseUs).coerceAtLeast(0L) / 1000L)
        val timeSec = currentPlayheadMs / 1000f

        return buffers.drawFrame { canvas ->
            NewsCrickWatermarkRenderer.renderLogo(canvas, 0f, 0f, logoW.toFloat(), logoH.toFloat(), timeSec)
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = defaultSettings
}

private class FbTemplateBottomOverlay(
    context: Context,
    targetWidth: Int,
    targetHeight: Int,
    bottomImageUriStr: String?,
) : BitmapOverlay() {

    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, -1f)
        .setBackgroundFrameAnchor(0f, -1f)
        .build()

    private val staticBitmap: Bitmap = run {
        val vH = (targetWidth * (9f / 16f)).roundToInt()
        val barH = (targetHeight - 2 * vH).coerceAtLeast(0)
        val bottomY = vH + barH
        val bottomH = (targetHeight - bottomY).coerceAtLeast(2)

        val bmp = Bitmap.createBitmap(targetWidth, bottomH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0B0B0B"))

        if (!bottomImageUriStr.isNullOrBlank()) {
            runCatching {
                val uri = Uri.parse(bottomImageUriStr)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                var sample = 1
                val longest = max(bounds.outWidth, bounds.outHeight)
                val targetMax = max(targetWidth, bottomH)
                while (targetMax > 0 && longest / sample > targetMax * 2) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val src = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
                if (src != null && !src.isRecycled) {
                    val iw = src.width.toFloat()
                    val ih = src.height.toFloat()
                    val sc = minOf(targetWidth.toFloat() / iw, bottomH.toFloat() / ih)
                    val dw = iw * sc
                    val dh = ih * sc
                    val dx = (targetWidth - dw) / 2f
                    val dy = (bottomH - dh) / 2f
                    val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    canvas.drawBitmap(src, null, RectF(dx, dy, dx + dw, dy + dh), imgPaint)
                    src.recycle()
                }
            }
        }
        bmp
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = staticBitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}

private class FbTemplateBarOverlay(
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val vH: Int,
    private val barH: Int,
    private val buffers: PingPongBuffers,
    private val clipStartMs: Long = 0L,
) : BitmapOverlay() {

    private val defaultSettings: OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, 1f)
        .setBackgroundFrameAnchor(0f, 1f - (2f * vH.toFloat() / targetHeight.toFloat()))
        .build()

    private var baseUs = Long.MIN_VALUE

    fun forClip(startMs: Long): FbTemplateBarOverlay =
        FbTemplateBarOverlay(targetWidth, targetHeight, vH, barH, buffers, startMs)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (baseUs == Long.MIN_VALUE) baseUs = presentationTimeUs
        val currentPlayheadMs = clipStartMs + ((presentationTimeUs - baseUs).coerceAtLeast(0L) / 1000L)
        val timeSeconds = currentPlayheadMs / 1000f

        return buffers.drawFrame { canvas ->
            FbTemplateBarRenderer.drawTplBar(canvas, targetWidth.toFloat(), barH.toFloat(), timeSeconds)
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = defaultSettings
}
