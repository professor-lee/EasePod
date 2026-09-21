package app.easepod.ui

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.easepod.MainActivity
import app.easepod.core.AppSettings
import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.WheelKey
import app.easepod.plugins.PendingInstallStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegrationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AppModel
    private var originalSettings: AppSettings? = null

    @Before fun prepareUnlockedApplication() {
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[AppModel::class.java]
            assumeFalse("Integration tests require an unlocked device", compose.activity.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        }
        runBlocking { model.app.awaitStartupResources() }
        compose.waitUntil(15_000) { model.library.ready && model.themeList.isNotEmpty() }
        originalSettings = runBlocking { model.app.settings.persistedSettings.first() }
        runBlocking { model.app.settings.update { it.copy(themeId = "silver", safeMode = false, touchGuard = false, reducedMotion = true) } }
        compose.waitUntil { model.activeThemeId == "silver" && !model.settings.safeMode && !model.settings.touchGuard }
        compose.runOnIdle { model.home() }
    }

    @After fun restoreSettingsAndForeground() {
        val previous = originalSettings ?: return
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppModel::class.java]; model.home() }
        runBlocking { model.app.settings.update { previous } }
    }

    @Test fun recreationRetainsFileSessionButOrdinaryBackgroundInvalidatesIt() {
        var generation = 0L
        lateinit var retained: AppModel
        compose.runOnIdle {
            model.navigate(Route("storage"))
            retained = model
            generation = model.sessionGeneration
            assertTrue(model.acceptsFileResult(generation))
        }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[AppModel::class.java]
            assertSame("Configuration changes retain the ViewModel", retained, model)
            assertEquals("storage", model.route.id)
            assertEquals(generation, model.sessionGeneration)
            assertTrue("The original file-result token survives recreation", model.acceptsFileResult(generation))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity { activity ->
            val backgroundModel = ViewModelProvider(activity)[AppModel::class.java]
            assertTrue(backgroundModel.sessionGeneration > generation)
            assertFalse("An ordinary onStop invalidates old file results", backgroundModel.acceptsFileResult(generation))
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertEquals("storage", model.route.id)
            assertFalse(model.acceptsFileResult(generation))
            assertTrue(model.acceptsFileResult(model.sessionGeneration))
        }
    }

    @Test fun privateBackupRoundTripPreservesQaOrderAndRequiresLocalRelinking() {
        compose.runOnIdle {
            assumeTrue("Backup test only runs against EasePod-QA", model.library.root?.name == "EasePod-QA" && model.library.root?.permissionValid == true)
            assumeTrue("Backup test must not export or duplicate user playlists", model.library.playlists.isEmpty())
            val files = model.localTracks.map { DocumentsContract.getDocumentId(Uri.parse(it.contentUri)).substringAfterLast('/') }.toSet()
            assumeTrue("Requires the three authorized silent tracks", files == setOf("Standalone.wav", "First.flac", "Second.ogg") && model.localTracks.size == 3)
        }
        val title = "EasePod Backup QA ${UUID.randomUUID().toString().take(8)}"
        val password = "qa-backup-${UUID.randomUUID()}".toCharArray()
        val backup = File.createTempFile("easepod-qa-", ".epb", model.app.cacheDir)
        val originalRoot = model.library.root
        val originalTracks = model.localTracks.associateBy { it.id }
        val sourceIds = model.localTracks.take(2).map { it.id }.let { it + it.first() }
        val exportSettings = model.settings.copy(themeId = "silver", networkQuality = "lossless", wheelSensitivity = 3)
        try {
            val sourcePlaylist = runBlocking { model.app.library.createPlaylist(title, sourceIds) }
            runBlocking { model.app.settings.update { exportSettings } }
            val bytes = runBlocking { model.app.library.exportBackup(password) }
            try { backup.outputStream().use { it.write(bytes) } } finally { bytes.fill(0) }
            runBlocking {
                model.app.library.renamePlaylist(sourcePlaylist, "$title changed")
                model.app.settings.update { it.copy(themeId = "black", networkQuality = "standard", wheelSensitivity = 1) }
            }
            val encrypted = backup.readBytes()
            try { runBlocking { model.app.library.importBackup(encrypted, password) } } finally { encrypted.fill(0) }
            compose.waitUntil(15_000) { model.library.playlists.any { it.title == title } && model.settings.networkQuality == "lossless" }
            compose.runOnIdle {
                assertEquals(originalRoot, model.library.root)
                assertEquals(originalTracks, model.localTracks.associateBy { it.id })
                val restored = model.library.playlists.single { it.title == title }
                assertNotEquals(sourcePlaylist, restored.id)
                assertEquals(sourceIds.size, restored.entries.size)
                val restoredTracks = restored.entries.map { entry -> model.library.tracks.single { it.id == entry.trackId } }
                assertEquals(sourceIds.map { originalTracks.getValue(it).title }, restoredTracks.map { it.title })
                assertEquals(restored.entries.first().trackId, restored.entries.last().trackId)
                assertEquals(3, restored.entries.map { it.id }.distinct().size)
                assertTrue("SAF identities are never portable backup credentials", restoredTracks.all { it.sourceId == LOCAL_SOURCE && !it.available && it.contentUri == null && it.artworkUri == null })
                assertEquals(sourceIds, model.library.playlists.single { it.id == sourcePlaylist }.entries.map { it.trackId })
                assertEquals("silver", model.settings.themeId)
                assertEquals(3, model.settings.wheelSensitivity)
                model.navigate(Route("playlist", restored.id, restored.title))
            }
            capture("integration-restored-backup-playlist")
            val playlistIds = model.library.playlists.map { it.id }.toSet()
            val secondRead = backup.readBytes()
            try { runBlocking { model.app.library.importBackup(secondRead, password) } } finally { secondRead.fill(0) }
            assertEquals("The same export must not import twice", playlistIds, model.app.library.library.value.playlists.map { it.id }.toSet())
        } finally {
            password.fill('\u0000')
            backup.delete()
            runBlocking { model.app.library.library.value.playlists.filter { it.title == title || it.title == "$title changed" }.forEach { model.app.library.deletePlaylist(it.id) } }
        }
    }

    @Test fun generatedThemePackageInstallsPreviewsAppliesRollsBackAndUninstalls() {
        val themeId = "org.easepod.qa.t${UUID.randomUUID().toString().replace("-", "")}"
        val packages = mutableListOf<File>()
        try {
            val firstPackage = themeArchive(themeId, "1", "#356659", 0xff3c8271.toInt()).also(packages::add)
            val first = runBlocking { model.app.themes.install(firstPackage.path) }
            assertEquals(themeId, first.id)
            assertTrue(File(requireNotNull(first.assets.albumPlaceholder)).isFile)
            val firstPixels = File(requireNotNull(first.assets.albumPlaceholder)).readBytes()
            compose.waitUntil(10_000) { model.themeList.any { it.id == themeId } }
            compose.runOnIdle {
                model.navigate(Route("themes"))
                model.activate(model.page().rows.indexOfFirst { it.id == themeId })
                assertEquals(themeId, model.activeThemeId)
                assertEquals("silver", model.settings.themeId)
            }
            capture("integration-theme-preview")
            compose.runOnIdle {
                model.back()
                assertEquals("silver", model.activeThemeId)
                model.activate(model.page().rows.indexOfFirst { it.id == themeId })
                chooseDialog("应用")
            }
            compose.waitUntil(10_000) { model.settings.themeId == themeId && model.previewThemeId == null }
            capture("integration-theme-applied")
            val secondPackage = themeArchive(themeId, "2", "#763b5c", 0xffad587f.toInt()).also(packages::add)
            val second = runBlocking { model.app.themes.install(secondPackage.path) }
            assertTrue(second.hasPrevious)
            assertNotEquals(first.assets.albumPlaceholder, second.assets.albumPlaceholder)
            assertFalse(firstPixels.contentEquals(File(requireNotNull(second.assets.albumPlaceholder)).readBytes()))
            compose.waitUntil(10_000) { model.themeList.any { it.id == themeId && it.version == "2" && it.hasPrevious } }
            compose.runOnIdle { themeMenu(themeId); chooseDialog("恢复上一版本") }
            compose.waitUntil(10_000) { model.themeList.any { it.id == themeId && it.version == "1" } && model.dialog == null }
            assertArrayEquals(firstPixels, File(requireNotNull(model.app.themes.theme(themeId).assets.albumPlaceholder)).readBytes())
            capture("integration-theme-rollback")
            compose.runOnIdle { themeMenu(themeId); chooseDialog("卸载主题"); chooseDialog("确认") }
            compose.waitUntil(10_000) { model.themeList.none { it.id == themeId } && model.settings.themeId == "silver" }
            assertFalse(File(requireNotNull(first.assets.albumPlaceholder)).exists())
            assertFalse(File(requireNotNull(second.assets.albumPlaceholder)).exists())
        } finally {
            runBlocking {
                model.app.settings.update { it.copy(themeId = "silver") }
                model.app.themes.uninstall(themeId)
            }
            packages.forEach(File::delete)
        }
    }

    @Test fun themeReceiptIsFinalizedEvenWhenItsInstallUiSessionEnds() {
        assumeTrue("Installation test requires no existing pending package", runBlocking { PendingInstallStore(model.app).restore() } == null)
        val themeId = "org.easepod.qa.t${UUID.randomUUID().toString().replace("-", "")}"
        val archive = themeArchive(themeId, "1", "#356659", 0xff3c8271.toInt())
        try {
            compose.runOnIdle {
                model.navigate(Route("store"))
                model.pluginFileChosen(Uri.fromFile(archive))
            }
            compose.waitUntil(15_000) { model.route.id == "install" && model.installCandidate?.pluginId == themeId }
            val candidate = requireNotNull(model.installCandidate)
            assertEquals(candidate, runBlocking { PendingInstallStore(model.app).restore() }?.candidate)
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                model = ViewModelProvider(compose.activity)[AppModel::class.java]
                assertEquals(candidate, model.installCandidate)
                model.activate(model.page().rows.indexOfFirst { it.id == "install-confirm" })
                model.suspended()
            }
            compose.waitUntil(15_000) { model.themeList.any { it.id == themeId } && model.installStatus == "主题已安装" }
            assertNull("A completed theme import must not recover as another pending install", runBlocking { PendingInstallStore(model.app).restore() })
            compose.runOnIdle {
                assertEquals("install", model.route.id)
                assertEquals(listOf("完成"), model.page().rows.map { it.title })
                model.activate(0)
                assertEquals("store", model.route.id)
                assertNull(model.installCandidate)
            }
        } finally {
            compose.runOnIdle { model.home() }
            runBlocking {
                PendingInstallStore(model.app).restore()?.candidate?.takeIf { it.pluginId == themeId }?.let { PendingInstallStore(model.app).clear(it) }
                model.app.themes.uninstall(themeId)
            }
            archive.delete()
        }
    }

    private fun themeMenu(themeId: String) {
        model.navigate(Route("themes"))
        val index = model.page().rows.indexOfFirst { it.id == themeId }
        assertTrue(index >= 0)
        model.focus(index)
        model.hold(WheelKey.CENTER)
    }

    private fun chooseDialog(title: String) {
        val index = model.dialog?.rows?.indexOfFirst { it.title == title } ?: -1
        assertTrue("Missing dialog action: $title", index >= 0)
        model.dialogActivate(index)
    }

    private fun themeArchive(id: String, version: String, highlight: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val image = try { ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() } }
        finally { bitmap.recycle() }
        val manifest = JSONObject().put("manifestVersion", 1).put("kind", "theme").put("pluginId", id).put("displayName", "EasePod QA Theme").put("version", version)
        val tokens = JSONObject().put("colors", JSONObject().put("highlight", highlight)).put("font", "serif")
            .put("assets", JSONObject().put("albumPlaceholder", "assets/cover.png"))
        val file = File.createTempFile("theme-integration-", ".ep-theme", model.app.cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to manifest.toString().toByteArray(), "tokens.json" to tokens.toString().toByteArray(), "assets/cover.png" to image)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }

    private fun capture(name: String) {
        val directory = requireNotNull(compose.activity.getExternalFilesDir("verification"))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
