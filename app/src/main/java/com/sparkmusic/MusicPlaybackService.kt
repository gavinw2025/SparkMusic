package com.sparkmusic

import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import android.graphics.Bitmap
import android.view.KeyEvent
import kotlinx.coroutines.*


class MusicPlaybackService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var stateBuilder: PlaybackStateCompat.Builder
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var notificationManager: MediaNotificationManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var musicQueue: List<MusicItem> = emptyList()
    private var currentMediaId: String? = null
    private var artworkJob: Job? = null
    private val failedMediaIds = mutableSetOf<String>()

    /**
     * Presentation-only artwork fallback, decoded once per service instance. It never replaces
     * genuine artwork: the asynchronous lookup overwrites both keys when it finds a bitmap.
     */
    private val fallbackArtwork: Bitmap? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BitmapFactory.decodeResource(
            resources,
            R.drawable.sparkmusic_artwork_fallback,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inScaled = false
            }
        )
    }

    companion object {
        private const val TAG = "SparkMusicService"
        private const val ROOT_ID = "root_id"
        private const val POSITION_UPDATE_INTERVAL_MS = 500L

        /** Album art is published twice per track, so it is kept small to stay within Binder. */
        private const val ARTWORK_MAX_SIZE = 320
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")

        // Restores the cached library, so the car can browse without the phone UI being opened.
        MusicLibraryManager.load(this)
        refreshQueue()
        Log.d(TAG, "Music queue size: ${musicQueue.size}")

        // Initialize ExoPlayer with proper AudioAttributes
        val exoAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(exoAudioAttributes, true)
            setHandleAudioBecomingNoisy(true)
            addListener(playerListener)
        }

        // Create MediaSession
        val sessionIntent = packageManager?.getLaunchIntentForPackage(packageName)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this, 0, sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSessionCompat(this, TAG).apply {
            setSessionActivity(sessionActivityPendingIntent)
            setCallback(mediaSessionCallback)

            // CRITICAL: Set flags to handle media buttons
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            isActive = true
        }

        sessionToken = mediaSession.sessionToken

        stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO  // CRITICAL for seek bar
            )
        publishPlaybackState()

        notificationManager = MediaNotificationManager(this, mediaSession)

        startPositionUpdates()

        Log.d(TAG, "Service initialized successfully")
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {

        override fun onPlay() {
            Log.d(TAG, "onPlay called")
            refreshQueue()
            when {
                exoPlayer.currentMediaItem != null -> exoPlayer.play()
                musicQueue.isNotEmpty() -> playIndex(0)
                else -> Log.w(TAG, "onPlay ignored, the library is empty")
            }
            publishPlaybackState()
            notificationManager.showNotification()
        }

        override fun onPause() {
            Log.d(TAG, "onPause called")
            exoPlayer.pause()
            publishPlaybackState()
            notificationManager.showNotification()
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "onPlayFromMediaId: $mediaId")
            val musicItem = mediaId?.let { MusicLibraryManager.getMusicById(it) }
            if (musicItem == null) {
                Log.w(TAG, "Unknown media id: $mediaId")
                return
            }

            failedMediaIds.clear()
            refreshQueue()
            val index = musicQueue.indexOfFirst { it.id == musicItem.id }
            if (index == -1) {
                // The library changed after it was browsed, so play the item directly.
                playTrack(musicItem)
            } else {
                playIndex(index)
            }
        }

        override fun onSkipToNext() {
            Log.d(TAG, "onSkipToNext called")
            refreshQueue()
            if (musicQueue.isEmpty()) return

            val current = currentIndex()
            playIndex(if (current == -1) 0 else (current + 1) % musicQueue.size)
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious called")
            refreshQueue()
            if (musicQueue.isEmpty()) return

            val current = currentIndex()
            playIndex(
                when {
                    current == -1 -> 0
                    current - 1 < 0 -> musicQueue.size - 1
                    else -> current - 1
                }
            )
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "onSeekTo: $pos")
            exoPlayer.seekTo(pos)
            publishPlaybackState()
        }

        override fun onStop() {
            Log.d(TAG, "onStop called")
            stopPlayback()
        }
    }

    private fun playIndex(index: Int) {
        if (index !in musicQueue.indices) return
        playTrack(musicQueue[index])
    }

    private fun playTrack(musicItem: MusicItem) {
        Log.d(TAG, "Playing: ${musicItem.title}")
        currentMediaId = musicItem.id
        startServiceForPlayback()

        val mediaItem = MediaItem.fromUri(Uri.parse(musicItem.path))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, musicItem.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, musicItem.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, musicItem.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, musicItem.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, musicItem.duration)

        // Presentation fallback for Android Auto. Genuine artwork overwrites both keys below.
        fallbackArtwork?.let { fallback ->
            metadataBuilder
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, fallback)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, fallback)
        }

        val baseMetadata = metadataBuilder.build()

        mediaSession.setMetadata(baseMetadata) // Show basic info immediately

        // Cancelling the previous lookup keeps artwork of the previous track from showing up.
        artworkJob?.cancel()
        artworkJob = serviceScope.launch {
            val artwork: Bitmap? = loadArtwork(musicItem)
            Log.d(TAG, "Fetched artwork for ${musicItem.title}: ${artwork != null}")

            if (artwork != null && currentMediaId == musicItem.id) {
                val updatedMetadata = MediaMetadataCompat.Builder(baseMetadata)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
                    .build()

                mediaSession.setMetadata(updatedMetadata)
                notificationManager.refreshIfForeground()
            }
        }

        publishPlaybackState()
        notificationManager.showNotification()
    }

    /** Prefers the artwork embedded in the file and falls back to the MusicBrainz cover art. */
    private suspend fun loadArtwork(musicItem: MusicItem): Bitmap? =
        loadLocalArtwork(musicItem)
            ?: OnlineMetadataFetcher.fetch(musicItem.title, musicItem.artist)?.artwork

    private suspend fun loadLocalArtwork(musicItem: MusicItem): Bitmap? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(musicItem.path)
                retriever.embeddedPicture?.let { decodeArtwork(it) }
            } catch (e: Exception) {
                Log.d(TAG, "No embedded artwork for ${musicItem.title}")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

    /**
     * Embedded artwork is often far too large for session metadata, which is sent over Binder,
     * so it is decoded with a sample size and scaled to [ARTWORK_MAX_SIZE].
     */
    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= ARTWORK_MAX_SIZE) return decoded

        val scale = ARTWORK_MAX_SIZE.toFloat() / largest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt(),
            (decoded.height * scale).toInt(),
            true
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > ARTWORK_MAX_SIZE || height / sampleSize > ARTWORK_MAX_SIZE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Track ended")
                    onTrackEnded()
                }
                Player.STATE_BUFFERING -> Log.d(TAG, "Player buffering")
                Player.STATE_READY -> Log.d(TAG, "Player ready")
            }
            publishPlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "Is playing changed: $isPlaying")
            publishPlaybackState()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error for $currentMediaId", error)
            onTrackFailed(error)
        }
    }

    /** The end of the queue stops playback instead of looping back to the first track. */
    private fun onTrackEnded() {
        val next = currentIndex() + 1
        if (next in musicQueue.indices) {
            playIndex(next)
        } else {
            Log.d(TAG, "Reached the end of the queue")
            stopPlayback()
        }
    }

    /** Reports the failure and moves on, instead of stalling on an unplayable file. */
    private fun onTrackFailed(error: PlaybackException) {
        currentMediaId?.let { failedMediaIds.add(it) }
        publishPlaybackState(PlaybackStateCompat.STATE_ERROR, error.message ?: "Playback error")

        val next = nextPlayableIndex()
        if (next == -1) {
            stopPlayback()
        } else {
            playIndex(next)
        }
    }

    private fun nextPlayableIndex(): Int {
        if (musicQueue.isEmpty()) return -1

        val start = maxOf(currentIndex(), 0)
        for (offset in 1..musicQueue.size) {
            val index = (start + offset) % musicQueue.size
            if (musicQueue[index].id !in failedMediaIds) return index
        }
        return -1
    }

    private fun currentIndex(): Int {
        val id = currentMediaId ?: return -1
        return musicQueue.indexOfFirst { it.id == id }
    }

    /** Re-reads the shared library. Duplicates are identified by media id, not by title. */
    private fun refreshQueue() {
        musicQueue = MusicLibraryManager.getMusicFiles().distinctBy { it.id }

        if (currentMediaId != null && currentIndex() == -1) {
            Log.d(TAG, "The current track is no longer in the library")
        }
    }

    private fun stopPlayback() {
        exoPlayer.stop()
        publishPlaybackState(PlaybackStateCompat.STATE_STOPPED, null)
        notificationManager.hideNotification()
        stopSelf()
    }

    /** Derives the playback state from the player, so every client sees the same value. */
    private fun publishPlaybackState() {
        val state = when (exoPlayer.playbackState) {
            Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            Player.STATE_READY -> if (exoPlayer.isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> if (exoPlayer.currentMediaItem == null) {
                PlaybackStateCompat.STATE_NONE
            } else {
                PlaybackStateCompat.STATE_STOPPED
            }
        }
        publishPlaybackState(state, null)
    }

    private fun publishPlaybackState(state: Int, errorMessage: CharSequence?) {
        val hasMedia = exoPlayer.currentMediaItem != null
        val position = if (hasMedia) exoPlayer.currentPosition else 0L
        // PlaybackStateCompat requires a speed of 0 whenever playback is not progressing,
        // otherwise clients extrapolate a position that keeps moving while paused.
        val speed = if (state == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f

        stateBuilder
            .setState(state, position, speed)
            .setBufferedPosition(if (hasMedia) exoPlayer.bufferedPosition else 0L)
            .setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, errorMessage)
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    /** Keeps the published position moving while playing, for the phone UI and the car. */
    private fun startPositionUpdates() {
        serviceScope.launch {
            while (isActive) {
                delay(POSITION_UPDATE_INTERVAL_MS)
                if (exoPlayer.isPlaying) {
                    publishPlaybackState()
                }
            }
        }
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d(TAG, "onGetRoot called by: $clientPackageName")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren called for: $parentId")

        if (parentId != ROOT_ID) {
            result.sendResult(mutableListOf())
            return
        }

        refreshQueue()
        if (musicQueue.isNotEmpty() || !MusicLibraryManager.hasAudioPermission(this)) {
            result.sendResult(buildMediaItems())
            return
        }

        // Nothing known yet and the audio permission is already granted, so scan on demand
        // instead of presenting the car with an empty library.
        Log.d(TAG, "Library is empty, scanning on demand")
        result.detach()
        serviceScope.launch {
            try {
                MusicLibraryManager.scan(this@MusicPlaybackService)
            } catch (e: Exception) {
                Log.e(TAG, "On-demand library scan failed", e)
            } finally {
                withContext(NonCancellable) {
                    refreshQueue()
                    result.sendResult(buildMediaItems())
                }
            }
        }
    }

    private fun buildMediaItems(): MutableList<MediaBrowserCompat.MediaItem> {
        val mediaItems = musicQueue.map { musicItem ->
            val description = MediaDescriptionCompat.Builder()
                .setMediaId(musicItem.id)
                .setTitle(musicItem.title)
                .setSubtitle(musicItem.artist)
                .setDescription(musicItem.album)
                .build()

            MediaBrowserCompat.MediaItem(
                description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        }.toMutableList()

        Log.d(TAG, "Sending ${mediaItems.size} items")
        return mediaItems
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            // MediaButtonReceiver delivers media buttons through startForegroundService(), so the
            // foreground notification has to be posted before anything else happens.
            notificationManager.ensureForeground()

            val unhandledKeyEvent = MediaButtonReceiver.handleIntent(mediaSession, intent)
            if (unhandledKeyEvent != null) {
                dispatchMediaButtonKey(unhandledKeyEvent)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun dispatchMediaButtonKey(keyEvent: KeyEvent) {
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return

        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> mediaSessionCallback.onPlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> mediaSessionCallback.onPause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (exoPlayer.isPlaying) {
                    mediaSessionCallback.onPause()
                } else {
                    mediaSessionCallback.onPlay()
                }
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> mediaSessionCallback.onSkipToNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> mediaSessionCallback.onSkipToPrevious()
            KeyEvent.KEYCODE_MEDIA_STOP -> mediaSessionCallback.onStop()
            else -> Log.d(TAG, "Ignoring media button ${keyEvent.keyCode}")
        }
    }

    /** Starts the service explicitly, so playback survives the phone UI being closed. */
    private fun startServiceForPlayback() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, MusicPlaybackService::class.java))
        } catch (e: Exception) {
            // Android 12+ can reject background starts; the bound service keeps playing regardless.
            Log.w(TAG, "Could not start the service explicitly", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!exoPlayer.isPlaying) {
            notificationManager.hideNotification()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy called")
        artworkJob?.cancel()
        serviceScope.cancel()
        mediaSession.isActive = false
        exoPlayer.release()
        mediaSession.release()
    }
}