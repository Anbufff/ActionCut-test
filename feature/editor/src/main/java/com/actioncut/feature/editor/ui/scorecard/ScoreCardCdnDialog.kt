package com.actioncut.feature.editor.ui.scorecard

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.actioncut.core.designsystem.component.SelectableChip
import com.actioncut.core.model.ScorecardClipData
import com.actioncut.core.model.ScorecardKeyframe
import com.actioncut.core.model.ScorecardRowItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

@Composable
fun ScoreCardCdnDialog(
    onDismiss: () -> Unit,
    onDone: (ScorecardClipData) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { PlayerLibrary(context) }

    var workerUrl by remember { mutableStateOf(CdnScorecardClient.savedWorkerUrl(context)) }
    var matchInput by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var matchData by remember { mutableStateOf<CdnMatchScorecard?>(null) }
    var rawJson by remember { mutableStateOf("") }
    var inningsIdx by remember { mutableIntStateOf(0) }
    var type by remember { mutableStateOf(ScoreCardType.BATTING) }
    var themeIdx by remember { mutableIntStateOf(0) }
    var selectedRow by remember { mutableIntStateOf(2) }
    var composed by remember { mutableStateOf<ComposedCard?>(null) }

    LaunchedEffect(matchData, inningsIdx, type, selectedRow, themeIdx) {
        val inn = matchData?.innings?.getOrNull(inningsIdx) ?: return@LaunchedEffect
        composed = withContext(Dispatchers.Default) {
            CdnVectorScoreCardComposer.composeFrame(context, inn, type, selectedRow, library, themeIdx)
        }
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = maxOf(navInset, 56.dp)
    val white = TextStyle(color = Color.White)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(12.dp)
                    .padding(bottom = bottomSpace),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Live / Completed Cricbuzz Scorecard (4K Vector)", color = Color.White)

                OutlinedTextField(
                    value = workerUrl,
                    onValueChange = { workerUrl = it },
                    label = { Text("Cloudflare Worker CDN URL") },
                    singleLine = true,
                    textStyle = white,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = matchInput,
                        onValueChange = { matchInput = it },
                        label = { Text("Match ID or Cricbuzz Link (e.g. 161256)") },
                        singleLine = true,
                        textStyle = white,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = !loading && matchInput.isNotBlank() && workerUrl.isNotBlank(),
                        onClick = {
                            loading = true
                            errorMsg = null
                            CdnScorecardClient.saveWorkerUrl(context, workerUrl)
                            scope.launch {
                                val res = CdnScorecardClient.fetch(context, workerUrl, matchInput)
                                loading = false
                                res.onSuccess { (data, json) ->
                                    matchData = data
                                    rawJson = json
                                    inningsIdx = (data.innings.size - 1).coerceAtLeast(0)
                                    selectedRow = 2
                                }
                                res.onFailure {
                                    errorMsg = it.localizedMessage ?: "Failed to load scorecard"
                                }
                            }
                        },
                    ) { Text(if (loading) "..." else "Fetch") }
                }

                val data = matchData
                if (data != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        data.innings.forEachIndexed { idx, inn ->
                            val lbl = "${inn.teamShortName.ifBlank { inn.teamName }} ${inn.score}-${inn.wickets}"
                            SelectableChip(
                                label = lbl,
                                selected = inningsIdx == idx,
                                onClick = { inningsIdx = idx; selectedRow = 2 },
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScoreCardType.values().forEach { t ->
                            SelectableChip(
                                label = t.label,
                                selected = type == t,
                                onClick = { type = t; selectedRow = 2 },
                            )
                        }
                        SelectableChip(
                            label = "Design ${themeIdx + 1}: ${ScoreCardComposer.themeName(themeIdx)}",
                            selected = true,
                            onClick = { themeIdx = (themeIdx + 1) % ScoreCardComposer.THEME_COUNT },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val comp = composed
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        errorMsg != null -> Text(errorMsg!!, color = Color(0xFFFF6B6B), modifier = Modifier.align(Alignment.Center))
                        comp != null -> {
                            val bmp = remember(comp) { comp.bitmap.asImageBitmap() }
                            var boxSize by remember { mutableStateOf(IntSize.Zero) }
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { boxSize = it }
                                    .pointerInput(comp) {
                                        detectTapGestures { off ->
                                            if (boxSize.width <= 0 || boxSize.height <= 0) return@detectTapGestures
                                            val sc = min(boxSize.width.toFloat() / bmp.width, boxSize.height.toFloat() / bmp.height)
                                            val rh = bmp.height * sc
                                            val top = (boxSize.height - rh) / 2f
                                            val y = (off.y - top) / rh * bmp.height
                                            val idx = comp.rowRects.indexOfFirst { y >= it.top && y <= it.bottom }
                                            if (idx >= 2) selectedRow = idx
                                        }
                                    },
                            )
                        }
                        else -> Text(
                            "Enter any live or completed Cricbuzz Match ID / Link and tap Fetch.",
                            color = Color(0xFFBBBBBB),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        enabled = data != null && composed != null && !loading,
                        onClick = {
                            val inn = data?.innings?.getOrNull(inningsIdx) ?: return@Button
                            scope.launch {
                                val clipId = "sc_${System.currentTimeMillis()}"
                                val dir = File(context.filesDir, "scorecards").apply { mkdirs() }
                                val jsonFile = File(dir, "cdn_score_$clipId.json")
                                withContext(Dispatchers.IO) { jsonFile.writeText(rawJson) }

                                val vRows = CdnVectorScoreCardComposer.buildRows(inn, type)
                                val rowItems = vRows.mapIndexed { idx, r ->
                                    val nm = (r as? CdnVectorScoreCardComposer.VectorRow.PlayerRow)?.name
                                    ScorecardRowItem(
                                        rowIndex = idx,
                                        srcTop = 0,
                                        srcBottom = 0,
                                        matchedPlayerName = nm,
                                        fallbackName = nm,
                                    )
                                }
                                val safeInitial = selectedRow.coerceIn(0, (rowItems.size - 1).coerceAtLeast(0))

                                val scData = ScorecardClipData(
                                    id = clipId,
                                    sourceImageUri = Uri.fromFile(jsonFile).toString(),
                                    cropStartF = inningsIdx.toFloat(),
                                    cropEndF = 1f,
                                    type = type.name,
                                    themeIdx = themeIdx,
                                    rows = rowItems,
                                    keyframes = listOf(ScorecardKeyframe(0L, safeInitial)),
                                )
                                ScorecardManager.prepareCard(context, scData, library)
                                Toast.makeText(context, "Vector Scorecard Ready!", Toast.LENGTH_SHORT).show()
                                onDone(scData)
                            }
                        },
                    ) { Text("Add to video") }
                }
            }
        }
    }
}
