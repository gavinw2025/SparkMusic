package com.sparkmusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCachePolicyTest {

    @Test
    fun `startup scan runs when nothing was restored and the permission is granted`() {
        assertTrue(
            LibraryCachePolicy.shouldScanOnStartup(
                restoredCount = 0,
                hasAudioPermission = true,
                scanInProgress = false
            )
        )
    }

    @Test
    fun `startup scan is skipped when a valid library was restored`() {
        assertFalse(
            LibraryCachePolicy.shouldScanOnStartup(
                restoredCount = 29,
                hasAudioPermission = true,
                scanInProgress = false
            )
        )
    }

    @Test
    fun `startup scan is skipped without permission or while a scan runs`() {
        assertFalse(
            LibraryCachePolicy.shouldScanOnStartup(
                restoredCount = 0,
                hasAudioPermission = false,
                scanInProgress = false
            )
        )
        assertFalse(
            LibraryCachePolicy.shouldScanOnStartup(
                restoredCount = 0,
                hasAudioPermission = true,
                scanInProgress = true
            )
        )
    }

    @Test
    fun `permission is requested for an empty library that was never asked about`() {
        assertTrue(
            LibraryCachePolicy.shouldRequestPermissionOnStartup(
                restoredCount = 0,
                hasAudioPermission = false,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun `permission is not requested twice or for a restored library`() {
        assertFalse(
            LibraryCachePolicy.shouldRequestPermissionOnStartup(
                restoredCount = 0,
                hasAudioPermission = false,
                alreadyAsked = true
            )
        )
        assertFalse(
            LibraryCachePolicy.shouldRequestPermissionOnStartup(
                restoredCount = 29,
                hasAudioPermission = false,
                alreadyAsked = false
            )
        )
        assertFalse(
            LibraryCachePolicy.shouldRequestPermissionOnStartup(
                restoredCount = 0,
                hasAudioPermission = true,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun `an empty scan never replaces a non-empty index`() {
        assertFalse(LibraryCachePolicy.shouldWriteCache(existingCount = 29, newCount = 0))
    }

    @Test
    fun `a non-empty scan always replaces the index`() {
        assertTrue(LibraryCachePolicy.shouldWriteCache(existingCount = 29, newCount = 30))
        assertTrue(LibraryCachePolicy.shouldWriteCache(existingCount = 29, newCount = 1))
        assertTrue(LibraryCachePolicy.shouldWriteCache(existingCount = null, newCount = 3))
    }

    @Test
    fun `an empty library can still be cached when nothing valid would be lost`() {
        assertTrue(LibraryCachePolicy.shouldWriteCache(existingCount = null, newCount = 0))
        assertTrue(LibraryCachePolicy.shouldWriteCache(existingCount = 0, newCount = 0))
    }
}
