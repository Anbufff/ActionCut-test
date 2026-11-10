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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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
    private var isPlayingAudioOnly: Boolean = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            mirrorSecondaryPlayers(isPlaying)
            pushState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                // வீடியோ முடிந்தாலும் ஆடியோ நேரம் மீதமிருந்தால் ஆடியோவை நிறுத்தாமல் பிளே செய்ய அனுமதித்தல்
                if (masterPositionMs() < totalTimelineMs && (audioPlayer.isPlaying || audioPlayer.playbackState == Player.STATE_READY)) {
                    isPlayingAudioOnly = true
                    pushState()
                    return
                } else {
                    audioPlayer.pause()
                    pipPlayer.pause()
                    isPlayingAudioOnly = false
                }
            }
            pushState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyCurrentVolume()
            applyPreviewEffects()
            pushState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = pushState()
    }

    private val audioListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (masterPositionMs() >= totalTimelineMs) {
                    isPlayingAudioOnly = false
                    player.pause()
                    pushState()
                }
            }
        }
    }

    init {
        player.addListener(listener)
        audioPlayer.addListener(audioListener)
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

        // வீடியோ மற்றும் ஆடியோ இரண்டில் எது அதிக நேரமோ அதுவே முழு கால அளவு
        totalTimelineMs = maxOf(totalVideoMs, totalAudioMs, timeline.durationMs)
        isPlayingAudioOnly = false

        pushState()
    }

    private fun previewDurationMs(clip: Clip): Long = clip.timelineDurationMs.coerceAtLeast(1L)

    private fun cumulativeStarts(durations: List<Long>): List<Long> {
        var acc = 0L
        return durations.map { val start = acc; acc += it; start }
    }

    fun masterPositionMs(): Long {
        // வீடியோ முடிந்த பிறகு ஆடியோவின் நேரத்தைக் கணக்கிட்டு பிளேஹெட்டை முன்னோக்கி நகர்த்துதல்
        if (isPlayingAudioOnly || (player.mediaItemCount > 0 && player.playbackState == Player.STATE_ENDED)) {
            if (audioPlayer.mediaItemCount > 0) {
                val audioBase = audioStartsMs.getOrElse(audioPlayer.currentMediaItemIndex) { 0L }
                val audioPos = (audioBase + audioPlayer.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
                return audioPos.coerceAtMost(totalTimelineMs)
            }
        }
        if (player.mediaItemCount == 0) {
            if (audioPlayer.mediaItemCount > 0) {
                val audioBase = audioStartsMs.getOrElse(audioPlayer.currentMediaItemIndex) { 0L }
                return (audioBase + audioPlayer.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
            }
            return 0L
        }
        val base = videoStartsMs.getOrElse(player.currentMediaItemIndex) { 0L }
        return (base + player.currentPosition.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

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

    private fun mirrorSecondaryPlayers(isPlaying: Boolean) {
        val master = masterPositionMs()
        if (audioPlayer.mediaItemCount > 0) {
            if (isPlaying) {
                seekAbsolute(audioPlayer, audioStartsMs, master)
                audioPlayer.play()
            } else {
                audioPlayer.pause()
            }
        }
        if (pipPlayer.mediaItemCount > 0) {
            if (isPlaying) {
                seekAbsolute(pipPlayer, pipStartsMs, master)
                pipPlayer.play()
            } else {
                pipPlayer.pause()
            }
        }
    }

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

    fun play() {
        val pos = masterPositionMs()
        if (pos >= totalTimelineMs) {
            seekTo(0)
        }
        if (pos < totalVideoMs) {
            isPlayingAudioOnly = false
            player.play()
        } else {
            isPlayingAudioOnly = true
        }
        if (audioPlayer.mediaItemCount > 0) {
            audioPlayer.play()
        }
        pushState()
    }

    fun pause() {
        isPlayingAudioOnly = false
        player.pause()
        audioPlayer.pause()
        pipPlayer.pause()
        pushState()
    }

    fun togglePlayPause() {
        val isAnyPlaying = player.isPlaying || audioPlayer.isPlaying || isPlayingAudioOnly
        if (isAnyPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceIn(0, totalTimelineMs)
        if (pos <= totalVideoMs) {
            isPlayingAudioOnly = false
            seekAbsolute(player, videoStartsMs, pos)
        } else {
            isPlayingAudioOnly = true
            // வீடியோ முடிந்த பகுதிக்கு அப்பால் நகர்த்தினால் கடைசி ஸ்கோர்கார்டு பிரேமை திரையில் வைத்திருத்தல்
            if (player.mediaItemCount > 0) {
                val lastIdx = player.mediaItemCount - 1
                val lastStart = videoStartsMs.getOrElse(lastIdx) { 0L }
                val lastItemDur = (totalVideoMs - lastStart).coerceAtLeast(1L)
                player.seekTo(lastIdx, (lastItemDur - 10L).coerceAtLeast(0L))
            }
        }
        seekAbsolute(audioPlayer, audioStartsMs, pos)
        seekAbsolute(pipPlayer, pipStartsMs, pos)
        pushState()
    }

    fun setSpeed(speed: Float) {
        val params = PlaybackParameters(speed.coerceIn(0.1f, 10f))
        player.playbackParameters = params
        audioPlayer.playbackParameters = params
        pipPlayer.playbackParameters = params
    }

    fun positionFlow(intervalMs: Long = 33L): Flow<Long> = flow {
        while (true) {
            val master = masterPositionMs()
            if (!isPlayingAudioOnly && audioPlayer.isPlaying) {
                val audioAbs = audioStartsMs.getOrElse(audioPlayer.currentMediaItemIndex) { 0L } + audioPlayer.currentPosition
                if (kotlin.math.abs(audioAbs - master) > 300) {
                    seekAbsolute(audioPlayer, audioStartsMs, master)
                }
            }
            emit(master)
            delay(intervalMs)
        }
    }

    fun release() {
        player.removeListener(listener)
        audioPlayer.removeListener(audioListener)
        player.release()
        audioPlayer.release()
        pipPlayer.release()
    }

    private fun pushState() {
        val isAnyPlaying = player.isPlaying || audioPlayer.isPlaying || isPlayingAudioOnly
        val isEnded = masterPositionMs() >= totalTimelineMs && !isAnyPlaying
        _state.value = PlaybackState(
            isPlaying = isAnyPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING || audioPlayer.playbackState == Player.STATE_BUFFERING,
            positionMs = masterPositionMs(),
            durationMs = totalTimelineMs,
            isEnded = isEnded,
        )
    }
}
