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
                                text = "Blue Stadium + Green Pitch Default · Tamil AI Auto-Sync & Cutout",
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
                        text = "முழு Voiceover கட்டுரையை Paste செய்து 'AI Auto-Sync' அழுத்தினால், ஸ்டேடியம் Background-ல் தமிழ் Flash News + Player Cutout தானாக Timeline-ல் செட் ஆகும்.",
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
            "Known player names in local library (prefer exact match if mentioned or implied as captain/coach): ${knownPlayers.take(120).joinToString(", ")}."
        } else {
            ""
        }

        val prompt = """
            You are a Tamil Cricket TV News Flash Graphics editor.
            Read the following Tamil cricket voiceover script carefully and extract 8 to 9 key Flash News moments in exact chronological order from start to end of the article.

            STRICT LANGUAGE & FORMAT RULES:
            1. ALL "title" and "bullets" text MUST BE WRITTEN IN TAMIL SCRIPT (தமிழ் எழுத்துகளில் மட்டுமே இருக்க வேண்டும்)! NEVER translate the Tamil script into full English sentences!
            2. ONLY cricket player/coach names (e.g., "Rohit Sharma", "Ruturaj Gaikwad", "Gautam Gambhir", "Nitish Kumar Reddy") and numeric stats + short English units (e.g., "351 runs", "6.5 overs", "13 balls 5 runs", "0 runs") should be in English inside the Tamil sentences!
               GOOD example bullet: "முதல் பந்திலேயே டக் அவுட் ஆகி ஏமாற்றம் அளித்தார் Rohit Sharma."
               GOOD example bullet: "13 balls-ல் வெறும் 5 runs மட்டுமே எடுத்து சொதப்பினார் Ruturaj Gaikwad."
               BAD example (DO NOT DO THIS): "Rohit Sharma got out on a golden duck without scoring any runs."
            3. Remove filler conversational phrases ("மக்களே", "யோசிச்சு பாருங்க", "கமெண்ட் பாக்ஸ்ல சொல்லுங்க").
            4. Convert Tamil spoken numbers into crisp English digits + units (e.g., "முன்னூத்தி ஐம்பத்தியொன்னு" -> "351 runs", "ஆறு புள்ளி அஞ்சு ஓவர்" -> "6.5 overs").
            5. $playerHint
            6. Even if a paragraph refers to "கேப்டன்" (captain), "கோச்" (coach), or "அவர்" (he) without repeating the name, infer the actual cricketer/coach from context and set "playerName" to their full English name!
            7. For each moment return:
               - "title": short punchy Tamil badge title (2 to 4 words, e.g., "Rohit Sharma டக்", "Ruturaj சொதப்பல்", "இறுதி முடிவு")
               - "bullets": array of 1 or 2 complete Tamil sentences (each 8 to 16 words max) with English player names & numeric stats
               - "playerName": English full name of the main cricketer/coach discussed in that segment
               - "startFraction": float from 0.02 to 0.90 representing chronological position in the article.

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
                    .put("temperature", 0.2)
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
            val frac = o.optDouble("startFraction", (i + 0.5) / kotlin.math.max(arr.length(), 1))
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
        val rawParas = cleaned.split(Regex("\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val mergedUnits = ArrayList<String>()
        for (p in rawParas) {
            if (mergedUnits.isNotEmpty() && (mergedUnits.last().length < 36 || p.length < 28)) {
                mergedUnits[mergedUnits.lastIndex] = "${mergedUnits.last()} $p"
            } else {
                mergedUnits.add(p)
            }
        }

        val units = if (mergedUnits.size >= 4) {
            mergedUnits
        } else {
            cleaned.split(Regex("(?<!\\d)\\.(?!\\d)\\s+"))
                .map { it.trim() }
                .filter { it.length >= 20 }
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
        var lastPlayer: String? = null

        for (k in 0 until targetCount) {
            val idx = ((k.toFloat() / targetCount) * units.size).toInt().coerceIn(0, units.size - 1)
            val block = units[idx]
            val sentences = block.split(Regex("(?<!\\d)\\.(?!\\d)\\s+"))
                .map { it.trim() }
                .filter { it.length >= 10 }

            val b1 = (sentences.getOrElse(0) { block }).take(135)
            val b2 = sentences.getOrNull(1)?.takeIf { it.length >= 16 }?.take(135)
            val bullets = listOfNotNull(b1.takeIf { it.isNotEmpty() }, b2)

            val directPlayer = detectMentionedPlayer(block, knownPlayers)
            val player = directPlayer ?: if (
                lastPlayer != null &&
                (block.contains("அவர்") || block.contains("இவர்") || block.contains("இவர") || block.contains("கேப்டன்"))
            ) {
                lastPlayer
            } else {
                detectMentionedPlayer(fullArticle, knownPlayers)
            }
            if (directPlayer != null) lastPlayer = directPlayer

            val title = when {
                k == targetCount - 1 -> "இறுதி முடிவு"
                player != null -> "$player அப்டேட்"
                k == 0 -> "முக்கிய செய்தி"
                else -> "FLASH NEWS ${k + 1}"
            }
            val frac = (0.04f + (k.toFloat() / kotlin.math.max(targetCount, 1)) * 0.86f).coerceIn(0.02f, 0.92f)
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

        knownPlayers.firstOrNull { p ->
            val parts = p.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 4 }
            parts.any { token -> lower.contains(token) }
        }?.let { return it }

        val commonMap = listOf(
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
            "கேப்டன்" to "Suryakumar Yadav",
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
            "shreyas" to "Shreyas Iyer",
            "iyer" to "Shreyas Iyer",
            "ஷ்ரேயாஸ்" to "Shreyas Iyer",
            "அய்யர்" to "Shreyas Iyer",
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

    suspend fun detectVoicePauseStartsMs(
        context: Context,
        audioClips: List<Clip>,
        segments: List<FlashNewsSegment>,
        totalDurationMs: Long,
    ): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) return@withContext emptyList()
        val safeTotal = totalDurationMs.coerceAtLeast(segments.size * 6_000L)
        val count = segments.size

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

/**
 * Full 1920x1080 Star Sports / IPL Stadium Default Background + Slanted Flash News Cards:
 * - Deep Royal Blue Stadium Sky + Glowing Floodlight Beams + Curved Green Turf Pitch at bottom
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

        // 0. Draw Default Stadium Arena + Floodlights + Curved Green Cricket Pitch Background
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
        val maxBottom = 875f // keeps cards neatly above the green pitch horizon curve

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

        // 1. HERO HEADER PARALLELOGRAM (Gold Wedge + Deep Royal Indigo Card)
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

        // 2. FLOATING SLANTED BULLET CARDS
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

    /**
     * Draws the full-frame Royal Blue Cricket Stadium + Floodlight Beams + Curved Green
     * Outfield Pitch default background matching the user's maxresdefault reference image.
     */
    private fun drawDefaultStadiumBackground(canvas: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Deep Royal Blue Stadium Sky Gradient
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

        // 2. Upper Stadium Roof / Bowl Architectural Curves
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = AndroidColor.argb(45, 110, 195, 255)
        val arch1 = Path().apply {
            moveTo(-100f, 520f)
            quadTo(w * 0.50f, 120f, w + 100f, 520f)
        }
        canvas.drawPath(arch1, p)

        p.strokeWidth = 10f
        p.color = AndroidColor.argb(28, 0, 210, 255)
        val arch2 = Path().apply {
            moveTo(-80f, 650f)
            quadTo(w * 0.50f, 290f, w + 80f, 650f)
        }
        canvas.drawPath(arch2, p)

        // Left & Right Stadium Floodlight Truss Diagonal Silhouettes
        p.style = Paint.Style.FILL
        p.color = AndroidColor.argb(110, 2, 10, 56)
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

        // 3. Glowing Stadium Floodlight Halos (Behind Cards & Behind Right Player)
        p.shader = RadialGradient(
            w * 0.28f, h * 0.34f, w * 0.36f,
            intArrayOf(AndroidColor.argb(95, 45, 155, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.28f, h * 0.34f, w * 0.36f, p)

        p.shader = RadialGradient(
            w * 0.76f, h * 0.42f, w * 0.34f,
            intArrayOf(AndroidColor.argb(135, 65, 175, 255), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(w * 0.76f, h * 0.42f, w * 0.34f, p)

        // Diagonal Floodlight Beams shining down from upper left & upper right
        p.shader = LinearGradient(
            180f, 0f, 660f, 840f,
            intArrayOf(AndroidColor.argb(72, 120, 225, 255), AndroidColor.TRANSPARENT),
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
            intArrayOf(AndroidColor.argb(78, 120, 225, 255), AndroidColor.TRANSPARENT),
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

        // Subtle Stadium Crowd & Bokeh Dots in the distant mid-band
        p.color = AndroidColor.argb(42, 160, 220, 255)
        for (i in 0..42) {
            val bx = (i * 47f) % w
            val by = 540f + ((i * 29) % 270)
            canvas.drawCircle(bx, by, 2.5f + (i % 4), p)
        }

        // 4. Curved Lush Green Cricket Outfield Turf at the Bottom
        val turfPath = Path().apply {
            moveTo(0f, 920f)
            quadTo(w * 0.50f, 770f, w, 920f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        canvas.save()
        canvas.clipPath(turfPath)

        p.shader = RadialGradient(
            w * 0.50f, 850f, w * 0.62f,
            intArrayOf(
                AndroidColor.parseColor("#A4EB34"),
                AndroidColor.parseColor("#54B818"),
                AndroidColor.parseColor("#1D6E0A"),
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 760f, w, h, p)
        p.shader = null

        // Alternating Mowed Turf Stripes radiating from horizon
        val centerTopX = w * 0.50f
        val centerTopY = 740f
        for (s in -8..8 step 2) {
            p.color = AndroidColor.argb(30, 255, 255, 255)
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

        // Bright Floodlight Rim Glow along the Grass Horizon Curve
        p.style = Paint.Style.STROKE
        p.strokeWidth = 6f
        p.color = AndroidColor.argb(145, 195, 255, 95)
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
