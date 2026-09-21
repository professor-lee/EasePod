package app.easepod.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.easepod.MainActivity
import app.easepod.core.AppSettings
import app.easepod.core.Track
import app.easepod.core.WheelKey
import app.easepod.plugins.CatalogEntry
import app.easepod.plugins.CatalogPage
import app.easepod.plugins.LyricLine
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PageRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: AppModel
    private lateinit var originalSettings: AppSettings
    private lateinit var originalSource: PageSource
    private var originalLock: (() -> Boolean)? = null
    private val source = ControlledPageSource()

    @Before fun prepare() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppModel::class.java] }
        runBlocking {
            model.app.awaitStartupResources()
            originalSettings = model.app.settings.persistedSettings.first()
            model.app.settings.update { it.copy(touchGuard = true, safeMode = false, reducedMotion = true, clickSound = false, haptics = false) }
        }
        compose.waitUntil(10_000) { model.library.ready && model.settings.touchGuard && !model.settings.safeMode }
        compose.runOnIdle {
            originalSource = model.pageSource; originalLock = model.lockQuery
            model.pageSource = source; model.lockQuery = { false }; model.refreshLock(false); model.home()
        }
    }

    @After fun restore() {
        compose.runOnIdle {
            model.home(); model.pageSource = originalSource; model.lockQuery = originalLock
            model.refreshLock(originalLock?.invoke() ?: false)
        }
        runBlocking { model.app.settings.update { originalSettings } }
    }

    @Test fun guardedLyricsFailureCanRetryWithThePhysicalCenterRegion() {
        compose.runOnIdle { model.navigate(Route("lyrics")) }
        compose.waitUntil { source.lyrics.size == 1 }
        source.lyrics[0].completeExceptionally(IllegalStateException("Temporary lyrics failure"))
        compose.waitUntil { model.lyricText == "歌词读取失败" }
        compose.runOnIdle { assertEquals(listOf("重试"), model.page().rows.map { it.title }) }
        compose.onNode(hasText("重试") and hasClickAction()).performTouchInput { click() }
        assertEquals("Guarded LCD touches must not issue a request", 1, source.lyrics.size)
        compose.onNodeWithContentDescription("中心，确认").performTouchInput { click() }
        compose.waitUntil { source.lyrics.size == 2 }
        source.lyrics[1].complete(listOf(LyricLine(0, "Recovered lyrics")))
        compose.waitUntil { model.lyricLines.size == 1 }
        compose.runOnIdle { assertEquals("Recovered lyrics", model.lyricLines.single().text); assertEquals("", model.lyricText) }
    }

    @Test fun failedSearchAppendRetriesTheSameCursorAfterReturningToThePage() {
        compose.runOnIdle { model.navigate(Route("search", source = "fixture")); model.inputChanged("albums") }
        request(0).response.complete(page("first", cursor = "next-page"))
        compose.waitUntil { model.catalogItems.size == 1 && model.searchStatus != "正在搜索" }
        choose("加载更多")
        assertEquals("next-page", request(1).cursor)
        request(1).response.completeExceptionally(IllegalStateException("Temporary page failure"))
        compose.waitUntil { model.searchStatus.startsWith("搜索失败") }
        compose.runOnIdle {
            assertEquals(listOf("first"), model.catalogItems.map { it.remoteId })
            model.navigate(Route("settings")); model.back()
            assertEquals("search", model.route.id)
            assertEquals("albums", model.route.draft)
        }
        choose("重试")
        assertEquals("Search", request(2).operation)
        assertEquals("next-page", request(2).cursor)
        request(2).response.complete(page("second"))
        compose.waitUntil { model.catalogItems.size == 2 }
        compose.runOnIdle { assertEquals(listOf("first", "second"), model.catalogItems.map { it.remoteId }) }
    }

    @Test fun albumArtistAndPlaylistUseDetailsIncludingTheirNextPage() {
        val entries = listOf("ALBUM", "ARTIST", "PLAYLIST").map { CatalogEntry(it, "key-$it", it) }
        compose.runOnIdle { model.navigate(Route("search", source = "fixture", accountScope = "scope-a")); model.inputChanged("catalog") }
        request(0).response.complete(CatalogPage(emptyList(), items = entries))
        compose.waitUntil { model.catalogItems.size == 3 }
        var index = 1
        entries.forEach { entry ->
            choose(entry.title)
            val first = request(index++)
            assertEquals("Details", first.operation)
            assertEquals(entry.remoteId, first.key)
            assertEquals("scope-a", first.account)
            first.response.complete(page("child", cursor = "details-next"))
            compose.waitUntil { !model.busy }
            choose("加载更多")
            val next = request(index++)
            assertEquals("Details", next.operation)
            assertEquals("details-next", next.cursor)
            next.response.complete(page("other-child"))
            compose.waitUntil { !model.busy && model.catalogItems.size == 2 }
            compose.runOnIdle { model.back(); assertEquals("search", model.route.id) }
        }
        assertFalse(source.requests.any { it.operation == "Browse" })
    }

    @Test fun relockCancelsPendingReadsAndRetryWorksWhileStillLocked() {
        compose.runOnIdle { model.navigate(Route("search", source = "fixture")); model.inputChanged("before-lock") }
        val staleSearch = request(0)
        compose.runOnIdle { model.lockQuery = { true }; model.refreshLock(true) }
        compose.waitUntil { model.searchStatus.startsWith("搜索失败") }
        staleSearch.response.complete(page("stale-search"))
        choose("重试")
        request(1).response.complete(page("fresh-search"))
        compose.waitUntil { model.catalogItems.any { it.remoteId == "fresh-search" } }
        compose.runOnIdle {
            assertTrue(model.locked)
            assertEquals(listOf("fresh-search"), model.catalogItems.map { it.remoteId })
            model.lockQuery = { false }; model.refreshLock(false)
            model.navigate(Route("songs", source = "fixture"))
        }
        val staleBrowse = request(2)
        compose.runOnIdle { model.lockQuery = { true }; model.refreshLock(true) }
        compose.waitUntil { !model.busy && model.remoteStatus.startsWith("读取失败") }
        staleBrowse.response.complete(page("stale-browse"))
        choose("重试")
        request(3).response.complete(page("fresh-browse"))
        compose.waitUntil { !model.busy && model.catalogItems.any { it.remoteId == "fresh-browse" } }
        compose.runOnIdle { assertTrue(model.locked); assertEquals(listOf("fresh-browse"), model.catalogItems.map { it.remoteId }) }
    }

    private fun choose(title: String) = compose.runOnIdle {
        val index = model.page().rows.indexOfFirst { it.title == title }
        assertTrue("Missing action $title on ${model.route.id}", index >= 0)
        model.focus(index); model.key(WheelKey.CENTER)
    }

    private fun request(index: Int): CatalogCall {
        compose.waitUntil(5_000) { source.requests.size > index }
        return source.requests[index]
    }

    private fun page(id: String, cursor: String? = null) = CatalogPage(emptyList(), nextCursor = cursor,
        items = listOf(CatalogEntry("ALBUM", id, id)))

    private data class CatalogCall(val operation: String, val key: String?, val cursor: String?, val account: String,
        val response: CompletableDeferred<CatalogPage?> = CompletableDeferred())

    private class ControlledPageSource : PageSource {
        val requests = CopyOnWriteArrayList<CatalogCall>()
        val lyrics = CopyOnWriteArrayList<CompletableDeferred<List<LyricLine>>>()
        private suspend fun call(operation: String, key: String?, cursor: String?, account: String): CatalogPage? {
            val call = CatalogCall(operation, key, cursor, account)
            requests += call
            return call.response.await()
        }
        override suspend fun browse(source: String, parent: String?, cursor: String?, account: String) = requireNotNull(call("Browse", parent, cursor, account))
        override suspend fun search(source: String, query: String, cursor: String?, account: String) = requireNotNull(call("Search", query, cursor, account))
        override suspend fun details(source: String, key: String, cursor: String?, account: String) = call("Details", key, cursor, account)
        override suspend fun lyrics(track: Track?): List<LyricLine> {
            val response = CompletableDeferred<List<LyricLine>>()
            lyrics += response
            return response.await()
        }
    }
}
