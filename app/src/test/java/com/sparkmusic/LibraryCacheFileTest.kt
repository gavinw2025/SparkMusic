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

class LibraryCacheFileTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("sparkmusic-cache").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `read returns null when the index is missing or not a file`() {
        assertNull(LibraryCacheFile.read(File(directory, "missing.json")))

        val asDirectory = File(directory, "music_library.json")
        assertTrue(asDirectory.mkdir())
        assertNull(LibraryCacheFile.read(asDirectory))
    }

    @Test
    fun `write creates the index and keeps unicode content intact`() {
        val index = File(directory, "music_library.json")
        val content = """[{"id":"1","title":"光年之外","path":"/music/a.mp3"}]"""

        assertTrue(LibraryCacheFile.write(index, content))
        assertEquals(content, LibraryCacheFile.read(index))
    }

    @Test
    fun `write replaces an existing index and leaves no temporary file behind`() {
        val index = File(directory, "music_library.json")
        index.writeText("""[{"id":"1","path":"/old.mp3"}]""")

        assertTrue(LibraryCacheFile.write(index, """[{"id":"2","path":"/new.mp3"}]"""))

        assertEquals("""[{"id":"2","path":"/new.mp3"}]""", LibraryCacheFile.read(index))
        assertFalse(File(directory, "music_library.json.tmp").exists())
        assertFalse(File(directory, "music_library.json.bak").exists())
    }

    @Test
    fun `write keeps the previous index when the temporary write fails`() {
        val index = File(directory, "music_library.json")
        val previous = """[{"id":"1","path":"/a.mp3"}]"""
        index.writeText(previous)

        // A non-empty directory at the temporary path makes the write step fail.
        val blocker = File(directory, "music_library.json.tmp")
        assertTrue(blocker.mkdir())
        File(blocker, "blocker").writeText("x")

        assertFalse(LibraryCacheFile.write(index, "[]"))
        assertEquals(previous, LibraryCacheFile.read(index))
    }

    @Test
    fun `write fails without touching the index when the directory is missing`() {
        val index = File(File(directory, "missing"), "music_library.json")

        assertFalse(LibraryCacheFile.write(index, "[]"))
        assertFalse(index.exists())
    }
}
