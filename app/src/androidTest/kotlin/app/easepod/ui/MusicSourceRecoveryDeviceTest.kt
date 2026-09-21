package app.easepod.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import app.easepod.EasePodApplication
import app.easepod.contract.HomeCatalog
import app.easepod.core.AppSettings
import app.easepod.core.Track
import app.easepod.plugins.AccountRecord
import app.easepod.plugins.CatalogEntry
import app.easepod.plugins.CatalogPage
import app.easepod.plugins.LyricLine
import app.easepod.plugins.PluginDatabase
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class MusicSourceRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as EasePodApplication
    private val plugin = "app.easepod.netease"
    private val fixtureId = UUID.randomUUID().toString()
    private val accountScope = "recovery-$fixtureId"
    private val preferenceName = "music-recovery-test-$fixtureId"
    private val sourcePreferences = app.getSharedPreferences("$preferenceName-source", Context.MODE_PRIVATE)
    private val accountPreferences = app.getSharedPreferences("$preferenceName-accounts", Context.MODE_PRIVATE)
    private val viewModels = ViewModelStore()
    private val pages = ControlledPages()
    private lateinit var model: AppModel
    private lateinit var originalSettings: AppSettings
    private var originalEnabled = true

    @Before fun prepare() {
        runBlocking {
            app.awaitStartupResources()
            assumeTrue("网易云插件未安装，跳过可选插件来源恢复测试", runCatching {
                app.packageManager.getPackageInfo(plugin, 0)
            }.isSuccess)
            originalSettings = app.settings.persistedSettings.first()
            originalEnabled = app.plugins.plugins.value.single { it.id == plugin }.enabled
            app.settings.update { it.copy(safeMode = false, touchGuard = true) }
            app.plugins.setEnabled(plugin, true)
            PluginDatabase.create(app).dao().account(AccountRecord(plugin, "recovery-fixture-$fixtureId", accountScope))
            app.plugins.activeAccountScopes.first { (plugin to accountScope) in it }
        }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { viewModels.clear() }
        runBlocking {
            PluginDatabase.create(app).dao().removeAccount(plugin, accountScope)
            app.plugins.activeAccountScopes.first { (plugin to accountScope) !in it }
            app.plugins.setEnabled(plugin, originalEnabled)
            if (::originalSettings.isInitialized) app.settings.update { originalSettings }
        }
        app.deleteSharedPreferences("$preferenceName-source")
        app.deleteSharedPreferences("$preferenceName-accounts")
    }

    @Test fun dailyRecommendationsKeepTheirCatalogKeyAndFocusAfterColdRestore() {
        val saved = Route("songs", HomeCatalog.DAILY, "每日推荐", plugin, focus = 1,
            focusKey = "PLAYLIST:daily-second", accountScope = accountScope, musicRoot = true)
        restore(listOf(Route("home"), Route("music"), saved))
        val call = request(0)
        assertEquals(HomeCatalog.DAILY, call.key)
        assertEquals(accountScope, call.account)
        call.response.complete(CatalogPage(emptyList(), items = listOf(entry("daily-first"), entry("daily-second"))))
        waitUntil { !model.busy && model.catalogItems.size == 2 }
        instrumentation.runOnMainSync {
            assertEquals(HomeCatalog.DAILY, model.route.key)
            assertEquals("每日推荐", model.route.title)
            assertEquals(1, model.route.focus)
            assertEquals("PLAYLIST:daily-second", model.route.focusKey)
        }
    }

    @Test fun anExpiredSourceCannotRestorePrivateDetailsOrOldBackStackRoutes() {
        val expired = "expired-$fixtureId"
        restore(listOf(Route("home"), Route("music"),
            Route("playlists", HomeCatalog.PLAYLISTS, "歌单", plugin, accountScope = expired, musicRoot = true),
            Route("playlist", "private-expired", "Expired private playlist", plugin, accountScope = expired)),
            selection = MusicSourceSelection(plugin, expired))
        waitUntil { model.library.ready && model.route.source.isBlank() }
        instrumentation.runOnMainSync {
            assertNull(model.musicSource)
            assertNull(MusicSourceStore(sourcePreferences).read())
            assertTrue(model.remoteTracks.isEmpty())
            assertTrue(model.catalogItems.isEmpty())
            repeat(6) {
                model.back()
                assertEquals("No expired account route may return", "", model.route.source)
                assertNotEquals(expired, model.route.accountScope)
            }
        }
        assertTrue("An expired source must not request private catalog data", pages.requests.isEmpty())
    }

    @Test fun coverFlowRestoresTheSelectedCoverFromALaterPage() {
        restore(listOf(Route("home"), Route("music"),
            Route("coverflow", HomeCatalog.COVERS, "Cover Flow", plugin, accountScope = accountScope, musicRoot = true)),
            coverId = "cover-third")
        val first = request(0)
        assertEquals(HomeCatalog.COVERS, first.key)
        assertNull(first.cursor)
        first.response.complete(CatalogPage(emptyList(), nextCursor = "covers-next",
            items = listOf(entry("cover-first"), entry("cover-second"))))
        val second = request(1)
        assertEquals(HomeCatalog.COVERS, second.key)
        assertEquals("covers-next", second.cursor)
        assertEquals(accountScope, second.account)
        second.response.complete(CatalogPage(emptyList(), items = listOf(entry("cover-third"), entry("cover-fourth"))))
        waitUntil { !model.busy && model.selectedCover()?.id == "cover-third" }
        instrumentation.runOnMainSync {
            assertEquals(listOf("cover-first", "cover-second", "cover-third", "cover-fourth"), model.coverItems.map { it.id })
            assertEquals("cover-third", model.selectedCover()?.id)
            model.selectCover(0)
            assertEquals("playlist", model.route.id)
            assertEquals("cover-third", model.route.key)
            assertEquals(accountScope, model.route.accountScope)
        }
        val details = request(2)
        assertEquals("Details", details.operation)
        assertEquals("cover-third", details.key)
        details.response.complete(CatalogPage(emptyList()))
        waitUntil { !model.busy }
        instrumentation.runOnMainSync {
            model.back()
            assertEquals("coverflow", model.route.id)
            assertEquals("cover-third", model.selectedCover()?.id)
        }
        assertEquals(3, pages.requests.size)
    }

    @Test fun aFailedRecoveryPageKeepsLoadedCoversAndRetriesTheSameCursor() {
        restore(listOf(Route("home"), Route("music"),
            Route("coverflow", HomeCatalog.COVERS, "Cover Flow", plugin, accountScope = accountScope, musicRoot = true)),
            coverId = "cover-third")
        request(0).response.complete(CatalogPage(emptyList(), nextCursor = "covers-next",
            items = listOf(entry("cover-first"), entry("cover-second"))))
        val failed = request(1)
        assertEquals("covers-next", failed.cursor)
        failed.response.completeExceptionally(IllegalStateException("Recovery page unavailable"))
        waitUntil { !model.busy && model.remoteStatus.startsWith("读取失败") }
        instrumentation.runOnMainSync {
            assertEquals(listOf("cover-first", "cover-second"), model.coverItems.map { it.id })
            assertEquals("covers-next", model.nextCursor)
            model.page().rows.single { it.title == "重试" }.action()
        }
        val retry = request(2)
        assertEquals(HomeCatalog.COVERS, retry.key)
        assertEquals("covers-next", retry.cursor)
        assertEquals(accountScope, retry.account)
        retry.response.complete(CatalogPage(emptyList(), items = listOf(entry("cover-third"))))
        waitUntil { !model.busy && model.coverItems.size == 3 }
        instrumentation.runOnMainSync {
            assertEquals(listOf("cover-first", "cover-second", "cover-third"), model.coverItems.map { it.id })
            assertNull(model.nextCursor)
            assertFalse(model.remoteStatus.startsWith("读取失败"))
        }
        assertEquals(3, pages.requests.size)
    }

    @Test fun aRepeatedRecoveryCursorStopsWithoutOfferingTheSamePagesAgain() {
        restore(listOf(Route("home"), Route("music"),
            Route("coverflow", HomeCatalog.COVERS, "Cover Flow", plugin, accountScope = accountScope, musicRoot = true)),
            coverId = "removed-cover")
        request(0).response.complete(CatalogPage(emptyList(), nextCursor = "cursor-a", items = listOf(entry("cover-first"))))
        val pageA = request(1)
        assertEquals("cursor-a", pageA.cursor)
        pageA.response.complete(CatalogPage(emptyList(), nextCursor = "cursor-b", items = listOf(entry("cover-second"))))
        val pageB = request(2)
        assertEquals("cursor-b", pageB.cursor)
        pageB.response.complete(CatalogPage(emptyList(), nextCursor = "cursor-a", items = listOf(entry("cover-third"))))
        waitUntil { !model.busy && model.coverItems.size == 3 }
        instrumentation.runOnMainSync {
            assertEquals(listOf("cover-first", "cover-second", "cover-third"), model.coverItems.map { it.id })
            assertNull(model.nextCursor)
            assertFalse(model.page().rows.any { it.title == "加载更多" || it.title == "重试" })
        }
        assertEquals(3, pages.requests.size)
    }

    private fun restore(routes: List<Route>, selection: MusicSourceSelection = MusicSourceSelection(plugin, accountScope), coverId: String? = null) {
        MusicSourceStore(sourcePreferences).select(selection)
        SelectedAccountsStore(accountPreferences).select(plugin, accountScope)
        val state = SavedStateHandle(mapOf("navigation" to ArrayList(routes.map { it.toSavedBundle() })))
        if (coverId != null) state["coverAlbum"] = coverId
        instrumentation.runOnMainSync {
            // Startup restoration suspends in PluginManager.refresh before reading these stores or loading a page.
            model = AppModel(app, state)
            model.pageSource = pages
            model.musicSourceStore = MusicSourceStore(sourcePreferences)
            model.selectedAccountsStore = SelectedAccountsStore(accountPreferences)
            model.lockQuery = { false }
            viewModels.put("recovery", model)
        }
    }

    private fun request(index: Int): CatalogCall {
        waitUntil { pages.requests.size > index }
        return pages.requests[index]
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            var complete = false
            instrumentation.runOnMainSync { complete = condition() }
            if (complete) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for cold restoration")
    }

    private fun entry(id: String) = CatalogEntry("PLAYLIST", id, id, "Recovery fixture")

    private data class CatalogCall(val operation: String, val key: String?, val cursor: String?, val account: String,
        val response: CompletableDeferred<CatalogPage> = CompletableDeferred())

    private class ControlledPages : PageSource {
        val requests = CopyOnWriteArrayList<CatalogCall>()
        private suspend fun call(operation: String, key: String?, cursor: String?, account: String): CatalogPage {
            val request = CatalogCall(operation, key, cursor, account)
            requests += request
            return request.response.await()
        }
        override suspend fun browse(source: String, parent: String?, cursor: String?, account: String) = call("Browse", parent, cursor, account)
        override suspend fun search(source: String, query: String, cursor: String?, account: String): CatalogPage = error("Recovery must not search")
        override suspend fun details(source: String, key: String, cursor: String?, account: String) = call("Details", key, cursor, account)
        override suspend fun lyrics(track: Track?): List<LyricLine> = emptyList()
    }
}
