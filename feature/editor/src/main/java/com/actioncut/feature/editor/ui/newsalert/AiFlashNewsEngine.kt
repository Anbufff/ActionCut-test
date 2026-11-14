package com.actioncut.feature.editor.ui.newsalert

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import com.actioncut.core.model.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToLong

object AiFlashNewsEngine {
    private const val PREFS = "actioncut_ai_news"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_GEMINI_AUDIO = "gemini_audio_sync"
    private const val INLINE_AUDIO_LIMIT_BYTES = 4 * 1024 * 1024
    private const val MAX_AUDIO_UPLOAD_BYTES = 100L * 1024L * 1024L
    private const val MD_FENCE = "\u0060\u0060\u0060"

    private val ACTIVE_GEMINI_MODELS = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.6-flash",
        "gemini-3.8-flash",
        "gemini-3.1-flash-lite",
        "gemini-3-flash-preview",
    )

    fun isGeminiAudioSync(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GEMINI_AUDIO, false)

    fun setGeminiAudioSync(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GEMINI_AUDIO, enabled)
            .apply()
    }

    private fun toastMain(context: Context, msg: String, longDuration: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context.applicationContext,
                msg,
                if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
        }
    }

    data class GenerationResult(
        val segments: List<FlashNewsSegment>,
        val usedAi: Boolean,
        val aiError: String? = null,
    )

    private data class AudioSyncOutcome(
        val starts: List<Long?>,
        val error: String? = null,
    )

    fun getSavedApiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API, "")
            .orEmpty()

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API, key.trim())
            .apply()
    }

    suspend fun generateSegments(
        context: Context,
        fullArticle: String,
        knownPlayers: List<String>,
    ): Result<GenerationResult> = withContext(Dispatchers.IO) {
        val key = getSavedApiKey(context).trim()
        var aiErrorMsg: String? = null
        if (key.isNotEmpty()) {
            val aiResult = callGeminiWithFallback(key, fullArticle, knownPlayers)
            if (aiResult.isSuccess) {
                val list = aiResult.getOrNull().orEmpty()
                if (list.isNotEmpty()) {
                    return@withContext Result.success(GenerationResult(list, usedAi = true))
                }
            }
            aiErrorMsg = aiResult.exceptionOrNull()?.message?.take(120) ?: "AI error"
        }
        val fallback = offlineFallbackSegments(fullArticle, knownPlayers)
        if (fallback.isNotEmpty()) {
            Result.success(GenerationResult(fallback, usedAi = false, aiError = aiErrorMsg))
        } else {
            Result.failure(IllegalStateException(aiErrorMsg ?: "Could not extract Flash News from text"))
        }
    }

    private fun callGeminiWithFallback(
        apiKey: String,
        article: String,
        knownPlayers: List<String>,
    ): Result<List<FlashNewsSegment>> {
        var firstMeaningfulError: Throwable? = null
        var lastError: Throwable? = null
        for (model in ACTIVE_GEMINI_MODELS) {
            val res = runCatching { callGeminiModel(apiKey, model, article, knownPlayers) }
            if (res.isSuccess && res.getOrNull()?.isNotEmpty() == true) {
                return res
            }
            val err = res.exceptionOrNull()
            if (err != null) {
                lastError = err
                val msg = err.message.orEmpty()
                if (firstMeaningfulError == null && !msg.contains("HTTP 404")) {
                    firstMeaningfulError = err
                }
                if (msg.contains("HTTP 429") || msg.contains("HTTP 503")) {
                    runCatching { Thread.sleep(1_200L) }
                }
            }
        }
        return Result.failure(firstMeaningfulError ?: lastError ?: IllegalStateException("Gemini API unavailable"))
    }

    private fun callGeminiModel(
        apiKey: String,
        model: String,
        article: String,
        knownPlayers: List<String>,
    ): List<FlashNewsSegment> {
        val cleanKey = apiKey.trim()
        val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$encodedKey"
        val playerHint = if (knownPlayers.isNotEmpty()) {
            "Known human player names in local library (use exact spelling if mentioned): ${knownPlayers.take(120).joinToString(", ")}."
        } else {
            ""
        }

        val prompt = """
            You are a senior Tamil Cricket TV News Flash Graphics Editor.
            Read the entire Tamil cricket voiceover script carefully and chronologically from start to end.
            There is NO fixed limit on the number of Flash News items (do NOT limit to 8). Extract EVERY distinct moment in the script where EITHER:
              (A) a specific HUMAN cricket player or coach is discussed, OR
              (B) a cricket TEAM (like India, New Zealand, Australia, England, CSK, RCB, MI, etc.) is discussed as the main topic!

            Do NOT combine two DIFFERENT players (e.g., Shreyas Iyer and Virat Kohli) into the same Flash News card! Whenever the script moves to a new player or team, create a separate card right where that player/team starts being talked about.

            STRICT LANGUAGE, COMPLETENESS & ACCURACY RULES:
            1. ALL "title" and "bullets" text MUST BE WRITTEN IN CLEAR, GRAMMATICALLY COMPLETE TAMIL SCRIPT (தமிழ் எழுத்துகளில் மட்டுமே)! NEVER translate into full English sentences!
            2. NEVER cut off a sentence mid-way or leave out the subject/context! Every bullet MUST be a self-contained, meaningful Tamil news line so the viewer immediately understands the full fact (who did what, against whom, what record/reason/stat).
            3. ONLY cricket player/coach/team names (e.g., "Shreyas Iyer", "Virat Kohli", "Gautam Gambhir", "Team India") and numeric stats + short English units (e.g., "351 runs", "6.5 overs", "13 balls 5 runs", "100*") should be in English inside the Tamil sentences.
            4. Write 2 detailed, informative Tamil bullets per card whenever enough facts are in that section (12 to 26 words per bullet is ideal — do NOT compress too tightly into vague 4-word fragments!).
            5. Remove only casual filler greetings ("மக்களே", "யோசிச்சு பாருங்க", "கமெண்ட் பாக்ஸ்ல சொல்லுங்க"), but KEEP every match detail, opponent name, coach/captain context, score, strike rate, wicket, or milestone.
            6. Convert Tamil spoken numbers into crisp English digits + units (e.g., "முன்னூத்தி ஐம்பத்தியொன்னு ரன்" -> "351 runs").
            7. Good bullet example:
               - "Gautam Gambhir பயிற்சியாளராக வந்த பிறகு, முதல் முறையாக Shreyas Iyer குறைந்த ரன்களில் ஆட்டமிழந்தார்."
               - "நியூசிலாந்து அணிக்கு எதிராக 13 பந்துகளில் வெறும் 5 runs மட்டுமே எடுத்து விக்கெட்டை இழந்தார்."
            8. $playerHint
            9. For EACH extracted moment, return:
               - "title": short punchy Tamil badge title (2 to 5 words, e.g., "Shreyas Iyer அதிரடி அப்டேட்", "Virat Kohli புதிய சாதனை", "Team India திட்டம்")
               - "bullets": array of 1 or 2 complete, detailed Tamil news sentences (12 to 26 words each) with English player/team names & stats
               - "playerName": full English name of the HUMAN cricketer/coach ONLY (or "" if this moment is about a team with no specific human player)
               - "teamName": English team name if team-focused (e.g., "India", "New Zealand", "CSK", or "")
               - "anchorText": EXACT 3 to 5 consecutive words copied verbatim (untouched!) from the original ARTICLE right where this player/team begins to be discussed. This is used to sync the exact timestamp with the voiceover!
               - "startFraction": approximate float 0.0..0.95 as backup.

            Return ONLY a valid JSON array of objects, ordered chronologically.

            ARTICLE:
            $article
        """.trimIndent()

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json"),
            )
        }

        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("x-goog-api-key", cleanKey)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setFixedLengthStreamingMode(payloadBytes.size)
        }
        conn.outputStream.use { it.write(payloadBytes) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            val apiMsg = extractApiError(body)
            throw IllegalStateException("HTTP $code (${apiMsg ?: model})")
        }

        val rawText = extractGeminiResponseText(body, model)
        val arr = if (rawText.startsWith("{")) {
            val obj = JSONObject(rawText)
            obj.optJSONArray("segments") ?: obj.optJSONArray("items") ?: JSONArray()
        } else {
            JSONArray(rawText)
        }

        val totalLen = max(article.length, 1)
        var searchCursor = 0
        val out = ArrayList<FlashNewsSegment>(arr.length())

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val title = o.optString("title", "FLASH NEWS").trim()
            val bArr = o.optJSONArray("bullets") ?: JSONArray()
            val bullets = (0 until bArr.length())
                .map { bArr.optString(it, "").trim() }
                .filter { it.isNotEmpty() }
            if (bullets.isEmpty()) continue

            val player = o.optString("playerName", "").trim().takeIf { it.isNotEmpty() }
            val team = o.optString("teamName", "").trim().takeIf { it.isNotEmpty() }
            if (player == null && team == null) continue

            val anchor = o.optString("anchorText", "").trim().takeIf { it.isNotEmpty() }
            val aiFrac = o.optDouble("startFraction", (i.toDouble() / max(arr.length(), 1)))
                .toFloat().coerceIn(0.0f, 0.96f)

            val matchedIdx = findAnchorCharIndex(article, anchor, player, team, searchCursor)
            val exactFrac = if (matchedIdx >= 0) {
                searchCursor = matchedIdx + 4
                (matchedIdx.toFloat() / totalLen.toFloat()).coerceIn(0.0f, 0.96f)
            } else {
                aiFrac
            }

            out.add(
                FlashNewsSegment(
                    title = title,
                    bullets = bullets,
                    playerName = player,
                    teamName = team,
                    anchorText = anchor,
                    startFraction = exactFrac,
                ),
            )
        }
        return out.sortedBy { it.startFraction }
    }

    private fun extractApiError(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun extractGeminiResponseText(body: String, model: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            throw IllegalStateException(
                if (blockReason.isNotBlank()) "Blocked ($blockReason)" else "Empty response ($model)",
            )
        }
        val cand = candidates.getJSONObject(0)
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("No content parts ($model)")

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val t = part.optString("text", "")
            if (t.isNotEmpty()) sb.append(t)
        }

        var raw = sb.toString().trim()
            .removePrefix("${MD_FENCE}json")
            .removePrefix(MD_FENCE)
            .removeSuffix(MD_FENCE)
            .trim()

        val arrIdx = raw.indexOf('[')
        val objIdx = raw.indexOf('{')
        val start = when {
            arrIdx >= 0 && objIdx >= 0 -> minOf(arrIdx, objIdx)
            arrIdx >= 0 -> arrIdx
            objIdx >= 0 -> objIdx
            else -> -1
        }
        if (start > 0) raw = raw.substring(start)
        return raw.trim()
    }

    /**
     * Locates the exact character index in [article] starting from [fromIdx] so the Flash News
     * aligns mathematically with the spoken voiceover position rather than LLM guess fractions.
     */
    private fun findAnchorCharIndex(
        article: String,
        anchor: String?,
        player: String?,
        team: String?,
        fromIdx: Int,
    ): Int {
        val safeFrom = fromIdx.coerceIn(0, max(0, article.length - 1))
        if (!anchor.isNullOrBlank()) {
            val direct = article.indexOf(anchor, startIndex = safeFrom, ignoreCase = true)
            if (direct >= 0) return direct

            val words = anchor.split(Regex("\\s+")).filter { it.length >= 2 }
            if (words.size >= 2) {
                val twoWord = "${words[0]} ${words[1]}"
                val wMatch = article.indexOf(twoWord, startIndex = safeFrom, ignoreCase = true)
                if (wMatch >= 0) return wMatch
            }
            for (w in words.filter { it.length >= 4 }) {
                val idx = article.indexOf(w, startIndex = safeFrom, ignoreCase = true)
                if (idx >= 0) return idx
            }
        }
        if (!player.isNullOrBlank()) {
            val pIdx = article.indexOf(player, startIndex = safeFrom, ignoreCase = true)
            if (pIdx >= 0) return pIdx
            val lastTok = player.split(Regex("\\s+")).lastOrNull()?.takeIf { it.length >= 4 }
            if (lastTok != null) {
                val lIdx = article.indexOf(lastTok, startIndex = safeFrom, ignoreCase = true)
                if (lIdx >= 0) return lIdx
            }
        }
        if (!team.isNullOrBlank()) {
            val tIdx = article.indexOf(team, startIndex = safeFrom, ignoreCase = true)
            if (tIdx >= 0) return tIdx
        }
        return -1
    }

    private fun offlineFallbackSegments(
        fullArticle: String,
        knownPlayers: List<String>,
    ): List<FlashNewsSegment> {
        val cleaned = normalizeCommonTamilNumbers(fullArticle)
        val rawUnits = cleaned.split(Regex("\\n+"))
            .flatMap { para ->
                val trimmed = para.trim()
                if (trimmed.length > 220) {
                    trimmed.split(Regex("(?<!\\d)\\.(?!\\d)\\s+")).map { it.trim() }
                } else {
                    listOf(trimmed)
                }
            }
            .filter { it.length >= 16 }

        if (rawUnits.isEmpty()) return emptyList()

        val totalLen = max(cleaned.length, 1)
        val out = ArrayList<FlashNewsSegment>()
        var cursor = 0
        var lastSubjectKey: String? = null

        for (unit in rawUnits) {
            val pos = cleaned.indexOf(unit, startIndex = cursor).let { if (it >= 0) it else cursor }
            cursor = pos + unit.length

            val player = detectMentionedPlayer(unit, knownPlayers)
            val team = detectMentionedTeam(unit)
            if (player == null && team == null) continue

            val subjectKey = player ?: "TEAM:$team"
            val sentences = unit.split(Regex("(?<!\\d)\\.(?!\\d)\\s+"))
                .map { it.trim() }
                .filter { it.length >= 10 }
            val b1 = (sentences.getOrElse(0) { unit }).take(220)
            val b2 = sentences.getOrNull(1)?.takeIf { it.length >= 14 }?.take(220)

            // Merge back-to-back units about the exact same player/team if close together
            if (subjectKey == lastSubjectKey && out.isNotEmpty() && out.last().bullets.size < 2) {
                val prev = out.last()
                out[out.lastIndex] = prev.copy(bullets = prev.bullets + b1)
                continue
            }

            lastSubjectKey = subjectKey
            val title = when {
                player != null -> "$player அப்டேட்"
                team != null -> "$team அப்டேட்"
                else -> "FLASH NEWS"
            }
            val frac = (pos.toFloat() / totalLen.toFloat()).coerceIn(0.0f, 0.95f)
            out.add(
                FlashNewsSegment(
                    title = title,
                    bullets = listOfNotNull(b1.takeIf { it.isNotEmpty() }, b2),
                    playerName = player,
                    teamName = team,
                    anchorText = unit.take(32),
                    startFraction = frac,
                ),
            )
        }
        return out
    }

    private fun detectMentionedTeam(text: String): String? {
        val lower = text.lowercase()
        val teamMap = listOf(
            "இந்தியா" to "India",
            "இந்திய அணி" to "India",
            "team india" to "India",
            "india" to "India",
            "நியூசிலாந்து" to "New Zealand",
            "new zealand" to "New Zealand",
            "ஆஸ்திரேலியா" to "Australia",
            "australia" to "Australia",
            "இங்கிலாந்து" to "England",
            "england" to "England",
            "தென் ஆப்பிரிக்கா" to "South Africa",
            "south africa" to "South Africa",
            "பாகிஸ்தான்" to "Pakistan",
            "pakistan" to "Pakistan",
            "இலங்கை" to "Sri Lanka",
            "sri lanka" to "Sri Lanka",
            "வங்கதேசம்" to "Bangladesh",
            "bangladesh" to "Bangladesh",
            "ஆப்கானிஸ்தான்" to "Afghanistan",
            "afghanistan" to "Afghanistan",
            "வெஸ்ட் இண்டீஸ்" to "West Indies",
            "west indies" to "West Indies",
            "சென்னை" to "CSK",
            "csk" to "CSK",
            "மும்பை" to "MI",
            "rcb" to "RCB",
            "பெங்களூரு" to "RCB",
            "கொல்கத்தா" to "KKR",
            "kkr" to "KKR",
            "ஹைதராபாத்" to "SRH",
            "srh" to "SRH",
            "ராஜஸ்தான்" to "RR",
            "டெல்லி" to "DC",
            "பஞ்சாப்" to "PBKS",
            "குஜராத்" to "GT",
            "லக்னோ" to "LSG",
        )
        return teamMap.firstOrNull { (needle, _) -> lower.contains(needle) }?.second
    }

    private fun detectMentionedPlayer(text: String, knownPlayers: List<String>): String? {
        val lower = text.lowercase()
        knownPlayers.firstOrNull { p ->
            val pn = p.trim().lowercase()
            pn.length >= 4 && lower.contains(pn)
        }?.let { return it }

        knownPlayers.firstOrNull { p ->
            val parts = p.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 4 }
            parts.any { token -> lower.contains(token) }
        }?.let { return it }

        val commonMap = listOf(
            "shreyas" to "Shreyas Iyer",
            "iyer" to "Shreyas Iyer",
            "ஷ்ரேயாஸ்" to "Shreyas Iyer",
            "ஸ்ரேயாஸ்" to "Shreyas Iyer",
            "அய்யர்" to "Shreyas Iyer",
            "nitish" to "Nitish Kumar Reddy",
            "நிதீஷ்" to "Nitish Kumar Reddy",
            "ரெட்டி" to "Nitish Kumar Reddy",
            "anshul" to "Anshul Kamboj",
            "kamboj" to "Anshul Kamboj",
            "அன்ஷுல்" to "Anshul Kamboj",
            "கம்போஜ்" to "Anshul Kamboj",
            "gambhir" to "Gautam Gambhir",
            "கம்பீர்" to "Gautam Gambhir",
            "கோச்" to "Gautam Gambhir",
            "பயிற்சியாளர்" to "Gautam Gambhir",
            "rohit" to "Rohit Sharma",
            "ரோஹித்" to "Rohit Sharma",
            "virat" to "Virat Kohli",
            "kohli" to "Virat Kohli",
            "விராட்" to "Virat Kohli",
            "கோலி" to "Virat Kohli",
            "ruturaj" to "Ruturaj Gaikwad",
            "gaikwad" to "Ruturaj Gaikwad",
            "ருதுராஜ்" to "Ruturaj Gaikwad",
            "கெய்க்வாட்" to "Ruturaj Gaikwad",
            "shubman" to "Shubman Gill",
            "gill" to "Shubman Gill",
            "சுப்மன்" to "Shubman Gill",
            "கில்" to "Shubman Gill",
            "suryakumar" to "Suryakumar Yadav",
            "surya" to "Suryakumar Yadav",
            "சூர்யகுமார்" to "Suryakumar Yadav",
            "சூர்யா" to "Suryakumar Yadav",
            "rahul" to "KL Rahul",
            "ராகுல்" to "KL Rahul",
            "bumrah" to "Jasprit Bumrah",
            "பும்ரா" to "Jasprit Bumrah",
            "hardik" to "Hardik Pandya",
            "ஹர்திக்" to "Hardik Pandya",
            "jadeja" to "Ravindra Jadeja",
            "ஜடேஜா" to "Ravindra Jadeja",
            "sanju" to "Sanju Samson",
            "சஞ்சு" to "Sanju Samson",
            "samson" to "Sanju Samson",
            "சாம்சன்" to "Sanju Samson",
            "siraj" to "Mohammed Siraj",
            "சிராஜ்" to "Mohammed Siraj",
            "shami" to "Mohammed Shami",
            "ஷமி" to "Mohammed Shami",
            "pant" to "Rishabh Pant",
            "பண்ட்" to "Rishabh Pant",
            "axar" to "Axar Patel",
            "அக்சர்" to "Axar Patel",
            "kuldeep" to "Kuldeep Yadav",
            "குல்தீப்" to "Kuldeep Yadav",
            "arshdeep" to "Arshdeep Singh",
            "அர்ஷ்தீப்" to "Arshdeep Singh",
            "jaiswal" to "Yashasvi Jaiswal",
            "ஜெய்ஸ்வால்" to "Yashasvi Jaiswal",
            "tilak" to "Tilak Varma",
            "திலக்" to "Tilak Varma",
            "abhishek" to "Abhishek Sharma",
            "அபிஷேக்" to "Abhishek Sharma",
            "rinku" to "Rinku Singh",
            "ரிங்கு" to "Rinku Singh",
            "dube" to "Shivam Dube",
            "துபே" to "Shivam Dube",
            "harshit" to "Harshit Rana",
            "ஹர்ஷித்" to "Harshit Rana",
            "ராணா" to "Harshit Rana",
            "varun" to "Varun Chakaravarthy",
            "வருண்" to "Varun Chakaravarthy",
            "washington" to "Washington Sundar",
            "வாஷிங்டன்" to "Washington Sundar",
            "சுந்தர்" to "Washington Sundar",
            "ishan" to "Ishan Kishan",
            "இஷான்" to "Ishan Kishan",
            "dhoni" to "MS Dhoni",
            "தோனி" to "MS Dhoni",
        )
        return commonMap.firstOrNull { (needle, _) -> lower.contains(needle) }?.second
    }

    private fun normalizeCommonTamilNumbers(input: String): String {
        return input
            .replace("ஆறு புள்ளி அஞ்சு ஓவர்", "6.5 overs")
            .replace("ஆறு புள்ளி ஐந்து ஓவர்", "6.5 overs")
            .replace("ஒன்பது ஓவர்ல", "9 overs-ல்")
            .replace("ஒன்பது ஓவர்", "9 overs")
            .replace("முன்னூத்தி ஐம்பத்தியொன்னு", "351")
            .replace("எண்பத்திரண்டு", "82")
            .replace("பதினாறு பந்து", "16 balls")
            .replace("அம்பது ரன்", "50 runs")
            .replace("ஐம்பது ரன்", "50 runs")
            .replace("ஒன்பது ரன்", "9 runs")
    }

    /**
     * Sends the actual voiceover audio to Gemini (inline for <=4MB, Resumable Files API for >4MB)
     * and asks at which second each segment's player/team is FIRST spoken.
     * Returns timeline-ms per segment (null when unknown). Never throws.
     */
    private fun locateSegmentsInAudio(
        context: Context,
        audioClips: List<Clip>,
        segments: List<FlashNewsSegment>,
    ): AudioSyncOutcome {
        val nothing = List<Long?>(segments.size) { null }
        val key = getSavedApiKey(context).trim()
        if (key.isEmpty()) return AudioSyncOutcome(nothing, "No API Key")
        if (segments.isEmpty()) return AudioSyncOutcome(nothing)

        val clip = audioClips
            .filter { it.mediaUri != null }
            .maxByOrNull { it.timelineDurationMs }
            ?: return AudioSyncOutcome(nothing, "No audio clip")
        val uriStr = clip.mediaUri ?: return AudioSyncOutcome(nothing, "No audio URI")

        var tempCacheFile: File? = null
        var uploadedFileName: String? = null
        return try {
            val uri = Uri.parse(uriStr)

            var knownLen = resolveContentLength(context, uri)
            if (knownLen <= 0L) {
                val tf = File(context.cacheDir, "gemini_audio_tmp_${System.currentTimeMillis()}")
                val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tf).use { output -> input.copyTo(output) }
                }
                if (copied == null) {
                    tf.delete()
                    return AudioSyncOutcome(nothing, "Cannot read audio")
                }
                tempCacheFile = tf
                knownLen = tf.length()
            }
            if (knownLen <= 0L) return AudioSyncOutcome(nothing, "Empty audio")
            if (knownLen > MAX_AUDIO_UPLOAD_BYTES) {
                return AudioSyncOutcome(nothing, "Audio > 100MB")
            }

            val streamProvider: () -> InputStream? = {
                val tf = tempCacheFile
                if (tf != null) tf.inputStream() else context.contentResolver.openInputStream(uri)
            }

            val mime = detectAudioMime(context, uri, uriStr, streamProvider)

            val audioPart: JSONObject = if (knownLen <= INLINE_AUDIO_LIMIT_BYTES) {
                val bytes = streamProvider()?.use { it.readBytes() }
                    ?: return AudioSyncOutcome(nothing, "Cannot read audio")
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", mime).put("data", b64),
                )
            } else {
                val uploaded = uploadToGeminiFilesApi(key, mime, knownLen, streamProvider)
                uploadedFileName = uploaded.second
                JSONObject().put(
                    "file_data",
                    JSONObject().put("mime_type", mime).put("file_uri", uploaded.first),
                )
            }

            val itemLines = segments.mapIndexed { i, seg ->
                val who = seg.playerName ?: seg.teamName ?: ""
                "${i + 1}. name=\"$who\" ; spoken phrase starts like: \"${seg.anchorText.orEmpty()}\""
            }.joinToString("\n")

            val prompt = """
                You are a precise audio timestamp locator for a Tamil cricket voiceover.
                Listen to the attached audio. Below is an ordered list of items. Each item is a
                cricket player (or team) that the narrator talks about, in the SAME ORDER as they
                appear in the audio. The narrator may pronounce English names in Tamil
                (e.g. "ஷ்ரேயாஸ் ஐயர்" = Shreyas Iyer).

                For EACH item, give the time in seconds (decimal, 0.1 precision, measured from the
                very start of the audio file) at which the narrator FIRST begins speaking about that
                player/team, i.e. the moment the name (or the sentence introducing him) is spoken.
                Times must be strictly increasing. If you cannot find an item, use null.

                ITEMS:
                $itemLines

                Return ONLY a JSON array like [{"index":1,"seconds":12.4},{"index":2,"seconds":31.0}].
            """.trimIndent()

            var parsed: List<Double?>? = null
            var firstErr: String? = null
            var lastErr: String? = null
            for (model in ACTIVE_GEMINI_MODELS) {
                val r = runCatching {
                    callGeminiAudioModel(key, model, prompt, audioPart, segments.size)
                }
                val got = r.getOrNull()
                if (got != null && got.any { it != null }) {
                    parsed = got
                    break
                }
                val msg = r.exceptionOrNull()?.message
                if (!msg.isNullOrBlank()) {
                    lastErr = msg
                    if (firstErr == null && !msg.contains("HTTP 404")) {
                        firstErr = msg
                    }
                    if (msg.contains("HTTP 429") || msg.contains("HTTP 503")) {
                        runCatching { Thread.sleep(1_500L) }
                    }
                }
            }

            val secs = parsed ?: return AudioSyncOutcome(
                nothing,
                (firstErr ?: lastErr ?: "No match").take(90),
            )

            var lastMs = -1L
            val mapped = secs.map { sec ->
                if (sec == null) {
                    null
                } else {
                    val srcMs = (sec * 1000.0).roundToLong()
                    val tl = clip.timelineStartMs +
                        ((srcMs - clip.sourceInMs).toDouble() / clip.speed.coerceAtLeast(0.01f)).roundToLong()
                    val inside = tl >= clip.timelineStartMs - 500L && tl <= clip.timelineEndMs + 500L
                    if (inside && tl > lastMs) {
                        lastMs = tl
                        tl
                    } else {
                        null
                    }
                }
            }
            AudioSyncOutcome(mapped, null)
        } catch (t: Throwable) {
            AudioSyncOutcome(nothing, (t.message ?: t.javaClass.simpleName).take(90))
        } finally {
            runCatching { tempCacheFile?.delete() }
            uploadedFileName?.let { fname ->
                runCatching { deleteGeminiUploadedFile(key, fname) }
            }
        }
    }

    private fun resolveContentLength(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            if (f.exists() && f.length() > 0L) return f.length()
        }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0L) return afd.length
            }
        }
        return -1L
    }

    private fun detectAudioMime(
        context: Context,
        uri: Uri,
        uriStr: String,
        streamProvider: () -> InputStream?,
    ): String {
        val header = ByteArray(16)
        val readCount = runCatching {
            streamProvider()?.use { it.read(header) } ?: 0
        }.getOrDefault(0)
        if (readCount >= 12) {
            val h0 = header[0].toInt() and 0xFF
            val h1 = header[1].toInt() and 0xFF
            val h2 = header[2].toInt() and 0xFF
            val h3 = header[3].toInt() and 0xFF
            if (h0 == 'R'.code && h1 == 'I'.code && h2 == 'F'.code && h3 == 'F'.code &&
                header[8].toInt().toChar() == 'W' && header[9].toInt().toChar() == 'A'
            ) {
                return "audio/wav"
            }
            if (h0 == 'O'.code && h1 == 'g'.code && h2 == 'g'.code && h3 == 'S'.code) {
                return "audio/ogg"
            }
            if (h0 == 'f'.code && h1 == 'L'.code && h2 == 'a'.code && h3 == 'C'.code) {
                return "audio/flac"
            }
            if (header[4].toInt().toChar() == 'f' && header[5].toInt().toChar() == 't' &&
                header[6].toInt().toChar() == 'y' && header[7].toInt().toChar() == 'p'
            ) {
                val hasVideo = hasVideoTrack(context, uri)
                return if (hasVideo) "video/mp4" else "audio/mp4"
            }
            if (h0 == 'I'.code && h1 == 'D'.code && h2 == '3'.code) {
                return "audio/mpeg"
            }
            if (h0 == 0xFF && (h1 and 0xE0) == 0xE0) {
                return if ((h1 and 0x06) == 0x00) "audio/aac" else "audio/mpeg"
            }
        }

        val extractorMime = runCatching {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                var hasVid = false
                var audMime: String? = null
                for (i in 0 until ex.trackCount) {
                    val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
                    if (m.startsWith("video/")) hasVid = true
                    if (m.startsWith("audio/") && audMime == null) audMime = m
                }
                when {
                    hasVid -> "video/mp4"
                    audMime == "audio/mp4a-latm" -> "audio/mp4"
                    audMime == "audio/mpeg" -> "audio/mpeg"
                    audMime == "audio/opus" || audMime == "audio/vorbis" -> "audio/ogg"
                    audMime == "audio/flac" -> "audio/flac"
                    audMime == "audio/raw" -> "audio/wav"
                    else -> audMime
                }
            } finally {
                ex.release()
            }
        }.getOrNull()
        if (!extractorMime.isNullOrBlank()) return extractorMime

        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        when {
            fromResolver.contains("mpeg") || fromResolver.contains("mp3") -> return "audio/mpeg"
            fromResolver.contains("wav") -> return "audio/wav"
            fromResolver.contains("aac") -> return "audio/aac"
            fromResolver.contains("ogg") || fromResolver.contains("opus") -> return "audio/ogg"
            fromResolver.contains("flac") -> return "audio/flac"
            fromResolver.contains("m4a") || fromResolver == "audio/mp4" -> return "audio/mp4"
            fromResolver.startsWith("video/") -> return fromResolver
            fromResolver.startsWith("audio/") -> return fromResolver
        }
        val ext = uriStr.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "mp4", "m4v" -> "video/mp4"
            "m4a" -> "audio/mp4"
            else -> "audio/mpeg"
        }
    }

    private fun hasVideoTrack(context: Context, uri: Uri): Boolean = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).any { i ->
                ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
        } finally {
            ex.release()
        }
    }.getOrDefault(false)

    private fun uploadToGeminiFilesApi(
        apiKey: String,
        mime: String,
        contentLength: Long,
        streamProvider: () -> InputStream?,
    ): Pair<String, String> {
        val cleanKey = apiKey.trim()
        val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
        val startUrl = "https://generativelanguage.googleapis.com/upload/v1beta/files?key=$encodedKey"
        val startConn = (URL(startUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("x-goog-api-key", cleanKey)
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", contentLength.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        val metaBytes = JSONObject()
            .put("file", JSONObject().put("display_name", "voiceover_${System.currentTimeMillis()}"))
            .toString()
            .toByteArray(Charsets.UTF_8)
        startConn.setFixedLengthStreamingMode(metaBytes.size)
        startConn.outputStream.use { it.write(metaBytes) }

        val startCode = startConn.responseCode
        val uploadUrl = startConn.getHeaderField("X-Goog-Upload-URL")
            ?: startConn.getHeaderField("x-goog-upload-url")
        val startBody = (if (startCode in 200..299) startConn.inputStream else startConn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        startConn.disconnect()

        if (startCode !in 200..299 || uploadUrl.isNullOrBlank()) {
            throw IllegalStateException("Upload init HTTP $startCode ${extractApiError(startBody).orEmpty()}".trim())
        }

        val upConn = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            setRequestProperty("Content-Type", mime)
            setFixedLengthStreamingMode(contentLength)
        }
        streamProvider()?.use { input ->
            upConn.outputStream.use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        } ?: throw IllegalStateException("Audio stream unavailable")

        val upCode = upConn.responseCode
        val upBody = (if (upCode in 200..299) upConn.inputStream else upConn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        upConn.disconnect()

        if (upCode !in 200..299) {
            throw IllegalStateException("Upload HTTP $upCode ${extractApiError(upBody).orEmpty()}".trim())
        }

        val fileObj = JSONObject(upBody).getJSONObject("file")
        val fileUri = fileObj.getString("uri")
        val fileName = fileObj.optString("name", "")
        var state = fileObj.optString("state", "ACTIVE")

        var polls = 0
        while (state == "PROCESSING" && fileName.isNotBlank() && polls++ < 25) {
            Thread.sleep(1500)
            val pollUrl = "https://generativelanguage.googleapis.com/v1beta/$fileName?key=$encodedKey"
            val pollConn = (URL(pollUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("x-goog-api-key", cleanKey)
            }
            val pCode = pollConn.responseCode
            val pBody = (if (pCode in 200..299) pollConn.inputStream else pollConn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            pollConn.disconnect()
            if (pCode in 200..299) {
                val f = JSONObject(pBody)
                state = f.optString("state", "ACTIVE")
                if (state == "FAILED") throw IllegalStateException("Audio processing failed")
            }
        }
        return fileUri to fileName
    }

    private fun deleteGeminiUploadedFile(apiKey: String, fileName: String) {
        if (fileName.isBlank()) return
        val cleanKey = apiKey.trim()
        val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/$fileName?key=$encodedKey")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("x-goog-api-key", cleanKey)
        }
        runCatching { conn.responseCode }
        conn.disconnect()
    }

    private fun callGeminiAudioModel(
        apiKey: String,
        model: String,
        prompt: String,
        audioPart: JSONObject,
        count: Int,
    ): List<Double?> {
        val cleanKey = apiKey.trim()
        val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$encodedKey"
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(audioPart)
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json"),
            )
        }

        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("x-goog-api-key", cleanKey)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setFixedLengthStreamingMode(payloadBytes.size)
        }
        conn.outputStream.use { it.write(payloadBytes) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val msg = extractApiError(body)
            throw IllegalStateException("HTTP $code (${msg ?: model})")
        }

        val rawText = extractGeminiResponseText(body, model)
        val arr = if (rawText.startsWith("{")) {
            val obj = JSONObject(rawText)
            obj.optJSONArray("items") ?: obj.optJSONArray("segments") ?: obj.optJSONArray("results") ?: JSONArray()
        } else {
            JSONArray(rawText)
        }

        val out = ArrayList<Double?>(count)
        repeat(count) { out.add(null) }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("index", i + 1) - 1
            if (idx !in 0 until count) continue
            out[idx] = parseSecondsValue(o.opt("seconds"))
        }
        return out
    }

    /** Accepts 12.4, "12.4", or "01:12" / "00:01:12" style values. */
    private fun parseSecondsValue(v: Any?): Double? {
        if (v == null || v == JSONObject.NULL) return null
        if (v is Number) return v.toDouble().takeIf { it >= 0.0 }
        val str = v.toString().trim()
        if (str.isEmpty() || str.equals("null", ignoreCase = true)) return null
        if (str.contains(":")) {
            val nums = str.split(":").map { it.trim().toDoubleOrNull() ?: return null }
            var total = 0.0
            for (n in nums) total = total * 60.0 + n
            return total.takeIf { it >= 0.0 }
        }
        return str.toDoubleOrNull()?.takeIf { it >= 0.0 }
    }

    /**
     * Maps each segment onto the ACTUAL Voice Audio span using non-cascading timestamps
     * (never pushes later players late, and skips/trims around any CRIC CDN Scorecard range).
     */
    suspend fun detectVoicePauseStartsMs(
        context: Context,
        audioClips: List<Clip>,
        segments: List<FlashNewsSegment>,
        totalDurationMs: Long,
        blockedRanges: List<LongRange> = emptyList(),
    ): List<Pair<Long, Long>?> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()

        val audioStart = audioClips.minOfOrNull { it.timelineStartMs } ?: 0L
        val audioEnd = audioClips.maxOfOrNull { it.timelineEndMs } ?: totalDurationMs
        val speechBounds = sampleSpeechBoundsMs(context, audioClips, audioStart, audioEnd)
        val speechStart = speechBounds.first
        val speechSpan = (speechBounds.second - speechStart).coerceAtLeast(10_000L)

        val quietBuckets = sampleQuietPauseMs(context, audioClips, audioEnd)

        val voiceRuns: List<LongRange>? = analyzeVoiceRuns(context, audioClips)

        val desiredStarts: MutableList<Long>
        val minGapMs: Long
        if (voiceRuns != null) {
            minGapMs = 1_600L
            val totalVoiced = voiceRuns.sumOf { (it.last - it.first).coerceAtLeast(0L) }.coerceAtLeast(1L)
            desiredStarts = segments.map { seg ->
                val target = (seg.startFraction.coerceIn(0f, 0.97f) * totalVoiced).toLong()
                var cum = 0L
                var raw = voiceRuns.last().first
                for (run in voiceRuns) {
                    val len = (run.last - run.first).coerceAtLeast(0L)
                    if (target <= cum + len) {
                        raw = run.first + (target - cum)
                        break
                    }
                    cum += len
                }
                val snapRun = voiceRuns
                    .filter { abs(it.first - raw) <= 1_000L }
                    .minByOrNull { abs(it.first - raw) }
                val start = if (snapRun != null) snapRun.first - 150L else raw
                start.coerceIn(audioStart, (audioEnd - 2_000L).coerceAtLeast(audioStart))
            }.toMutableList()
        } else {
            minGapMs = 2_200L
            desiredStarts = segments.map { seg ->
                val raw = speechStart + (seg.startFraction * speechSpan).roundToLong()
                val snapped = quietBuckets
                    .filter { abs(it - raw) <= 1_800L }
                    .minByOrNull { abs(it - raw) }
                    ?: raw
                snapped.coerceIn(speechStart, (speechStart + speechSpan - 2_000L).coerceAtLeast(speechStart))
            }.toMutableList()
        }

        val useGeminiAudio = isGeminiAudioSync(context)
        val audioOutcome = if (useGeminiAudio) {
            locateSegmentsInAudio(context, audioClips, segments)
        } else {
            AudioSyncOutcome(List<Long?>(segments.size) { null })
        }
        val exactStarts = audioOutcome.starts
        val isExact = BooleanArray(segments.size)
        exactStarts.forEachIndexed { i, ex ->
            if (ex != null && i < desiredStarts.size) {
                desiredStarts[i] = (ex - 250L).coerceAtLeast(audioStart)
                isExact[i] = true
            }
        }
        val exactCount = isExact.count { it }
        val errNote = audioOutcome.error?.let { " ($it)" }.orEmpty()
        val failedAudio = useGeminiAudio && exactCount == 0
        val methodLabel = when {
            useGeminiAudio && exactCount > 0 -> "Gemini Audio sync ($exactCount/${segments.size})"
            useGeminiAudio && voiceRuns != null -> "Gemini Audio failed$errNote → Phone sync"
            useGeminiAudio -> "Gemini Audio failed$errNote → Basic sync"
            voiceRuns != null -> "Phone sync"
            else -> "Basic sync"
        }
        toastMain(context, methodLabel, longDuration = failedAudio)

        if (exactCount in 1 until segments.size) {
            var idx = 0
            while (idx < segments.size) {
                if (!isExact[idx]) {
                    val prevExact = (idx - 1 downTo 0).firstOrNull { isExact[it] }
                    val nextExact = (idx + 1 until segments.size).firstOrNull { isExact[it] }
                    if (prevExact != null && nextExact != null) {
                        val spanMs = (desiredStarts[nextExact] - desiredStarts[prevExact]).coerceAtLeast(1L)
                        val countBetween = nextExact - prevExact
                        for (k in (prevExact + 1) until nextExact) {
                            val step = (k - prevExact).toDouble() / countBetween.toDouble()
                            desiredStarts[k] = desiredStarts[prevExact] + (spanMs * step).roundToLong()
                        }
                        idx = nextExact
                        continue
                    }
                }
                idx++
            }
        }

        for (i in 1 until desiredStarts.size) {
            if (!isExact[i] && desiredStarts[i] < desiredStarts[i - 1] + minGapMs) {
                val nextExact = (i + 1 until segments.size).firstOrNull { isExact[it] }
                val candidate = desiredStarts[i - 1] + minGapMs
                desiredStarts[i] = if (nextExact != null && candidate >= desiredStarts[nextExact]) {
                    ((desiredStarts[i - 1] + desiredStarts[nextExact]) / 2L)
                } else {
                    candidate
                }
            }
        }

        val result = ArrayList<Pair<Long, Long>?>(segments.size)
        for (i in segments.indices) {
            var s = desiredStarts[i]
            val nextS = desiredStarts.getOrNull(i + 1)
            val maxDur = 9_500L
            var e = if (nextS != null) {
                minOf(s + maxDur, nextS - 350L)
            } else {
                minOf(s + maxDur, speechStart + speechSpan)
            }

            for (sc in blockedRanges) {
                if (e <= sc.first || s >= sc.last) continue
                if (s >= sc.first && e <= sc.last) {
                    e = s
                    break
                } else if (s < sc.first && e > sc.first) {
                    e = sc.first - 150L
                } else if (s < sc.last && e > sc.last) {
                    s = sc.last + 150L
                }
            }

            if (e - s >= 1_500L) {
                result.add(s to e)
            } else {
                result.add(null)
            }
        }
        result
    }

    private fun analyzeVoiceRuns(context: Context, audioClips: List<Clip>): List<LongRange>? {
        val clip = audioClips
            .filter { it.mediaUri != null }
            .maxByOrNull { it.timelineDurationMs }
            ?: return null
        val uriStr = clip.mediaUri ?: return null
        val speed = clip.speed.coerceAtLeast(0.01f).toDouble()
        val sourceDurMs = if (clip.sourceDurationMs > 0L) {
            clip.sourceDurationMs
        } else {
            (clip.timelineDurationMs * speed).toLong()
        }
        if (sourceDurMs < 3_000L) return null

        val frameMs = 40
        val maxFrames = (sourceDurMs / frameMs).toInt() + 2
        if (maxFrames > 60_000) return null
        val sumSq = DoubleArray(maxFrames)
        val counts = IntArray(maxFrames)

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uriStr), null)
            val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(trackIdx)

            val startUs = clip.sourceInMs * 1_000L
            val endUs = startUs + sourceDurMs * 1_000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var guard = 0
            val frameUs = frameMs * 1_000L

            while (!outputDone && guard++ < 400_000) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx)
                        val sz = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        val t = extractor.sampleTime
                        if (sz < 0 || t > endUs) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, sz, t, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = dec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val ch = channels.coerceAtLeast(1)
                        val n = shorts.remaining() / ch
                        val basePts = info.presentationTimeUs
                        for (i in 0 until n) {
                            var acc = 0.0
                            for (c in 0 until ch) {
                                acc += shorts.get(i * ch + c) / 32768.0
                            }
                            val mono = acc / ch
                            val tUs = basePts + (i * 1_000_000L) / sampleRate
                            val idx = ((tUs - startUs) / frameUs).toInt()
                            if (idx in 0 until maxFrames) {
                                sumSq[idx] += mono * mono
                                counts[idx]++
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = dec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } catch (e: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        val rms = FloatArray(maxFrames) { i ->
            if (counts[i] > 0) Math.sqrt(sumSq[i] / counts[i]).toFloat() else 0f
        }
        val nonZero = rms.filter { it > 0f }.sorted()
        if (nonZero.size < 50) return null
        val p20 = nonZero[(nonZero.size * 0.20).toInt().coerceIn(0, nonZero.size - 1)]
        val p95 = nonZero[(nonZero.size * 0.95).toInt().coerceIn(0, nonZero.size - 1)]
        if (p95 <= 0f) return null
        val threshold = max(p20 * 2.5f, p95 * 0.05f)

        val minPauseFrames = 7
        val minRunFrames = 5
        val runsFrames = ArrayList<IntRange>()
        var runStart = -1
        var lastVoiced = -1
        for (i in 0 until maxFrames) {
            if (rms[i] > threshold) {
                if (runStart < 0) runStart = i
                lastVoiced = i
            } else if (runStart >= 0 && i - lastVoiced >= minPauseFrames) {
                if (lastVoiced - runStart + 1 >= minRunFrames) runsFrames.add(runStart..lastVoiced)
                runStart = -1
            }
        }
        if (runStart >= 0 && lastVoiced - runStart + 1 >= minRunFrames) runsFrames.add(runStart..lastVoiced)
        if (runsFrames.isEmpty()) return null

        val runs = runsFrames.map { r ->
            val relStartMs = r.first * frameMs.toLong()
            val relEndMs = (r.last + 1) * frameMs.toLong()
            val tlStart = clip.timelineStartMs + (relStartMs / speed).roundToLong()
            val tlEnd = clip.timelineStartMs + (relEndMs / speed).roundToLong()
            tlStart..tlEnd
        }
        val totalVoiced = runs.sumOf { (it.last - it.first).coerceAtLeast(0L) }
        return if (totalVoiced >= 3_000L) runs else null
    }

    private fun sampleSpeechBoundsMs(
        context: Context,
        audioClips: List<Clip>,
        audioStart: Long,
        audioEnd: Long,
    ): Pair<Long, Long> {
        val primary = audioClips
            .filter { it.mediaUri != null }
            .maxByOrNull { it.timelineDurationMs }
            ?: return audioStart to audioEnd
        val uriStr = primary.mediaUri ?: return audioStart to audioEnd
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(context, Uri.parse(uriStr), null)
            val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@runCatching audioStart to audioEnd
            extractor.selectTrack(trackIdx)

            val bucketCount = 100
            val byteSums = LongArray(bucketCount)
            val byteCounts = IntArray(bucketCount)
            val buf = ByteBuffer.allocate(16 * 1024)
            val clipDurUs = primary.timelineDurationMs.coerceAtLeast(1_000L) * 1_000L
            var frames = 0
            while (frames++ < 25_000) {
                val sz = extractor.readSampleData(buf, 0)
                if (sz < 0) break
                val tUs = extractor.sampleTime - primary.sourceInMs * 1_000L
                if (tUs in 0L..clipDurUs) {
                    val b = ((tUs.toDouble() / clipDurUs) * (bucketCount - 1)).toInt().coerceIn(0, bucketCount - 1)
                    byteSums[b] += sz.toLong()
                    byteCounts[b]++
                }
                extractor.advance()
            }
            val avgs = DoubleArray(bucketCount) { i ->
                if (byteCounts[i] > 0) byteSums[i].toDouble() / byteCounts[i] else 0.0
            }
            val peak = avgs.maxOrNull() ?: 0.0
            if (peak <= 0.0) return@runCatching audioStart to audioEnd
            val voiceThreshold = peak * 0.18
            val firstVoice = avgs.indexOfFirst { it >= voiceThreshold }.takeIf { it >= 0 } ?: 0
            val lastVoice = avgs.indexOfLast { it >= voiceThreshold }.takeIf { it >= 0 } ?: (bucketCount - 1)
            val startMs = primary.timelineStartMs + ((firstVoice.toDouble() / bucketCount) * primary.timelineDurationMs).roundToLong()
            val endMs = primary.timelineStartMs + (((lastVoice + 1).toDouble() / bucketCount) * primary.timelineDurationMs).roundToLong()
            if (endMs - startMs >= 5_000L) startMs to endMs else audioStart to audioEnd
        }.getOrDefault(audioStart to audioEnd).also {
            runCatching { extractor.release() }
        }
    }

    private fun sampleQuietPauseMs(
        context: Context,
        audioClips: List<Clip>,
        totalMs: Long,
    ): List<Long> {
        val primary = audioClips
            .filter { it.mediaUri != null }
            .maxByOrNull { it.timelineDurationMs }
            ?: return emptyList()
        val uriStr = primary.mediaUri ?: return emptyList()
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(context, Uri.parse(uriStr), null)
            val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@runCatching emptyList()
            extractor.selectTrack(trackIdx)

            val bucketCount = 120
            val byteSums = LongArray(bucketCount)
            val byteCounts = IntArray(bucketCount)
            val buf = ByteBuffer.allocate(16 * 1024)
            val clipDurUs = primary.timelineDurationMs.coerceAtLeast(1_000L) * 1_000L
            var frames = 0

            while (frames++ < 25_000) {
                val sz = extractor.readSampleData(buf, 0)
                if (sz < 0) break
                val tUs = extractor.sampleTime - primary.sourceInMs * 1_000L
                if (tUs in 0L..clipDurUs) {
                    val b = ((tUs.toDouble() / clipDurUs) * (bucketCount - 1))
                        .toInt().coerceIn(0, bucketCount - 1)
                    byteSums[b] += sz.toLong()
                    byteCounts[b]++
                }
                extractor.advance()
            }

            val avgs = DoubleArray(bucketCount) { i ->
                if (byteCounts[i] > 0) byteSums[i].toDouble() / byteCounts[i] else Double.MAX_VALUE
            }
            val validAvgs = avgs.filter { it < Double.MAX_VALUE }.sorted()
            if (validAvgs.isEmpty()) return@runCatching emptyList()
            val threshold = validAvgs[(validAvgs.size * 0.25).toInt().coerceIn(0, validAvgs.size - 1)]

            val pauses = ArrayList<Long>()
            for (i in 1 until bucketCount - 1) {
                if (avgs[i] <= threshold) {
                    val timelineMs = primary.timelineStartMs +
                        ((i.toDouble() / bucketCount) * primary.timelineDurationMs).roundToLong()
                    if (timelineMs in 0L..totalMs) pauses.add(timelineMs)
                }
            }
            pauses
        }.getOrDefault(emptyList()).also {
            runCatching { extractor.release() }
        }
    }

    private fun abs(v: Long): Long = if (v < 0L) -v else v
}
