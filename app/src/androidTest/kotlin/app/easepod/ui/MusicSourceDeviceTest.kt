package app.easepod.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.easepod.MainActivity
import app.easepod.contract.HomeCatalog
import app.easepod.core.AppSettings
import app.easepod.core.Track
import app.easepod.core.WheelKey
import app.easepod.plugins.CatalogEntry
import app.easepod.plugins.CatalogPage
import app.easepod.plugins.LyricLine
import app.easepod.plugins.PluginAccount
import app.easepod.plugins.PluginAuthSession
import app.easepod.plugins.PluginException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class MusicSourceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AppModel
    private lateinit var originalSettings: AppSettings
    private lateinit var originalPageSource: PageSource
    private lateinit var originalAccountSource: AccountSource
    private lateinit var originalSelectedAccountsStore: SelectedAccountsStore
    private lateinit var originalMusicSourceStore: MusicSourceStore
    private lateinit var originalSelectedAccounts: Map<String, String>
    private lateinit var sourcePreferences: SharedPreferences
    private var originalMusicSource: MusicSourceSelection? = null
    private var originalLock: (() -> Boolean)? = null
    private var originalEnabled = true
    private val preferenceName = "music-source-test-${UUID.randomUUID()}"
    private val plugin = "app.easepod.netease"
    private val accounts = ControlledAccounts()
    private val pages = ControlledPages()

    @Before fun prepare() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppModel::class.java] }
        runBlocking {
            model.app.awaitStartupResources()
            assumeTrue("网易云插件未安装，跳过可选插件来源测试", model.app.plugins.plugins.value.any { it.id == plugin })
            originalSettings = model.app.settings.persistedSettings.first()
            originalEnabled = model.app.plugins.plugins.value.single { it.id == plugin }.enabled
            model.app.settings.update { it.copy(touchGuard = true, safeMode = false, reducedMotion = true, clickSound = false, haptics = false) }
            model.app.plugins.setEnabled(plugin, true)
        }
        compose.waitUntil(10_000) {
            model.library.ready && model.settings.touchGuard && !model.settings.safeMode &&
                model.pluginList.any { it.id == plugin && it.enabled && HomeCatalog.CAPABILITY in it.capabilities }
        }
        compose.runOnIdle {
            originalPageSource = model.pageSource
            originalAccountSource = model.accountSource
            originalLock = model.lockQuery
            originalSelectedAccountsStore = model.selectedAccountsStore
            originalMusicSourceStore = model.musicSourceStore
            originalSelectedAccounts = model.selectedAccounts.toMap()
            originalMusicSource = model.musicSource
            sourcePreferences = compose.activity.getSharedPreferences("$preferenceName-source", Context.MODE_PRIVATE)
            model.selectedAccountsStore = SelectedAccountsStore(compose.activity.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
            model.musicSourceStore = MusicSourceStore(sourcePreferences)
            model.pageSource = pages
            model.accountSource = accounts
            model.lockQuery = { false }
            model.refreshLock(false)
            model.home()
            model.selectedAccounts.clear()
            model.selectMusicSource(null)
        }
    }

    @After fun restore() {
        if (!::originalPageSource.isInitialized) return
        compose.runOnIdle {
            model.home()
            model.selectMusicSource(null)
            pages.requests.forEach { it.response.complete(CatalogPage(emptyList())) }
        }
        runBlocking { model.app.plugins.setEnabled(plugin, originalEnabled) }
        compose.waitUntil(10_000) { model.pluginList.single { it.id == plugin }.enabled == originalEnabled }
        compose.runOnIdle {
            model.pageSource = originalPageSource
            model.accountSource = originalAccountSource
            model.selectedAccounts.clear()
            model.selectedAccounts.putAll(originalSelectedAccounts)
            model.selectMusicSource(originalMusicSource)
            model.selectedAccountsStore = originalSelectedAccountsStore
            model.musicSourceStore = originalMusicSourceStore
            model.lockQuery = originalLock
            model.refreshLock(originalLock?.invoke() ?: false)
            compose.activity.deleteSharedPreferences(preferenceName)
            compose.activity.deleteSharedPreferences("$preferenceName-source")
        }
        runBlocking { model.app.settings.update { originalSettings } }
    }

    @Test fun verifiedLoginReplacesMainCatalogAndSearchWithTheAccountCatalog() {
        compose.runOnIdle { model.navigate(Route("login", key = plugin)) }
        choose("继续授权")
        compose.waitUntil(5_000) { model.musicSource?.accountScope == accounts.first.scope }
        compose.runOnIdle {
            assertEquals(MusicSourceSelection(plugin, accounts.first.scope), MusicSourceStore(sourcePreferences).read())
            assertEquals(accounts.first.scope, model.selectedAccounts[plugin])
            model.home()
        }
        choose("音乐")
        choose("Cover Flow")
        completeBrowse(0, HomeCatalog.COVERS, "main-cover")
        compose.runOnIdle { assertEquals(listOf("main-cover"), model.coverItems.map { it.id }); model.home() }

        listOf("歌单" to HomeCatalog.PLAYLISTS, "歌曲" to HomeCatalog.TRACKS,
            "专辑" to HomeCatalog.ALBUMS, "艺人" to HomeCatalog.ARTISTS,
            "流派" to HomeCatalog.GENRES).forEachIndexed { index, (title, key) ->
            choose("音乐")
            choose(title)
            completeBrowse(index + 1, key, "main-$index")
            compose.runOnIdle {
                assertTrue(model.page().rows.any { it.title == "main-$index" })
                model.home()
            }
        }
        choose("音乐")
        choose("搜索")
        compose.runOnIdle {
            assertEquals(plugin, model.route.source)
            assertEquals(accounts.first.scope, model.route.accountScope)
            model.inputChanged("fixture query")
        }
        val search = request(6)
        assertEquals("Search", search.operation)
        assertEquals("fixture query", search.key)
        assertAccount(search, accounts.first.scope)
        search.response.complete(page("search-result"))
        compose.waitUntil { model.catalogItems.any { it.remoteId == "search-result" } }
    }

    @Test fun sourceMenuSwitchesBetweenVerifiedAccountAndLocalMusic() {
        selectAccount(accounts.first)
        compose.runOnIdle { model.home() }
        choose("音乐")
        choose("音乐来源")
        chooseDialog("本地音乐")
        compose.runOnIdle {
            assertNull(model.musicSource)
            assertNull(MusicSourceStore(sourcePreferences).read())
            model.home()
        }
        choose("音乐")
        choose("Cover Flow")
        compose.runOnIdle {
            assertEquals("", model.route.source)
            assertEquals(model.library.albums.map { it.id }, model.coverItems.map { it.id })
            model.home()
        }
        choose("音乐")
        choose("歌单")
        compose.runOnIdle {
            assertEquals("", model.route.source)
            assertTrue(model.page().rows.any { it.title == "新建歌单" })
            model.home()
        }
        choose("音乐")
        choose("搜索")
        compose.runOnIdle { assertEquals("", model.route.source); model.home() }
        assertTrue(pages.requests.isEmpty())
        choose("音乐")
        choose("音乐来源")
        compose.runOnIdle {
            val serviceName = model.pluginList.single { it.id == plugin }.name
            val row = model.dialog!!.rows.single { it.title == serviceName }
            row.action()
            assertEquals(MusicSourceSelection(plugin, accounts.first.scope), model.musicSource)
        }
        choose("歌曲")
        completeBrowse(0, HomeCatalog.TRACKS, "cloud-again")
    }

    @Test fun changingAccountInvalidatesPendingPagesAndOldBackStackSnapshots() {
        selectAccount(accounts.first)
        compose.runOnIdle { model.home() }
        choose("音乐")
        choose("歌单")
        completeBrowse(0, HomeCatalog.PLAYLISTS, "private-first")
        choose("private-first")
        val stale = request(1)
        assertEquals("Details", stale.operation)
        assertAccount(stale, accounts.first.scope)
        selectAccount(accounts.second)
        stale.response.complete(page("stale-private-first"))
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(accounts.second.scope, model.musicSource?.accountScope)
            assertFalse(model.catalogItems.any { it.remoteId == "stale-private-first" })
            repeat(6) {
                model.back()
                assertFalse("An old account route must not be revived", model.route.source == plugin && model.route.accountScope == accounts.first.scope)
                assertFalse(model.catalogItems.any { it.remoteId == "private-first" || it.remoteId == "stale-private-first" })
            }
            model.home()
        }
        choose("音乐")
        choose("歌单")
        completeBrowse(2, HomeCatalog.PLAYLISTS, "private-second", accounts.second.scope)
    }

    @Test fun coverFlowOpensPlaylistAndPreservesSelectionThroughDetailsAndAppendRetry() {
        selectAccount(accounts.first)
        compose.runOnIdle { model.home() }
        choose("音乐")
        choose("Cover Flow")
        val first = request(0)
        assertEquals(HomeCatalog.COVERS, first.key)
        first.response.complete(CatalogPage(emptyList(), nextCursor = "cover-next",
            items = listOf(entry("cover-one"), entry("cover-two"))))
        compose.waitUntil { !model.busy && model.coverItems.size == 2 }
        compose.runOnIdle { model.selectCover(1) }
        val append = request(1)
        assertEquals("cover-next", append.cursor)
        append.response.completeExceptionally(IllegalStateException("Temporary cover failure"))
        compose.waitUntil { !model.busy && model.remoteStatus.startsWith("读取失败") }
        choose("重试")
        val retry = request(2)
        assertEquals(HomeCatalog.COVERS, retry.key)
        assertEquals("cover-next", retry.cursor)
        retry.response.complete(page("cover-three"))
        compose.waitUntil { !model.busy && model.coverItems.size == 3 }
        compose.runOnIdle {
            assertEquals("cover-two", model.selectedAlbum()?.id)
            model.selectCover(0)
            assertEquals("playlist", model.route.id)
            assertEquals("cover-two", model.route.key)
            assertEquals(accounts.first.scope, model.route.accountScope)
        }
        val details = request(3)
        assertEquals("Details", details.operation)
        assertEquals("cover-two", details.key)
        details.response.complete(CatalogPage(emptyList()))
        compose.waitUntil { !model.busy }
        compose.runOnIdle {
            model.back()
            assertEquals("coverflow", model.route.id)
            assertEquals("cover-two", model.selectedAlbum()?.id)
            assertEquals(listOf("cover-one", "cover-two", "cover-three"), model.coverItems.map { it.id })
        }
        assertEquals(4, pages.requests.size)
    }

    @Test fun disablingSelectedPluginFallsBackToLocalAndDropsPrivateContent() {
        selectAccount(accounts.first)
        compose.runOnIdle { model.home() }
        choose("音乐")
        choose("Cover Flow")
        completeBrowse(0, HomeCatalog.COVERS, "private-cover")
        runBlocking { model.app.plugins.setEnabled(plugin, false) }
        compose.waitUntil(10_000) { model.pluginList.none { it.id == plugin && it.enabled } && model.musicSource == null }
        compose.runOnIdle {
            assertFalse(model.coverItems.any { it.id == "private-cover" })
            assertNull(MusicSourceStore(sourcePreferences).read())
            model.home()
        }
        choose("音乐")
        choose("Cover Flow")
        compose.runOnIdle { assertEquals("", model.route.source) }
        assertEquals(1, pages.requests.size)
    }

    @Test fun expiredCatalogAccountFallsBackToLocalAndClearsItsSelection() {
        selectAccount(accounts.first)
        compose.runOnIdle { model.home() }
        choose("音乐")
        choose("歌曲")
        request(0).response.completeExceptionally(PluginException("AuthExpired"))
        compose.waitUntil { !model.busy && model.musicSource == null }
        compose.runOnIdle {
            assertEquals("", model.route.source)
            assertNull(model.selectedAccounts[plugin])
            assertNull(MusicSourceStore(sourcePreferences).read())
            assertTrue(model.remoteTracks.isEmpty())
            assertEquals("账号需要重新登录", model.notice)
        }
    }

    private fun selectAccount(account: PluginAccount) {
        accounts.signedIn = true
        compose.runOnIdle { model.navigate(Route("login", key = plugin)) }
        compose.waitUntil { model.accounts.any { it.scope == account.scope } }
        choose(account.displayName)
        chooseDialog("使用此账号")
        compose.runOnIdle { assertEquals(MusicSourceSelection(plugin, account.scope), model.musicSource) }
    }

    private fun choose(title: String) = compose.runOnIdle {
        val index = model.page().rows.indexOfFirst { it.title == title }
        assertTrue("Missing action $title on ${model.route.id}", index >= 0)
        model.focus(index)
        model.key(WheelKey.CENTER)
    }

    private fun chooseDialog(title: String) = compose.runOnIdle {
        val index = model.dialog!!.rows.indexOfFirst { it.title == title }
        assertTrue("Missing dialog action $title", index >= 0)
        model.dialogActivate(index)
    }

    private fun completeBrowse(index: Int, key: String, id: String, account: String = accounts.first.scope) {
        val call = request(index)
        assertEquals("Browse", call.operation)
        assertEquals(key, call.key)
        assertAccount(call, account)
        call.response.complete(page(id))
        compose.waitUntil { !model.busy && model.catalogItems.any { it.remoteId == id } }
    }

    private fun assertAccount(call: CatalogCall, account: String) {
        assertEquals(plugin, call.source)
        assertEquals(account, call.account)
    }

    private fun request(index: Int): CatalogCall {
        compose.waitUntil(5_000) { pages.requests.size > index }
        return pages.requests[index]
    }

    private fun entry(id: String) = CatalogEntry("PLAYLIST", id, id, "Fixture account")
    private fun page(id: String) = CatalogPage(emptyList(), items = listOf(entry(id)))

    private data class CatalogCall(val operation: String, val source: String, val key: String?, val cursor: String?, val account: String,
        val response: CompletableDeferred<CatalogPage?> = CompletableDeferred())

    private class ControlledPages : PageSource {
        val requests = CopyOnWriteArrayList<CatalogCall>()
        private suspend fun call(operation: String, source: String, key: String?, cursor: String?, account: String): CatalogPage? {
            val call = CatalogCall(operation, source, key, cursor, account)
            requests += call
            // Late connector responses must be ignored even when cancellation cannot stop the transport.
            return withContext(NonCancellable) { call.response.await() }
        }
        override suspend fun browse(source: String, parent: String?, cursor: String?, account: String) = requireNotNull(call("Browse", source, parent, cursor, account))
        override suspend fun search(source: String, query: String, cursor: String?, account: String) = requireNotNull(call("Search", source, query, cursor, account))
        override suspend fun details(source: String, key: String, cursor: String?, account: String) = call("Details", source, key, cursor, account)
        override suspend fun lyrics(track: Track?): List<LyricLine> = emptyList()
    }

    private class ControlledAccounts : AccountSource {
        val first = PluginAccount("fixture-first", "First fixture account", "fixture-first-scope", "SignedIn")
        val second = PluginAccount("fixture-second", "Second fixture account", "fixture-second-scope", "SignedIn")
        var signedIn = false
        override suspend fun accounts(plugin: String) = if (signedIn) listOf(first, second) else emptyList()
        override suspend fun beginAuth(plugin: String): PluginAuthSession {
            signedIn = true
            return PluginAuthSession("fixture-auth", "SignedIn", account = first)
        }
        override suspend fun pollAuth(plugin: String, session: String): PluginAuthSession = error("Already verified")
        override suspend fun cancelAuth(plugin: String, session: String) = Unit
        override suspend fun signOut(plugin: String, account: String): Unit = error("Read-only fixture must not sign out")
    }
}
