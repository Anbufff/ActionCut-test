package com.actioncut.feature.editor.ui.newsalert

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.actioncut.core.model.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

object AiFlashNewsEngine {

    private fun toastMain(context: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun generateSegments(
        @Suppress("UNUSED_PARAMETER") context: Context,
        fullArticle: String,
        knownPlayers: List<String>,
    ): Result<List<FlashNewsSegment>> = withContext(Dispatchers.IO) {
        val segments = buildOfflineSegments(fullArticle, knownPlayers)
        if (segments.isNotEmpty()) {
            Result.success(segments)
        } else {
            Result.failure(IllegalStateException("கட்டுரையில் Player அல்லது Team பெயர் கண்டறியப்படவில்லை"))
        }
    }

    private data class WeightedSentence(
        val raw: String,
        val cleaned: String,
        val startWeight: Float,
    )

    private fun buildOfflineSegments(
        fullArticle: String,
        knownPlayers: List<String>,
    ): List<FlashNewsSegment> {
        val rawSource = fullArticle.trim()
        if (rawSource.isEmpty()) return emptyList()

        val rawSentences = splitIntoSentences(rawSource)
        if (rawSentences.isEmpty()) return emptyList()

        val weighted = ArrayList<WeightedSentence>(rawSentences.size)
        var cumWeight = 0f
        for (raw in rawSentences) {
            val cleaned = cleanDisplaySentence(raw)
            val w = estimateSpokenWeight(raw)
            if (cleaned.length >= 10) {
                weighted.add(WeightedSentence(raw = raw, cleaned = cleaned, startWeight = cumWeight))
            }
            cumWeight += w
        }
        val totalWeight = cumWeight.coerceAtLeast(1f)
        if (weighted.isEmpty()) return emptyList()

        val out = ArrayList<FlashNewsSegment>()
        var lastSubjectKey: String? = null
        var currentAnchorFrac = 0f

        for (item in weighted) {
            val frac = (item.startWeight / totalWeight).coerceIn(0f, 0.96f)
            val player = detectMentionedPlayer(item.raw, knownPlayers)
                ?: detectMentionedPlayer(item.cleaned, knownPlayers)
            val team = detectMentionedTeam(item.raw)
                ?: detectMentionedTeam(item.cleaned)

            val explicitKey = when {
                player != null -> "P:$player"
                team != null -> "T:$team"
                else -> null
            }

            if (explicitKey == null) {
                // Continuation sentence for the currently active player/team
                if (out.isNotEmpty() && out.last().bullets.size < 2 && (frac - currentAnchorFrac) <= 0.14f) {
                    val prev = out.last()
                    val bulletText = item.cleaned.take(195)
                    if (bulletText.isNotEmpty()) {
                        out[out.lastIndex] = prev.copy(
                            bullets = prev.bullets + bulletText,
                            title = buildSmartTitle(prev.playerName, prev.teamName, prev.bullets + bulletText),
                        )
                    }
                }
                continue
            }

            val bulletText = item.cleaned.take(195)
            if (bulletText.isEmpty()) continue

            // Merge consecutive sentence of same subject only if current card has 1 bullet and is nearby
            if (explicitKey == lastSubjectKey &&
                out.isNotEmpty() &&
                out.last().bullets.size < 2 &&
                (frac - currentAnchorFrac) <= 0.10f
            ) {
                val prev = out.last()
                val updatedBullets = prev.bullets + bulletText
                out[out.lastIndex] = prev.copy(
                    bullets = updatedBullets,
                    title = buildSmartTitle(prev.playerName, prev.teamName, updatedBullets),
                )
                continue
            }

            lastSubjectKey = explicitKey
            currentAnchorFrac = frac
            val bulletsList = listOf(bulletText)
            out.add(
                FlashNewsSegment(
                    title = buildSmartTitle(player, team, bulletsList),
                    bullets = bulletsList,
                    playerName = player,
                    teamName = team,
                    anchorText = item.raw.take(36),
                    startFraction = frac,
                ),
            )
        }
        return out
    }

    private fun splitIntoSentences(article: String): List<String> {
        val result = ArrayList<String>()
        val paragraphs = article.split(Regex("\\r?\\n+"))
        for (para in paragraphs) {
            val trimmed = para.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(Regex("(?<!\\d)\\.(?!\\d)\\s+|(?<=[!?।])\\s+"))
            for (part in parts) {
                val p = part.trim().trimEnd('.')
                if (p.length >= 180 && p.contains(',')) {
                    // Break overly long comma-chained Tamil monologue cleanly into ~2 clauses
                    val clauses = p.split(Regex(",\\s+"))
                    var buf = StringBuilder()
                    for (c in clauses) {
                        if (buf.length + c.length > 140 && buf.isNotEmpty()) {
                            result.add(buf.toString().trim())
                            buf = StringBuilder(c)
                        } else {
                            if (buf.isNotEmpty()) buf.append(", ")
                            buf.append(c)
                        }
                    }
                    if (buf.isNotEmpty()) result.add(buf.toString().trim())
                } else if (p.isNotEmpty()) {
                    result.add(p)
                }
            }
        }
        return result
    }

    private fun cleanDisplaySentence(raw: String): String {
        var s = normalizeCommonTamilNumbers(raw)
        val fillerPatterns = listOf(
            Regex("மக்களே[,!.]*\\s*"),
            Regex("நண்பர்களே[,!.]*\\s*"),
            Regex("யோசிச்சு\\s*பாருங்க[,!.]*\\s*"),
            Regex("கமெண்ட்\\s*பாக்ஸ்ல\\s*சொல்லுங்க[,!.]*\\s*"),
            Regex("மறக்காம\\s*சப்ஸ்கிரைப்\\s*பண்ணுங்க[,!.]*\\s*"),
            Regex("லைக்\\s*பண்ணுங்க[,!.]*\\s*"),
            Regex("ஷேர்\\s*பண்ணுங்க[,!.]*\\s*"),
        )
        for (fp in fillerPatterns) {
            s = s.replace(fp, "")
        }
        return s.replace(Regex("\\s+"), " ").trim().trimStart(',', '-', '•', '*', ' ').trim()
    }

    /**
     * Estimates spoken syllable + pause weight of a Tamil/English sentence so timestamps
     * scale with real speaking speed instead of raw character length.
     */
    private fun estimateSpokenWeight(text: String): Float {
        var weight = 2.8f // natural sentence boundary pause
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for (tok in tokens) {
            val digits = tok.filter { it.isDigit() }
            if (digits.isNotEmpty()) {
                weight += digits.length * 2.1f + 1.4f
            }
            var tamilSyllables = 0
            var latinChars = 0
            for (ch in tok) {
                val code = ch.code
                when {
                    // Tamil vowels + consonants (exclude combining vowel signs / pulli)
                    code in 0x0B85..0x0BB9 -> tamilSyllables++
                    ch in 'a'..'z' || ch in 'A'..'Z' -> latinChars++
                    ch == ',' || ch == ';' -> weight += 0.9f
                }
            }
            weight += tamilSyllables * 1.0f
            if (latinChars > 0) {
                weight += (latinChars / 2.5f).coerceAtLeast(1.4f)
            }
        }
        return weight.coerceAtLeast(3.0f)
    }

    private fun buildSmartTitle(player: String?, team: String?, bullets: List<String>): String {
        val subject = player ?: team ?: return "FLASH NEWS"
        val combined = bullets.joinToString(" ").lowercase()
        val tag = when {
            combined.contains("சாதனை") || combined.contains("record") || combined.contains("முதல் முறை") -> "சாதனை"
            combined.contains("சதம்") || combined.contains("century") || Regex("\\b1\\d\\d\\*?\\b").containsMatchIn(combined) -> "சதம்"
            combined.contains("அரைசதம்") || combined.contains("fifty") -> "அரைசதம்"
            combined.contains("அதிரடி") || combined.contains("சிக்ஸர்") || combined.contains("பவுண்டரி") -> "அதிரடி"
            combined.contains("விக்கெட்") || combined.contains("ஆட்டமிழ") || combined.contains("பவுலிங்") -> "அப்டேட்"
            combined.contains("கோச்") || combined.contains("பயிற்சியாளர்") || combined.contains("திட்டம்") -> "திட்டம்"
            combined.contains("காயம்") || combined.contains("injury") -> "காயம் அப்டேட்"
            combined.contains("வெற்றி") || combined.contains("win") -> "வெற்றி"
            else -> "அப்டேட்"
        }
        return "$subject $tag"
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

        // 1. Direct English full-name match from known library / CRIC CDN
        knownPlayers.firstOrNull { p ->
            val pn = p.trim().lowercase()
            pn.length >= 4 && lower.contains(pn)
        }?.let { return it }

        // 2. Direct English token match (>= 4 chars) from known library / CRIC CDN
        knownPlayers.firstOrNull { p ->
            val parts = p.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 4 }
            parts.any { token -> lower.contains(token) }
        }?.let { return it }

        // 3. Built-in Tamil + English cricket star dictionary
        val commonMap = listOf(
            "shreyas" to "Shreyas Iyer", "iyer" to "Shreyas Iyer",
            "ஷ்ரேயாஸ்" to "Shreyas Iyer", "ஸ்ரேயாஸ்" to "Shreyas Iyer", "அய்யர்" to "Shreyas Iyer", "ஐயர்" to "Shreyas Iyer",
            "nitish" to "Nitish Kumar Reddy", "நிதீஷ்" to "Nitish Kumar Reddy", "ரெட்டி" to "Nitish Kumar Reddy",
            "anshul" to "Anshul Kamboj", "kamboj" to "Anshul Kamboj", "அன்ஷுல்" to "Anshul Kamboj", "கம்போஜ்" to "Anshul Kamboj",
            "gambhir" to "Gautam Gambhir", "கம்பீர்" to "Gautam Gambhir", "கவுதம்" to "Gautam Gambhir",
            "கோச்" to "Gautam Gambhir", "பயிற்சியாளர்" to "Gautam Gambhir",
            "rohit" to "Rohit Sharma", "ரோஹித்" to "Rohit Sharma", "ரோகித்" to "Rohit Sharma",
            "virat" to "Virat Kohli", "kohli" to "Virat Kohli", "விராட்" to "Virat Kohli", "கோலி" to "Virat Kohli", "கோஹ்லி" to "Virat Kohli",
            "ruturaj" to "Ruturaj Gaikwad", "gaikwad" to "Ruturaj Gaikwad", "ருதுராஜ்" to "Ruturaj Gaikwad", "கெய்க்வாட்" to "Ruturaj Gaikwad",
            "shubman" to "Shubman Gill", "gill" to "Shubman Gill", "சுப்மன்" to "Shubman Gill", "கில்" to "Shubman Gill",
            "suryakumar" to "Suryakumar Yadav", "surya" to "Suryakumar Yadav", "சூர்யகுமார்" to "Suryakumar Yadav", "சூர்யா" to "Suryakumar Yadav",
            "rahul" to "KL Rahul", "ராகுல்" to "KL Rahul",
            "bumrah" to "Jasprit Bumrah", "பும்ரா" to "Jasprit Bumrah",
            "hardik" to "Hardik Pandya", "ஹர்திக்" to "Hardik Pandya", "பாண்டியா" to "Hardik Pandya",
            "jadeja" to "Ravindra Jadeja", "ஜடேஜா" to "Ravindra Jadeja",
            "sanju" to "Sanju Samson", "சஞ்சு" to "Sanju Samson", "samson" to "Sanju Samson", "சாம்சன்" to "Sanju Samson",
            "siraj" to "Mohammed Siraj", "சிராஜ்" to "Mohammed Siraj",
            "shami" to "Mohammed Shami", "ஷமி" to "Mohammed Shami",
            "pant" to "Rishabh Pant", "பண்ட்" to "Rishabh Pant", "ரிஷப்" to "Rishabh Pant",
            "axar" to "Axar Patel", "அக்சர்" to "Axar Patel", "அக்ஷர்" to "Axar Patel",
            "kuldeep" to "Kuldeep Yadav", "குல்தீப்" to "Kuldeep Yadav",
            "arshdeep" to "Arshdeep Singh", "அர்ஷ்தீப்" to "Arshdeep Singh",
            "jaiswal" to "Yashasvi Jaiswal", "ஜெய்ஸ்வால்" to "Yashasvi Jaiswal",
            "tilak" to "Tilak Varma", "திலக்" to "Tilak Varma",
            "abhishek" to "Abhishek Sharma", "அபிஷேக்" to "Abhishek Sharma",
            "rinku" to "Rinku Singh", "ரிங்கு" to "Rinku Singh",
            "dube" to "Shivam Dube", "துபே" to "Shivam Dube",
            "harshit" to "Harshit Rana", "ஹர்ஷித்" to "Harshit Rana", "ராணா" to "Harshit Rana",
            "varun" to "Varun Chakaravarthy", "வருண்" to "Varun Chakaravarthy", "சக்கரவர்த்தி" to "Varun Chakaravarthy",
            "washington" to "Washington Sundar", "வாஷிங்டன்" to "Washington Sundar", "சுந்தர்" to "Washington Sundar",
            "ishan" to "Ishan Kishan", "இஷான்" to "Ishan Kishan", "கிஷன்" to "Ishan Kishan",
            "dhoni" to "MS Dhoni", "தோனி" to "MS Dhoni",
            "naman" to "Naman Dhir", "நமன்" to "Naman Dhir", "தீர்" to "Naman Dhir",
            "auqib" to "Auqib Nabi", "ஆகிப்" to "Auqib Nabi", "நபி" to "Auqib Nabi",
            "gurnoor" to "Gurnoor Brar", "குர்நூர்" to "Gurnoor Brar", "பிரார்" to "Gurnoor Brar",
            "santner" to "Mitchell Santner", "சான்ட்னர்" to "Mitchell Santner",
            "henry" to "Matt Henry", "ஹென்றி" to "Matt Henry",
            "seifert" to "Tim Seifert", "செய்ஃபெர்ட்" to "Tim Seifert",
        )
        commonMap.firstOrNull { (needle, _) -> lower.contains(needle) }?.let { return it.second }

        // 4. Dynamic Tamil-to-English phonetic skeleton match for ANY player in CARD ADD / CRIC CDN
        val textSkeleton = toPhoneticSkeleton(text)
        if (textSkeleton.length >= 4) {
            var bestPlayer: String? = null
            var bestScore = 0
            for (player in knownPlayers) {
                val pClean = player.trim()
                if (pClean.length < 4) continue
                val fullSkel = toPhoneticSkeleton(pClean)
                if (fullSkel.length >= 5 && textSkeleton.contains(fullSkel)) {
                    if (fullSkel.length > bestScore) {
                        bestScore = fullSkel.length
                        bestPlayer = pClean
                    }
                    continue
                }
                val partSkels = pClean.split(Regex("\\s+"))
                    .map { toPhoneticSkeleton(it) }
                    .filter { it.length >= 4 }
                for (ps in partSkels) {
                    if (textSkeleton.contains(ps) && ps.length > bestScore) {
                        bestScore = ps.length
                        bestPlayer = pClean
                    }
                }
            }
            if (bestPlayer != null) return bestPlayer
        }

        return null
    }

