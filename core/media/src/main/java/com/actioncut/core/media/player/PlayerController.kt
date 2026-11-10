package com.actioncut.core.media.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.actioncut.core.media.export.EffectMapper
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.Timeline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
    }

    private val audioPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
    }

    val pipPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = false
        repeatMode = Player.REPEAT_MODE_OFF
        volume = 0f
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var clipVolumes: List<Float> = emptyList()
    private var previewClips: List<Clip> = emptyList()
    private var lastPreviewSignature: String? = null

    private var videoStartsMs: List<Long> = emptyList()
    private var audioStartsMs: List<Long> = emptyList()
    private var pipStartsMs: List<Long> = emptyList()

    var totalTimelineMs: Long = 0L
    var totalVideoMs: Long = 0L
    var totalAudioMs: Long = 0L

    // HTML Video Editor பாணியிலான மாஸ்டர் டைம்லைன் கடிகாரம் (Master Timeline Clock)
    private var masterPlayheadMs: Long = 0L
    private var isTimelinePlaying: Boolean = false
    private var clockJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyCurrentVolume()
            applyPreviewEffects()
        }
    }

    init {
        player.addListener(listener)
    }

    fun setTimeline(timeline: Timeline) {
        val clips = previewClips(timeline)
        previewClips = clips
        clipVolumes = clips.map { it.volume }
        videoStartsMs = cumulativeStarts(clips.map { previewDurationMs(it) })
        totalVideoMs = (videoStartsMs.lastOrNull() ?: 0L) + (clips.lastOrNull()?.let { previewDurationMs(it) } ?: 0L)

        val items = clips.map { clip ->
            val builder = MediaItem.Builder().setUri(clip.mediaUri)
            if (clip.type == ClipType.IMAGE) {
                builder.setImageDurationMs(clip.timelineDurationMs)
            } else {
                builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceInMs)
                        .setEndPositionMs(
                            if (clip.sourceOutMs > clip.sourceInMs) clip.sourceOutMs
                            else C.TIME_END_OF_SOURCE,
                        )
                        .build(),
                )
            }
            builder.build()
        }
        player.setMediaItems(items)
        player.prepare()
        applyCurrentVolume()
        applyPreviewEffects(force = true)

        val audioClips = audioClipsOf(timeline)
        audioStartsMs = cumulativeStarts(audioClips.map { previewDurationMs(it) })
        totalAudioMs = (audioStartsMs.lastOrNull() ?: 0L) + (audioClips.lastOrNull()?.let { previewDurationMs(it) } ?: 0L)
        audioPlayer.setMediaItems(audioClips.map { it.toClippedMediaItem() })
        audioPlayer.prepare()

        val pipClips = pipClipsOf(timeline)
        pipStartsMs = cumulativeStarts(pipClips.map { previewDurationMs(it) })
        pipPlayer.setMediaItems(pipClips.map { it.toClippedMediaItem() })
        pipPlayer.prepare()

        // ஆடியோ, வீடியோ மற்றும் டைம்லைன் நீளங்களில் எது அதிகமோ அதுவே முழு கால அளவு (06:12 வரை)
        totalTimelineMs = maxOf(totalVideoMs, totalAudioMs, timeline.durationMs)
        masterPlayheadMs = 0L

        pushState()
    }

    private fun previewDurationMs(clip: Clip): Long = clip.timelineDurationMs.coerceAtLeast(1L)

    private fun cumulativeStarts(durations: List<Long>): List<Long> {
        var acc = 0L
        return durations.map { val start = acc; acc += it; start }
    }

    fun masterPositionMs(): Long = masterPlayheadMs

    private fun seekAbsolute(p: ExoPlayer, starts: List<Long>, absMs: Long) {
        if (p.mediaItemCount == 0) return
        var idx = 0
        for (i in 0 until p.mediaItemCount) {
            if (absMs >= (starts.getOrElse(i) { 0L })) idx = i else break
        }
        val within = (absMs - starts.getOrElse(idx) { 0L }).coerceAtLeast(0L)
        p.seekTo(idx, within)
    }

    private fun Clip.toClippedMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(mediaUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceInMs)
                    .setEndPositionMs(if (sourceOutMs > sourceInMs) sourceOutMs else C.TIME_END_OF_SOURCE)
                    .build(),
            )
            .build()

    private fun audioClipsOf(timeline: Timeline): List<Clip> =
        timeline.audioTracks
            .flatMap { it.clips }
            .filter { it.mediaUri != null && it.volume > 0f }
            .sortedBy { it.timelineStartMs }

    private fun pipClipsOf(timeline: Timeline): List<Clip> =
        timeline.tracks
            .filter { it.type == com.actioncut.core.model.TrackType.OVERLAY }
            .flatMap { it.clips }
            .filter { it.mediaUri != null && it.type == ClipType.VIDEO }
            .sortedBy { it.timelineStartMs }

    fun updateVolumes(timeline: Timeline) {
        clipVolumes = previewClips(timeline).map { it.volume }
        applyCurrentVolume()
    }

    fun refreshPreviewEffects(timeline: Timeline) {
        previewClips = previewClips(timeline)
        applyPreviewEffects()
    }

    private fun applyPreviewEffects(force: Boolean = false) {
        val clip = previewClips.getOrNull(player.currentMediaItemIndex)
        val signature = previewEffectSignature(clip)
        if (!force && signature == lastPreviewSignature) return
        lastPreviewSignature = signature
        val effects: List<Effect> = clip?.let { EffectMapper.previewVideoEffects(it) } ?: emptyList()
        runCatching {
            player.setVideoEffects(effects)
            if (!player.isPlaying && player.playbackState == Player.STATE_READY) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.takeIf { it > 0L } ?: (pos + 100L)
                val target = if (pos + 1L < dur) pos + 1L else (pos - 1L).coerceAtLeast(0L)
                if (target != pos) player.seekTo(target)
            }
        }
    }

    private fun previewEffectSignature(clip: Clip?): String = if (clip == null) "" else
        "${clip.id}|${clip.rotationDegrees}|${clip.transform.rotationDegrees}|" +
            "${clip.crop}|${clip.adjustments}|${clip.filter}|${clip.effects}"

    private fun previewClips(timeline: Timeline): List<Clip> =
        timeline.videoTracks.firstOrNull()?.clips.orEmpty()
            .filter { it.mediaUri != null && it.type != ClipType.TEXT }

    private fun applyCurrentVolume() {
        val index = player.currentMediaItemIndex
        player.volume = clipVolumes.getOrElse(index) { 1f }.coerceIn(0f, 1f)
    }

    /**
     * HTML எடிட்டர் பாணியில் ஆடியோ முடியும் வரை தொடர்ந்து பிளே செய்யும் செயல்பாடு
     */
    fun play() {
        if (masterPlayheadMs >= totalTimelineMs) {
            seekTo(0)
        }
        isTimelinePlaying = true
        startTimelineClock()
        pushState()
    }

    fun pause() {
        isTimelinePlaying = false
        clockJob?.cancel()
        player.pause()
        audioPlayer.pause()
        pipPlayer.pause()
        pushState()
    }

    fun togglePlayPause() {
        if (isTimelinePlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        masterPlayheadMs = positionMs.coerceIn(0, totalTimelineMs)
        syncMediaPlayersWithTime(masterPlayheadMs)
        pushState()
    }

    private fun syncMediaPlayersWithTime(posMs: Long) {
        // வீடியோ உள்ள பகுதியில் வீடியோ இயங்கும்; வீடியோ முடிந்த பகுதியில் கடைசி பிரேம் நிலைத்து நிற்கும்
        if (posMs < totalVideoMs) {
            seekAbsolute(player, videoStartsMs, posMs)
            if (isTimelinePlaying && !player.isPlaying) {
                player.play()
            }
        } else {
            if (player.mediaItemCount > 0) {
                val lastIdx = player.mediaItemCount - 1
                val lastStart = videoStartsMs.getOrElse(lastIdx) { 0L }
                val lastItemDur = (totalVideoMs - lastStart).coerceAtLeast(1L)
                player.seekTo(lastIdx, (lastItemDur - 5L).coerceAtLeast(0L))
                player.pause()
            }
        }

        // ஆடியோ எப்போதுமே தனது இடத்திற்கு ஏற்ப சரியாக ஒலிக்கச் செய்தல்
        if (posMs < totalAudioMs) {
            seekAbsolute(audioPlayer, audioStartsMs, posMs)
            if (isTimelinePlaying && !audioPlayer.isPlaying) {
                audioPlayer.play()
            }
        } else {
            audioPlayer.pause()
        }

        if (pipPlayer.mediaItemCount > 0) {
            seekAbsolute(pipPlayer, pipStartsMs, posMs)
            if (isTimelinePlaying && !pipPlayer.isPlaying) {
                pipPlayer.play()
            }
        }
    }

    private fun startTimelineClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            var lastRealTime = System.currentTimeMillis()
            while (isActive && isTimelinePlaying) {
                val now = System.currentTimeMillis()
                val delta = now - lastRealTime
                lastRealTime = now

                masterPlayheadMs = (masterPlayheadMs + delta).coerceAtMost(totalTimelineMs)
                syncMediaPlayersWithTime(masterPlayheadMs)
                pushState()

                if (masterPlayheadMs >= totalTimelineMs) {
                    pause()
                    break
                }
                delay(30L) // 30 FPS வேகத்தில் டைம்லைன் முன்னோக்கி ஓடும்
            }
        }
    }

    fun setSpeed(speed: Float) {
        val params = PlaybackParameters(speed.coerceIn(0.1f, 10f))
        player.playbackParameters = params
        audioPlayer.playbackParameters = params
        pipPlayer.playbackParameters = params
    }

    fun positionFlow(intervalMs: Long = 33L): Flow<Long> = flow {
        while (true) {
            emit(masterPlayheadMs)
            delay(intervalMs)
        }
    }

    fun release() {
        clockJob?.cancel()
        player.removeListener(listener)
        player.release()
        audioPlayer.release()
        pipPlayer.release()
    }

    private fun pushState() {
        _state.value = PlaybackState(
            isPlaying = isTimelinePlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING || audioPlayer.playbackState == Player.STATE_BUFFERING,
            positionMs = masterPlayheadMs,
            durationMs = totalTimelineMs,
            isEnded = masterPlayheadMs >= totalTimelineMs && !isTimelinePlaying,
        )
    }
}
