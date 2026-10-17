package com.sparkmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ScanMode { ALL_DEVICE, SELECTED_FOLDERS }

data class ScanFolder(
    val uri: String,
    val label: String,
    /** True when the persisted grant also allows deleting files inside this folder. */
    val writeAccess: Boolean = false
)

data class ScanScope(
    val mode: ScanMode = ScanMode.ALL_DEVICE,
    val folders: List<ScanFolder> = emptyList()
)

enum class ScanScopeProblem { NO_FOLDERS, FOLDER_UNAVAILABLE }

sealed class ScanScopeValidation {
    object Valid : ScanScopeValidation()
    data class Invalid(val problem: ScanScopeProblem) : ScanScopeValidation()
}

/** Thrown when a folder scan cannot start; the message is shown to the user. */
class ScanScopeException(message: String) : Exception(message)

object ScanScopePolicy {

    /** Pure validation so the rules can be unit tested. */
    fun validate(scope: ScanScope, hasReadPermission: (String) -> Boolean): ScanScopeValidation {
        if (scope.mode == ScanMode.ALL_DEVICE) return ScanScopeValidation.Valid
        if (scope.folders.isEmpty()) {
            return ScanScopeValidation.Invalid(ScanScopeProblem.NO_FOLDERS)
        }
        if (scope.folders.any { !hasReadPermission(it.uri) }) {
            return ScanScopeValidation.Invalid(ScanScopeProblem.FOLDER_UNAVAILABLE)
        }
        return ScanScopeValidation.Valid
    }
}

internal object ScanScopeCodec {

    private const val VERSION = 1

    fun encode(scope: ScanScope): String {
        val folders = JSONArray()
        scope.folders.forEach { folder ->
            folders.put(
                JSONObject()
                    .put("uri", folder.uri)
                    .put("label", folder.label)
                    .put("write", folder.writeAccess)
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("mode", scope.mode.name)
            .put("folders", folders)
            .toString()
    }

    /** Returns null when the content is missing or unreadable; callers fall back to ALL_DEVICE. */
    fun decodeOrNull(json: String?): ScanScope? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            val mode = runCatching { ScanMode.valueOf(root.optString("mode")) }
                .getOrDefault(ScanMode.ALL_DEVICE)
            val array = root.optJSONArray("folders") ?: JSONArray()
            val folders = (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val uri = entry.optString("uri")
                if (uri.isEmpty()) return@mapNotNull null
                val label = entry.optString("label").ifBlank { uri }
                ScanFolder(uri, label, entry.optBoolean("write", false))
            }.distinctBy { it.uri }

            ScanScope(mode, folders)
        } catch (e: Exception) {
            null
        }
    }
}

/** Deduplicates folder scans whose trees overlap or repeat a document. */
internal class ScanResultAccumulator {

    private val byKey = LinkedHashMap<String, MusicItem>()

    /** @return true when the item was new. */
    fun add(key: String, item: MusicItem): Boolean {
        if (byKey.containsKey(key)) return false
        byKey[key] = item
        return true
    }

    fun items(): List<MusicItem> = byKey.values.toList()
}

/**
 * Persists the scan mode and folder trees independently of the library cache and favourites.
 * SAF read permissions are taken here so they survive restarts.
 */
object ScanScopeStore {

    private const val TAG = "ScanScopeStore"
    private const val FILE_NAME = "scan_scope.json"

    private const val READ_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private const val READ_WRITE_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private val lock = Any()
    private var scope = ScanScope()

    @Volatile
    private var isLoaded = false

    fun load(context: Context) {
        if (isLoaded) return
        synchronized(lock) {
            if (isLoaded) return
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            scope = ScanScopeCodec.decodeOrNull(LibraryCacheFile.read(file)) ?: ScanScope()
            isLoaded = true
        }
    }

    fun current(context: Context): ScanScope {
        load(context)
        return synchronized(lock) { scope }
    }

    fun setMode(context: Context, mode: ScanMode): ScanScope {
        load(context)
        synchronized(lock) {
            scope = scope.copy(mode = mode)
            persist(context)
            return scope
        }
    }

    /**
     * Persists access to a folder tree. Write access is requested too, so the user can delete a
     * track from the device later; providers that grant read only still work for scanning.
     *
     * @return false when the URI permission could not be persisted.
     */
    fun addFolder(context: Context, uri: Uri): Boolean {
        load(context)

        val resolver = context.contentResolver
        val grantedFlags = try {
            resolver.takePersistableUriPermission(uri, READ_WRITE_FLAGS)
            READ_WRITE_FLAGS
        } catch (e: SecurityException) {
            try {
                resolver.takePersistableUriPermission(uri, READ_FLAGS)
                READ_FLAGS
            } catch (e2: SecurityException) {
                Log.w(TAG, "Could not persist access to $uri", e2)
                return false
            }
        }
        val canWrite = grantedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0

        val label = folderLabel(context, uri)
        synchronized(lock) {
            val folders = scope.folders.filterNot { it.uri == uri.toString() } +
                    ScanFolder(uri.toString(), label, canWrite)
            scope = scope.copy(mode = ScanMode.SELECTED_FOLDERS, folders = folders)
            persist(context)
        }
        return true
    }

    fun removeFolder(context: Context, uri: String): ScanScope {
        load(context)

        val canWrite = synchronized(lock) {
            scope.folders.firstOrNull { it.uri == uri }?.writeAccess == true
        }

        val updated = synchronized(lock) {
            scope = scope.copy(folders = scope.folders.filterNot { it.uri == uri })
            persist(context)
            scope
        }

        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                if (canWrite) READ_WRITE_FLAGS else READ_FLAGS
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not release access to $uri", e)
        }
        return updated
    }

    fun hasReadPermission(context: Context, uri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission
        }

    private fun persist(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!LibraryCacheFile.write(file, ScanScopeCodec.encode(scope))) {
            Log.w(TAG, "Could not save the scan scope")
        }
    }

    private fun folderLabel(context: Context, uri: Uri): String {
        val displayName = try {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (e: Exception) {
            null
        }

        return displayName?.takeIf { it.isNotBlank() }
            ?: DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { uri.toString() }
    }
}
