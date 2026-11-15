package com.actioncut.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actioncut.core.common.time.TimeFormatter
import com.actioncut.core.designsystem.component.PrimaryButton
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.TrackType
import com.actioncut.feature.editor.ui.newsalert.AutoCardDialog
import com.actioncut.feature.editor.ui.newsalert.AutoCardEntry
import com.actioncut.feature.editor.ui.newsalert.AutoCardRenderer
import com.actioncut.feature.editor.ui.newsalert.FlashNewsTextDialog
import com.actioncut.feature.editor.ui.newsalert.NewsAlertTemplatesDialog
import com.actioncut.feature.editor.ui.pip.FreeCropDialog
import com.actioncut.feature.editor.ui.pip.PipSelectedBar
import com.actioncut.feature.editor.ui.preview.PreviewPlayer
import com.actioncut.feature.editor.ui.scorecard.PlayerLibrary
import com.actioncut.feature.editor.ui.scorecard.PlayerLibraryDialog
import com.actioncut.feature.editor.ui.scorecard.PlayerRegisterDialog
import com.actioncut.feature.editor.ui.scorecard.ScoreCardCdnDialog
import com.actioncut.feature.editor.ui.scorecard.ScoreCardComposer
import com.actioncut.feature.editor.ui.scorecard.ScoreCardCropDialog
import com.actioncut.feature.editor.ui.timeline.EditorTimeline
import com.actioncut.feature.editor.ui.tools.EditorToolbar
import com.actioncut.feature.editor.ui.tools.ToolPanel