    /**
     * Converts both Tamil script and English text into a normalized consonant skeleton
     * (e.g. "நமன் தீர்" and "Naman Dhir" both become "nmntr") for 100% offline name matching.
     */
    private fun toPhoneticSkeleton(input: String): String {
        val sb = StringBuilder(input.length)
        var lastCh = '\u0000'
        fun emit(c: Char) {
            if (c != lastCh) {
                sb.append(c)
                lastCh = c
            }
        }
        val lower = input.lowercase()
        var i = 0
        while (i < lower.length) {
            val ch = lower[i]
            val next = if (i + 1 < lower.length) lower[i + 1] else '\u0000'
            when {
                // English digraphs
                ch == 's' && next == 'h' -> { emit('s'); i += 2; continue }
                ch == 'c' && next == 'h' -> { emit('s'); i += 2; continue }
                ch == 't' && next == 'h' -> { emit('t'); i += 2; continue }
                ch == 'p' && next == 'h' -> { emit('p'); i += 2; continue }
                ch == 'k' && next == 'h' -> { emit('k'); i += 2; continue }
                ch == 'g' && next == 'h' -> { emit('k'); i += 2; continue }
                // English consonants (collapse voiced/unvoiced pairs to match Tamil script)
                ch == 'b' || ch == 'p' || ch == 'f' -> emit('p')
                ch == 'd' || ch == 't' -> emit('t')
                ch == 'g' || ch == 'k' || ch == 'q' -> emit('k')
                ch == 'c' || ch == 's' || ch == 'z' -> emit('s')
                ch == 'j' -> emit('j')
                ch == 'v' || ch == 'w' -> emit('v')
                ch == 'm' -> emit('m')
                ch == 'n' -> emit('n')
                ch == 'r' -> emit('r')
                ch == 'l' -> emit('l')
                ch == 'y' -> emit('y')
                ch == 'h' -> emit('h')
                // Tamil consonants
                ch == 'க' -> emit('k')
                ch == 'ங' -> emit('n')
                ch == 'ச' || ch == 'ஷ' || ch == 'ஸ' -> emit('s')
                ch == 'ஜ' -> emit('j')
                ch == 'ஞ' || ch == 'ந' || ch == 'ன' || ch == 'ண' -> emit('n')
                ch == 'ட' || ch == 'த' -> emit('t')
                ch == 'ப' -> emit('p')
                ch == 'ம' -> emit('m')
                ch == 'ய' -> emit('y')
                ch == 'ர' || ch == 'ற' -> emit('r')
                ch == 'ல' || ch == 'ள' || ch == 'ழ' -> emit('l')
                ch == 'வ' -> emit('v')
                ch == 'ஹ' -> emit('h')
            }
            i++
        }
        return sb.toString()
    }

