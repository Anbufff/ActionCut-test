package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class CdnBatter(
    val id: String?,
    val name: String,
    val outDesc: String,
    val runs: Int,
    val balls: Int,
    val fours: Int,
    val sixes: Int,
    val sr: String,
    val imageId: String?,
)

data class CdnBowler(
    val id: String?,
    val name: String,
    val overs: String,
    val maidens: Int,
    val runs: Int,
    val wickets: Int,
    val econ: String,
    val imageId: String?,
)

data class CdnFow(
    val id: String?,
    val name: String,
    val wicketNumber: Int,
    val runs: Int,
    val score: String,
    val overs: String,
    val outDesc: String,
    val imageId: String?,
)

data class CdnInnings(
    val inningsId: Int,
    val teamName: String,
    val teamShortName: String,
    val score: Int,
    val wickets: Int,
    val overs: String,
    val isDeclared: Boolean,
    val batters: List<CdnBatter>,
    val bowlers: List<CdnBowler>,
    val fallOfWickets: List<CdnFow> = emptyList(),
)

data class CdnMatchScorecard(
    val matchId: String,
    val title: String,
    val status: String,
    val source: String,
    val innings: List<CdnInnings>,
    val seriesId: String? = null,
    val seriesName: String = "",
)

data class CdnRecentMatch(
    val matchId: String,
    val seriesId: String?,
    val seriesName: String,
    val matchDesc: String,
    val matchFormat: String,
    val state: String,
    val status: String,
    val team1: String,
    val team2: String,
    val team1Name: String,
    val team2Name: String,
    val team1Score: String,
    val team2Score: String,
    val isLive: Boolean,
)

data class CdnPointsTeam(
    val pos: Int,
    val teamId: String?,
    val teamName: String,
    val teamFullName: String,
    val matchesPlayed: Int,
    val won: Int,
    val lost: Int,
    val tied: Int,
    val nr: Int,
    val points: Int,
    val nrr: String,
    val form: List<String>,
    val qualified: Boolean,
    val imageId: String?,
)

data class CdnPointsGroup(
    val groupName: String,
    val teams: List<CdnPointsTeam>,
)

data class CdnPointsTable(
    val seriesId: String,
    val seriesName: String,
    val groups: List<CdnPointsGroup>,
)

data class CdnSquadPlayer(
    val id: String?,
    val name: String,
    val role: String,
    val isCaptain: Boolean,
    val isWk: Boolean,
    val isSubstitute: Boolean,
    val imageId: String?,
)

data class CdnSquadTeam(
    val teamId: String?,
    val teamName: String,
    val teamShortName: String,
    val playingXi: List<CdnSquadPlayer>,
    val bench: List<CdnSquadPlayer>,
)

data class CdnMatchSquads(
    val matchId: String,
    val title: String,
    val teams: List<CdnSquadTeam>,
)

object CdnScorecardClient {
    private const val PREFS = "actioncut_cdn_scorecard"
    private const val KEY_WORKER_URL = "worker_url"
    private const val PLACEHOLDER_IMAGE_ID = "174146"

    const val DEFAULT_WORKER_URL = "https://restless-field-a018.anbufff2.workers.dev"

    private val nameIndexLock = Any()

