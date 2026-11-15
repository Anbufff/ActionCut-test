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
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actioncut.core.designsystem.component.ToolItem

/**
 * Contextual action bar shown above the toolbar whenever a clip is selected.
 * Left-side Card button toggles the queued Auto Card popup; includes Split, Duplicate, Delete, Trim R, and Done.
 */
@Composable
fun SelectedClipBar(
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onTrimRight: () -> Unit = {},
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Only for scorecard / auto-card clips: switches between designs. */
    onTheme: (() -> Unit)? = null,
    themeLabel: String = "Design",
    /** Left-side Card queue button (replaces static "Clip" label). */
    onCardClick: () -> Unit = {},
    cardLabel: String = "Card",
    isCardActive: Boolean = false,
) {
    val scrollState = rememberScrollState()
    val useScroll = onTheme != null || cardLabel != "Card"
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Selected clip actions" },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = (if (useScroll) Modifier.horizontalScroll(scrollState) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolItem(
                label = cardLabel,
                icon = Icons.Filled.Dashboard,
                selected = isCardActive,
                onClick = onCardClick,
            )
            ToolItem(label = "Split", icon = Icons.Filled.ContentCut, onClick = onSplit)
            ToolItem(label = "Duplicate", icon = Icons.Filled.ContentCopy, onClick = onDuplicate)
            ToolItem(label = "Delete", icon = Icons.Filled.DeleteOutline, onClick = onDelete)
            ToolItem(label = "Trim R", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = onTrimRight)
            if (onTheme != null) {
                ToolItem(label = themeLabel, icon = Icons.Filled.Palette, onClick = onTheme)
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            } else if (!useScroll) {
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            }
            ToolItem(label = "Done", icon = Icons.Filled.Done, onClick = onDeselect)
        }
    }
}
