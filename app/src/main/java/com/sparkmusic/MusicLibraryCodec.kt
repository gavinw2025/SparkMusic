package com.sparkmusic

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON codec for the replaceable library cache. The root stays a JSON array so older builds can
 * still read the file; `genres` is an optional per-entry field that legacy caches simply lack.
 */
internal object MusicLibraryCodec {

    fun encode(items: List<MusicItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            val genres = JSONArray()
            item.genres.forEach { genres.put(it) }

            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("artist", item.artist)
                    .put("album", item.album)
                    .put("duration", item.duration)
                    .put("path", item.path)
                    .put("genres", genres)
                    .put("folder", item.folder)
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<MusicItem> {
        val array = JSONArray(json)
        val items = ArrayList<MusicItem>(array.length())

        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optString("id")
            val path = entry.optString("path")
            if (id.isEmpty() || path.isEmpty()) continue

            items.add(
                MusicItem(
                    id = id,
                    title = entry.optString("title"),
                    artist = entry.optString("artist"),
                    album = entry.optString("album"),
                    duration = entry.optLong("duration"),
                    path = path,
                    genres = readGenres(entry),
                    folder = entry.optString("folder")
                )
            )
        }
        // An index that has entries but no valid ones is corrupt, not an empty library.
        if (array.length() > 0 && items.isEmpty()) {
            throw IllegalArgumentException("Library cache contains no valid entries")
        }
        return items
    }

    private fun readGenres(entry: JSONObject): List<String> {
        val array = entry.optJSONArray("genres")
        if (array != null) {
            return (0 until array.length())
                .map { array.optString(it) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
        // Tolerates an early single-genre field if one is ever written.
        return entry.optString("genre")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(it) }
            .orEmpty()
    }
}
