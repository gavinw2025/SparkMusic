# SparkMusic 1.1

An Android music player for **local** audio playback: it scans music stored on the device,
keeps the library available across restarts, and plays it through a media session with
lock-screen, notification and Android Auto support.

There is no account, no cloud library and no streaming service. SparkMusic reads the audio
files that are already on the phone.

| | |
|---|---|
| Package | `com.sparkmusic` |
| Version | `versionName 1.0`, `versionCode 1` |
| Min / target SDK | 24 (Android 7.0) / 34 (Android 14) |
| Language | Kotlin |

---

## Features

### Library

- **Six library sections** with scrollable tabs and group counts:
  Songs, Artists, Albums, Genres, Folders and Favourites.
- **Drill-down navigation** into an artist, album, genre or folder to list its songs.
- **Genre support** through `MediaStore.Audio.Genres` and `Genres.Members`. A track can appear
  in more than one genre, but never twice in the same list.
- **Artist grouping** uses the `ARTIST` tag; `<unknown>` is shown as `Unknown Artist`.
- **Artwork fallback**: a bundled placeholder image is published instead of a blank cover, and
  genuine embedded artwork replaces it when available.

### Interface

- Library, Now Playing and mini-player screens built on Material Components, with a scrollable
  tab bar and a consistent dark/light theme.
- Custom SparkMusic launcher icon declared as an adaptive icon
  (`mipmap-anydpi-v26/ic_launcher.xml`) with dedicated background, foreground and monochrome
  layers.

### Scanning

- **All device music** — the standard `MediaStore` audio query.
- **Selected folders** — pick one or more folder trees with Android's Storage Access Framework
  (`ACTION_OPEN_DOCUMENT_TREE`). Nested folders are traversed recursively, and overlapping
  selections cannot produce duplicate entries.
- The selected scope, the folder list and the folder permissions are **persisted**, so they
  survive app restarts.
- Scanning runs **off the UI thread**.

### Favourites

- Heart toggle on every library row and in the Now Playing screen.
- Stored separately from the library cache, so rescanning never deletes them.
- Most recently added first; a favourite whose file is temporarily unavailable stays saved but
  is hidden until the track is available again.

### Playback

- Local playback through **ExoPlayer**, controlled from a `MediaSession` with a foreground
  service notification (play/pause, previous, next) and background playback.
- Mini-player and a dedicated Now Playing screen with artwork, seek bar and transport controls.
- **Playback error handling**: when a track genuinely cannot be played, the app offers
  *Remove from library*, *Delete file from device*, *Skip* or *Cancel* — with a confirmation
  before anything changes.

### Android Auto

- The playback service is a `MediaBrowserService`, so the car can browse the library and read
  Now Playing metadata through the media session.
- The library is restored from the persisted cache on a cold start, so the car is not presented
  with an empty list before the phone app has been opened.

### Verified vs. unverified functionality

The statements below reflect what has actually been exercised. Anything listed as unverified is
implemented but has **not** been confirmed end-to-end on a physical device.

| Area | Status |
|---|---|
| Debug build, 84 JVM unit tests | Verified |
| Install and launch on a physical device (Xiaomi 2312CRAD3C, Android 16) | Verified |
| Startup recovery with an empty cache, automatic all-device scan (29 tracks) | Verified |
| Persisted library cache format (`music_library.json`) | Verified |
| Library UI rendering (tabs, rows, hearts, scan-scope row) | Verified |
| Playback, seek, notification controls, artwork | Not verified on device* |
| Favourite toggles, dialog actions (remove / delete / skip / cancel) | Not verified on device* |
| Selected-folder scanning through the SAF picker | Not verified on device* |
| Android Auto browsing and Now Playing in a car or head unit | Not verified (no head unit available) |

\* These flows require touch interaction, and the available test device blocks simulated input
(`INJECT_EVENTS` is denied to the `shell` user). They are covered by unit tests where the logic
is pure, but they still need a manual pass on a device.

---

## Technology

