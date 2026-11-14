package com.actioncut.feature.editor.ui.newsalert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.actioncut.core.model.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToLong

data class FlashNewsSegment(
    val title: String,
    val bullets: List<String>,
    val playerName: String? = null,
    val teamName: String? = null,
    val anchorText: String? = null,
    val startFraction: Float = 0f,
)

@Composable
fun NewsAlertTemplatesDialog(
    onDismiss: () -> Unit,
    onSelectTemplate: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "News Alert Templates",
                        style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF06166E))
                        .clickable { onSelectTemplate() }
                        .padding(14.dp),
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            color = Color(0xFF45A817),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.78f),
                            size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.22f),
                        )
                        val goldWedge = ComposePath().apply {
                            moveTo(20f, 12f)
                            lineTo(64f, 12f)
                            lineTo(44f, size.height * 0.72f)
                            lineTo(0f, size.height * 0.72f)
                            close()
                        }
                        drawPath(goldWedge, color = Color(0xFFFFC107))

                        val mainCard = ComposePath().apply {
                            moveTo(72f, 12f)
                            lineTo(size.width * 0.72f, 12f)
                            lineTo(size.width * 0.67f, size.height * 0.72f)
                            lineTo(52f, size.height * 0.72f)
                            close()
                        }
                        drawPath(mainCard, color = Color(0xFF280A82))
                    }
                    Text(
                        text = "⚡ STADIUM FLASH NEWS",
                        style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 60.dp, bottom = 14.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141416))
                        .border(1.5.dp, Color(0xFFFFC107), RoundedCornerShape(12.dp))
                        .clickable { onSelectTemplate() }
                        .padding(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF242426)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, tint = Color(0xFFFFC107))
                        }
                        Column {
                            Text(
                                text = "STADIUM BROADCAST CARD",
                                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Blue Stadium + Green Pitch Default · Player/Team Accurate Sync",
                                style = TextStyle(color = Color(0xFFFFC107), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FlashNewsTextDialog(
    initialText: String = "",
    knownPlayerNames: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    onAiAutoSync: (List<FlashNewsSegment>) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    var apiKey by remember { mutableStateOf(AiFlashNewsEngine.getSavedApiKey(context)) }
    var showApiKeyBox by remember { mutableStateOf(apiKey.isBlank()) }
    var isBusy by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(18.dp)),
                color = Color(0xFF161618),
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Flash News Text",
                            style = TextStyle(color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { showApiKeyBox = !showApiKeyBox },
                            enabled = !isBusy,
                        ) {
                            Icon(
                                Icons.Filled.Key,
                                contentDescription = "API Key",
                                tint = if (apiKey.isNotBlank()) Color(0xFF00E5FF) else Color(0xFFFFC107),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = if (apiKey.isNotBlank()) "Key ✓" else "API Key",
                                color = if (apiKey.isNotBlank()) Color(0xFF00E5FF) else Color(0xFFFFC107),
                                fontSize = 12.sp,
                            )
                        }
                        IconButton(
                            onClick = { if (!isBusy) onDismiss() },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    if (showApiKeyBox) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                AiFlashNewsEngine.saveApiKey(context, it)
                            },
                            singleLine = true,
                            placeholder = {
                                Text(
                                    "Google AI Studio Free Gemini API Key",
                                    color = Color(0xFF777777),
                                    fontSize = 12.sp,
                                )
                            },
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF202022)),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFC107),
                                unfocusedBorderColor = Color(0xFF333333),
                                cursorColor = Color(0xFFFFC107),
                            ),
                        )
                    }

                    Text(
                        text = "முழு Voiceover கட்டுரையை Paste செய்து 'AI Auto-Sync' அழுத்தினால், Player அல்லது Team பெயர் வரும் சரியான நேரங்களில் Flash News தானாக செட் ஆகும் (Scorecard மீது வராது).",
                        style = TextStyle(color = Color(0xFFB0B0B0), fontSize = 11.5.sp, lineHeight = 16.sp),
                    )

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = {
                            Text(
                                text = "முழு கட்டுரை அல்லது Flash News வரியை இங்கே Paste செய்யவும்...",
                                color = Color(0xFF777777),
                                fontSize = 14.sp,
                            )
                        },
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF202022)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF333333),
                            cursorColor = Color(0xFF00E5FF),
                        ),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { if (!isBusy) onDismiss() },
                            enabled = !isBusy,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(46.dp),
                        ) {
                            Text("Cancel", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                if (text.isNotBlank()) onApply(text)
                            },
                            enabled = text.isNotBlank() && !isBusy,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2C2C2E),
                                disabledContainerColor = Color(0xFF1F1F21),
                            ),
                            modifier = Modifier
                                .weight(0.85f)
                                .height(46.dp),
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(3.dp))
                            Text("Apply", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (text.isBlank() || isBusy) return@Button
                                isBusy = true
                                AiFlashNewsEngine.saveApiKey(context, apiKey)
                                scope.launch {
                                    val res = AiFlashNewsEngine.generateSegments(context, text, knownPlayerNames)
                                    isBusy = false
                                    res.onSuccess { result ->
                                        if (!result.usedAi) {
                                            val reason = result.aiError ?: "No Gemini API Key"
                                            Toast.makeText(
                                                context,
                                                "Offline mode ($reason) — ${result.segments.size} slides",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                        onAiAutoSync(result.segments)
                                    }.onFailure { err ->
                                        Toast.makeText(
                                            context,
                                            "Sync failed: ${err.localizedMessage ?: "Try again"}",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            enabled = text.isNotBlank() && !isBusy,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFC107),
                                disabledContainerColor = Color(0xFF5A4500),
                            ),
                            modifier = Modifier
                                .weight(1.15f)
                                .height(46.dp),
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.Black,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("AI Auto-Sync", color = Color.Black, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

object AiFlashNewsEngine {
    private const val PREFS = "actioncut_ai_news"
    private const val KEY_API = "gemini_api_key"

    data class GenerationResult(
        val segments: List<FlashNewsSegment>,
        val usedAi: Boolean,
        val aiError: String? = null,
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
            aiErrorMsg = aiResult.exceptionOrNull()?.message?.take(90) ?: "AI error"
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
        val models = listOf(
            "gemini-3.5-flash-lite",
            "gemini-3.8-flash",
            "gemini-3-flash",
            "gemini-2.5-flash",
        )
        var lastError: Throwable? = null
        for (model in models) {
            val res = runCatching { callGeminiModel(apiKey, model, article, knownPlayers) }
            if (res.isSuccess && res.getOrNull()?.isNotEmpty() == true) {
                return res
            }
            lastError = res.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("Gemini API unavailable"))
    }

    private fun callGeminiModel(
        apiKey: String,
        model: String,
        article: String,
        knownPlayers: List<String>,
    ): List<FlashNewsSegment> {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val playerHint = if (knownPlayers.isNotEmpty()) {
            "Known human player names in local library (use exact spelling if mentioned): ${knownPlayers.take(120).joinToString(", ")}."
        } else {
            ""
        }

        val prompt = """
            You are a Tamil Cricket TV News Flash Graphics editor.
            Read the entire Tamil cricket voiceover script chronologically from start to end.
            There is NO fixed limit on the number of Flash News items (do NOT limit to 8). Extract EVERY distinct moment in the script where EITHER:
              (A) a specific HUMAN cricket player or coach is discussed, OR
              (B) a cricket TEAM (like India, New Zealand, Australia, England, CSK, RCB, MI, etc.) is discussed as the main topic!

            Do NOT combine two DIFFERENT players (e.g., Shreyas Iyer and Virat Kohli) into the same Flash News card! Whenever the script moves to a new player or team, create a separate card right where that player/team starts being talked about.

            STRICT LANGUAGE & ACCURACY RULES:
            1. ALL "title" and "bullets" text MUST BE WRITTEN IN TAMIL SCRIPT (தமிழ் எழுத்துகளில் மட்டுமே)! NEVER translate into full English sentences!
            2. ONLY cricket player/coach/team names (e.g., "Shreyas Iyer", "Virat Kohli", "Rohit Sharma", "Team India") and numeric stats + short English units (e.g., "351 runs", "6.5 overs", "13 balls 5 runs") should be in English inside the Tamil sentences.
            3. Remove filler conversational phrases ("மக்களே", "யோசிச்சு பாருங்க", "கமெண்ட் பாக்ஸ்ல சொல்லுங்க").
            4. Convert Tamil spoken numbers into crisp English digits + units.
            5. $playerHint
            6. For EACH extracted moment, return:
               - "title": short punchy Tamil badge title (2 to 4 words, e.g., "Shreyas Iyer அதிரடி", "Virat Kohli சாதனை", "Team India திட்டம்")
               - "bullets": array of 1 or 2 complete Tamil sentences (8 to 16 words max) with English player/team names & stats
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
                JSONObject()
                    .put("temperature", 0.15)
                    .put("responseMimeType", "application/json"),
            )
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 28_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            val apiMsg = runCatching {
                JSONObject(body).getJSONObject("error").optString("message")
            }.getOrNull()
            throw IllegalStateException("HTTP $code (${apiMsg ?: model})")
        }

        val root = JSONObject(body)
        val rawText = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

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
                if (trimmed.length > 190) {
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
            val b1 = (sentences.getOrElse(0) { unit }).take(135)
            val b2 = sentences.getOrNull(1)?.takeIf { it.length >= 14 }?.take(135)

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

        // 1. Compute exact non-cascading start times from startFraction inside actual speech bounds
        val desiredStarts = segments.mapIndexed { idx, seg ->
            val raw = speechStart + (seg.startFraction * speechSpan).roundToLong()
            val snapped = quietBuckets
                .filter { abs(it - raw) <= 1_800L }
                .minByOrNull { abs(it - raw) }
                ?: raw
            snapped.coerceIn(speechStart, (speechStart + speechSpan - 2_000L).coerceAtLeast(speechStart))
        }.toMutableList()

        // Ensure strictly ascending starts with >= 2.2s gap without drifting future items far away
        for (i in 1 until desiredStarts.size) {
            if (desiredStarts[i] < desiredStarts[i - 1] + 2_200L) {
                desiredStarts[i] = desiredStarts[i - 1] + 2_200L
            }
        }

        // 2. Build non-overlapping windows capped at 9.5s (or trimmed before next segment) & avoid scorecards
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

            // Trim or skip if overlapping any CRIC / CRIC CDN Scorecard clip
            for (sc in blockedRanges) {
                if (e <= sc.first || s >= sc.last) continue
                if (s >= sc.first && e <= sc.last) {
                    // Completely inside Scorecard -> hide completely
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

    private fun sampleSpeechBoundsMs(
        context: Context,
        audioClips: List<Clip>,
        audioStart: Long,
        audioEnd: Long,
    ): Pair<Long, Long> {
        val primary = audioClips.firstOrNull { it.mediaUri != null } ?: return audioStart to audioEnd
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
        val primary = audioClips.firstOrNull { it.mediaUri != null } ?: return emptyList()
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

/**
 * Full 1920x1080 Star Sports / IPL Stadium Default Background + Slanted Flash News Cards:
 * - Deep Royal Blue Stadium Sky + Glowing Floodlight Beams + Vivid Sunlit Green Turf Pitch
 * - Gold-Amber slanted wedge + Royal Indigo Hero Header Card
 * - Floating Silver-White slanted bullet bars with Tamil Navy text & Crimson Highlights
 */
object NewsBannerRenderer {

    private val fileCounter = AtomicInteger(0)
    private val highlightWordRegex = Regex("[A-Za-z0-9]")

    fun createBannerBitmap(context: Context, rawText: String): String {
        val rawLines = rawText.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return if (rawLines.size >= 2) {
            val title = rawLines.first().trimStart('#', '*', '-', '•', 'o', ' ').trim()
            val bullets = rawLines.drop(1).map {
                it.trimStart('*', '-', '•', 'o', ' ').trim()
            }.filter { it.isNotEmpty() }
            createSegmentBannerBitmap(context, title, bullets)
        } else {
            val sentences = rawText.split(Regex("(?<!\\d)\\.(?!\\d)\\s+"))
                .map { it.trim().trimEnd('.') }
                .filter { it.isNotEmpty() }
            val title = if (sentences.size >= 2) sentences.first().take(48) else "FLASH NEWS"
            val bullets = if (sentences.size >= 2) sentences.drop(1) else sentences
            createSegmentBannerBitmap(context, title, bullets)
        }
    }

    fun createSegmentBannerBitmap(
        context: Context,
        title: String,
        bullets: List<String>,
    ): String {
        val width = 1920
        val height = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawDefaultStadiumBackground(canvas, width.toFloat(), height.toFloat())

        val cleanTitle = title.trim().ifEmpty { "FLASH NEWS" }
        val cleanBullets = bullets.map { it.trim() }.filter { it.isNotEmpty() }

        val cardLeft = 108f
        val cardWidth = 960f
        val goldWedgeW = 86f
        val tabWidth = 80f
        val skew = 36f
        val textPadL = 28f
        val textPadR = 30f
        val topMargin = 135f
        val maxBottom = 875f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 0f, 3f, AndroidColor.argb(180, 0, 0, 0))
        }
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 23f
            color = AndroidColor.parseColor("#FFD500")
            letterSpacing = 0.08f
        }
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var titleSize = 44f
        var bulletSize = 33f
        var headerH = 148f
        var bulletHeights = emptyList<Float>()

        while (bulletSize >= 22f) {
            titlePaint.textSize = titleSize
            val titleMaxW = cardWidth - goldWedgeW - textPadL - textPadR - skew
            val titleLines = wrapLines(cleanTitle, titlePaint, titleMaxW)
            headerH = 54f + titleLines.size * (titleSize * 1.24f) + 22f

            bulletPaint.textSize = bulletSize
            val bLineH = bulletSize * 1.32f
            val bMaxW = cardWidth - tabWidth - textPadL - textPadR - skew
            bulletHeights = cleanBullets.map { b ->
                val bLines = wrapLines(b, bulletPaint, bMaxW)
                bLines.size * bLineH + 36f
            }

            val totalH = headerH + 16f + bulletHeights.sum() + (cleanBullets.size * 12f)
            if (topMargin + totalH <= maxBottom) break

            bulletSize -= 2f
            titleSize = (titleSize - 2f).coerceAtLeast(30f)
        }

        val totalBlockH = headerH + 16f + bulletHeights.sum() + maxOf(0, cleanBullets.size - 1) * 12f
        var currY = topMargin + ((maxBottom - topMargin - totalBlockH).coerceAtLeast(0f) * 0.40f)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(145, 0, 0, 0)
            setShadowLayer(20f, 5f, 9f, AndroidColor.argb(180, 0, 0, 0))
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.8f
        }

        val hdrSkew = skew * (headerH / 120f).coerceIn(0.85f, 1.3f)
        val wedgePath = slantedRect(cardLeft, currY, goldWedgeW, headerH, hdrSkew)
        val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                cardLeft, currY, cardLeft + goldWedgeW, currY + headerH,
                AndroidColor.parseColor("#FFE600"),
                AndroidColor.parseColor("#FF8800"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(wedgePath, shadowPaint)
        canvas.drawPath(wedgePath, wedgePaint)

        val hdrMainX = cardLeft + goldWedgeW - 2f
        val hdrMainW = cardWidth - goldWedgeW + 2f
        val hdrPath = slantedRect(hdrMainX, currY, hdrMainW, headerH, hdrSkew)
        val hdrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                hdrMainX, currY, hdrMainX + hdrMainW, currY + headerH,
                intArrayOf(
                    AndroidColor.parseColor("#18056B"),
                    AndroidColor.parseColor("#2E0CA8"),
                    AndroidColor.parseColor("#0C1C6B"),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(hdrPath, shadowPaint)
        canvas.drawPath(hdrPath, hdrPaint)

        borderPaint.color = AndroidColor.parseColor("#E6F0FF")
        borderPaint.strokeWidth = 3f
        canvas.drawPath(slantedRect(cardLeft, currY, cardWidth, headerH, hdrSkew), borderPaint)

        val hdrTextX = hdrMainX + textPadL
        canvas.drawText("⚡ FLASH NEWS", hdrTextX, currY + 38f, tagPaint)

        titlePaint.textSize = titleSize
        titlePaint.color = AndroidColor.WHITE
        val titleLines = wrapLines(cleanTitle, titlePaint, cardWidth - goldWedgeW - textPadL - textPadR - hdrSkew)
        var ty = currY + 48f + titleSize
        titleLines.forEach { line ->
            canvas.drawText(line, hdrTextX, ty, titlePaint)
            ty += titleSize * 1.24f
        }

        currY += headerH + 16f

        bulletPaint.textSize = bulletSize
        val bLineH = bulletSize * 1.32f
        val bMaxW = cardWidth - tabWidth - textPadL - textPadR - skew

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 26f
            color = AndroidColor.parseColor("#FFE600")
            textAlign = Paint.Align.CENTER
        }
        val goldStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#FFB300")
        }

        cleanBullets.forEachIndexed { idx, sentence ->
            val bh = bulletHeights.getOrElse(idx) { 80f }
            val rowSkew = skew * (bh / 100f).coerceIn(0.65f, 1.15f)
            val rowLeft = cardLeft - 6f

            val fullCardPath = slantedRect(rowLeft, currY, cardWidth, bh, rowSkew)
            canvas.drawPath(fullCardPath, shadowPaint)

            val tabPath = slantedRect(rowLeft, currY, tabWidth, bh, rowSkew)
            val tabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rowLeft, currY, rowLeft + tabWidth, currY + bh,
                    AndroidColor.parseColor("#2A0A8F"),
                    AndroidColor.parseColor("#12044A"),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(tabPath, tabPaint)

            val stripPath = slantedRect(rowLeft + tabWidth - 5f, currY, 6f, bh, rowSkew)
            canvas.drawPath(stripPath, goldStripPaint)

            val numLabel = String.format("%02d", idx + 1)
            canvas.drawText(
                numLabel,
                rowLeft + tabWidth / 2f + rowSkew * 0.45f,
                currY + bh / 2f + 9f,
                numPaint,
            )

            val bodyX = rowLeft + tabWidth
            val bodyW = cardWidth - tabWidth
            val bodyPath = slantedRect(bodyX, currY, bodyW, bh, rowSkew)
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    bodyX, currY, bodyX + bodyW, currY + bh,
                    AndroidColor.parseColor("#FFFFFF"),
                    AndroidColor.parseColor("#E3EAF7"),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(bodyPath, bodyPaint)

            borderPaint.color = AndroidColor.parseColor("#CCFFFFFF")
            borderPaint.strokeWidth = 2.2f
            canvas.drawPath(fullCardPath, borderPaint)

            val lines = wrapLines(sentence, bulletPaint, bMaxW)
            var ly = currY + 22f + bulletSize * 0.9f
            val bxText = bodyX + textPadL + rowSkew * 0.32f
            lines.forEach { line ->
                drawHighlightedLine(
                    canvas = canvas,
                    line = line,
                    startX = bxText,
                    y = ly,
                    paint = bulletPaint,
                    tamilColor = AndroidColor.parseColor("#0F0A38"),
                    highlightColor = AndroidColor.parseColor("#C80036"),
                )
                ly += bLineH
            }

            currY += bh + 12f
        }

        val dir = File(context.filesDir, "news_banners").apply { mkdirs() }
        val file = File(dir, "news_banner_${System.currentTimeMillis()}_${fileCounter.incrementAndGet()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return Uri.fromFile(file).toString()
    }

    private fun drawDefaultStadiumBackground(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.alpha = 255
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                AndroidColor.parseColor("#02083D"),
                AndroidColor.parseColor("#0B2A9E"),
                AndroidColor.parseColor("#091E73"),
                AndroidColor.parseColor("#030A36"),
            ),
            floatArrayOf(0f, 0.38f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = AndroidColor.argb(55, 110, 195, 255)
        val arch1 = Path().apply {
            moveTo(-100f, 520f)
            quadTo(w * 0.50f, 120f, w + 100f, 520f)
        }
        canvas.drawPath(arch1, p)

        p.strokeWidth = 10f
        p.color = AndroidColor.argb(34, 0, 210, 255)
        val arch2 = Path().apply {
            moveTo(-80f, 650f)
            quadTo(w * 0.50f, 290f, w + 80f, 650f)
        }
        canvas.drawPath(arch2, p)

        p.style = Paint.Style.FILL
        p.color = AndroidColor.argb(120, 2, 10, 56)
        val leftRoof = Path().apply {
            moveTo(0f, 180f)
            lineTo(540f, 470f)
            lineTo(0f, 700f)
            close()
        }
        canvas.drawPath(leftRoof, p)

        val rightRoof = Path().apply {
            moveTo(w, 160f)
            lineTo(w - 580f, 470f)
            lineTo(w, 720f)
            close()
        }
        canvas.drawPath(rightRoof, p)

        p.alpha = 255
        p.shader = RadialGradient(
            w * 0.28f, h * 0.34f, w * 0.36f,
            intArrayOf(AndroidColor.argb(115, 45, 165, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.28f, h * 0.34f, w * 0.36f, p)

        p.shader = RadialGradient(
            w * 0.76f, h * 0.42f, w * 0.35f,
            intArrayOf(AndroidColor.argb(165, 70, 185, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.76f, h * 0.42f, w * 0.35f, p)

        p.shader = LinearGradient(
            180f, 0f, 660f, 840f,
            intArrayOf(AndroidColor.argb(95, 130, 230, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        val beamL = Path().apply {
            moveTo(60f, 0f)
            lineTo(360f, 0f)
            lineTo(840f, 880f)
            lineTo(320f, 880f)
            close()
        }
        canvas.drawPath(beamL, p)

        p.shader = LinearGradient(
            w - 180f, 0f, w - 660f, 840f,
            intArrayOf(AndroidColor.argb(105, 130, 230, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        val beamR = Path().apply {
            moveTo(w - 380f, 0f)
            lineTo(w - 60f, 0f)
            lineTo(w - 300f, 880f)
            lineTo(w - 860f, 880f)
            close()
        }
        canvas.drawPath(beamR, p)
        p.shader = null

        p.color = AndroidColor.argb(48, 160, 220, 255)
        for (i in 0..42) {
            val bx = (i * 47f) % w
            val by = 540f + ((i * 29) % 270)
            canvas.drawCircle(bx, by, 2.5f + (i % 4), p)
        }

        val turfPath = Path().apply {
            moveTo(0f, 920f)
            quadTo(w * 0.50f, 770f, w, 920f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.save()
        canvas.clipPath(turfPath)

        p.alpha = 255
        p.shader = RadialGradient(
            w * 0.50f, 830f, w * 0.68f,
            intArrayOf(
                AndroidColor.parseColor("#B8F238"),
                AndroidColor.parseColor("#5DC71C"),
                AndroidColor.parseColor("#1E750A"),
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 760f, w, h, p)
        p.shader = null

        val centerTopX = w * 0.50f
        val centerTopY = 740f
        for (s in -8..8 step 2) {
            p.color = AndroidColor.argb(40, 255, 255, 255)
            val stripe = Path().apply {
                moveTo(centerTopX + s * 65f, centerTopY)
                lineTo(centerTopX + (s + 1) * 65f, centerTopY)
                lineTo(centerTopX + (s + 1) * 210f, h + 40f)
                lineTo(centerTopX + s * 210f, h + 40f)
                close()
            }
            canvas.drawPath(stripe, p)
        }
        canvas.restore()

        p.alpha = 255
        p.style = Paint.Style.STROKE
        p.strokeWidth = 7f
        p.color = AndroidColor.argb(200, 215, 255, 95)
        val rimPath = Path().apply {
            moveTo(0f, 920f)
            quadTo(w * 0.50f, 770f, w, 920f)
        }
        canvas.drawPath(rimPath, p)
        p.style = Paint.Style.FILL
    }

    private fun slantedRect(x: Float, y: Float, w: Float, h: Float, skew: Float): Path =
        Path().apply {
            moveTo(x + skew, y)
            lineTo(x + w + skew, y)
            lineTo(x + w, y + h)
            lineTo(x, y + h)
            close()
        }

    private fun drawHighlightedLine(
        canvas: Canvas,
        line: String,
        startX: Float,
        y: Float,
        paint: Paint,
        tamilColor: Int,
        highlightColor: Int,
    ) {
        val tokens = line.split(" ")
        var cx = startX
        val spaceW = paint.measureText(" ")
        tokens.forEachIndexed { idx, tok ->
            paint.color = if (highlightWordRegex.containsMatchIn(tok)) highlightColor else tamilColor
            canvas.drawText(tok, cx, y, paint)
            cx += paint.measureText(tok)
            if (idx < tokens.lastIndex) cx += spaceW
        }
    }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val test = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(test) > maxWidth && current.isNotEmpty()) {
                lines.add(current)
                current = word
            } else {
                current = test
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }
}
