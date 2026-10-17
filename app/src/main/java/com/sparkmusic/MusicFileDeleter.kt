package com.sparkmusic

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

/** Why the app cannot delete a file on its own. */
enum class DeleteUnavailableReason {
    /** The folder was granted read only; re-adding it can grant write access. */
    NO_WRITE_PERMISSION,

    /** Android below 11 has no per-file delete request and the app holds no storage permission. */
    UNSUPPORTED_ANDROID_VERSION,

    /** No deletion mechanism applies to this entry. */
    UNSUPPORTED_SOURCE
}

sealed class DeleteResult {
    /** The file is gone, so the library entry may be removed as well. */
    object Deleted : DeleteResult()

    /** MediaStore requires its own confirmation dialog before anything is deleted. */
    data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult()

    data class Unavailable(val reason: DeleteUnavailableReason) : DeleteResult()

    data class Failed(val message: String?) : DeleteResult()
}

/**
 * Deletes the file behind a library entry, never the library entry itself.
 *
 * Success is only reported when Android confirms the file is gone, so the library is never
 * updated on a cancelled or refused deletion.
 */
object MusicFileDeleter {

    private const val TAG = "MusicFileDeleter"

    /** SAF deletion happens immediately; MediaStore may return a confirmation request. */
    fun delete(context: Context, id: String, path: String): DeleteResult =
        when (val source = TrackSourceResolver.resolve(id, path)) {
            is TrackSource.MediaStore -> requestMediaStoreDeletion(context, source.id)
            is TrackSource.Document -> deleteDocument(context, source.uri)
            TrackSource.Unknown -> DeleteResult.Unavailable(DeleteUnavailableReason.UNSUPPORTED_SOURCE)
        }

    private fun requestMediaStoreDeletion(context: Context, id: Long): DeleteResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DeleteResult.Unavailable(DeleteUnavailableReason.UNSUPPORTED_ANDROID_VERSION)
        }

        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        return try {
            val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
            DeleteResult.NeedsConfirmation(request.intentSender)
        } catch (e: Exception) {
            Log.w(TAG, "Could not request deletion of $uri", e)
            DeleteResult.Failed(e.message)
        }
    }

    private fun deleteDocument(context: Context, uri: String): DeleteResult = try {
        if (DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))) {
            DeleteResult.Deleted
        } else {
            DeleteResult.Failed(null)
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "No write access to $uri", e)
        DeleteResult.Unavailable(DeleteUnavailableReason.NO_WRITE_PERMISSION)
    } catch (e: UnsupportedOperationException) {
        Log.w(TAG, "The provider does not support deleting $uri", e)
        DeleteResult.Unavailable(DeleteUnavailableReason.UNSUPPORTED_SOURCE)
    } catch (e: Exception) {
        Log.w(TAG, "Could not delete $uri", e)
        DeleteResult.Failed(e.message)
    }
}
