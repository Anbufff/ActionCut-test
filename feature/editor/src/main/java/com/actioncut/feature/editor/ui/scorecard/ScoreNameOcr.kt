package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object ScoreNameOcr {

    data class RowResult(val player: Player?, val rawName: String)

    private val dismissalRe = Regex(
        "^(not\\s*out\\.?$|batting\\.?$|did\\s*not\\s*bat|absent\\s*hurt|retired|" +
            "c\\s*&?\\s*b|c\\s+.+?\\s+b|st\\s+.+?\\s+b|lbw\\s+b|b\\s+[a-z]|run\\s*out)",
        RegexOption.IGNORE_CASE
    )

    private val headerWordRe = Regex(
        "^(batter|batsman|bowler|extras|total|fow|fall|of|wickets|r|b|m|o|w|sr|econ|nb|wd|4s|6s)$",
        RegexOption.IGNORE_CASE
    )

    private val teamScorePattern = Regex("\\d{1,3}\\s*[-/]\\s*\\d{1,2}|\\(?\\s*\\d+(\\.\\d)?\\s*ov", RegexOption.IGNORE_CASE)

    fun isHeaderOrTeamRow(rawText: String): Boolean {
        val clean = rawText.trim()
        if (clean.isEmpty()) return true
        if (teamScorePattern.containsMatchIn(clean)) return true
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.all { headerWordRe.matches(it.replace(Regex("[^A-Za-z0-9]"), "")) }) return true
        return false
    }

    fun extractNameLine(rawText: String): String {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (dismissalRe.containsMatchIn(line)) continue
            if (isHeaderOrTeamRow(line)) continue
            if (line.replace(Regex("[^A-Za-z]"), "").length < 3) continue
            return line
        }
        return ""
    }

    fun cleanRawName(s: String): String {
        if (s.isBlank()) return ""
        var line = s.split("\n").map { it.trim() }.firstOrNull { it.length >= 3 } ?: ""
        // அடைப்புக்குறி நீக்குதல் எ.கா: (c), (wk)
        line = line.replace(Regex("\\([a-zA-Z]+\\)"), "").trim()
        line = line.replace(Regex("[^A-Za-z .'-]"), " ").replace(Regex("\\s+"), " ").trim()
        if (line.length < 3) return ""
        if (line.length > 26) line = line.take(26).trim()
        return line.split(" ").filter { it.isNotBlank() }.joinToString(" ") { w ->
            w.substring(0, 1).uppercase() + w.substring(1).lowercase()
        }
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z]"), "")

    fun matchPlayer(nameLine: String, players: List<Player>): Player? {
        val text = norm(nameLine)
        if (text.length < 3) return null
        var best: Player? = null
        var bestLen = 0
        for (pl in players) {
            val pn = norm(pl.name)
            if (pn.length < 3) continue
            if ((text.contains(pn) || pn.contains(text)) && pn.length > bestLen) {
                best = pl
                bestLen = pn.length
            }
        }
        return best
    }

    private suspend fun readRowText(source: Bitmap, row: RowInfo): String {
        val y0 = row.srcTop.coerceIn(0, source.height - 1)
        val h = (row.srcBottom - row.srcTop).coerceIn(1, source.height - y0)
        val w = (source.width * 0.65f).toInt().coerceIn(1, source.width)
        val crop = runCatching { Bitmap.createBitmap(source, 0, y0, w, h) }.getOrNull() ?: return ""
        return recognize(crop)
    }

    suspend fun matchRow(source: Bitmap, row: RowInfo, players: List<Player>): RowResult {
        val raw = readRowText(source, row)
        val line = extractNameLine(raw)
        val clean = cleanRawName(line)
        return RowResult(matchPlayer(line, players), clean)
    }

    suspend fun matchAllRows(
        source: Bitmap,
        rows: List<RowInfo>,
        players: List<Player>,
    ): List<RowResult?> {
        val results = MutableList<RowResult?>(rows.size) { null }
        for (i in rows.indices) {
            results[i] = matchRow(source, rows[i], players)
        }
        return results
    }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                recognizer.close()
                if (cont.isActive) cont.resume(result.text)
            }
            .addOnFailureListener {
                recognizer.close()
                if (cont.isActive) cont.resume("")
            }
    }
}
