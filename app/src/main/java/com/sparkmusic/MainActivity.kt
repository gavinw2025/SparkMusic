package com.sparkmusic

import android.content.ComponentName
import android.net.Uri
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
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.sparkmusic.adapters.MusicAdapter
import kotlinx.coroutines.CancellationException
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

    private lateinit var tabLayout: TabLayout
    private lateinit var groupHeader: View
    private lateinit var tvGroupTitle: TextView
    private lateinit var btnLibraryBack: ImageButton
    private lateinit var btnFavouriteNowPlaying: ImageButton
    private lateinit var tvScanScope: TextView
    private lateinit var btnScanScope: Button

    private var scopeFolderList: LinearLayout? = null
    private var scopeNoFolders: View? = null
    private var category = LibraryCategory.SONGS
    private var selectedGroupKey: String? = null
    private var currentTrackId: String? = null
    private var currentTrackPath: String = ""

    private var showingNowPlaying = false

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var isScanning = false
    private var permissionRequested = false

    /** The track whose system deletion dialog is open, if any. */
    private var pendingDelete: MusicItem? = null

    /** Prevents a second failure prompt from stacking on top of an open one. */
    private var playbackFailureDialogOpen = false

    private val libraryListener: () -> Unit = {
        runOnUiThread { renderLibrary() }
    }

    private val favouritesListener: () -> Unit = {
        runOnUiThread {
            renderLibrary()
            refreshFavouriteButton()
        }
    }

    private lateinit var albumArt: ImageView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        if (!ScanScopeStore.addFolder(this, uri)) {
            Toast.makeText(this, R.string.folder_access_failed, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        updateScanScopeLabel()
        renderScopeFolders()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scanMusic()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    /** Result of the MediaStore delete confirmation; the library only changes on RESULT_OK. */
    private val deleteFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val item = pendingDelete
        pendingDelete = null
        if (item == null) return@registerForActivityResult

        if (result.resultCode == RESULT_OK) {
            onFileDeleted(item)
        } else {
            Toast.makeText(this, R.string.delete_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionRequested = savedInstanceState?.getBoolean(STATE_PERMISSION_REQUESTED) == true
        MusicLibraryManager.load(this)
        FavouritesStore.load(this)
        ScanScopeStore.load(this)

        // A system delete dialog can outlive this activity, so the track is restored by id.
        pendingDelete = savedInstanceState?.getString(STATE_PENDING_DELETE)
            ?.let { MusicLibraryManager.getMusicById(it) }

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
        btnFavouriteNowPlaying = screenNowPlaying.findViewById(R.id.btnFavouriteNowPlaying)

        tabLayout = findViewById(R.id.tabLayout)
        groupHeader = findViewById(R.id.groupHeader)
        tvGroupTitle = findViewById(R.id.tvGroupTitle)
        btnLibraryBack = findViewById(R.id.btnLibraryBack)
        tvScanScope = findViewById(R.id.tvScanScope)
        btnScanScope = findViewById(R.id.btnScanScope)

        miniPlayerContainer = findViewById(R.id.miniPlayerContainer)
        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        miniArtwork = findViewById(R.id.miniArtwork)
        miniTitle = findViewById(R.id.miniTitle)
        miniArtist = findViewById(R.id.miniArtist)
        miniPlayPause = findViewById(R.id.miniPlayPause)


        recyclerView.layoutManager = LinearLayoutManager(this)
        musicAdapter = MusicAdapter(
            onSongClick = { musicItem -> playMusic(musicItem) },
            onGroupClick = { group -> openGroup(group) },
            onFavouriteClick = { musicItem -> FavouritesStore.toggle(musicItem.id, musicItem.path) },
            isFavourite = { musicItem -> FavouritesStore.isFavourite(musicItem.id, musicItem.path) }
        )
        recyclerView.adapter = musicAdapter

        btnScan.setOnClickListener {
            checkPermissionAndScan()
        }
        btnScanScope.setOnClickListener {
            showScanScopeDialog()
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
        btnLibraryBack.setOnClickListener {
            leaveGroup()
        }
        btnFavouriteNowPlaying.setOnClickListener {
            val id = currentTrackId ?: return@setOnClickListener
            FavouritesStore.toggle(id, currentTrackPath)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showingNowPlaying -> showLibrary()
                    selectedGroupKey != null -> leaveGroup()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        setupSeekBar()
        setupMediaBrowser()
        startProgressUpdate()

        // Show the restored library first, then recover only when nothing could be restored.
        setupLibraryTabs(savedInstanceState)
        renderLibrary()
        updateScanScopeLabel()
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

    private fun setupLibraryTabs(savedInstanceState: Bundle?) {
        categoryTitles.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }

        category = LibraryCategory.values().getOrElse(
            savedInstanceState?.getInt(STATE_LIBRARY_CATEGORY) ?: 0
        ) { LibraryCategory.SONGS }
        selectedGroupKey = savedInstanceState?.getString(STATE_LIBRARY_GROUP)

        tabLayout.getTabAt(category.ordinal)?.select()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                category = LibraryCategory.values().getOrElse(tab.position) { LibraryCategory.SONGS }
                selectedGroupKey = null
                renderLibrary()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                if (selectedGroupKey != null) {
                    selectedGroupKey = null
                    renderLibrary()
                }
            }
        })
    }

    /** Renders the selected tab or its drilled-down song list; playback is untouched. */
    private fun renderLibrary() {
        val items = MusicLibraryManager.getMusicFiles()
        val group = selectedGroup(items)

        val rows = when (category) {
            LibraryCategory.SONGS ->
                items.map { MusicAdapter.Row.Song(it) }
            LibraryCategory.FAVOURITES ->
                FavouritesStore.favouriteItems(items).map { MusicAdapter.Row.Song(it) }
            LibraryCategory.ARTISTS, LibraryCategory.ALBUMS,
            LibraryCategory.GENRES, LibraryCategory.FOLDERS ->
                if (group == null) {
                    groupsFor(items).map { MusicAdapter.Row.Group(it) }
                } else {
                    LibraryGrouping.songsIn(group, items).map { MusicAdapter.Row.Song(it) }
                }
        }

        musicAdapter.submitRows(rows)

        groupHeader.visibility = if (group == null) View.GONE else View.VISIBLE
        tvGroupTitle.text = group?.title.orEmpty()
    }

    private fun selectedGroup(items: List<MusicItem>): LibraryGroup? {
        val key = selectedGroupKey ?: return null
        val group = groupsFor(items).firstOrNull { it.key == key }
        if (group == null) selectedGroupKey = null
        return group
    }

    private fun groupsFor(items: List<MusicItem>): List<LibraryGroup> = when (category) {
        LibraryCategory.ARTISTS -> LibraryGrouping.artists(items)
        LibraryCategory.ALBUMS -> LibraryGrouping.albums(items)
        LibraryCategory.GENRES -> LibraryGrouping.genres(items)
        LibraryCategory.FOLDERS -> LibraryGrouping.folders(items)
        else -> emptyList()
    }

    private fun openGroup(group: LibraryGroup) {
        selectedGroupKey = group.key
        renderLibrary()
    }

    private fun leaveGroup() {
        if (selectedGroupKey == null) return
        selectedGroupKey = null
        renderLibrary()
    }

    private fun refreshFavouriteButton() {
        val id = currentTrackId
        val favourite = FavouritesStore.isFavourite(id, currentTrackPath)

        btnFavouriteNowPlaying.isEnabled = !id.isNullOrEmpty()
        btnFavouriteNowPlaying.setImageResource(
            if (favourite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        btnFavouriteNowPlaying.contentDescription = getString(
            if (favourite) R.string.remove_from_favourites else R.string.add_to_favourites
        )
    }

    private fun updateScanScopeLabel() {
        val scope = ScanScopeStore.current(this)
        tvScanScope.text = when {
            scope.mode == ScanMode.ALL_DEVICE -> getString(R.string.scan_scope_all_device)
            scope.folders.isEmpty() -> getString(R.string.scan_scope_no_folders)
            else -> resources.getQuantityString(
                R.plurals.scan_scope_folders,
                scope.folders.size,
                scope.folders.size
            )
        }
    }

    private fun showScanScopeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_scan_scope, null)
        val modeGroup = view.findViewById<RadioGroup>(R.id.scopeModeGroup)
        val allDevice = view.findViewById<RadioButton>(R.id.rbAllDevice)
        val selectedFolders = view.findViewById<RadioButton>(R.id.rbSelectedFolders)
        val folderList = view.findViewById<LinearLayout>(R.id.folderList)
        val noFolders = view.findViewById<View>(R.id.tvNoFolders)
        val addFolder = view.findViewById<Button>(R.id.btnAddFolder)

        val scope = ScanScopeStore.current(this)
        allDevice.isChecked = scope.mode == ScanMode.ALL_DEVICE
        selectedFolders.isChecked = scope.mode == ScanMode.SELECTED_FOLDERS

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbSelectedFolders) {
                ScanMode.SELECTED_FOLDERS
            } else {
                ScanMode.ALL_DEVICE
            }
            ScanScopeStore.setMode(this, mode)
            updateScanScopeLabel()
            renderScopeFolders()
        }

        addFolder.setOnClickListener {
            folderPicker.launch(null)
        }

        scopeFolderList = folderList
        scopeNoFolders = noFolders
        renderScopeFolders()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_scope_title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener {
                scopeFolderList = null
                scopeNoFolders = null
            }
            .show()
    }

    private fun renderScopeFolders() {
        val folderList = scopeFolderList ?: return
        val noFolders = scopeNoFolders ?: return

        folderList.removeAllViews()
        val scope = ScanScopeStore.current(this)
        noFolders.visibility = if (scope.folders.isEmpty()) View.VISIBLE else View.GONE

        scope.folders.forEach { folder ->
            val row = layoutInflater.inflate(R.layout.item_scan_folder, folderList, false)
            row.findViewById<TextView>(R.id.tvFolderLabel).text = folder.label
            row.findViewById<ImageButton>(R.id.btnRemoveFolder).apply {
                contentDescription = getString(R.string.remove_folder)
                setOnClickListener {
                    ScanScopeStore.removeFolder(this@MainActivity, folder.uri)
                    updateScanScopeLabel()
                    renderScopeFolders()
                }
            }
            folderList.addView(row)
        }
    }

    private fun scanMessage(scope: ScanScope): String = when {
        scope.mode == ScanMode.ALL_DEVICE -> getString(R.string.scanning_all_device)
        scope.folders.size <= 1 -> getString(R.string.scanning_one_folder)
        else -> getString(R.string.scanning_folders, scope.folders.size)
    }

    private val categoryTitles = intArrayOf(
        R.string.category_songs,
        R.string.category_artists,
        R.string.category_albums,
        R.string.category_genres,
        R.string.category_folders,
        R.string.category_favourites
    )

    private enum class LibraryCategory { SONGS, ARTISTS, ALBUMS, GENRES, FOLDERS, FAVOURITES }

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

        handlePlaybackFailure(state)
    }

    /**
     * Offers the failure actions only for a track the library knows and only for failures that
     * mean the file itself is unusable, so network or audio-focus problems never prompt.
     */
    private fun handlePlaybackFailure(state: PlaybackStateCompat?) {
        if (state?.state != PlaybackStateCompat.STATE_ERROR) return
        if (playbackFailureDialogOpen) return

        val extras = state.extras ?: return
        val mediaId = extras.getString(MusicPlaybackService.EXTRA_FAILED_MEDIA_ID) ?: return
        val errorCode = extras.getInt(MusicPlaybackService.EXTRA_FAILED_ERROR_CODE)

        if (PlaybackErrorPolicy.classify(errorCode) != PlaybackErrorAction.OFFER_REMOVE) return
        if (!playbackErrorPromptGuard.shouldPrompt(mediaId)) return

        val item = MusicLibraryManager.getMusicById(mediaId) ?: return
        showPlaybackFailureDialog(item)
    }

    private fun showPlaybackFailureDialog(item: MusicItem) {
        if (isFinishing || isDestroyed) return

        val actions = arrayOf(
            getString(R.string.action_remove_from_library),
            getString(R.string.action_delete_file),
            getString(R.string.action_skip_track),
            getString(R.string.action_cancel)
        )

        playbackFailureDialogOpen = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_error_title)
            .setMessage(getString(R.string.playback_error_message, item.title))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> confirmRemoveFromLibrary(item)
                    1 -> confirmDeleteFile(item)
                    // Skip and Cancel leave the track and the library unchanged; the service has
                    // already moved on to the next playable track.
                }
            }
            .setOnDismissListener { playbackFailureDialogOpen = false }
            .show()
    }

    private fun confirmRemoveFromLibrary(item: MusicItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_remove_title)
            .setMessage(getString(R.string.confirm_remove_message, item.title))
            .setPositiveButton(R.string.action_remove_from_library) { _, _ ->
                removeFromLibrary(item)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDeleteFile(item: MusicItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_message, item.title))
            .setPositiveButton(R.string.action_delete_file) { _, _ -> deleteFile(item) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteFile(item: MusicItem) {
        when (val result = MusicFileDeleter.delete(this, item.id, item.path)) {
            DeleteResult.Deleted -> onFileDeleted(item)
            is DeleteResult.NeedsConfirmation -> {
                pendingDelete = item
                deleteFileLauncher.launch(
                    IntentSenderRequest.Builder(result.intentSender).build()
                )
            }
            is DeleteResult.Unavailable -> Toast.makeText(
                this,
                deleteUnavailableMessage(result.reason),
                Toast.LENGTH_LONG
            ).show()
            is DeleteResult.Failed -> Toast.makeText(
                this,
                R.string.delete_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun deleteUnavailableMessage(reason: DeleteUnavailableReason): Int = when (reason) {
        DeleteUnavailableReason.NO_WRITE_PERMISSION -> R.string.delete_denied_write_access
        DeleteUnavailableReason.UNSUPPORTED_ANDROID_VERSION -> R.string.delete_unsupported_version
        DeleteUnavailableReason.UNSUPPORTED_SOURCE -> R.string.delete_unsupported_source
    }

    /** The file is gone, so its library entry goes too; favourites stay untouched. */
    private fun onFileDeleted(item: MusicItem) {
        removeFromLibrary(item, R.string.track_deleted)
    }

    private fun removeFromLibrary(item: MusicItem, successMessage: Int = R.string.track_removed) {
        lifecycleScope.launch {
            val removed = try {
                MusicLibraryManager.removeTrack(this@MainActivity, item.id, item.path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "Could not remove ${item.id}", e)
                false
            }

            Toast.makeText(
                this@MainActivity,
                if (removed) successMessage else R.string.track_remove_failed,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateMetadata(metadata: MediaMetadataCompat?) {
        if (metadata != null) {
            val title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Unknown"
            val artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
            val artBitmap = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

            currentTrackId = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
            currentTrackPath = currentTrackId
                ?.let { id -> MusicLibraryManager.getMusicById(id)?.path }
                .orEmpty()

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
            currentTrackId = null
            currentTrackPath = ""

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
        refreshFavouriteButton()
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

        val scope = ScanScopeStore.current(this)
        when (val validation = ScanScopePolicy.validate(scope) {
            ScanScopeStore.hasReadPermission(this, it)
        }) {
            is ScanScopeValidation.Invalid -> {
                val message = when (validation.problem) {
                    ScanScopeProblem.NO_FOLDERS -> R.string.error_no_folders
                    ScanScopeProblem.FOLDER_UNAVAILABLE -> R.string.error_folder_unavailable
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return
            }
            ScanScopeValidation.Valid -> Unit
        }

        isScanning = true
        btnScan.isEnabled = false
        Toast.makeText(this, scanMessage(scope), Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Starting music scan...")

        lifecycleScope.launch {
            try {
                val musicFiles = MusicLibraryManager.scan(this@MainActivity)
                Log.d("MainActivity", "Scan complete. Found ${musicFiles.size} files")
                renderLibrary()
                if (musicFiles.isEmpty() && MusicLibraryManager.getMusicFiles().isNotEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.scan_empty_kept,
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Found ${musicFiles.size} songs",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: ScanScopeException) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("MainActivity", "Music scan failed", e)
                Toast.makeText(this@MainActivity, R.string.scan_failed, Toast.LENGTH_LONG).show()
            } finally {
                btnScan.isEnabled = true
                isScanning = false
            }
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
        FavouritesStore.addListener(favouritesListener)
        mediaBrowser?.connect()
    }

    override fun onStop() {
        super.onStop()
        MusicLibraryManager.removeListener(libraryListener)
        FavouritesStore.removeListener(favouritesListener)
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
        outState.putInt(STATE_LIBRARY_CATEGORY, category.ordinal)
        outState.putString(STATE_LIBRARY_GROUP, selectedGroupKey)
        outState.putString(STATE_PENDING_DELETE, pendingDelete?.id)
    }

    private companion object {
        private const val STATE_PERMISSION_REQUESTED = "permission_requested"
        private const val STATE_SHOWING_NOW_PLAYING = "showing_now_playing"
        private const val STATE_LIBRARY_CATEGORY = "library_category"
        private const val STATE_LIBRARY_GROUP = "library_group"
        private const val STATE_PENDING_DELETE = "pending_delete"

        /** Process-wide, so a recreated activity cannot prompt twice for the same track. */
        private val playbackErrorPromptGuard = PlaybackErrorPromptGuard()
    }
}