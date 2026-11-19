package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class CdnViewMode(val label: String, val typeKey: String) {
    SCORE("SCORE", "BATTING"),
    BOWL("BOWL", "BOWLING"),
    FOW("FOW", "FOW"),
    POINTS("POINTS", "POINTS"),
    SQUADS("SQUADS", "SQUADS");

    companion object {
        fun fromTypeKey(key: String): CdnViewMode = when (key.uppercase()) {
            "BOWLING", "BOWL" -> BOWL
            "FOW" -> FOW
            "POINTS" -> POINTS
            "SQUADS", "SQUAD" -> SQUADS
            else -> SCORE
        }
    }
}

/**
 * 4K-crisp Broadcast Vector Renderer for CDN/JSON Cricbuzz Scorecards,
 * Fall of Wickets (FOW), Points Table, and Squads (Playing XI + Bench).
 */
object CdnVectorScoreCardComposer {

    private const val CDN_LIST_LEFT = 24f
    private const val CDN_LIST_WIDTH = 816f
    private const val CDN_PANEL_LEFT = 956f // 116px clean gap for bouncing arrow

    private val POP_RUN_GRADIENTS = arrayOf(
        intArrayOf(0xFF0052D4.toInt(), 0xFF4364F7.toInt(), 0xFF00C6FF.toInt()),
        intArrayOf(0xFFFF416C.toInt(), 0xFFFF4B2B.toInt(), 0xFFFF9068.toInt()),
        intArrayOf(0xFF7F00FF.toInt(), 0xFFE100FF.toInt(), 0xFFFF5CA8.toInt()),
        intArrayOf(0xFF00B09B.toInt(), 0xFF28DF99.toInt(), 0xFF96C93D.toInt()),
        intArrayOf(0xFFFC00FF.toInt(), 0xFFFF007A.toInt(), 0xFFFF8A00.toInt()),
        intArrayOf(0xFFFF8008.toInt(), 0xFFFF512F.toInt(), 0xFFFFC837.toInt()),
    )

    private val NEON_RUN_GRADIENTS = arrayOf(
        intArrayOf(0xFF00F5FF.toInt(), 0xFF38BDF8.toInt(), 0xFF4DFF9E.toInt()),
        intArrayOf(0xFFFF6BD6.toInt(), 0xFFFF4081.toInt(), 0xFFFFD400.toInt()),
        intArrayOf(0xFFFFB300.toInt(), 0xFFFF6D00.toInt(), 0xFFFFEA00.toInt()),
        intArrayOf(0xFFEA80FC.toInt(), 0xFFB388FF.toInt(), 0xFF00E5FF.toInt()),
        intArrayOf(0xFF76FF03.toInt(), 0xFF00E676.toInt(), 0xFF00E5FF.toInt()),
        intArrayOf(0xFFFF5252.toInt(), 0xFFFF8A65.toInt(), 0xFFFFD54F.toInt()),
    )

    private val GOLD_PILL_GRADIENTS = arrayOf(
        intArrayOf(0xFF00897B.toInt(), 0xFF004D40.toInt()),
        intArrayOf(0xFF1E88E5.toInt(), 0xFF0D47A1.toInt()),
        intArrayOf(0xFFD81B60.toInt(), 0xFF880E4F.toInt()),
        intArrayOf(0xFF8E24AA.toInt(), 0xFF4A148C.toInt()),
        intArrayOf(0xFFF4511E.toInt(), 0xFFBF360C.toInt()),
        intArrayOf(0xFF00ACC1.toInt(), 0xFF006064.toInt()),
    )

    sealed interface VectorRow {
        data class Header(val leftTitle: String, val scorePart: String, val oversPart: String) : VectorRow
        data class Columns(val col0: String, val stats: List<String>) : VectorRow
        data class PlayerRow(
            val playerId: String?,
            val name: String,
            val subText: String,
            val primaryStat: String,
            val otherStats: List<String>,
            val imageId: String?,
            val badgeText: String = "",
            val extraTag: String = "",
        ) : VectorRow
    }

    data class SavedCdnBundle(
        val match: CdnMatchScorecard?,
        val pointsTable: CdnPointsTable?,
        val squads: CdnMatchSquads?,
    )

    fun buildRows(innings: CdnInnings, type: ScoreCardType): List<VectorRow> =
        buildRowsForMode(
            mode = if (type == ScoreCardType.BOWLING) CdnViewMode.BOWL else CdnViewMode.SCORE,
            innings = innings,
        )

    fun buildRowsForMode(
        mode: CdnViewMode,
        innings: CdnInnings? = null,
        pointsGroup: CdnPointsGroup? = null,
        pointsSeriesName: String? = null,
        squadTeam: CdnSquadTeam? = null,
    ): List<VectorRow> = when (mode) {
        CdnViewMode.SCORE -> {
            val inn = innings ?: return emptyList()
            val teamLabel = inn.teamShortName.ifBlank { inn.teamName }
            val decSuffix = if (inn.isDeclared) "d" else ""
            val scorePart = "${inn.score}-${inn.wickets}$decSuffix"
            val oversPart = "(${inn.overs} Ov)"
            buildList {
                add(VectorRow.Header(teamLabel, scorePart, oversPart))
                add(VectorRow.Columns("BATTER", listOf("R", "B", "4s", "6s", "SR")))
                inn.batters.forEach { b ->
                    add(
                        VectorRow.PlayerRow(
                            playerId = b.id,
                            name = b.name,
                            subText = b.outDesc,
                            primaryStat = b.runs.toString(),
                            otherStats = listOf(b.balls.toString(), b.fours.toString(), b.sixes.toString(), b.sr),
                            imageId = b.imageId,
                        ),
                    )
                }
            }
        }

        CdnViewMode.BOWL -> {
            val inn = innings ?: return emptyList()
            val teamLabel = inn.teamShortName.ifBlank { inn.teamName }
            val decSuffix = if (inn.isDeclared) "d" else ""
            val scorePart = "${inn.score}-${inn.wickets}$decSuffix"
            val oversPart = "(${inn.overs} Ov)"
            buildList {
                add(VectorRow.Header("$teamLabel BOWLING", scorePart, oversPart))
                add(VectorRow.Columns("BOWLER", listOf("O", "M", "R", "W", "ECO")))
                inn.bowlers.forEach { bw ->
                    add(
                        VectorRow.PlayerRow(
                            playerId = bw.id,
                            name = bw.name,
                            subText = "",
                            primaryStat = bw.wickets.toString(),
                            otherStats = listOf(bw.overs, bw.maidens.toString(), bw.runs.toString(), bw.econ),
                            imageId = bw.imageId,
                        ),
                    )
                }
            }
        }

        CdnViewMode.FOW -> {
            val inn = innings ?: return emptyList()
            val teamLabel = inn.teamShortName.ifBlank { inn.teamName }
            val decSuffix = if (inn.isDeclared) "d" else ""
            val scorePart = "${inn.score}-${inn.wickets}$decSuffix"
            val oversPart = "(${inn.overs} Ov)"
            buildList {
                add(VectorRow.Header("$teamLabel FOW", scorePart, oversPart))
                add(VectorRow.Columns("FALL OF WICKETS", listOf("SCORE", "OVER", "WKT")))
                inn.fallOfWickets.forEach { fw ->
                    add(
                        VectorRow.PlayerRow(
                            playerId = fw.id,
                            name = fw.name,
                            subText = fw.outDesc.ifBlank { "Wicket #${fw.wicketNumber} at ${fw.overs} Ov" },
                            primaryStat = fw.score,
                            otherStats = listOf(fw.overs, "#${fw.wicketNumber}"),
                            imageId = fw.imageId,
                            badgeText = "WKT ${fw.wicketNumber}",
                        ),
                    )
                }
            }
        }

        CdnViewMode.POINTS -> {
            val grp = pointsGroup ?: return emptyList()
            val title = pointsSeriesName?.takeIf { it.isNotBlank() } ?: "POINTS TABLE"
            buildList {
                add(VectorRow.Header(title, grp.groupName, "(${grp.teams.size} Teams)"))
                add(VectorRow.Columns("TEAM", listOf("M", "W", "L", "PTS", "NRR")))
                grp.teams.forEach { tm ->
                    val sub = buildList {
                        if (tm.qualified) add("Qualified (Q)")
                        else add("Rank #${tm.pos}")
                        if (tm.tied > 0) add("T:${tm.tied}")
                        if (tm.nr > 0) add("NR:${tm.nr}")
                    }.joinToString("  •  ")
                    add(
                        VectorRow.PlayerRow(
                            playerId = tm.teamId,
                            name = "${tm.pos}. ${tm.teamName}",
                            subText = sub,
                            primaryStat = tm.points.toString(),
                            otherStats = listOf(
                                tm.matchesPlayed.toString(),
                                tm.won.toString(),
                                tm.lost.toString(),
                                tm.nrr,
                            ),
                            imageId = tm.imageId,
                            badgeText = tm.teamFullName.ifBlank { tm.teamName },
                            extraTag = tm.form.joinToString(" "),
                        ),
                    )
                }
            }
        }

        CdnViewMode.SQUADS -> {
            val tm = squadTeam ?: return emptyList()
            val teamLabel = tm.teamShortName.ifBlank { tm.teamName }
            val benchPart = if (tm.bench.isNotEmpty()) "(+${tm.bench.size} Bench)" else ""
            buildList {
                add(VectorRow.Header("$teamLabel SQUAD", "${tm.playingXi.size} XI", benchPart))
                add(VectorRow.Columns("PLAYER", listOf("TAG")))
                (tm.playingXi + tm.bench).forEach { pl ->
                    val roleLine = buildList {
                        if (pl.role.isNotBlank()) add(pl.role)
                        add(if (pl.isSubstitute) "Bench" else "Playing XI")
                    }.joinToString("  •  ")
                    val tag = when {
                        pl.isCaptain && pl.isWk -> "C/WK"
                        pl.isCaptain -> "C"
                        pl.isWk -> "WK"
                        pl.isSubstitute -> "SUB"
                        else -> "XI"
                    }
                    add(
                        VectorRow.PlayerRow(
                            playerId = pl.id,
                            name = pl.name,
                            subText = roleLine,
                            primaryStat = tag,
                            otherStats = emptyList(),
                            imageId = pl.imageId,
                            badgeText = if (pl.isSubstitute) "BENCH" else "XI",
                        ),
                    )
                }
            }
        }
    }

