package com.sparkmusic

/**
 * Decisions for restoring, scanning and rewriting the library index.
 *
 * Kept free of Android types so the recovery and cache-safety rules can be verified by JVM tests.
 */
internal object LibraryCachePolicy {

    /**
     * The startup scan only runs when nothing was restored, the permission is already granted and
     * no scan is already in progress, so a valid restored library is never scanned again.
     */
    fun shouldScanOnStartup(
        restoredCount: Int,
        hasAudioPermission: Boolean,
        scanInProgress: Boolean
    ): Boolean = restoredCount == 0 && hasAudioPermission && !scanInProgress

    /**
     * The permission is requested only for an empty library, and only while the user has not
     * already answered the prompt, to avoid repeating it on every launch.
     */
    fun shouldRequestPermissionOnStartup(
        restoredCount: Int,
        hasAudioPermission: Boolean,
        alreadyAsked: Boolean
    ): Boolean = restoredCount == 0 && !hasAudioPermission && !alreadyAsked

    /**
     * An empty scan result may only replace the index when there is no known non-empty index to
     * lose. An empty library can still be cached when nothing valid was stored before.
     *
     * @param existingCount entries in the usable cached index, or null when there is none.
     */
    fun shouldWriteCache(existingCount: Int?, newCount: Int): Boolean =
        newCount > 0 || (existingCount ?: 0) == 0
}
