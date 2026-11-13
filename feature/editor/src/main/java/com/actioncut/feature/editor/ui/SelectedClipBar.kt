package com.actioncut.feature.editor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actioncut.core.designsystem.component.ToolItem

/**
 * Contextual action bar shown above the toolbar whenever a clip is selected.
 * Includes Split, Duplicate, Delete, Trim R, and Done.
 */
@Composable
fun SelectedClipBar(
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onTrimRight: () -> Unit = {},
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Only for scorecard clips: switches between the 3 scorecard designs. */
    onTheme: (() -> Unit)? = null,
    themeLabel: String = "Design",
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Selected clip actions" },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = (if (onTheme != null) Modifier.horizontalScroll(scrollState) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Clip",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
            ToolItem(label = "Split", icon = Icons.Filled.ContentCut, onClick = onSplit)
            ToolItem(label = "Duplicate", icon = Icons.Filled.ContentCopy, onClick = onDuplicate)
            ToolItem(label = "Delete", icon = Icons.Filled.DeleteOutline, onClick = onDelete)
            ToolItem(label = "Trim R", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = onTrimRight)
            if (onTheme != null) {
                ToolItem(label = themeLabel, icon = Icons.Filled.Palette, onClick = onTheme)
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
            ToolItem(label = "Done", icon = Icons.Filled.Done, onClick = onDeselect)
        }
    }
}