    fun composeFrame(
        context: Context,
        innings: CdnInnings,
        type: ScoreCardType,
        selected: Int,
        library: PlayerLibrary,
        themeIdx: Int = 0,
        opponentName: String? = null,
        staticRunsTimeMs: Long? = null,
    ): ComposedCard = composeModeFrame(
        context = context,
        mode = if (type == ScoreCardType.BOWLING) CdnViewMode.BOWL else CdnViewMode.SCORE,
        selected = selected,
        library = library,
        themeIdx = themeIdx,
        innings = innings,
        opponentName = opponentName,
        staticRunsTimeMs = staticRunsTimeMs,
    )

    fun composeModeFrame(
        context: Context,
        mode: CdnViewMode,
        selected: Int,
        library: PlayerLibrary,
        themeIdx: Int = 0,
        innings: CdnInnings? = null,
        pointsGroup: CdnPointsGroup? = null,
        pointsSeriesName: String? = null,
        squadTeam: CdnSquadTeam? = null,
        opponentName: String? = null,
        staticRunsTimeMs: Long? = null,
    ): ComposedCard {
        val theme = ScoreCardComposer.normTheme(themeIdx)
        val rows = buildRowsForMode(mode, innings, pointsGroup, pointsSeriesName, squadTeam)
        val w = ScoreCardComposer.CANVAS_W
        val h = ScoreCardComposer.CANVAS_H
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        drawStadiumBackdrop(c, p, w, h)

        val listLeft = CDN_LIST_LEFT
        val listWidth = CDN_LIST_WIDTH
        val footerH = 52f
        val footerTop = h - 26f - footerH
        val gap = 8f

        val headerH = 76f
        val colsH = 44f
        val headerRect = RectF(listLeft, 26f, listLeft + listWidth, 26f + headerH)
        val colsRect = RectF(listLeft, headerRect.bottom + gap, listLeft + listWidth, headerRect.bottom + gap + colsH)

        val playersTop = colsRect.bottom + gap
        val playersBottom = footerTop - 10f
        val playersAreaH = (playersBottom - playersTop).coerceAtLeast(100f)

        val playerRows = rows.drop(2)
        val playerHeights = playerRows.map { r ->
            val pr = r as VectorRow.PlayerRow
            if (pr.subText.isNotBlank()) 106f else 78f
        }
        val totalPlayersH = playerHeights.sum() + gap * max(0, playerRows.size - 1)

        val selPlayerIdx = (selected - 2).coerceIn(0, max(0, playerRows.size - 1))
        val startY = if (totalPlayersH <= playersAreaH) {
            playersTop
        } else {
            var activeTop = 0f
            for (i in 0 until selPlayerIdx) {
                activeTop += playerHeights[i] + gap
            }
            val activeH = playerHeights.getOrElse(selPlayerIdx) { 106f }
            val desiredTop = activeTop - (playersAreaH - activeH) / 2f
            val maxScroll = totalPlayersH - playersAreaH
            playersTop - desiredTop.coerceIn(0f, maxScroll)
        }

        val renderedRowRects = ArrayList<RectF>(rows.size)
        renderedRowRects.add(RectF(headerRect))
        renderedRowRects.add(RectF(colsRect))

        c.save()
        c.clipRect(listLeft - 12f, playersTop - 4f, listLeft + listWidth + 12f, playersBottom + 4f)
        var currY = startY
        playerRows.forEachIndexed { pIdx, row ->
            val rh = playerHeights[pIdx]
            val rowRect = RectF(listLeft, currY, listLeft + listWidth, currY + rh)
            renderedRowRects.add(RectF(rowRect))
            val isSel = (pIdx + 2) == selected
            val rInfo = RowInfo(rowRect, 0, 0, isHeader = false, isColumnHeader = false)
            ScoreCardComposer.drawRowContainer(c, p, theme, rowRect, rInfo, isSel, pIdx)
            drawVectorRowContent(c, p, rowRect, row, theme, isSel, mode, pIdx)
            currY += rh + gap
        }
        c.restore()

        rows.getOrNull(0)?.let { hdr ->
            val rInfo = RowInfo(headerRect, 0, 0, isHeader = true, isColumnHeader = false)
            ScoreCardComposer.drawRowContainer(c, p, theme, headerRect, rInfo, false, 0)
            drawVectorRowContent(c, p, headerRect, hdr, theme, false, mode, 0)
        }
        rows.getOrNull(1)?.let { col ->
            val rInfo = RowInfo(colsRect, 0, 0, isHeader = false, isColumnHeader = true)
            ScoreCardComposer.drawRowContainer(c, p, theme, colsRect, rInfo, false, 0)
            drawVectorRowContent(c, p, colsRect, col, theme, false, mode, 0)
        }

        val footerRect = RectF(listLeft, footerTop, listLeft + listWidth, footerTop + footerH)
        drawFooterBarForMode(c, p, footerRect, mode, innings, pointsGroup, squadTeam, theme)

        val selectedRow = rows.getOrNull(selected) as? VectorRow.PlayerRow

        if (mode == CdnViewMode.POINTS) {
            val selTeam = pointsGroup?.teams?.getOrNull(selPlayerIdx)
            val teamLogo = CdnScorecardClient.cachedTeamLogo(context, selTeam?.imageId)
            if (theme == 2) {
                drawPopPointsTeamPanel(c, p, bmpPaint, pointsGroup, pointsSeriesName, selTeam, teamLogo)
            } else {
                drawPointsTeamPanel(c, p, bmpPaint, theme, pointsGroup, pointsSeriesName, selTeam, teamLogo)
                if (staticRunsTimeMs != null && selTeam != null) {
                    ScoreCardComposer.drawAnimatedRuns3D(
                        canvas = c,
                        runsText = selTeam.points.toString(),
                        labelText = "PTS",
                        timeInClipMs = staticRunsTimeMs,
                        themeIdx = theme,
                        panelLeft = CDN_PANEL_LEFT,
                    )
                }
            }
        } else {
            val allPlayers = library.all()
            val cleanLookupName = selectedRow?.name?.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")?.trim()
            val matchedPlayer = cleanLookupName?.let { ScoreNameOcr.matchPlayer(it, allPlayers) }
            val photo = matchedPlayer?.let { library.photo(it) }
                ?: CdnScorecardClient.cachedPlayerPhoto(
                    context = context,
                    imageId = selectedRow?.imageId,
                    playerId = selectedRow?.playerId,
                    playerName = cleanLookupName ?: selectedRow?.name,
                )

            val panelType = if (mode == CdnViewMode.BOWL) ScoreCardType.BOWLING else ScoreCardType.BATTING
            val teamContext = innings ?: CdnInnings(
                inningsId = 1,
                teamName = squadTeam?.teamName ?: "",
                teamShortName = squadTeam?.teamShortName ?: "",
                score = 0,
                wickets = 0,
                overs = "0",
                isDeclared = false,
                batters = emptyList(),
                bowlers = emptyList(),
            )

            if (theme == 2) {
                drawPopStarPerformerPanel(
                    c = c,
                    p = p,
                    bmpPaint = bmpPaint,
                    mode = mode,
                    innings = teamContext,
                    type = panelType,
                    selectedRow = selectedRow,
                    player = matchedPlayer,
                    photo = photo,
                    opponentName = opponentName,
                    fowItem = if (mode == CdnViewMode.FOW) innings?.fallOfWickets?.getOrNull(selPlayerIdx) else null,
                    squadPlayer = if (mode == CdnViewMode.SQUADS) (squadTeam?.playingXi.orEmpty() + squadTeam?.bench.orEmpty()).getOrNull(selPlayerIdx) else null,
                )
            } else {
                ScoreCardComposer.drawPlayerPanel(
                    c = c,
                    p = p,
                    bmpPaint = bmpPaint,
                    theme = theme,
                    type = panelType,
                    player = matchedPlayer,
                    photo = photo,
                    fallbackName = selectedRow?.name,
                    panelLeft = CDN_PANEL_LEFT,
                    reserveRightForRuns = (selectedRow != null),
                )
                if (staticRunsTimeMs != null && selectedRow != null) {
                    val badgePair = animatedBadgeForMode(mode, selectedRow)
                    ScoreCardComposer.drawAnimatedRuns3D(
                        canvas = c,
                        runsText = badgePair.first,
                        labelText = badgePair.second,
                        timeInClipMs = staticRunsTimeMs,
                        themeIdx = theme,
                        panelLeft = CDN_PANEL_LEFT,
                    )
                }
            }
        }

        return ComposedCard(out, renderedRowRects)
    }

