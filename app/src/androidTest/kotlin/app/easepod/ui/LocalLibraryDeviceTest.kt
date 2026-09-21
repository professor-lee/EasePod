package app.easepod.ui

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.easepod.MainActivity
import app.easepod.core.AppSettings
import app.easepod.core.RepeatMode
import app.easepod.core.Track
import app.easepod.core.WheelKey
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLibraryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AppModel
    private lateinit var tracks: Map<String, Track>
    private var originalSettings: AppSettings? = null
    private var originalVolume = 0
    private var originalRepeat = RepeatMode.OFF
    private var originalShuffle = false

    @Before fun prepareOnlyTheAuthorizedFixtureLibrary() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppModel::class.java] }
        compose.waitUntil(15_000) { model.library.ready }
        val fixtureFiles = setOf("Standalone.wav", "First.flac", "Second.ogg")
        compose.runOnIdle {
            val root = model.library.root
            assumeTrue("Requires the explicitly selected EasePod-QA SAF folder", root?.name == "EasePod-QA" && root.permissionValid)
            assumeTrue("Requires exactly the three authorized local fixture tracks", model.localTracks.size == 3 && model.localTracks.map(::fileName).toSet() == fixtureFiles)
            assumeTrue("Each fixture must belong to the selected QA folder", model.localTracks.all { documentId(it).contains("/EasePod-QA/") })
            assumeFalse("Run the device suite with the device unlocked", compose.activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            tracks = model.localTracks.associateBy(::fileName)
            originalRepeat = model.playback.repeat
            originalShuffle = model.playback.shuffle
        }
        val audio = compose.activity.getSystemService(AudioManager::class.java)
        originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalSettings = runBlocking { model.app.settings.persistedSettings.first() }
        runBlocking { model.app.settings.update { it.copy(touchGuard = false, safeMode = false, themeId = "silver", reducedMotion = false, haptics = false, clickSound = false) } }
        compose.waitUntil { model.settings.themeId == "silver" && !model.settings.touchGuard && !model.settings.safeMode && !model.settings.reducedMotion }
        compose.runOnIdle {
            model.app.player.pause()
            model.app.player.volume(0f)
            model.app.player.repeat(RepeatMode.OFF)
            model.app.player.shuffle(false)
            model.home()
        }
        compose.waitUntil { !model.playback.playing && model.playback.repeat == RepeatMode.OFF && !model.playback.shuffle }
    }

    @After fun restoreSettingsAndLeavePlaybackPaused() {
        compose.mainClock.autoAdvance = true
        val previous = originalSettings ?: return
        try {
            compose.runOnIdle {
                model.app.player.pause()
                model.app.player.repeat(originalRepeat)
                model.app.player.shuffle(originalShuffle)
                model.home()
            }
            runBlocking { model.app.settings.update { previous } }
        } finally {
            val audio = compose.activity.getSystemService(AudioManager::class.java)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    @Test fun rootSinglesDirectoryAlbumsAndSiblingLyricsUseThePublishedSafLibrary() {
        compose.runOnIdle {
            assertNull(tracks.getValue("Standalone.wav").albumId)
            assertNull(tracks.getValue("Standalone.wav").albumTitle)
            assertEquals(setOf("Album-A", "Album-B"), model.library.albums.map { it.title }.toSet())
            assertEquals(2, model.library.albums.size)
            for ((directory, file) in listOf("Album-A" to "First.flac", "Album-B" to "Second.ogg")) {
                val album = model.library.albums.single { it.title == directory }
                val track = tracks.getValue(file)
                assertEquals(listOf(track.id), album.trackIds)
                assertEquals(album.id, track.albumId)
                assertEquals(directory, track.albumTitle)
                assertNotNull("Directory cover must be decoded", album.artworkUri)
                assertEquals(album.artworkUri, track.artworkUri)
            }
            model.navigate(Route("lyrics", tracks.getValue("First.flac").id))
        }
        compose.waitUntil(10_000) { model.lyricLines.isNotEmpty() || model.lyricText != "正在读取歌词" }
        compose.runOnIdle {
            assertTrue("First.lrc must yield nonempty lyrics", model.lyricLines.isNotEmpty())
            assertTrue("First.lrc must retain its timestamps", model.lyricLines.any { it.timeMs != null })
            assertEquals("", model.lyricText)
        }
        capture("local-lyrics-saf")
    }

    @Test fun allThreeFormatsPlayAndSeekThroughTheAppModel() {
        for (file in listOf("Standalone.wav", "First.flac", "Second.ogg")) {
            val track = tracks.getValue(file)
            playFromSongs(track)
            val initialPosition = model.playback.positionMs
            compose.waitUntil(5_000) { model.playback.positionMs > initialPosition + 250 }
            compose.runOnIdle { model.key(WheelKey.PLAY) }
            compose.waitUntil(5_000) { !model.playback.playing }
            compose.runOnIdle {
                assertTrue("30-second fixture duration", model.playback.durationMs in 29_000L..31_000L)
                assertTrue(model.playback.canSeek)
                model.previewSeek(12_000)
                assertEquals(12_000L, model.currentPosition)
                model.finishSeek(false)
            }
            compose.waitUntil(5_000) { abs(model.playback.positionMs - 12_000) < 750 }
            compose.runOnIdle {
                model.previewSeek(18_000)
                assertEquals(18_000L, model.currentPosition)
                model.finishSeek(true)
                assertTrue("Cancelled drag must retain the committed position", abs(model.currentPosition - 12_000) < 750)
                assertNull(model.playback.error)
            }
            capture("local-now-${file.substringAfterLast('.')}")
        }
        playFromSongs(tracks.getValue("First.flac"))
        compose.runOnIdle { model.app.player.pause(); model.navigate(Route("lyrics")) }
        compose.waitUntil(10_000) { !model.playback.playing && model.lyricLines.isNotEmpty() }
        var target = 0L
        compose.runOnIdle {
            val line = model.lyricLines.indexOfFirst { it.timeMs != null && it.timeMs!! < model.playback.durationMs }
            assertTrue(line >= 0)
            target = requireNotNull(model.lyricLines[line].timeMs)
            model.scrollLyrics(line - model.lyricFocus)
            model.key(WheelKey.CENTER)
        }
        compose.waitUntil(5_000) { abs(model.playback.positionMs - target) < 750 }
        capture("local-lyrics-seek")
        compose.runOnIdle { model.navigate(Route("queue")) }
        capture("local-queue")
    }

    @Test fun artistAllSongsAndPlayAllIncludeTracksAcrossDirectories() {
        val selected = tracks.values.groupBy { it.artist }.entries.firstOrNull { (_, group) -> group.map { it.albumId }.distinct().size >= 2 }
        assumeTrue("Artist fixture must span at least two directories or a directory and root singles", selected != null)
        val choice = requireNotNull(selected)
        val artist = choice.key
        val ids = choice.value.map { it.id }.toSet()
        compose.runOnIdle {
            model.navigate(Route("artist", artist))
            assertTrue(model.page().rows.any { it.title == "播放全部" })
            model.activate(model.page().rows.indexOfFirst { it.title == "全部歌曲" })
            assertEquals("songs", model.route.id)
            assertEquals(artist, model.route.localArtist)
            assertEquals(ids, model.page().rows.map { it.id }.toSet())
            assertEquals(artist, model.route.toSavedBundle().toRouteOrNull()!!.localArtist)
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[AppModel::class.java]
            assertEquals(artist, model.route.localArtist)
            assertEquals(ids, model.page().rows.map { it.id }.toSet())
            model.back()
            assertEquals("artist", model.route.id)
            model.activate(model.page().rows.indexOfFirst { it.title == "播放全部" })
        }
        compose.waitUntil(10_000) { model.playback.playing && model.playback.queue.map { it.track.id }.toSet() == ids }
        compose.runOnIdle {
            assertEquals(ids.size, model.playback.queue.size)
            assertEquals("now", model.route.id)
            model.app.player.pause()
        }
    }

    @Test fun historyDisplaysDistinctEventsAndRemovesOnlyTheSelectedOccurrence() {
        val track = tracks.getValue("Standalone.wav")
        val queue = model.playback.queue
        val events = mutableListOf<String>()
        try {
            repeat(2) {
                runBlocking { model.app.library.recordHistory(track.id) }
                events += model.app.library.library.value.history.first().id
            }
            compose.waitUntil { model.library.history.count { it.id in events } == 2 }
            compose.runOnIdle {
                model.navigate(Route("history"))
                val rows = model.page().rows.filter { it.id in events.map { id -> "history:$id" } }
                assertEquals(2, rows.size)
                assertTrue(rows.all { it.title == track.title && it.detail.matches(Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}.*")) })
                model.focus(model.page().rows.indexOfFirst { it.id == "history:${events.first()}" })
                model.hold(WheelKey.CENTER)
                model.dialogActivate(model.dialog!!.rows.indexOfFirst { it.title == "移除记录" })
            }
            compose.waitUntil { model.library.history.none { it.id == events.first() } }
            compose.runOnIdle {
                assertTrue(model.library.history.any { it.id == events.last() })
                assertTrue(model.localTracks.any { it.id == track.id })
                assertEquals(queue, model.playback.queue)
            }
            capture("local-history-events")
        } finally { runBlocking { events.forEach { model.app.library.removeHistory(it) } } }
    }

    @Test fun addToPlaylistCreatesWithTheOriginalTrackAndThenAppendsAnother() {
        val title = "EasePod QA ${UUID.randomUUID().toString().take(12)}"
        try {
            openTrackMenu(tracks.getValue("First.flac"))
            chooseDialog("添加到歌单")
            chooseDialog("新建歌单")
            compose.waitUntil(10_000) { model.route.id == "create-playlist" }
            compose.runOnIdle {
                assertEquals(tracks.getValue("First.flac").id, model.route.pendingTrackId)
                model.inputChanged(title)
                model.submitInput()
                model.submitInput()
            }
            compose.waitUntil(10_000) { model.route.id == "playlist" && model.library.playlists.any { it.title == title } }
            compose.runOnIdle {
                val matches = model.library.playlists.filter { it.title == title }
                assertEquals("Repeated submit must not create duplicate playlists", 1, matches.size)
                assertEquals(listOf(tracks.getValue("First.flac").id), matches.single().entries.map { it.trackId })
                assertEquals(matches.single().id, model.route.key)
            }
            capture("local-created-playlist")
            openTrackMenu(tracks.getValue("Second.ogg"))
            chooseDialog("添加到歌单")
            chooseDialog(title)
            compose.waitUntil(10_000) { model.library.playlists.singleOrNull { it.title == title }?.entries?.size == 2 }
            compose.runOnIdle {
                val playlist = model.library.playlists.single { it.title == title }
                assertEquals(listOf(tracks.getValue("First.flac").id, tracks.getValue("Second.ogg").id), playlist.entries.map { it.trackId })
                model.navigate(Route("playlist", playlist.id, playlist.title))
            }
            capture("local-populated-playlist")
        } finally {
            try { compose.waitUntil(10_000) { !model.busy } }
            finally {
                runBlocking {
                    model.app.library.library.value.playlists.filter { it.title == title }.forEach { model.app.library.deletePlaylist(it.id) }
                }
            }
        }
    }

    @Test fun coverFlowRetargetsBothDirectionsAndConfirmsTheLatestAlbum() {
        compose.runOnIdle { model.navigate(Route("coverflow")) }
        capture("local-coverflow-rest")
        val initial = model.coverPosition
        compose.runOnIdle {
            model.rotate(model.library.albums.size)
            assertEquals("A full cycle must not animate an unchanged target", initial, model.coverPosition)
            model.rotate(-model.library.albums.size)
            assertEquals(initial, model.coverPosition)
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                model.rotate(1)
                assertEquals(initial + 1, model.coverPosition)
            }
            compose.mainClock.advanceTimeBy(80)
            capture("local-coverflow-clockwise-mid")
            compose.runOnIdle {
                model.rotate(-1)
                assertEquals(initial, model.coverPosition)
            }
            compose.mainClock.advanceTimeBy(64)
            capture("local-coverflow-counterclockwise-mid")
            for (step in listOf(1, 1, -1)) {
                compose.runOnIdle { model.rotate(step) }
                compose.mainClock.advanceTimeBy(32)
            }
            compose.runOnIdle {
                assertEquals(initial + 1, model.coverPosition)
                val latest = model.library.albums[Math.floorMod(initial + 1, model.library.albums.size)]
                assertEquals(latest.id, model.selectedAlbum()?.id)
                model.key(WheelKey.CENTER)
                assertEquals("album", model.route.id)
                assertEquals(latest.id, model.route.key)
                assertEquals(latest.title, model.page().title)
            }
        } finally { compose.mainClock.autoAdvance = true }
        capture("local-coverflow-confirmed-album")
    }

    @Test fun captureMainRoutesAndAllBundledThemes() {
        playFromSongs(tracks.getValue("First.flac"))
        compose.runOnIdle { model.app.player.pause() }
        compose.waitUntil { !model.playback.playing }
        val album = model.library.albums.single { it.title == "Album-A" }
        val routes = listOf("home", "music", "songs", "artists", "albums", "genres", "playlists", "coverflow", "history", "now", "queue", "lyrics",
            "settings", "themes", "wheel-settings", "audio-settings", "sleep", "plugins", "services", "store", "storage", "local-folders", "downloads", "about")
        for (id in routes) {
            compose.runOnIdle { model.home(); if (id != "home") model.navigate(Route(id)) }
            if (id == "lyrics") compose.waitUntil(10_000) { model.lyricLines.isNotEmpty() }
            capture("route-silver-$id")
        }
        compose.runOnIdle { model.navigate(Route("album", album.id, album.title)) }
        capture("route-silver-album")
        compose.waitUntil(10_000) { model.themeList.map { it.id }.containsAll(listOf("silver", "black", "oled")) }
        for (theme in listOf("black", "oled")) {
            runBlocking { model.app.settings.update { it.copy(themeId = theme) } }
            compose.waitUntil { model.activeThemeId == theme }
            for (id in listOf("home", "now", "coverflow")) {
                compose.runOnIdle { model.home(); if (id != "home") model.navigate(Route(id)) }
                capture("route-$theme-$id")
            }
        }
    }

    private fun playFromSongs(track: Track) {
        compose.runOnIdle {
            model.navigate(Route("songs"))
            val index = model.page().rows.indexOfFirst { it.id == track.id }
            assertTrue(index >= 0)
            model.activate(index)
            assertEquals("now", model.route.id)
        }
        compose.waitUntil(15_000) { model.playback.current?.id == track.id && model.playback.playing && !model.playback.buffering && model.playback.canSeek }
        compose.runOnIdle { assertNull(model.playback.error) }
    }

    private fun openTrackMenu(track: Track) {
        compose.runOnIdle {
            model.navigate(Route("songs"))
            val index = model.page().rows.indexOfFirst { it.id == track.id }
            assertTrue(index >= 0)
            model.focus(index)
            model.hold(WheelKey.CENTER)
            assertNotNull(model.dialog)
        }
    }

    private fun chooseDialog(title: String) {
        compose.runOnIdle {
            val index = model.dialog?.rows?.indexOfFirst { it.title == title } ?: -1
            assertTrue("Missing dialog action: $title", index >= 0)
            model.dialogActivate(index)
        }
    }

    private fun capture(name: String) {
        val directory = requireNotNull(compose.activity.getExternalFilesDir("verification"))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun fileName(track: Track) = documentId(track).substringAfterLast('/')
    private fun documentId(track: Track) = runCatching { DocumentsContract.getDocumentId(Uri.parse(requireNotNull(track.contentUri))) }.getOrDefault("")
}
