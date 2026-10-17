package com.sparkmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanScopeCodecTest {

    @Test
    fun `encode and decode round trip`() {
        val scope = ScanScope(
            ScanMode.SELECTED_FOLDERS,
            listOf(ScanFolder("content://tree/a", "Music"), ScanFolder("content://tree/b", "Podcasts"))
        )

        assertEquals(scope, ScanScopeCodec.decodeOrNull(ScanScopeCodec.encode(scope)))
    }

    @Test
    fun `missing or invalid content decodes to null`() {
        assertNull(ScanScopeCodec.decodeOrNull(null))
        assertNull(ScanScopeCodec.decodeOrNull(""))
        assertNull(ScanScopeCodec.decodeOrNull("not json"))
    }

    @Test
    fun `an unknown mode falls back to all device`() {
        val json = """{"version":1,"mode":"SOMETHING_ELSE","folders":[]}"""

        assertEquals(ScanMode.ALL_DEVICE, ScanScopeCodec.decodeOrNull(json)?.mode)
    }

    @Test
    fun `folders without a uri are skipped and duplicates removed`() {
        val json = """{"version":1,"mode":"SELECTED_FOLDERS","folders":[
            {"uri":"content://tree/a","label":"Music"},
            {"uri":"","label":"Dropped"},
            {"uri":"content://tree/a","label":"Duplicate"}
        ]}"""

        val scope = ScanScopeCodec.decodeOrNull(json)

        assertEquals(1, scope?.folders?.size)
        assertEquals("content://tree/a", scope?.folders?.single()?.uri)
        assertEquals("Music", scope?.folders?.single()?.label)
    }

    @Test
    fun `a blank label falls back to the uri`() {
        val json = """{"version":1,"mode":"SELECTED_FOLDERS","folders":[{"uri":"content://tree/a","label":""}]}"""

        assertEquals("content://tree/a", ScanScopeCodec.decodeOrNull(json)?.folders?.single()?.label)
    }

    @Test
    fun `write access survives a round trip`() {
        val scope = ScanScope(
            ScanMode.SELECTED_FOLDERS,
            listOf(ScanFolder("content://tree/a", "Music", writeAccess = true))
        )

        assertEquals(scope, ScanScopeCodec.decodeOrNull(ScanScopeCodec.encode(scope)))
    }

    @Test
    fun `a folder stored before write access existed is read only`() {
        val json = """{"version":1,"mode":"SELECTED_FOLDERS","folders":[{"uri":"content://tree/a","label":"Music"}]}"""

        assertEquals(false, ScanScopeCodec.decodeOrNull(json)?.folders?.single()?.writeAccess)
    }
}

class ScanScopePolicyTest {

    private val allowed: (String) -> Boolean = { true }
    private val denied: (String) -> Boolean = { false }

    @Test
    fun `all device is always valid`() {
        assertEquals(
            ScanScopeValidation.Valid,
            ScanScopePolicy.validate(ScanScope(ScanMode.ALL_DEVICE), denied)
        )
    }

    @Test
    fun `folder mode without folders is invalid`() {
        val result = ScanScopePolicy.validate(ScanScope(ScanMode.SELECTED_FOLDERS), allowed)

        assertEquals(ScanScopeValidation.Invalid(ScanScopeProblem.NO_FOLDERS), result)
    }

    @Test
    fun `a folder without persisted access is invalid`() {
        val scope = ScanScope(
            ScanMode.SELECTED_FOLDERS,
            listOf(ScanFolder("content://tree/a", "Music"))
        )

        val result = ScanScopePolicy.validate(scope, denied)

        assertEquals(ScanScopeValidation.Invalid(ScanScopeProblem.FOLDER_UNAVAILABLE), result)
    }

    @Test
    fun `permitted folders are valid`() {
        val scope = ScanScope(
            ScanMode.SELECTED_FOLDERS,
            listOf(ScanFolder("content://tree/a", "Music"), ScanFolder("content://tree/b", "More"))
        )

        assertEquals(ScanScopeValidation.Valid, ScanScopePolicy.validate(scope, allowed))
    }
}

class ScanResultAccumulatorTest {

    private fun item(id: String) =
        MusicItem(id, "Song $id", "Artist", "Album", 1000, "/music/$id.mp3")

    @Test
    fun `repeated documents are ignored`() {
        val accumulator = ScanResultAccumulator()

        assertTrue(accumulator.add("provider|1", item("1")))
        assertFalse(accumulator.add("provider|1", item("1")))

        assertEquals(1, accumulator.items().size)
    }

    @Test
    fun `overlapping folder trees yield one track per document`() {
        val accumulator = ScanResultAccumulator()

        // The same document reached through two selected trees keeps one key.
        accumulator.add("externalstorage|primary:Music/a.mp3", item("a"))
        accumulator.add("externalstorage|primary:Music/a.mp3", item("a"))

        assertEquals(listOf("a"), accumulator.items().map { it.id })
    }

    @Test
    fun `distinct documents are kept in discovery order`() {
        val accumulator = ScanResultAccumulator()

        accumulator.add("provider|1", item("1"))
        accumulator.add("provider|2", item("2"))

        assertEquals(listOf("1", "2"), accumulator.items().map { it.id })
    }
}
