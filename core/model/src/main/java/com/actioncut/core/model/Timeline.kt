package com.actioncut.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrackType {
    VIDEO,
    OVERLAY,
    TEXT,
    AUDIO,
}

@Serializable
data class Track(
    val id: String,
    val type: TrackType,
    val clips: List<Clip> = emptyList(),
    val index: Int = 0,
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
) {
    val durationMs: Long get() = clips.maxOfOrNull { it.timelineEndMs } ?: 0L
    val isEmpty: Boolean get() = clips.isEmpty()
    fun clipAt(timeMs: Long): Clip? =
        clips.firstOrNull { timeMs in it.timelineStartMs until it.timelineEndMs }
}

@Serializable
data class Timeline(
    val tracks: List<Track> = emptyList(),
    val playheadMs: Long = 0L,
    val canvas: CanvasSettings = CanvasSettings(), // 🌟 TPL தகவல்களை டேட்டாபேஸில் நிரந்தரமாகச் சேமிக்க
) {
    val durationMs: Long get() = tracks.maxOfOrNull { it.durationMs } ?: 0L
    val videoTracks: List<Track> get() = tracks.filter { it.type == TrackType.VIDEO }
    val audioTracks: List<Track> get() = tracks.filter { it.type == TrackType.AUDIO }
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
    val allClips: List<Clip> get() = tracks.flatMap { it.clips }

    companion object {
        fun empty(): Timeline = Timeline()
    }
}
