package com.actioncut.feature.editor.ui.newsalert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import kotlin.math.min
import kotlin.math.roundToLong

data class FlashNewsSegment(
    val title: String,
    val bullets: List<String>,
    val playerName: String? = null,
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
                        .background(Color(0xFF1E1E1E))
                        .clickable { onSelectTemplate() }
                        .padding(14.dp),
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        val path = ComposePath().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width * 0.65f, 0f)
                            lineTo(size.width * 0.76f, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, color = Color(0xFF900C18))
                    }
                    Text(
                        text = "o FLASH NEWS",
                        style = TextStyle(color = Color(0xFFFFD700), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
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
                                text = "NEWS BANNER CARD",
                                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Full 16:9 · Voiceover-க்கு ஏற்ப Auto-Sync & Player Cutout சேரும்",
                                style = TextStyle(color = Color(0xFFFFC107), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Flash News Text:
 * பழைய Dialog அமைப்பு மாறாமல், முழு கட்டுரையை Paste செய்து ✨ AI Auto-Sync செய்யும் வசதியுடன்.
 */
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
                                    "Google AI Studio Free Gemini API Key (Optional for offline)",
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
                        text = "முழு Voiceover கட்டுரையை Paste செய்து 'AI Auto-Sync' அழுத்தினால் 8–9 முக்கிய Flash News + Player Cutout தானாக Timeline-ல் செட் ஆகும். அல்லது 'Apply' மூலம் தனி Banner சேர்க்கலாம்.",
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
                                    res.onSuccess { (segments, usedAi) ->
                                        if (!usedAi && apiKey.isBlank()) {
                                            Toast.makeText(
                                                context,
                                                "Offline Smart Sync (${segments.size} slides). Add Gemini Key for AI summaries.",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        onAiAutoSync(segments)
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
    ): Result<Pair<List<FlashNewsSegment>, Boolean>> = withContext(Dispatchers.IO) {
        val key = getSavedApiKey(context).trim()
        if (key.isNotEmpty()) {
            val aiResult = callGeminiWithFallback(key, fullArticle, knownPlayers)
            if (aiResult.isSuccess) {
                val list = aiResult.getOrNull().orEmpty()
                if (list.isNotEmpty()) return@withContext Result.success(list to true)
            }
        }
        val fallback = offlineFallbackSegments(fullArticle, knownPlayers)
        if (fallback.isNotEmpty()) {
            Result.success(fallback to false)
        } else {
            Result.failure(IllegalStateException("Could not extract Flash News from text"))
        }
    }

    private fun callGeminiWithFallback(
        apiKey: String,
        article: String,
        knownPlayers: List<String>,
    ): Result<List<FlashNewsSegment>> {
        val models = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
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
            "Known player names in local library (prefer exact match if mentioned): ${knownPlayers.take(80).joinToString(", ")}."
        } else {
            ""
        }

        val prompt = """
            You are a Tamil Cricket TV News Flash Graphics editor.
            Read the following Tamil cricket voiceover script carefully and extract 7 to 9 key Flash News moments in exact chronological order from start to end of the article.

            STRICT RULES:
            1. Remove filler conversational phrases ("மக்களே", "யோசிச்சு பாருங்க", "கமெண்ட் பாக்ஸ்ல சொல்லுங்க").
            2. Convert Tamil spoken numbers into crisp English digits + units (e.g., "முன்னூத்தி ஐம்பத்தியொன்னு" -> "351 runs", "ஆறு புள்ளி அஞ்சு ஓவர்" -> "6.5 overs", "ஒன்பது ஓவர்ல எண்பத்திரண்டு" -> "9 overs 82 runs", "பதினாறு பந்துல ஒன்பது" -> "16 balls 9 runs").
            3. Keep cricket player and coach names in English (e.g., "Gautam Gambhir", "Nitish Kumar Reddy", "Anshul Kamboj", "Rohit Sharma", "Virat Kohli", "Shubman Gill").
            4. $playerHint
            5. For each moment return:
               - "title": short punchy Tamil badge title (2 to 4 words, e.g., "அதிர்ச்சி தோல்வி", "கம்பீர் எச்சரிக்கை", "Nitish Kumar சிக்கல்", "இறுதி முடிவு")
               - "bullets": array of 1 or 2 short punchy Tamil+English bullet points (each 8 to 18 words max so it fits cleanly on a TV news banner)
               - "playerName": English name of the main cricketer/coach discussed in that segment (or "" if general team news)
               - "startFraction": float from 0.02 to 0.90 representing where in the article this moment happens chronologically.

            Return ONLY a valid JSON array of objects, no extra text.

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
                    .put("temperature", 0.25)
                    .put("responseMimeType", "application/json"),
            )
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()

        require(code in 200..299) { "HTTP $code: $body" }

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
            val frac = o.optDouble("startFraction", (i + 0.5) / max(arr.length(), 1))
                .toFloat().coerceIn(0.01f, 0.94f)
            out.add(FlashNewsSegment(title = title, bullets = bullets, playerName = player, startFraction = frac))
        }
        return out.sortedBy { it.startFraction }
    }

    private fun offlineFallbackSegments(
        fullArticle: String,
        knownPlayers: List<String>,
    ): List<FlashNewsSegment> {
        val cleaned = normalizeCommonTamilNumbers(fullArticle)
        val rawBlocks = cleaned
            .split(Regex("\\n+"))
            .map { it.trim() }
            .filter { it.length >= 12 }

        val units = if (rawBlocks.size >= 4) {
            rawBlocks
        } else {
            cleaned.split(Regex("(?<!\\d)\\.(?!\\d)\\s+"))
                .map { it.trim() }
                .filter { it.length >= 12 }
        }

        if (units.isEmpty()) {
            return listOf(
                FlashNewsSegment(
                    title = "FLASH NEWS",
                    bullets = listOf(cleaned.take(160)),
                    playerName = detectMentionedPlayer(cleaned, knownPlayers),
                    startFraction = 0.05f,
                ),
            )
        }

        val targetCount = min(9, units.size).coerceAtLeast(1)
        val out = ArrayList<FlashNewsSegment>(targetCount)
        for (k in 0 until targetCount) {
            val idx = ((k.toFloat() / targetCount) * units.size).toInt().coerceIn(0, units.size - 1)
            val block = units[idx]
            val sentences = block.split(Regex("(?<!\\d)\\.(?!\\d)\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val b1 = sentences.getOrElse(0) { block }.take(130)
            val b2 = sentences.getOrNull(1)?.take(130)
            val bullets = listOfNotNull(b1.takeIf { it.isNotEmpty() }, b2?.takeIf { it.isNotEmpty() })
            val player = detectMentionedPlayer(block, knownPlayers)
            val title = when {
                k == targetCount - 1 -> "இறுதி முடிவு"
                player != null -> "$player அப்டேட்"
                k == 0 -> "முக்கிய செய்தி"
                else -> "FLASH NEWS ${k + 1}"
            }
            val frac = (0.04f + (k.toFloat() / max(targetCount, 1)) * 0.86f).coerceIn(0.02f, 0.92f)
            out.add(FlashNewsSegment(title = title, bullets = bullets, playerName = player, startFraction = frac))
        }
        return out
    }

    private fun detectMentionedPlayer(text: String, knownPlayers: List<String>): String? {
        val lower = text.lowercase()
        knownPlayers.firstOrNull { p ->
            val pn = p.trim().lowercase()
            pn.length >= 4 && lower.contains(pn)
        }?.let { return it }

        val commonMap = listOf(
            "nitish kumar reddy" to "Nitish Kumar Reddy",
            "நிதீஷ்" to "Nitish Kumar Reddy",
            "anshul kamboj" to "Anshul Kamboj",
            "அன்ஷுல்" to "Anshul Kamboj",
            "gautam gambhir" to "Gautam Gambhir",
            "கம்பீர்" to "Gautam Gambhir",
            "rohit sharma" to "Rohit Sharma",
            "ரோஹித்" to "Rohit Sharma",
            "virat kohli" to "Virat Kohli",
            "கோலி" to "Virat Kohli",
            "shubman gill" to "Shubman Gill",
            "கில்" to "Shubman Gill",
            "kl rahul" to "KL Rahul",
            "ராகுல்" to "KL Rahul",
            "bumrah" to "Jasprit Bumrah",
            "பும்ரா" to "Jasprit Bumrah",
            "hardik" to "Hardik Pandya",
            "ஹர்திக்" to "Hardik Pandya",
            "suryakumar" to "Suryakumar Yadav",
            "சூர்யகுமார்" to "Suryakumar Yadav",
            "jadeja" to "Ravindra Jadeja",
            "ஜடேஜா" to "Ravindra Jadeja",
            "sanju" to "Sanju Samson",
            "சஞ்சு" to "Sanju Samson",
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
            "gill" to "Shubman Gill",
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
     * Computes voice-pause-aligned (startMs, endMs) windows for each Flash News burst so the
     * banner pops in ~8-11s at important moments and clears cleanly between segments.
     */
    suspend fun detectVoicePauseStartsMs(
        context: Context,
        audioClips: List<Clip>,
        segments: List<FlashNewsSegment>,
        totalDurationMs: Long,
    ): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()
        val safeTotal = totalDurationMs.coerceAtLeast(segments.size * 6_000L)
        val count = segments.size

        // Ideal burst length: ~10s for 6-min video, scaled down gracefully for shorter clips
        val maxWindowPerSeg = (safeTotal / count.toFloat()).toLong().coerceAtLeast(2_500L)
        val burstDur = min(10_500L, (maxWindowPerSeg * 0.58f).toLong().coerceAtLeast(2_200L))

        val quietBuckets = sampleQuietPauseMs(context, audioClips, safeTotal)

        val windows = ArrayList<Pair<Long, Long>>(count)
        var minNextStart = 1_500L.coerceAtMost(safeTotal / 10L)

        for (i in 0 until count) {
            val seg = segments[i]
            val nominalStart = (seg.startFraction * (safeTotal - burstDur))
                .roundToLong()
                .coerceIn(minNextStart, (safeTotal - burstDur).coerceAtLeast(minNextStart))

            // Snap to nearest quiet speech pause within +-3.5 seconds if audio pauses exist
            val snapped = quietBuckets
                .filter { abs(it - nominalStart) <= 3_500L && it >= minNextStart && it + burstDur <= safeTotal }
                .minByOrNull { abs(it - nominalStart) }
                ?: nominalStart

            val startMs = snapped.coerceAtLeast(minNextStart).coerceAtMost((safeTotal - 1_500L).coerceAtLeast(0L))
            val endMs = (startMs + burstDur).coerceAtMost(safeTotal)
            windows.add(startMs to endMs)
            minNextStart = endMs + 1_200L
        }
        windows
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
                if (tUs >= 0L && tUs <= clipDurUs) {
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

object NewsBannerRenderer {

    private val fileCounter = AtomicInteger(0)

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
            val title = if (sentences.size >= 2) sentences.first().take(48) else ""
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

        val bannerWidth = width * 0.52f
        val slantOffset = width * 0.08f

        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(bannerWidth, 0f)
            lineTo(bannerWidth - slantOffset, height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }

        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, bannerWidth, height.toFloat(),
                AndroidColor.parseColor("#8E0614"),
                AndroidColor.parseColor("#4A0208"),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(path, bannerPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            color = AndroidColor.parseColor("#38FFFFFF")
        }
        canvas.drawLine(bannerWidth, 0f, bannerWidth - slantOffset, height.toFloat(), borderPaint)

        val cleanTitle = title.trim()
        val cleanBullets = bullets.map { it.trim() }.filter { it.isNotEmpty() }

        val startX = 64f
        val textStartX = startX + 36f
        val maxTextWidth = bannerWidth - slantOffset - 92f
        val topY = 120f
        val maxBottomY = height - 85f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 1f, 2f, AndroidColor.argb(140, 0, 0, 0))
        }
        val highlightBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#FFE600")
            style = Paint.Style.FILL
        }
        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = AndroidColor.parseColor("#FFB300")
        }

        // Auto-fit font size so all bullets fit inside the 1080p banner without clipping
        var chosenSize = 36f
        while (chosenSize >= 22f) {
            textPaint.textSize = chosenSize
            val lineH = chosenSize * 1.38f
            var simY = topY
            if (cleanTitle.isNotEmpty()) {
                val tLines = wrapLines(cleanTitle, textPaint, maxTextWidth - 18f)
                simY += tLines.size * lineH + 26f
            }
            cleanBullets.forEach { b ->
                val bLines = wrapLines(b, textPaint, maxTextWidth)
                simY += bLines.size * lineH + 28f
            }
            if (simY <= maxBottomY) break
            chosenSize -= 2f
        }

        textPaint.textSize = chosenSize
        val lineHeight = chosenSize * 1.38f
        var currY = topY

        // 1. Yellow Highlight Title Badge (like Screenshot "இறுதி முடிவு")
        if (cleanTitle.isNotEmpty()) {
            bulletPaint.color = AndroidColor.parseColor("#FFB300")
            canvas.drawCircle(startX, currY - chosenSize * 0.30f, 9f, bulletPaint)

            val titleLines = wrapLines(cleanTitle, textPaint, maxTextWidth - 20f)
            textPaint.color = AndroidColor.parseColor("#140A00")
            textPaint.clearShadowLayer()
            titleLines.forEach { line ->
                val tw = textPaint.measureText(line)
                val pillRect = RectF(
                    textStartX - 10f,
                    currY - chosenSize * 0.96f,
                    textStartX + tw + 14f,
                    currY + chosenSize * 0.24f,
                )
                canvas.drawRoundRect(pillRect, 8f, 8f, highlightBgPaint)
                canvas.drawText(line, textStartX, currY, textPaint)
                currY += lineHeight
            }
            currY += 18f
            textPaint.setShadowLayer(4f, 1f, 2f, AndroidColor.argb(140, 0, 0, 0))
        }

        // 2. Crisp White Bold Bullets with 'o' Markers
        textPaint.color = AndroidColor.WHITE
        cleanBullets.forEach { sentence ->
            bulletPaint.color = AndroidColor.parseColor("#FF9800")
            canvas.drawCircle(startX, currY - chosenSize * 0.30f, 9f, bulletPaint)

            val lines = wrapLines(sentence, textPaint, maxTextWidth)
            lines.forEach { line ->
                if (currY <= height - 36f) {
                    canvas.drawText(line, textStartX, currY, textPaint)
                    currY += lineHeight
                }
            }
            currY += 24f
        }

        val dir = File(context.filesDir, "news_banners").apply { mkdirs() }
        val file = File(dir, "news_banner_${System.currentTimeMillis()}_${fileCounter.incrementAndGet()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return Uri.fromFile(file).toString()
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