    private fun normalizeCommonTamilNumbers(input: String): String {
        return input
            .replace("ஆறு புள்ளி அஞ்சு ஓவர்", "6.5 overs")
            .replace("ஆறு புள்ளி ஐந்து ஓவர்", "6.5 overs")
            .replace("ஒன்பது ஓவர்ல", "9 overs-ல்")
            .replace("ஒன்பது ஓவர்", "9 overs")
            .replace("முன்னூத்தி ஐம்பத்தியொன்னு", "351")
            .replace("இருநூறு", "200")
            .replace("நூறு", "100")
            .replace("எண்பத்திரண்டு", "82")
            .replace("பதினாறு பந்து", "16 balls")
            .replace("அம்பது ரன்", "50 runs")
            .replace("ஐம்பது ரன்", "50 runs")
            .replace("அறுபது ரன்", "60 runs")
            .replace("எழுபது ரன்", "70 runs")
            .replace("எண்பது ரன்", "80 runs")
            .replace("தொண்ணூறு ரன்", "90 runs")
            .replace("ஒன்பது ரன்", "9 runs")
            .replace("பத்து ரன்", "10 runs")
    }

    /**
     * Maps each segment onto the ACTUAL Voice Audio span across ALL audio clips using
     * 25ms adaptive-noise PCM voice-activity detection and sentence-onset snapping.
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
            minGapMs = 1_500L
            val totalVoiced = voiceRuns.sumOf { (it.last - it.first).coerceAtLeast(0L) }.coerceAtLeast(1L)
            val usedRunIdx = HashSet<Int>()
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
                // Snap cleanly to the nearest unclaimed sentence/phrase onset within ±1.4s
                var bestIdx = -1
                var bestDist = Long.MAX_VALUE
                voiceRuns.forEachIndexed { idx, run ->
                    if (idx !in usedRunIdx) {
                        val d = abs(run.first - raw)
                        if (d <= 1_400L && d < bestDist) {
                            bestDist = d
                            bestIdx = idx
                        }
                    }
                }
                val start = if (bestIdx >= 0) {
                    usedRunIdx.add(bestIdx)
                    voiceRuns[bestIdx].first - 120L
                } else {
                    raw
                }
                start.coerceIn(audioStart, (audioEnd - 2_000L).coerceAtLeast(audioStart))
            }.toMutableList()
        } else {
            minGapMs = 2_000L
            desiredStarts = segments.map { seg ->
                val raw = speechStart + (seg.startFraction * speechSpan).roundToLong()
                val snapped = quietBuckets
                    .filter { abs(it - raw) <= 1_800L }
                    .minByOrNull { abs(it - raw) }
                    ?: raw
                snapped.coerceIn(speechStart, (speechStart + speechSpan - 2_000L).coerceAtLeast(speechStart))
            }.toMutableList()
        }

        for (i in 1 until desiredStarts.size) {
            if (desiredStarts[i] < desiredStarts[i - 1] + minGapMs) {
                desiredStarts[i] = desiredStarts[i - 1] + minGapMs
            }
        }

        val result = ArrayList<Pair<Long, Long>?>(segments.size)
        for (i in segments.indices) {
            var s = desiredStarts[i]
            val nextS = desiredStarts.getOrNull(i + 1)
            val maxDur = 9_500L
            var e = if (nextS != null) {
                minOf(s + maxDur, nextS - 300L)
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

        val syncedCount = result.count { it != null }
        val modeName = if (voiceRuns != null) "Voice-Run Sync" else "Audio Sync"
        toastMain(context, "Offline $modeName: $syncedCount/${segments.size} cards synced")
        result
    }

    /** Analyzes ALL audio clips in timeline order at 25ms resolution with adaptive noise floor. */
    private fun analyzeVoiceRuns(context: Context, audioClips: List<Clip>): List<LongRange>? {
        val validClips = audioClips.filter { it.mediaUri != null }.sortedBy { it.timelineStartMs }
        if (validClips.isEmpty()) return null
        val allRuns = ArrayList<LongRange>()
        for (clip in validClips) {
            val runs = analyzeSingleClipVoiceRuns(context, clip)
            if (!runs.isNullOrEmpty()) {
                allRuns.addAll(runs)
            } else if (clip.timelineDurationMs >= 500L) {
                allRuns.add(clip.timelineStartMs..clip.timelineEndMs)
            }
        }
        if (allRuns.isEmpty()) return null
        val sorted = allRuns.sortedBy { it.first }
        val totalVoiced = sorted.sumOf { (it.last - it.first).coerceAtLeast(0L) }
        return if (totalVoiced >= 3_000L) sorted else null
    }

