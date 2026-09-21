package app.easepod.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.easepod.MainActivity
import app.easepod.contract.HomeCatalog
import app.easepod.core.WheelKey
import app.easepod.netease.NeteasePlugin
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NeteaseHomeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AppModel

    /** Run after QR login with instrumentation argument: -e neteaseAccountLive true. */
    @Test fun verifiedAccountFeedsMainMusicScreensWithoutCloudWrites() {
        if (InstrumentationRegistry.getArguments().getString("neteaseAccountLive") != "true") return
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppModel::class.java] }
        runBlocking { withTimeout(15_000) { model.app.awaitStartupResources(); model.app.plugins.awaitAccountState() } }
        compose.waitUntil(10_000) { model.library.ready && model.pluginList.any { it.id == NeteasePlugin.id } }

        val context = compose.activity
        val sourcePreferences = context.getSharedPreferences("current-music-source", Context.MODE_PRIVATE)
        val accountPreferences = context.getSharedPreferences("selected-service-accounts", Context.MODE_PRIVATE)
        val originalSourcePreferences = sourcePreferences.all.toMap()
        val originalAccountPreferences = accountPreferences.all.toMap()
        val originalSettings = runBlocking { model.app.settings.persistedSettings.first() }
        val originalEnabled = model.app.plugins.plugins.value.single { it.id == NeteasePlugin.id }.enabled
        var originalSource: MusicSourceSelection? = null
        var originalAccounts: Map<String, String> = emptyMap()
        compose.runOnIdle {
            assertTrue("Live catalog must use the real plugin adapter", model.pageSource is PluginPageSource)
            assertTrue("Live account must use the real plugin adapter", model.accountSource is PluginAccountSource)
            originalSource = model.musicSource
            originalAccounts = model.selectedAccounts.toMap()
        }
        try {
            runBlocking {
                model.app.settings.update { it.copy(touchGuard = true, safeMode = false, reducedMotion = true, clickSound = false, haptics = false) }
                if (!originalEnabled) model.app.plugins.setEnabled(NeteasePlugin.id, true)
            }
            compose.waitUntil(10_000) {
                !model.settings.safeMode && model.settings.touchGuard && model.settings.reducedMotion &&
                    model.pluginList.any { it.id == NeteasePlugin.id && it.enabled && HomeCatalog.CAPABILITY in it.capabilities }
            }
            compose.runOnIdle { model.home(); model.navigate(Route("login", key = NeteasePlugin.id)) }
            compose.waitUntil(10_000) { model.accounts.any { it.state == "SignedIn" } }
            var scope = ""
            compose.runOnIdle {
                val account = model.accounts.firstOrNull { it.state == "SignedIn" && it.scope == originalAccounts[NeteasePlugin.id] }
                    ?: model.accounts.first { it.state == "SignedIn" }
                scope = account.scope
                assertTrue("A live account must have a private scope", scope.isNotBlank() && scope !in setOf("local", "public"))
                val index = model.page().rows.indexOfFirst { it.title == account.displayName }
                assertTrue("Verified account must be visible on the account page", index >= 0)
                model.activate(index)
                model.dialog!!.rows.single { it.title == "使用此账号" }.action()
                assertEquals(MusicSourceSelection(NeteasePlugin.id, scope), model.musicSource)
            }

            openMusic()
            capture("music-source-live-menu")
            choose("Cover Flow")
            awaitCatalog(scope, HomeCatalog.COVERS)
            capture("music-source-live-covers")
            var query: String? = null
            var openedPlaylist = false
            var selectedCover: String? = null
            compose.runOnIdle {
                val index = model.coverItems.indexOfFirst { it.kind.equals("PLAYLIST", true) && it.id.startsWith("playlist:") }
                if (index >= 0) {
                    if (index != 0) model.selectCover(index)
                    selectedCover = model.selectedCover()?.id
                    model.selectCover(0)
                    assertEquals("playlist", model.route.id)
                    openedPlaylist = true
                }
            }
            if (openedPlaylist) {
                awaitCatalog(scope)
                compose.runOnIdle {
                    query = model.remoteTracks.firstOrNull()?.title
                    model.back()
                    assertEquals("coverflow", model.route.id)
                    assertEquals(selectedCover, model.selectedCover()?.id)
                }
            }

            listOf("歌单" to HomeCatalog.PLAYLISTS, "歌曲" to HomeCatalog.TRACKS,
                "专辑" to HomeCatalog.ALBUMS, "艺人" to HomeCatalog.ARTISTS).forEach { (title, key) ->
                openMusic()
                choose(title)
                awaitCatalog(scope, key)
                compose.runOnIdle { if (query.isNullOrBlank()) query = model.remoteTracks.firstOrNull()?.title }
                if (key == HomeCatalog.PLAYLISTS) capture("music-source-live-playlists")
            }
            // These account feeds are normal Cover Flow/playlist entries, not separate music-menu actions.
            openMusic(); choose("Cover Flow"); awaitCatalog(scope, HomeCatalog.COVERS)
            assertTrue("Cover Flow must include daily recommendations", model.catalogItems.any { it.remoteId == HomeCatalog.DAILY })
            assertTrue("Cover Flow must include private radar", model.catalogItems.any { it.remoteId == HomeCatalog.NETEASE_RADAR })
            assertTrue("Cover Flow must include song roam", model.catalogItems.any { it.remoteId == HomeCatalog.NETEASE_ROAM })
            openMusic(); choose("歌单"); awaitCatalog(scope, HomeCatalog.PLAYLISTS)
            listOf(HomeCatalog.DAILY, HomeCatalog.NETEASE_RADAR, HomeCatalog.NETEASE_ROAM).forEach { virtual ->
                compose.runOnIdle {
                    val row = model.page().rows.indexOfFirst { it.id == "PLAYLIST:$virtual" }
                    assertTrue("Playlist must include $virtual", row >= 0)
                    model.activate(row)
                }
                awaitCatalog(scope, virtual)
                compose.runOnIdle { model.back() }
                compose.waitUntil(10_000) { !model.busy }
            }
            // Empty personal collections are valid; use a real catalog title for the search read.
            if (query.isNullOrBlank()) query = runBlocking {
                withTimeout(10_000) {
                    model.app.plugins.details(NeteasePlugin.id, "347230", pageSize = 1, accountScope = scope)
                        .tracks.firstOrNull()?.title
                }
            }
            assertFalse("Search requires a real catalog title", query.isNullOrBlank())
            openMusic()
            choose("搜索")
            compose.runOnIdle { model.inputChanged(requireNotNull(query).take(64)); model.submitInput() }
            compose.waitUntil(10_000) { model.searchStatus != "正在搜索" }
            compose.runOnIdle {
                assertTrue("Main search must retain the verified account", model.route.accountScope == scope)
                assertEquals(NeteasePlugin.id, model.route.source)
                assertFalse("Main search failed", model.searchStatus.startsWith("搜索失败"))
                assertTrue("Search tracks must retain the verified account", model.searchResults.all { it.accountScope == scope })
            }
        } finally {
            compose.runOnIdle { model.home(); model.selectMusicSource(null) }
            runBlocking {
                if (!originalEnabled) model.app.plugins.setEnabled(NeteasePlugin.id, false)
                model.app.settings.update { originalSettings }
            }
            compose.waitUntil(10_000) {
                model.settings == originalSettings && model.pluginList.single { it.id == NeteasePlugin.id }.enabled == originalEnabled
            }
            compose.runOnIdle {
                model.selectedAccounts.clear(); model.selectedAccounts.putAll(originalAccounts)
                model.selectMusicSource(originalSource)
                restorePreferences(sourcePreferences, originalSourcePreferences)
                restorePreferences(accountPreferences, originalAccountPreferences)
                model.home(); model.navigate(Route("music"))
            }
        }
    }

    private fun openMusic() {
        compose.runOnIdle { model.home() }
        choose("音乐")
    }

    private fun choose(title: String) = compose.runOnIdle {
        val index = model.page().rows.indexOfFirst { it.title == title }
        assertTrue("Missing music menu action", index >= 0)
        model.focus(index); model.key(WheelKey.CENTER)
    }

    private fun awaitCatalog(scope: String, key: String? = null) {
        compose.waitUntil(10_000) { !model.busy }
        compose.runOnIdle {
            assertEquals(NeteasePlugin.id, model.route.source)
            assertTrue("Main catalog must retain the verified account", model.route.accountScope == scope)
            if (key != null) assertEquals(key, model.route.key)
            assertFalse("Main catalog read failed", model.remoteStatus.startsWith("读取失败"))
            assertFalse("Main catalog endpoint was unsupported", model.remoteStatus.contains("不支持"))
            assertTrue("Catalog tracks must retain the verified account", model.remoteTracks.all { it.accountScope == scope })
        }
    }

    private fun capture(name: String) {
        val directory = requireNotNull(compose.activity.getExternalFilesDir("verification"))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun restorePreferences(preferences: SharedPreferences, snapshot: Map<String, *>) {
        preferences.edit().clear().apply {
            snapshot.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.apply()
    }
}
