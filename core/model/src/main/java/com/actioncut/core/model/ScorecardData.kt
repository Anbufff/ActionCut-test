package com.actioncut.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ScorecardKeyframe(
    val timeMs: Long,
    val rowIndex: Int
)

@Serializable
data class ScorecardRowItem(
    val rowIndex: Int,
    val srcTop: Int,
    val srcBottom: Int,
    val matchedPlayerName: String? = null,
    val fallbackName: String? = null
)

@Serializable
data class ScorecardClipData(
    val id: String,
    val sourceImageUri: String,
    val cropStartF: Float,
    val cropEndF: Float,
    val type: String = "BATTING", // BATTING or BOWLING
    val themeIdx: Int = 0,
    val rows: List<ScorecardRowItem> = emptyList(),
    val keyframes: List<ScorecardKeyframe> = listOf(ScorecardKeyframe(0L, 0))
)