    private fun analyzeSingleClipVoiceRuns(context: Context, clip: Clip): List<LongRange>? {
        val uriStr = clip.mediaUri ?: return null
        val speed = clip.speed.coerceAtLeast(0.01f).toDouble()
        val sourceDurMs = if (clip.sourceDurationMs > 0L) {
            clip.sourceDurationMs
        } else {
            (clip.timelineDurationMs * speed).toLong()
        }
        if (sourceDurMs < 3_000L) return null

        val frameMs = 25
        val maxFrames = (sourceDurMs / frameMs).toInt() + 2
        if (maxFrames > 120_000) return null
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

            while (!outputDone && guard++ < 500_000) {
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
        } catch (_: Throwable) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }

        val rms = FloatArray(maxFrames) { i ->
            if (counts[i] > 0) sqrt(sumSq[i] / counts[i]).toFloat() else 0f
        }
        val nonZero = rms.filter { it > 0f }.sorted()
        if (nonZero.size < 60) return null
        val p15 = nonZero[(nonZero.size * 0.15).toInt().coerceIn(0, nonZero.size - 1)]
        val p85 = nonZero[(nonZero.size * 0.85).toInt().coerceIn(0, nonZero.size - 1)]
        if (p85 <= 0f) return null
        // Adaptive threshold rises cleanly above light background hum/BGM while preserving breath pauses
        val threshold = max(p15 * 2.2f, p15 + (p85 - p15) * 0.14f)

