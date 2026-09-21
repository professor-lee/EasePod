package app.easepod.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.easepod.EasePodApplication
import app.easepod.core.Track
import app.easepod.netease.NeteaseMediaDomains
import app.easepod.netease.NeteasePlugin
import app.easepod.plugins.CatalogPage
import app.easepod.plugins.PluginException
import app.easepod.plugins.PluginPlaybackSource
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NeteasePublicDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as EasePodApplication

    @Test fun installedServiceIsAnExternalExportedPlugin() {
        assumeTrue("网易云插件 APK 未安装", runCatching {
            context.packageManager.getPackageInfo(NeteasePlugin.id, 0)
        }.isSuccess)
        val component = ComponentName(NeteasePlugin.id, NeteasePlugin.serviceClass)
        val service = context.packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0))
        assertTrue(service.exported)
        assertTrue(service.enabled)
        assertNotEquals(context.applicationInfo.uid, service.applicationInfo.uid)
        assertEquals(NeteasePlugin.id, service.packageName)
    }

    /** Run explicitly with instrumentation argument: -e neteaseLive true. */
    @Test fun livePublicCatalogSearchResolutionLyricsAndMedia3Playback() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("neteaseLive") != "true") return@runBlocking
        val deadline = SystemClock.elapsedRealtime() + 120_000L
        withTimeout(30_000L) { app.awaitStartupResources() }
        val originalSafeMode = app.settings.persistedSettings.first().safeMode
        val registered = app.plugins.plugins.value.singleOrNull { it.id == NeteasePlugin.id }
        assumeTrue("网易云插件 APK 未安装", registered != null)
        assertFalse(registered!!.builtin)
        assertTrue(registered.approved)
        val originalEnabled = registered.enabled
        var engine: ExoPlayer? = null
        try {
            withTimeout((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                app.plugins.setSafeMode(false)
                if (!originalEnabled) app.plugins.setEnabled(NeteasePlugin.id, true)
                val browse = app.plugins.browse(NeteasePlugin.id, pageSize = 10, accountScope = "public")
                val playlist = browse.items.firstOrNull { it.kind == "PLAYLIST" && it.remoteId.startsWith("playlist:") }
                    ?: throw AssertionError("Live public browse returned no recommended playlists")
                val details = app.plugins.details(NeteasePlugin.id, playlist.remoteId, pageSize = 30, accountScope = "public")
                assertTrue("Recommended playlist returned no tracks", details.tracks.isNotEmpty())
                val query = details.tracks.firstOrNull { it.available }?.title?.take(64)
                    ?: throw AssertionError("Recommended playlist has no available public tracks")
                val search = app.plugins.search(NeteasePlugin.id, query, pageSize = 10, accountScope = "public")
                assertTrue("Live search returned no tracks for a catalog title", search.tracks.isNotEmpty())
                val (selected, source) = resolveAvailable((details.tracks + search.tracks).distinctBy { it.remoteId }.filter { it.available }.take(12))
                assertEquals("public", selected.accountScope)
                assertEquals("NO_STORE", source.cachePolicy)
                assertTrue(source.uri.startsWith("https://"))
                assertTrue(source.headers.keys.none { it.equals("cookie", true) || it.equals("authorization", true) })
                assertTrue(source.quality in setOf("standard", "high", "lossless", "hires"))
                val lyrics = app.plugins.lyricsPage(selected)
                assertTrue(lyrics.lines.size <= 50)
                lyrics.nextCursor?.let { assertTrue(app.plugins.lyricsPage(selected, it).lines.size <= 50) }

                val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
                    .cache(null).followRedirects(false).followSslRedirects(false)
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val url = chain.request().url
                        if (!url.isHttps || url.port != 443 || url.host !in source.allowedHosts ||
                            NeteaseMediaDomains.httpsUrl(url.toString()) == null) throw IOException("Media URL outside the resolved HTTPS policy")
                        chain.proceed(chain.request())
                    }.build()
                val factory = OkHttpDataSource.Factory(client).setDefaultRequestProperties(source.headers)
                main {
                    engine = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                        volume = 0f
                        setMediaItem(MediaItem.fromUri(source.uri))
                        prepare()
                        play()
                    }
                }
                await(deadline) {
                    var started = false
                    main {
                        val player = requireNotNull(engine)
                        assertNull("Live Media3 playback failed: ${player.playerError?.errorCodeName}", player.playerError)
                        started = player.isPlaying && player.currentPosition >= 750L
                    }
                    started
                }
                main { requireNotNull(engine).pause() }
                var stoppedAt = 0L
                main { assertFalse(requireNotNull(engine).isPlaying); stoppedAt = requireNotNull(engine).currentPosition }
                delay(300L)
                main {
                    assertFalse("Pause must stop live playback", requireNotNull(engine).isPlaying)
                    assertTrue("Paused playback position advanced", requireNotNull(engine).currentPosition <= stoppedAt + 100L)
                }
            }
        } finally {
            main { engine?.release() }
            withTimeout(10_000L) {
                if (!originalEnabled) app.plugins.setEnabled(NeteasePlugin.id, false)
                app.plugins.setSafeMode(originalSafeMode)
            }
        }
    }

    /** Run after QR login with instrumentation argument: -e neteaseAccountLive true. */
    @Test fun liveSignedInAccountCatalogFavoriteGetterResolutionAndLyricsAreReadOnly() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("neteaseAccountLive") != "true") return@runBlocking
        withTimeout(30_000L) { app.awaitStartupResources() }
        val originalSafeMode = app.settings.persistedSettings.first().safeMode
        val registered = app.plugins.plugins.value.singleOrNull { it.id == NeteasePlugin.id }
            ?: throw AssertionError("Bundled Netease service is missing from the real host registry")
        val originalEnabled = registered.enabled
        try {
            withTimeout(150_000L) {
                app.plugins.setSafeMode(false)
                if (!originalEnabled) app.plugins.setEnabled(NeteasePlugin.id, true)
                val account = app.plugins.accounts(NeteasePlugin.id).firstOrNull { it.state == "SignedIn" }
                    ?: throw AssertionError("The account live test requires a verified QR login")
                val scope = account.scope
                assertTrue("Verified account must use a private host scope", scope.isNotBlank() && scope !in setOf("public", "local"))
                val root = app.plugins.browse(NeteasePlugin.id, pageSize = 10, accountScope = scope)
                assertTrue("Private account categories are missing", root.items.map { it.remoteId }
                    .containsAll(listOf("category:playlists", "category:favorites", "category:daily")))

                suspend fun browsePages(parent: String, pageSize: Int): List<CatalogPage> {
                    val first = app.plugins.browse(NeteasePlugin.id, parent, pageSize = pageSize, accountScope = scope)
                    assertAccountPage(first, scope, pageSize)
                    val pages = mutableListOf(first)
                    first.nextCursor?.let { cursor ->
                        assertTrue("Private catalog returned an empty cursor", cursor.isNotBlank())
                        val next = app.plugins.browse(NeteasePlugin.id, parent, cursor, pageSize, scope)
                        assertAccountPage(next, scope, pageSize)
                        assertTrue("Private catalog repeated its cursor", next.nextCursor != cursor)
                        pages += next
                    }
                    return pages
                }

                val playlistPages = browsePages("category:playlists", 2)
                val favoritePages = browsePages("category:favorites", 5)
                val dailyPages = browsePages("category:daily", 5)
                val candidates = (favoritePages + dailyPages).flatMap { it.tracks }.toMutableList()
                val playlists = playlistPages.flatMap { it.items }
                    .filter { it.kind == "PLAYLIST" && it.remoteId.startsWith("playlist:") }
                    .distinctBy { it.remoteId }.take(2)
                for (playlist in playlists) {
                    val first = app.plugins.details(NeteasePlugin.id, playlist.remoteId, pageSize = 2, accountScope = scope)
                    assertAccountPage(first, scope, 2)
                    candidates += first.tracks
                    first.nextCursor?.let { cursor ->
                        assertTrue("Account playlist returned an empty cursor", cursor.isNotBlank())
                        val next = app.plugins.details(NeteasePlugin.id, playlist.remoteId, cursor, 2, scope)
                        assertAccountPage(next, scope, 2)
                        assertTrue("Account playlist repeated its cursor", next.nextCursor != cursor)
                        candidates += next.tracks
                    }
                }

                // Empty personal collections are valid; a public song can still be read in this account scope.
                if (candidates.isEmpty()) {
                    val fallback = app.plugins.details(NeteasePlugin.id, "347230", pageSize = 1, accountScope = scope)
                    assertAccountPage(fallback, scope, 1)
                    candidates += fallback.tracks
                }
                val favoriteCandidate = favoritePages.flatMap { it.tracks }.firstOrNull()
                val target = favoriteCandidate?.remoteId ?: candidates.firstOrNull()?.remoteId ?: "347230"
                val favorite = app.plugins.cloudLibrary.favorite(NeteasePlugin.id, scope, target)
                if (favoriteCandidate != null) assertTrue("A listed favorite was not confirmed by the real getter", favorite)
                else if (favoritePages.all { it.tracks.isEmpty() } && favoritePages.last().nextCursor == null) {
                    assertFalse("An empty favorite collection must not report a favorite", favorite)
                }

                val resolved = resolveAccountCandidate(candidates.distinctBy { it.remoteId }.filter { it.available }.take(8))
                if (resolved != null) {
                    val (track, source) = resolved
                    assertTrue("Resolved track left the verified account scope", track.accountScope == scope)
                    assertEquals("NO_STORE", source.cachePolicy)
                    assertTrue("Account media must pass the HTTPS CDN policy", NeteaseMediaDomains.httpsUrl(source.uri) == source.uri)
                    assertTrue("Account media must not forward credentials", source.headers.keys.none {
                        it.equals("cookie", true) || it.equals("authorization", true)
                    })
                    assertTrue(source.quality in setOf("standard", "high", "lossless", "hires"))
                    val lyrics = app.plugins.lyricsPage(track)
                    assertTrue(lyrics.lines.size <= 50)
                    lyrics.nextCursor?.let { cursor ->
                        val next = app.plugins.lyricsPage(track, cursor)
                        assertTrue(next.lines.size <= 50)
                        assertTrue("Account lyrics repeated their cursor", next.nextCursor != cursor)
                    }
                }
            }
        } finally {
            withTimeout(10_000L) {
                if (!originalEnabled) app.plugins.setEnabled(NeteasePlugin.id, false)
                app.plugins.setSafeMode(originalSafeMode)
            }
        }
    }

    private fun assertAccountPage(page: CatalogPage, scope: String, pageSize: Int) {
        assertTrue("Account catalog exceeded its requested page size", page.items.size + page.tracks.size <= pageSize)
        assertTrue("Account catalog returned tracks from a different scope", page.tracks.all { it.accountScope == scope })
    }

    private suspend fun resolveAccountCandidate(candidates: List<Track>): Pair<Track, PluginPlaybackSource>? {
        for (track in candidates) {
            try { return track to app.plugins.resolve(track, "standard") }
            catch (error: PluginException) {
                if (error.code !in setOf("EntitlementRequired", "Unavailable", "RegionRestricted", "NotFound")) throw error
            }
        }
        return null
    }

    private suspend fun resolveAvailable(candidates: List<Track>): Pair<Track, PluginPlaybackSource> {
        val unavailable = mutableListOf<String>()
        for (track in candidates) {
            try { return track to app.plugins.resolve(track, "standard") }
            catch (error: PluginException) {
                if (error.code !in setOf("EntitlementRequired", "Unavailable", "RegionRestricted", "NotFound")) throw error
                unavailable += error.code
            }
        }
        throw AssertionError("No playable public track among ${candidates.size} candidates: $unavailable")
    }

    private suspend fun await(deadline: Long, predicate: () -> Boolean) {
        while (!predicate()) {
            assertTrue("Timed out waiting for live Netease media playback", SystemClock.elapsedRealtime() < deadline)
            delay(25L)
        }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
}
