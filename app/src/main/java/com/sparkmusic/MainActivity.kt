package com.sparkmusic

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sparkmusic.adapters.MusicAdapter
import kotlinx.coroutines.launch
import android.widget.ImageView


class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var musicAdapter: MusicAdapter
    private lateinit var btnScan: Button
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var tvNowPlaying: TextView
    private lateinit var tvArtist: TextView

    private lateinit var screenLibrary: View
    private lateinit var screenNowPlaying: View
    private lateinit var miniPlayerContainer: View
    private lateinit var miniPlayerCard: View
    private lateinit var miniArtwork: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayPause: ImageButton
    private lateinit var btnBackNowPlaying: ImageButton

    private var showingNowPlaying = false

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var isScanning = false
    private var permissionRequested = false

    private val libraryListener: () -> Unit = {
        runOnUiThread { musicAdapter.submitList(MusicLibraryManager.getMusicFiles()) }
    }

    private lateinit var albumArt: ImageView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scanMusic()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionRequested = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUESTED) == true
        MusicLibraryManager.load(this)

        screenLibrary = findViewById(R.id.screenLibrary)
        screenNowPlaying = findViewById(R.id.screenNowPlaying)

        recyclerView = findViewById(R.id.recyclerView)
        btnScan = findViewById(R.id.btnScan)

        // Scoped to the Now Playing screen: item_music.xml also defines a tvArtist.
        btnPlayPause = screenNowPlaying.findViewById(R.id.btnPlayPause)
        btnNext = screenNowPlaying.findViewById(R.id.btnNext)
        btnPrevious = screenNowPlaying.findViewById(R.id.btnPrevious)
        seekBar = screenNowPlaying.findViewById(R.id.seekBar)
        tvCurrentTime = screenNowPlaying.findViewById(R.id.tvCurrentTime)
        tvTotalTime = screenNowPlaying.findViewById(R.id.tvTotalTime)
        tvNowPlaying = screenNowPlaying.findViewById(R.id.tvNowPlaying)
        tvArtist = screenNowPlaying.findViewById(R.id.tvArtist)
        albumArt = screenNowPlaying.findViewById(R.id.albumArt)
        btnBackNowPlaying = screenNowPlaying.findViewById(R.id.btnBackNowPlaying)

        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        miniArtwork = findViewById(R.id.miniArtwork)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniPlayPause = findViewById(R.id.miniPlayPause)


        recyclerView.layoutManager = LinearLayoutManager(this)
        musicAdapter = MusicAdapter { musicItem ->
            playMusic(musicItem)
        }
        recyclerView.adapter = musicAdapter

        btnScan.setOnClickListener {
            checkPermissionAndScan()
        }

        btnPlayPause.setOnClickListener {
            togglePlayback()
        }

        btnNext.setOnClickListener {
            mediaController?.transportControls?.skipToNext()
        }

        btnPrevious.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious()
        }

        // The mini-player only navigates; it never starts playback itself.
        miniPlayerCard.setOnClickListener {
            showNowPlaying()
        }
        miniPlayPause.setOnClickListener {
            togglePlayback()
        }
        btnBackNowPlaying.setOnClickListener {
            showLibrary()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (showingNowPlaying) {
                    showLibrary()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setupSeekBar()
        setupMediaBrowser()
        startProgressUpdate()

        // Show the restored library first, then recover only when nothing could be restored.
        musicAdapter.submitList(MusicLibraryManager.getMusicFiles())
        recoverLibraryIfNeeded()
        applyScreen(savedInstanceState?.getBoolean(STATE_SHOWING_NOW_PLAYING) == true)
    }

    private fun showLibrary() = applyScreen(false)

    private fun showNowPlaying() = applyScreen(true)

    /** Switches the visible screen only; playback and its controller stay untouched. */
    private fun applyScreen(nowPlaying: Boolean) {
        showingNowPlaying = nowPlaying
        screenLibrary.visibility = if (nowPlaying) View.GONE else View.VISIBLE
        screenNowPlaying.visibility = if (nowPlaying) View.VISIBLE else View.GONE
        supportActionBar?.title = getString(
            if (nowPlaying) R.string.now_playing else R.string.app_name
        )
    }

    /**
     * Restores automatically after a data clear, a permission reset or an unreadable index,
     * without prompting or scanning while a valid library has already been restored.
     */
    private fun recoverLibraryIfNeeded() {
        val restoredCount = MusicLibraryManager.getMusicFiles().size
        val hasPermission = MusicLibraryManager.hasAudioPermission(this)

        if (LibraryCachePolicy.shouldScanOnStartup(restoredCount, hasPermission, isScanning)) {
            scanMusic()
            return
        }

        val alreadyAsked = permissionRequested ||
                shouldShowRequestPermissionRationale(MusicLibraryManager.audioPermission())
        if (LibraryCachePolicy.shouldRequestPermissionOnStartup(
                restoredCount,
                hasPermission,
                alreadyAsked
            )
        ) {
            permissionRequested = true
            requestPermissionLauncher.launch(MusicLibraryManager.audioPermission())
        }
    }

    private fun setupSeekBar() {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.let {
                    mediaController?.transportControls?.seekTo(it.progress.toLong())
                }
            }
        })
    }

    private fun setupMediaBrowser() {
        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicPlaybackService::class.java),
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    mediaBrowser?.sessionToken?.let { token ->
                        mediaController = MediaControllerCompat(this@MainActivity, token)
                        MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
                        mediaController?.registerCallback(controllerCallback)
                        updatePlaybackState(mediaController?.playbackState)
                        updateMetadata(mediaController?.metadata)
                    }
                }
            },
            null
        )
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            updateMetadata(metadata)
        }
    }

    private fun updatePlaybackState(state: PlaybackStateCompat?) {
        val isPlaying = state?.state == PlaybackStateCompat.STATE_PLAYING
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
        val description = getString(if (isPlaying) R.string.pause else R.string.play)

        btnPlayPause.setImageResource(iconRes)
        btnPlayPause.contentDescription = description
        miniPlayPause.setImageResource(iconRes)
        miniPlayPause.contentDescription = description

        if (!isUserSeeking) {
            seekBar.progress = state?.position?.toInt() ?: 0
        }
    }

    private fun updateMetadata(metadata: MediaMetadataCompat?) {
        if (metadata != null) {
            val title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Unknown"
            val artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            val artBitmap = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

            tvNowPlaying.text = title
            tvArtist.text = artist
            seekBar.max = duration.toInt()
            tvTotalTime.text = formatTime(duration)

            if (artBitmap != null) {
                albumArt.setImageBitmap(artBitmap)
                miniArtwork.setImageBitmap(artBitmap)
            } else {
                albumArt.setImageResource(R.drawable.music_placeholder)
                miniArtwork.setImageResource(R.drawable.ic_music_placeholder)
            }

            miniTitle.text = title
            miniArtist.text = artist
            miniPlayerContainer.visibility = View.VISIBLE
        } else {
            tvNowPlaying.text = "No song playing"
            tvArtist.text = ""
            seekBar.max = 100
            seekBar.progress = 0
            tvCurrentTime.text = "0:00"
            tvTotalTime.text = "0:00"

            albumArt.setImageResource(R.drawable.music_placeholder)

            miniTitle.text = ""
            miniArtist.text = ""
            miniArtwork.setImageResource(R.drawable.ic_music_placeholder)
            miniPlayerContainer.visibility = View.GONE
        }
    }


    private fun startProgressUpdate() {
        val updateTask = object : Runnable {
            override fun run() {
                if (!isUserSeeking) {
                    val state = mediaController?.playbackState
                    if (state?.state == PlaybackStateCompat.STATE_PLAYING) {
                        val position = state.position
                        seekBar.progress = position.toInt()
                        tvCurrentTime.text = formatTime(position)
                    }
                }
                handler.postDelayed(this, 1000) // Update every second
            }
        }
        handler.post(updateTask)
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).toInt()
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", minutes, secs)
    }

    private fun checkPermissionAndScan() {
        if (MusicLibraryManager.hasAudioPermission(this)) {
            scanMusic()
        } else {
            requestPermissionLauncher.launch(MusicLibraryManager.audioPermission())
        }
    }

    private fun scanMusic() {
        if (isScanning) return

        isScanning = true
        btnScan.isEnabled = false
        Toast.makeText(this, "Scanning music files…", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Starting music scan...")

        lifecycleScope.launch {
            val musicFiles = try {
                MusicLibraryManager.scan(this@MainActivity)
            } catch (e: Exception) {
                Log.e("MainActivity", "Music scan failed", e)
                emptyList()
            }

            Log.d("MainActivity", "Scan complete. Found ${musicFiles.size} files")
            musicAdapter.submitList(musicFiles)
            btnScan.isEnabled = true
            isScanning = false
            Toast.makeText(this@MainActivity, "Found ${musicFiles.size} songs", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun playMusic(musicItem: MusicItem) {
        mediaController?.transportControls?.playFromMediaId(musicItem.id, null)
    }

    private fun togglePlayback() {
        val state = mediaController?.playbackState
        when (state?.state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                mediaController?.transportControls?.pause()
            }
            else -> {
                mediaController?.transportControls?.play()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MusicLibraryManager.addListener(libraryListener)
        mediaBrowser?.connect()
    }

    override fun onStop() {
        super.onStop()
        MusicLibraryManager.removeListener(libraryListener)
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser?.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PERMISSION_REQUESTED, permissionRequested)
        outState.putBoolean(STATE_SHOWING_NOW_PLAYING, showingNowPlaying)
    }

    private companion object {
        private const val STATE_PERMISSION_REQUESTED = "permission_requested"
        private const val STATE_SHOWING_NOW_PLAYING = "showing_now_playing"
    }
}