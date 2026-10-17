package com.sparkmusic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

data class MusicItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val path: String,
    val genres: List<String> = emptyList(),
    val folder: String = ""
)

class MusicScanner(private val context: Context) {

    fun scanMusicFiles(scope: ScanScope = ScanScope()): List<MusicItem> = when (scope.mode) {
        ScanMode.ALL_DEVICE -> scanDeviceMusic()
        ScanMode.SELECTED_FOLDERS -> scanSelectedFolders(scope)
    }

    /** Existing MediaStore scan: every track visible to the app. */
    private fun scanDeviceMusic(): List<MusicItem> {
        val musicFiles = mutableListOf<MusicItem>()
        val genresByAudioId = readGenres()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn).toString()
                val title = normalize(cursor.getString(titleColumn), "Unknown")
                val artist = normalize(cursor.getString(artistColumn), LibraryGrouping.UNKNOWN_ARTIST)
                val album = normalize(cursor.getString(albumColumn), LibraryGrouping.UNKNOWN_ALBUM)
                val duration = cursor.getLong(durationColumn)
                val path = cursor.getString(pathColumn)?.takeIf { it.isNotBlank() } ?: continue

                musicFiles.add(
                    MusicItem(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        duration = duration,
                        path = path,
                        genres = genresByAudioId[id].orEmpty(),
                        folder = LibraryGrouping.folderPath(path)
                    )
                )
            }
        }

        return musicFiles
    }

    /**
     * SAF scan: only files inside the selected trees, including nested folders. Document URIs
     * double as the playable path, so the existing ExoPlayer pipeline can open them.
     */
    private fun scanSelectedFolders(scope: ScanScope): List<MusicItem> {
        if (scope.folders.isEmpty()) {
            throw ScanScopeException("Select at least one folder before scanning.")
        }

        val accumulator = ScanResultAccumulator()

        scope.folders.forEach { folder ->
            if (!ScanScopeStore.hasReadPermission(context, folder.uri)) {
                throw ScanScopeException(
                    "Access to \"${folder.label}\" was lost. Re-add the folder and try again."
                )
            }

            val treeUri = Uri.parse(folder.uri)
            try {
                walkTree(
                    treeUri = treeUri,
                    parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri),
                    displayPath = folder.label.ifBlank { treeUri.toString() },
                    accumulator = accumulator,
                    visited = HashSet()
                )
            } catch (e: ScanScopeException) {
                throw e
            } catch (e: SecurityException) {
                throw ScanScopeException(
                    "Access to \"${folder.label}\" was lost. Re-add the folder and try again."
                )
            } catch (e: Exception) {
                throw ScanScopeException(
                    "Could not read \"${folder.label}\": ${e.message ?: "unknown error"}"
                )
            }
        }

        return accumulator.items().sortedBy { it.title.lowercase() }
    }

    private fun walkTree(
        treeUri: Uri,
        parentDocumentId: String,
        displayPath: String,
        accumulator: ScanResultAccumulator,
        visited: MutableSet<String>
    ) {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn =
                cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn) ?: continue
                val mimeType = cursor.getString(mimeColumn).orEmpty()

                if (!visited.add(documentId)) continue

                val childPath = if (displayPath.isEmpty()) name else "$displayPath/$name"
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(treeUri, documentId, childPath, accumulator, visited)
                } else if (isAudio(mimeType, name)) {
                    val documentUri =
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    accumulator.add(
                        "${treeUri.authority}|$documentId",
                        readDocument(documentUri, name, displayPath)
                    )
                }
            }
        }
    }

    private fun isAudio(mimeType: String, name: String): Boolean {
        if (mimeType.startsWith("audio/")) return true
        return name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }

    private fun readDocument(documentUri: Uri, displayName: String, folder: String): MusicItem {
        val fallbackTitle = displayName.substringBeforeLast('.').ifBlank { displayName }
        val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }
            .getOrElse { documentUri.toString() }
        val id = "saf:${documentUri.authority}:$documentId"

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, documentUri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fallbackTitle
            val artist = normalize(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                LibraryGrouping.UNKNOWN_ARTIST
            )
            val album = normalize(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                LibraryGrouping.UNKNOWN_ALBUM
            )
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val genres = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.split(';', '/')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }
                ?.distinct()
                .orEmpty()

            MusicItem(
                id = id,
                title = title,
                artist = artist,
                album = album,
                duration = duration,
                path = documentUri.toString(),
                genres = genres,
                folder = folder
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read metadata for $displayName", e)
            MusicItem(
                id = id,
                title = fallbackTitle,
                artist = LibraryGrouping.UNKNOWN_ARTIST,
                album = LibraryGrouping.UNKNOWN_ALBUM,
                duration = 0L,
                path = documentUri.toString(),
                folder = folder
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Genre names keyed by audio id. Genres and their members exist since API 1, so this works
     * below API 30 where the GENRE column is unavailable; a track may belong to several genres.
     */
    private fun readGenres(): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()

        try {
            context.contentResolver.query(
                MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.GenresColumns.NAME),
                null,
                null,
                null
            )?.use { genreCursor ->
                val idColumn = genreCursor.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
                val nameColumn = genreCursor.getColumnIndexOrThrow(MediaStore.Audio.GenresColumns.NAME)

                while (genreCursor.moveToNext()) {
                    val genreId = genreCursor.getLong(idColumn)
                    val name = genreCursor.getString(nameColumn)?.trim().orEmpty()
                    if (name.isEmpty() || name.equals("<unknown>", ignoreCase = true)) continue

                    val membersUri =
                        MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
                    context.contentResolver.query(
                        membersUri,
                        arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID),
                        null,
                        null,
                        null
                    )?.use { memberCursor ->
                        val audioColumn =
                            memberCursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.Members.AUDIO_ID)
                        while (memberCursor.moveToNext()) {
                            val audioId = memberCursor.getLong(audioColumn).toString()
                            result.getOrPut(audioId) { mutableListOf() }.add(name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read genre metadata", e)
        }

        return result.mapValues { (_, names) -> names.distinct().sorted() }
    }

    private fun normalize(value: String?, fallback: String): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed.equals("<unknown>", ignoreCase = true)) {
            fallback
        } else {
            trimmed
        }
    }

    private companion object {
        private const val TAG = "MusicScanner"

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
            "wma", "mka", "mid", "midi", "amr"
        )
    }
}