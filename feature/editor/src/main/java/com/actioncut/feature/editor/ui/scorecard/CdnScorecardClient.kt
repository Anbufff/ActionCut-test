package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
)

data class CdnMatchScorecard(
    val matchId: String,
    val title: String,
    val status: String,
    val source: String,
    val innings: List<CdnInnings>,
)

object CdnScorecardClient {
    private const val PREFS = "actioncut_cdn_scorecard"
    private const val KEY_WORKER_URL = "worker_url"

    const val DEFAULT_WORKER_URL = "https://restless-field-a018.anbufff2.workers.dev"

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

    /** Fetches match scorecard JSON immediately without blocking on all player photos. */
    suspend fun fetchMatchOnly(workerBaseUrl: String, matchInput: String): Result<Pair<CdnMatchScorecard, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = workerBaseUrl.trim().trimEnd('/')
                require(base.startsWith("http")) { "Valid Cloudflare Worker URL required" }
                val encoded = URLEncoder.encode(matchInput.trim(), "UTF-8")
                val sep = if (base.contains("?")) "&" else "?"
                val reqUrl = "${base}${sep}matchId=$encoded&_=${System.currentTimeMillis()}"

                val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()

                if (code !in 200..299) {
                    val errMsg = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw IllegalStateException(errMsg?.ifBlank { null } ?: "HTTP $code")
                }

                val parsed = parseJson(body)
                require(parsed.innings.isNotEmpty()) { "Scorecard data not available for this match yet." }
                parsed to body
            }
        }

    /** Downloads all batter & bowler photos for an innings in parallel (up to 6 concurrent). */
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
        }.distinctBy { (imgId, pid, _) -> "${imgId.orEmpty()}_${pid.orEmpty()}" }

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

    /** Ensures a single player's 600x600 Cricbuzz photo is cached locally and returns true if downloaded/present. */
    suspend fun ensurePlayerPhoto(
        context: Context,
        workerBaseUrl: String,
        imageId: String?,
        playerId: String?,
        playerName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "scorecards/cdn_players").apply { mkdirs() }
        val target = when {
            !imageId.isNullOrBlank() -> File(dir, "img_$imageId.jpg")
            !playerId.isNullOrBlank() -> File(dir, "pid_$playerId.jpg")
            else -> return@withContext false
        }
        if (target.exists() && target.length() > 0L) return@withContext true

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
                    val tmp = File(dir, "${target.name}.tmp")
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    bmp.recycle()
                    tmp.renameTo(target)
                } else false
            } else false
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    fun cachedPlayerPhoto(context: Context, imageId: String?, playerId: String?): Bitmap? {
        val dir = File(context.filesDir, "scorecards/cdn_players")
        val candidates = buildList {
            if (!imageId.isNullOrBlank()) add(File(dir, "img_$imageId.jpg"))
            if (!playerId.isNullOrBlank()) add(File(dir, "pid_$playerId.jpg"))
        }
        val file = candidates.firstOrNull { it.exists() && it.length() > 0L } ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
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
            )
        }
        return CdnMatchScorecard(
            matchId = root.optString("matchId", ""),
            title = root.optString("title", "CRICKET SCORECARD"),
            status = root.optString("status", ""),
            source = root.optString("source", ""),
            innings = inningsList,
        )
    }
}
