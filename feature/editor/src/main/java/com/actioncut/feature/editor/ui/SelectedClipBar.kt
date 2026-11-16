package com.actioncut.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.actioncut.core.designsystem.component.clickableNoRipple
import com.actioncut.core.designsystem.component.rememberHaptic

/**
 * Compact contextual sub-toolbar shown above the main toolbar whenever a clip is selected.
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
        color = Color(0xFF141114),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (useScroll) Modifier.horizontalScroll(scrollState) else Modifier)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (useScroll) Arrangement.spacedBy(8.dp) else Arrangement.SpaceEvenly,
        ) {
            SubToolItem(
                label = cardLabel,
                icon = Icons.Filled.Dashboard,
                selected = isCardActive,
                onClick = onCardClick,
            )
            SubToolItem(label = "Split", icon = Icons.Filled.ContentCut, onClick = onSplit)
            SubToolItem(label = "Duplicate", icon = Icons.Filled.ContentCopy, onClick = onDuplicate)
            SubToolItem(label = "Delete", icon = Icons.Filled.DeleteOutline, onClick = onDelete)
            SubToolItem(label = "Trim R", icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = onTrimRight)
            if (onTheme != null) {
                SubToolItem(label = themeLabel, icon = Icons.Filled.Palette, onClick = onTheme)
                Spacer(Modifier.width(4.dp))
            }
            SubToolItem(label = "Done", icon = Icons.Filled.Done, onClick = onDeselect)
        }
    }
}

@Composable
private fun SubToolItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val haptic = rememberHaptic()
    val tileColor = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF282226)
    val iconColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .clickableNoRipple { haptic.tick(); onClick() }
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tileColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = iconColor, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
