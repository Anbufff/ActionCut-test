package com.actioncut.feature.editor.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actioncut.core.domain.editor.ClipFactory
import com.actioncut.core.domain.editor.TimelineEditor
import com.actioncut.core.domain.usecase.GetProjectUseCase
import com.actioncut.core.domain.usecase.ResolveLibraryAudioUseCase
import com.actioncut.core.domain.usecase.ResolveMediaUseCase
import com.actioncut.core.domain.usecase.SaveProjectUseCase
import com.actioncut.core.media.audio.VoiceRecorder
import com.actioncut.core.media.player.PlayerController
import com.actioncut.core.media.waveform.WaveformExtractor
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.CanvasSettings
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ColorAdjustments
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.Filter
import com.actioncut.core.model.FitMode
import com.actioncut.core.model.Keyframe
import com.actioncut.core.model.MediaItem
import com.actioncut.core.model.MediaType
import com.actioncut.core.model.Project
import com.actioncut.core.model.ScorecardClipData
import com.actioncut.core.model.ScorecardKeyframe
import com.actioncut.core.model.SpeedRamp
import com.actioncut.core.model.TextProperties
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.Transform
import com.actioncut.core.model.Transition
import com.actioncut.core.model.TransitionCategory
import com.actioncut.core.model.TransitionType
import com.actioncut.core.model.VisualEffect
import com.actioncut.core.model.VisualEffectType
import com.actioncut.feature.editor.ui.newsalert.NewsBannerRenderer
import com.actioncut.feature.editor.ui.scorecard.ScorecardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class EditorViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    savedStateHandle: SavedStateHandle,
    private val getProject: GetProjectUseCase,
    private val saveProject: SaveProjectUseCase,
    private val resolveMedia: ResolveMediaUseCase,
    private val resolveLibraryAudio: ResolveLibraryAudioUseCase,
    private val waveformExtractor: WaveformExtractor,
    private val voiceRecorder: VoiceRecorder,
    val playerController: PlayerController,
) : ViewModel() {

    private val projectId: String = savedStateHandle[ARG_PROJECT_ID] ?: ""

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var loadedProject: Project? = null
    private val undoStack = ArrayDeque<Timeline>()
    private val redoStack = ArrayDeque<Timeline>()
    private var saveJob: Job? = null

    init {
        observePlayback()
        loadProject()
    }

    private fun loadProject() {
        viewModelScope.launch {
            val project = getProject(projectId)
            if (project == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val initialTimeline = project.timeline.copy(
                tracks = project.timeline.tracks.map { track ->
                    if (track.type == TrackType.VIDEO) {
                        track.copy(
                            clips = track.clips.map { clip ->
                                if (clip.type == ClipType.IMAGE) {
                                    val hasZoomEffect = clip.effects.any { it.type == VisualEffectType.ZOOM_PULSE }
                                    val effs = if (!hasZoomEffect) {
                                        clip.effects + VisualEffect(VisualEffectType.ZOOM_PULSE, 1f)
                                    } else clip.effects

                                    val kfs = if (clip.keyframes.isEmpty()) {
                                        listOf(
                                            Keyframe(0L, scale = 1.0f),
                                            Keyframe(clip.timelineDurationMs / 2, scale = 1.10f),
                                            Keyframe(clip.timelineDurationMs, scale = 1.0f),
                                        )
                                    } else clip.keyframes

                                    val tr = clip.transitionToNext ?: Transition(TransitionType.SEAMLESS_MERGE, 0L)
                                    clip.copy(effects = effs, keyframes = kfs, transitionToNext = tr)
                                } else clip
                            }
                        )
                    } else track
                }
            )

            val updatedProject = project.copy(timeline = initialTimeline)
            loadedProject = updatedProject

            _uiState.update {
                it.copy(
                    isLoading = false,
                    projectName = updatedProject.name,
                    timeline = initialTimeline,
                    aspectRatio = updatedProject.aspectRatio,
                    canvas = updatedProject.canvas,
                    selectedClipId = initialTimeline.allClips.firstOrNull()?.id,
                    playheadMs = initialTimeline.playheadMs,
                )
            }
            playerController.setTimeline(initialTimeline, preservePositionMs = initialTimeline.playheadMs)
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            playerController.state.collect { playback ->
                _uiState.update { state ->
                    state.copy(
                        isPlaying = playback.isPlaying,
                        playheadMs = playback.positionMs.coerceIn(0, state.durationMs),
                    )
                }
            }
        }
        viewModelScope.launch {
            playerController.positionFlow().collect { positionMs ->
                if (_uiState.value.isPlaying) {
                    _uiState.update { it.copy(playheadMs = positionMs.coerceIn(0, it.durationMs)) }
                }
            }
        }
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, _uiState.value.durationMs)
        playerController.seekTo(clamped)
        _uiState.update { it.copy(playheadMs = clamped) }
    }

    fun selectClip(clipId: String?) {
        _uiState.update { state ->
            val clip = clipId?.let { id -> state.timeline.allClips.firstOrNull { it.id == id } }
            val newPlayhead = if (clip != null && !clip.contains(state.playheadMs)) {
                clip.timelineStartMs
            } else {
                state.playheadMs
            }
            state.copy(selectedClipId = clipId, playheadMs = newPlayhead)
        }
    }

    fun setAspectRatio(ratio: AspectRatio) {
        if (ratio != AspectRatio.RATIO_16_9 && ratio != AspectRatio.RATIO_4_5) return
        _uiState.update { it.copy(aspectRatio = ratio) }
        loadedProject = loadedProject?.copy(aspectRatio = ratio)
        scheduleSave()
    }

    fun renameProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(projectName = trimmed) }
        loadedProject = loadedProject?.copy(name = trimmed)
        scheduleSave()
    }

    suspend fun loadWaveform(uri: String): FloatArray = waveformExtractor.amplitudes(uri)

    fun setActiveTool(tool: EditorTool?) {
        when (tool) {
            EditorTool.SPLIT -> { splitAtPlayhead(); clearTool() }
            EditorTool.DELETE -> { deleteSelected(); clearTool() }
            EditorTool.REVERSE -> { toggleReverse(); clearTool() }
            EditorTool.ROTATE -> { rotateSelected(); clearTool() }
            EditorTool.MUTE -> { toggleMuteSelected(); clearTool() }
            EditorTool.EXTRACT_AUDIO -> { detachAudioFromSelected(); clearTool() }
            EditorTool.AUDIO, EditorTool.PIP, EditorTool.CRIC, EditorTool.CARD_ADD, EditorTool.PLAYER, EditorTool.NEWS_ALERT -> clearTool()
            else -> _uiState.update { it.copy(activeTool = tool) }
        }
    }

    private fun clearTool() = _uiState.update { it.copy(activeTool = null) }

    fun setZoom(pxPerSecond: Float) = _uiState.update {
        it.copy(
            pxPerSecond = pxPerSecond.coerceIn(
                EditorUiState.MIN_PX_PER_SECOND,
                EditorUiState.MAX_PX_PER_SECOND,
            ),
        )
    }

    private suspend fun resolveAnyMedia(uriStr: String): MediaItem? {
        val parsedUri = Uri.parse(uriStr)
        val mimeType = runCatching { context.contentResolver.getType(parsedUri) }.getOrNull()
            ?: MimeTypeMap.getFileExtensionFromUrl(uriStr)?.let { ext ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            }

        val isImage = mimeType?.startsWith("image/") == true ||
            uriStr.endsWith(".jpg", true) || uriStr.endsWith(".jpeg", true) ||
            uriStr.endsWith(".png", true) || uriStr.endsWith(".webp", true)

        if (isImage) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.contentResolver.openInputStream(parsedUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            val title = parsedUri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?') ?: "Image"
            return MediaItem(
                id = uriStr.hashCode().toString(),
                uri = uriStr,
                type = MediaType.IMAGE,
                displayName = title,
                durationMs = 8_000L,
                width = bounds.outWidth.coerceAtLeast(0),
                height = bounds.outHeight.coerceAtLeast(0),
            )
        }

        val videoItem = resolveMedia(uriStr)
        if (videoItem != null) return videoItem

        val fallbackBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val isDecodable = runCatching {
            context.contentResolver.openInputStream(parsedUri)?.use {
                BitmapFactory.decodeStream(it, null, fallbackBounds)
            }
            fallbackBounds.outWidth > 0 && fallbackBounds.outHeight > 0
        }.getOrDefault(false)

        if (isDecodable) {
            val title = parsedUri.lastPathSegment?.substringAfterLast('/')?.substringBefore('?') ?: "Image"
            return MediaItem(
                id = uriStr.hashCode().toString(),
                uri = uriStr,
                type = MediaType.IMAGE,
                displayName = title,
                durationMs = 8_000L,
                width = fallbackBounds.outWidth,
                height = fallbackBounds.outHeight,
            )
        }

        return null
    }

    fun appendMediaList(uris: List<String>) {
        viewModelScope.launch {
            val mediaItems = uris.mapNotNull { resolveAnyMedia(it) }
            if (mediaItems.isEmpty()) return@launch

            val videoTrack = _uiState.value.timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
            var currentEnd = videoTrack?.durationMs ?: 0L
            val newClips = mediaItems.map { item ->
                val clip = ClipFactory.fromMedia(item, currentEnd)
                currentEnd = clip.timelineEndMs
                clip
            }

            mutate(structural = true) { timeline ->
                if (videoTrack != null) {
                    var updated = timeline
                    newClips.forEach { c ->
                        updated = TimelineEditor.appendClip(updated, videoTrack.id, c)
                    }
                    updated
                } else {
                    val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.VIDEO)
                    var updated = withTrack
                    newClips.forEach { c ->
                        updated = TimelineEditor.appendClip(updated, trackId, c)
                    }
                    updated
                }
            }
            if (newClips.isNotEmpty()) {
                _uiState.update { it.copy(selectedClipId = newClips.last().id) }
            }
        }
    }

    // ------------------------------------------------------------------ Fty News Alert Banner
    fun addNewsBannerOverlay(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val bannerUri = NewsBannerRenderer.createBannerBitmap(context, text)
            val currentPlayhead = _uiState.value.playheadMs
            val totalTimelineMs = _uiState.value.timeline.durationMs

            val bannerEndMs = if (totalTimelineMs > currentPlayhead) totalTimelineMs else currentPlayhead + 30_000L

            val clip = Clip(
                id = "banner_${System.currentTimeMillis()}",
                type = ClipType.STICKER,
                mediaUri = bannerUri,
                timelineStartMs = currentPlayhead,
                timelineEndMs = bannerEndMs,
                transform = Transform(offsetX = 0f, offsetY = 0f, scale = 1.0f),
            )
            val track = _uiState.value.timeline.tracks
                .firstOrNull { it.type == TrackType.OVERLAY }
            mutate(structural = false) { timeline ->
                if (track != null) {
                    TimelineEditor.addClip(timeline, track.id, clip)
                } else {
                    val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.OVERLAY)
                    TimelineEditor.addClip(withTrack, trackId, clip)
                }
            }
            _uiState.update { it.copy(selectedClipId = clip.id) }
        }
    }

    // ------------------------------------------------------------------ Fty PiP (Picture-in-Picture)
    /** Fty PiP: வீடியோ அல்லது போட்டோ இரண்டையுமே PiP-ஆகச் சேர்க்கும் செயல்பாடு */
    fun addPipAtPlayhead(uri: String) {
        viewModelScope.launch {
            val media = resolveAnyMedia(uri) ?: return@launch
            val isVideo = media.type == MediaType.VIDEO
            val base = ClipFactory.fromMedia(
                if (isVideo) media.copy(type = MediaType.VIDEO) else media.copy(type = MediaType.IMAGE),
                _uiState.value.playheadMs,
            )
            val clip = base.copy(
                transform = Transform(offsetX = 0.35f, offsetY = -0.35f, scale = 0.45f),
                keyframes = listOf(
                    Keyframe(timeMs = 0L, offsetX = 0.35f, offsetY = -0.35f, scale = 0.45f)
                ),
            )
            val track = _uiState.value.timeline.tracks
                .firstOrNull { it.type == TrackType.OVERLAY }
            mutate(structural = true) { timeline ->
                if (track != null) {
                    TimelineEditor.addClip(timeline, track.id, clip)
                } else {
                    val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.OVERLAY)
                    TimelineEditor.addClip(withTrack, trackId, clip)
                }
            }
            _uiState.update { it.copy(selectedClipId = clip.id) }
        }
    }

    /** Fty PiP: CUTOUT பட்டனை அழுத்தும்போது வட்ட வடிவ மாஸ்க் / Cutout அமைத்தல் */
    fun togglePipCutout() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c ->
                val existingCrop = c.crop
                if (existingCrop != null && !existingCrop.isFull) {
                    c.copy(crop = null)
                } else {
                    c.copy(crop = CropRect(0.12f, 0.08f, 0.88f, 0.92f))
                }
            }
        }
    }

    // ------------------------------------------------------------------ Dynamic Scorecard
    fun addScorecardClip(data: ScorecardClipData) {
        val durationMs = 120_000L
        val cardFile = java.io.File(context.cacheDir, "card_${data.id}.png")
        val cardUri = if (cardFile.exists()) {
            android.net.Uri.fromFile(cardFile).toString()
        } else {
            data.sourceImageUri
        }

        val clip = Clip(
            id = data.id,
            type = ClipType.IMAGE,
            mediaUri = cardUri,
            timelineStartMs = _uiState.value.playheadMs,
            timelineEndMs = _uiState.value.playheadMs + durationMs,
            scorecard = data,
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

    fun onScorecardRowTapped(clipId: String, rowIndex: Int) {
        val clip = currentClip(clipId) ?: return
        val sc = clip.scorecard ?: return
        val timeInClip = (_uiState.value.playheadMs - clip.timelineStartMs).coerceAtLeast(0L)

        val existingKfs = sc.keyframes.toMutableList()
        val nearIdx = existingKfs.indexOfFirst { abs(it.timeMs - timeInClip) <= 300L }

        if (nearIdx >= 0) {
            existingKfs[nearIdx] = ScorecardKeyframe(timeInClip, rowIndex)
        } else {
            existingKfs.add(ScorecardKeyframe(timeInClip, rowIndex))
            existingKfs.sortBy { it.timeMs }
        }

        val updatedSc = sc.copy(keyframes = existingKfs)
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, clipId) { it.copy(scorecard = updatedSc) }
        }
    }

    fun toggleScorecardKeyframe() = withSelected { clipId ->
        val clip = currentClip(clipId) ?: return@withSelected
        val sc = clip.scorecard ?: return@withSelected
        val timeInClip = (_uiState.value.playheadMs - clip.timelineStartMs).coerceAtLeast(0L)

        val existingKfs = sc.keyframes.toMutableList()
        val nearIdx = existingKfs.indexOfFirst { abs(it.timeMs - timeInClip) <= 300L }

        if (nearIdx >= 0 && existingKfs.size > 1) {
            existingKfs.removeAt(nearIdx)
        } else if (nearIdx < 0) {
            val activeRow = ScorecardManager.getActiveRowIndex(sc, timeInClip)
            existingKfs.add(ScorecardKeyframe(timeInClip, activeRow))
            existingKfs.sortBy { it.timeMs }
        }

        val updatedSc = sc.copy(keyframes = existingKfs)
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, clipId) { it.copy(scorecard = updatedSc) }
        }
    }

    fun cycleScorecardTheme() = withSelected { clipId ->
        val clip = currentClip(clipId) ?: return@withSelected
        val sc = clip.scorecard ?: return@withSelected
        val nextTheme = (sc.themeIdx + 1) % 4
        val updatedSc = sc.copy(themeIdx = nextTheme)
        ScorecardManager.clearCache(clipId)
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, clipId) { it.copy(scorecard = updatedSc) }
        }
    }

    fun setScorecardDuration(durationMs: Long) = withSelected { clipId ->
        val clip = currentClip(clipId) ?: return@withSelected
        val clamped = durationMs.coerceIn(500L, 180_000L)
        mutate(structural = true) { timeline ->
            TimelineEditor.updateClip(timeline, clipId) {
                it.copy(timelineEndMs = it.timelineStartMs + clamped)
            }
        }
    }

    fun splitAtPlayhead() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: clipAtPlayhead()?.id ?: return
        mutate(structural = true) { TimelineEditor.splitClip(it, clipId, state.playheadMs) }
    }

    fun deleteSelected() {
        val clipId = _uiState.value.selectedClipId ?: return
        mutate(structural = true) { TimelineEditor.removeClip(it, clipId, ripple = true) }
        _uiState.update { it.copy(selectedClipId = null) }
    }

    fun duplicateSelected() = withSelected { id ->
        val (track, clip) = TimelineEditor.findClip(_uiState.value.timeline, id) ?: return@withSelected
        val copy = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = clip.timelineEndMs,
            timelineEndMs = clip.timelineEndMs + clip.timelineDurationMs,
        )
        mutate(structural = true) { TimelineEditor.insertClip(it, track.id, copy) }
        _uiState.update { it.copy(selectedClipId = copy.id) }
    }

    fun trimStart(clipId: String, newStartMs: Long) {
        val clamped = newStartMs.coerceAtLeast(0)
        mutate(structural = false) { TimelineEditor.trimClipStart(it, clipId, clamped) }
        _uiState.update { it.copy(playheadMs = clamped) }
    }

    fun trimEnd(clipId: String, newEndMs: Long) {
        val clamped = newEndMs.coerceAtLeast(0)
        mutate(structural = false) { TimelineEditor.trimClipEnd(it, clipId, clamped) }
        _uiState.update { it.copy(playheadMs = clamped) }
    }

    fun trimRightAtPlayhead() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val clip = currentClip(clipId) ?: return
        val playhead = state.playheadMs
        if (playhead > clip.timelineStartMs && playhead < clip.timelineEndMs) {
            trimEnd(clipId, playhead)
            commitTrim()
        }
    }

    fun commitTrim() {
        val currentPos = _uiState.value.playheadMs
        playerController.setTimeline(_uiState.value.timeline, preservePositionMs = currentPos)
        playerController.seekTo(currentPos)
    }

    fun moveClip(clipId: String, newStartMs: Long) {
        val track = TimelineEditor.findClip(_uiState.value.timeline, clipId)?.first ?: return
        val clampedStart = newStartMs.coerceAtLeast(0)
        mutate(structural = false) {
            TimelineEditor.moveClip(it, clipId, track.id, clampedStart)
        }
        _uiState.update { it.copy(playheadMs = clampedStart) }
    }

    fun commitArrangement() {
        val currentPos = _uiState.value.playheadMs
        playerController.setTimeline(_uiState.value.timeline, preservePositionMs = currentPos)
        playerController.seekTo(currentPos)
    }

    fun detachAudioFromSelected() = withSelected { id ->
        mutate(structural = true) { TimelineEditor.detachAudio(it, id) }
    }

    fun setSpeed(speed: Float) = withSelected { id ->
        mutate(structural = true) { TimelineEditor.setSpeed(it, id, speed) }
    }

    fun toggleReverse() = withSelected { id ->
        val current = currentClip(id)?.isReversed ?: false
        mutate(structural = true) { TimelineEditor.setReversed(it, id, !current) }
    }

    fun addTextAtPlayhead(text: String) {
        val state = _uiState.value
        val track = state.timeline.tracks.firstOrNull { it.type == TrackType.TEXT }
        val clip = ClipFactory.text(text, state.playheadMs)
        mutate(structural = true) { timeline ->
            if (track != null) {
                TimelineEditor.insertClip(timeline, track.id, clip)
            } else {
                val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.TEXT)
                TimelineEditor.insertClip(withTrack, trackId, clip)
            }
        }
    }

    fun addAudioAtPlayhead(uri: String) {
        viewModelScope.launch {
            val media = resolveAnyMedia(uri) ?: return@launch
            val audioMedia = media.copy(type = MediaType.AUDIO)
            val clip = ClipFactory.fromMedia(audioMedia, _uiState.value.playheadMs)
            val track = _uiState.value.timeline.tracks
                .firstOrNull { it.type == TrackType.AUDIO }
            mutate(structural = true) { timeline ->
                if (track != null) {
                    TimelineEditor.insertClip(timeline, track.id, clip)
                } else {
                    val (withTrack, trackId) =
                        TimelineEditor.addTrack(timeline, TrackType.AUDIO)
                    TimelineEditor.insertClip(withTrack, trackId, clip)
                }
            }
            _uiState.update { it.copy(selectedClipId = clip.id) }
        }
    }

    fun toggleMuteSelected() = withSelected { id ->
        val muted = (currentClip(id)?.volume ?: 1f) == 0f
        mutate(structural = false) { TimelineEditor.setVolume(it, id, if (muted) 1f else 0f) }
    }

    fun addLibraryTrackAtPlayhead(track: com.actioncut.core.model.LibraryTrack) {
        viewModelScope.launch {
            val uri = resolveLibraryAudio(track.rawResName) ?: return@launch
            addAudioAtPlayhead(uri)
        }
    }

    fun startVoiceover() {
        if (voiceRecorder.start()) {
            _uiState.update { it.copy(isRecordingVoiceover = true) }
        }
    }

    fun stopVoiceover() {
        val uri = voiceRecorder.stop()
        _uiState.update { it.copy(isRecordingVoiceover = false) }
        if (uri != null) addAudioAtPlayhead(uri)
    }

    fun addStickerAtPlayhead(emoji: String) {
        val clip = ClipFactory.emojiSticker(emoji, _uiState.value.playheadMs)
        val track = _uiState.value.timeline.tracks
            .firstOrNull { it.type == TrackType.OVERLAY }
        mutate(structural = false) { timeline ->
            if (track != null) {
                TimelineEditor.addClip(timeline, track.id, clip)
            } else {
                val (withTrack, trackId) =
                    TimelineEditor.addTrack(timeline, TrackType.OVERLAY)
                TimelineEditor.addClip(withTrack, trackId, clip)
            }
        }
        _uiState.update { it.copy(selectedClipId = clip.id) }
    }

    fun setOverlayPosition(clipId: String, offsetX: Float, offsetY: Float) {
        val clip = currentClip(clipId) ?: return
        mutate(structural = false) {
            TimelineEditor.setTransform(it, clipId, clip.transform.copy(offsetX = offsetX, offsetY = offsetY))
        }
    }

    fun setOverlayScale(clipId: String, scale: Float) {
        val clip = currentClip(clipId) ?: return
        mutate(structural = false) {
            TimelineEditor.setTransform(it, clipId, clip.transform.copy(scale = scale.coerceIn(0.1f, 4.0f)))
        }
    }

    fun setFitMode(mode: FitMode) = updateCanvas { it.copy(fitMode = mode) }

    fun setBackgroundColor(argb: Int) = updateCanvas { it.copy(backgroundColorArgb = argb) }

    private fun updateCanvas(transform: (CanvasSettings) -> CanvasSettings) {
        val newCanvas = transform(_uiState.value.canvas)
        _uiState.update { it.copy(canvas = newCanvas) }
        loadedProject = loadedProject?.copy(canvas = newCanvas)
        scheduleSave()
    }

    fun setCrop(clipId: String, crop: com.actioncut.core.model.CropRect) {
        mutate(structural = false) {
            TimelineEditor.updateClip(it, clipId) { c -> c.copy(crop = crop) }
        }
    }

    fun addKeyframeAtPlayhead() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val timeInClip = (_uiState.value.playheadMs - clip.timelineStartMs).coerceAtLeast(0L)
        val kf = com.actioncut.core.model.Keyframe(
            timeMs = timeInClip,
            offsetX = clip.transform.offsetX,
            offsetY = clip.transform.offsetY,
            scale = clip.transform.scale,
            rotationDegrees = clip.transform.rotationDegrees,
            opacity = clip.opacity,
        )
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c ->
                val others = c.keyframes.filter { abs(it.timeMs - timeInClip) > 50L }
                c.copy(keyframes = (others + kf).sortedBy { it.timeMs })
            }
        }
    }

    fun clearKeyframes() = withSelected { id ->
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c -> c.copy(keyframes = emptyList()) }
        }
    }

    fun setSpeedRamp(ramp: SpeedRamp) = withSelected { id ->
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c -> c.copy(speedRamp = ramp) }
        }
    }

    fun setVolume(volume: Float) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setVolume(it, id, volume) }
    }

    fun rotateSelected() = withSelected { id ->
        val current = currentClip(id)?.rotationDegrees ?: 0
        mutate(structural = false) { TimelineEditor.setRotation(it, id, current + 90) }
    }

    fun setFilter(filter: Filter?) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setFilter(it, id, filter) }
    }

    fun setAdjustments(adjustments: ColorAdjustments) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setAdjustments(it, id, adjustments) }
    }

    fun setTextProperties(text: TextProperties) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.updateClip(it, id) { clip -> clip.copy(text = text) } }
    }

    fun setTransition(transition: Transition?) = withSelected { id ->
        mutate(structural = true) { TimelineEditor.setTransition(it, id, transition) }
    }

    fun applyTransitionToAll(transition: Transition) {
        mutate(structural = true) { TimelineEditor.applyTransitionToAll(it, transition) }
    }

    fun applyRandomTransitions(category: TransitionCategory) {
        val candidates = if (category == TransitionCategory.ALL) {
            TransitionType.entries.filter { it != TransitionType.NONE && it != TransitionType.SEAMLESS_MERGE }
        } else {
            TransitionType.entries.filter { it.category == category }
        }
        mutate(structural = true) { TimelineEditor.applyRandomTransitions(it, candidates) }
    }

    fun addEffect(effect: VisualEffect) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.addEffect(it, id, effect) }
    }

    fun toggleEffect(type: VisualEffectType) = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val existingIndex = clip.effects.indexOfFirst { it.type == type }
        mutate(structural = false) { timeline ->
            if (existingIndex >= 0) {
                TimelineEditor.removeEffect(timeline, id, existingIndex)
            } else {
                TimelineEditor.addEffect(timeline, id, VisualEffect(type))
            }
        }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.timeline)
        val previous = undoStack.removeLast()
        applyTimeline(previous, structural = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_uiState.value.timeline)
        val next = redoStack.removeLast()
        applyTimeline(next, structural = true)
    }

    private inline fun withSelected(action: (String) -> Unit) {
        _uiState.value.selectedClipId?.let(action)
    }

    private fun currentClip(id: String) = _uiState.value.timeline.allClips.firstOrNull { it.id == id }

    private fun clipAtPlayhead(): Clip? {
        val playhead = _uiState.value.playheadMs
        return _uiState.value.timeline.videoTracks.firstOrNull()?.clipAt(playhead)
    }

    private fun mutate(structural: Boolean, op: (Timeline) -> Timeline) {
        val current = _uiState.value.timeline
        val updated = op(current)
        if (updated == current) return
        undoStack.addLast(current)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        applyTimeline(updated, structural)
    }

    private fun applyTimeline(timeline: Timeline, structural: Boolean) {
        val currentPlayhead = _uiState.value.playheadMs
        _uiState.update {
            it.copy(
                timeline = timeline,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                playheadMs = currentPlayhead.coerceIn(0, timeline.durationMs),
            )
        }
        if (structural) {
            playerController.setTimeline(timeline, preservePositionMs = currentPlayhead)
            playerController.seekTo(currentPlayhead)
        } else {
            playerController.updateVolumes(timeline)
            playerController.refreshPreviewEffects(timeline)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        val project = loadedProject ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            val updated = project.copy(timeline = _uiState.value.timeline)
            loadedProject = updated
            saveProject(updated)
        }
    }

    fun saveNow() {
        val project = loadedProject ?: return
        viewModelScope.launch {
            saveProject(project.copy(timeline = _uiState.value.timeline))
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveNow()
        voiceRecorder.cancel()
        playerController.release()
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
        private const val MAX_UNDO = 50
    }
}
