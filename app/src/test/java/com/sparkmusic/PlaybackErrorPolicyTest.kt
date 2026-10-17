package com.sparkmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorPolicyTest {

    @Test
    fun `an unsupported or malformed container offers the removal actions`() {
        assertEquals(PlaybackErrorAction.OFFER_REMOVE, PlaybackErrorPolicy.classify(3001))
        assertEquals(PlaybackErrorAction.OFFER_REMOVE, PlaybackErrorPolicy.classify(3003))
        assertEquals(PlaybackErrorAction.OFFER_REMOVE, PlaybackErrorPolicy.classify(3004))
    }

    @Test
    fun `decoder failures offer the removal actions`() {
        listOf(4001, 4002, 4003, 4004, 4005).forEach { code ->
            assertEquals("code $code", PlaybackErrorAction.OFFER_REMOVE, PlaybackErrorPolicy.classify(code))
        }
    }

    @Test
    fun `a missing or unreadable file offers the removal actions`() {
        assertEquals(PlaybackErrorAction.OFFER_REMOVE, PlaybackErrorPolicy.classify(2005))
        assertEquals(PlaybackErrorAction.OFFER_REMOVE, PlaybackErrorPolicy.classify(2006))
        assertEquals(PlaybackErrorAction.OFFER_REMOVE, PlaybackErrorPolicy.classify(5001))
    }

    @Test
    fun `transient network failures never prompt`() {
        listOf(1003, 2000, 2001, 2002, 2003, 2004, 2007, 2008).forEach { code ->
            assertEquals("code $code", PlaybackErrorAction.IGNORE, PlaybackErrorPolicy.classify(code))
        }
    }

    @Test
    fun `audio output and drm failures never prompt`() {
        listOf(5002, 6000, 6001, 6003, 6008).forEach { code ->
            assertEquals("code $code", PlaybackErrorAction.IGNORE, PlaybackErrorPolicy.classify(code))
        }
    }

    @Test
    fun `an unknown code never prompts`() {
        assertEquals(PlaybackErrorAction.IGNORE, PlaybackErrorPolicy.classify(9999))
        assertEquals(PlaybackErrorAction.IGNORE, PlaybackErrorPolicy.classify(0))
    }
}

class PlaybackErrorPromptGuardTest {

    @Test
    fun `the first failure of a track prompts once`() {
        val guard = PlaybackErrorPromptGuard()

        assertTrue(guard.shouldPrompt("42"))
        assertFalse(guard.shouldPrompt("42"))
    }

    @Test
    fun `different tracks prompt independently`() {
        val guard = PlaybackErrorPromptGuard()

        assertTrue(guard.shouldPrompt("42"))
        assertTrue(guard.shouldPrompt("saf:com.android.externalstorage.documents:primary:Music/a.mp3"))
    }

    @Test
    fun `a missing media id never prompts`() {
        assertFalse(PlaybackErrorPromptGuard().shouldPrompt(""))
    }
}
