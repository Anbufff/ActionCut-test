package com.actioncut.feature.editor.ui.scorecard

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.actioncut.core.designsystem.component.SelectableChip
import com.actioncut.core.model.ScorecardClipData
import com.actioncut.core.model.ScorecardKeyframe
import com.actioncut.core.model.ScorecardRowItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var matchData by remember { mutableStateOf<CdnMatchScorecard?>(null) }
    var rawJson by remember { mutableStateOf("") }
    var inningsIdx by remember { mutableIntStateOf(0) }

    var pointsData by remember { mutableStateOf<CdnPointsTable?>(null) }
    var pointsRawJson by remember { mutableStateOf("") }
    var pointsGroupIdx by remember { mutableIntStateOf(0) }
    var pointsLoadedForKey by remember { mutableStateOf("") }

    var squadsData by remember { mutableStateOf<CdnMatchSquads?>(null) }
    var squadsRawJson by remember { mutableStateOf("") }
    var squadTeamIdx by remember { mutableIntStateOf(0) }
    var squadsLoadedForMatch by remember { mutableStateOf("") }

    var recentMatches by remember { mutableStateOf<List<CdnRecentMatch>>(emptyList()) }
    var loadingRecent by remember { mutableStateOf(false) }
    var recentError by remember { mutableStateOf<String?>(null) }
    var showRecentPicker by remember { mutableStateOf(true) }

    var mode by remember { mutableStateOf(CdnViewMode.SCORE) }
    var themeIdx by remember { mutableIntStateOf(0) }
    var selectedRow by remember { mutableIntStateOf(2) }
    var photoVersion by remember { mutableIntStateOf(0) }
    var composed by remember { mutableStateOf<ComposedCard?>(null) }

    fun loadRecentMatches() {
        if (!workerUrl.trim().startsWith("http")) return
        loadingRecent = true
        recentError = null
        scope.launch {
            val res = CdnScorecardClient.fetchRecentMatches(workerUrl)
            loadingRecent = false
            res.onSuccess { list ->
                recentMatches = list
                if (list.isEmpty()) recentError = "No recent matches found."
            }
            res.onFailure {
                recentError = it.localizedMessage ?: "Failed to load recent matches"
            }
        }
    }

    fun loadPointsForCurrent() {
        val sid = matchData?.seriesId
        val key = "${sid.orEmpty()}|${matchInput.trim()}"
        if (pointsData != null && pointsLoadedForKey == key) return
        loading = true
        errorMsg = null
        scope.launch {
            val res = CdnScorecardClient.fetchPointsTable(
                workerBaseUrl = workerUrl,
                seriesId = sid,
                matchOrUrl = matchInput.ifBlank { matchData?.matchId.orEmpty() },
            )
            loading = false
            res.onSuccess { (pt, json) ->
                pointsData = pt
                pointsRawJson = json
                pointsGroupIdx = 0
                pointsLoadedForKey = key
                selectedRow = 2
                pt.groups.firstOrNull()?.let { grp ->
                    CdnScorecardClient.downloadPointsLogos(context, workerUrl, grp)
                    photoVersion++
                }
            }
            res.onFailure {
                errorMsg = it.localizedMessage ?: "Points table unavailable"
            }
        }
    }

    fun loadSquadsForCurrent() {
        val targetMatch = matchData?.matchId?.takeIf { it.isNotBlank() } ?: matchInput.trim()
        if (targetMatch.isBlank()) return
        if (squadsData != null && squadsLoadedForMatch == targetMatch) return
        loading = true
        errorMsg = null
        scope.launch {
            val res = CdnScorecardClient.fetchSquads(workerUrl, targetMatch)
            loading = false
            res.onSuccess { (sq, json) ->
                squadsData = sq
                squadsRawJson = json
                squadTeamIdx = 0
                squadsLoadedForMatch = targetMatch
                selectedRow = 2
                sq.teams.firstOrNull()?.let { tm ->
                    CdnScorecardClient.downloadSquadPhotos(context, workerUrl, tm)
                    photoVersion++
                }
            }
            res.onFailure {
                errorMsg = it.localizedMessage ?: "Squads unavailable"
            }
        }
    }

    fun performFetch(input: String) {
        val clean = input.trim()
        if (clean.isBlank() || !workerUrl.trim().startsWith("http")) return
        loading = true
        errorMsg = null
        showRecentPicker = false
        CdnScorecardClient.saveWorkerUrl(context, workerUrl)

        val isSeriesPointsLink = clean.contains("points-table", ignoreCase = true) ||
            (clean.contains("cricket-series/", ignoreCase = true) && !clean.contains("scorecard", ignoreCase = true))

        if (isSeriesPointsLink) {
            mode = CdnViewMode.POINTS
            matchData = null
            rawJson = ""
            pointsData = null
            pointsLoadedForKey = ""
            scope.launch {
                val res = CdnScorecardClient.fetchPointsTable(workerUrl, matchOrUrl = clean)
                loading = false
                res.onSuccess { (pt, json) ->
                    pointsData = pt
                    pointsRawJson = json
                    pointsGroupIdx = 0
                    pointsLoadedForKey = "|$clean"
                    selectedRow = 2
                    pt.groups.firstOrNull()?.let { grp ->
                        CdnScorecardClient.downloadPointsLogos(context, workerUrl, grp)
                        photoVersion++
                    }
                }
                res.onFailure {
                    errorMsg = it.localizedMessage ?: "Failed to load points table"
                }
            }
            return
        }

        if (clean.contains("squads", ignoreCase = true)) {
            mode = CdnViewMode.SQUADS
        } else if (mode == CdnViewMode.POINTS) {
            mode = CdnViewMode.SCORE
        }

        pointsData = null
        pointsRawJson = ""
        pointsLoadedForKey = ""
        squadsData = null
        squadsRawJson = ""
        squadsLoadedForMatch = ""

        scope.launch {
            val res = CdnScorecardClient.fetchMatchOnly(workerUrl, clean)
            loading = false
            res.onSuccess { (data, json) ->
                matchData = data
                rawJson = json
                inningsIdx = (data.innings.size - 1).coerceAtLeast(0)
                selectedRow = 2
                photoVersion++
                if (mode == CdnViewMode.SQUADS) {
                    loadSquadsForCurrent()
                }
            }
            res.onFailure {
                errorMsg = it.localizedMessage ?: "Failed to load scorecard"
            }
        }
    }

    LaunchedEffect(Unit) {
        loadRecentMatches()
    }

    LaunchedEffect(matchData, inningsIdx, pointsData, pointsGroupIdx, squadsData, squadTeamIdx, mode, selectedRow, themeIdx, photoVersion) {
        val currentInn = matchData?.innings?.getOrNull(inningsIdx)
        val currentPtGrp = pointsData?.groups?.getOrNull(pointsGroupIdx)
        val currentSqTeam = squadsData?.teams?.getOrNull(squadTeamIdx)

        val hasActiveData = when (mode) {
            CdnViewMode.SCORE, CdnViewMode.BOWL -> currentInn != null
            CdnViewMode.FOW -> currentInn != null && currentInn.fallOfWickets.isNotEmpty()
            CdnViewMode.POINTS -> currentPtGrp != null
            CdnViewMode.SQUADS -> currentSqTeam != null && (currentSqTeam.playingXi.isNotEmpty() || currentSqTeam.bench.isNotEmpty())
        }
        if (!hasActiveData) {
            composed = null
            return@LaunchedEffect
        }

        val opponent = when {
            mode == CdnViewMode.SQUADS && currentSqTeam != null ->
                squadsData?.teams?.firstOrNull { it !== currentSqTeam }?.let { it.teamName.ifBlank { it.teamShortName } }
            currentInn != null ->
                matchData?.innings?.firstOrNull { it.inningsId != currentInn.inningsId }?.let { it.teamName.ifBlank { it.teamShortName } }
            else -> null
        }

        composed = withContext(Dispatchers.Default) {
            CdnVectorScoreCardComposer.composeModeFrame(
                context = context,
                mode = mode,
                selected = selectedRow,
                library = library,
                themeIdx = themeIdx,
                innings = currentInn,
                pointsGroup = currentPtGrp,
                pointsSeriesName = pointsData?.seriesName,
                squadTeam = currentSqTeam,
                opponentName = opponent,
                staticRunsTimeMs = 0L,
            )
        }

        val rows = CdnVectorScoreCardComposer.buildRowsForMode(
            mode = mode,
            innings = currentInn,
            pointsGroup = currentPtGrp,
            pointsSeriesName = pointsData?.seriesName,
            squadTeam = currentSqTeam,
        )
        val sel = rows.getOrNull(selectedRow) as? CdnVectorScoreCardComposer.VectorRow.PlayerRow
        if (sel != null) {
            if (mode == CdnViewMode.POINTS) {
                if (!sel.imageId.isNullOrBlank() && CdnScorecardClient.cachedTeamLogo(context, sel.imageId) == null) {
                    if (CdnScorecardClient.ensureTeamLogo(context, workerUrl, sel.imageId)) photoVersion++
                }
            } else {
                val cleanName = sel.name.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
                if (CdnScorecardClient.cachedPlayerPhoto(context, sel.imageId, sel.playerId, cleanName) == null) {
                    val fetched = CdnScorecardClient.ensurePlayerPhoto(context, workerUrl, sel.imageId, sel.playerId, cleanName)
                    if (fetched) photoVersion++
                }
            }
        }
    }

    LaunchedEffect(matchData, inningsIdx) {
        val inn = matchData?.innings?.getOrNull(inningsIdx) ?: return@LaunchedEffect
        CdnScorecardClient.downloadInningsPhotos(context, workerUrl, inn)
        photoVersion++
    }

    LaunchedEffect(squadsData, squadTeamIdx) {
        val tm = squadsData?.teams?.getOrNull(squadTeamIdx) ?: return@LaunchedEffect
        CdnScorecardClient.downloadSquadPhotos(context, workerUrl, tm)
        photoVersion++
    }

    LaunchedEffect(pointsData, pointsGroupIdx) {
        val grp = pointsData?.groups?.getOrNull(pointsGroupIdx) ?: return@LaunchedEffect
        CdnScorecardClient.downloadPointsLogos(context, workerUrl, grp)
        photoVersion++
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = maxOf(navInset, 56.dp)
    val white = TextStyle(color = Color.White)

    val data = matchData
    val currentInnings = data?.innings?.getOrNull(inningsIdx)
    val currentPointsGroup = pointsData?.groups?.getOrNull(pointsGroupIdx)
    val currentSquadTeam = squadsData?.teams?.getOrNull(squadTeamIdx)
    val hasAnyLoaded = data != null || pointsData != null || squadsData != null

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
                        enabled = !loading && !saving && matchInput.isNotBlank() && workerUrl.isNotBlank(),
                        onClick = { performFetch(matchInput) },
                    ) { Text(if (loading) "..." else "Fetch") }
                }

                if (hasAnyLoaded) {
                    // Top Sub-selector row: Innings (for SCORE/BOWL/FOW), Groups (for POINTS), Teams (for SQUADS) + Recent toggle
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (mode) {
                            CdnViewMode.POINTS -> {
                                pointsData?.groups?.forEachIndexed { idx, grp ->
                                    SelectableChip(
                                        label = grp.groupName,
                                        selected = pointsGroupIdx == idx && !showRecentPicker,
                                        onClick = { showRecentPicker = false; pointsGroupIdx = idx; selectedRow = 2 },
                                    )
                                }
                            }
                            CdnViewMode.SQUADS -> {
                                squadsData?.teams?.forEachIndexed { idx, tm ->
                                    SelectableChip(
                                        label = "${tm.teamShortName.ifBlank { tm.teamName }} (${tm.playingXi.size} XI)",
                                        selected = squadTeamIdx == idx && !showRecentPicker,
                                        onClick = { showRecentPicker = false; squadTeamIdx = idx; selectedRow = 2 },
                                    )
                                }
                            }
                            else -> {
                                data?.innings?.forEachIndexed { idx, inn ->
                                    val lbl = "${inn.teamShortName.ifBlank { inn.teamName }} ${inn.score}-${inn.wickets}"
                                    SelectableChip(
                                        label = lbl,
                                        selected = inningsIdx == idx && !showRecentPicker,
                                        onClick = { showRecentPicker = false; inningsIdx = idx; selectedRow = 2 },
                                    )
                                }
                            }
                        }
                        SelectableChip(
                            label = "Matches",
                            selected = showRecentPicker,
                            onClick = {
                                showRecentPicker = !showRecentPicker
                                if (showRecentPicker && recentMatches.isEmpty()) loadRecentMatches()
                            },
                        )
                    }

                    // Mode selector row: SCORE | BOWL | FOW | POINTS | SQUADS | Design X
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CdnViewMode.entries.forEach { m ->
                            SelectableChip(
                                label = m.label,
                                selected = mode == m && !showRecentPicker,
                                onClick = {
                                    showRecentPicker = false
                                    mode = m
                                    selectedRow = 2
                                    errorMsg = null
                                    if (m == CdnViewMode.POINTS) loadPointsForCurrent()
                                    if (m == CdnViewMode.SQUADS) loadSquadsForCurrent()
                                },
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
                        showRecentPicker || !hasAnyLoaded -> {
                            RecentMatchesPanel(
                                matches = recentMatches,
                                loading = loadingRecent,
                                error = recentError,
                                onRefresh = { loadRecentMatches() },
                                onSelectMatch = { rm ->
                                    matchInput = rm.matchId
                                    performFetch(rm.matchId)
                                },
                            )
                        }
                        errorMsg != null -> Text(errorMsg!!, color = Color(0xFFFF6B6B), modifier = Modifier.align(Alignment.Center))
                        mode == CdnViewMode.FOW && currentInnings?.fallOfWickets.isNullOrEmpty() -> Text(
                            "No Fall of Wickets data for this innings.",
                            color = Color(0xFFBBBBBB),
                            modifier = Modifier.align(Alignment.Center),
                        )
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
                                            var bestIdx = -1
                                            var bestDist = Float.MAX_VALUE
                                            comp.rowRects.forEachIndexed { idx, rect ->
                                                if (idx < 2) return@forEachIndexed
                                                val d = when {
                                                    y < rect.top -> rect.top - y
                                                    y > rect.bottom -> y - rect.bottom
                                                    else -> 0f
                                                }
                                                if (d <= 36f && d < bestDist) {
                                                    bestDist = d
                                                    bestIdx = idx
                                                }
                                            }
                                            if (bestIdx >= 2) selectedRow = bestIdx
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

                // Quick-tap player / team chips below preview for effortless selection
                if (!showRecentPicker && hasAnyLoaded) {
                    val quickRows = remember(currentInnings, currentPointsGroup, currentSquadTeam, mode) {
                        CdnVectorScoreCardComposer.buildRowsForMode(
                            mode = mode,
                            innings = currentInnings,
                            pointsGroup = currentPointsGroup,
                            pointsSeriesName = pointsData?.seriesName,
                            squadTeam = currentSquadTeam,
                        )
                    }
                    if (quickRows.size > 2) {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(quickRows) { idx, row ->
                                if (row is CdnVectorScoreCardComposer.VectorRow.PlayerRow) {
                                    val chipLabel = if (mode == CdnViewMode.POINTS) {
                                        row.name
                                    } else {
                                        "${idx - 1}. ${row.name}"
                                    }
                                    SelectableChip(
                                        label = chipLabel,
                                        selected = selectedRow == idx,
                                        onClick = { selectedRow = idx },
                                    )
                                }
                            }
                        }
                    }
                }

                val canAdd = !showRecentPicker && composed != null && !loading && !saving && when (mode) {
                    CdnViewMode.SCORE, CdnViewMode.BOWL, CdnViewMode.FOW -> currentInnings != null
                    CdnViewMode.POINTS -> currentPointsGroup != null
                    CdnViewMode.SQUADS -> currentSquadTeam != null
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        enabled = canAdd,
                        onClick = {
                            saving = true
                            scope.launch {
                                when (mode) {
                                    CdnViewMode.POINTS -> currentPointsGroup?.let {
                                        CdnScorecardClient.downloadPointsLogos(context, workerUrl, it)
                                    }
                                    CdnViewMode.SQUADS -> currentSquadTeam?.let {
                                        CdnScorecardClient.downloadSquadPhotos(context, workerUrl, it)
                                    }
                                    else -> currentInnings?.let {
                                        CdnScorecardClient.downloadInningsPhotos(context, workerUrl, it)
                                    }
                                }

                                val clipId = "sc_${System.currentTimeMillis()}"
                                val dir = File(context.filesDir, "scorecards").apply { mkdirs() }
                                val jsonFile = File(dir, "cdn_score_$clipId.json")
                                withContext(Dispatchers.IO) {
                                    val rootObj = if (rawJson.isNotBlank()) {
                                        runCatching { JSONObject(rawJson) }.getOrElse { JSONObject() }
                                    } else {
                                        JSONObject()
                                    }
                                    if (pointsRawJson.isNotBlank()) {
                                        runCatching { rootObj.put("pointsTable", JSONObject(pointsRawJson)) }
                                    }
                                    if (squadsRawJson.isNotBlank()) {
                                        runCatching { rootObj.put("squads", JSONObject(squadsRawJson)) }
                                    }
                                    jsonFile.writeText(rootObj.toString())
                                }

                                val vRows = CdnVectorScoreCardComposer.buildRowsForMode(
                                    mode = mode,
                                    innings = currentInnings,
                                    pointsGroup = currentPointsGroup,
                                    pointsSeriesName = pointsData?.seriesName,
                                    squadTeam = currentSquadTeam,
                                )
                                val rowItems = vRows.mapIndexed { idx, r ->
                                    val nm = (r as? CdnVectorScoreCardComposer.VectorRow.PlayerRow)?.name
                                        ?.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")?.trim()
                                    ScorecardRowItem(
                                        rowIndex = idx,
                                        srcTop = 0,
                                        srcBottom = 0,
                                        matchedPlayerName = nm,
                                        fallbackName = nm,
                                    )
                                }
                                val safeInitial = selectedRow.coerceIn(0, (rowItems.size - 1).coerceAtLeast(0))
                                val startIdx = when (mode) {
                                    CdnViewMode.POINTS -> pointsGroupIdx.toFloat()
                                    CdnViewMode.SQUADS -> squadTeamIdx.toFloat()
                                    else -> inningsIdx.toFloat()
                                }

                                val scData = ScorecardClipData(
                                    id = clipId,
                                    sourceImageUri = Uri.fromFile(jsonFile).toString(),
                                    cropStartF = startIdx,
                                    cropEndF = 1f,
                                    type = mode.typeKey,
                                    themeIdx = themeIdx,
                                    rows = rowItems,
                                    keyframes = listOf(ScorecardKeyframe(0L, safeInitial)),
                                )
                                ScorecardManager.prepareCard(context, scData, library)
                                saving = false
                                Toast.makeText(context, "Vector ${mode.label} Ready!", Toast.LENGTH_SHORT).show()
                                onDone(scData)
                            }
                        },
                    ) { Text(if (saving) "Preparing..." else "Add to video") }
                }
            }
        }
    }
}

