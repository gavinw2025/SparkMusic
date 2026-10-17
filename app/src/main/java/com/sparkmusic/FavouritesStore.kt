package com.sparkmusic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FavouriteEntry(
    val id: String,
    val path: String,
    val addedAt: Long
)

/**
 * Pure favourite set: id-first, path-fallback matching, most-recently-added ordering and
 * duplicate prevention. Free of Android types so it can be unit tested.
 */
internal class FavouritesState(entries: List<FavouriteEntry> = emptyList()) {

    private val byId = LinkedHashMap<String, FavouriteEntry>()
    private val pathToId = HashMap<String, String>()

    init {
        entries.forEach { entry ->
            if (entry.id.isEmpty() || entry.path.isEmpty()) return@forEach
            if (byId.containsKey(entry.id) || pathToId.containsKey(entry.path)) return@forEach
            byId[entry.id] = entry
            pathToId[entry.path] = entry.id
        }
    }

    /** Most recently added first. */
    fun entries(): List<FavouriteEntry> = byId.values.sortedByDescending { it.addedAt }

    fun isFavourite(id: String, path: String): Boolean =
        byId.containsKey(id) || (path.isNotEmpty() && pathToId.containsKey(path))

    /** @return true when the track is a favourite afterwards. */
    fun toggle(id: String, path: String, now: Long): Boolean {
        val existing = byId[id] ?: path.takeIf { it.isNotEmpty() }?.let { pathToId[it] }?.let { byId[it] }
        if (existing != null) {
            remove(existing)
            return false
        }
        if (id.isEmpty() || path.isEmpty()) return false
        byId[id] = FavouriteEntry(id, path, now)
        pathToId[path] = id
        return true
    }

    /** Adds without duplicating; returns true when the entry was new. */
    fun add(id: String, path: String, now: Long): Boolean {
        if (id.isEmpty() || path.isEmpty()) return false
        if (isFavourite(id, path)) return false
        byId[id] = FavouriteEntry(id, path, now)
        pathToId[path] = id
        return true
    }

    /** Removes by id first, then by path; returns true when an entry was removed. */
    fun remove(id: String, path: String): Boolean {
        val existing = byId[id] ?: path.takeIf { it.isNotEmpty() }?.let { pathToId[it] }?.let { byId[it] }
            ?: return false
        remove(existing)
        return true
    }

    /**
     * Favourites that are currently in [items], most recently added first. Entries whose files
     * are temporarily unavailable stay stored but are omitted from the returned list.
     */
    fun resolve(items: List<MusicItem>): List<MusicItem> {
        val itemsById = items.associateBy { it.id }
        val itemsByPath = items.associateBy { it.path }
        val seen = HashSet<String>()
        val result = ArrayList<MusicItem>()

        for (entry in entries()) {
            val item = itemsById[entry.id] ?: itemsByPath[entry.path] ?: continue
            if (seen.add(item.id)) result.add(item)
        }
        return result
    }

    private fun remove(entry: FavouriteEntry) {
        byId.remove(entry.id)
        pathToId.remove(entry.path)
    }
}

internal object FavouritesCodec {

    private const val VERSION = 1

    fun encode(entries: List<FavouriteEntry>): String {
        val items = JSONArray()
        entries.forEach { entry ->
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("path", entry.path)
                    .put("addedAt", entry.addedAt)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("items", items)
            .toString()
    }

    /** Returns null when the content is missing or is not a valid favourites document. */
    fun decodeOrNull(json: String?): List<FavouriteEntry>? {
        if (json.isNullOrBlank()) return null
        return try {
            val items = JSONObject(json).optJSONArray("items") ?: return null
            (0 until items.length()).mapNotNull { index ->
                val entry = items.optJSONObject(index) ?: return@mapNotNull null
                val id = entry.optString("id")
                val path = entry.optString("path")
                if (id.isEmpty() || path.isEmpty()) return@mapNotNull null
                FavouriteEntry(id, path, entry.optLong("addedAt"))
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * File access for favourites. Reading never writes, so a corrupt file survives until the user
 * actually changes a favourite.
 */
internal object FavouritesPersistence {

    fun load(file: File): List<FavouriteEntry>? =
        FavouritesCodec.decodeOrNull(LibraryCacheFile.read(file))

    fun save(file: File, entries: List<FavouriteEntry>): Boolean =
        LibraryCacheFile.write(file, FavouritesCodec.encode(entries))
}

/**
 * Phone-only favourites, stored separately from the replaceable library cache so rescans and
 * cache rebuilds never touch them. Android Auto never reads this store.
 */
object FavouritesStore {

    private const val TAG = "FavouritesStore"
    private const val FILE_NAME = "favourites.json"

    private val lock = Any()
    private val listeners = LinkedHashSet<() -> Unit>()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private var state = FavouritesState()
    private var file: File? = null

    @Volatile
    private var isLoaded = false

    fun load(context: Context) {
        if (isLoaded) return

        synchronized(lock) {
            if (isLoaded) return
            val target = File(context.applicationContext.filesDir, FILE_NAME)
            file = target
            val entries = FavouritesPersistence.load(target)
            if (entries == null && target.exists()) {
                Log.w(TAG, "Ignoring unreadable favourites file")
            }
            state = FavouritesState(entries.orEmpty())
            isLoaded = true
        }
    }

    fun isFavourite(id: String?, path: String?): Boolean {
        if (id.isNullOrEmpty()) return false
        return synchronized(lock) { state.isFavourite(id, path.orEmpty()) }
    }

    /** Visible favourites (unavailable files are kept in storage but omitted). */
    fun favouriteItems(items: List<MusicItem>): List<MusicItem> =
        synchronized(lock) { state.resolve(items) }

    /** Toggles a favourite; never touches playback. Requires [load] to have run. */
    fun toggle(id: String, path: String): Boolean {
        val now = System.currentTimeMillis()
        val snapshot: List<FavouriteEntry>
        val result: Boolean

        synchronized(lock) {
            result = state.toggle(id, path, now)
            snapshot = state.entries()
        }

        notifyListeners()
        persist(snapshot)
        return result
    }

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { it() }
    }

    private fun persist(entries: List<FavouriteEntry>) {
        val target = file ?: return
        writeScope.launch {
            writeLock.withLock {
                if (!FavouritesPersistence.save(target, entries)) {
                    Log.w(TAG, "Could not save favourites")
                }
            }
        }
    }
}
