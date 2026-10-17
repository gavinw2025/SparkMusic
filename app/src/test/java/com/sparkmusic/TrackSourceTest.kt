package com.sparkmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackSourceTest {

    @Test
    fun `a media store id with an absolute path is a media store track`() {
        assertEquals(
            TrackSource.MediaStore(42),
            TrackSourceResolver.resolve("42", "/storage/emulated/0/Music/song.mp3")
        )
    }

    @Test
    fun `a media store content uri is a media store track`() {
        assertEquals(
            TrackSource.MediaStore(42),
            TrackSourceResolver.resolve("42", "content://media/external/audio/media/42")
        )
    }

    @Test
    fun `a saf document id with a content uri is a document`() {
        val path = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                "document/primary%3AMusic%2Fsong.mp3"

        assertEquals(
            TrackSource.Document(path),
            TrackSourceResolver.resolve(
                "saf:com.android.externalstorage.documents:primary:Music/song.mp3",
                path
            )
        )
    }

    @Test
    fun `an unknown entry has no deletion source`() {
        assertEquals(TrackSource.Unknown, TrackSourceResolver.resolve("", ""))
        assertEquals(TrackSource.Unknown, TrackSourceResolver.resolve("not-a-number", "/tmp/a.mp3"))
        assertEquals(TrackSource.Unknown, TrackSourceResolver.resolve("0", "/tmp/a.mp3"))
    }

    @Test
    fun `the owning tree uri is derived from a document uri`() {
        val documentUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                "document/primary%3AMusic%2Fsong.mp3"

        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            TrackSourceResolver.treeUriOf(documentUri)
        )
    }

    @Test
    fun `a non tree uri has no owning tree`() {
        assertNull(TrackSourceResolver.treeUriOf("content://media/external/audio/media/42"))
        assertNull(TrackSourceResolver.treeUriOf("/storage/emulated/0/Music/song.mp3"))
        assertNull(TrackSourceResolver.treeUriOf(""))
    }
}
