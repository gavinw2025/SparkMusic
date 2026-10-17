package com.sparkmusic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

object OnlineMetadataFetcher {

    private const val TAG = "MetadataFetcher"
    private val client = OkHttpClient()

    data class OnlineTrackInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: Bitmap?
    )

    suspend fun fetch(title: String, artist: String?): OnlineTrackInfo? =
        withContext(Dispatchers.IO) {
            try {
                val safeTitle = URLEncoder.encode(title, "UTF-8")
                val safeArtist = artist?.let { URLEncoder.encode(it, "UTF-8") }

                val query = if (!safeArtist.isNullOrBlank()) {
                    "recording:\"$safeTitle\" AND artist:\"$safeArtist\""
                } else {
                    "recording:\"$safeTitle\""
                }

                val url =
                    "https://musicbrainz.org/ws/2/recording/?query=$query&fmt=json&limit=1"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "SparkMusicApp/1.0 ( your@email.com )")
                    .build()

                val body = client.newCall(request).execute().use { response ->
                    response.body?.string()
                } ?: return@withContext null

                val json = JSONObject(body)
                val recordings = json.getJSONArray("recordings")
                if (recordings.length() == 0) return@withContext null

                val rec = recordings.getJSONObject(0)
                val recTitle = rec.optString("title")
                val artistCredit = rec.optJSONArray("artist-credit")
                val recArtist = artistCredit
                    ?.optJSONObject(0)
                    ?.optJSONObject("artist")
                    ?.optString("name")

                val releases = rec.optJSONArray("releases")
                val release = releases?.optJSONObject(0)
                val album = release?.optString("title")
                val releaseId = release?.optString("id")

                val artwork = releaseId?.let { fetchArtwork(it) }

                Log.d(TAG, "Metadata found: $recTitle - $recArtist")
                OnlineTrackInfo(recTitle, recArtist, album, artwork)

            } catch (e: Exception) {
                Log.e(TAG, "Metadata fetch failed", e)
                null
            }
        }

    /**
     * Downloads the front cover of a MusicBrainz release id (see [fetch], which resolves the id).
     */
    suspend fun fetchArtwork(
        releaseId: String,
        title: String? = null,
        artist: String? = null
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val url = "https://coverartarchive.org/release/$releaseId/front-250"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Artwork request for release $releaseId failed: ${response.code}")
                    return@withContext null
                }
                response.body?.byteStream()?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Artwork download failed for release $releaseId", e)
            null
        }
    }
}