@Composable
private fun RecentMatchesPanel(
    matches: List<CdnRecentMatch>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelectMatch: (CdnRecentMatch) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cricbuzz Live & Recent Matches",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onRefresh,
                enabled = !loading,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh matches",
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        when {
            loading && matches.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            error != null && matches.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$error\n\nTap Refresh or enter any Match ID above.",
                    color = Color(0xFFBBBBBB),
                    fontSize = 13.sp,
                )
            }
            matches.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Enter any live or completed Cricbuzz Match ID / Link and tap Fetch.",
                    color = Color(0xFFBBBBBB),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(matches, key = { it.matchId }) { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1B1C22))
                            .border(
                                width = 1.dp,
                                color = if (m.isLive) Color(0xFFE5173F) else Color(0xFF2D303A),
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelectMatch(m) }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (m.isLive) Color(0xFFE5173F) else Color(0xFF2A2E39))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = if (m.isLive) "LIVE" else (m.matchFormat.ifBlank { "RECENT" }),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = listOf(m.seriesName, m.matchDesc).filter { it.isNotBlank() }.joinToString(" • "),
                                color = Color(0xFF9EA5B5),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "#${m.matchId}",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = m.team1,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            if (m.team1Score.isNotBlank()) {
                                Text(
                                    text = m.team1Score,
                                    color = Color(0xFFFFE600),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        if (m.team2.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = m.team2,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (m.team2Score.isNotBlank()) {
                                    Text(
                                        text = m.team2Score,
                                        color = Color(0xFFFFE600),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }

                        if (m.status.isNotBlank()) {
                            Text(
                                text = m.status,
                                color = if (m.isLive) Color(0xFFFF6B81) else Color(0xFF81C784),
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
