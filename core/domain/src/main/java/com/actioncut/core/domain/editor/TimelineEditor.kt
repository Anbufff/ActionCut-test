package com.actioncut.core.domain.editor

import com.actioncut.core.model.AudioFade
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ColorAdjustments
import com.actioncut.core.model.Filter
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.Track
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.Transform
import com.actioncut.core.model.Transition
import com.actioncut.core.model.TransitionType
import com.actioncut.core.model.VisualEffect
import com.actioncut.core.model.SpeedPresets
import java.util.UUID

object TimelineEditor {

    const val MIN_CLIP_DURATION_MS = 100L

    // ----------------------------------------------------------------- Track Operations
    fun addTrack(timeline: Timeline, type: TrackType): Pair<Timeline, String> {
        val id = "trk_${UUID.randomUUID().toString().take(8)}"
        val track = Track(id = id, type = type, index = timeline.tracks.size)
        return timeline.copy(tracks = timeline.tracks + track) to id
    }

    fun removeTrack(timeline: Timeline, trackId: String): Timeline =
        timeline.copy(tracks = timeline.tracks.filterNot { it.id == trackId })

    // ----------------------------------------------------------------- Structural Operations
    fun appendClip(timeline: Timeline, trackId: String, clip: Clip): Timeline {
        val track = timeline.track(trackId) ?: return timeline
        val start = track.durationMs
        val placed = clip.copy(
            timelineStartMs = start,
            timelineEndMs = start + clip.timelineDurationMs,
        )
        return mapTrack(timeline, trackId) { it.copy(clips = it.clips + placed) }
    }

    fun addClip(timeline: Timeline, trackId: String, clip: Clip): Timeline =
        mapTrack(timeline, trackId) { t -> t.copy(clips = (t.clips + clip).sortedBy { it.timelineStartMs }) }

    fun insertClip(timeline: Timeline, trackId: String, clip: Clip): Timeline {
        return mapTrack(timeline, trackId) { track ->
            val shifted = track.clips.map { existing ->
                if (existing.timelineStartMs >= clip.timelineStartMs) {
                    existing.shiftedBy(clip.timelineDurationMs)
                } else {
                    existing
                }
            }
            track.copy(clips = (shifted + clip).sortedBy { it.timelineStartMs })
        }
    }

    fun removeClip(timeline: Timeline, clipId: String, ripple: Boolean = true): Timeline {
        val located = findClip(timeline, clipId) ?: return timeline
        val (track, clip) = located
        return mapTrack(timeline, track.id) { t ->
            val remaining = t.clips.filterNot { it.id == clipId }
            val result = if (ripple) {
                remaining.map { c ->
                    if (c.timelineStartMs >= clip.timelineEndMs) {
                        c.shiftedBy(-clip.timelineDurationMs)
                    } else {
                        c
                    }
                }
            } else {
                remaining
            }
            t.copy(clips = result)
        }
    }

    fun splitClip(timeline: Timeline, clipId: String, atTimelineMs: Long): Timeline {
        val located = findClip(timeline, clipId) ?: return timeline
        val (track, clip) = located
        if (atTimelineMs <= clip.timelineStartMs + MIN_CLIP_DURATION_MS) return timeline
        if (atTimelineMs >= clip.timelineEndMs - MIN_CLIP_DURATION_MS) return timeline

        val offsetTimeline = atTimelineMs - clip.timelineStartMs
        val sourceMid = sourceAt(clip, offsetTimeline)

        val first: Clip
        val second: Clip
        if (clip.hasSourceWindow) {
            if (clip.isReversed) {
                first = clip.copy(
                    id = newClipId(),
                    timelineEndMs = atTimelineMs,
                    sourceInMs = sourceMid,
                )
                second = clip.copy(
                    id = newClipId(),
                    timelineStartMs = atTimelineMs,
                    sourceOutMs = sourceMid,
                    transitionToNext = clip.transitionToNext,
                )
            } else {
                first = clip.copy(
                    id = newClipId(),
                    timelineEndMs = atTimelineMs,
                    sourceOutMs = sourceMid,
                    transitionToNext = null,
                )
                second = clip.copy(
                    id = newClipId(),
                    timelineStartMs = atTimelineMs,
                    sourceInMs = sourceMid,
                )
            }
        } else {
            first = clip.copy(id = newClipId(), timelineEndMs = atTimelineMs, transitionToNext = null)
            second = clip.copy(id = newClipId(), timelineStartMs = atTimelineMs)
        }

        return mapTrack(timeline, track.id) { t ->
            t.copy(clips = t.clips.flatMap { if (it.id == clipId) listOf(first, second) else listOf(it) })
        }
    }

