package com.sparkmusic

enum class GroupType { ARTIST, ALBUM, GENRE, FOLDER }

data class LibraryGroup(
    val type: GroupType,
    val key: String,
    val title: String,
    val subtitle: String,
    val count: Int,
    val artist: String = ""
)

/**
 * Read-only groupings of the library. Kept free of Android types so the behaviour is testable.
 */
object LibraryGrouping {

    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
    const val UNKNOWN_FOLDER = "Unknown folder"

    fun normalizeArtist(value: String): String = normalize(value, UNKNOWN_ARTIST)

    fun normalizeAlbum(value: String): String = normalize(value, UNKNOWN_ALBUM)

    fun folderPath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        val separator = trimmed.trimEnd('/').lastIndexOf('/')
        return if (separator <= 0) "" else trimmed.substring(0, separator)
    }

    fun folderPath(item: MusicItem): String =
        item.folder.ifBlank { folderPath(item.path) }

    private fun folderLabel(path: String): String =
        if (path.isEmpty()) UNKNOWN_FOLDER else path.substringAfterLast('/').ifEmpty { path }

    fun artists(items: List<MusicItem>): List<LibraryGroup> =
        items.groupBy { normalizeArtist(it.artist) }
            .map { (artist, songs) ->
                LibraryGroup(GroupType.ARTIST, "artist:$artist", artist, "", songs.size)
            }
            .sortedBy { it.title.lowercase() }

    fun albums(items: List<MusicItem>): List<LibraryGroup> =
        items.groupBy { normalizeAlbum(it.album) to normalizeArtist(it.artist) }
            .map { (entry, songs) ->
                val (album, artist) = entry
                LibraryGroup(
                    type = GroupType.ALBUM,
                    key = "album:$album\u0000$artist",
                    title = album,
                    subtitle = artist,
                    count = songs.size,
                    artist = artist
                )
            }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.subtitle.lowercase() }))

    fun genres(items: List<MusicItem>): List<LibraryGroup> {
        val byGenre = LinkedHashMap<String, MutableList<MusicItem>>()
        items.forEach { item ->
            item.genres
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals("<unknown>", true) }
                .distinct()
                .forEach { genre -> byGenre.getOrPut(genre) { mutableListOf() }.add(item) }
        }
        return byGenre.map { (genre, songs) ->
            LibraryGroup(GroupType.GENRE, "genre:$genre", genre, "", songs.distinctBy { it.id }.size)
        }.sortedBy { it.title.lowercase() }
    }

    fun folders(items: List<MusicItem>): List<LibraryGroup> =
        items.groupBy { folderPath(it) }
            .map { (path, songs) ->
                LibraryGroup(GroupType.FOLDER, path, folderLabel(path), path, songs.size)
            }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.subtitle.lowercase() }))

    /** Songs of [group], without duplicates even when a track has several matching genres. */
    fun songsIn(group: LibraryGroup, items: List<MusicItem>): List<MusicItem> = when (group.type) {
        GroupType.ARTIST -> items.filter { normalizeArtist(it.artist) == group.title }
        GroupType.ALBUM -> items.filter {
            normalizeAlbum(it.album) == group.title && normalizeArtist(it.artist) == group.artist
        }
        GroupType.GENRE -> items.filter { item ->
            item.genres.any { genre -> genre.trim().equals(group.title, ignoreCase = true) }
        }
        GroupType.FOLDER -> items.filter { folderPath(it) == group.key }
    }.distinctBy { it.id }

    private fun normalize(value: String, fallback: String): String {
        val trimmed = value.trim()
        return if (trimmed.isEmpty() || trimmed.equals("<unknown>", ignoreCase = true)) {
            fallback
        } else {
            trimmed
        }
    }
}
