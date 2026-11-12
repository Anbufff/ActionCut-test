package com.actioncut.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actioncut.core.common.time.TimeFormatter
import com.actioncut.core.designsystem.component.PrimaryButton
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.TrackType
import com.actioncut.feature.editor.ui.newsalert.FlashNewsTextDialog
import com.actioncut.feature.editor.ui.newsalert.NewsAlertTemplatesDialog
import com.actioncut.feature.editor.ui.pip.PipSelectedBar
import com.actioncut.feature.editor.ui.preview.PreviewPlayer
import com.actioncut.feature.editor.ui.scorecard.PlayerLibrary
import com.actioncut.feature.editor.ui.scorecard.PlayerLibraryDialog
import com.actioncut.feature.editor.ui.scorecard.PlayerRegisterDialog
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
    var isCropModeActive by remember { mutableStateOf(false) }

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

        val activePipClips = uiState.timeline.tracks
            .filter { it.type == TrackType.OVERLAY }
            .flatMap { it.clips }
            .filter { it.mediaUri?.contains("news_banner") != true }

        PreviewPlayer(
            player = viewModel.playerController.player,
            aspectRatio = uiState.aspectRatio,
            isPlaying = uiState.isPlaying,
            onTogglePlay = viewModel::togglePlayPause,
            overlays = uiState.timeline.allClips.filter {
                it.type == ClipType.TEXT || it.type == ClipType.STICKER
            },
            playheadMs = uiState.playheadMs,
            selectedClipId = uiState.selectedClipId,
            onSelectOverlay = viewModel::selectClip,
            onMoveOverlay = viewModel::setOverlayPosition,
            pipPlayer = viewModel.playerController.pipPlayer,
            pipClips = activePipClips,
            onScaleOverlay = viewModel::setOverlayScale,
            onCropChange = { id, crop -> viewModel.setCrop(id, crop) },
            isCropModeActive = isCropModeActive,
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
            onZoomIn = { viewModel.setZoom(uiState.pxPerSecond * 1.4f) },
            onZoomOut = { viewModel.setZoom(uiState.pxPerSecond / 1.4f) },
        )

        EditorTimeline(
            timeline = uiState.timeline,
            playheadMs = uiState.playheadMs,
            durationMs = uiState.durationMs,
            pxPerSecond = uiState.pxPerSecond,
            isPlaying = uiState.isPlaying,
            selectedClipId = uiState.selectedClipId,
            onScrub = viewModel::seekTo,
            onSelectClip = viewModel::selectClip,
            onTrimStart = viewModel::trimStart,
            onTrimEnd = viewModel::trimEnd,
            onTrimCommit = viewModel::commitTrim,
            onMoveClip = viewModel::moveClip,
            onMoveCommit = viewModel::commitArrangement,
            onLoadWaveform = viewModel::loadWaveform,
            onAddMedia = { addMediaPicker.launch(arrayOf("image/*", "video/*")) },
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
                            isCropActive = isCropModeActive,
                            isCutoutActive = isCutoutActive,
                            onCrop = { isCropModeActive = !isCropModeActive },
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
                        SelectedClipBar(
                            onSplit = viewModel::splitAtPlayhead,
                            onDuplicate = viewModel::duplicateSelected,
                            onDelete = viewModel::deleteSelected,
                            onTrimRight = viewModel::trimRightAtPlayhead,
                            onDeselect = { viewModel.selectClip(null) },
                        )
                    }

                    EditorToolbar(
                        activeTool = uiState.activeTool,
                        onToolSelected = { tool ->
                            when (tool) {
                                EditorTool.AUDIO -> audioPicker.launch(arrayOf("audio/*"))
                                EditorTool.PIP -> pipMediaPicker.launch(arrayOf("image/*", "video/*"))
                                EditorTool.CRIC -> cricPicker.launch(arrayOf("image/*"))
                                EditorTool.CARD_ADD -> showRegister = true
                                EditorTool.PLAYER -> showPlayers = true
                                EditorTool.NEWS_ALERT -> showNewsAlertTemplates = true
                                else -> viewModel.setActiveTool(tool)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
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
            onDismiss = { showFlashNewsText = false },
            onApply = { text ->
                showFlashNewsText = false
                viewModel.addNewsBannerOverlay(text)
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

    if (showRename) {
        RenameDialog(
            currentName = uiState.projectName,
            onDismiss = { showRename = false },
            onConfirm = viewModel::renameProject,
        )
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onTitleClick() }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename project",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
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
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onAspectRatio(r) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (aspectRatio == AspectRatio.RATIO_4_5) {
                Text(
                    text = "TPL",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isFbTemplateEnabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isFbTemplateEnabled) Brush.linearGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF)))
                            else Color.Transparent,
                        )
                        .clickable { onToggleFbTemplate() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
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
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    val haptic = com.actioncut.core.designsystem.component.rememberHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = { haptic.click(); onTogglePlay() }) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = "${TimeFormatter.precise(playheadMs)} / ${TimeFormatter.clock(durationMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onZoomOut) {
            Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onZoomIn) {
            Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(20.dp))
        }
    }
}
