package com.sparkmusic

/** What the UI should do about a reported playback failure. */
enum class PlaybackErrorAction {
    /** A network, timeout, DRM or unmapped failure: stay silent and let the user retry. */
    IGNORE,

    /** The file itself cannot be opened or decoded: offer remove / delete / skip. */
    OFFER_REMOVE
}

/**
 * Classifies ExoPlayer failures so only a genuinely unplayable track reaches the user.
 *
 * Codes are the `PlaybackException.ERROR_CODE_*` values kept as integers, so the rules can be
 * verified by JVM tests without the ExoPlayer runtime.
 */
internal object PlaybackErrorPolicy {

    private val UNPLAYABLE_CODES: Set<Int> = setOf(
        2005, // IO_FILE_NOT_FOUND - the file was moved or deleted
        2006, // IO_NO_PERMISSION - the source is no longer readable
        3001, // PARSING_CONTAINER_MALFORMED
        3002, // PARSING_MANIFEST_MALFORMED
        3003, // PARSING_CONTAINER_UNSUPPORTED
        3004, // PARSING_MANIFEST_UNSUPPORTED
        4001, // DECODER_INIT_FAILED
        4002, // DECODER_QUERY_FAILED
        4003, // DECODING_FAILED
        4004, // DECODING_FORMAT_EXCEEDS_CAPABILITIES
        4005, // DECODING_FORMAT_UNSUPPORTED
        5001  // AUDIO_TRACK_INIT_FAILED
    )

    fun classify(errorCode: Int): PlaybackErrorAction =
        if (errorCode in UNPLAYABLE_CODES) PlaybackErrorAction.OFFER_REMOVE
        else PlaybackErrorAction.IGNORE
}

/**
 * Remembers which tracks already produced a prompt, so a queue that keeps retrying one bad file
 * cannot nag the user and a dismissed prompt is not shown again in the same process.
 */
internal class PlaybackErrorPromptGuard {

    private val prompted = LinkedHashSet<String>()

    /** @return true the first time this media id asks for a prompt. */
    fun shouldPrompt(mediaId: String): Boolean {
        if (mediaId.isEmpty()) return false
        return prompted.add(mediaId)
    }
}
