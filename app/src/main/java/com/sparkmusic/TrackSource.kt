package com.sparkmusic

/** Where a library entry lives, which decides how it can be deleted. */
sealed class TrackSource {
    /** A MediaStore audio row, deletable through a system confirmation request. */
    data class MediaStore(val id: Long) : TrackSource()

    /** A SAF document reached through a folder the user granted. */
    data class Document(val uri: String) : TrackSource()

    /** Anything else: the app has no safe deletion mechanism for it. */
    object Unknown : TrackSource()
}

/**
 * Resolves the deletion source from the identifiers the scanner produces, so callers never assume
 * every track has a MediaStore row: tracks found through selected folders do not.
 */
internal object TrackSourceResolver {

    fun resolve(id: String, path: String): TrackSource {
        val numericId = id.toLongOrNull()

        if (path.startsWith("content://media/")) {
            return if (numericId != null && numericId > 0L) {
                TrackSource.MediaStore(numericId)
            } else {
                TrackSource.Unknown
            }
        }
        if (path.startsWith("content://")) return TrackSource.Document(path)
        if (numericId != null && numericId > 0L) return TrackSource.MediaStore(numericId)
        return TrackSource.Unknown
    }

    /**
     * The tree URI that owns a document URI, or null when the value is not a tree document.
     * Used to look up whether the selected folder was granted write access.
     */
    fun treeUriOf(documentUri: String): String? {
        val marker = "/document/"
        val index = documentUri.indexOf(marker)
        if (index <= 0) return null
        return documentUri.substring(0, index)
    }
}
