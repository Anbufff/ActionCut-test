package com.actioncut.core.media.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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
import kotlin.math.abs

class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
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
    private var videoDurationsMs: List<Long> = emptyList()
    private var pipStartsMs: List<Long> = emptyList()
    private var pipDurationsMs: List<Long> = emptyList()

    var totalTimelineMs: Long = 0L
    var totalVideoMs: Long = 0L
    var totalAudioMs: Long = 0L

    private var masterPlayheadMs: Long = 0L
    private var isTimelinePlaying: Boolean = false
    private var isScrubbing: Boolean = false
    private var lastScrubSeekRealMs: Long = 0L
    private var scrubSeekJob: Job? = null
    private var clockJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private data class AudioTrackState(
        val clip: Clip,
        val player: ExoPlayer
    )
    private val audioTrackStates = mutableListOf<AudioTrackState>()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyCurrentVolume()
            applyPreviewEffects()
        }
    }

    init {
        player.addListener(listener)
    }

    fun setTimeline(timeline: Timeline, preservePositionMs: Long? = null) {
        val targetPos = preservePositionMs ?: masterPlayheadMs

        audioTrackStates.forEach { it.player.release() }
        audioTrackStates.clear()

        val clips = previewClips(timeline)
        previewClips = clips
        clipVolumes = clips.map { it.volume }
        videoDurationsMs = clips.map { previewDurationMs(it) }
        videoStartsMs = cumulativeStarts(videoDurationsMs)
        totalVideoMs = (videoStartsMs.lastOrNull() ?: 0L) + (videoDurationsMs.lastOrNull() ?: 0L)

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
        totalAudioMs = audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L

        audioClips.forEach { aClip ->
            val aPlayer = ExoPlayer.Builder(context).build().apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
                volume = aClip.volume.coerceIn(0f, 1f)
                val mItem = MediaItem.Builder().setUri(aClip.mediaUri).build()
                setMediaItem(mItem)
                prepare()
            }
            audioTrackStates.add(AudioTrackState(aClip, aPlayer))
        }

        val pipClips = pipClipsOf(timeline)
        pipDurationsMs = pipClips.map { previewDurationMs(it) }
        pipStartsMs = cumulativeStarts(pipDurationsMs)
        pipPlayer.setMediaItems(pipClips.map { it.toClippedMediaItem() })
        pipPlayer.prepare()

        totalTimelineMs = maxOf(totalVideoMs, totalAudioMs, timeline.durationMs)
        masterPlayheadMs = targetPos.coerceIn(0L, totalTimelineMs)

        syncVideoState(forceSeek = true)
        syncAudioState(forceSeek = true)
        syncPipState(forceSeek = true)

        pushState()
    }

    private fun previewDurationMs(clip: Clip): Long = clip.timelineDurationMs.coerceAtLeast(1L)

    private fun cumulativeStarts(durations: List<Long>): List<Long> {
        var acc = 0L
        return durations.map { val start = acc; acc += it; start }
    }

    fun masterPositionMs(): Long = masterPlayheadMs

    private fun seekAbsolute(
        p: ExoPlayer,
        starts: List<Long>,
        durations: List<Long>,
        absMs: Long,
        forScrub: Boolean,
    ) {
        if (p.mediaItemCount == 0) return
        var idx = 0
        for (i in 0 until p.mediaItemCount) {
            if (absMs >= (starts.getOrElse(i) { 0L })) idx = i else break
        }
        var within = (absMs - starts.getOrElse(idx) { 0L }).coerceAtLeast(0L)
        if (forScrub) {
            val clipDur = durations.getOrElse(idx) { 0L }
            if (clipDur > 120L && within > clipDur - 60L) {
                within = clipDur - 60L
            }
        }
        if (p.currentMediaItemIndex == idx && abs(p.currentPosition - within) < 16L) {
            return
        }
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

        val activeAudioClips = audioClipsOf(timeline)
        audioTrackStates.forEach { state ->
            val match = activeAudioClips.find { it.id == state.clip.id }
            if (match != null) {
                state.player.volume = match.volume.coerceIn(0f, 1f)
            }
        }
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

    fun play() {
        scrubSeekJob?.cancel()
        if (isScrubbing) {
            isScrubbing = false
            setScrubMode(false)
        }
        if (masterPlayheadMs >= totalTimelineMs) {
            seekTo(0)
        }
        isTimelinePlaying = true
        syncVideoState(forceSeek = true)
        syncAudioState(forceSeek = true)
        syncPipState(forceSeek = true)
        startTimelineClock()
        pushState()
    }

    fun pause() {
        isTimelinePlaying = false
        clockJob?.cancel()
        player.pause()
        audioTrackStates.forEach { it.player.pause() }
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

    fun beginScrub() {
        if (isTimelinePlaying) {
            pause()
        }
        if (!isScrubbing) {
            isScrubbing = true
            setScrubMode(true)
        }
    }

    fun scrubTo(positionMs: Long) {
        masterPlayheadMs = positionMs.coerceIn(0L, totalTimelineMs)
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastScrubSeekRealMs
        if (elapsed >= SCRUB_SEEK_INTERVAL_MS) {
            scrubSeekJob?.cancel()
            lastScrubSeekRealMs = now
            syncVideoState(forceSeek = true, forScrub = true)
            syncPipState(forceSeek = true, forScrub = true)
        } else {
            scrubSeekJob?.cancel()
            val waitMs = SCRUB_SEEK_INTERVAL_MS - elapsed
            scrubSeekJob = scope.launch {
                delay(waitMs)
                lastScrubSeekRealMs = SystemClock.elapsedRealtime()
                syncVideoState(forceSeek = true, forScrub = true)
                syncPipState(forceSeek = true, forScrub = true)
            }
        }
    }

    fun endScrub(positionMs: Long) {
        scrubSeekJob?.cancel()
        isScrubbing = false
        setScrubMode(false)
        masterPlayheadMs = positionMs.coerceIn(0L, totalTimelineMs)
        syncVideoState(forceSeek = true, forScrub = false)
        syncAudioState(forceSeek = true)
        syncPipState(forceSeek = true, forScrub = false)
        pushState()
    }

    private fun setScrubMode(enabled: Boolean) {
        val params = if (enabled) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT
        runCatching {
            player.setScrubbingModeEnabled(enabled)
            player.setSeekParameters(params)
        }
        if (pipPlayer.mediaItemCount > 0) {
            runCatching {
                pipPlayer.setScrubbingModeEnabled(enabled)
                pipPlayer.setSeekParameters(params)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        scrubSeekJob?.cancel()
        masterPlayheadMs = positionMs.coerceIn(0, totalTimelineMs)
        syncVideoState(forceSeek = true)
        syncAudioState(forceSeek = true)
        syncPipState(forceSeek = true)
        pushState()
    }

    private fun syncVideoState(forceSeek: Boolean, forScrub: Boolean = false) {
        if (masterPlayheadMs < totalVideoMs) {
            if (forceSeek) {
                seekAbsolute(player, videoStartsMs, videoDurationsMs, masterPlayheadMs, forScrub)
            }
            if (isTimelinePlaying && !player.isPlaying) {
                player.play()
            }
        } else {
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    private fun syncAudioState(forceSeek: Boolean) {
        audioTrackStates.forEach { state ->
            val aClip = state.clip
            val aPlayer = state.player
            val isInsideClip = masterPlayheadMs >= aClip.timelineStartMs && masterPlayheadMs < aClip.timelineEndMs

            if (isInsideClip) {
                val offsetInClipMs = masterPlayheadMs - aClip.timelineStartMs
                val targetMediaMs = aClip.sourceInMs + (offsetInClipMs * aClip.speed).toLong()

                if (forceSeek || abs(aPlayer.currentPosition - targetMediaMs) > 400L) {
                    aPlayer.seekTo(targetMediaMs.coerceAtLeast(0L))
                }
                aPlayer.volume = aClip.volume.coerceIn(0f, 1f)
                if (isTimelinePlaying && !aPlayer.isPlaying) {
                    aPlayer.play()
                }
            } else {
                if (aPlayer.isPlaying) {
                    aPlayer.pause()
                }
            }
        }
    }

    private fun syncPipState(forceSeek: Boolean, forScrub: Boolean = false) {
        if (pipPlayer.mediaItemCount > 0) {
            if (forceSeek) {
                seekAbsolute(pipPlayer, pipStartsMs, pipDurationsMs, masterPlayheadMs, forScrub)
            }
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
                syncVideoState(forceSeek = false)
                syncAudioState(forceSeek = false)
                syncPipState(forceSeek = false)
                pushState()

                if (masterPlayheadMs >= totalTimelineMs) {
                    pause()
                    break
                }
                delay(30L)
            }
        }
    }

    fun setSpeed(speed: Float) {
        val params = PlaybackParameters(speed.coerceIn(0.1f, 10f))
        player.playbackParameters = params
        audioTrackStates.forEach { it.player.playbackParameters = params }
        pipPlayer.playbackParameters = params
    }

    fun positionFlow(intervalMs: Long = 33L): Flow<Long> = flow {
        while (true) {
            emit(masterPlayheadMs)
            delay(intervalMs)
        }
    }

    fun release() {
        scrubSeekJob?.cancel()
        clockJob?.cancel()
        player.removeListener(listener)
        player.release()
        audioTrackStates.forEach { it.player.release() }
        audioTrackStates.clear()
        pipPlayer.release()
    }

    private fun pushState() {
        _state.value = PlaybackState(
            isPlaying = isTimelinePlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = masterPlayheadMs,
            durationMs = totalTimelineMs,
            isEnded = masterPlayheadMs >= totalTimelineMs && !isTimelinePlaying,
        )
    }

    private companion object {
        const val SCRUB_SEEK_INTERVAL_MS = 40L
    }
}
