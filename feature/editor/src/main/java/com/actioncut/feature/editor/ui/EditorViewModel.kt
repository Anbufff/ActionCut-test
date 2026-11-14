package com.actioncut.feature.editor.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
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
import com.actioncut.core.model.FilterCategory
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
import com.actioncut.feature.editor.ui.newsalert.AiFlashNewsEngine
import com.actioncut.feature.editor.ui.newsalert.FlashNewsSegment
import com.actioncut.feature.editor.ui.newsalert.NewsBannerRenderer
import com.actioncut.feature.editor.ui.pip.AiCutoutManager
import com.actioncut.feature.editor.ui.scorecard.CdnScorecardClient
import com.actioncut.feature.editor.ui.scorecard.Player
import com.actioncut.feature.editor.ui.scorecard.PlayerLibrary
import com.actioncut.feature.editor.ui.scorecard.ScoreCardComposer
import com.actioncut.feature.editor.ui.scorecard.ScorecardManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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

    @Volatile
    private var isUserScrubbing: Boolean = false

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
                canvas = project.canvas,
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

            initialTimeline.allClips.forEach { c ->
                val uri = c.mediaUri
                if (c.type == ClipType.IMAGE && uri != null && c.filter?.id?.contains("cutout") == true) {
                    launch(Dispatchers.IO) {
                        AiCutoutManager.getOrCreateCutoutUri(
                            context = context,
                            imageUriStr = uri,
                            softBottomFade = c.id.startsWith("pip_ai_"),
                        )
                    }
                }
            }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            playerController.state.collect { playback ->
                _uiState.update { state ->
                    state.copy(
                        isPlaying = playback.isPlaying,
                        playheadMs = if (isUserScrubbing) {
                            state.playheadMs
                        } else {
                            playback.positionMs.coerceIn(0, state.durationMs)
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            playerController.positionFlow().collect { positionMs ->
                if (_uiState.value.isPlaying && !isUserScrubbing) {
                    _uiState.update { it.copy(playheadMs = positionMs.coerceIn(0, it.durationMs)) }
                }
            }
        }
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun pause() {
        if (_uiState.value.isPlaying) {
            playerController.pause()
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun beginScrub() {
        isUserScrubbing = true
        playerController.beginScrub()
        if (_uiState.value.isPlaying) {
            _uiState.update { it.copy(isPlaying = false) }
        }
    }

    fun scrubTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, _uiState.value.durationMs)
        _uiState.update { it.copy(playheadMs = clamped) }
        playerController.scrubTo(clamped)
    }

    fun endScrub(positionMs: Long) {
        isUserScrubbing = false
        val clamped = positionMs.coerceIn(0, _uiState.value.durationMs)
        _uiState.update { it.copy(playheadMs = clamped) }
        playerController.endScrub(clamped)
    }

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

    fun toggleFbTemplate() {
        val cur = _uiState.value.canvas
        val newOn = !cur.isFbTemplateEnabled
        updateCanvas { it.copy(isFbTemplateEnabled = newOn) }
    }

    fun setFbTemplateImage(uriStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val localUri = runCatching {
                val uri = Uri.parse(uriStr)
                val targetFile = File(context.filesDir, "tpl_bottom_image.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
                Uri.fromFile(targetFile).toString()
            }.getOrDefault(uriStr)

            updateCanvas { it.copy(fbTemplateImageUri = localUri, isFbTemplateEnabled = true) }
        }
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
            EditorTool.AUDIO, EditorTool.PIP, EditorTool.CRIC, EditorTool.CRIC_CDN, EditorTool.CARD_ADD, EditorTool.PLAYER, EditorTool.NEWS_ALERT -> clearTool()
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

    fun addNewsBannerOverlay(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val bannerUri = withContext(Dispatchers.IO) {
                NewsBannerRenderer.createBannerBitmap(context, text)
            }
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

    /** Returns ALL registered (CARD ADD) + auto-saved (CRIC CDN) player names for Gemini & offline matching. */
    fun getKnownPlayerNamesForAi(): List<String> {
        val libNames = runCatching { PlayerLibrary(context).all().map { it.name } }.getOrDefault(emptyList())
        val cdnKeys = loadCdnPhotoIndex().keys.toList()
        return (libNames + cdnKeys).distinct()
    }

    private fun loadCdnPhotoIndex(): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        val cdnDir = File(context.filesDir, "players_cdn")
        if (!cdnDir.exists()) return out
        val idxFile = File(cdnDir, "name_index.json")
        if (idxFile.exists()) {
            runCatching {
                val obj = JSONObject(idxFile.readText())
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val f = File(obj.optString(k, ""))
                    if (f.exists() && f.length() > 0L) out[k] = f
                }
            }
        }
        cdnDir.listFiles { f -> f.extension.equals("jpg", true) || f.extension.equals("png", true) }
            ?.forEach { f ->
                val key = f.nameWithoutExtension.lowercase().replace(Regex("[^a-z]"), "")
                if (key.length >= 3 && !out.containsKey(key) && f.length() > 0L) {
                    out[key] = f
                }
            }
        return out
    }

    /**
     * AI Auto-Sync:
     * Places ~8-9 timed Stadium Broadcast Flash News bursts aligned with Voiceover pauses,
     * and places transparent bottom-faded Player Cutout Photos standing on the right pitch.
     */
    fun addAiFlashNewsSequence(segments: List<FlashNewsSegment>) {
        if (segments.isEmpty()) return
        viewModelScope.launch {
            val currentTimeline = _uiState.value.timeline
            val audioClips = currentTimeline.audioTracks
                .flatMap { it.clips }
                .filter { it.mediaUri != null && it.volume > 0f }
                .sortedBy { it.timelineStartMs }

            val audioEndMs = audioClips.maxOfOrNull { it.timelineEndMs } ?: 0L
            val videoEndMs = currentTimeline.videoTracks
                .flatMap { it.clips }
                .maxOfOrNull { it.timelineEndMs } ?: 0L
            val targetDurationMs = maxOf(audioEndMs, videoEndMs, currentTimeline.durationMs, segments.size * 20_000L)

            val windows = AiFlashNewsEngine.detectVoicePauseStartsMs(
                context = context,
                audioClips = audioClips,
                segments = segments,
                totalDurationMs = targetDurationMs,
            )

            val libraryPlayers = withContext(Dispatchers.IO) { PlayerLibrary(context).all() }
            val cdnIndex = withContext(Dispatchers.IO) { loadCdnPhotoIndex() }

            val generatedClips = ArrayList<Clip>()
            val missingPlayers = LinkedHashSet<String>()
            var cutoutCount = 0

            withContext(Dispatchers.IO) {
                segments.forEachIndexed { idx, seg ->
                    val (startMs, endMs) = windows.getOrNull(idx) ?: return@forEachIndexed
                    val bannerUri = NewsBannerRenderer.createSegmentBannerBitmap(context, seg.title, seg.bullets)
                    val bannerClip = Clip(
                        id = "banner_ai_${System.currentTimeMillis()}_$idx",
                        type = ClipType.STICKER,
                        mediaUri = bannerUri,
                        timelineStartMs = startMs,
                        timelineEndMs = endMs,
                        transform = Transform(offsetX = 0f, offsetY = 0f, scale = 1.0f),
                    )
                    generatedClips.add(bannerClip)

                    val playerName = seg.playerName?.trim().orEmpty()
                    if (playerName.isNotEmpty()) {
                        val photoFile = resolvePlayerPhotoFile(playerName, libraryPlayers, cdnIndex)
                        if (photoFile != null && photoFile.exists() && photoFile.length() > 0L) {
                            val photoUri = Uri.fromFile(photoFile).toString()
                            AiCutoutManager.getOrCreateCutoutUri(
                                context = context,
                                imageUriStr = photoUri,
                                forceRefresh = true,
                                softBottomFade = true,
                            )
                            cutoutCount++
                            generatedClips.add(
                                Clip(
                                    id = "pip_ai_${System.currentTimeMillis()}_$idx",
                                    type = ClipType.IMAGE,
                                    mediaUri = photoUri,
                                    timelineStartMs = startMs,
                                    timelineEndMs = endMs,
                                    // Standing tall on the right stadium turf like Hardik Pandya reference
                                    transform = Transform(offsetX = 0.52f, offsetY = 0.14f, scale = 0.76f),
                                    crop = CropRect(0f, 0f, 1f, 1f),
                                    filter = Filter("ai_cutout", "AI Cutout", FilterCategory.NONE),
                                    effects = emptyList(),
                                    keyframes = emptyList(),
                                ),
                            )
                        } else {
                            missingPlayers.add(playerName)
                        }
                    }
                }
            }

            if (generatedClips.isEmpty()) return@launch

            val existingTrack = _uiState.value.timeline.tracks.firstOrNull { it.type == TrackType.OVERLAY }
            mutate(structural = false) { timeline ->
                var updated = timeline
                val trackId = if (existingTrack != null) {
                    existingTrack.id
                } else {
                    val (withTrack, newTrackId) = TimelineEditor.addTrack(updated, TrackType.OVERLAY)
                    updated = withTrack
                    newTrackId
                }
                generatedClips.forEach { clip ->
                    updated = TimelineEditor.addClip(updated, trackId, clip)
                }
                updated
            }

            val firstClip = generatedClips.first()
            _uiState.update { it.copy(selectedClipId = firstClip.id) }
            val missingNote = if (missingPlayers.isNotEmpty()) {
                "\nMissing photo (add in CARD ADD): ${missingPlayers.take(3).joinToString(", ")}"
            } else {
                ""
            }
            Toast.makeText(
                context,
                "${segments.size} Flash News + $cutoutCount Cutouts synced!$missingNote",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun resolvePlayerPhotoFile(
        playerName: String,
        libraryPlayers: List<Player>,
        cdnIndex: Map<String, File>,
    ): File? {
        val normTarget = playerName.lowercase().replace(Regex("[^a-z]"), "")
        val tokens = playerName.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 }
        val firstLast = if (tokens.size >= 2) "${tokens.first()}${tokens.last()}" else normTarget

        if (normTarget.length >= 3) {
            val libDir = File(context.filesDir, "players")
            val exactLib = libraryPlayers.firstOrNull { p ->
                val pn = p.name.lowercase().replace(Regex("[^a-z]"), "")
                pn.length >= 3 && (pn == normTarget || pn == firstLast || normTarget.contains(pn) || pn.contains(normTarget))
            } ?: libraryPlayers.firstOrNull { p ->
                val pTokens = p.name.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 }
                tokens.any { t -> t.length >= 4 && pTokens.contains(t) }
            }
            if (exactLib != null) {
                val f = File(libDir, exactLib.photoFile)
                if (f.exists() && f.length() > 0L) return f
            }
        }

        cdnIndex[normTarget]?.let { return it }
        cdnIndex[firstLast]?.let { return it }
        cdnIndex.entries.firstOrNull { (k, _) ->
            k.length >= 4 && (normTarget.contains(k) || k.contains(normTarget) || firstLast.contains(k) || k.contains(firstLast))
        }?.value?.let { return it }

        val lastToken = tokens.lastOrNull()?.takeIf { it.length >= 5 }
        if (lastToken != null) {
            cdnIndex.entries.firstOrNull { (k, _) -> k.contains(lastToken) }?.value?.let { return it }
        }
        for (tok in tokens.filter { it.length >= 5 }) {
            cdnIndex.entries.firstOrNull { (k, _) -> k.contains(tok) }?.value?.let { return it }
        }

        return CdnScorecardClient.findCachedPhotoFileByName(context, playerName)
    }

    fun addPipAtPlayhead(uri: String) {
        viewModelScope.launch {
            val media = resolveAnyMedia(uri) ?: return@launch
            val isVideo = media.type == MediaType.VIDEO
            val base = ClipFactory.fromMedia(
                if (isVideo) media.copy(type = MediaType.VIDEO) else media.copy(type = MediaType.IMAGE),
                _uiState.value.playheadMs,
            )
            val clip = base.copy(
                type = if (isVideo) ClipType.VIDEO else ClipType.IMAGE,
                transform = Transform(offsetX = 0.0f, offsetY = 0.0f, scale = 0.5f),
                crop = CropRect(0f, 0f, 1f, 1f),
                effects = emptyList(),
                keyframes = emptyList(),
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

    fun setPipToClipEnd() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val videoTrack = _uiState.value.timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
        val mainClip = videoTrack?.clipAt(clip.timelineStartMs)
            ?: videoTrack?.clips?.firstOrNull { it.timelineEndMs > clip.timelineStartMs }
        val targetEndMs = mainClip?.timelineEndMs ?: _uiState.value.timeline.durationMs
        if (targetEndMs > clip.timelineStartMs) {
            mutate(structural = true) { timeline ->
                TimelineEditor.updateClip(timeline, id) { c ->
                    c.copy(timelineEndMs = targetEndMs)
                }
            }
        }
    }

    fun setPipToVideoEnd() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val totalMs = _uiState.value.timeline.durationMs
        if (totalMs > clip.timelineStartMs) {
            mutate(structural = true) { timeline ->
                TimelineEditor.updateClip(timeline, id) { c ->
                    c.copy(timelineEndMs = totalMs)
                }
            }
        }
    }

    fun flipSelectedPip() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c ->
                val curId = c.filter?.id.orEmpty()
                val newId = when {
                    curId.contains("flip") && curId.contains("cutout") -> "ai_cutout"
                    curId.contains("flip") -> ""
                    curId.contains("cutout") -> "ai_cutout_flip"
                    else -> "flip"
                }
                c.copy(filter = if (newId.isEmpty()) null else Filter(newId, "Filter", FilterCategory.NONE))
            }
        }
    }

    fun togglePipCutout() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val isNowCutout = clip.filter?.id?.contains("cutout") != true

        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c ->
                val curId = c.filter?.id.orEmpty()
                val newId = if (isNowCutout) {
                    if (curId.contains("flip")) "ai_cutout_flip" else "ai_cutout"
                } else {
                    if (curId.contains("flip")) "flip" else ""
                }
                c.copy(filter = if (newId.isEmpty()) null else Filter(newId, "AI Cutout", FilterCategory.NONE))
            }
        }

        val mediaUri = clip.mediaUri
        if (isNowCutout && mediaUri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                AiCutoutManager.getOrCreateCutoutUri(
                    context = context,
                    imageUriStr = mediaUri,
                    forceRefresh = true,
                    softBottomFade = clip.id.startsWith("pip_ai_"),
                )
            }
        }
    }

    fun addScorecardClip(data: ScorecardClipData) {
        val durationMs = 120_000L
        val cardFile = File(context.cacheDir, "card_${data.id}.png")
        val cardUri = if (cardFile.exists()) {
            Uri.fromFile(cardFile).toString()
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
        setScorecardTheme(clipId, (ScoreCardComposer.normTheme(sc.themeIdx) + 1) % ScoreCardComposer.THEME_COUNT)
    }

    fun setScorecardTheme(clipId: String, themeIdx: Int) {
        val clip = currentClip(clipId) ?: return
        val sc = clip.scorecard ?: return
        val updatedSc = sc.copy(themeIdx = ScoreCardComposer.normTheme(themeIdx))
        viewModelScope.launch {
            ScorecardManager.prepareCard(context, updatedSc, PlayerLibrary(context))
            mutate(structural = false) { timeline ->
                TimelineEditor.updateClip(timeline, clipId) { it.copy(scorecard = updatedSc) }
            }
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
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, clipId) { c ->
                val newTransform = c.transform.copy(offsetX = offsetX, offsetY = offsetY)
                val updatedKfs = if (c.keyframes.size <= 1) {
                    c.keyframes.map { it.copy(offsetX = offsetX, offsetY = offsetY) }
                } else {
                    val timeInClip = (_uiState.value.playheadMs - c.timelineStartMs).coerceAtLeast(0L)
                    val nearIdx = c.keyframes.indexOfFirst { abs(it.timeMs - timeInClip) <= 300L }
                    if (nearIdx >= 0) {
                        c.keyframes.mapIndexed { idx, kf ->
                            if (idx == nearIdx) kf.copy(offsetX = offsetX, offsetY = offsetY) else kf
                        }
                    } else {
                        val dx = offsetX - c.transform.offsetX
                        val dy = offsetY - c.transform.offsetY
                        c.keyframes.map { it.copy(offsetX = it.offsetX + dx, offsetY = it.offsetY + dy) }
                    }
                }
                c.copy(transform = newTransform, keyframes = updatedKfs)
            }
        }
    }

    fun setOverlayScale(clipId: String, scale: Float) {
        val clip = currentClip(clipId) ?: return
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, clipId) { c ->
                val newScale = scale.coerceIn(0.15f, 4.0f)
                val newTransform = c.transform.copy(scale = newScale)
                val updatedKfs = c.keyframes.map { it.copy(scale = newScale) }
                c.copy(transform = newTransform, keyframes = updatedKfs)
            }
        }
    }

    fun setFitMode(mode: FitMode) = updateCanvas { it.copy(fitMode = mode) }

    fun setBackgroundColor(argb: Int) = updateCanvas { it.copy(backgroundColorArgb = argb) }

    private fun updateCanvas(transform: (CanvasSettings) -> CanvasSettings) {
        val newCanvas = transform(_uiState.value.canvas)
        val updatedTimeline = _uiState.value.timeline.copy(canvas = newCanvas)
        _uiState.update { it.copy(canvas = newCanvas, timeline = updatedTimeline) }
        loadedProject = loadedProject?.copy(canvas = newCanvas, timeline = updatedTimeline)
        scheduleSave()
    }

    fun setCrop(clipId: String, crop: CropRect) {
        mutate(structural = false) {
            TimelineEditor.updateClip(it, clipId) { c -> c.copy(crop = crop) }
        }
    }

    fun addKeyframeAtPlayhead() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val timeInClip = (_uiState.value.playheadMs - clip.timelineStartMs).coerceAtLeast(0L)
        val kf = Keyframe(
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

    fun setTransition(transition: Transition?) {
        val targetId = _uiState.value.selectedClipId
            ?: clipAtPlayhead()?.id
            ?: _uiState.value.timeline.videoTracks.firstOrNull()?.clips?.firstOrNull()?.id
        if (targetId != null) {
            mutate(structural = true) { TimelineEditor.setTransition(it, targetId, transition) }
            _uiState.update { it.copy(selectedClipId = targetId) }
        }
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
            TimelineEditor.updateClip(timeline, id) { c ->
                val newEffects = if (existingIndex >= 0) {
                    c.effects.filterIndexed { index, _ -> index != existingIndex }
                } else {
                    c.effects + VisualEffect(type, 1f)
                }
                c.copy(effects = newEffects)
            }
        }
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val current = _uiState.value.timeline
        redoStack.addLast(current)
        _uiState.update {
            it.copy(
                timeline = prev,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
        loadedProject = loadedProject?.copy(timeline = prev)
        playerController.setTimeline(prev, preservePositionMs = _uiState.value.playheadMs)
        scheduleSave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        val current = _uiState.value.timeline
        undoStack.addLast(current)
        _uiState.update {
            it.copy(
                timeline = next,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
        loadedProject = loadedProject?.copy(timeline = next)
        playerController.setTimeline(next, preservePositionMs = _uiState.value.playheadMs)
        scheduleSave()
    }

    private inline fun withSelected(crossinline block: (String) -> Unit) {
        _uiState.value.selectedClipId?.let(block)
    }

    private fun currentClip(id: String): Clip? =
        _uiState.value.timeline.allClips.firstOrNull { it.id == id }

    private fun clipAtPlayhead(): Clip? {
        val pos = _uiState.value.playheadMs
        return _uiState.value.timeline.tracks
            .firstOrNull { it.type == TrackType.VIDEO }
            ?.clips?.firstOrNull { it.contains(pos) }
    }

    private fun mutate(structural: Boolean = false, transform: (Timeline) -> Timeline) {
        val currentTimeline = _uiState.value.timeline
        val newTimeline = transform(currentTimeline)
        if (newTimeline == currentTimeline) return

        undoStack.addLast(currentTimeline)
        redoStack.clear()

        _uiState.update {
            it.copy(
                timeline = newTimeline,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
        loadedProject = loadedProject?.copy(timeline = newTimeline)

        if (structural) {
            playerController.setTimeline(newTimeline, preservePositionMs = _uiState.value.playheadMs)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500L)
            loadedProject?.let { saveProject(it) }
        }
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
    }
}
