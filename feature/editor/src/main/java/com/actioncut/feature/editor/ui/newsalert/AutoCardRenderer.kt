package com.actioncut.feature.editor.ui.newsalert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
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
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

data class AutoCardEntry(
    val index: Int,
    val playerName: String,
    val text: String,
    val customPhotoUri: String? = null,
    val layoutIdx: Int = index % AutoCardRenderer.LAYOUT_COUNT,
)

/**
 * Smart Multi-Layout + Pure Jersey Color Auto-Card Renderer (100% Offline, 1920x1080).
 * Supports both built-in stadium/jersey templates and a persistent user-uploaded Custom Background.
 */
object AutoCardRenderer {

    const val LAYOUT_COUNT = 6
    private const val CANVAS_W = 1920f
    private const val CANVAS_H = 1080f
    private const val PREFS = "actioncut_auto_card_prefs"
    private const val KEY_USE_CUSTOM_BG = "use_custom_bg"
    private const val CUSTOM_BG_FILENAME = "custom_bg.jpg"
    private val fileCounter = AtomicInteger(0)
    private val highlightWordRegex = Regex("[A-Za-z0-9]")

    private val LAYOUT_NAMES = arrayOf(
        "Diagonal Split",
        "Mirror Glass",
        "Center Spotlight",
        "Night Broadcast",
        "Bento Stat",
        "Magazine Hero",
    )

    fun layoutName(idx: Int): String = LAYOUT_NAMES[((idx % LAYOUT_COUNT) + LAYOUT_COUNT) % LAYOUT_COUNT]

    fun isAutoCardUri(uri: String?): Boolean = uri?.contains("auto_card_") == true

