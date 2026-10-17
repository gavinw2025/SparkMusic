package com.sparkmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGroupingTest {

    private fun item(
        id: String,
        title: String = "Song $id",
        artist: String = "Artist",
        album: String = "Album",
        path: String = "/music/$id.mp3",
        genres: List<String> = emptyList()
    ) = MusicItem(id, title, artist, album, 1000, path, genres)

    @Test
    fun `artists are grouped by name with counts and unknown normalisation`() {
        val items = listOf(
            item("1", artist = "Alpha"),
            item("2", artist = "Alpha"),
            item("3", artist = "<unknown>"),
            item("4", artist = "")
        )

        val groups = LibraryGrouping.artists(items)

        assertEquals(listOf("Alpha", "Unknown Artist"), groups.map { it.title })
        assertEquals(listOf(2, 2), groups.map { it.count })
    }

    @Test
    fun `albums are grouped by album and artist`() {
        val items = listOf(
            item("1", album = "Greatest", artist = "A"),
            item("2", album = "Greatest", artist = "A"),
            item("3", album = "Greatest", artist = "B"),
            item("4", album = "<unknown>", artist = "A")
        )

        val groups = LibraryGrouping.albums(items)

        assertEquals(3, groups.size)
        val sameAlbum = groups.filter { it.title == "Greatest" }
        assertEquals(listOf("A", "B"), sameAlbum.map { it.artist })
        assertEquals(listOf(2, 1), sameAlbum.map { it.count })
        assertTrue(groups.any { it.title == "Unknown Album" })
    }

    @Test
    fun `folders are derived from the file path`() {
        val items = listOf(
            item("1", path = "/storage/music/Albums/one.mp3"),
            item("2", path = "/storage/music/Albums/two.mp3"),
            item("3", path = "/storage/music/Singles/three.mp3")
        )

        val groups = LibraryGrouping.folders(items)

        assertEquals(listOf("Albums", "Singles"), groups.map { it.title })
        assertEquals(listOf(2, 1), groups.map { it.count })
        assertEquals("/storage/music/Albums", groups.first().subtitle)
    }

    @Test
    fun `a track with several genres appears in each of them once`() {
        val items = listOf(
            item("1", genres = listOf("Rock", "Pop", "Rock")),
            item("2", genres = listOf("Rock")),
            item("3", genres = listOf("Jazz"))
        )

        val groups = LibraryGrouping.genres(items)
        val rock = groups.first { it.title == "Rock" }
        val pop = groups.first { it.title == "Pop" }

        assertEquals(listOf("Jazz", "Pop", "Rock"), groups.map { it.title })
        assertEquals(2, rock.count)
        assertEquals(1, pop.count)
        assertEquals(listOf("1", "2"), LibraryGrouping.songsIn(rock, items).map { it.id })
        assertEquals(listOf("1"), LibraryGrouping.songsIn(pop, items).map { it.id })
    }

    @Test
    fun `duplicate genre names within one track do not duplicate the song`() {
        val items = listOf(item("1", genres = listOf("Pop", "Pop", "Pop")))

        val pop = LibraryGrouping.genres(items).single()

        assertEquals(1, pop.count)
        assertEquals(1, LibraryGrouping.songsIn(pop, items).size)
    }

    @Test
    fun `unknown or blank genres are ignored`() {
        val items = listOf(
            item("1", genres = listOf("<unknown>", "", "  ")),
            item("2", genres = emptyList())
        )

        assertTrue(LibraryGrouping.genres(items).isEmpty())
    }

    @Test
    fun `songsIn filters artists and albums`() {
        val items = listOf(
            item("1", artist = "Alpha", album = "First"),
            item("2", artist = "Alpha", album = "Second"),
            item("3", artist = "Beta", album = "First")
        )

        val alpha = LibraryGrouping.artists(items).first { it.title == "Alpha" }
        val firstByBeta = LibraryGrouping.albums(items).first { it.title == "First" && it.artist == "Beta" }

        assertEquals(listOf("1", "2"), LibraryGrouping.songsIn(alpha, items).map { it.id })
        assertEquals(listOf("3"), LibraryGrouping.songsIn(firstByBeta, items).map { it.id })
    }

    @Test
    fun `groups are sorted case insensitively`() {
        val items = listOf(
            item("1", artist = "beta"),
            item("2", artist = "Alpha"),
            item("3", artist = "Gamma")
        )

        assertEquals(
            listOf("Alpha", "beta", "Gamma"),
            LibraryGrouping.artists(items).map { it.title }
        )
    }
}
