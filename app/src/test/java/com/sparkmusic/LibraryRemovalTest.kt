package com.sparkmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRemovalTest {

    private fun item(id: String, path: String = "/music/$id.mp3") =
        MusicItem(id, "Song $id", "Artist", "Album", 1000, path)

    @Test
    fun `the matching entry is removed and the rest keep their order`() {
        val items = listOf(item("1"), item("2"), item("3"))

        val result = LibraryRemoval.remove(items, "2", "/music/2.mp3")

        assertEquals(listOf("1", "3"), result.items.map { it.id })
        assertEquals("2", result.removed?.id)
    }

    @Test
    fun `a reused id removes exactly one entry`() {
        val items = listOf(item("7", "/music/a.mp3"), item("7", "/music/b.mp3"))

        val result = LibraryRemoval.remove(items, "7", "/music/a.mp3")

        assertEquals(1, result.items.size)
        assertEquals("/music/b.mp3", result.items.single().path)
    }

    @Test
    fun `an id miss falls back to an exact path match`() {
        val items = listOf(item("1"), item("2"))

        val result = LibraryRemoval.remove(items, "stale-id", "/music/2.mp3")

        assertEquals(listOf("1"), result.items.map { it.id })
    }

    @Test
    fun `a missing entry leaves the library untouched`() {
        val items = listOf(item("1"), item("2"))

        val result = LibraryRemoval.remove(items, "9", "/music/9.mp3")

        assertNull(result.removed)
        assertSame(items, result.items)
    }

    @Test
    fun `empty identifiers never remove anything`() {
        val items = listOf(item("1"))

        assertNull(LibraryRemoval.remove(items, "", "").removed)
    }

    @Test
    fun `a partial path never matches`() {
        val items = listOf(item("1", "/music/12.mp3"))

        assertNull(LibraryRemoval.remove(items, "", "/music/2.mp3").removed)
    }
}

class LibraryRemovalCacheTest {

    private fun item(id: String) =
        MusicItem(id, "Song $id", "Artist", "Album", 1000, "/music/$id.mp3")

    @Test
    fun `the reduced library still round trips through the cache format`() {
        val remaining = LibraryRemoval.remove(listOf(item("1"), item("2")), "1", "").items

        val decoded = MusicLibraryCodec.decode(MusicLibraryCodec.encode(remaining))

        assertEquals(listOf("2"), decoded.map { it.id })
    }

    @Test
    fun `removing the last track writes a valid empty library`() {
        val empty = LibraryRemoval.remove(listOf(item("1")), "1", "").items

        val json = MusicLibraryCodec.encode(empty)

        assertEquals("[]", json)
        assertTrue(MusicLibraryCodec.decode(json).isEmpty())
    }

    @Test
    fun `a favourite outside the library stays stored and comes back with the track`() {
        val favourites = FavouritesState(listOf(FavouriteEntry("1", "/music/1.mp3", 5)))
        val items = listOf(item("1"), item("2"))

        val remaining = LibraryRemoval.remove(items, "1", "/music/1.mp3").items

        assertTrue(favourites.isFavourite("1", "/music/1.mp3"))
        assertTrue(favourites.resolve(remaining).isEmpty())
        assertEquals(listOf("1"), favourites.resolve(items).map { it.id })
    }
}