    fun trimClipStart(timeline: Timeline, clipId: String, newStartMs: Long): Timeline {
        val (_, clip) = findClip(timeline, clipId) ?: return timeline
        val clamped = newStartMs.coerceIn(
            (clip.timelineStartMs - headRoom(clip)),
            clip.timelineEndMs - MIN_CLIP_DURATION_MS,
        )
        val delta = clamped - clip.timelineStartMs
        val sourceDelta = (delta * clip.speed).toLong()
        return updateClip(timeline, clipId) { c ->
            if (!c.hasSourceWindow) {
                c.copy(timelineStartMs = clamped)
            } else if (c.isReversed) {
                c.copy(timelineStartMs = clamped, sourceOutMs = c.sourceOutMs - sourceDelta)
            } else {
                c.copy(timelineStartMs = clamped, sourceInMs = c.sourceInMs + sourceDelta)
            }
        }
    }

    fun trimClipEnd(timeline: Timeline, clipId: String, newEndMs: Long): Timeline {
        val (track, clip) = findClip(timeline, clipId) ?: return timeline
        val clamped = newEndMs.coerceIn(
            clip.timelineStartMs + MIN_CLIP_DURATION_MS,
            clip.timelineEndMs + tailRoom(clip),
        )
        val delta = clamped - clip.timelineEndMs
        if (delta == 0L) return timeline

        val sourceDelta = (delta * clip.speed).toLong()
        val updatedTimeline = updateClip(timeline, clipId) { c ->
            if (!c.hasSourceWindow) {
                c.copy(timelineEndMs = clamped)
            } else if (c.isReversed) {
                c.copy(timelineEndMs = clamped, sourceInMs = c.sourceInMs - sourceDelta)
            } else {
                c.copy(timelineEndMs = clamped, sourceOutMs = c.sourceOutMs + sourceDelta)
            }
        }

        return mapTrack(updatedTimeline, track.id) { t ->
            val sorted = t.clips.sortedBy { it.timelineStartMs }
            val targetIdx = sorted.indexOfFirst { it.id == clipId }
            if (targetIdx < 0) return@mapTrack t
            val result = sorted.mapIndexed { idx, c ->
                if (idx > targetIdx) {
                    c.copy(
                        timelineStartMs = (c.timelineStartMs + delta).coerceAtLeast(0),
                        timelineEndMs = (c.timelineEndMs + delta).coerceAtLeast(0),
                    )
                } else c
            }
            t.copy(clips = result)
        }
    }

