// ... (ScoreCardCropDialog.kt உள்ளே onDone கால் செய்யும் பட்டன் பகுதியில்):
Button(
    enabled = composed != null && !busy,
    onClick = {
        val c = card ?: return@Button
        busy = true
        scope.launch {
            val clipId = "sc_${System.currentTimeMillis()}"
            val rowItems = c.rows.mapIndexed { idx, rInfo ->
                val res = rowResults.getOrNull(idx)
                ScorecardRowItem(
                    rowIndex = idx,
                    srcTop = rInfo.srcTop,
                    srcBottom = rInfo.srcBottom,
                    matchedPlayerName = res?.player?.name,
                    fallbackName = res?.rawName
                )
            }

            // முதல் பேட்ஸ்மேன் வரியை இயல்பாக தேர்ந்தெடுத்தல் (Row 0 டீம் ஸ்கோரைத் தவிர்த்தல்)
            val initialSelected = if (selected >= 0) selected else {
                rowItems.indexOfFirst { !it.fallbackName.isNullOrBlank() }.coerceAtLeast(0)
            }

            val scorecardData = ScorecardClipData(
                id = clipId,
                sourceImageUri = sourceUri,
                cropStartF = startF,
                cropEndF = endF,
                type = type.name,
                themeIdx = 0,
                rows = rowItems,
                keyframes = listOf(ScorecardKeyframe(0L, initialSelected))
            )

            // பின்னணி இமேஜ் முழு 16:9 ஆக உருவாக்கப்பட்டு Cache செய்யப்படுகிறது
            ScorecardManager.prepareCard(context, scorecardData, library)

            busy = false
            onDone(scorecardData)
        }
    },
) { Text(if (busy) "Working..." else "Add to video") }
