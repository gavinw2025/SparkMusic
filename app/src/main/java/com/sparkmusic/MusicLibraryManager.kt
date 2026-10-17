package com.sparkmusic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single source of truth for the device music library.
 *
 * The scanned library is cached in [Context.getFilesDir], so the media browser service can serve
 * Android Auto on a cold start without the phone UI having been opened first.
 */
object MusicLibraryManager {

    private const val TAG = "MusicLibraryManager"
    private const val CACHE_FILE_NAME = "music_library.json"

    @Volatile
    private var musicFiles: List<MusicItem> = emptyList()

    /**
     * True once the library is known: restored from a readable cache (possibly empty) or rebuilt
     * by a scan. A missing or corrupt cache leaves it false so the next [load] can retry.
     */
    @Volatile
    var isLoaded: Boolean = false
        private set

    private val scanLock = Mutex()
    private val stateLock = Any()
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Incremented after every completed scan, so a waiting caller can reuse its result. */
    private val scanGeneration = AtomicInteger(0)

    // Get all music tracks
    fun getMusicFiles(): List<MusicItem> = musicFiles

    // Get a single music track by its ID
    fun getMusicById(id: String): MusicItem? = musicFiles.firstOrNull { it.id == id }

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /** The runtime permission needed to query the audio MediaStore. */
    fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission()) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * Restores the cached library into memory.
     *
     * A missing, unreadable or corrupt cache is not latched, so a later call can retry instead of
     * leaving the library permanently empty. A valid cache, including a valid empty one, marks
     * the library as loaded. Safe to call from [android.app.Service.onCreate] and activity startup.
     */
    fun load(context: Context) {
        if (isLoaded) return

        val cached = readCache(context.applicationContext)
        synchronized(stateLock) {
            // A scan may have populated the library while the cache was being read.
            if (isLoaded) return
            if (cached == null) {
                Log.w(TAG, "No usable library cache, a scan is required")
                return
            }
            musicFiles = cached
            isLoaded = true
        }
    }

    /** Scans the MediaStore off the main thread and updates the library plus its cache. */
    suspend fun scan(context: Context): List<MusicItem> {
        val requestedGeneration = scanGeneration.get()

        return scanLock.withLock {
            if (scanGeneration.get() != requestedGeneration) {
                // Another scan finished while this caller waited, so its result is fresh enough.
                Log.d(TAG, "Reusing the library scan that just completed")
                return@withLock musicFiles
            }

            val appContext = context.applicationContext
            val scanned = withContext(Dispatchers.IO) {
                val scope = ScanScopeStore.current(appContext)
                MusicScanner(appContext).scanMusicFiles(scope)
            }

            // Keep memory and cache consistent: an empty result must not replace a known
            // non-empty library in only one of the two places.
            val previousCount = synchronized(stateLock) { musicFiles.size }
            val replaceLibrary = LibraryCachePolicy.shouldWriteCache(
                existingCount = previousCount.takeIf { it > 0 },
                newCount = scanned.size
            )

            if (replaceLibrary) {
                synchronized(stateLock) {
                    musicFiles = scanned
                    isLoaded = true
                }
                withContext(Dispatchers.IO) { writeCache(appContext, scanned) }
            } else {
                Log.w(TAG, "Keeping the previous library, the scan returned no tracks")
            }
            scanGeneration.incrementAndGet()
            notifyListeners()
            scanned
        }
    }

    /**
     * Removes one track after an explicit user confirmation and rewrites the index.
     *
     * Unlike a scan, an explicit removal is written even when it empties the library. Memory is
     * only updated after the index was written, so the two can never disagree.
     *
     * @return true when the entry left the library and the index.
     */
    suspend fun removeTrack(context: Context, id: String, path: String): Boolean {
        val appContext = context.applicationContext

        return scanLock.withLock {
            val result = synchronized(stateLock) { LibraryRemoval.remove(musicFiles, id, path) }
            val removed = result.removed ?: return@withLock false

            val written = withContext(Dispatchers.IO) {
                writeCache(appContext, result.items, force = true)
            }
            if (!written) {
                Log.w(TAG, "Could not persist the removal of ${removed.id}")
                return@withLock false
            }

            synchronized(stateLock) {
                musicFiles = result.items
                isLoaded = true
            }
            notifyListeners()
            true
        }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE_NAME)

    /** Returns null when the index is missing, unreadable or corrupt. */
    private fun readCache(context: Context): List<MusicItem>? {
        val json = LibraryCacheFile.read(cacheFile(context)) ?: return null

        return try {
            MusicLibraryCodec.decode(json)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable library cache", e)
            null
        }
    }

    /**
     * Caches [items], except when that would replace a known non-empty index with nothing, which
     * happens when a scan sees an empty MediaStore because of a transient error.
     *
     * @param force writes an empty library too, for an explicit user removal.
     * @return true only when the new index is in place.
     */
    private fun writeCache(context: Context, items: List<MusicItem>, force: Boolean = false): Boolean {
        val file = cacheFile(context)

        if (!force) {
            val existingCount = if (items.isEmpty()) countCachedItems(file) else null
            if (!LibraryCachePolicy.shouldWriteCache(existingCount, items.size)) {
                Log.w(TAG, "Keeping the previous library index, the scan returned no tracks")
                return false
            }
        }

        if (!LibraryCacheFile.write(file, MusicLibraryCodec.encode(items))) {
            Log.w(TAG, "Could not cache the music library")
            return false
        }
        return true
    }

    /** Entries in the cached index, or null when there is no usable index to preserve. */
    private fun countCachedItems(file: File): Int? = try {
        LibraryCacheFile.read(file)?.let { MusicLibraryCodec.decode(it).size }
    } catch (e: Exception) {
        null
    }
}
