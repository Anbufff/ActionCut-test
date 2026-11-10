package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/** A registered player: name, BAT/BOWL role ("bat" or "bowl"), country and a saved photo. */
data class Player(
    val id: String,
    val name: String,
    val role: String,
    val country: String,
    val photoFile: String,
)

/** Player register stored in app storage (players.json + one JPEG per player). */
class PlayerLibrary(private val context: Context) {
    private val dir = File(context.filesDir, "players").apply { mkdirs() }
    private val index = File(dir, "players.json")

    fun all(): List<Player> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Player(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    role = o.optString("role", "bat"),
                    country = o.optString("country", ""),
                    photoFile = o.getString("photo"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<Player>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("role", it.role)
                    .put("country", it.country)
                    .put("photo", it.photoFile),
            )
        }
        index.writeText(arr.toString())
    }

    /** Copies (and down-sizes to max 1280px) the picked photo into app storage. */
    fun add(name: String, role: String, country: String, photoUri: Uri): Player? {
        val bmp = decode(photoUri, 1280) ?: return null
        val id = UUID.randomUUID().toString()
        val fileName = "$id.jpg"
        FileOutputStream(File(dir, fileName)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val player = Player(id, name.trim(), role, country.trim(), fileName)
        save(all() + player)
        return player
    }

    fun delete(id: String) {
        val list = all()
        list.firstOrNull { it.id == id }?.let { File(dir, it.photoFile).delete() }
        save(list.filterNot { it.id == id })
    }

    fun photo(player: Player, sample: Int = 1): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(File(dir, player.photoFile).path, opts)
    }.getOrNull()

    private fun decode(uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    /**
     * Matches OCR text (the name line of a scoreboard row) against registered players.
     * A player matches when most of their name words (3+ letters) appear in the text, allowing
     * one wrong letter in long words. Returns null when nothing is close enough.
     */
    fun match(ocrText: String, role: String): Player? {
        val firstLine = ocrText.lines().firstOrNull { it.isNotBlank() } ?: return null
        val tokens = firstLine.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return null
        var best: Player? = null
        var bestScore = 0f
        for (p in all()) {
            val words = p.name.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 3 }
            if (words.isEmpty()) continue
            val hits = words.count { w ->
                tokens.any { t -> t == w || (w.length >= 5 && t.length >= 5 && editDistance(t, w) <= 1) }
            }
            if (hits == 0) continue
            var score = hits.toFloat() / words.size
            if (p.role == role) score += 0.05f
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return if (bestScore >= 0.5f) best else null
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