    // -------------------------------------------------------------------------
    // Local Storage Management for Custom Background Image
    // -------------------------------------------------------------------------
    fun getCustomBackgroundFile(context: Context): File? {
        val f = File(File(context.filesDir, "auto_cards"), CUSTOM_BG_FILENAME)
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun isCustomBackgroundEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_USE_CUSTOM_BG, false) && getCustomBackgroundFile(context) != null
    }

    fun setCustomBackgroundEnabled(context: Context, enabled: Boolean) {
        val actual = enabled && getCustomBackgroundFile(context) != null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USE_CUSTOM_BG, actual)
            .apply()
    }

    fun saveCustomBackground(context: Context, uri: Uri): Boolean {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sample > 1920) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return false

            val dir = File(context.filesDir, "auto_cards").apply { mkdirs() }
            val target = File(dir, CUSTOM_BG_FILENAME)
            val tmp = File(dir, "$CUSTOM_BG_FILENAME.tmp")
            FileOutputStream(tmp).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bmp.recycle()
            if (target.exists()) target.delete()
            val ok = tmp.renameTo(target)
            if (ok) {
                setCustomBackgroundEnabled(context, true)
            } else {
                tmp.delete()
            }
            ok
        }.getOrDefault(false)
    }

    fun deleteCustomBackground(context: Context) {
        runCatching {
            File(File(context.filesDir, "auto_cards"), CUSTOM_BG_FILENAME).delete()
        }
        setCustomBackgroundEnabled(context, false)
    }

    fun loadCustomBackgroundThumb(context: Context): Bitmap? {
        val f = getCustomBackgroundFile(context) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 160) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private data class JerseyPalette(
        val bgDark: Int,
        val bgMid: Int,
        val accent: Int,
        val secondary: Int,
        val highlight: Int,
    )

    private val FALLBACK_PALETTES = listOf(
        JerseyPalette(0xFF041030.toInt(), 0xFF0B2B7A.toInt(), 0xFF00D4FF.toInt(), 0xFFFF7A00.toInt(), 0xFFFFE600.toInt()),
        JerseyPalette(0xFF080509.toInt(), 0xFF52081B.toInt(), 0xFFE5173F.toInt(), 0xFFFFB300.toInt(), 0xFFFFE600.toInt()),
        JerseyPalette(0xFF0D0524.toInt(), 0xFF331173.toInt(), 0xFFAA44FF.toInt(), 0xFF00E5FF.toInt(), 0xFFFFE600.toInt()),
        JerseyPalette(0xFF031A12.toInt(), 0xFF0B4F37.toInt(), 0xFF00E676.toInt(), 0xFFFFD54F.toInt(), 0xFFD4FF33.toInt()),
        JerseyPalette(0xFF081226.toInt(), 0xFF1A2E5C.toInt(), 0xFFFFC107.toInt(), 0xFFFF6D00.toInt(), 0xFFFFF176.toInt()),
        JerseyPalette(0xFF140718.toInt(), 0xFF47124A.toInt(), 0xFFFF3D7F.toInt(), 0xFFFF9100.toInt(), 0xFFFFE600.toInt()),
    )

    private val PLAYER_ALIAS_MAP = listOf(
        "ஷ்ரேயாஸ் அய்யர்" to "Shreyas Iyer",
        "ஷ்ரேயாஸ் ஐயர்" to "Shreyas Iyer",
        "ஸ்ரேயாஸ் அய்யர்" to "Shreyas Iyer",
        "ஸ்ரேயாஸ் ஐயர்" to "Shreyas Iyer",
        "ஷ்ரேயாஸ்" to "Shreyas Iyer",
        "ஸ்ரேயாஸ்" to "Shreyas Iyer",
        "இஷான் கிஷன்" to "Ishan Kishan",
        "இஷான் கிஷான்" to "Ishan Kishan",
        "இஷான்" to "Ishan Kishan",
        "விராட் கோலி" to "Virat Kohli",
        "விராட் கோஹ்லி" to "Virat Kohli",
        "விராட்" to "Virat Kohli",
        "கோலி" to "Virat Kohli",
        "கோஹ்லி" to "Virat Kohli",
        "ரோஹித் சர்மா" to "Rohit Sharma",
        "ரோகித் சர்மா" to "Rohit Sharma",
        "ரோஹித்" to "Rohit Sharma",
        "ரோகித்" to "Rohit Sharma",
        "ருதுராஜ் கெய்க்வாட்" to "Ruturaj Gaikwad",
        "ருதுராஜ்" to "Ruturaj Gaikwad",
        "கெய்க்வாட்" to "Ruturaj Gaikwad",
        "சுப்மன் கில்" to "Shubman Gill",
        "சுப்மன்" to "Shubman Gill",
        "சூர்யகுமார் யாதவ்" to "Suryakumar Yadav",
        "சூர்யகுமார்" to "Suryakumar Yadav",
        "கேஎல் ராகுல்" to "KL Rahul",
        "கே.எல். ராகுல்" to "KL Rahul",
        "ராகுல்" to "KL Rahul",
        "ஜஸ்பிரித் பும்ரா" to "Jasprit Bumrah",
        "பும்ரா" to "Jasprit Bumrah",
        "ஹர்திக் பாண்டியா" to "Hardik Pandya",
        "ஹர்திக்" to "Hardik Pandya",
        "பாண்டியா" to "Hardik Pandya",
        "ரவீந்திர ஜடேஜா" to "Ravindra Jadeja",
        "ஜடேஜா" to "Ravindra Jadeja",
        "சஞ்சு சாம்சன்" to "Sanju Samson",
        "சஞ்சு" to "Sanju Samson",
        "சாம்சன்" to "Sanju Samson",
        "முகமது சிராஜ்" to "Mohammed Siraj",
        "சிராஜ்" to "Mohammed Siraj",
        "முகமது ஷமி" to "Mohammed Shami",
        "ஷமி" to "Mohammed Shami",
        "ரிஷப் பண்ட்" to "Rishabh Pant",
        "ரிஷப்" to "Rishabh Pant",
        "பண்ட்" to "Rishabh Pant",
        "அக்சர் படேல்" to "Axar Patel",
        "அக்ஷர் படேல்" to "Axar Patel",
        "குல்தீப் யாதவ்" to "Kuldeep Yadav",
        "குல்தீப்" to "Kuldeep Yadav",
        "அர்ஷ்தீப் சிங்" to "Arshdeep Singh",
        "அர்ஷ்தீப்" to "Arshdeep Singh",
        "யஷஸ்வி ஜெய்ஸ்வால்" to "Yashasvi Jaiswal",
        "ஜெய்ஸ்வால்" to "Yashasvi Jaiswal",
        "திலக் வர்மா" to "Tilak Varma",
        "அபிஷேக் சர்மா" to "Abhishek Sharma",
        "ரிங்கு சிங்" to "Rinku Singh",
        "சிவம் துபே" to "Shivam Dube",
        "ஹர்ஷித் ராணா" to "Harshit Rana",
        "வருண் சக்கரவர்த்தி" to "Varun Chakaravarthy",
        "வாஷிங்டன் சுந்தர்" to "Washington Sundar",
        "எம்எஸ் தோனி" to "MS Dhoni",
        "தோனி" to "MS Dhoni",
        "கவுதம் கம்பீர்" to "Gautam Gambhir",
        "கம்பீர்" to "Gautam Gambhir",
        "நிதீஷ் குமார் ரெட்டி" to "Nitish Kumar Reddy",
        "நிதீஷ் ரெட்டி" to "Nitish Kumar Reddy",
    )

    private val CRICKET_ENGLISH_RULES: List<Pair<Regex, String>> = listOf(
        Regex("(?<=^|\\s)இந்திய\\s+அணி(?=$|\\s|[.,!?-])") to "Team India",
        Regex("(?<=^|\\s)இந்தியா(?=$|\\s|[.,!?-])") to "India",
        Regex("(?<=^|\\s)ஆஸ்திரேலியா(?=$|\\s|[.,!?-])") to "Australia",
        Regex("(?<=^|\\s)நியூசிலாந்து(?=$|\\s|[.,!?-])") to "New Zealand",
        Regex("(?<=^|\\s)இங்கிலாந்து(?=$|\\s|[.,!?-])") to "England",
        Regex("(?<=^|\\s)தென்\\s*ஆப்பிரிக்கா(?=$|\\s|[.,!?-])") to "South Africa",
        Regex("(?<=^|\\s)பாகிஸ்தான்(?=$|\\s|[.,!?-])") to "Pakistan",
        Regex("(?<=^|\\s)இலங்கை(?=$|\\s|[.,!?-])") to "Sri Lanka",
        Regex("(?<=^|\\s)வங்கதேசம்(?=$|\\s|[.,!?-])") to "Bangladesh",
        Regex("(?<=^|\\s)ஆப்கானிஸ்தான்(?=$|\\s|[.,!?-])") to "Afghanistan",
        Regex("(?<=^|\\s)வெஸ்ட்\\s*இண்டீஸ்(?=$|\\s|[.,!?-])") to "West Indies",
        Regex("(?<=^|\\s)சிஎஸ்கே(?=$|\\s|[.,!?-])") to "CSK",
        Regex("(?<=^|\\s)ஆர்சிபி(?=$|\\s|[.,!?-])") to "RCB",
        Regex("(?<=^|\\s)எம்ஐ(?=$|\\s|[.,!?-])") to "MI",
        Regex("(?<=^|\\s)கேகேஆர்(?=$|\\s|[.,!?-])") to "KKR",
        Regex("(?<=^|\\s)டீம்(?=$|\\s|[.,!?-])") to "Team",
        Regex("(?<=^|\\s)டக்\\s*அவுட்(?=$|\\s|[.,!?-])") to "duck out",
        Regex("(?<=^|\\s)நாட்\\s*அவுட்(?=$|\\s|[.,!?-])") to "not out",
        Regex("(?<=^|\\s)ரன்\\s*அவுட்(?=$|\\s|[.,!?-])") to "run out",
        Regex("(?<=^|\\s)அவுட்டாகி(?=$|\\s|[.,!?-])") to "out ஆகி",
        Regex("(?<=^|\\s)அவுட்(?=$|\\s|[.,!?-])") to "out",
        Regex("(?<=^|\\s)ஆல்ரவுண்டர்(?=$|\\s|[.,!?-])") to "all-rounder",
        Regex("(?<=^|\\s)ஆல்-ரவுண்டர்(?=$|\\s|[.,!?-])") to "all-rounder",
        Regex("(?<=^|\\s)பெவிலியன்(?=$|\\s|[.,!?-])") to "pavilion",
        Regex("(?<=^|\\s)பேட்ஸ்மேன்கள்(?=$|\\s|[.,!?-])") to "batsmen",
        Regex("(?<=^|\\s)பேட்ஸ்மேன்(?=$|\\s|[.,!?-])") to "batsman",
        Regex("(?<=^|\\s)பேட்டிங்கில்(?=$|\\s|[.,!?-])") to "batting-ல்",
        Regex("(?<=^|\\s)பேட்டிங்(?=$|\\s|[.,!?-])") to "batting",
        Regex("(?<=^|\\s)பேட்(?=$|\\s|[.,!?-])") to "bat",
        Regex("(?<=^|\\s)பவுலர்களை(?=$|\\s|[.,!?-])") to "bowlers-ஐ",
        Regex("(?<=^|\\s)பவுலர்கள்(?=$|\\s|[.,!?-])") to "bowlers",
        Regex("(?<=^|\\s)பவுலர்(?=$|\\s|[.,!?-])") to "bowler",
        Regex("(?<=^|\\s)பவுலிங்(?=$|\\s|[.,!?-])") to "bowling",
        Regex("(?<=^|\\s)பந்திலேயே(?=$|\\s|[.,!?-])") to "ball-லேயே",
        Regex("(?<=^|\\s)பந்துகளை(?=$|\\s|[.,!?-])") to "balls-ஐ",
        Regex("(?<=^|\\s)பந்துகளில்(?=$|\\s|[.,!?-])") to "balls-ல்",
        Regex("(?<=^|\\s)பந்துகள்(?=$|\\s|[.,!?-])") to "balls",
        Regex("(?<=^|\\s)பந்தில்(?=$|\\s|[.,!?-])") to "ball-ல்",
        Regex("(?<=^|\\s)பந்து(?=$|\\s|[.,!?-])") to "ball",
        Regex("(?<=^|\\s|\\d)பால்ல(?=$|\\s|[.,!?-])") to "balls-ல்",
        Regex("(?<=^|\\s)விக்கெட்டுகளை(?=$|\\s|[.,!?-])") to "wickets-ஐ",
        Regex("(?<=^|\\s)விக்கெட்டுகள்(?=$|\\s|[.,!?-])") to "wickets",
        Regex("(?<=^|\\s)விக்கெட்டை(?=$|\\s|[.,!?-])") to "wicket-ஐ",
        Regex("(?<=^|\\s)விக்கெட்டுக்கு(?=$|\\s|[.,!?-])") to "wicket-க்கு",
        Regex("(?<=^|\\s)விக்கெட்(?=$|\\s|[.,!?-])") to "wicket",
        Regex("(?<=^|\\s|\\d)ரன்களை(?=$|\\s|[.,!?-])") to "runs-ஐ",
        Regex("(?<=^|\\s|\\d)ரன்கள்(?=$|\\s|[.,!?-])") to "runs",
        Regex("(?<=^|\\s|\\d)ரன்(?=$|\\s|[.,!?-])") to "runs",
        Regex("(?<=^|\\s|\\d)ஓவர்கள்(?=$|\\s|[.,!?-])") to "overs",
        Regex("(?<=^|\\s|\\d)ஓவர்(?=$|\\s|[.,!?-])") to "over",
        Regex("(?<=^|\\s)ஸ்கோரை(?=$|\\s|[.,!?-])") to "score-ஐ",
        Regex("(?<=^|\\s)ஸ்கோர்(?=$|\\s|[.,!?-])") to "score",
        Regex("(?<=^|\\s)கேப்டனாக(?=$|\\s|[.,!?-])") to "captain-ஆக",
        Regex("(?<=^|\\s)கேப்டன்(?=$|\\s|[.,!?-])") to "captain",
        Regex("(?<=^|\\s)கோச்(?=$|\\s|[.,!?-])") to "coach",
        Regex("(?<=^|\\s)தொடர்களில்(?=$|\\s|[.,!?-])") to "series-ல்",
        Regex("(?<=^|\\s)கிரிக்கெட்(?=$|\\s|[.,!?-])") to "cricket",
        Regex("(?<=^|\\s)மேட்ச்(?=$|\\s|[.,!?-])") to "match",
    )

    fun parseKeyPoints(rawText: String, knownPlayers: List<String> = emptyList()): List<AutoCardEntry> {
        val cleanInput = rawText.replace("\r\n", "\n").trim()
        if (cleanInput.isEmpty()) return emptyList()

        val numberedStartRegex = Regex("(?m)^\\s*\\d{1,2}\\s*[.)\\]]\\s+")
        val blocks: List<String> = if (numberedStartRegex.containsMatchIn(cleanInput)) {
            val matches = numberedStartRegex.findAll(cleanInput).toList()
            matches.mapIndexed { idx, match ->
                val start = match.range.last + 1
                val end = if (idx + 1 < matches.size) matches[idx + 1].range.first else cleanInput.length
                cleanInput.substring(start, end).trim()
            }.filter { it.isNotEmpty() }
        } else {
            cleanInput.split(Regex("\\n+"))
                .map { it.trim().trimStart('-', '*', '•', ' ') }
                .filter { it.length >= 6 }
        }

        val entries = ArrayList<AutoCardEntry>(blocks.size)
        blocks.forEachIndexed { idx, rawBlock ->
            val block = rawBlock.replace("**", "").replace("__", "").replace(Regex("\\s+"), " ").trim()
            if (block.isEmpty()) return@forEachIndexed

            val colonIdx = block.indexOf(':')
            val dashIdx = block.indexOf(" - ")
            val splitIdx = when {
                colonIdx in 2..46 -> colonIdx
                dashIdx in 2..46 -> dashIdx
                else -> -1
            }

            var playerName: String
            var body: String
            if (splitIdx > 0) {
                val left = block.substring(0, splitIdx).trim()
                val sepLen = if (splitIdx == colonIdx) 1 else 3
                val right = block.substring(splitIdx + sepLen).trim()
                playerName = resolveDisplayPlayerName(left, knownPlayers)
                body = cleanDisplayText(right)
            } else {
                val detected = detectPlayerInText(block, knownPlayers)
                playerName = detected ?: "CRICKET FLASH"
                body = cleanDisplayText(block)
            }

            if (body.isBlank()) body = cleanDisplayText(block)
            entries.add(
                AutoCardEntry(
                    index = idx,
                    playerName = playerName,
                    text = body,
                    layoutIdx = idx % LAYOUT_COUNT,
                ),
            )
        }
        return entries
    }

    private fun resolveDisplayPlayerName(rawLeft: String, knownPlayers: List<String>): String {
        val cleaned = rawLeft.trim().trim('*', '-', ':', '•', ' ')
        val lower = cleaned.lowercase()
        for ((alias, eng) in PLAYER_ALIAS_MAP) {
            if (lower.contains(alias.lowercase())) return eng
        }
        knownPlayers.firstOrNull { it.equals(cleaned, ignoreCase = true) }?.let { return it }
        knownPlayers.firstOrNull { kp ->
            val kpl = kp.lowercase()
            kpl.length >= 4 && (lower.contains(kpl) || kpl.contains(lower))
        }?.let { return it }
        return cleaned.ifBlank { "CRICKET FLASH" }
    }

    private fun detectPlayerInText(text: String, knownPlayers: List<String>): String? {
        val lower = text.lowercase()
        knownPlayers.firstOrNull { it.length >= 4 && lower.contains(it.lowercase()) }?.let { return it }
        for ((alias, eng) in PLAYER_ALIAS_MAP) {
            if (lower.contains(alias.lowercase())) return eng
        }
        return null
    }

    private fun cleanDisplayText(input: String): String {
        var s = input.trim()
        for ((pattern, replacement) in CRICKET_ENGLISH_RULES) {
            s = s.replace(pattern, replacement)
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractHeroStat(text: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("\\b(\\d+\\*?)\\s*(runs?|run)\\b", RegexOption.IGNORE_CASE) to "RUNS",
            Regex("\\b(\\d+)\\s*(balls?|ball)\\b", RegexOption.IGNORE_CASE) to "BALLS",
            Regex("\\b(\\d+)\\s*(wickets?|wkts?)\\b", RegexOption.IGNORE_CASE) to "WICKETS",
            Regex("\\b(\\d+(?:\\.\\d)?)\\s*(overs?|ov)\\b", RegexOption.IGNORE_CASE) to "OVERS",
            Regex("\\b(\\d+)\\s*(sixes?|six)\\b", RegexOption.IGNORE_CASE) to "SIXES",
            Regex("\\b(\\d+)\\s*(fours?|four)\\b", RegexOption.IGNORE_CASE) to "FOURS",
        )
        val allMatches = ArrayList<Pair<String, String>>()
        for ((re, label) in patterns) {
            re.findAll(text).forEach { m ->
                allMatches.add(m.groupValues[1] to label)
            }
        }
        if (allMatches.isEmpty()) return null
        return allMatches.maxByOrNull { (num, _) -> num.trimEnd('*').toFloatOrNull() ?: 0f }
            ?: allMatches.first()
    }

    fun renderCardToFile(
        context: Context,
        entry: AutoCardEntry,
        resolvedCutoutUri: String?,
        layoutIdxOverride: Int? = null,
        customBgPathOverride: String? = null,
    ): String {
        val layoutIdx = ((layoutIdxOverride ?: entry.layoutIdx) % LAYOUT_COUNT + LAYOUT_COUNT) % LAYOUT_COUNT
        val cutoutBmp = decodeBitmap(resolvedCutoutUri)
        val customBgPath = customBgPathOverride
            ?: if (isCustomBackgroundEnabled(context)) getCustomBackgroundFile(context)?.absolutePath else null
        val customBgBmp = customBgPath?.let { decodeBitmap(it) }

        val palette = extractJerseyPalette(cutoutBmp, entry.index)
        val heroStat = extractHeroStat(entry.text)

        val out = Bitmap.createBitmap(CANVAS_W.toInt(), CANVAS_H.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        when (layoutIdx) {
            0 -> drawLayoutDiagonalSplit(canvas, entry, cutoutBmp, customBgBmp, palette, heroStat)
            1 -> drawLayoutMirrorGlass(canvas, entry, cutoutBmp, customBgBmp, palette, heroStat)
            2 -> drawLayoutCenterSpotlight(canvas, entry, cutoutBmp, customBgBmp, palette, heroStat)
            3 -> drawLayoutNightBroadcast(canvas, entry, cutoutBmp, customBgBmp, palette, heroStat)
            4 -> drawLayoutBentoStat(canvas, entry, cutoutBmp, customBgBmp, palette, heroStat)
            else -> drawLayoutMagazineHero(canvas, entry, cutoutBmp, customBgBmp, palette, heroStat)
        }

        cutoutBmp?.recycle()
        customBgBmp?.recycle()

        val dir = File(context.filesDir, "auto_cards").apply { mkdirs() }
        val baseName = "auto_card_${System.currentTimeMillis()}_${fileCounter.incrementAndGet()}"
        val pngFile = File(dir, "$baseName.png")
        FileOutputStream(pngFile).use { fos ->
            out.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        out.recycle()

        val metaFile = File(dir, "$baseName.json")
        runCatching {
            val obj = JSONObject()
                .put("index", entry.index)
                .put("playerName", entry.playerName)
                .put("text", entry.text)
                .put("cutoutUri", resolvedCutoutUri ?: "")
                .put("layoutIdx", layoutIdx)
                .put("customBgPath", customBgPath ?: "")
            metaFile.writeText(obj.toString())
        }

        return Uri.fromFile(pngFile).toString()
    }

    fun cycleCardDesign(context: Context, currentImageUri: String): Pair<String, Int>? {
        val path = Uri.parse(currentImageUri).path ?: return null
        if (!path.endsWith(".png", ignoreCase = true)) return null
        val jsonFile = File(path.substring(0, path.length - 4) + ".json")
        if (!jsonFile.exists()) return null

        val obj = runCatching { JSONObject(jsonFile.readText()) }.getOrNull() ?: return null
        val nextLayout = (obj.optInt("layoutIdx", 0) + 1) % LAYOUT_COUNT
        val entry = AutoCardEntry(
            index = obj.optInt("index", 0),
            playerName = obj.optString("playerName", "CRICKET FLASH"),
            text = obj.optString("text", ""),
            layoutIdx = nextLayout,
        )
        val cutoutUri = obj.optString("cutoutUri", "").takeIf { it.isNotBlank() }
        val savedBgPath = obj.optString("customBgPath", "").takeIf { it.isNotBlank() && File(it).exists() }
        val activeBgPath = savedBgPath
            ?: if (isCustomBackgroundEnabled(context)) getCustomBackgroundFile(context)?.absolutePath else null

        val newUri = renderCardToFile(
            context = context,
            entry = entry,
            resolvedCutoutUri = cutoutUri,
            layoutIdxOverride = nextLayout,
            customBgPathOverride = activeBgPath ?: "",
        )
        return newUri to nextLayout
    }

    private fun extractJerseyPalette(cutoutBmp: Bitmap?, seedIdx: Int): JerseyPalette {
        val fallback = FALLBACK_PALETTES[seedIdx % FALLBACK_PALETTES.size]
        if (cutoutBmp == null || cutoutBmp.width < 20 || cutoutBmp.height < 20) return fallback

        val w = cutoutBmp.width
        val h = cutoutBmp.height
        val yStart = (h * 0.48f).toInt()
        val yEnd = (h * 0.88f).toInt()
        val xStart = (w * 0.20f).toInt()
        val xEnd = (w * 0.80f).toInt()
        val step = max(2, min(w, h) / 60)

        val buckets = IntArray(12)
        val hueSums = FloatArray(12)
        val satSums = FloatArray(12)
        val hsv = FloatArray(3)
        var totalHits = 0

        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val px = cutoutBmp.getPixel(x, y)
                if (AndroidColor.alpha(px) > 190) {
                    AndroidColor.colorToHSV(px, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val valB = hsv[2]
                    val isSkinTone = (hue in 8f..42f && sat in 0.18f..0.68f && valB > 0.30f)
                    if (sat >= 0.26f && valB in 0.18f..0.95f && !isSkinTone) {
                        val b = ((hue / 30f).toInt()).coerceIn(0, 11)
                        buckets[b]++
                        hueSums[b] += hue
                        satSums[b] += sat
                        totalHits++
                    }
                }
                x += step
            }
            y += step
        }

        if (totalHits < 15) return fallback

        var bestBucket = 0
        for (i in 1 until 12) {
            if (buckets[i] > buckets[bestBucket]) bestBucket = i
        }
        val domHue = hueSums[bestBucket] / buckets[bestBucket]
        val domSat = (satSums[bestBucket] / buckets[bestBucket]).coerceIn(0.55f, 0.96f)

        val bgDark = AndroidColor.HSVToColor(floatArrayOf(domHue, (domSat * 0.85f).coerceIn(0.4f, 0.9f), 0.12f))
        val bgMid = AndroidColor.HSVToColor(floatArrayOf(domHue, (domSat * 0.90f).coerceIn(0.45f, 0.92f), 0.34f))
        val accent = AndroidColor.HSVToColor(floatArrayOf(domHue, domSat.coerceAtLeast(0.75f), 0.96f))
        val secHue = (domHue + 150f) % 360f
        val secondary = AndroidColor.HSVToColor(floatArrayOf(secHue, 0.85f, 0.96f))
        val highlight = 0xFFFFE600.toInt()
        return JerseyPalette(bgDark, bgMid, accent, secondary, highlight)
    }

    // -------------------------------------------------------------------------
    // LAYOUT 0: Diagonal Jersey Split + Hero Stat + Right Cutout
    // -------------------------------------------------------------------------
    private fun drawLayoutDiagonalSplit(
        c: Canvas,
        entry: AutoCardEntry,
        cutout: Bitmap?,
        customBg: Bitmap?,
        pal: JerseyPalette,
        stat: Pair<String, String>?,
    ) {
        drawStudioBackdrop(c, pal, spotlightCx = 1420f, spotlightCy = 460f, customBg = customBg)

        val panelPath = Path().apply {
            moveTo(0f, 0f)
            lineTo(1230f, 0f)
            lineTo(1010f, CANVAS_H)
            lineTo(0f, CANVAS_H)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val c0 = if (customBg != null) withAlpha(pal.bgDark, 210) else pal.bgDark
        val c1 = if (customBg != null) withAlpha(pal.bgMid, 195) else pal.bgMid
        val c2 = if (customBg != null) withAlpha(darken(pal.bgDark, 0.7f), 220) else darken(pal.bgDark, 0.7f)
        p.shader = LinearGradient(
            0f, 0f, 1100f, CANVAS_H,
            intArrayOf(c0, c1, c2),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawPath(panelPath, p)
        p.shader = null

        p.style = Paint.Style.STROKE
        p.strokeWidth = 10f
        p.color = pal.accent
        c.drawLine(1230f, 0f, 1010f, CANVAS_H, p)
        p.strokeWidth = 4f
        p.color = pal.highlight
        c.drawLine(1246f, 0f, 1026f, CANVAS_H, p)
        p.style = Paint.Style.FILL

        drawCutoutInZone(c, cutout, entry.playerName, RectF(1070f, 85f, 1860f, 1020f), pal, alignBottom = true)

        drawNumPill(c, entry.index + 1, "PLAYER KEY POINT", 110f, 115f, pal.accent, pal.highlight)
        if (stat != null) {
            drawHeroStatPill(c, stat.first, stat.second, 760f, 105f, 220f, 130f, pal)
        }
        drawPlayerTitle(c, entry.playerName, 110f, 215f, 620f, pal.highlight, 60f)

        val cardRect = RectF(110f, 325f, 995f, 945f)
        drawGlassCard(c, cardRect, 26f, pal.accent, darkFill = 0xCC060914.toInt())
        drawFittedBodyText(
            c = c,
            text = entry.text,
            rect = RectF(cardRect.left + 38f, cardRect.top + 36f, cardRect.right - 38f, cardRect.bottom - 36f),
            tamilColor = AndroidColor.WHITE,
            highlightColor = pal.highlight,
            maxSp = 44f,
        )
    }

    // -------------------------------------------------------------------------
    // LAYOUT 1: Mirror Glass (Left Big Cutout + Right Frosted Card)
    // -------------------------------------------------------------------------
    private fun drawLayoutMirrorGlass(
        c: Canvas,
        entry: AutoCardEntry,
        cutout: Bitmap?,
        customBg: Bitmap?,
        pal: JerseyPalette,
        stat: Pair<String, String>?,
    ) {
        drawStudioBackdrop(c, pal, spotlightCx = 460f, spotlightCy = 480f, customBg = customBg)

        drawOrbitalRings(c, 470f, 480f, 340f, pal)
        drawCutoutInZone(c, cutout, entry.playerName, RectF(85f, 80f, 845f, 1030f), pal, alignBottom = true)

        val cardRect = RectF(875f, 175f, 1805f, 955f)
        drawGlassCard(c, cardRect, 30f, pal.accent, darkFill = 0xD80A0D18.toInt())

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, cardRect.top + 36f, 0f, cardRect.bottom - 36f,
                intArrayOf(pal.highlight, pal.accent), null, Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(RectF(cardRect.left + 26f, cardRect.top + 36f, cardRect.left + 40f, cardRect.bottom - 36f), 7f, 7f, barPaint)

        val contentL = cardRect.left + 68f
        drawNumPill(c, entry.index + 1, "CRICKET UPDATE", contentL, cardRect.top + 40f, pal.accent, pal.highlight)
        if (stat != null) {
            drawHeroStatPill(c, stat.first, stat.second, cardRect.right - 245f, cardRect.top + 34f, 210f, 126f, pal)
        }
        drawPlayerTitle(c, entry.playerName, contentL, cardRect.top + 135f, 590f, pal.highlight, 58f)

        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(95, 255, 255, 255)
            strokeWidth = 2f
        }
        c.drawLine(contentL, cardRect.top + 225f, cardRect.right - 40f, cardRect.top + 225f, divPaint)

        drawFittedBodyText(
            c = c,
            text = entry.text,
            rect = RectF(contentL, cardRect.top + 250f, cardRect.right - 44f, cardRect.bottom - 38f),
            tamilColor = AndroidColor.WHITE,
            highlightColor = pal.highlight,
            maxSp = 43f,
        )
    }

    // -------------------------------------------------------------------------
    // LAYOUT 2: Center Spotlight Portrait + Bottom Split Stat & Quote Card
    // -------------------------------------------------------------------------
    private fun drawLayoutCenterSpotlight(
        c: Canvas,
        entry: AutoCardEntry,
        cutout: Bitmap?,
        customBg: Bitmap?,
        pal: JerseyPalette,
        stat: Pair<String, String>?,
    ) {
        drawStudioBackdrop(c, pal, spotlightCx = 960f, spotlightCy = 350f, customBg = customBg)
        drawOrbitalRings(c, 960f, 360f, 290f, pal)

        drawCutoutInZone(c, cutout, entry.playerName, RectF(610f, 60f, 1310f, 675f), pal, alignBottom = true)

        val topCard = RectF(110f, 105f, 610f, 295f)
        drawGlassCard(c, topCard, 22f, pal.accent, darkFill = 0xCC070B16.toInt())
        drawNumPill(c, entry.index + 1, "SPOTLIGHT", topCard.left + 26f, topCard.top + 24f, pal.accent, pal.highlight)
        drawPlayerTitle(c, entry.playerName, topCard.left + 26f, topCard.top + 100f, topCard.width() - 52f, AndroidColor.WHITE, 46f)

        val botCard = RectF(115f, 645f, 1805f, 970f)
        drawGlassCard(c, botCard, 28f, pal.highlight, darkFill = 0xE6080B14.toInt())

        val textLeft = if (stat != null) {
            drawHeroStatPill(
                c, stat.first, stat.second,
                botCard.left + 34f, botCard.top + 38f,
                240f, botCard.height() - 76f, pal,
            )
            botCard.left + 305f
        } else {
            botCard.left + 44f
        }

        drawFittedBodyText(
            c = c,
            text = entry.text,
            rect = RectF(textLeft, botCard.top + 34f, botCard.right - 44f, botCard.bottom - 34f),
            tamilColor = AndroidColor.WHITE,
            highlightColor = pal.highlight,
            maxSp = 40f,
        )
    }

    // -------------------------------------------------------------------------
    // LAYOUT 3: Night Broadcast Crimson/Jersey Card + Right Studio Cutout
    // -------------------------------------------------------------------------
    private fun drawLayoutNightBroadcast(
        c: Canvas,
        entry: AutoCardEntry,
        cutout: Bitmap?,
        customBg: Bitmap?,
        pal: JerseyPalette,
        stat: Pair<String, String>?,
    ) {
        drawStudioBackdrop(c, pal, spotlightCx = 1430f, spotlightCy = 440f, customBg = customBg)
        drawOrbitalRings(c, 1430f, 440f, 310f, pal)
        drawCutoutInZone(c, cutout, entry.playerName, RectF(1085f, 80f, 1845f, 990f), pal, alignBottom = true)

        val hdrRect = RectF(105f, 120f, 1055f, 290f)
        val hdrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                hdrRect.left, hdrRect.top, hdrRect.right, hdrRect.bottom,
                intArrayOf(pal.accent, darken(pal.accent, 0.55f), pal.bgDark),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRoundRect(hdrRect, 24f, 24f, hdrPaint)
        drawGlassBorder(c, hdrRect, 24f, pal.highlight, 3.2f)

        drawNumPill(c, entry.index + 1, "BREAKING KEY POINT", hdrRect.left + 32f, hdrRect.top + 22f, pal.bgDark, pal.highlight)
        drawPlayerTitle(c, entry.playerName, hdrRect.left + 32f, hdrRect.top + 92f, hdrRect.width() - 64f, AndroidColor.WHITE, 56f)

        val bodyRect = RectF(105f, 320f, 1055f, 945f)
        drawGlassCard(c, bodyRect, 26f, pal.accent, darkFill = 0xE20B0D18.toInt())

        val textTop = if (stat != null) {
            drawHeroStatBanner(c, stat.first, stat.second, bodyRect.left + 36f, bodyRect.top + 28f, bodyRect.width() - 72f, 92f, pal)
            bodyRect.top + 142f
        } else {
            bodyRect.top + 38f
        }

        drawFittedBodyText(
            c = c,
            text = entry.text,
            rect = RectF(bodyRect.left + 38f, textTop, bodyRect.right - 38f, bodyRect.bottom - 36f),
            tamilColor = AndroidColor.WHITE,
            highlightColor = pal.highlight,
            maxSp = 43f,
        )
    }

    // -------------------------------------------------------------------------
    // LAYOUT 4: Bento Stat & Story Grid + Tall Right Cutout
    // -------------------------------------------------------------------------
    private fun drawLayoutBentoStat(
        c: Canvas,
        entry: AutoCardEntry,
        cutout: Bitmap?,
        customBg: Bitmap?,
        pal: JerseyPalette,
        stat: Pair<String, String>?,
    ) {
        drawStudioBackdrop(c, pal, spotlightCx = 1460f, spotlightCy = 460f, customBg = customBg)
        drawCutoutInZone(c, cutout, entry.playerName, RectF(1120f, 80f, 1850f, 1020f), pal, alignBottom = true)

        val tileTop = RectF(110f, 110f, 1090f, 305f)
        drawGlassCard(c, tileTop, 26f, pal.accent, darkFill = 0xDD0B1020.toInt())
        drawNumPill(c, entry.index + 1, "MATCH ANALYSIS", tileTop.left + 30f, tileTop.top + 26f, pal.accent, pal.highlight)
        drawPlayerTitle(c, entry.playerName, tileTop.left + 30f, tileTop.top + 102f, tileTop.width() - 60f, pal.highlight, 58f)

        val statTile = RectF(110f, 330f, 410f, 945f)
        drawGlassCard(c, statTile, 26f, pal.highlight, darkFill = 0xE60E1528.toInt())
        val bigNum = stat?.first ?: String.format("%02d", entry.index + 1)
        val bigLbl = stat?.second ?: "POINT"
        drawCenteredStatTileContent(c, bigNum, bigLbl, statTile, pal)

        val storyTile = RectF(435f, 330f, 1090f, 945f)
        drawGlassCard(c, storyTile, 26f, pal.accent, darkFill = 0xDF070A14.toInt())
        drawFittedBodyText(
            c = c,
            text = entry.text,
            rect = RectF(storyTile.left + 34f, storyTile.top + 36f, storyTile.right - 34f, storyTile.bottom - 36f),
            tamilColor = AndroidColor.WHITE,
            highlightColor = pal.highlight,
            maxSp = 40f,
        )
    }

    // -------------------------------------------------------------------------
    // LAYOUT 5: Magazine Hero / Giant Watermark Number Poster
    // -------------------------------------------------------------------------
    private fun drawLayoutMagazineHero(
        c: Canvas,
        entry: AutoCardEntry,
        cutout: Bitmap?,
        customBg: Bitmap?,
        pal: JerseyPalette,
        stat: Pair<String, String>?,
    ) {
        drawStudioBackdrop(c, pal, spotlightCx = 1380f, spotlightCy = 460f, customBg = customBg)

        val wmNum = stat?.first ?: String.format("%02d", entry.index + 1)
        val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 460f
            color = AndroidColor.argb(26, 255, 255, 255)
        }
        c.drawText(wmNum, 80f, 470f, wmPaint)

        drawOrbitalRings(c, 1440f, 460f, 330f, pal)
        drawCutoutInZone(c, cutout, entry.playerName, RectF(1070f, 75f, 1850f, 1020f), pal, alignBottom = true)

        val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 140f, 0f, 930f, intArrayOf(pal.highlight, pal.accent), null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(110f, 140f, 126f, 930f), 8f, 8f, stripePaint)

        drawNumPill(c, entry.index + 1, "KEY HIGHLIGHT", 160f, 140f, pal.accent, pal.highlight)
        drawPlayerTitle(c, entry.playerName, 160f, 232f, 890f, AndroidColor.WHITE, 64f)

        if (stat != null) {
            drawHeroStatBanner(c, stat.first, stat.second, 160f, 335f, 440f, 86f, pal)
        }

        val textTop = if (stat != null) 455f else 360f
        drawFittedBodyText(
            c = c,
            text = entry.text,
            rect = RectF(160f, textTop, 1060f, 935f),
            tamilColor = AndroidColor.WHITE,
            highlightColor = pal.highlight,
            maxSp = 44f,
        )
    }

    // -------------------------------------------------------------------------
    // Shared Studio Drawing Helpers
    // -------------------------------------------------------------------------
    private fun drawStudioBackdrop(
        c: Canvas,
        pal: JerseyPalette,
        spotlightCx: Float,
        spotlightCy: Float,
        customBg: Bitmap?,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (customBg != null && customBg.width > 0 && customBg.height > 0) {
            val scale = max(CANVAS_W / customBg.width.toFloat(), CANVAS_H / customBg.height.toFloat())
            val dw = customBg.width * scale
            val dh = customBg.height * scale
            val dl = (CANVAS_W - dw) / 2f
            val dt = (CANVAS_H - dh) / 2f
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            c.drawBitmap(customBg, null, RectF(dl, dt, dl + dw, dt + dh), bmpPaint)

            // Dark atmospheric tint so cards & Tamil text pop clearly over any photo
            p.color = 0x7A040610.toInt()
            c.drawRect(0f, 0f, CANVAS_W, CANVAS_H, p)
        } else {
            p.shader = LinearGradient(
                0f, 0f, CANVAS_W, CANVAS_H,
                intArrayOf(darken(pal.bgDark, 0.85f), pal.bgMid, pal.bgDark),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, CANVAS_W, CANVAS_H, p)
        }

        p.shader = RadialGradient(
            spotlightCx, spotlightCy, 760f,
            intArrayOf(
                AndroidColor.argb(if (customBg != null) 95 else 140, AndroidColor.red(pal.accent), AndroidColor.green(pal.accent), AndroidColor.blue(pal.accent)),
                AndroidColor.argb(45, AndroidColor.red(pal.secondary), AndroidColor.green(pal.secondary), AndroidColor.blue(pal.secondary)),
                AndroidColor.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(spotlightCx, spotlightCy, 760f, p)
        p.shader = null

        if (customBg == null) {
            val turfPath = Path().apply {
                moveTo(0f, 965f)
                quadTo(CANVAS_W * 0.5f, 865f, CANVAS_W, 965f)
                lineTo(CANVAS_W, CANVAS_H)
                lineTo(0f, CANVAS_H)
                close()
            }
            canvasDrawTurf(c, turfPath, pal)
        } else {
            // Subtle bottom accent vignette over custom background
            p.shader = LinearGradient(0f, CANVAS_H * 0.78f, 0f, CANVAS_H, AndroidColor.TRANSPARENT, 0xCC03050C.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, CANVAS_H * 0.78f, CANVAS_W, CANVAS_H, p)
            p.shader = null
        }
    }

    private fun canvasDrawTurf(c: Canvas, turfPath: Path, pal: JerseyPalette) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.save()
        c.clipPath(turfPath)
        p.shader = LinearGradient(0f, 860f, 0f, CANVAS_H, 0xFF0E4020.toInt(), 0xFF020C06.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 860f, CANVAS_W, CANVAS_H, p)
        p.shader = null
        c.restore()

        p.style = Paint.Style.STROKE
        p.strokeWidth = 6f
        p.shader = LinearGradient(0f, 920f, CANVAS_W, 920f, intArrayOf(pal.accent, pal.highlight, pal.secondary), null, Shader.TileMode.CLAMP)
        val rim = Path().apply {
            moveTo(0f, 965f)
            quadTo(CANVAS_W * 0.5f, 865f, CANVAS_W, 965f)
        }
        c.drawPath(rim, p)
        p.shader = null
        p.style = Paint.Style.FILL
    }

    private fun drawOrbitalRings(c: Canvas, cx: Float, cy: Float, r: Float, pal: JerseyPalette) {
        val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        rp.strokeWidth = 3.5f
        rp.color = AndroidColor.argb(80, AndroidColor.red(pal.highlight), AndroidColor.green(pal.highlight), AndroidColor.blue(pal.highlight))
        c.drawCircle(cx, cy, r, rp)
        rp.strokeWidth = 2f
        rp.color = AndroidColor.argb(55, AndroidColor.red(pal.accent), AndroidColor.green(pal.accent), AndroidColor.blue(pal.accent))
        c.drawCircle(cx, cy, r * 1.22f, rp)
    }

    private fun drawCutoutInZone(
        c: Canvas,
        cutout: Bitmap?,
        playerName: String,
        zone: RectF,
        pal: JerseyPalette,
        alignBottom: Boolean,
    ) {
        if (cutout != null && cutout.width > 0 && cutout.height > 0) {
            val faded = applySoftBottomFadeCopy(cutout, 0.16f)
            try {
                val scale = min(zone.width() / faded.width.toFloat(), zone.height() / faded.height.toFloat())
                val dw = faded.width * scale
                val dh = faded.height * scale
                val left = zone.left + (zone.width() - dw) / 2f
                val top = if (alignBottom) zone.bottom - dh else zone.top + (zone.height() - dh) * 0.35f
                val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                c.drawBitmap(faded, null, RectF(left, top, left + dw, top + dh), bmpPaint)
            } finally {
                if (faded !== cutout) faded.recycle()
            }
        } else {
            val cx = zone.centerX()
            val cy = zone.centerY()
            val r = min(zone.width(), zone.height()) * 0.32f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(cx - r * 0.2f, cy - r * 0.2f, r * 1.2f, intArrayOf(pal.bgMid, pal.bgDark), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.color = pal.highlight
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL
            p.color = AndroidColor.WHITE
            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            p.textAlign = Paint.Align.CENTER
            p.textSize = r * 0.72f
            val initials = playerName.split(Regex("\\s+")).filter { it.isNotEmpty() }
                .take(2).joinToString("") { it.first().uppercaseChar().toString() }.ifBlank { "NC" }
            c.drawText(initials, cx, cy + p.textSize * 0.34f, p)
            p.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawGlassCard(c: Canvas, rect: RectF, rad: Float, borderColor: Int, darkFill: Int) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(140, 0, 0, 0)
            setShadowLayer(24f, 0f, 10f, AndroidColor.argb(175, 0, 0, 0))
        }
        c.drawRoundRect(rect, rad, rad, shadow)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darkFill }
        c.drawRoundRect(rect, rad, rad, bg)

        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, rad, rad, Path.Direction.CW) })
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.38f, 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        c.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.38f, sheen)
        c.restore()

        drawGlassBorder(c, rect, rad, borderColor, 2.5f)
    }

    private fun drawGlassBorder(c: Canvas, rect: RectF, rad: Float, color: Int, width: Float) {
        val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }
        c.drawRoundRect(rect, rad, rad, bp)
    }

    private fun drawNumPill(c: Canvas, num: Int, label: String, x: Float, y: Float, accent: Int, highlight: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 22f
            letterSpacing = 0.08f
        }
        val text = String.format("%02d  •  %s", num, label)
        val w = p.measureText(text) + 42f
        val h = 42f
        val rect = RectF(x, y, x + w, y + h)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.argb(180, 0, 0, 0) }
        c.drawRoundRect(rect, h / 2f, h / 2f, bg)
        drawGlassBorder(c, rect, h / 2f, accent, 2f)
        p.color = highlight
        c.drawText(text, x + 21f, y + 29f, p)
    }

    private fun drawPlayerTitle(c: Canvas, name: String, x: Float, y: Float, maxW: Float, color: Int, startSize: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            this.color = color
            textSize = startSize
            setShadowLayer(8f, 0f, 3f, AndroidColor.argb(180, 0, 0, 0))
        }
        val upper = name.uppercase()
        while (p.measureText(upper) > maxW && p.textSize > 26f) {
            p.textSize -= 2f
        }
        c.drawText(upper, x, y + p.textSize, p)
    }

    private fun drawHeroStatPill(
        c: Canvas,
        value: String,
        label: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        pal: JerseyPalette,
    ) {
        val rect = RectF(x, y, x + w, y + h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, intArrayOf(pal.accent, pal.bgMid), null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, 22f, 22f, p)
        p.shader = null
        drawGlassBorder(c, rect, 22f, pal.highlight, 3f)

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = AndroidColor.WHITE
            textSize = min(h * 0.48f, 68f)
        }
        while (tp.measureText(value) > w - 24f && tp.textSize > 26f) tp.textSize -= 2f
        c.drawText(value, rect.centerX(), rect.centerY() + tp.textSize * 0.16f, tp)

        tp.textSize = 20f
        tp.color = pal.highlight
        tp.letterSpacing = 0.12f
        c.drawText(label, rect.centerX(), rect.bottom - 20f, tp)
    }

    private fun drawHeroStatBanner(
        c: Canvas,
        value: String,
        label: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        pal: JerseyPalette,
    ) {
        val rect = RectF(x, y, x + w, y + h)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(rect.left, 0f, rect.right, 0f, intArrayOf(pal.accent, pal.bgDark), null, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, 18f, 18f, bg)
        drawGlassBorder(c, rect, 18f, pal.highlight, 2.5f)

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = pal.highlight
            textSize = 46f
        }
        val numW = tp.measureText(value)
        c.drawText(value, rect.left + 28f, rect.centerY() + 16f, tp)

        tp.color = AndroidColor.WHITE
        tp.textSize = 26f
        tp.letterSpacing = 0.10f
        c.drawText(label, rect.left + 44f + numW, rect.centerY() + 10f, tp)
    }

    private fun drawCenteredStatTileContent(c: Canvas, value: String, label: String, rect: RectF, pal: JerseyPalette) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = pal.highlight
            textSize = 112f
        }
        while (tp.measureText(value) > rect.width() - 36f && tp.textSize > 44f) tp.textSize -= 4f
        c.drawText(value, rect.centerX(), rect.centerY() + tp.textSize * 0.18f, tp)

        tp.textSize = 30f
        tp.color = AndroidColor.WHITE
        tp.letterSpacing = 0.14f
        c.drawText(label, rect.centerX(), rect.centerY() + 92f, tp)
    }

    private fun drawFittedBodyText(
        c: Canvas,
        text: String,
        rect: RectF,
        tamilColor: Int,
        highlightColor: Int,
        maxSp: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 0f, 2f, AndroidColor.argb(145, 0, 0, 0))
        }
        var size = maxSp
        var lines = emptyList<String>()
        while (size >= 20f) {
            paint.textSize = size
            lines = wrapLines(text, paint, rect.width())
            val totalH = lines.size * (size * 1.38f)
            if (totalH <= rect.height()) break
            size -= 1.5f
        }
        val lineH = size * 1.38f
        val totalBlockH = lines.size * lineH
        var y = rect.top + ((rect.height() - totalBlockH).coerceAtLeast(0f) * 0.45f) + size
        lines.forEach { line ->
            drawHighlightedLine(c, line, rect.left, y, paint, tamilColor, highlightColor)
            y += lineH
        }
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

    private fun applySoftBottomFadeCopy(src: Bitmap, fadeFraction: Float): Bitmap {
        val copy = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val bw = copy.width
        val bh = copy.height
        val fadeRows = (bh * fadeFraction).toInt().coerceAtLeast(1)
        val startY = bh - fadeRows
        val row = IntArray(bw)
        for (y in startY until bh) {
            val factor = (1f - (y - startY).toFloat() / fadeRows.toFloat()).coerceIn(0f, 1f)
            val smooth = factor * factor
            copy.getPixels(row, 0, bw, 0, y, bw, 1)
            for (x in 0 until bw) {
                val p = row[x]
                val a = AndroidColor.alpha(p)
                if (a > 0) {
                    val na = (a * smooth).toInt().coerceIn(0, 255)
                    row[x] = (na shl 24) or (p and 0x00FFFFFF)
                }
            }
            copy.setPixels(row, 0, bw, 0, y, bw, 1)
        }
        return copy
    }

    private fun decodeBitmap(uriStr: String?): Bitmap? {
        if (uriStr.isNullOrBlank()) return null
        val path = Uri.parse(uriStr).path ?: uriStr
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun darken(argb: Int, factor: Float): Int {
        val a = AndroidColor.alpha(argb)
        val r = (AndroidColor.red(argb) * factor).toInt().coerceIn(0, 255)
        val g = (AndroidColor.green(argb) * factor).toInt().coerceIn(0, 255)
        val b = (AndroidColor.blue(argb) * factor).toInt().coerceIn(0, 255)
        return AndroidColor.argb(a, r, g, b)
    }

    private fun withAlpha(argb: Int, alpha: Int): Int {
        return (alpha.coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)
    }
}