| Layer | Component |
|---|---|
| Language | Kotlin 2.0.0 (JVM target 17) |
| Build | Gradle 9.7.1, Android Gradle Plugin 8.10.1 |
| SDK | `compileSdk` 34, `minSdk` 24, `targetSdk` 34 |
| UI | XML layouts, `AppCompatActivity`, Material Components 1.11.0, `RecyclerView` 1.3.2, `ConstraintLayout` 2.1.4, `TabLayout`, View Binding |
| Media session | AndroidX Media 1.7.0 (`MediaBrowserServiceCompat`, `MediaSessionCompat`, `MediaControllerCompat`, `MediaButtonReceiver`) |
| Playback engine | ExoPlayer 2.19.1 (`com.google.android.exoplayer:exoplayer`) |
| Async | Kotlin Coroutines 1.7.3, `androidx.lifecycle` 2.7.0 |
| Networking | OkHttp 4.12.0 (optional online metadata / artwork lookup via MusicBrainz) |
| Tests | JUnit 4.13.2 (JVM unit tests, no instrumentation tests) |

AndroidX Media 1.7.0 still ships its compat classes in the `android.support.v4.media` package
(`MediaSessionCompat`, `MediaMetadataCompat`, `PlaybackStateCompat`, …); the project imports them
from there.

---

## Project structure

```
app/src/main/java/com/sparkmusic/
  MainActivity.kt            Library tabs, groups, Now Playing, mini-player, error prompts
  MusicPlaybackService.kt    MediaBrowserService, MediaSession, ExoPlayer, queue
  MediaNotificationManager.kt Foreground media notification
  MusicLibraryManager.kt     Single source of truth for the library + cache
  MusicScanner.kt            MediaStore scan and SAF folder traversal
  ScanScope.kt               Scan mode, folder list, permissions, validation
  FavouritesStore.kt         Persistent favourites
  LibraryGrouping.kt         Artist / album / genre / folder grouping
  MusicLibraryCodec.kt       JSON codec for the library cache
  LibraryCacheFile.kt        Atomic file write (temp file + rename)
  LibraryCachePolicy.kt      Startup recovery and empty-scan safeguards
  PlaybackErrorPolicy.kt     Error classification and prompt de-duplication
  TrackSource.kt             MediaStore vs. SAF source resolution
  MusicFileDeleter.kt        MediaStore delete request / SAF document deletion
  LibraryRemoval.kt          Single-entry removal from the library
  OnlineMetadataFetcher.kt   Optional online artwork and metadata lookup
  adapters/MusicAdapter.kt   Song rows, group rows, heart buttons

app/src/test/java/com/sparkmusic/   84 JVM unit tests
app/src/main/res/                   Layouts, drawables, adaptive launcher icon
```

---

## Build

Requirements: JDK 17 and the Android SDK (platform 34).

```bash
# From the repository root (Windows)
.\gradlew.bat :app:assembleDebug
```

```bash
# macOS / Linux
./gradlew :app:assembleDebug
```

The debug APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Test

```bash
.\gradlew.bat :app:testDebugUnitTest
```

- 84 unit tests in 10 test files (15 test classes); the current suite passes.
- HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Static analysis (optional, see *Known limitations*):
  `.\gradlew.bat :app:lintDebug`

