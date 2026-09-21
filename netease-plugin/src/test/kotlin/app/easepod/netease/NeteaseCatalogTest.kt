package app.easepod.netease

import app.easepod.contract.HomeCatalog
import app.easepod.contract.RequestEnvelope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseCatalogTest {
    private val cookies = NeteaseCookies()

    @Test fun publicRecommendationsMapTypedIdsAndHttpsArtwork() = runBlocking {
        val api = FakeApi { _, _ -> json("""{"code":200,"result":[{"id":21,"name":"Mix","picUrl":"http://p1.music.126.net/cover.jpg"}]}""") }
        val result = NeteaseCatalog(api).handle(request(), cookies, null)
        assertNull(result.errorCode)
        assertEquals("/api/personalized/playlist", api.calls.single().path)
        assertEquals("PLAYLIST", result.items.single().kind)
        assertEquals("playlist:21", result.items.single().remoteId)
        assertEquals("https://p1.music.126.net/cover.jpg", result.items.single().artworkUrl)
    }

    @Test fun privateRootUsesContractContainerKindsAndPublicCannotOpenPrivateCategories() = runBlocking {
        val api = FakeApi { _, _ -> error("No request expected") }
        val catalog = NeteaseCatalog(api)
        val root = catalog.handle(request().copy(accountContext = "account-7"), cookies, "7")
        assertEquals(listOf("category:playlists", "category:favorites", "category:daily", "category:recommended"), root.items.map { it.remoteId })
        assertTrue(root.items.all { it.kind == "PLAYLIST" })
        listOf("category:playlists", "category:favorites", "category:daily").forEach { parent ->
            assertEquals("AuthRequired", catalog.handle(request().copy(remoteId = parent), cookies, null).errorCode)
        }
        assertEquals("AuthRequired", catalog.handle(request().copy(accountContext = "account-7"), cookies, null).errorCode)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun homeCatalogRequiresAnAccountAndOffersOnlySupportedContainers() = runBlocking {
        val api = FakeApi { _, _ -> error("No request expected") }
        val catalog = NeteaseCatalog(api)
        val home = request().copy(remoteId = HomeCatalog.ROOT, accountContext = "account-7")
        val result = catalog.handle(home, cookies, "7")
        assertNull(result.errorCode)
        assertEquals(listOf(HomeCatalog.PLAYLISTS, HomeCatalog.FAVORITES, HomeCatalog.DAILY,
            HomeCatalog.ALBUMS, HomeCatalog.ARTISTS, "category:recommended"), result.items.map { it.remoteId })
        assertEquals("ALBUM", result.items.first { it.remoteId == HomeCatalog.ALBUMS }.kind)
        assertEquals("ARTIST", result.items.first { it.remoteId == HomeCatalog.ARTISTS }.kind)
        listOf(HomeCatalog.ROOT, HomeCatalog.COVERS, HomeCatalog.PLAYLISTS, HomeCatalog.TRACKS,
            HomeCatalog.ALBUMS, HomeCatalog.ARTISTS, HomeCatalog.GENRES, HomeCatalog.FAVORITES,
            HomeCatalog.DAILY).forEach { parent ->
            assertEquals("AuthRequired", catalog.handle(request().copy(remoteId = parent), cookies, null).errorCode)
            assertEquals("AuthRequired", catalog.handle(home.copy(remoteId = parent), cookies, null).errorCode)
        }
        assertEquals("Unsupported", catalog.handle(home.copy(remoteId = HomeCatalog.GENRES), cookies, "7").errorCode)
        assertEquals("Unsupported", catalog.handle(home.copy(remoteId = "home:unknown"), cookies, "7").errorCode)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun homeCoverAndPlaylistPagesShareProviderDataButCannotShareCursors() = runBlocking {
        val api = FakeApi { path, data ->
            when (path) {
                "/api/v3/discovery/recommend/songs", "/api/v1/discovery/recommend/resource", "/api/v1/radio/get" -> json("""{"code":200}""")
                else -> {
                    assertEquals("/api/user/playlist", path)
                    assertEquals("7", data.getString("uid"))
                    json("""{"code":200,"playlist":[{"id":9,"name":"Mine","coverImgUrl":"https://p1.music.126.net/cover.jpg"}],"more":true}""")
                }
            }
        }
        val catalog = NeteaseCatalog(api)
        val cover = request().copy(remoteId = HomeCatalog.COVERS, accountContext = "account-7", pageSize = 4)
        val first = catalog.handle(cover, cookies, "7")
        assertEquals(listOf(HomeCatalog.DAILY, HomeCatalog.NETEASE_RADAR, HomeCatalog.NETEASE_ROAM, "playlist:9"), first.items.map { it.remoteId })
        assertNotNull(first.items.last().artworkUrl)
        assertNotNull(first.nextCursor)
        assertNull(catalog.handle(cover.copy(cursor = first.nextCursor), cookies, "7").errorCode)
        assertEquals(1, api.calls.last().data.getInt("offset"))
        val calls = api.calls.size
        assertEquals("InvalidRequest", catalog.handle(cover.copy(remoteId = HomeCatalog.PLAYLISTS,
            cursor = first.nextCursor), cookies, "7").errorCode)
        assertEquals("InvalidRequest", catalog.handle(cover.copy(cursor = first.nextCursor), cookies, "8").errorCode)
        assertEquals(calls, api.calls.size)
        assertEquals(first.items, catalog.handle(cover.copy(remoteId = HomeCatalog.COVERS), cookies, "7").items)
    }

    @Test fun homeTracksAndFavoritesUseTheCompleteFavoriteIdListAndDailyRemainsSeparate() = runBlocking {
        val api = FakeApi { path, _ -> when (path) {
            "/api/song/like/get" -> json("""{"code":200,"ids":[11,12]}""")
            "/api/v3/song/detail" -> json("""{"code":200,"songs":[{"id":11,"name":"Favorite"}]}""")
            "/api/v3/discovery/recommend/songs" -> json("""{"code":200,"data":{"dailySongs":[{"id":21,"name":"Daily"}]}}""")
            else -> error("Unexpected path: $path")
        } }
        val catalog = NeteaseCatalog(api)
        val r = request().copy(accountContext = "account-7", pageSize = 1)
        val tracks = catalog.handle(r.copy(remoteId = HomeCatalog.TRACKS), cookies, "7")
        val favorites = catalog.handle(r.copy(remoteId = HomeCatalog.FAVORITES), cookies, "7")
        assertEquals("11", tracks.items.single().remoteId)
        assertEquals(tracks.items, favorites.items)
        assertNotNull(tracks.nextCursor)
        assertNotEquals(tracks.nextCursor, favorites.nextCursor)
        val last = catalog.handle(r.copy(remoteId = HomeCatalog.TRACKS, cursor = tracks.nextCursor), cookies, "7")
        assertEquals("12", last.items.single().remoteId)
        assertEquals("UNAVAILABLE", last.items.single().availability)
        assertNull(last.nextCursor)
        assertEquals("21", catalog.handle(r.copy(remoteId = HomeCatalog.DAILY), cookies, "7").items.single().remoteId)
    }

    @Test fun homeSubscribedAlbumsAndArtistsUseBoundedProviderPaginationAndTypedIds() = runBlocking {
        val api = FakeApi { path, data ->
            assertEquals(1, data.getInt("limit"))
            assertTrue(data.getBoolean("total"))
            when (path) {
                "/api/album/sublist" -> json("""{"code":200,"data":[{"id":8,"name":"Album","artists":[{"name":"Artist"}],"picUrl":"http://p1.music.126.net/album.jpg"}],"hasMore":true}""")
                "/api/artist/sublist" -> json("""{"code":200,"data":[{"id":9,"name":"Artist","picUrl":"https://p2.music.126.net/artist.jpg"}],"hasMore":false}""")
                else -> error("Unexpected path: $path")
            }
        }
        val catalog = NeteaseCatalog(api)
        val r = request().copy(accountContext = "account-7", remoteId = HomeCatalog.ALBUMS, pageSize = 1)
        val albums = catalog.handle(r, cookies, "7")
        assertEquals("album:8", albums.items.single().remoteId)
        assertEquals("ALBUM", albums.items.single().kind)
        assertEquals(listOf("Artist"), albums.items.single().artists)
        assertEquals("https://p1.music.126.net/album.jpg", albums.items.single().artworkUrl)
        assertNotNull(albums.nextCursor)
        assertNull(catalog.handle(r.copy(cursor = albums.nextCursor), cookies, "7").errorCode)
        assertEquals(1, api.calls.last().data.getInt("offset"))
        val artists = catalog.handle(r.copy(remoteId = HomeCatalog.ARTISTS), cookies, "7")
        assertEquals("artist:9", artists.items.single().remoteId)
        assertEquals("ARTIST", artists.items.single().kind)
        assertNull(artists.nextCursor)
        assertTrue(api.calls.all { it.mode == NeteaseMode.WEAPI })
    }

    @Test fun homeSubscriptionsCanPaginateUsingAnExplicitTotalWithoutHasMore() = runBlocking {
        val api = FakeApi { _, data ->
            json("""{"code":200,"data":[{"id":${data.getInt("offset") + 8},"name":"Saved"}],"count":2}""")
        }
        val catalog = NeteaseCatalog(api)
        listOf(HomeCatalog.ALBUMS, HomeCatalog.ARTISTS).forEach { parent ->
            val r = request().copy(accountContext = "account-7", remoteId = parent, pageSize = 1)
            val first = catalog.handle(r, cookies, "7")
            assertNull(first.errorCode)
            assertNotNull(first.nextCursor)
            val second = catalog.handle(r.copy(cursor = first.nextCursor), cookies, "7")
            assertNull(second.errorCode)
            assertNotEquals(first.items.single().remoteId, second.items.single().remoteId)
            assertNull(second.nextCursor)
        }
    }

    @Test fun homeSubscriptionsDistinguishEmptyLibrariesFromMissingOrFailedProviderData() = runBlocking {
        val r = request().copy(accountContext = "account-7", remoteId = HomeCatalog.ALBUMS)
        val empty = NeteaseCatalog(FakeApi { _, _ -> json("""{"code":200,"data":[],"hasMore":false}""") }).handle(r, cookies, "7")
        assertNull(empty.errorCode)
        assertTrue(empty.items.isEmpty())
        assertNull(empty.nextCursor)
        val missing = NeteaseCatalog(FakeApi { _, _ -> json("""{"code":200,"hasMore":false}""") }).handle(r, cookies, "7")
        assertEquals("InvalidResponse", missing.errorCode)
        val missingPagination = NeteaseCatalog(FakeApi { _, _ -> json("""{"code":200,"data":[]}""") }).handle(r, cookies, "7")
        assertEquals("InvalidResponse", missingPagination.errorCode)
        val expired = NeteaseCatalog(FakeApi { _, _ -> json("""{"code":301}""") }).handle(r, cookies, "7")
        assertEquals("AuthExpired", expired.errorCode)
    }

    @Test fun artworkDropsQueriesWithoutDoubleEscapingEncodedPath() = runBlocking {
        val api = FakeApi { _, _ -> json("""{"code":200,"result":[{"id":21,"name":"Mix","picUrl":"https://p1.music.126.net/a%2Fb%25c.jpg?param=300y300"}]}""") }
        val result = NeteaseCatalog(api).handle(request(), cookies, null)
        assertEquals("https://p1.music.126.net/a%2Fb%25c.jpg", result.items.single().artworkUrl)
    }

    @Test fun searchPaginationBindsAccountQueryKindParentAndUserIdentity() = runBlocking {
        val api = FakeApi { _, _ -> json("""{"code":200,"result":{"songCount":3,"songs":[{"id":1,"name":"First"}]}}""") }
        val catalog = NeteaseCatalog(api)
        val first = request("Search").copy(query = "song", pageSize = 1)
        val cursor = catalog.handle(first, cookies, null).nextCursor!!
        val second = catalog.handle(first.copy(cursor = cursor), cookies, null)
        assertNull(second.errorCode)
        assertEquals(1, api.calls.last().data.getInt("offset"))
        val calls = api.calls.size
        listOf(first.copy(query = "different"), first.copy(kind = "ALBUM"), first.copy(remoteId = "playlist:42"),
            first.copy(accountContext = "account-7"), first.copy(pageSize = 2)).forEach { other ->
            assertEquals("InvalidRequest", catalog.handle(other.copy(cursor = cursor), cookies,
                if (other.accountContext == "public") null else "7").errorCode)
        }
        assertEquals(calls, api.calls.size)
        val privateRequest = first.copy(accountContext = "account-7")
        val privateCursor = catalog.handle(privateRequest, cookies, "7").nextCursor
        assertEquals("InvalidRequest", catalog.handle(privateRequest.copy(cursor = privateCursor), cookies, "8").errorCode)
    }

    @Test fun albumAndArtistSearchUseProviderTypesAndTypedIds() = runBlocking {
        val api = FakeApi { _, data -> when (data.getInt("type")) {
            10 -> json("""{"code":200,"result":{"albumCount":1,"albums":[{"id":8,"name":"Album","artist":{"name":"Artist"}}]}}""")
            100 -> json("""{"code":200,"result":{"artistCount":1,"artists":[{"id":9,"name":"Artist"}]}}""")
            else -> error("Unexpected search type")
        } }
        val catalog = NeteaseCatalog(api)
        assertEquals("album:8", catalog.handle(request("Search").copy(query = "x", kind = "ALBUM"), cookies, null).items.single().remoteId)
        assertEquals("artist:9", catalog.handle(request("Search").copy(query = "x", kind = "ARTIST"), cookies, null).items.single().remoteId)
        assertTrue(api.calls.all { it.mode == NeteaseMode.EAPI })
    }

    @Test fun playlistReadsAllTrackIdsThenOnlyFetchesRequestedPageInPlaylistOrder() = runBlocking {
        val api = FakeApi { path, _ -> when (path) {
            "/api/v6/playlist/detail" -> json("""{"code":200,"playlist":{"trackIds":[{"id":1},{"id":2},{"id":3}],"tracks":[{"id":1}],"updateTime":5}}""")
            "/api/v3/song/detail" -> json("""{"code":200,"songs":[{"id":2,"name":"Second"},{"id":1,"name":"First"}]}""")
            else -> error("Unexpected path")
        } }
        val catalog = NeteaseCatalog(api)
        val first = request("GetDetails").copy(remoteIds = listOf("playlist:8"), pageSize = 2)
        val result = catalog.handle(first, cookies, null)
        assertNull(result.errorCode)
        assertEquals(listOf("1", "2"), result.items.map { it.remoteId })
        assertEquals(2, JSONArray(api.calls.last().data.getString("c")).length())
        assertNotNull(result.nextCursor)
        assertEquals("playlist:8:5", result.snapshotId)
        val last = catalog.handle(first.copy(cursor = result.nextCursor), cookies, null)
        assertEquals("3", last.items.single().remoteId)
        assertEquals("UNAVAILABLE", last.items.single().availability)
        assertNull(last.nextCursor)
        assertEquals(3, JSONArray(api.calls.last().data.getString("c")).getJSONObject(0).getInt("id"))
    }

    @Test fun invalidIdsAndCursorsNeverReachTheApi() = runBlocking {
        val api = FakeApi { _, _ -> error("No request expected") }
        val catalog = NeteaseCatalog(api)
        listOf("https://example.com", "1/../../test", "0", "-1", "9223372036854775808").forEach { remoteId ->
            assertEquals("InvalidRequest", catalog.handle(request("ResolvePlayback").copy(remoteId = remoteId), cookies, null).errorCode)
        }
        assertEquals("InvalidRequest", catalog.handle(request().copy(cursor = "ncm1:any:20"), cookies, null).errorCode)
        assertTrue(api.calls.isEmpty())
    }

    @Test fun privatePlaylistsAndFavoritesUseVerifiedUserId() = runBlocking {
        val api = FakeApi { path, data ->
            if (path != "/api/v3/song/detail") assertEquals("7", data.getString("uid"))
            when (path) {
                "/api/user/playlist" -> json("""{"code":200,"playlist":[{"id":9,"name":"Mine"}],"more":true}""")
                "/api/song/like/get" -> json("""{"code":200,"ids":[11,12]}""")
                "/api/v3/song/detail" -> json("""{"code":200,"songs":[{"id":11,"name":"Favorite"}]}""")
                else -> error("Unexpected path")
            }
        }
        val catalog = NeteaseCatalog(api)
        val privateRequest = request().copy(accountContext = "opaque-account", pageSize = 1)
        val playlists = catalog.handle(privateRequest.copy(remoteId = "category:playlists"), cookies, "7")
        assertEquals("playlist:9", playlists.items.single().remoteId)
        assertNotNull(playlists.nextCursor)
        val favorites = catalog.handle(privateRequest.copy(remoteId = "category:favorites"), cookies, "7")
        assertEquals("11", favorites.items.single().remoteId)
        assertNotNull(favorites.nextCursor)
        assertEquals(NeteaseMode.EAPI, api.calls.first { it.path == "/api/song/like/get" }.mode)
    }

    @Test fun dailyRecommendationsMapSongsAndDurationWithoutLeakingProviderJson() = runBlocking {
        val api = FakeApi { _, _ -> json("""{"code":200,"data":{"dailySongs":[{"id":1,"name":"Daily","dt":90000,"ar":[{"name":"Artist"}],"al":{"id":4,"name":"Album"}}]}}""") }
        val result = NeteaseCatalog(api).handle(request().copy(accountContext = "account-7", remoteId = "category:daily"), cookies, "7")
        val song = result.items.single()
        assertEquals("/api/v3/discovery/recommend/songs", api.calls.single().path)
        assertEquals(90000L, song.durationMs)
        assertEquals(listOf("Artist"), song.artists)
        assertEquals("album:4", song.albumId)
    }

    @Test fun privateRoamFetchesThreeBatchesDedupesAndPaginatesTheSession() = runBlocking {
        val batches = ArrayDeque(listOf(
            json("""{"code":200,"data":[{"id":1,"name":"One"},{"id":2,"name":"Two"}]}"""),
            json("""{"code":200,"data":[{"id":2,"name":"Two"},{"id":3,"name":"Three"}]}"""),
            json("""{"code":200,"data":[{"id":4,"name":"Four"},{"id":3,"name":"Three"}]}""")))
        val api = FakeApi { path, data ->
            assertEquals("/api/v1/radio/get", path)
            assertEquals(20, data.getInt("limit"))
            batches.removeFirst()
        }
        val catalog = NeteaseCatalog(api)
        val request = request().copy(remoteId = HomeCatalog.NETEASE_ROAM, accountContext = "account-7", pageSize = 2)
        val first = catalog.handle(request, cookies, "7")
        assertEquals(listOf("1", "2"), first.items.map { it.remoteId })
        assertNotNull(first.nextCursor)
        val second = catalog.handle(request.copy(cursor = first.nextCursor), cookies, "7")
        assertEquals(listOf("3", "4"), second.items.map { it.remoteId })
        assertNull(second.nextCursor)
        assertEquals(3, api.calls.size)
    }

    @Test fun albumDetailsAndArtistBrowseSelectCorrectEndpoints() = runBlocking {
        val api = FakeApi { _, _ -> json("""{"code":200,"songs":[{"id":1,"name":"Song"}],"more":false}""") }
        val catalog = NeteaseCatalog(api)
        assertNull(catalog.handle(request("GetDetails").copy(remoteIds = listOf("album:8")), cookies, null).errorCode)
        assertEquals("/api/v1/album/8", api.calls.last().path)
        assertNull(catalog.handle(request().copy(remoteId = "artist:9"), cookies, null).errorCode)
        assertEquals("/api/v1/artist/songs", api.calls.last().path)
        assertEquals(NeteaseMode.EAPI, api.calls.last().mode)
    }

    @Test fun playbackQualityMapsFourTiersAndReportsActualGrantedTier() = runBlocking {
        val api = FakeApi { _, _ -> playback(""""level":"standard","type":"mp3","br":128000,"size":1234,"expi":60""") }
        val catalog = NeteaseCatalog(api)
        mapOf("standard" to "standard", "high" to "exhigh", "lossless" to "lossless", "hires" to "hires").forEach { (quality, level) ->
            val result = catalog.handle(request("ResolvePlayback").copy(remoteId = "1", quality = quality), cookies, null)
            assertNull(result.errorCode)
            assertEquals(level, api.calls.last().data.getString("level"))
            val source = result.playback!!
            assertEquals("standard", source.actualQuality)
            assertEquals("audio/mpeg", source.mime)
            assertEquals(1234L, source.length)
            assertEquals("NO_STORE", source.cachePolicy)
            assertEquals("", source.resolverRevision)
            assertTrue(source.headers.isEmpty())
            assertTrue(source.expiresAtEpochMs > System.currentTimeMillis())
        }
    }

    @Test fun previewIsExplicitAndEntitlementFailureDoesNotReturnAStream() = runBlocking {
        val responses = ArrayDeque(listOf(
            playback(""""freeTrialInfo":{"start":20,"end":50}"""),
            json("""{"code":200,"data":[{"id":1,"code":200,"url":null,"fee":1}]}"""),
            json("""{"code":200,"data":[{"id":1,"code":404,"url":null}]}""")))
        val catalog = NeteaseCatalog(FakeApi { _, _ -> responses.removeFirst() })
        val r = request("ResolvePlayback").copy(remoteId = "1")
        assertTrue(catalog.handle(r, cookies, null).playback!!.isPreview)
        val denied = catalog.handle(r, cookies, null)
        assertEquals("EntitlementRequired", denied.errorCode)
        assertNull(denied.playback)
        assertEquals("Unavailable", catalog.handle(r, cookies, null).errorCode)
    }

    @Test fun upstreamRepresentationDigestIdentifiesActualQualityWithoutAuthorizingCaching() = runBlocking {
        val digest = "abc123".repeat(5) + "ab"
        val api = FakeApi { _, _ -> playback(""""md5":"$digest","level":"lossless","type":"flac"""") }
        val source = NeteaseCatalog(api).handle(request("ResolvePlayback").copy(remoteId = "1"), cookies, null).playback!!
        assertEquals("$digest:lossless", source.resolverRevision)
        assertEquals("NO_STORE", source.cachePolicy)
        assertNull(source.contentSha256)
    }

    @Test fun unsafeMediaUrlsAreRejectedAndOnlyKnownCdnHttpIsUpgraded() = runBlocking {
        assertEquals("https://m801.music.126.net/song.flac", NeteaseMediaDomains.httpsUrl("http://m801.music.126.net/song.flac"))
        listOf("https://m1.music.126.net.evil.example/song", "https://evil.example/song", "file:///etc/passwd",
            "https://user:secret@m1.music.126.net/song", "https://m1.music.126.net:444/song", "https://m1.music.126.net/song#fragment",
            "https://127.0.0.1/song", "https://m1.music.126.net\\@evil.example/song").forEach { url ->
            assertNull(url, NeteaseMediaDomains.httpsUrl(url))
        }
        val api = FakeApi { _, _ -> json("""{"code":200,"data":[{"id":1,"code":200,"url":"https://evil.example/song"}]}""") }
        val result = NeteaseCatalog(api).handle(request("ResolvePlayback").copy(remoteId = "1"), cookies, null)
        assertEquals("UnsafeMediaUrl", result.errorCode)
        assertNull(result.playback)
    }

    @Test fun translatedLyricsRemainBoundedAndPaginateAtMatchedTimestamps() = runBlocking {
        val response = JSONObject().put("code", 200)
            .put("lrc", JSONObject().put("lyric", "[offset:100]\n[00:01.20][00:02.200]First\n[00:03]Second"))
            .put("tlyric", JSONObject().put("lyric", "[00:01.300]Translated first\n[00:03.100]Translated second"))
        val catalog = NeteaseCatalog(FakeApi { _, _ -> response })
        val r = request("GetLyrics").copy(remoteId = "1", pageSize = 2)
        val first = catalog.handle(r, cookies, null)
        assertEquals(listOf(1300L, 2300L), first.lyrics.map { it.timeMs })
        assertEquals("Translated first", first.lyrics.first().translation)
        assertNull(first.lyrics.last().translation)
        val last = catalog.handle(r.copy(cursor = first.nextCursor), cookies, null)
        assertEquals("Translated second", last.lyrics.single().translation)
        assertNull(last.nextCursor)
        assertEquals("InvalidRequest", catalog.handle(r.copy(remoteId = "2", cursor = first.nextCursor), cookies, null).errorCode)
    }

    @Test fun plainLyricsSkipMetadataAndLongTextIsBounded() {
        val plain = NeteaseCatalog.parseLrc("[ar:Artist]\n{\"t\":0}\nPlain lyrics\n" + "x".repeat(2000))
        assertEquals(2, plain.size)
        assertTrue(plain.all { it.timeMs == -1L && it.text.length <= 384 })
        assertTrue(NeteaseCatalog.parseLrc("").isEmpty())
    }

    @Test fun businessAndTransportErrorsCannotBecomeSuccessfulEmptyCatalogs() = runBlocking {
        val r = request()
        listOf(301 to "AuthExpired", 403 to "EntitlementRequired", 404 to "NotFound", 429 to "RateLimited", 502 to "ServiceError").forEach { (code, error) ->
            val result = NeteaseCatalog(FakeApi { _, _ -> JSONObject().put("code", code) }).handle(r, cookies, null)
            assertEquals(error, result.errorCode)
            assertTrue(result.items.isEmpty())
        }
        val invalid = NeteaseCatalog(FakeApi { _, _ -> json("""{"code":200}""") }).handle(r, cookies, null)
        assertEquals("InvalidResponse", invalid.errorCode)
        val network = NeteaseCatalog(FakeApi { _, _ -> throw NeteaseException("NetworkUnavailable", "Offline") }).handle(r, cookies, null)
        assertEquals("NetworkUnavailable", network.errorCode)
    }

    private fun request(operation: String = "Browse") = RequestEnvelope("connection", "request", "trace", Long.MAX_VALUE, operation = operation)
    private fun json(value: String) = JSONObject(value)
    private fun playback(fields: String) = json("""{"code":200,"data":[{"id":1,"code":200,"url":"https://m1.music.126.net/song.mp3",$fields}]}""")

    private data class Call(val path: String, val data: JSONObject, val mode: NeteaseMode)
    private class FakeApi(private val response: (String, JSONObject) -> JSONObject) : NeteaseApi {
        val calls = mutableListOf<Call>()
        override suspend fun post(path: String, data: JSONObject, cookies: NeteaseCookies, mode: NeteaseMode): JSONObject {
            calls += Call(path, data, mode)
            return response(path, data)
        }
    }
}
