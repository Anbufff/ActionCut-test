package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Port of the web editor's row-to-player matching (index.html: ocrMatchAllRowPlayers,
 * ocrMatchRowPlayer, fkbExtractBatsmanNameLine, fkbNormName, fkbFragmentOfName, fkbCleanRawOcrName).
 */
object ScoreNameOcr {

    /** [player] = matched registered player (or null); [rawName] = cleaned OCR guess shown when unmatched. */
    data class RowResult(val player: Player?, val rawName: String)

    // fkb: FKB_DISMISSAL_LINE_RE
    private val dismissalRe = Regex(
        "^(not\\s*out\\.?$|batting\\.?$|did\\s*not\\s*bat|absent\\s*hurt|retired\\s*(hurt|out)?$|hit\\s*wicket|" +
            "c\\s*&?\\s*b\\s+|c\\s+.+?\\s+b\\s+|st\\s+.+?\\s+b\\s+|lbw\\s+b\\s+|b\\s+[a-z]|run\\s*out)",
        RegexOption.IGNORE_CASE,
    )

    // fkb: FKB_HEADER_WORD_RE
    private val headerWordRe = Regex(
        "^(batter|batsman|bowler|extras|total|fow|fall|of|wickets|r|b|m|o|w|sr|econ|nb|wd|4s|6s)$",
        RegexOption.IGNORE_CASE,
    )
    private val teamScoreA = Regex("\\d{1,3}\\s*[-/]\\s*\\d{1,2}\\b")
    private val teamScoreB = Regex("\\(?\\s*\\d+(\\.\\d)?\\s*ov(ers?)?\\b", RegexOption.IGNORE_CASE)
    private val teamCode = Regex("^[A-Z]{2,3}$")

    private fun isHeaderLine(line: String): Boolean {
        val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.all { headerWordRe.matches(it.replace(Regex("[^A-Za-z0-9]"), "")) }
    }

    private fun isTeamScoreLine(line: String): Boolean {
        val l = line.trim()
        if (l.isEmpty()) return false
        return teamScoreA.containsMatchIn(l) || teamScoreB.containsMatchIn(l) || teamCode.matches(l)
    }

    /** fkbExtractBatsmanNameLine: the first line that is not dismissal / header / team score. */
    fun extractNameLine(rawText: String): String {
        val lines = rawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (dismissalRe.containsMatchIn(line)) continue
            if (isHeaderLine(line)) continue
            if (isTeamScoreLine(line)) continue
            if (line.replace(Regex("[^A-Za-z]"), "").length < 3) continue
            return line
        }
        return ""
    }

    /** fkbCleanRawOcrName: presentable best-guess name for unregistered rows. */
    fun cleanRawName(s: String): String {
        if (s.isBlank()) return ""
        var line = s.split("\n").map { it.trim() }.firstOrNull { it.length >= 3 } ?: ""
        line = line.replace(Regex("[^A-Za-z .'-]"), " ").replace(Regex("\\s+"), " ").trim()
        if (line.length < 3) return ""
        if (line.length > 28) line = line.take(28).trim()
        return line.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w.substring(0, 1).uppercase() + w.substring(1).lowercase()
        }
    }

    private fun norm(s: String): String = s.lowercase().replace(Regex("[^a-z]"), "")

    // fkbFragmentOfName: an OCR fragment may only match the START of one of the name's words.
    private fun fragmentOfName(fragNorm: String, regName: String): Boolean {
        if (fragNorm.length < 3) return false
        val tokens = regName.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return tokens.any { it.startsWith(fragNorm) }
    }

    /** Bidirectional substring match; the LONGEST registered name wins (same as the web editor). */
    fun matchPlayer(nameLine: String, players: List<Player>): Player? {
        val text = norm(nameLine)
        if (text.length < 3) return null
        var best: Player? = null
        var bestLen = 0
        for (pl in players) {
            val pn = norm(pl.name)
            if (pn.length < 3) continue
            if ((text.contains(pn) || fragmentOfName(text, pl.name)) && pn.length > bestLen) {
                best = pl
                bestLen = pn.length
            }
        }
        return best
    }

    /** OCR of the left 58% of one row (name area), full row height - same crop as the web editor. */
    private suspend fun readRowText(source: Bitmap, row: RowInfo): String {
        val y0 = row.srcTop.coerceIn(0, source.height - 1)
        val h = (row.srcBottom - row.srcTop).coerceIn(1, source.height - y0)
        val w = (source.width * 0.58f).toInt().coerceIn(1, source.width)
        val crop = runCatching { Bitmap.createBitmap(source, 0, y0, w, h) }.getOrNull() ?: return ""
        return recognize(crop)
    }

    suspend fun matchRow(source: Bitmap, row: RowInfo, players: List<Player>): RowResult {
        val line = extractNameLine(readRowText(source, row))
        return RowResult(matchPlayer(line, players), cleanRawName(line))
    }

    /** ocrMatchAllRowPlayers: runs OCR across every row once; [onProgress] is called after each row. */
    suspend fun matchAllRows(
        source: Bitmap,
        rows: List<RowInfo>,
        players: List<Player>,
        onProgress: (List<RowResult?>) -> Unit = {},
    ): List<RowResult?> {
        val results = MutableList<RowResult?>(rows.size) { null }
        for (i in rows.indices) {
            results[i] = matchRow(source, rows[i], players)
            onProgress(results.toList())
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
