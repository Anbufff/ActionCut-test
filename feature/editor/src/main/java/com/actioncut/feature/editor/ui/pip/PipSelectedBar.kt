package com.actioncut.feature.editor.ui.pip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.actioncut.core.designsystem.component.ToolItem

/**
 * Fty Video Editor ஸ்கிரீன்ஷாட்டில் உள்ளபடியே PiP தேர்ந்தெடுக்கப்படும் போது தோன்றும் Toolbar:
 * இயல்பு நிலை: [ CROP ] [ TRIM R ] [ CUTOUT ] [ DELETE ] [ SETTINGS ]
 * Keyframe நிலை: [ KF +/- ] [ CLIP END ] [ VID END ] [ CROP ] [ TRIM R ]
 */
@Composable
fun PipSelectedBar(
    isKeyframeMode: Boolean = false,
    isCropActive: Boolean = false,
    isCutoutActive: Boolean = false,
    onCrop: () -> Unit,
    onTrimRight: () -> Unit,
    onCutout: () -> Unit,
    onDelete: () -> Unit,
    onSettings: () -> Unit,
    onKeyframeToggle: () -> Unit = {},
    onClipEnd: () -> Unit = {},
    onVidEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (isKeyframeMode) {
                ToolItem(label = "KF +/-", icon = Icons.Filled.Pin, onClick = onKeyframeToggle)
                ToolItem(label = "CLIP END", icon = Icons.Filled.SkipNext, onClick = onClipEnd)
                ToolItem(label = "VID END", icon = Icons.Filled.FastForward, onClick = onVidEnd)
                ToolItem(
                    label = "CROP",
                    icon = Icons.Filled.Crop,
                    selected = isCropActive,
                    onClick = onCrop,
                )
                ToolItem(label = "TRIM R", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = onTrimRight)
            } else {
                ToolItem(
                    label = "CROP",
                    icon = Icons.Filled.Crop,
                    selected = isCropActive,
                    onClick = onCrop,
                )
                ToolItem(label = "TRIM R", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = onTrimRight)
                ToolItem(
                    label = "CUTOUT",
                    icon = Icons.Filled.Face,
                    selected = isCutoutActive,
                    onClick = onCutout,
                )
                ToolItem(label = "DELETE", icon = Icons.Filled.Delete, onClick = onDelete)
                ToolItem(label = "SETTINGS", icon = Icons.Filled.Settings, onClick = onSettings)
            }
        }
    }
}
