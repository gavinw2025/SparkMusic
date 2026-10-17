package com.sparkmusic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FavouritesCodecTest {

    @Test
    fun `encode and decode round trip`() {
        val entries = listOf(
            FavouriteEntry("1", "/music/a.mp3", 100),
            FavouriteEntry("2", "/music/b.mp3", 200)
        )

        assertEquals(entries, FavouritesCodec.decodeOrNull(FavouritesCodec.encode(entries)))
    }

    @Test
    fun `invalid or empty content decodes to null`() {
        assertNull(FavouritesCodec.decodeOrNull(null))
        assertNull(FavouritesCodec.decodeOrNull(""))
        assertNull(FavouritesCodec.decodeOrNull("not json"))
        assertNull(FavouritesCodec.decodeOrNull("""{"version":1}"""))
        assertNull(FavouritesCodec.decodeOrNull("""[]"""))
    }

    @Test
    fun `entries without id or path are skipped`() {
        val json = """{"version":1,"items":[
            {"id":"1","path":"/a","addedAt":1},
            {"id":"","path":"/b","addedAt":2},
            {"id":"3","path":"","addedAt":3},
            {"id":"4","path":"/d","addedAt":4}
        ]}"""

        val decoded = FavouritesCodec.decodeOrNull(json)

        assertEquals(listOf("1", "4"), decoded?.map { it.id })
    }

    @Test
    fun `unknown fields and versions are tolerated`() {
        val json = """{"version":99,"extra":"x","items":[{"id":"1","path":"/a","addedAt":1,"note":"y"}]}"""

        val decoded = FavouritesCodec.decodeOrNull(json)

        assertEquals(1, decoded?.size)
        assertEquals("1", decoded?.single()?.id)
    }
}

class FavouritesPersistenceTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("sparkmusic-favourites").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `missing file loads as null without creating anything`() {
        val file = File(directory, "favourites.json")

        assertNull(FavouritesPersistence.load(file))
        assertFalse(file.exists())
    }

    @Test
    fun `corrupt file is not overwritten by a read`() {
        val file = File(directory, "favourites.json")
        file.writeText("{this is not json")

        assertNull(FavouritesPersistence.load(file))
        assertEquals("{this is not json", file.readText())
    }

    @Test
    fun `saved favourites reload identically`() {
        val file = File(directory, "favourites.json")
        val entries = listOf(
            FavouriteEntry("1", "/music/a.mp3", 10),
            FavouriteEntry("2", "/music/b.mp3", 20)
        )

        assertTrue(FavouritesPersistence.save(file, entries))
        assertEquals(entries, FavouritesPersistence.load(file))
    }

    @Test
    fun `a failed write does not create a file in a missing directory`() {
        val file = File(File(directory, "missing"), "favourites.json")

        assertFalse(FavouritesPersistence.save(file, listOf(FavouriteEntry("1", "/a", 1))))
        assertFalse(file.exists())
    }
}