    fun moveClip(
        timeline: Timeline,
        clipId: String,
        targetTrackId: String,
        newStartMs: Long,
    ): Timeline {
        val (sourceTrack, clip) = findClip(timeline, clipId) ?: return timeline
        if (timeline.track(targetTrackId) == null) return timeline

        if (sourceTrack.id != targetTrackId) {
            val start = newStartMs.coerceAtLeast(0)
            val moved = clip.copy(
                timelineStartMs = start,
                timelineEndMs = start + clip.timelineDurationMs,
            )
            val withoutOriginal = mapTrack(timeline, sourceTrack.id) { t ->
                t.copy(clips = t.clips.filterNot { it.id == clipId })
            }
            return mapTrack(withoutOriginal, targetTrackId) { t ->
                t.copy(clips = (t.clips + moved).sortedBy { it.timelineStartMs })
            }
        }

        val sortedClips = sourceTrack.clips.sortedBy { it.timelineStartMs }
        val targetIndex = sortedClips.indexOfFirst { it.id == clipId }
        if (targetIndex < 0) return timeline

        val minAllowedStart = if (targetIndex > 0) sortedClips[targetIndex - 1].timelineEndMs else 0L
        val clampedStart = newStartMs.coerceAtLeast(minAllowedStart)
        val delta = clampedStart - clip.timelineStartMs
        if (delta == 0L) return timeline

        return mapTrack(timeline, sourceTrack.id) { track ->
            val updatedClips = sortedClips.mapIndexed { index, c ->
                when {
                    c.id == clipId -> {
                        c.copy(
                            timelineStartMs = clampedStart,
                            timelineEndMs = clampedStart + c.timelineDurationMs,
                        )
                    }
                    index > targetIndex -> {
                        val shiftedStart = (c.timelineStartMs + delta).coerceAtLeast(0)
                        c.copy(
                            timelineStartMs = shiftedStart,
                            timelineEndMs = shiftedStart + c.timelineDurationMs,
                        )
                    }
                    else -> c
                }
            }.sortedBy { it.timelineStartMs }

            track.copy(clips = updatedClips)
        }
    }

    // ----------------------------------------------------------------- Apply All & Random All
    fun applyTransitionToAll(timeline: Timeline, transition: Transition): Timeline =
        timeline.copy(
            tracks = timeline.tracks.map { track ->
                if (track.type == TrackType.VIDEO) {
                    track.copy(clips = track.clips.map { it.copy(transitionToNext = transition) })
                } else track
            }
        )

    fun applyRandomTransitions(timeline: Timeline, candidates: List<TransitionType>, durationMs: Long = 500L): Timeline {
        if (candidates.isEmpty()) return timeline
        return timeline.copy(
            tracks = timeline.tracks.map { track ->
                if (track.type == TrackType.VIDEO) {
                    track.copy(
                        clips = track.clips.map { clip ->
                            clip.copy(transitionToNext = Transition(candidates.random(), durationMs))
                        }
                    )
                } else track
            }
        )
    }

    // ----------------------------------------------------------------- Audio / Properties
    fun detachAudio(timeline: Timeline, clipId: String): Timeline {
        val located = findClip(timeline, clipId) ?: return timeline
        val (_, clip) = located
        if (clip.type != ClipType.VIDEO || clip.mediaUri == null) return timeline

        val audioClip = Clip(
            id = newClipId(),
            type = ClipType.AUDIO,
            mediaUri = clip.mediaUri,
            timelineStartMs = clip.timelineStartMs,
            timelineEndMs = clip.timelineEndMs,
            sourceInMs = clip.sourceInMs,
            sourceOutMs = clip.sourceOutMs,
            speed = clip.speed,
            isReversed = clip.isReversed,
            volume = if (clip.volume <= 0f) 1f else clip.volume,
        )

        val muted = updateClip(timeline, clipId) { it.copy(volume = 0f) }
        val audioTrack = muted.tracks.firstOrNull { it.type == TrackType.AUDIO }
        return if (audioTrack != null) {
            mapTrack(muted, audioTrack.id) { t ->
                t.copy(clips = (t.clips + audioClip).sortedBy { it.timelineStartMs })
            }
        } else {
            val (withTrack, trackId) = addTrack(muted, TrackType.AUDIO)
            mapTrack(withTrack, trackId) { t -> t.copy(clips = t.clips + audioClip) }
        }
    }

    fun updateClip(timeline: Timeline, clipId: String, transform: (Clip) -> Clip): Timeline =
        timeline.copy(
            tracks = timeline.tracks.map { track ->
                if (track.clips.none { it.id == clipId }) {
                    track
                } else {
                    track.copy(clips = track.clips.map { if (it.id == clipId) transform(it) else it })
                }
            },
        )

    fun setSpeed(timeline: Timeline, clipId: String, speed: Float): Timeline {
        val s = speed.coerceIn(SpeedPresets.MIN, SpeedPresets.MAX)
        return updateClip(timeline, clipId) { c ->
            if (!c.hasSourceWindow) {
                c.copy(speed = s)
            } else {
                val newDuration = (c.sourceDurationMs / s).toLong().coerceAtLeast(MIN_CLIP_DURATION_MS)
                c.copy(speed = s, timelineEndMs = c.timelineStartMs + newDuration)
            }
        }
    }