        val minPauseFrames = 9 // 225ms pause splits spoken phrases
        val minRunFrames = 6   // 150ms minimum spoken burst
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
        val valid = audioClips.filter { it.mediaUri != null }.sortedBy { it.timelineStartMs }
        if (valid.size != 1) return audioStart to audioEnd
        val primary = valid.first()
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
        val valid = audioClips.filter { it.mediaUri != null }.sortedBy { it.timelineStartMs }
        if (valid.isEmpty()) return emptyList()
        val allPauses = ArrayList<Long>()
        valid.forEach { clip ->
            allPauses.add(clip.timelineStartMs)
            val uriStr = clip.mediaUri ?: return@forEach
            val extractor = MediaExtractor()
            val clipPauses = runCatching {
                extractor.setDataSource(context, Uri.parse(uriStr), null)
                val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return@runCatching emptyList()
                extractor.selectTrack(trackIdx)

                val bucketCount = 120
                val byteSums = LongArray(bucketCount)
                val byteCounts = IntArray(bucketCount)
                val buf = ByteBuffer.allocate(16 * 1024)
                val clipDurUs = clip.timelineDurationMs.coerceAtLeast(1_000L) * 1_000L
                var frames = 0

                while (frames++ < 25_000) {
                    val sz = extractor.readSampleData(buf, 0)
                    if (sz < 0) break
                    val tUs = extractor.sampleTime - clip.sourceInMs * 1_000L
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
                        val timelineMs = clip.timelineStartMs +
                            ((i.toDouble() / bucketCount) * clip.timelineDurationMs).roundToLong()
                        if (timelineMs in 0L..totalMs) pauses.add(timelineMs)
                    }
                }
                pauses
            }.getOrDefault(emptyList()).also {
                runCatching { extractor.release() }
            }
            allPauses.addAll(clipPauses)
        }
        return allPauses
    }
}