    fun savedWorkerUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(KEY_WORKER_URL, DEFAULT_WORKER_URL).orEmpty().ifBlank { DEFAULT_WORKER_URL }
    }

    fun saveWorkerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORKER_URL, url.trim().trimEnd('/'))
            .apply()
    }

    suspend fun fetch(context: Context, workerBaseUrl: String, matchInput: String): Result<Pair<CdnMatchScorecard, String>> =
        fetchMatchOnly(workerBaseUrl, matchInput)

    private fun httpGet(reqUrl: String): Pair<Int, String> {
        val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return code to body
    }

    /** Fetches match scorecard JSON immediately without blocking on all player photos. */
    suspend fun fetchMatchOnly(workerBaseUrl: String, matchInput: String): Result<Pair<CdnMatchScorecard, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = workerBaseUrl.trim().trimEnd('/')
                require(base.startsWith("http")) { "Valid Cloudflare Worker URL required" }
                val encoded = URLEncoder.encode(matchInput.trim(), "UTF-8")
                val sep = if (base.contains("?")) "&" else "?"
                val reqUrl = "${base}${sep}matchId=$encoded&_=${System.currentTimeMillis()}"

                val (code, body) = httpGet(reqUrl)
                if (code !in 200..299) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw IllegalStateException(errMsg?.ifBlank { null } ?: "HTTP $code")
                }

                val parsed = parseJson(body)
                require(parsed.innings.isNotEmpty()) { "Scorecard data not available for this match yet." }
                parsed to body
            }
        }

    /** Fetches live and recent Cricbuzz matches from the Cloudflare Worker. */
    suspend fun fetchRecentMatches(workerBaseUrl: String): Result<List<CdnRecentMatch>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = workerBaseUrl.trim().trimEnd('/')
                require(base.startsWith("http")) { "Valid Cloudflare Worker URL required" }
                val sep = if (base.contains("?")) "&" else "?"
                val reqUrl = "${base}${sep}action=recent&_=${System.currentTimeMillis()}"

                val (code, body) = httpGet(reqUrl)
                if (code !in 200..299) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw IllegalStateException(errMsg?.ifBlank { null } ?: "HTTP $code")
                }
                parseRecentMatchesJson(body)
            }
        }

    /** Fetches series Points Table using either seriesId or matchId/URL. */
    suspend fun fetchPointsTable(
        workerBaseUrl: String,
        seriesId: String? = null,
        matchOrUrl: String = "",
    ): Result<Pair<CdnPointsTable, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = workerBaseUrl.trim().trimEnd('/')
                require(base.startsWith("http")) { "Valid Cloudflare Worker URL required" }
                val sep = if (base.contains("?")) "&" else "?"
                val params = buildList {
                    add("action=pointsTable")
                    if (!seriesId.isNullOrBlank()) add("seriesId=${URLEncoder.encode(seriesId.trim(), "UTF-8")}")
                    if (matchOrUrl.isNotBlank()) add("matchId=${URLEncoder.encode(matchOrUrl.trim(), "UTF-8")}")
                    add("_=${System.currentTimeMillis()}")
                }.joinToString("&")
                val reqUrl = "$base$sep$params"

                val (code, body) = httpGet(reqUrl)
                if (code !in 200..299) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw IllegalStateException(errMsg?.ifBlank { null } ?: "Points Table unavailable (HTTP $code)")
                }
                val parsed = parsePointsJson(body)
                require(parsed.groups.isNotEmpty()) { "Points Table not available for this series/match." }
                parsed to body
            }
        }

    /** Fetches Playing XI & Bench Squads for the match. */
    suspend fun fetchSquads(
        workerBaseUrl: String,
        matchInput: String,
    ): Result<Pair<CdnMatchSquads, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = workerBaseUrl.trim().trimEnd('/')
                require(base.startsWith("http")) { "Valid Cloudflare Worker URL required" }
                val sep = if (base.contains("?")) "&" else "?"
                val encoded = URLEncoder.encode(matchInput.trim(), "UTF-8")
                val reqUrl = "${base}${sep}action=squads&matchId=$encoded&_=${System.currentTimeMillis()}"

                val (code, body) = httpGet(reqUrl)
                if (code !in 200..299) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw IllegalStateException(errMsg?.ifBlank { null } ?: "Squads unavailable (HTTP $code)")
                }
                val parsed = parseSquadsJson(body)
                require(parsed.teams.any { it.playingXi.isNotEmpty() || it.bench.isNotEmpty() }) {
                    "Squads not available for this match yet."
                }
                parsed to body
            }
        }

    /** Downloads all batter, bowler & FOW photos for an innings in parallel (up to 6 concurrent). */
    suspend fun downloadInningsPhotos(
        context: Context,
        workerBaseUrl: String,
        innings: CdnInnings,
    ) = withContext(Dispatchers.IO) {
        val base = workerBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) return@withContext
        val players = buildList {
            innings.batters.forEach { add(Triple(it.imageId, it.id, it.name)) }
            innings.bowlers.forEach { add(Triple(it.imageId, it.id, it.name)) }
            innings.fallOfWickets.forEach { add(Triple(it.imageId, it.id, it.name)) }
        }.distinctBy { (imgId, pid, name) -> "${imgId.orEmpty()}_${pid.orEmpty()}_${normName(name)}" }

        val gate = Semaphore(6)
        coroutineScope {
            players.map { (imgId, pid, name) ->
                async {
                    gate.withPermit {
                        ensurePlayerPhoto(context, base, imgId, pid, name)
                    }
                }
            }.awaitAll()
        }
    }

    /** Downloads all Playing XI & Bench player photos for a squad team in parallel. */
    suspend fun downloadSquadPhotos(
        context: Context,
        workerBaseUrl: String,
        team: CdnSquadTeam,
    ) = withContext(Dispatchers.IO) {
        val base = workerBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) return@withContext
        val players = (team.playingXi + team.bench)
            .map { Triple(it.imageId, it.id, it.name) }
            .distinctBy { (imgId, pid, name) -> "${imgId.orEmpty()}_${pid.orEmpty()}_${normName(name)}" }

        val gate = Semaphore(6)
        coroutineScope {
            players.map { (imgId, pid, name) ->
                async {
                    gate.withPermit {
                        ensurePlayerPhoto(context, base, imgId, pid, name)
                    }
                }
            }.awaitAll()
        }
    }

    /** Downloads team crests/logos for a Points Table group if imageIds are present. */
    suspend fun downloadPointsLogos(
        context: Context,
        workerBaseUrl: String,
        group: CdnPointsGroup,
    ) = withContext(Dispatchers.IO) {
        val base = workerBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) return@withContext
        val gate = Semaphore(6)
        coroutineScope {
            group.teams.mapNotNull { it.imageId?.takeIf { id -> id.isNotBlank() } }.distinct().map { imgId ->
                async {
                    gate.withPermit {
                        ensureTeamLogo(context, base, imgId)
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun ensureTeamLogo(
        context: Context,
        workerBaseUrl: String,
        imageId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (imageId.isBlank() || imageId == "0" || imageId == PLACEHOLDER_IMAGE_ID) return@withContext false
        val dir = File(context.filesDir, "scorecards/cdn_teams").apply { mkdirs() }
        val target = File(dir, "team_$imageId.png")
        if (target.exists() && target.length() > 0L) return@withContext true

        val base = workerBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) return@withContext false
        val sep = if (base.contains("?")) "&" else "?"
        val imgUrl = "${base}${sep}imgId=$imageId"

        runCatching {
            val conn = (URL(imgUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            val ok = if (conn.responseCode in 200..299) {
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    val tmp = File(dir, "${target.name}.tmp")
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) }
                    bmp.recycle()
                    tmp.renameTo(target)
                } else false
            } else false
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    fun cachedTeamLogo(context: Context, imageId: String?): Bitmap? {
        if (imageId.isNullOrBlank() || imageId == "0" || imageId == PLACEHOLDER_IMAGE_ID) return null
        val file = File(context.filesDir, "scorecards/cdn_teams/team_$imageId.png")
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    /**
     * Ensures a single player's real Cricbuzz photo is cached locally and indexed permanently by
     * player name in `filesDir/players_cdn/`. Cricbuzz's "cb" placeholder ball is skipped.
     */
    suspend fun ensurePlayerPhoto(
        context: Context,
        workerBaseUrl: String,
        imageId: String?,
        playerId: String?,
        playerName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (imageId == PLACEHOLDER_IMAGE_ID || imageId == "0") return@withContext false

        val dir = File(context.filesDir, "scorecards/cdn_players").apply { mkdirs() }
        val target = when {
            !imageId.isNullOrBlank() -> File(dir, "img_$imageId.jpg")
            !playerId.isNullOrBlank() -> File(dir, "pid_$playerId.jpg")
            else -> return@withContext false
        }
        if (target.exists() && target.length() > 0L) {
            val existing = runCatching { BitmapFactory.decodeFile(target.absolutePath) }.getOrNull()
            if (existing != null) {
                val bad = isCricbuzzPlaceholder(existing)
                existing.recycle()
                if (!bad) {
                    saveNameMapping(context, playerName, target)
                    return@withContext true
                }
            }
            runCatching { target.delete() }
            return@withContext false
        }

        val base = workerBaseUrl.trim().trimEnd('/')
        if (!base.startsWith("http")) return@withContext false
        val sep = if (base.contains("?")) "&" else "?"
        val imgUrl = when {
            !imageId.isNullOrBlank() -> "${base}${sep}imgId=$imageId"
            else -> "${base}${sep}playerId=$playerId&name=${URLEncoder.encode(playerName, "UTF-8")}"
        }

        runCatching {
            val conn = (URL(imgUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            val ok = if (conn.responseCode in 200..299) {
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) {
                    if (isCricbuzzPlaceholder(bmp)) {
                        bmp.recycle()
                        false
                    } else {
                        val tmp = File(dir, "${target.name}.tmp")
                        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        bmp.recycle()
                        val moved = tmp.renameTo(target)
                        if (moved) saveNameMapping(context, playerName, target)
                        moved
                    }
                } else false
            } else false
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    fun cachedPlayerPhoto(
        context: Context,
        imageId: String?,
        playerId: String?,
        playerName: String? = null,
    ): Bitmap? {
        if (imageId == PLACEHOLDER_IMAGE_ID || imageId == "0") return null
        val dir = File(context.filesDir, "scorecards/cdn_players")
        val candidates = buildList {
            if (!imageId.isNullOrBlank()) add(File(dir, "img_$imageId.jpg"))
            if (!playerId.isNullOrBlank()) add(File(dir, "pid_$playerId.jpg"))
        }
        val file = candidates.firstOrNull { it.exists() && it.length() > 0L }
            ?: playerName?.let { findCachedPhotoFileByName(context, it) }
            ?: return null

        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        if (isCricbuzzPlaceholder(bmp)) {
            bmp.recycle()
            runCatching { file.delete() }
            return null
        }
        return bmp
    }

    /** Finds a permanently cached player photo file in local storage by player name (exact or partial). */
    fun findCachedPhotoFileByName(context: Context, playerName: String): File? {
        val key = normName(playerName)
        if (key.length < 3) return null
        synchronized(nameIndexLock) {
            val idxFile = nameIndexFile(context)
            if (!idxFile.exists()) return null
            val obj = runCatching { JSONObject(idxFile.readText()) }.getOrNull() ?: return null
            val exactPath = obj.optString(key, "").takeIf { it.isNotBlank() }
            if (exactPath != null) {
                val f = File(exactPath)
                if (f.exists() && f.length() > 0L) return f
            }
            val keys = obj.keys()
            var bestPath: String? = null
            var bestScore = 0
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.length >= 4 && (key.contains(k) || k.contains(key))) {
                    val score = minOf(k.length, key.length)
                    if (score > bestScore) {
                        val p = obj.optString(k, "")
                        val f = File(p)
                        if (f.exists() && f.length() > 0L) {
                            bestScore = score
                            bestPath = p
                        }
                    }
                }
            }
            return bestPath?.let { File(it) }
        }
    }

    private fun saveNameMapping(context: Context, playerName: String, photoFile: File) {
        val key = normName(playerName)
        if (key.length < 3) return
        val permDir = File(context.filesDir, "players_cdn").apply { mkdirs() }
        val permFile = File(permDir, "$key.jpg")
        runCatching {
            if (!permFile.exists() || permFile.length() != photoFile.length()) {
                photoFile.copyTo(permFile, overwrite = true)
            }
        }
        val storedFile = if (permFile.exists() && permFile.length() > 0L) permFile else photoFile
        synchronized(nameIndexLock) {
            val idxFile = nameIndexFile(context)
            val obj = if (idxFile.exists()) {
                runCatching { JSONObject(idxFile.readText()) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            obj.put(key, storedFile.absolutePath)
            idxFile.writeText(obj.toString())
        }
    }

    private fun nameIndexFile(context: Context): File =
        File(File(context.filesDir, "players_cdn").apply { mkdirs() }, "name_index.json")

    private fun normName(name: String): String =
        name.lowercase().replace(Regex("[^a-z]"), "")

    fun isCricbuzzPlaceholder(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w < 20 || h < 20) return false

        fun px(fx: Float, fy: Float): Int =
            bmp.getPixel((w * fx).toInt().coerceIn(0, w - 1), (h * fy).toInt().coerceIn(0, h - 1))

        fun isPureWhite(c: Int): Boolean =
            Color.alpha(c) > 200 && Color.red(c) > 232 && Color.green(c) > 232 && Color.blue(c) > 232

        fun bandHasWhite(fx: Float?, fy: Float?, fromPct: Int, toPct: Int): Boolean {
            for (pct in fromPct..toPct step 2) {
                val p = if (fx != null) px(fx, pct / 100f) else px(pct / 100f, fy!!)
                if (isPureWhite(p)) return true
            }
            return false
        }

        val hasSymmetricalWhiteRing =
            bandHasWhite(0.50f, null, 12, 38) &&
                bandHasWhite(0.50f, null, 62, 88) &&
                bandHasWhite(null, 0.50f, 12, 38) &&
                bandHasWhite(null, 0.50f, 62, 88)
        if (!hasSymmetricalWhiteRing) return false

        val corners = intArrayOf(
            px(0.05f, 0.05f),
            px(0.95f, 0.05f),
            px(0.05f, 0.95f),
            px(0.95f, 0.95f),
        )
        val cornersTransparent = corners.all { Color.alpha(it) < 35 }
        val c0 = corners[0]
        val cornersUniform = corners.all { c ->
            abs(Color.red(c) - Color.red(c0)) < 18 &&
                abs(Color.green(c) - Color.green(c0)) < 18 &&
                abs(Color.blue(c) - Color.blue(c0)) < 18
        }
        if (!cornersTransparent && !cornersUniform) return false

        for (yp in 36..64 step 7) {
            for (xp in 36..64 step 7) {
                val c = px(xp / 100f, yp / 100f)
                if (Color.alpha(c) > 180) {
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    if (r > g + 16 && r > b + 22 && r > 90) {
                        return false
                    }
                }
            }
        }
        return true
    }

    fun parseJson(raw: String): CdnMatchScorecard {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("innings") ?: JSONArray()
        val inningsList = (0 until arr.length()).map { idx ->
            val o = arr.getJSONObject(idx)
            val batArr = o.optJSONArray("batters") ?: JSONArray()
            val batters = (0 until batArr.length()).map { bi ->
                val b = batArr.getJSONObject(bi)
                val rawId = b.opt("id")?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                CdnBatter(
                    id = rawId,
                    name = b.optString("name", ""),
                    outDesc = b.optString("outDesc", ""),
                    runs = b.optInt("runs", 0),
                    balls = b.optInt("balls", 0),
                    fours = b.optInt("fours", 0),
                    sixes = b.optInt("sixes", 0),
                    sr = b.optString("sr", "0.00"),
                    imageId = b.opt("imageId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                )
            }
            val bowlArr = o.optJSONArray("bowlers") ?: JSONArray()
            val bowlers = (0 until bowlArr.length()).map { wi ->
                val w = bowlArr.getJSONObject(wi)
                val rawId = w.opt("id")?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                CdnBowler(
                    id = rawId,
                    name = w.optString("name", ""),
                    overs = w.optString("overs", "0"),
                    maidens = w.optInt("maidens", 0),
                    runs = w.optInt("runs", 0),
                    wickets = w.optInt("wickets", 0),
                    econ = w.optString("econ", "0.00"),
                    imageId = w.opt("imageId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                )
            }
            val fowArr = o.optJSONArray("fallOfWickets") ?: JSONArray()
            val fallOfWickets = (0 until fowArr.length()).map { fi ->
                val f = fowArr.getJSONObject(fi)
                val rawId = f.opt("id")?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                CdnFow(
                    id = rawId,
                    name = f.optString("name", ""),
                    wicketNumber = f.optInt("wicketNumber", fi + 1),
                    runs = f.optInt("runs", 0),
                    score = f.optString("score", "${f.optInt("runs", 0)}-${f.optInt("wicketNumber", fi + 1)}"),
                    overs = f.optString("overs", "0"),
                    outDesc = f.optString("outDesc", ""),
                    imageId = f.opt("imageId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                )
            }
            CdnInnings(
                inningsId = o.optInt("inningsId", idx + 1),
                teamName = o.optString("teamName", "Team ${idx + 1}"),
                teamShortName = o.optString("teamShortName", o.optString("teamName", "T${idx + 1}")),
                score = o.optInt("score", 0),
                wickets = o.optInt("wickets", 0),
                overs = o.optString("overs", "0"),
                isDeclared = o.optBoolean("isDeclared", false),
                batters = batters,
                bowlers = bowlers,
                fallOfWickets = fallOfWickets,
            )
        }
        return CdnMatchScorecard(
            matchId = root.optString("matchId", ""),
            title = root.optString("title", "CRICKET SCORECARD"),
            status = root.optString("status", ""),
            source = root.optString("source", ""),
            innings = inningsList,
            seriesId = root.opt("seriesId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
            seriesName = root.optString("seriesName", ""),
        )
    }

    fun parseRecentMatchesJson(raw: String): List<CdnRecentMatch> {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("matches") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            val mid = o.optString("matchId", "")
            if (mid.isBlank()) return@mapNotNull null
            CdnRecentMatch(
                matchId = mid,
                seriesId = o.opt("seriesId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                seriesName = o.optString("seriesName", ""),
                matchDesc = o.optString("matchDesc", ""),
                matchFormat = o.optString("matchFormat", ""),
                state = o.optString("state", ""),
                status = o.optString("status", ""),
                team1 = o.optString("team1", "T1"),
                team2 = o.optString("team2", "T2"),
                team1Name = o.optString("team1Name", o.optString("team1", "")),
                team2Name = o.optString("team2Name", o.optString("team2", "")),
                team1Score = o.optString("team1Score", ""),
                team2Score = o.optString("team2Score", ""),
                isLive = o.optBoolean("isLive", false),
            )
        }
    }

    fun parsePointsJson(raw: String): CdnPointsTable {
        val root = JSONObject(raw)
        val gArr = root.optJSONArray("groups") ?: JSONArray()
        val groups = (0 until gArr.length()).map { gi ->
            val g = gArr.getJSONObject(gi)
            val tArr = g.optJSONArray("teams") ?: JSONArray()
            val teams = (0 until tArr.length()).map { ti ->
                val t = tArr.getJSONObject(ti)
                val fArr = t.optJSONArray("form") ?: JSONArray()
                val form = (0 until fArr.length()).mapNotNull { fi ->
                    fArr.optString(fi, "").takeIf { it.isNotBlank() }
                }
                CdnPointsTeam(
                    pos = t.optInt("pos", ti + 1),
                    teamId = t.opt("teamId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                    teamName = t.optString("teamName", ""),
                    teamFullName = t.optString("teamFullName", t.optString("teamName", "")),
                    matchesPlayed = t.optInt("matchesPlayed", 0),
                    won = t.optInt("won", 0),
                    lost = t.optInt("lost", 0),
                    tied = t.optInt("tied", 0),
                    nr = t.optInt("nr", 0),
                    points = t.optInt("points", 0),
                    nrr = t.optString("nrr", "0.000"),
                    form = form,
                    qualified = t.optBoolean("qualified", false),
                    imageId = t.opt("imageId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                )
            }
            CdnPointsGroup(
                groupName = g.optString("groupName", "Standings"),
                teams = teams,
            )
        }
        return CdnPointsTable(
            seriesId = root.optString("seriesId", ""),
            seriesName = root.optString("seriesName", "POINTS TABLE"),
            groups = groups,
        )
    }

    fun parseSquadsJson(raw: String): CdnMatchSquads {
        val root = JSONObject(raw)
        val tArr = root.optJSONArray("teams") ?: JSONArray()
        val teams = (0 until tArr.length()).map { ti ->
            val t = tArr.getJSONObject(ti)
            fun parsePlayers(arr: JSONArray?): List<CdnSquadPlayer> {
                if (arr == null) return emptyList()
                return (0 until arr.length()).map { pi ->
                    val p = arr.getJSONObject(pi)
                    CdnSquadPlayer(
                        id = p.opt("id")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                        name = p.optString("name", ""),
                        role = p.optString("role", ""),
                        isCaptain = p.optBoolean("isCaptain", false),
                        isWk = p.optBoolean("isWk", false),
                        isSubstitute = p.optBoolean("isSubstitute", false),
                        imageId = p.opt("imageId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                    )
                }
            }
            CdnSquadTeam(
                teamId = t.opt("teamId")?.toString()?.takeIf { it != "null" && it.isNotBlank() },
                teamName = t.optString("teamName", "Team ${ti + 1}"),
                teamShortName = t.optString("teamShortName", t.optString("teamName", "T${ti + 1}")),
                playingXi = parsePlayers(t.optJSONArray("playingXi")),
                bench = parsePlayers(t.optJSONArray("bench")),
            )
        }
        return CdnMatchSquads(
            matchId = root.optString("matchId", ""),
            title = root.optString("title", "SQUADS"),
            teams = teams,
        )
    }
}