    fun animatedBadgeForMode(mode: CdnViewMode, row: VectorRow.PlayerRow): Pair<String, String> = when (mode) {
        CdnViewMode.SCORE -> row.primaryStat to "RUNS"
        CdnViewMode.BOWL -> row.primaryStat to "WKTS"
        CdnViewMode.FOW -> row.primaryStat to "WKT ${row.otherStats.getOrElse(1) { "" }.removePrefix("#")}".trim()
        CdnViewMode.POINTS -> row.primaryStat to "PTS"
        CdnViewMode.SQUADS -> row.primaryStat to (row.badgeText.ifBlank { "SQUAD" })
    }

    private fun drawPointsTeamPanel(
        c: Canvas,
        p: Paint,
        bmpPaint: Paint,
        theme: Int,
        group: CdnPointsGroup?,
        seriesName: String?,
        team: CdnPointsTeam?,
        logo: Bitmap?,
    ) {
        val pl = CDN_PANEL_LEFT
        val pr = ScoreCardComposer.CANVAS_W - 28f
        val pt = 28f
        val pb = ScoreCardComposer.CANVAS_H - 28f
        val pw = pr - pl
        val ph = pb - pt
        val panelRect = RectF(pl, pt, pr, pb)
        val corner = if (theme == 0) 22f else 26f

        p.shader = RadialGradient(
            pl + pw * 0.5f, pt + ph * 0.35f, pw * 0.85f,
            if (theme == 0) {
                intArrayOf(0xFF1B4580.toInt(), 0xFF0C1D3B.toInt(), 0xFF030814.toInt())
            } else {
                intArrayOf(0xFF3A101E.toInt(), 0xFF1A070E.toInt(), 0xFF0B0306.toInt())
            },
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(panelRect, corner, corner, p)
        p.shader = null

        c.save()
        c.clipPath(Path().apply { addRoundRect(panelRect, corner, corner, Path.Direction.CW) })

        val accent = if (theme == 0) 0xFF00E5FF.toInt() else 0xFFF6D77A.toInt()

        // Top badges
        val badgeW = pw * 0.30f
        val badgeH = ph * 0.055f
        val badgeRect = RectF(pl + pw * 0.04f, pt + ph * 0.035f, pl + pw * 0.04f + badgeW, pt + ph * 0.035f + badgeH)
        p.color = if (theme == 0) 0xFFE50000.toInt() else 0xFFF6D77A.toInt()
        c.drawRoundRect(badgeRect, 10f, 10f, p)
        p.color = if (theme == 0) Color.WHITE else 0xFF2A0710.toInt()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = badgeH * 0.54f
        p.textAlign = Paint.Align.CENTER
        c.drawText("⚡ POINTS TABLE", badgeRect.centerX(), badgeRect.centerY() + p.textSize * 0.35f, p)

        val rankLabel = if (team?.qualified == true) "RANK #${team.pos} • QUALIFIED" else "RANK #${team?.pos ?: 1}"
        p.textSize = 22f
        val rankW = p.measureText(rankLabel) + 40f
        val rankRect = RectF(pr - pw * 0.04f - rankW, badgeRect.top, pr - pw * 0.04f, badgeRect.bottom)
        p.color = if (team?.qualified == true) 0xFF0E6B3A.toInt() else 0xCC0A1224.toInt()
        c.drawRoundRect(rankRect, badgeH / 2f, badgeH / 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = if (team?.qualified == true) 0xFF4DFF88.toInt() else accent
        c.drawRoundRect(rankRect, badgeH / 2f, badgeH / 2f, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        c.drawText(rankLabel, rankRect.centerX(), rankRect.centerY() + 8f, p)

        // Center Team Crest / Logo
        val crestCy = pt + ph * 0.34f
        val crestCx = pl + pw / 2f
        val crestR = 152f

        p.shader = RadialGradient(
            crestCx, crestCy, crestR * 1.45f,
            intArrayOf((0x44 shl 24) or (accent and 0x00FFFFFF), 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
        c.drawCircle(crestCx, crestCy, crestR * 1.45f, p)
        p.shader = null

        if (logo != null && logo.width > 0 && logo.height > 0) {
            val lSize = crestR * 1.75f
            val sc = min(lSize / logo.width, lSize / logo.height)
            val dw = logo.width * sc
            val dh = logo.height * sc
            c.drawBitmap(logo, null, RectF(crestCx - dw / 2f, crestCy - dh / 2f, crestCx + dw / 2f, crestCy + dh / 2f), bmpPaint)
        } else {
            p.style = Paint.Style.FILL
            p.color = if (theme == 0) 0xFF0A214A.toInt() else 0xFF2B0A16.toInt()
            c.drawCircle(crestCx, crestCy, crestR, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.color = accent
            c.drawCircle(crestCx, crestCy, crestR, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.textSize = 72f
            val shortCode = (team?.teamName ?: "TEAM").uppercase().take(5)
            while (p.measureText(shortCode) > crestR * 1.55f && p.textSize > 32f) p.textSize -= 2f
            c.drawText(shortCode, crestCx, crestCy + p.textSize * 0.34f, p)
        }

        // Quick 4-stat cards (M, W, L, NRR)
        val statRowTop = pt + ph * 0.54f
        val statRowH = 112f
        val statGap = 14f
        val sLeft = pl + pw * 0.04f
        val sW = (pw * 0.92f - statGap * 3) / 4f
        val statPairs = listOf(
            (team?.matchesPlayed?.toString() ?: "0") to "MATCHES",
            (team?.won?.toString() ?: "0") to "WON",
            (team?.lost?.toString() ?: "0") to "LOST",
            (team?.nrr ?: "0.000") to "NET RR",
        )
        statPairs.forEachIndexed { idx, (valStr, lbl) ->
            val sx = sLeft + idx * (sW + statGap)
            val r = RectF(sx, statRowTop, sx + sW, statRowTop + statRowH)
            p.color = 0xAA070B18.toInt()
            c.drawRoundRect(r, 16f, 16f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.6f
            p.color = (0x55 shl 24) or (accent and 0x00FFFFFF)
            c.drawRoundRect(r, 16f, 16f, p)
            p.style = Paint.Style.FILL
            p.color = if (idx == 1) 0xFF4DFF88.toInt() else if (idx == 2) 0xFFFF6B81.toInt() else Color.WHITE
            p.textSize = 36f
            while (p.measureText(valStr) > sW - 14f && p.textSize > 20f) p.textSize -= 2f
            c.drawText(valStr, r.centerX(), r.top + 54f, p)
            p.color = accent
            p.textSize = 16f
            c.drawText(lbl, r.centerX(), r.bottom - 20f, p)
        }

        // Recent Form pills if available
        val formList = team?.form.orEmpty()
        if (formList.isNotEmpty()) {
            val fy = statRowTop + statRowH + 34f
            p.textAlign = Paint.Align.LEFT
            p.color = 0xFFB0BCD4.toInt()
            p.textSize = 20f
            c.drawText("FORM:", sLeft, fy + 8f, p)
            var fx = sLeft + 92f
            formList.take(5).forEach { f ->
                val fColor = when (f.uppercase()) {
                    "W" -> 0xFF1DB954.toInt()
                    "L" -> 0xFFE5173F.toInt()
                    else -> 0xFF5A6578.toInt()
                }
                p.color = fColor
                c.drawCircle(fx, fy, 18f, p)
                p.color = Color.WHITE
                p.textAlign = Paint.Align.CENTER
                p.textSize = 18f
                c.drawText(f.uppercase().take(1), fx, fy + 6f, p)
                fx += 46f
            }
        }

        // Bottom Nameplate (leaving right space for 3D PTS badge)
        val npLeft = pl + pw * 0.035f
        val npRight = pr - pw * 0.035f
        val npTop = pb - ph * 0.21f
        val npBottom = pb - ph * 0.03f
        val npRect = RectF(npLeft, npTop, npRight, npBottom)
        p.color = 0xA8050914.toInt()
        c.drawRoundRect(npRect, 16f, 16f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.color = (0x55 shl 24) or (accent and 0x00FFFFFF)
        c.drawRoundRect(npRect, 16f, 16f, p)

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
        p.color = accent
        p.textSize = ph * 0.028f
        val subGrp = (seriesName?.takeIf { it.isNotBlank() } ?: group?.groupName ?: "STANDINGS").uppercase()
        c.drawText("● $subGrp", npLeft + pw * 0.035f, npTop + ph * 0.052f, p)

        p.color = Color.WHITE
        p.textSize = ph * 0.058f
        val fullTeamName = (team?.teamFullName?.ifBlank { team.teamName } ?: "TEAM").uppercase()
        val maxNmW = (npRect.width() - pw * 0.07f - 220f).coerceAtLeast(120f)
        while (p.measureText(fullTeamName) > maxNmW && p.textSize > 20f) p.textSize -= 2f
        c.drawText(fullTeamName, npLeft + pw * 0.035f, npTop + ph * 0.125f, p)

        c.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = 4.5f
        p.color = accent
        c.drawRoundRect(panelRect, corner, corner, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawPopPointsTeamPanel(
        c: Canvas,
        p: Paint,
        bmpPaint: Paint,
        group: CdnPointsGroup?,
        seriesName: String?,
        team: CdnPointsTeam?,
        logo: Bitmap?,
    ) {
        val pl = CDN_PANEL_LEFT
        val pr = ScoreCardComposer.CANVAS_W - 28f
        val pt = 28f
        val pb = ScoreCardComposer.CANVAS_H - 28f
        val pw = pr - pl
        val ph = pb - pt
        val panelRect = RectF(pl, pt, pr, pb)
        val corner = 24f

        val splitY = pt + ph * 0.575f
        val bandH = 38f
        val bandBottom = splitY + bandH

        c.save()
        c.clipPath(Path().apply { addRoundRect(panelRect, corner, corner, Path.Direction.CW) })

        p.shader = RadialGradient(
            pl + pw * 0.5f, pt + (splitY - pt) * 0.45f, pw * 0.78f,
            intArrayOf(0xFF1B4D3E.toInt(), 0xFF0A241C.toInt(), 0xFF04140F.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(pl, pt, pr, splitY, p)
        p.shader = null

        val cx = pl + pw / 2f
        val cy = pt + (splitY - pt) * 0.52f
        if (logo != null && logo.width > 0 && logo.height > 0) {
            val lSize = min(pw * 0.55f, (splitY - pt) * 0.70f)
            val sc = min(lSize / logo.width, lSize / logo.height)
            val dw = logo.width * sc
            val dh = logo.height * sc
            c.drawBitmap(logo, null, RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f), bmpPaint)
        } else {
            val r = 155f
            p.color = 0xFF06382C.toInt()
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.color = 0xFFD4FF33.toInt()
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            p.textSize = 72f
            val code = (team?.teamName ?: "TEAM").uppercase().take(5)
            c.drawText(code, cx, cy + 24f, p)
        }

        // Top badge
        val badgeRect = RectF(pl + 20f, pt + 20f, pl + 20f + pw * 0.28f, pt + 62f)
        p.color = 0xE6052D22.toInt()
        c.drawRoundRect(badgeRect, 12f, 12f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0xFFD4FF33.toInt()
        c.drawRoundRect(badgeRect, 12f, 12f, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.textSize = 20f
        p.textAlign = Paint.Align.CENTER
        c.drawText("⚡ POINTS TABLE", badgeRect.centerX(), badgeRect.centerY() + 7f, p)

        // Hot-pink band
        p.color = 0xFFFF007A.toInt()
        c.drawRect(pl, splitY, pr, bandBottom, p)
        p.color = Color.WHITE
        p.textSize = 18f
        val bandText = (seriesName?.takeIf { it.isNotBlank() } ?: group?.groupName ?: "TOURNAMENT STANDINGS").uppercase()
        c.drawText("$bandText  •  RANK #${team?.pos ?: 1}", pl + pw / 2f, splitY + bandH * 0.68f, p)

        // Bottom emerald section
        p.color = 0xFF042D23.toInt()
        c.drawRect(pl, bandBottom, pr, pb, p)

        val fullName = (team?.teamFullName?.ifBlank { team.teamName } ?: "TEAM").uppercase()
        p.textAlign = Paint.Align.LEFT
        p.color = 0xFFD4FF33.toInt()
        p.textSize = 46f
        while (p.measureText(fullName) > pw - 60f && p.textSize > 22f) p.textSize -= 2f
        c.drawText(fullName, pl + 28f, bandBottom + 80f, p)

        p.color = Color.WHITE
        p.textSize = 24f
        val qualLine = if (team?.qualified == true) "STATUS: QUALIFIED (Q)" else "GROUP: ${group?.groupName ?: "STANDINGS"}"
        c.drawText(qualLine, pl + 28f, bandBottom + 116f, p)

        val statCols = listOf(
            (team?.points?.toString() ?: "0") to "POINTS",
            (team?.matchesPlayed?.toString() ?: "0") to "MATCHES",
            (team?.won?.toString() ?: "0") to "WON",
            (team?.lost?.toString() ?: "0") to "LOST",
            (team?.nrr ?: "0.000") to "NRR",
        )
        val stripLeft = pl + 24f
        val stripRight = pr - 24f
        val stripTop = bandBottom + 158f
        val stripBottom = pb - 24f
        val colGap = 6f
        val colW = (stripRight - stripLeft - colGap * 4) / 5f
        statCols.forEachIndexed { idx, (value, label) ->
            val bx0 = stripLeft + idx * (colW + colGap)
            val boxRect = RectF(bx0, stripTop, bx0 + colW, stripBottom)
            p.color = if (idx % 2 == 0) 0xFFFF007A.toInt() else 0x44031F18
            c.drawRoundRect(boxRect, 6f, 6f, p)
            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.textSize = 46f
            while (p.measureText(value) > colW - 12f && p.textSize > 22f) p.textSize -= 2f
            c.drawText(value, boxRect.centerX(), boxRect.top + boxRect.height() * 0.56f, p)
            p.textSize = 18f
            c.drawText(label, boxRect.centerX(), boxRect.top + boxRect.height() * 0.84f, p)
        }

        c.restore()
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = 0xCCFFFFFF.toInt()
        c.drawRoundRect(panelRect, corner, corner, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawPopStarPerformerPanel(
        c: Canvas,
        p: Paint,
        bmpPaint: Paint,
        mode: CdnViewMode,
        innings: CdnInnings,
        type: ScoreCardType,
        selectedRow: VectorRow.PlayerRow?,
        player: Player?,
        photo: Bitmap?,
        opponentName: String?,
        fowItem: CdnFow? = null,
        squadPlayer: CdnSquadPlayer? = null,
    ) {
        val pl = CDN_PANEL_LEFT
        val pr = ScoreCardComposer.CANVAS_W - 28f
        val pt = 28f
        val pb = ScoreCardComposer.CANVAS_H - 28f
        val pw = pr - pl
        val ph = pb - pt
        val panelRect = RectF(pl, pt, pr, pb)
        val corner = 24f

        val splitY = pt + ph * 0.575f
        val bandH = 38f
        val bandBottom = splitY + bandH

        c.save()
        c.clipPath(Path().apply { addRoundRect(panelRect, corner, corner, Path.Direction.CW) })

        val displayName = (player?.name ?: selectedRow?.name ?: if (type == ScoreCardType.BOWLING) "BOWLER" else "BATTER")
            .trim()
            .uppercase()

        if (photo != null && photo.width > 0 && photo.height > 0) {
            val leftEdge = sampleEdgeColor(photo, isLeft = true)
            val rightEdge = sampleEdgeColor(photo, isLeft = false)

            if (leftEdge != null && rightEdge != null) {
                p.shader = LinearGradient(
                    pl, 0f, pr, 0f,
                    intArrayOf(darkenColor(leftEdge, 0.90f), leftEdge, rightEdge, darkenColor(rightEdge, 0.90f)),
                    floatArrayOf(0f, 0.22f, 0.78f, 1f),
                    Shader.TileMode.CLAMP,
                )
                c.drawRect(pl, pt, pr, splitY, p)
                p.shader = null
            } else {
                p.shader = RadialGradient(
                    pl + pw * 0.5f, pt + (splitY - pt) * 0.4f, pw * 0.8f,
                    intArrayOf(0xFF1B4D3E.toInt(), 0xFF0A241C.toInt()),
                    null, Shader.TileMode.CLAMP,
                )
                c.drawRect(pl, pt, pr, splitY, p)
                p.shader = null
            }

            val photoH = splitY - pt
            val scale = min(pw / photo.width, photoH / photo.height)
            val dw = photo.width * scale
            val dh = photo.height * scale
            val dl = pl + (pw - dw) / 2f
            val dt = splitY - dh
            c.save()
            c.clipRect(pl, pt, pr, splitY)
            c.drawBitmap(photo, null, RectF(dl, dt, dl + dw, dt + dh), bmpPaint)
            c.restore()
        } else {
            p.shader = RadialGradient(
                pl + pw * 0.5f, pt + (splitY - pt) * 0.45f, pw * 0.75f,
                intArrayOf(0xFF1B4D3E.toInt(), 0xFF0A241C.toInt(), 0xFF04140F.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(pl, pt, pr, splitY, p)
            p.shader = null

            ScoreCardComposer.drawNewsCrickNoPhotoEmblem(
                c = c,
                p = p,
                area = RectF(pl, pt + 22f, pr, splitY - 10f),
                theme = 2,
                isBowling = (type == ScoreCardType.BOWLING),
                playerName = displayName,
            )
        }

        p.shader = LinearGradient(
            pl, 0f, pr, 0f,
            intArrayOf(0x28000000, 0x00000000, 0x00000000, 0x28000000),
            floatArrayOf(0f, 0.16f, 0.84f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(pl, pt, pr, splitY, p)
        p.shader = null

        val badgeW = pw * 0.26f
        val badgeH = 42f
        val badgeRect = RectF(pl + 20f, pt + 20f, pl + 20f + badgeW, pt + 20f + badgeH)
        p.style = Paint.Style.FILL
        p.color = 0xE6052D22.toInt()
        c.drawRoundRect(badgeRect, 12f, 12f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = 0xFFD4FF33.toInt()
        c.drawRoundRect(badgeRect, 12f, 12f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = 21f
        p.textAlign = Paint.Align.CENTER
        c.drawText("⚡ NEWS CRICK", badgeRect.centerX(), badgeRect.centerY() + 7f, p)

        p.color = 0xFFFF007A.toInt()
        c.drawRect(pl, splitY, pr, bandBottom, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 18f
        p.letterSpacing = 0.08f
        p.textAlign = Paint.Align.CENTER
        val bandLabel = when (mode) {
            CdnViewMode.FOW -> "FALL OF WICKETS  •  WICKET BREAKDOWN  •  FALL OF WICKETS"
            CdnViewMode.SQUADS -> "MATCH SQUAD  •  PLAYING XI & BENCH  •  MATCH SQUAD"
            else -> "STAR PERFORMER  •  STAR PERFORMER  •  STAR PERFORMER  •  STAR PERFORMER"
        }
        c.drawText(bandLabel, pl + pw / 2f, splitY + bandH * 0.68f, p)
        p.letterSpacing = 0f

        p.color = 0xFF042D23.toInt()
        c.drawRect(pl, bandBottom, pr, pb, p)

        p.color = 0xFF073E30.toInt()
        val curve1 = Path().apply {
            moveTo(pl, bandBottom + 80f)
            quadTo(pl + pw * 0.45f, bandBottom - 10f, pr, bandBottom + 110f)
            lineTo(pr, pb)
            lineTo(pl, pb)
            close()
        }
        c.drawPath(curve1, p)

        val iconCx = pl + 110f
        val iconCy = bandBottom + 88f
        drawCricketBadgeIcon(c, p, iconCx, iconCy, isBowling = (type == ScoreCardType.BOWLING))

        val team = innings.teamName.ifBlank { innings.teamShortName }.uppercase()
        val subLine = when (mode) {
            CdnViewMode.FOW -> fowItem?.outDesc?.takeIf { it.isNotBlank() }?.uppercase() ?: "$team  •  WICKET #${fowItem?.wicketNumber ?: 1}"
            CdnViewMode.SQUADS -> {
                val r = squadPlayer?.role?.takeIf { it.isNotBlank() } ?: "PLAYER"
                val st = if (squadPlayer?.isSubstitute == true) "BENCH" else "PLAYING XI"
                "$team  •  ${r.uppercase()}  •  $st"
            }
            else -> if (!opponentName.isNullOrBlank()) {
                "VS ${opponentName.trim().uppercase()}"
            } else {
                if (type == ScoreCardType.BOWLING) "$team  •  KEY BOWLER" else "$team  •  KEY BATSMAN"
            }
        }

        val nameX = pl + 184f
        val maxNameW = pr - nameX - 28f
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.LEFT
        p.color = 0xFFD4FF33.toInt()
        p.textSize = 48f
        while (p.measureText(displayName) > maxNameW && p.textSize > 22f) p.textSize -= 2f
        c.drawText(displayName, nameX, bandBottom + 82f, p)

        p.color = 0xFFFFFFFF.toInt()
        p.textSize = 26f
        while (p.measureText(subLine) > maxNameW && p.textSize > 16f) p.textSize -= 1.5f
        c.drawText(subLine, nameX, bandBottom + 118f, p)

        val statCols: List<Pair<String, String>> = when (mode) {
            CdnViewMode.SCORE -> {
                val r = selectedRow?.primaryStat ?: "0"
                val b = selectedRow?.otherStats?.getOrElse(0) { "0" } ?: "0"
                val f4 = selectedRow?.otherStats?.getOrElse(1) { "0" } ?: "0"
                val s6 = selectedRow?.otherStats?.getOrElse(2) { "0" } ?: "0"
                val sr = selectedRow?.otherStats?.getOrElse(3) { "0.0" } ?: "0.0"
                listOf(r to "RUNS", b to "BALLS", f4 to "FOURS", s6 to "SIXES", sr to "STRIKE RATE")
            }
            CdnViewMode.BOWL -> {
                val o = selectedRow?.otherStats?.getOrElse(0) { "0" } ?: "0"
                val m = selectedRow?.otherStats?.getOrElse(1) { "0" } ?: "0"
                val r = selectedRow?.otherStats?.getOrElse(2) { "0" } ?: "0"
                val w = selectedRow?.primaryStat ?: "0"
                val eco = selectedRow?.otherStats?.getOrElse(3) { "0.0" } ?: "0.0"
                listOf(w to "WICKETS", o to "OVERS", m to "MAIDENS", r to "RUNS", eco to "ECONOMY")
            }
            CdnViewMode.FOW -> {
                val sc = fowItem?.score ?: selectedRow?.primaryStat ?: "0-1"
                val ov = fowItem?.overs ?: selectedRow?.otherStats?.getOrElse(0) { "0.0" } ?: "0.0"
                val wn = "#${fowItem?.wicketNumber ?: 1}"
                val tm = "${innings.score}-${innings.wickets}"
                listOf(sc to "FALL SCORE", ov to "OVER", wn to "WICKET", tm to "TEAM TOTAL")
            }
            CdnViewMode.SQUADS -> {
                val tag = selectedRow?.primaryStat ?: "XI"
                val status = if (squadPlayer?.isSubstitute == true) "BENCH" else "PLAYING XI"
                val role = squadPlayer?.role?.ifBlank { "PLAYER" }?.uppercase() ?: "PLAYER"
                listOf(tag to "STATUS", status to "SQUAD", role to "ROLE")
            }
            CdnViewMode.POINTS -> emptyList()
        }

        if (statCols.isNotEmpty()) {
            val stripLeft = pl + 24f
            val stripRight = pr - 24f
            val stripTop = bandBottom + 160f
            val stripBottom = pb - 24f
            val colGap = 8f
            val nCols = statCols.size
            val colW = (stripRight - stripLeft - colGap * (nCols - 1)) / nCols

            statCols.forEachIndexed { idx, (value, label) ->
                val bx0 = stripLeft + idx * (colW + colGap)
                val boxRect = RectF(bx0, stripTop, bx0 + colW, stripBottom)
                p.style = Paint.Style.FILL
                p.color = if (idx % 2 == 0) 0xFFFF007A.toInt() else 0x44031F18
                c.drawRoundRect(boxRect, 6f, 6f, p)

                p.color = 0xFFFFFFFF.toInt()
                p.typeface = Typeface.DEFAULT_BOLD
                p.textAlign = Paint.Align.CENTER
                p.textSize = if (nCols <= 3) 44f else 52f
                while (p.measureText(value) > (colW - 14f) && p.textSize > 20f) p.textSize -= 2f
                c.drawText(value, boxRect.centerX(), boxRect.top + boxRect.height() * 0.56f, p)

                p.textSize = 19f
                while (p.measureText(label) > (colW - 10f) && p.textSize > 13f) p.textSize -= 1f
                c.drawText(label, boxRect.centerX(), boxRect.top + boxRect.height() * 0.84f, p)
            }
        }

        c.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = 0xCCFFFFFF.toInt()
        c.drawRoundRect(panelRect, corner, corner, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
    }

    private fun sampleEdgeColor(bmp: Bitmap, isLeft: Boolean): Int? {
        val w = bmp.width
        val h = bmp.height
        if (w < 6 || h < 6) return null
        val x = if (isLeft) 2 else w - 3
        val ys = intArrayOf(4, h / 8, h / 4, h / 3)
        var aSum = 0L; var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (y in ys) {
            val px = bmp.getPixel(x, y.coerceIn(0, h - 1))
            val a = Color.alpha(px)
            if (a < 240) return null
            aSum += a; rSum += Color.red(px); gSum += Color.green(px); bSum += Color.blue(px)
        }
        val n = ys.size
        return Color.argb((aSum / n).toInt(), (rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }

    private fun darkenColor(argb: Int, factor: Float): Int {
        val a = Color.alpha(argb)
        val r = (Color.red(argb) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(argb) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(argb) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun drawCricketBadgeIcon(c: Canvas, p: Paint, cx: Float, cy: Float, isBowling: Boolean) {
        p.style = Paint.Style.FILL
        p.color = 0xFFD4FF33.toInt()
        if (isBowling) {
            c.drawCircle(cx, cy, 34f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = 0xFF042D23.toInt()
            c.drawLine(cx - 22f, cy - 22f, cx + 22f, cy + 22f, p)
            p.style = Paint.Style.FILL
        } else {
            c.save()
            c.translate(cx, cy)
            c.rotate(42f)
            c.drawRoundRect(RectF(-12f, -12f, 12f, 44f), 4f, 4f, p)
            c.drawRoundRect(RectF(-4f, -44f, 4f, -12f), 2f, 2f, p)
            c.restore()
        }
    }

    private fun drawStadiumBackdrop(c: Canvas, p: Paint, w: Float, h: Float) {
        p.maskFilter = null
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF0A2418.toInt(), 0xFF0E3322.toInt(), 0xFF05140D.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, p)

        p.shader = RadialGradient(
            w * 0.42f, h * 0.15f, w * 0.65f,
            intArrayOf(0x2E22995A, 0x00000000),
            null,
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, p)

        val beamPath = Path().apply {
            moveTo(w * 0.25f, 0f)
            lineTo(w * 0.45f, 0f)
            lineTo(w * 0.20f, h)
            lineTo(0f, h)
            close()
        }
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x16FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )
        c.drawPath(beamPath, p)
        p.shader = null
    }

    private fun drawFooterBarForMode(
        c: Canvas,
        p: Paint,
        rect: RectF,
        mode: CdnViewMode,
        innings: CdnInnings?,
        pointsGroup: CdnPointsGroup?,
        squadTeam: CdnSquadTeam?,
        theme: Int,
    ) {
        val rad = 14f
        p.shader = null
        p.maskFilter = null
        p.style = Paint.Style.FILL

        when (theme) {
            0 -> {
                p.color = 0xD8071022.toInt()
                c.drawRoundRect(rect, rad, rad, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = 0x4400E5FF
                c.drawRoundRect(rect, rad, rad, p)
            }
            1 -> {
                p.color = 0xE2101015.toInt()
                c.drawRoundRect(rect, rad, rad, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = 0x60C8922A
                c.drawRoundRect(rect, rad, rad, p)
            }
            else -> {
                p.color = 0xEEFFFFFF.toInt()
                c.drawRoundRect(rect, rad, rad, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f
                p.color = 0x336C2BD9
                c.drawRoundRect(rect, rad, rad, p)
            }
        }

        val labelColor = when (theme) {
            0 -> 0xFF9FB4DC.toInt()
            1 -> 0xFFB8B2A2.toInt()
            else -> 0xFF5C6475.toInt()
        }
        val valueColor = when (theme) {
            0 -> 0xFFFFFFFF.toInt()
            1 -> 0xFFF6D77A.toInt()
            else -> 0xFF141824.toInt()
        }

        val leftLabel: String
        val leftVal: String
        val rightLabel: String
        val rightVal: String
        when (mode) {
            CdnViewMode.POINTS -> {
                val topTeam = pointsGroup?.teams?.firstOrNull()
                leftLabel = "Teams  "
                leftVal = "${pointsGroup?.teams?.size ?: 0}"
                rightLabel = "Leader  "
                rightVal = topTeam?.let { "${it.teamName} (${it.points} pts)" } ?: "-"
            }
            CdnViewMode.SQUADS -> {
                leftLabel = "Playing XI  "
                leftVal = "${squadTeam?.playingXi?.size ?: 0}"
                rightLabel = "Bench  "
                rightVal = "${squadTeam?.bench?.size ?: 0}"
            }
            CdnViewMode.FOW -> {
                val inn = innings
                leftLabel = "Wickets Fallen  "
                leftVal = "${inn?.fallOfWickets?.size ?: inn?.wickets ?: 0}"
                rightLabel = "Overs  "
                rightVal = inn?.overs ?: "0.0"
            }
            else -> {
                val inn = innings
                val oversStr = inn?.let { if (it.overs.contains(".")) it.overs else "${it.overs}.0" } ?: "0.0"
                val decOv = parseDecimalOvers(inn?.overs ?: "0")
                val runRate = if (decOv > 0.0 && inn != null) {
                    String.format(Locale.US, "%.2f", inn.score / decOv)
                } else {
                    "0.00"
                }
                leftLabel = "Overs  "
                leftVal = oversStr
                rightLabel = "Run rate  "
                rightVal = runRate
            }
        }

        val cy = rect.centerY() + 8f
        p.style = Paint.Style.FILL
        p.textSize = 23f
        p.textAlign = Paint.Align.LEFT

        val leftStart = rect.left + 28f
        p.typeface = Typeface.DEFAULT
        p.color = labelColor
        c.drawText(leftLabel, leftStart, cy, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = valueColor
        c.drawText(leftVal, leftStart + p.measureText(leftLabel), cy, p)

        val rightEnd = rect.right - 26f
        p.typeface = Typeface.DEFAULT_BOLD
        val rValW = p.measureText(rightVal)
        p.typeface = Typeface.DEFAULT
        val rLblW = p.measureText(rightLabel)

        p.color = labelColor
        c.drawText(rightLabel, rightEnd - rValW - rLblW, cy, p)
        p.typeface = Typeface.DEFAULT_BOLD
        p.color = valueColor
        c.drawText(rightVal, rightEnd - rValW, cy, p)
    }

    private fun parseDecimalOvers(overs: String): Double {
        val trimmed = overs.trim()
        val parts = trimmed.split(".")
        val whole = parts.getOrNull(0)?.toIntOrNull() ?: return 0.0
        val balls = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return whole + (balls.coerceIn(0, 5) / 6.0)
    }

    private fun drawVectorRowContent(
        c: Canvas,
        p: Paint,
        rect: RectF,
        row: VectorRow,
        theme: Int,
        selected: Boolean,
        mode: CdnViewMode,
        playerIndex: Int,
    ) {
        val leftX = rect.left + if (theme == 1 && row !is VectorRow.Header) 80f else 34f
        val rightX = rect.right - 24f

        val statXs: FloatArray = when (mode) {
            CdnViewMode.FOW -> floatArrayOf(rect.right - 244f, rect.right - 122f, rightX)
            CdnViewMode.POINTS -> floatArrayOf(
                rect.right - 344f,
                rect.right - 288f,
                rect.right - 232f,
                rect.right - 154f,
                rightX,
            )
            CdnViewMode.SQUADS -> floatArrayOf(rect.right - 56f)
            else -> floatArrayOf(
                rect.right - 318f,
                rect.right - 228f,
                rect.right - 168f,
                rect.right - 110f,
                rightX,
            )
        }

        val mainColor = when {
            theme == 2 && row !is VectorRow.Header -> 0xFF141824.toInt()
            theme == 1 && selected -> 0xFF1A1208.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val subColor = when {
            theme == 2 && !selected -> 0xFF5C6475.toInt()
            theme == 2 && selected -> 0xFF4A4E5D.toInt()
            theme == 1 && selected -> 0xFF4D350A.toInt()
            selected && theme == 0 -> 0xFFFFC4CE.toInt()
            theme == 0 -> 0xFF9FB4DC.toInt()
            else -> 0xFFAAB0C2.toInt()
        }

        p.shader = null
        p.maskFilter = null
        p.clearShadowLayer()
        p.style = Paint.Style.FILL
        p.letterSpacing = 0f

        when (row) {
            is VectorRow.Header -> {
                p.textSize = 24f
                p.typeface = Typeface.DEFAULT
                val ovText = if (row.oversPart.isNotBlank()) " ${row.oversPart}" else ""
                val ovW = p.measureText(ovText)

                p.textSize = 36f
                p.typeface = Typeface.DEFAULT_BOLD
                val scW = p.measureText(row.scorePart)

                val rightTotalW = scW + ovW
                val titleLeft = rect.left + 34f
                val maxTitleW = (rightX - titleLeft - rightTotalW - 20f).coerceAtLeast(120f)

                p.textSize = 33f
                p.color = if (theme == 1) 0xFFF6D77A.toInt() else 0xFFFFFFFF.toInt()
                p.textAlign = Paint.Align.LEFT
                c.drawText(ellipsize(row.leftTitle, p, maxTitleW), titleLeft, rect.centerY() + 12f, p)

                p.textAlign = Paint.Align.RIGHT
                if (ovText.isNotEmpty()) {
                    p.textSize = 23f
                    p.typeface = Typeface.DEFAULT
                    p.color = if (theme == 1) 0xFFE8D8B0.toInt() else 0xFFDCEBFF.toInt()
                    c.drawText(ovText, rightX, rect.centerY() + 10f, p)
                }

                p.textSize = 36f
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = 0xFFFFFFFF.toInt()
                c.drawText(row.scorePart, rightX - ovW, rect.centerY() + 12f, p)
            }

            is VectorRow.Columns -> {
                p.typeface = Typeface.DEFAULT_BOLD
                p.color = if (theme == 1) 0xFFD6B363.toInt() else subColor
                p.textSize = 21f
                p.textAlign = Paint.Align.LEFT
                c.drawText(row.col0, leftX, rect.centerY() + 8f, p)

                row.stats.forEachIndexed { idx, label ->
                    if (idx < statXs.size) {
                        val isLast = idx == statXs.lastIndex && mode != CdnViewMode.SQUADS
                        p.textAlign = if (isLast) Paint.Align.RIGHT else Paint.Align.CENTER
                        c.drawText(label, statXs[idx], rect.centerY() + 8f, p)
                    }
                }
            }

            is VectorRow.PlayerRow -> {
                if (theme == 1) {
                    val cx = rect.left + 48f
                    val cy = rect.centerY()
                    val radius = 18f
                    if (selected) {
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 2.5f
                        p.color = 0xFF1A1208.toInt()
                        c.drawCircle(cx, cy, radius, p)
                    } else {
                        p.style = Paint.Style.FILL
                        p.color = 0xFF181820.toInt()
                        c.drawCircle(cx, cy, radius, p)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 1.8f
                        p.color = 0xAAC8922A.toInt()
                        c.drawCircle(cx, cy, radius, p)
                    }
                    p.style = Paint.Style.FILL
                    p.typeface = Typeface.DEFAULT_BOLD
                    p.textSize = 19f
                    p.textAlign = Paint.Align.CENTER
                    p.color = if (selected) 0xFF1A1208.toInt() else 0xFFF6D77A.toInt()
                    c.drawText((playerIndex + 1).toString(), cx, cy + 7f, p)
                }

                val firstStatBoundary = when (mode) {
                    CdnViewMode.FOW -> statXs[0] - 52f
                    CdnViewMode.POINTS -> statXs[0] - 28f
                    CdnViewMode.SQUADS -> statXs[0] - 58f
                    else -> statXs[0] - 48f
                }
                val maxNameW = (firstStatBoundary - leftX - 10f).coerceAtLeast(160f)

                p.typeface = Typeface.DEFAULT_BOLD
                p.color = mainColor
                p.textSize = 34f
                while (p.measureText(row.name) > maxNameW && p.textSize > 22f) {
                    p.textSize -= 1.5f
                }
                p.textAlign = Paint.Align.LEFT
                val nameY = if (row.subText.isNotBlank()) rect.top + rect.height() * 0.45f else rect.centerY() + 12f
                c.drawText(ellipsize(row.name, p, maxNameW), leftX, nameY, p)

                if (row.subText.isNotBlank()) {
                    p.typeface = Typeface.DEFAULT
                    p.color = subColor
                    p.textSize = 20f
                    c.drawText(ellipsize(row.subText, p, maxNameW), leftX, rect.top + rect.height() * 0.80f, p)
                }

                val allCols: List<String>
                val primaryIdx: Int
                when (mode) {
                    CdnViewMode.SCORE -> {
                        allCols = listOf(row.primaryStat) + row.otherStats
                        primaryIdx = 0
                    }
                    CdnViewMode.BOWL -> {
                        allCols = listOf(
                            row.otherStats.getOrElse(0) { "0" },
                            row.otherStats.getOrElse(1) { "0" },
                            row.otherStats.getOrElse(2) { "0" },
                            row.primaryStat,
                            row.otherStats.getOrElse(3) { "0.00" },
                        )
                        primaryIdx = 3
                    }
                    CdnViewMode.FOW -> {
                        allCols = listOf(
                            row.primaryStat,
                            row.otherStats.getOrElse(0) { "0" },
                            row.otherStats.getOrElse(1) { "" },
                        )
                        primaryIdx = 0
                    }
                    CdnViewMode.POINTS -> {
                        allCols = listOf(
                            row.otherStats.getOrElse(0) { "0" },
                            row.otherStats.getOrElse(1) { "0" },
                            row.otherStats.getOrElse(2) { "0" },
                            row.primaryStat,
                            row.otherStats.getOrElse(3) { "0.000" },
                        )
                        primaryIdx = 3
                    }
                    CdnViewMode.SQUADS -> {
                        allCols = listOf(row.primaryStat)
                        primaryIdx = 0
                    }
                }

                allCols.forEachIndexed { idx, v ->
                    if (idx >= statXs.size) return@forEachIndexed
                    val isPrimary = idx == primaryIdx
                    val colX = statXs[idx]
                    val isLastCol = idx == statXs.lastIndex && mode != CdnViewMode.SQUADS

                    if (isPrimary && theme == 1) {
                        val pillW = if (mode == CdnViewMode.FOW || mode == CdnViewMode.SQUADS) 94f else 82f
                        val pillH = 60f
                        val pillRect = RectF(
                            colX - pillW / 2f,
                            rect.centerY() - pillH / 2f,
                            colX + pillW / 2f,
                            rect.centerY() + pillH / 2f,
                        )
                        val pillColors = if (selected) {
                            intArrayOf(0xFFB0124A.toInt(), 0xFF4A0A52.toInt())
                        } else {
                            GOLD_PILL_GRADIENTS[playerIndex % GOLD_PILL_GRADIENTS.size]
                        }
                        p.style = Paint.Style.FILL
                        p.shader = LinearGradient(
                            pillRect.left, pillRect.top, pillRect.right, pillRect.bottom,
                            pillColors, null, Shader.TileMode.CLAMP,
                        )
                        c.drawRoundRect(pillRect, 14f, 14f, p)
                        p.shader = null

                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 2f
                        p.color = if (selected) 0xFF1A1208.toInt() else 0xFFF6D77A.toInt()
                        c.drawRoundRect(pillRect, 14f, 14f, p)
                        p.style = Paint.Style.FILL

                        p.typeface = Typeface.DEFAULT_BOLD
                        p.textSize = if (v.length >= 4) 30f else 42f
                        while (p.measureText(v) > pillW - 12f && p.textSize > 20f) p.textSize -= 2f
                        p.textAlign = Paint.Align.CENTER
                        p.shader = LinearGradient(
                            0f, pillRect.top + 8f, 0f, pillRect.bottom - 8f,
                            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE680.toInt(), 0xFFFFB300.toInt()),
                            null, Shader.TileMode.CLAMP,
                        )
                        p.setShadowLayer(4f, 0f, 2f, 0x66000000)
                        c.drawText(v, colX, rect.centerY() + p.textSize * 0.35f, p)
                        p.clearShadowLayer()
                        p.shader = null
                    } else if (isPrimary) {
                        p.typeface = Typeface.DEFAULT_BOLD
                        p.textSize = if (v.length >= 4) 34f else 46f
                        while (p.measureText(v) > 92f && p.textSize > 22f) p.textSize -= 2f
                        p.textAlign = Paint.Align.CENTER

                        val gradColors = if (theme == 0) {
                            if (selected) {
                                intArrayOf(0xFFFFF59D.toInt(), 0xFFFFD400.toInt(), 0xFFFF5252.toInt())
                            } else {
                                NEON_RUN_GRADIENTS[playerIndex % NEON_RUN_GRADIENTS.size]
                            }
                        } else {
                            if (selected) {
                                intArrayOf(0xFFFF007A.toInt(), 0xFFFF6D00.toInt(), 0xFF7B1FA2.toInt())
                            } else {
                                POP_RUN_GRADIENTS[playerIndex % POP_RUN_GRADIENTS.size]
                            }
                        }

                        p.shader = LinearGradient(
                            colX - 28f, rect.top + 14f,
                            colX + 28f, rect.bottom - 14f,
                            gradColors, null, Shader.TileMode.CLAMP,
                        )
                        val shadowCol = if (theme == 0) {
                            (0x88 shl 24) or (gradColors[0] and 0x00FFFFFF)
                        } else {
                            0x33000000
                        }
                        p.setShadowLayer(if (theme == 0) 8f else 4f, 0f, if (theme == 0) 0f else 2f, shadowCol)
                        c.drawText(v, colX, rect.centerY() + p.textSize * 0.35f, p)
                        p.clearShadowLayer()
                        p.shader = null
                    } else {
                        val isSixesHighlight = theme == 0 && mode == CdnViewMode.SCORE && idx == 3 && (v.toIntOrNull() ?: 0) >= 3
                        p.typeface = if (theme == 1 && selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        p.color = if (isSixesHighlight) 0xFFFFD400.toInt() else mainColor
                        p.textSize = 23f
                        p.textAlign = if (isLastCol) Paint.Align.RIGHT else Paint.Align.CENTER
                        c.drawText(v, colX, rect.centerY() + 8f, p)
                    }
                }
            }
        }
        p.clearShadowLayer()
        p.shader = null
        p.textAlign = Paint.Align.LEFT
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    fun loadInnings(context: Context, jsonUri: String, inningsIdx: Int): CdnInnings? =
        loadInningsWithOpponent(context, jsonUri, inningsIdx)?.first

    fun loadInningsWithOpponent(context: Context, jsonUri: String, inningsIdx: Int): Pair<CdnInnings, String?>? {
        val bundle = loadSavedBundle(context, jsonUri) ?: return null
        val match = bundle.match ?: return null
        val inn = match.innings.getOrNull(inningsIdx) ?: match.innings.firstOrNull() ?: return null
        val opp = match.innings.firstOrNull { it.inningsId != inn.inningsId }?.let {
            it.teamName.ifBlank { it.teamShortName }
        }
        return inn to opp
    }

    fun loadSavedBundle(context: Context, jsonUri: String): SavedCdnBundle? {
        val raw = runCatching {
            val uri = Uri.parse(jsonUri)
            val file = File(uri.path ?: jsonUri)
            if (file.exists()) file.readText() else null
        }.getOrNull() ?: return null

        return runCatching {
            val root = JSONObject(raw)
            val match = if (root.has("innings") && (root.optJSONArray("innings")?.length() ?: 0) > 0) {
                CdnScorecardClient.parseJson(raw)
            } else null

            val ptObj = root.optJSONObject("pointsTable")
                ?: if (root.has("groups")) root else null
            val points = ptObj?.let { runCatching { CdnScorecardClient.parsePointsJson(it.toString()) }.getOrNull() }

            val sqObj = root.optJSONObject("squads")
                ?: if (root.has("teams")) root else null
            val squads = sqObj?.let { runCatching { CdnScorecardClient.parseSquadsJson(it.toString()) }.getOrNull() }

            SavedCdnBundle(match, points, squads)
        }.getOrNull()
    }
}
