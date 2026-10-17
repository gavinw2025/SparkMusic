package com.sparkmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesStateTest {

    private fun item(id: String, path: String) =
        MusicItem(id, "Song $id", "Artist", "Album", 1000, path)

    @Test
    fun `toggle adds then removes a favourite`() {
        val state = FavouritesState()

        assertTrue(state.toggle("1", "/music/a.mp3", 10))
        assertTrue(state.isFavourite("1", "/music/a.mp3"))

        assertFalse(state.toggle("1", "/music/a.mp3", 20))
        assertFalse(state.isFavourite("1", "/music/a.mp3"))
        assertTrue(state.entries().isEmpty())
    }

    @Test
    fun `add prevents duplicate ids and duplicate paths`() {
        val state = FavouritesState()

        assertTrue(state.add("1", "/music/a.mp3", 1))
        assertFalse(state.add("1", "/music/a.mp3", 2))
        assertFalse(state.add("2", "/music/a.mp3", 3))

        assertEquals(1, state.entries().size)
    }

    @Test
    fun `duplicate entries in a loaded file are ignored`() {
        val state = FavouritesState(
            listOf(
                FavouriteEntry("1", "/music/a.mp3", 1),
                FavouriteEntry("1", "/music/other.mp3", 2),
                FavouriteEntry("2", "/music/a.mp3", 3)
            )
        )

        assertEquals(1, state.entries().size)
        assertEquals("1", state.entries().single().id)
    }

    @Test
    fun `matching uses the id first and falls back to the path`() {
        val state = FavouritesState(listOf(FavouriteEntry("1", "/music/a.mp3", 1)))

        assertTrue(state.isFavourite("1", "/music/renamed.mp3"))
        assertTrue(state.isFavourite("99", "/music/a.mp3"))
        assertFalse(state.isFavourite("99", "/music/b.mp3"))
    }

    @Test
    fun `toggling a known path removes the entry when the id changed`() {
        val state = FavouritesState(listOf(FavouriteEntry("1", "/music/a.mp3", 1)))

        assertFalse(state.toggle("99", "/music/a.mp3", 2))
        assertTrue(state.entries().isEmpty())
        assertFalse(state.isFavourite("99", "/music/a.mp3"))
    }

    @Test
    fun `favourites resolve most recently added first`() {
        val state = FavouritesState()
        state.toggle("1", "/music/a.mp3", 100)
        state.toggle("2", "/music/b.mp3", 200)

        val resolved = state.resolve(listOf(item("1", "/music/a.mp3"), item("2", "/music/b.mp3")))

        assertEquals(listOf("2", "1"), resolved.map { it.id })
    }

    @Test
    fun `unavailable favourites stay stored but are hidden`() {
        val state = FavouritesState()
        state.toggle("1", "/music/a.mp3", 1)

        assertTrue(state.resolve(listOf(item("2", "/music/b.mp3"))).isEmpty())
        assertEquals(1, state.entries().size)
    }

    @Test
    fun `resolve matches by path when the media id changed`() {
        val state = FavouritesState()
        state.toggle("1", "/music/a.mp3", 1)

        val resolved = state.resolve(listOf(item("2", "/music/a.mp3")))

        assertEquals("2", resolved.single().id)
    }
}
