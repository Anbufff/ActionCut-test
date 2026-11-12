package com.actioncut.feature.media.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.actioncut.core.common.time.TimeFormatter
import com.actioncut.core.designsystem.component.EmptyState
import com.actioncut.core.designsystem.component.SelectableChip
import com.actioncut.core.domain.repository.MediaFilter
import com.actioncut.core.model.MediaItem
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun MediaPickerScreen(
    onProjectCreated: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: MediaPickerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val permissionState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(permissionState.allPermissionsGranted) {
        viewModel.onPermissionResult(permissionState.allPermissionsGranted)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("New Project", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (uiState.selected.isNotEmpty()) {
                            Text(
                                text = "${uiState.selected.size} selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // 🌟 Create பட்டன் எப்போதும் மேலே வலது மூலையில் தெளிவாகத் தெரியும்
                    if (uiState.selected.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.createProject(onCreated = onProjectCreated) },
                            enabled = !uiState.isCreating,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (uiState.isCreating) "Creating…" else "Create (${uiState.selected.size})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            // Project Name input field
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = uiState.projectName,
                    onValueChange = viewModel::setProjectName,
                    singleLine = true,
                    placeholder = { Text("Project name (e.g. Video Nov 12)", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.DriveFileRenameOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }

            FilterRow(selected = uiState.filter, onSelect = viewModel::setFilter)

            when {
                !uiState.permissionGranted -> EmptyState(
                    icon = Icons.Filled.PermMedia,
                    title = "Access your media",
                    description = "Grant access to import videos and photos for editing.",
                    actionLabel = "Grant access",
                    onAction = { permissionState.launchMultiplePermissionRequest() },
                )

                uiState.isEmpty -> EmptyState(
                    icon = Icons.Filled.PermMedia,
                    title = "No media found",
                    description = "Capture a video or photo, then come back to start editing.",
                )

                else -> MediaGrid(
                    media = uiState.media,
                    selectionIndexOf = uiState::selectionIndexOf,
                    onToggle = viewModel::toggleSelection,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(selected: MediaFilter, onSelect: (MediaFilter) -> Unit) {
    val filters = listOf(
        MediaFilter.ALL to "All",
        MediaFilter.VIDEO to "Video",
        MediaFilter.IMAGE to "Photo",
        MediaFilter.AUDIO to "Audio",
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters.size) { index ->
            val (filter, label) = filters[index]
            SelectableChip(label = label, selected = selected == filter, onClick = { onSelect(filter) })
        }
    }
}

@Composable
private fun MediaGrid(
    media: List<MediaItem>,
    selectionIndexOf: (MediaItem) -> Int,
    onToggle: (MediaItem) -> Unit,
) {
    // 🌟 அதிக படங்கள் தெரியும்படி 3 Columns கிரிட்
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(media, key = { it.id + "_" + it.uri }) { item ->
            MediaCell(
                item = item,
                selectionIndex = selectionIndexOf(item),
                onClick = { onToggle(item) },
            )
        }
    }
}

@Composable
private fun MediaCell(
    item: MediaItem,
    selectionIndex: Int,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val isSelected = selectionIndex >= 0
    val request = remember(item.uri) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .apply {
                if (item.isVideo) {
                    videoFrameMillis(1_000)
                    decoderFactory(VideoFrameDecoder.Factory())
                }
            }
            .crossfade(true)
            .build()
    }

    // 🌟 கிளிக் செய்தால்: முதல் முறை Select ஆகும்; மீண்டும் அதே படத்தை தொட்டால் Unselect ஆகும்!
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isSelected) {
                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable { onClick() },
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Select ஆனதும் படத்தின் மேல் லேசான கருப்பு நிற ஷேடிங்
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
            )
        }

        // வீடியோவின் கால அளவு
        if (item.durationMs > 0) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            ) {
                Text(
                    text = TimeFormatter.clock(item.durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }

        // வலது மேல் மூலையில் தோன்றும் வரிசை எண் (1, 2, 3...)
        SelectionBadge(
            selectionIndex = selectionIndex,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp),
        )
    }
}

@Composable
private fun SelectionBadge(selectionIndex: Int, modifier: Modifier = Modifier) {
    val isSelected = selectionIndex >= 0
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
            )
            .then(
                if (!isSelected) {
                    Modifier.border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Text(
                text = (selectionIndex + 1).toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