## Install with ADB

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.sparkmusic/.MainActivity
```

`-r` performs a normal upgrade and keeps existing app data. Do not uninstall or clear app data
unless you intend to lose the library cache, favourites and scan scope.

On first launch SparkMusic asks for the audio permission (`READ_MEDIA_AUDIO` on Android 13+,
otherwise `READ_EXTERNAL_STORAGE`) and scans automatically once it is granted. **Scan Music
Files** re-runs the scan at any time with the currently selected scope.

---

## Data and storage

Everything is stored in the app's private files directory (`context.filesDir`, typically
`/data/data/com.sparkmusic/files/`).

| File | Purpose |
|---|---|
| `music_library.json` | The replaceable library index (JSON array of tracks) |
| `favourites.json` | Favourites, independent of the library cache |
| `scan_scope.json` | Selected scan mode and folder list |

**Library cache.** The index is written to a temporary file, verified, then swapped in, so an
interrupted write cannot truncate a valid index. An empty or failed scan never replaces a valid
non-empty library, and a missing or corrupt cache does not permanently latch the library as
"loaded" — the next launch can retry.

**Scan scope.** Folder picks are persisted with `takePersistableUriPermission`. Write access is
requested as well, so a file inside a selected folder can be deleted later; if the provider
grants read-only access, scanning still works and deletion is simply reported as unavailable.

**Favourites.** Matched by media ID first and file path second, so a favourite still resolves
after a rescan whenever either value is unchanged. A corrupt favourites file is never overwritten
until a favourite is actually changed.

**Remove vs. delete.** These are deliberately different actions:

- *Remove from library* deletes only SparkMusic's own index entry. **The audio file stays on the
  device**, and a later scan can find it again.
- *Delete file from device* asks for a second confirmation and then uses the platform mechanism:
  a `MediaStore` delete request on Android 11+, or `DocumentsContract.deleteDocument` for a
  selected folder when write access was granted. The library entry is updated only after Android
  confirms that the file is gone. Nothing is ever deleted automatically.

Android's cache-clearing action does not remove any of these files, because they live in
`filesDir`, not `cacheDir`.

---

## Android Auto and device testing

Android Auto support is declared through `res/xml/automotive_app_desc.xml`
(`<uses name="media" />`) and implemented by the `MediaBrowserService`. A car connects to
`onGetRoot` / `onLoadChildren`, and Now Playing metadata (title, artist, artwork) is published on
the media session.

To exercise it:

1. Connect the phone to an Android Auto head unit, or use the Desktop Head Unit (DHU) from the
   Android Auto SDK with a device running the Android Auto app in developer mode.
2. Open SparkMusic once so the library cache exists.
3. In the car, browse **SparkMusic** from the media source list and start a track.

Checklist that still needs a manual pass on real hardware:

- Library browsing in the car after a cold start (cache restored, no phone UI opened).
- Now Playing title, artist and artwork — including a track with no embedded artwork.
- Transport controls from the car.
- Behaviour with the phone locked and the screen off.

Voice search (`MEDIA_PLAY_FROM_SEARCH`) is **not** implemented; lint reports it as a missing
intent filter and callback.

---

## Known limitations

- Selected-folder tracks are played through their `content://` document URI. Embedded artwork
  extraction uses a file path, so SAF tracks fall back to the bundled artwork or an online lookup.
- Deleting a `MediaStore` file from the app requires Android 11 or newer. On Android 7–10 the app
  holds no storage-write permission, so only *Remove from library* is offered there.
- Deleting a file inside a selected folder requires the folder grant to include write access.
  Folders added with read-only access must be re-added to enable deletion.
- After *Remove from library*, the playback queue drops the track at the next play / next /
  previous action; until then it can still be reached and will simply fail and be skipped.
- `:app:lintDebug` currently reports three pre-existing errors unrelated to the features above:
  `MissingIntentFilterForMediaSearch` and `MissingOnPlayFromSearch` (voice search) and
  `UseAppTint` in `screen_now_playing.xml`. The build itself succeeds.
- The tests are JVM unit tests only; there is no instrumentation test suite.

---

## Development status

SparkMusic 1.1 currently meets the owner's needs. Feature development is paused and will resume
only when a new request is made. The code is published as-is for reference and personal use.

---

## Contributing and Git safety

If you build on this project:

1. Work on a branch and keep commits focused.
2. Run the unit tests before committing:
   `.\gradlew.bat :app:testDebugUnitTest`
3. Build the debug APK to confirm the app still assembles:
   `.\gradlew.bat :app:assembleDebug`
4. Avoid destructive Git commands in a working copy that contains uncommitted work — no
   `git reset --hard`, `git clean -fd`, `git checkout .` or `git stash` unless you have verified
   that nothing valuable would be lost. Never force-push a shared branch.
5. Do not commit build outputs (`app/build/`), APKs, `.idea/` or other machine-specific files.

There is no license file in this repository; treat the code as proprietary unless the owner
states otherwise.