@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onExport: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showRename by remember { mutableStateOf(false) }

    var showNewsAlertTemplates by remember { mutableStateOf(false) }
    var showFlashNewsText by remember { mutableStateOf(false) }
    var showAutoCard by remember { mutableStateOf(false) }
    var cropTargetClip by remember { mutableStateOf<Clip?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current

    val tplImagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.setFbTemplateImage(uri.toString())
        }
    }

    val addMediaPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            viewModel.appendMediaList(uris.map { it.toString() })
        }
    }

    val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addAudioAtPlayhead(uri.toString())
        }
    }

    val pipMediaPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addPipAtPlayhead(uri.toString())
        }
    }

    var cricSource by remember { mutableStateOf<String?>(null) }
    var showCricCdn by remember { mutableStateOf(false) }
    var showPlayers by remember { mutableStateOf(false) }
    var showRegister by remember { mutableStateOf(false) }
    val playerLibrary = remember { PlayerLibrary(context) }
    val cricPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) cricSource = uri.toString() }
    val recordPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startVoiceover() }
    val requestVoiceover = {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startVoiceover()
        else recordPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            EditorTopBar(
                title = uiState.projectName,
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onBack = onBack,
                onExport = onExport,
                onTitleClick = { showRename = true },
                aspectRatio = uiState.aspectRatio,
                onAspectRatio = viewModel::setAspectRatio,
                isFbTemplateEnabled = uiState.canvas.isFbTemplateEnabled,
                onToggleFbTemplate = {
                    viewModel.toggleFbTemplate()
                    if (!uiState.canvas.isFbTemplateEnabled && uiState.canvas.fbTemplateImageUri == null) {
                        tplImagePicker.launch(arrayOf("image/*"))
                    }
                },
            )

            val activePipClips = remember(uiState.timeline) {
                uiState.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }
                    .filter { it.mediaUri?.contains("news_banner") != true }
            }

            val activeOverlays = remember(uiState.timeline) {
                uiState.timeline.allClips.filter {
                    it.type == ClipType.TEXT || it.type == ClipType.STICKER
                }
            }

            PreviewPlayer(
                player = viewModel.playerController.player,
                aspectRatio = uiState.aspectRatio,
                isPlaying = uiState.isPlaying,
                onTogglePlay = viewModel::togglePlayPause,
                overlays = activeOverlays,
                playheadMs = uiState.playheadMs,
                selectedClipId = uiState.selectedClipId,
                onSelectOverlay = viewModel::selectClip,
                onMoveOverlay = viewModel::setOverlayPosition,
                pipPlayer = viewModel.playerController.pipPlayer,
                pipClips = activePipClips,
                onScaleOverlay = viewModel::setOverlayScale,
                onCropChange = { id, crop -> viewModel.setCrop(id, crop) },
                onSetPipAnimation = viewModel::setPipAnimation,
                onDeleteOverlay = { id ->
                    viewModel.selectClip(id)
                    viewModel.deleteSelected()
                },
                onFlipOverlay = { id ->
                    viewModel.selectClip(id)
                    viewModel.flipSelectedPip()
                },
                fitMode = uiState.canvas.fitMode,
                backgroundColorArgb = uiState.canvas.backgroundColorArgb,
                allClips = uiState.timeline.allClips,
                onScorecardRowTapped = viewModel::onScorecardRowTapped,
                isFbTemplateEnabled = uiState.canvas.isFbTemplateEnabled,
                fbTemplateImageUri = uiState.canvas.fbTemplateImageUri,
                onPickTplImage = { tplImagePicker.launch(arrayOf("image/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            PlaybackBar(
                playheadMs = uiState.playheadMs,
                durationMs = uiState.durationMs,
                isPlaying = uiState.isPlaying,
                onTogglePlay = viewModel::togglePlayPause,
            )

            EditorTimeline(
                timeline = uiState.timeline,
                playheadMs = uiState.playheadMs,
                durationMs = uiState.durationMs,
                pxPerSecond = uiState.pxPerSecond,
                isPlaying = uiState.isPlaying,
                selectedClipId = uiState.selectedClipId,
                onScrub = viewModel::scrubTo,
                onSelectClip = viewModel::selectClip,
                onTrimStart = viewModel::trimStart,
                onTrimEnd = viewModel::trimEnd,
                onTrimCommit = viewModel::commitTrim,
                onMoveClip = viewModel::moveClip,
                onMoveCommit = viewModel::commitArrangement,
                onLoadWaveform = viewModel::loadWaveform,
                onAddMedia = { addMediaPicker.launch(arrayOf("image/*", "video/*")) },
                onPause = viewModel::pause,
                onScrubStart = viewModel::beginScrub,
                onScrubEnd = viewModel::endScrub,
                onZoomChange = viewModel::setZoom,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            )

            Box(modifier = Modifier.navigationBarsPadding()) {
                val tool = uiState.activeTool
                if (tool != null && tool.opensPanel) {
                    ToolPanel(
                        tool = tool,
                        selectedClip = uiState.selectedClip,
                        onClose = { viewModel.setActiveTool(null) },
                        onSpeed = viewModel::setSpeed,
                        onSpeedRamp = viewModel::setSpeedRamp,
                        onVolume = viewModel::setVolume,
                        onFilter = viewModel::setFilter,
                        onAdjust = viewModel::setAdjustments,
                        onAddText = viewModel::addTextAtPlayhead,
                        onAddSticker = viewModel::addStickerAtPlayhead,
                        canvas = uiState.canvas,
                        onFitMode = viewModel::setFitMode,
                        onBackgroundColor = viewModel::setBackgroundColor,
                        onCrop = { rect -> uiState.selectedClipId?.let { viewModel.setCrop(it, rect) } },
                        onTransition = viewModel::setTransition,
                        onToggleEffect = viewModel::toggleEffect,
                        onAddLibraryTrack = viewModel::addLibraryTrackAtPlayhead,
                        isRecordingVoiceover = uiState.isRecordingVoiceover,
                        onStartVoiceover = requestVoiceover,
                        onStopVoiceover = viewModel::stopVoiceover,
                        onAddKeyframe = viewModel::addKeyframeAtPlayhead,
                        onClearKeyframes = viewModel::clearKeyframes,
                        onApplyTransitionToAll = viewModel::applyTransitionToAll,
                        onApplyRandomTransitions = viewModel::applyRandomTransitions,
                    )
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val selClip = uiState.selectedClip
                        val isPipSelected = selClip != null && (
                            uiState.timeline.tracks.firstOrNull { it.type == TrackType.OVERLAY }
                                ?.clips?.any { it.id == selClip.id } == true &&
                                selClip.mediaUri?.contains("news_banner") != true
                        )

                        if (isPipSelected && selClip != null) {
                            val isKeyframeMode = selClip.keyframes.isNotEmpty()
                            val isCutoutActive = selClip.filter?.id?.contains("cutout") == true

                            PipSelectedBar(
                                isKeyframeMode = isKeyframeMode,
                                isCropActive = cropTargetClip?.id == selClip.id,
                                isCutoutActive = isCutoutActive,
                                onCrop = { cropTargetClip = selClip },
                                onTrimRight = viewModel::trimRightAtPlayhead,
                                onCutout = { viewModel.togglePipCutout() },
                                onDelete = viewModel::deleteSelected,
                                onSettings = { viewModel.setActiveTool(EditorTool.KEYFRAME) },
                                onKeyframeToggle = viewModel::addKeyframeAtPlayhead,
                                onClipEnd = viewModel::setPipToClipEnd,
                                onVidEnd = viewModel::setPipToVideoEnd,
                                onFlip = viewModel::flipSelectedPip,
                            )
                        } else if (selClip != null) {
                            val scorecardTheme = selClip.scorecard?.themeIdx
                            val isAutoCard = AutoCardRenderer.isAutoCardUri(selClip.mediaUri)
                            val hasQueue = uiState.autoCardQueue.isNotEmpty()
                            val cardLabel = if (hasQueue) {
                                "Card ${uiState.autoCardQueueIndex + 1}/${uiState.autoCardQueue.size}"
                            } else {
                                "Card"
                            }

                            SelectedClipBar(
                                onSplit = viewModel::splitAtPlayhead,
                                onDuplicate = viewModel::duplicateSelected,
                                onDelete = viewModel::deleteSelected,
                                onTrimRight = viewModel::trimRightAtPlayhead,
                                onDeselect = {
                                    viewModel.closeCardPopup()
                                    viewModel.selectClip(null)
                                },
                                onTheme = when {
                                    scorecardTheme != null -> ({ viewModel.cycleScorecardTheme() })
                                    isAutoCard -> ({ viewModel.cycleAutoCardDesign() })
                                    else -> null
                                },
                                themeLabel = if (scorecardTheme != null) {
                                    "Design ${ScoreCardComposer.normTheme(scorecardTheme) + 1}: ${ScoreCardComposer.themeName(scorecardTheme)}"
                                } else {
                                    "Design"
                                },
                                onCardClick = {
                                    if (hasQueue) viewModel.toggleCardPopup()
                                    else showAutoCard = true
                                },
                                cardLabel = cardLabel,
                                isCardActive = uiState.isCardPopupVisible,
                            )
                        }

                        EditorToolbar(
                            activeTool = uiState.activeTool,
                            onToolSelected = { tool ->
                                when (tool) {
                                    EditorTool.AUDIO -> audioPicker.launch(arrayOf("audio/*"))
                                    EditorTool.PIP -> pipMediaPicker.launch(arrayOf("image/*", "video/*"))
                                    EditorTool.CRIC -> cricPicker.launch(arrayOf("image/*"))
                                    EditorTool.CRIC_CDN -> showCricCdn = true
                                    EditorTool.CARD_ADD -> showRegister = true
                                    EditorTool.PLAYER -> showPlayers = true
                                    EditorTool.NEWS_ALERT -> showNewsAlertTemplates = true
                                    EditorTool.AUTO_CARD -> showAutoCard = true
                                    EditorTool.CROP -> {
                                        val sel = uiState.selectedClip
                                        if (sel != null && sel.mediaUri != null && sel.scorecard == null) {
                                            cropTargetClip = sel
                                        } else {
                                            viewModel.setActiveTool(tool)
                                        }
                                    }
                                    else -> viewModel.setActiveTool(tool)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Floating Queued Card Popup positioned cleanly above SelectedClipBar without overlapping buttons
        val currentQueuedEntry = uiState.autoCardQueue.getOrNull(uiState.autoCardQueueIndex)
        if (uiState.isCardPopupVisible && currentQueuedEntry != null) {
            val hasPhoto = remember(currentQueuedEntry, uiState.autoCardQueueIndex) {
                !currentQueuedEntry.customPhotoUri.isNullOrBlank() ||
                    !viewModel.resolvePlayerPhotoUriForAutoCard(currentQueuedEntry.playerName).isNullOrBlank()
            }
            QueuedCardPopup(
                entry = currentQueuedEntry,
                index = uiState.autoCardQueueIndex,
                total = uiState.autoCardQueue.size,
                hasPhoto = hasPhoto,
                onPrev = viewModel::prevQueuedCard,
                onNext = viewModel::nextQueuedCard,
                onAddAtPlayhead = viewModel::insertCurrentQueuedCardAtPlayhead,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 184.dp),
            )
        }
    }

    cropTargetClip?.let { target ->
        FreeCropDialog(
            clip = target,
            playheadMs = uiState.playheadMs,
            onDismiss = { cropTargetClip = null },
            onApplyCrop = { rect ->
                viewModel.setCrop(target.id, rect)
                cropTargetClip = null
            },
        )
    }

    if (showNewsAlertTemplates) {
        NewsAlertTemplatesDialog(
            onDismiss = { showNewsAlertTemplates = false },
            onSelectTemplate = {
                showNewsAlertTemplates = false
                showFlashNewsText = true
            },
        )
    }

    if (showFlashNewsText) {
        FlashNewsTextDialog(
            knownPlayerNames = remember { viewModel.getKnownPlayerNamesForAi() },
            onDismiss = { showFlashNewsText = false },
            onApply = { text ->
                showFlashNewsText = false
                viewModel.addNewsBannerOverlay(text)
            },
            onAiAutoSync = { segments ->
                showFlashNewsText = false
                viewModel.addAiFlashNewsSequence(segments)
            },
        )
    }

    if (showAutoCard) {
        AutoCardDialog(
            knownPlayerNames = remember { viewModel.getKnownPlayerNamesForAi() },
            resolvePhotoUri = { name -> viewModel.resolvePlayerPhotoUriForAutoCard(name) },
            onDismiss = { showAutoCard = false },
            onSaveQueue = { entries, durationMs ->
                showAutoCard = false
                viewModel.setAutoCardQueue(entries, durationMs)
            },
            onGenerateAndAdd = { entries, durationMs ->
                showAutoCard = false
                viewModel.addAutoCardSequence(entries, durationMs)
            },
        )
    }

    if (showRegister) {
        PlayerRegisterDialog(
            library = playerLibrary,
            onDismiss = { showRegister = false },
        )
    }
    if (showPlayers) {
        PlayerLibraryDialog(
            library = playerLibrary,
            onDismiss = { showPlayers = false },
        )
    }

    cricSource?.let { source ->
        ScoreCardCropDialog(
            sourceUri = source,
            onDismiss = { cricSource = null },
            onDone = { scorecardData ->
                viewModel.addScorecardClip(scorecardData)
                cricSource = null
            },
        )
    }

    if (showCricCdn) {
        ScoreCardCdnDialog(
            onDismiss = { showCricCdn = false },
            onDone = { scorecardData ->
                viewModel.addScorecardClip(scorecardData)
                showCricCdn = false
            },
        )
    }

    if (showRename) {
        RenameDialog(
            currentName = uiState.projectName,
            onDismiss = { showRename = false },
            onConfirm = viewModel::renameProject,
        )
    }
}

@Composable
private fun QueuedCardPopup(
    entry: AutoCardEntry,
    index: Int,
    total: Int,
    hasPhoto: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAddAtPlayhead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF5161820),
        tonalElevation = 10.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPrev,
                    enabled = total > 1,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous Card",
                        tint = if (total > 1) Color.White else Color(0xFF555555),
                        modifier = Modifier.size(18.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                ) {
                    Text(
                        text = "Card ${index + 1} / $total  •  ${entry.playerName}",
                        color = Color(0xFFFFE600),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = onNext,
                    enabled = total > 1,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next Card",
                        tint = if (total > 1) Color.White else Color(0xFF555555),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF20232E))
                    .clickable { onAddAtPlayhead() }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2A2E3D))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Filled.Palette,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = AutoCardRenderer.layoutName(entry.layoutIdx),
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (hasPhoto) Color(0xFF173D26) else Color(0xFF3E3012))
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            if (hasPhoto) Icons.Filled.Check else Icons.Filled.Image,
                            contentDescription = null,
                            tint = if (hasPhoto) Color(0xFF4DFF88) else Color(0xFFFFC107),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = if (hasPhoto) "Photo" else "NC Crest",
                            color = if (hasPhoto) Color(0xFF4DFF88) else Color(0xFFFFC107),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = "+ Tap to Add",
                        color = Color(0xFFFFC107),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = entry.text,
                    color = Color.White,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onTitleClick: () -> Unit,
    aspectRatio: AspectRatio,
    onAspectRatio: (AspectRatio) -> Unit,
    isFbTemplateEnabled: Boolean = false,
    onToggleFbTemplate: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onTitleClick() }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename project",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            listOf(
                AspectRatio.RATIO_16_9,
                AspectRatio.RATIO_4_5,
            ).forEach { r ->
                val on = aspectRatio == r
                Text(
                    text = r.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onAspectRatio(r) }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                )
            }
            if (aspectRatio == AspectRatio.RATIO_4_5) {
                Text(
                    text = "TPL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isFbTemplateEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .then(
                            if (isFbTemplateEnabled) {
                                Modifier.background(
                                    Brush.linearGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF))),
                                )
                            } else {
                                Modifier.background(Color.Transparent)
                            }
                        )
                        .clickable { onToggleFbTemplate() }
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                )
            }
        }
        IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", modifier = Modifier.size(18.dp))
        }
        PrimaryButton(text = "Export", onClick = onExport, leadingIcon = Icons.Filled.Add)
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(currentName) { mutableStateOf(currentName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("Project name") },
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(draft); onDismiss() }) {
                Text("Save")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PlaybackBar(
    playheadMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
) {
    val haptic = com.actioncut.core.designsystem.component.rememberHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = TimeFormatter.precise(playheadMs),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = " / ${TimeFormatter.clock(durationMs)}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier
                .size(width = 58.dp, height = 44.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.linearGradient(listOf(Color(0xFFFF2E4D), Color(0xFFFF6B3D))),
                )
                .clickable { haptic.click(); onTogglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
