package com.sparkmusic

/** Outcome of removing a single library entry. */
internal data class LibraryRemovalResult(
    val items: List<MusicItem>,
    val removed: MusicItem?
)

/**
 * Removes exactly one entry: an exact media id first, then an exact path. Duplicate or reused ids
 * can therefore never take unrelated entries with them.
 */
internal object LibraryRemoval {

    fun remove(items: List<MusicItem>, id: String, path: String): LibraryRemovalResult {
        var index = if (id.isEmpty()) -1 else items.indexOfFirst { it.id == id }
        if (index < 0 && path.isNotEmpty()) index = items.indexOfFirst { it.path == path }
        if (index < 0) return LibraryRemovalResult(items, null)

        val remaining = items.toMutableList()
        val removed = remaining.removeAt(index)
        return LibraryRemovalResult(remaining, removed)
    }
}