    fun setReversed(timeline: Timeline, clipId: String, reversed: Boolean): Timeline =
        updateClip(timeline, clipId) { it.copy(isReversed = reversed) }

    fun setVolume(timeline: Timeline, clipId: String, volume: Float): Timeline =
        updateClip(timeline, clipId) { it.copy(volume = volume.coerceIn(0f, 2f)) }

    fun setRotation(timeline: Timeline, clipId: String, degrees: Int): Timeline =
        updateClip(timeline, clipId) { it.copy(rotationDegrees = ((degrees % 360) + 360) % 360) }

    fun setOpacity(timeline: Timeline, clipId: String, opacity: Float): Timeline =
        updateClip(timeline, clipId) { it.copy(opacity = opacity.coerceIn(0f, 1f)) }

    fun setAdjustments(timeline: Timeline, clipId: String, adjustments: ColorAdjustments): Timeline =
        updateClip(timeline, clipId) { it.copy(adjustments = adjustments) }

    fun setFilter(timeline: Timeline, clipId: String, filter: Filter?): Timeline =
        updateClip(timeline, clipId) { it.copy(filter = filter) }

    fun setTransform(timeline: Timeline, clipId: String, transform: Transform): Timeline =
        updateClip(timeline, clipId) { it.copy(transform = transform) }

    fun setAudioFade(timeline: Timeline, clipId: String, fade: AudioFade): Timeline =
        updateClip(timeline, clipId) { it.copy(audioFade = fade) }

    fun setTransition(timeline: Timeline, clipId: String, transition: Transition?): Timeline =
        updateClip(timeline, clipId) { it.copy(transitionToNext = transition) }

    fun addEffect(timeline: Timeline, clipId: String, effect: VisualEffect): Timeline =
        updateClip(timeline, clipId) { it.copy(effects = it.effects + effect) }

    fun removeEffect(timeline: Timeline, clipId: String, index: Int): Timeline =
        updateClip(timeline, clipId) { c ->
            if (index in c.effects.indices) c.copy(effects = c.effects.filterIndexed { i, _ -> i != index }) else c
        }

    fun findClip(timeline: Timeline, clipId: String): Pair<Track, Clip>? {
        for (track in timeline.tracks) {
            val clip = track.clips.firstOrNull { it.id == clipId }
            if (clip != null) return track to clip
        }
        return null
    }

    private fun newClipId(): String = "clp_${UUID.randomUUID().toString().take(8)}"

    private fun mapTrack(timeline: Timeline, trackId: String, transform: (Track) -> Track): Timeline =
        timeline.copy(tracks = timeline.tracks.map { if (it.id == trackId) transform(it) else it })

    private fun sourceAt(clip: Clip, offsetTimelineMs: Long): Long {
        val sourceOffset = (offsetTimelineMs * clip.speed).toLong()
        return if (clip.isReversed) clip.sourceOutMs - sourceOffset else clip.sourceInMs + sourceOffset
    }

    private fun headRoom(clip: Clip): Long {
        if (!clip.hasSourceWindow) return clip.timelineStartMs
        val availableSource = if (clip.isReversed) Long.MAX_VALUE else clip.sourceInMs
        return (availableSource / clip.speed).toLong().coerceAtMost(clip.timelineStartMs)
    }

    private fun tailRoom(clip: Clip): Long {
        if (!clip.hasSourceWindow) return Long.MAX_VALUE / 2
        return (clip.sourceDurationMs / clip.speed).toLong()
    }

    private fun Clip.shiftedBy(deltaMs: Long): Clip =
        copy(timelineStartMs = timelineStartMs + deltaMs, timelineEndMs = timelineEndMs + deltaMs)

    private val Clip.hasSourceWindow: Boolean
        get() = type == ClipType.VIDEO || type == ClipType.AUDIO
}
