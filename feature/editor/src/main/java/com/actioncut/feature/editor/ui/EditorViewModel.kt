fun addScorecardClip(data: ScorecardClipData) {
    val durationMs = 120_000L
    
    // மூல ஸ்கிரீன்ஷாட்டுக்கு பதிலாக தயாரிக்கப்பட்ட 16:9 ஸ்கோர்கார்டு இமேஜை அமைத்தல்
    val cardFile = java.io.File(context.cacheDir, "card_${data.id}.png")
    val cardUri = if (cardFile.exists()) {
        android.net.Uri.fromFile(cardFile).toString()
    } else {
        data.sourceImageUri
    }

    val clip = Clip(
        id = data.id,
        type = ClipType.IMAGE,
        mediaUri = cardUri, // <--- இப்போது பழைய படம் பின்னணியில் தெரியாது!
        timelineStartMs = _uiState.value.playheadMs,
        timelineEndMs = _uiState.value.playheadMs + durationMs,
        scorecard = data
    )
    val track = _uiState.value.timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
    mutate(structural = true) { timeline ->
        if (track != null) {
            TimelineEditor.appendClip(timeline, track.id, clip)
        } else {
            val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.VIDEO)
            TimelineEditor.appendClip(withTrack, trackId, clip)
        }
    }
    _uiState.update { it.copy(selectedClipId = clip.id) }
}
