package com.actioncut.feature.editor.ui.scorecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
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
        save(listOf(player) + all()) // newest player first, like the web editor
        return player
    }


    /**
     * Imports a "Players Backup" .json exported by the web video editor
     * ({"type":"fkb-players-backup","players":[{name, role, photoBase64}, ...]}).
     * Players already registered (same name and role) are skipped. Returns how many were added.
     */
    fun importBackup(uri: Uri): Int {
        var added = 0
        val existing = all().map { it.name.trim().lowercase() + "|" + it.role }.toMutableSet()
        val list = all().toMutableList()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                fun readPlayers() {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var name = ""
                        var role = "bat"
                        var country = ""
                        var photo = ""
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> name = reader.nextString()
                                "role" -> role = reader.nextString()
                                "country" -> country = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                "photoBase64" -> photo = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        val key = name.trim().lowercase() + "|" + role
                        if (name.isBlank() || photo.isBlank() || key in existing) continue
                        val saved = savePhotoFromDataUrl(photo) ?: continue
                        existing.add(key)
                        list.add(Player(saved.first, name.trim(), if (role == "bowl") "bowl" else "bat", country.trim(), saved.second))
                        added++
                    }
                    reader.endArray()
                }
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    readPlayers()
                } else {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "players") readPlayers() else reader.skipValue()
                    }
                    reader.endObject()
                }
            }
        }
        if (added > 0) save(list)
        return added
    }

    /** Decodes a data-URL / base64 photo, down-sizes it and stores it. Returns (id, fileName). */
    private fun savePhotoFromDataUrl(dataUrl: String): Pair<String, String>? = runCatching {
        val b64 = dataUrl.substringAfter(",", dataUrl)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 1280) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching null
        val id = UUID.randomUUID().toString()
        val fileName = "$id.jpg"
        FileOutputStream(File(dir, fileName)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        Pair(id, fileName)
    }.getOrNull()

    fun delete(id: String) {
        val list = all()
        list.firstOrNull { it.id == id }?.let { File(dir, it.photoFile).delete() }
        save(list.filterNot { it.id == id })
    }


    fun deleteAll() {
        all().forEach { File(dir, it.photoFile).delete() }
        save(emptyList())
    }

    /** Writes a backup compatible with the web editor's "Players Backup" import. Returns count. */
    fun exportBackup(uri: Uri): Int {
        val list = all()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            android.util.JsonWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
                w.beginObject()
                w.name("type").value("fkb-players-backup")
                w.name("version").value(1)
                w.name("exportedAt").value(System.currentTimeMillis())
                w.name("players").beginArray()
                list.forEach { p ->
                    val bytes = runCatching { File(dir, p.photoFile).readBytes() }.getOrNull()
                    val photo = if (bytes != null) "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) else ""
                    w.beginObject()
                    w.name("id").value(p.id)
                    w.name("name").value(p.name)
                    w.name("role").value(p.role)
                    w.name("country").value(p.country)
                    w.name("createdAt").value(System.currentTimeMillis())
                    w.name("photoBase64").value(photo)
                    w.endObject()
                }
                w.endArray()
                w.endObject()
            }
        }
        return list.size
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

}
