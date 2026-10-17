package com.sparkmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibraryCodecTest {

    private fun item(
        id: String,
        artist: String = "Artist",
        album: String = "Album",
        path: String = "/music/$id.mp3",
        genres: List<String> = emptyList(),
        folder: String = ""
    ) = MusicItem(id, "Song $id", artist, album, 1234, path, genres, folder)

    @Test
    fun `round trip preserves every field including genres`() {
        val items = listOf(
            item("1", genres = listOf("Rock", "Pop")),
            item("2", artist = "Other", album = "Another", path = "/music/two.flac")
        )

        assertEquals(items, MusicLibraryCodec.decode(MusicLibraryCodec.encode(items)))
    }

    @Test
    fun `round trip preserves the folder`() {
        val items = listOf(item("1", path = "content://doc/a", folder = "Music/Albums"))

        assertEquals(
            "Music/Albums",
            MusicLibraryCodec.decode(MusicLibraryCodec.encode(items)).single().folder
        )
    }

    @Test
    fun `legacy cache without folder decodes with an empty folder`() {
        val legacy = """[{"id":"1","title":"Old","artist":"A","album":"B","duration":100,"path":"/music/old.mp3"}]"""

        assertTrue(MusicLibraryCodec.decode(legacy).single().folder.isEmpty())
    }

    @Test
    fun `legacy cache without genres still decodes`() {
        val legacy = """[
            {"id":"1","title":"Old","artist":"A","album":"B","duration":100,"path":"/music/old.mp3"}
        ]"""

        val decoded = MusicLibraryCodec.decode(legacy)

        assertEquals(1, decoded.size)
        assertEquals("Old", decoded.single().title)
        assertTrue(decoded.single().genres.isEmpty())
    }

    @Test
    fun `single genre field from an early cache is accepted`() {
        val json = """[{"id":"1","title":"T","artist":"A","album":"B","duration":1,"path":"/p","genre":"Jazz"}]"""

        assertEquals(listOf("Jazz"), MusicLibraryCodec.decode(json).single().genres)
    }

    @Test
    fun `entries without id or path are skipped`() {
        val json = """[
            {"id":"1","title":"Keep","artist":"A","album":"B","duration":1,"path":"/keep"},
            {"id":"","title":"Drop","artist":"A","album":"B","duration":1,"path":"/drop"},
            {"id":"3","title":"Drop","artist":"A","album":"B","duration":1,"path":""}
        ]"""

        assertEquals(listOf("1"), MusicLibraryCodec.decode(json).map { it.id })
    }

    @Test
    fun `an index with entries but none valid is corrupt`() {
        val json = """[{"id":"","path":""}]"""

        assertThrows(IllegalArgumentException::class.java) {
            MusicLibraryCodec.decode(json)
        }
    }

    @Test
    fun `an empty array decodes to an empty library`() {
        assertTrue(MusicLibraryCodec.decode("[]").isEmpty())
    }

    @Test
    fun `invalid json throws`() {
        assertThrows(Exception::class.java) {
            MusicLibraryCodec.decode("not json")
        }
    }
}
