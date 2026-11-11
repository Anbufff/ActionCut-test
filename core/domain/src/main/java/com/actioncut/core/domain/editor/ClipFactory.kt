package com.actioncut.core.domain.editor

import com.actioncut.core.common.id.Ids
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.Keyframe
import com.actioncut.core.model.MediaItem
import com.actioncut.core.model.MediaType
import com.actioncut.core.model.TextAlignment
import com.actioncut.core.model.TextProperties
import com.actioncut.core.model.Transform
import com.actioncut.core.model.Transition
import com.actioncut.core.model.TransitionType
import com.actioncut.core.model.VisualEffect
import com.actioncut.core.model.VisualEffectType

/** Builds [Clip]s from raw media / user actions with sensible defaults. */
object ClipFactory {

    const val DEFAULT_IMAGE_DURATION_MS = 3_000L
    const val DEFAULT_PICTURE_DURATION_MS = 8_000L
    const val DEFAULT_TEXT_DURATION_MS = 2_000L

    fun fromMedia(media: MediaItem, timelineStartMs: Long): Clip {
        val type = when (media.type) {
            MediaType.VIDEO -> ClipType.VIDEO
            MediaType.IMAGE -> ClipType.IMAGE
            MediaType.AUDIO -> ClipType.AUDIO
        }
        val hasSource = media.type == MediaType.VIDEO || media.type == MediaType.AUDIO
        val duration = if (media.type == MediaType.IMAGE) {
            DEFAULT_PICTURE_DURATION_MS
        } else {
            media.durationMs.coerceAtLeast(TimelineEditor.MIN_CLIP_DURATION_MS)
        }

        // Fty Editor Group 5 Zoom In-Out எஃபெக்ட்
        val imageEffects = if (media.type == MediaType.IMAGE) {
            listOf(VisualEffect(VisualEffectType.ZOOM_PULSE, intensity = 1.0f))
        } else {
            emptyList()
        }

        // முதல் பாதியில் 1.0x -> 1.10x, இரண்டாம் பாதியில் 1.10x -> 1.0x
        val imageKeyframes = if (media.type == MediaType.IMAGE) {
            listOf(
                Keyframe(timeMs = 0L, scale = 1.0f),
                Keyframe(timeMs = duration / 2, scale = 1.10f),
                Keyframe(timeMs = duration, scale = 1.0f),
            )
        } else {
            emptyList()
        }

        val defaultTransition = if (media.type == MediaType.IMAGE) {
            Transition(TransitionType.SEAMLESS_MERGE, 0L)
        } else {
            null
        }

        return Clip(
            id = Ids.clip(),
            type = type,
            mediaUri = media.uri,
            timelineStartMs = timelineStartMs,
            timelineEndMs = timelineStartMs + duration,
            sourceInMs = 0L,
            sourceOutMs = if (hasSource) media.durationMs else 0L,
            effects = imageEffects,
            keyframes = imageKeyframes,
            transitionToNext = defaultTransition,
        )
    }

    fun text(content: String, timelineStartMs: Long): Clip = Clip(
        id = Ids.clip(),
        type = ClipType.TEXT,
        timelineStartMs = timelineStartMs,
        timelineEndMs = timelineStartMs + DEFAULT_TEXT_DURATION_MS,
        text = TextProperties(text = content, alignment = TextAlignment.CENTER),
    )

    fun sticker(uri: String, timelineStartMs: Long, durationMs: Long = DEFAULT_IMAGE_DURATION_MS): Clip =
        Clip(
            id = Ids.clip(),
            type = ClipType.STICKER,
            mediaUri = uri,
            timelineStartMs = timelineStartMs,
            timelineEndMs = timelineStartMs + durationMs,
        )

    fun scoreCard(uri: String, timelineStartMs: Long, durationMs: Long, transform: Transform): Clip =
        Clip(
            id = Ids.clip(),
            type = ClipType.STICKER,
            mediaUri = uri,
            timelineStartMs = timelineStartMs,
            timelineEndMs = timelineStartMs + durationMs,
            transform = transform,
        )

    fun emojiSticker(emoji: String, timelineStartMs: Long, durationMs: Long = DEFAULT_IMAGE_DURATION_MS): Clip =
        Clip(
            id = Ids.clip(),
            type = ClipType.STICKER,
            timelineStartMs = timelineStartMs,
            timelineEndMs = timelineStartMs + durationMs,
            text = TextProperties(text = emoji, fontSizeSp = 64f),
            transform = Transform(),
        )
}
