package app.easepod.netease

import app.easepod.contract.CatalogItem
import app.easepod.contract.HomeCatalog
import app.easepod.contract.LyricSegment
import app.easepod.contract.PlaybackSource
import app.easepod.contract.RequestEnvelope
import app.easepod.contract.ResultEnvelope
import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private fun JSONArray?.orEmptyObjects(): List<JSONObject> = this?.let { array ->
    (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
}.orEmpty()

/** Exact CDN hosts shared by the resolver and the host's media policy. */
object NeteaseMediaDomains {
    val hosts: Set<String> = buildSet {
        (1..4).forEach { add("p$it.music.126.net") }
        (1..12).forEach { add("m$it.music.126.net") }
        listOf(701, 702, 801, 802).forEach { add("m$it.music.126.net") }
    }

    fun httpsUrl(value: String?): String? {
        if (value.isNullOrBlank() || value.length > 4096 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = try { URI(value) } catch (_: Exception) { return null }
        if (uri.scheme !in setOf("http", "https") || uri.host?.lowercase() !in hosts ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1, 443) ||
            uri.rawPath.isNullOrEmpty() || '\\' in value) return null
        // Only upgrade known CDN URLs; no cleartext request is ever issued.
        return if (uri.scheme == "http") "https:" + value.substringAfter(':') else value
    }
}

/** Maps provider JSON to the bounded, account-scoped plugin protocol. */
class NeteaseCatalog(private val api: NeteaseApi) {
    /**
     * CNMPlayer keeps a private-roam session instead of treating every browse
     * request as a fresh three-song response.  Keep the same bounded session
     * in the plugin process: the first page refreshes once per UTC day, later
     * pages extend the list, and playback remembers the last song for the next
     * daily refresh.  The host's cursor still carries the page offset, so a
     * process restart simply starts a new session on the next browse.
     */
    private data class RoamState(
        val tracks: MutableList<CatalogItem> = mutableListOf(),
        var lastPlayedId: String? = null,
        var lastRefreshDay: Long? = null,
    )

    private val roamStates = mutableMapOf<String, RoamState>()

    suspend fun handle(request: RequestEnvelope, cookies: NeteaseCookies, userId: String?): ResultEnvelope {
        return try {
            if (request.pageSize !in 1..50 || request.remoteIds.size > 50 || request.query.orEmpty().length > 1024)
                fail("InvalidRequest", "请求超出限制")
            if (request.accountContext != "public" && (userId == null || !isId(userId)))
                fail("AuthRequired", "请先登录网易云音乐")
            val page = Page(request, userId)
            when (request.operation) {
                "Browse" -> browse(request, page, cookies, userId, request.remoteId ?: "root")
                "Search" -> search(request, page, cookies)
                "GetDetails" -> details(request, page, cookies, userId)
                "ResolvePlayback" -> resolve(request, cookies, userId)
                "GetLyrics" -> lyrics(request, page, cookies)
                else -> fail("Unsupported", "不支持此操作")
            }
        } catch (error: NeteaseException) {
            request.result().copy(errorCode = error.code, errorMessage = error.message?.take(512))
        } catch (_: JSONException) {
            request.result().copy(errorCode = "InvalidResponse", errorMessage = "音乐服务返回了无法解析的数据")
        }
    }

    private suspend fun browse(r: RequestEnvelope, page: Page, cookies: NeteaseCookies, userId: String?, parent: String): ResultEnvelope {
        return when {
            HomeCatalog.isHome(parent) && parent !in setOf(HomeCatalog.NETEASE_RADAR, HomeCatalog.NETEASE_ROAM) -> home(r, page, cookies, userId, parent)
            parent == "root" && r.accountContext != "public" -> page.items(listOf(
                category("playlists", "我的歌单"), category("favorites", "我喜欢的音乐"),
                category("daily", "每日推荐"), category("recommended", "推荐歌单")))
            parent in setOf("root", "category:recommended") -> {
                val body = call("/api/personalized/playlist", json("limit" to 100, "total" to true, "n" to 1000), cookies)
                page.items(body.array("result").objects().map { container(it, "PLAYLIST") })
            }
            parent == "category:playlists" -> {
                val uid = account(r, userId)
                val virtual = if (r.remoteId == HomeCatalog.COVERS || r.remoteId == HomeCatalog.PLAYLISTS)
                    if (page.offset == 0) virtualPlaylists(cookies, r.pageSize, userId) else fixedVirtualPlaylists()
                else emptyList()
                val virtualPage = virtual.drop(page.offset).take(r.pageSize)
                val remaining = r.pageSize - virtualPage.size
                val userOffset = (page.offset - virtual.size).coerceAtLeast(0)
                val body = if (remaining > 0) call("/api/user/playlist", json("uid" to uid, "limit" to remaining,
                    "offset" to userOffset, "includeVideo" to false), cookies) else null
                val users = body?.array("playlist")?.objects()?.map { container(it, "PLAYLIST") }?.take(remaining).orEmpty()
                val items = virtualPage + users
                val more = page.offset + items.size < virtual.size || body?.optBoolean("more", false) == true
                page.remoteItems(items, more)
            }
            parent == "category:favorites" -> {
                val uid = account(r, userId)
                val body = call("/api/song/like/get", json("uid" to uid), cookies, NeteaseMode.EAPI)
                trackPage(body.array("ids").ids(), page, cookies)
            }
            parent == "category:daily" -> {
                account(r, userId)
                val body = call("/api/v3/discovery/recommend/songs", JSONObject(), cookies)
                val songs = body.optJSONObject("data")?.optJSONArray("dailySongs") ?: body.optJSONArray("recommend")
                    ?: fail("InvalidResponse", "每日推荐数据不完整")
                page.items(songs.objects().map { track(it) })
            }
            parent == HomeCatalog.NETEASE_RADAR -> {
                val body = call("/api/v1/discovery/recommend/resource", JSONObject(), cookies, NeteaseMode.WEAPI)
                val resource = body.optJSONArray("recommend").orEmptyObjects()
                    .firstOrNull { it.optString("name").contains("私人雷达") }
                val id = resource?.optString("id")?.takeIf(::isId)
                    ?: fail("NotFound", "私人雷达暂不可用")
                val detail = call("/api/v6/playlist/detail", json("id" to id, "n" to 0, "s" to 0), cookies, NeteaseMode.EAPI)
                val playlist = detail.optJSONObject("playlist") ?: fail("NotFound", "私人雷达暂不可用")
                val ids = playlist.array("trackIds").objects().map { id(it.optString("id")) }
                trackPage(ids, page, cookies, "playlist:$id:${playlist.optLong("updateTime", 0)}")
            }
            parent == HomeCatalog.NETEASE_ROAM -> {
                account(r, userId)
                roamPage(r, page, cookies, userId)
            }
            parent.startsWith("playlist:") -> {
                val id = id(parent.removePrefix("playlist:"))
                val body = call("/api/v6/playlist/detail", json("id" to id, "n" to 0, "s" to 0), cookies, NeteaseMode.EAPI)
                val playlist = body.optJSONObject("playlist") ?: fail("NotFound", "歌单不存在或无权访问")
                // The embedded tracks can be truncated; paginate the complete ID list first.
                val ids = playlist.array("trackIds").objects().map { id(it.optString("id")) }
                trackPage(ids, page, cookies, "playlist:$id:${playlist.optLong("updateTime", 0)}")
            }
            parent.startsWith("album:") -> {
                val id = id(parent.removePrefix("album:"))
                val body = call("/api/v1/album/$id", JSONObject(), cookies)
                page.items(body.array("songs").objects().map { track(it) })
            }
            parent.startsWith("artist:") -> {
                val id = id(parent.removePrefix("artist:"))
                val body = call("/api/v1/artist/songs", json("id" to id, "private_cloud" to "true", "work_type" to 1,
                    "order" to "hot", "offset" to page.offset, "limit" to r.pageSize), cookies, NeteaseMode.EAPI)
                val items = body.array("songs").objects().map { track(it) }.take(r.pageSize)
                page.remoteItems(items, body.optBoolean("more", false))
            }
            else -> fail("NotFound", "目录不存在")
        }
    }

    /** Read a private-roam page from the session, refreshing/expanding it as needed. */
    private suspend fun roamPage(r: RequestEnvelope, page: Page, cookies: NeteaseCookies, userId: String?): ResultEnvelope {
        val accountKey = userId ?: fail("AuthRequired", "请先登录网易云音乐")
        val state = synchronized(roamStates) { roamStates.getOrPut(accountKey) { RoamState() } }
        val today = System.currentTimeMillis() / 86_400_000L

        // CNMPlayer refreshes once per UTC day.  A failed refresh leaves the
        // previous list intact and does not advance the day marker.
        if (page.offset == 0 && (state.tracks.isEmpty() || state.lastRefreshDay != today)) {
            val fetched = try {
                fetchRoamBatch(cookies)
            } catch (error: NeteaseException) {
                if (state.tracks.isEmpty() || error.code in setOf("AuthRequired", "AuthExpired", "EntitlementRequired")) throw error
                emptyList()
            }
            if (fetched.isNotEmpty()) {
                val previous = state.tracks.toList()
                val keep = state.lastPlayedId?.let { id -> previous.firstOrNull { it.remoteId == id } }
                state.tracks.clear()
                keep?.let(state.tracks::add)
                fetched.forEach { item ->
                    if (state.tracks.none { it.remoteId == item.remoteId }) state.tracks += item
                }
                state.lastRefreshDay = today
            } else if (state.tracks.isEmpty()) {
                fail("InvalidResponse", "私人漫游数据为空")
            }
        }

        // The radio endpoint returns a small batch regardless of the host page
        // size.  Fetch a few batches when the host asks beyond the current
        // session, matching CNMPlayer's play-through expansion and deduping.
        var attempts = 0
        while (state.tracks.size < page.offset + page.size && attempts++ < 3) {
            val fetched = try {
                fetchRoamBatch(cookies)
            } catch (error: NeteaseException) {
                if (error.code in setOf("AuthRequired", "AuthExpired", "EntitlementRequired")) throw error
                null
            }
            if (fetched == null) break
            if (fetched.isEmpty()) break
            var added = false
            fetched.forEach { item ->
                if (state.tracks.none { it.remoteId == item.remoteId }) {
                    state.tracks += item
                    added = true
                }
            }
            if (!added) break
        }

        page.checkOffset(state.tracks.size)
        val selected = state.tracks.drop(page.offset).take(page.size)
        return page.remoteItems(selected, page.offset + selected.size < state.tracks.size)
    }

    /** Pull three twenty-song radio batches, deduping by song id like CNMPlayer. */
    private suspend fun fetchRoamBatch(cookies: NeteaseCookies): List<CatalogItem> {
        val result = mutableListOf<CatalogItem>()
        val seen = mutableSetOf<String>()
        var lastError: NeteaseException? = null
        repeat(3) {
            try {
                val body = call("/api/v1/radio/get", json("limit" to 20, "mode" to "DEFAULT", "subMode" to ""), cookies, NeteaseMode.WEAPI)
                body.optJSONArray("data")?.objects().orEmpty().forEach { song ->
                    val item = runCatching { track(song) }.getOrNull() ?: return@forEach
                    if (seen.add(item.remoteId)) result += item
                }
            } catch (error: NeteaseException) {
                lastError = error
            }
        }
        if (result.isEmpty()) lastError?.let { throw it }
        return result
    }

    private suspend fun home(r: RequestEnvelope, page: Page, cookies: NeteaseCookies, userId: String?, parent: String): ResultEnvelope {
        account(r, userId)
        return when (parent) {
            HomeCatalog.ROOT -> page.items(listOf(
                CatalogItem(kind = "PLAYLIST", remoteId = HomeCatalog.PLAYLISTS, title = "我的歌单"),
                CatalogItem(kind = "PLAYLIST", remoteId = HomeCatalog.FAVORITES, title = "我喜欢的音乐"),
                CatalogItem(kind = "PLAYLIST", remoteId = HomeCatalog.DAILY, title = "每日推荐"),
                CatalogItem(kind = "ALBUM", remoteId = HomeCatalog.ALBUMS, title = "收藏专辑"),
                CatalogItem(kind = "ARTIST", remoteId = HomeCatalog.ARTISTS, title = "收藏歌手"),
                category("recommended", "推荐歌单")))
            HomeCatalog.COVERS, HomeCatalog.PLAYLISTS -> browse(r, page, cookies, userId, "category:playlists")
            HomeCatalog.TRACKS, HomeCatalog.FAVORITES -> browse(r, page, cookies, userId, "category:favorites")
            HomeCatalog.DAILY -> browse(r, page, cookies, userId, "category:daily")
            HomeCatalog.ALBUMS, HomeCatalog.ARTISTS -> {
                val kind = if (parent == HomeCatalog.ALBUMS) "ALBUM" else "ARTIST"
                val path = if (kind == "ALBUM") "/api/album/sublist" else "/api/artist/sublist"
                val body = call(path, json("limit" to r.pageSize, "offset" to page.offset, "total" to true), cookies)
                val items = body.array("data").objects().take(r.pageSize).map { container(it, kind) }
                val hasMore = if (!body.isNull("hasMore")) body.getBoolean("hasMore") else {
                    val count = body.optLong("count", -1).takeIf { it >= 0 }
                        ?: fail("InvalidResponse", "收藏列表分页信息不完整")
                    page.offset + items.size < count
                }
                page.remoteItems(items, hasMore)
            }
            else -> fail("Unsupported", "网易云音乐不支持此目录")
        }
    }

    /** Match CNMPlayer's pinned tile artwork: daily/roam use the first song's album cover,
     * while radar uses the recommendation card cover when it is available. */
    private suspend fun virtualPlaylists(cookies: NeteaseCookies, limit: Int, userId: String?): List<CatalogItem> {
        var dailyCover: String? = null
        var radarCover: String? = null
        var roamCover: String? = null
        try {
            val body = call("/api/v3/discovery/recommend/songs", JSONObject(), cookies)
            val songs = body.optJSONObject("data")?.optJSONArray("dailySongs") ?: body.optJSONArray("recommend")
            dailyCover = songs?.objects()?.firstNotNullOfOrNull { it.optJSONObject("al")?.text("picUrl") ?: it.optJSONObject("album")?.text("picUrl") }
        } catch (_: Exception) { }
        try {
            val body = call("/api/v1/discovery/recommend/resource", JSONObject(), cookies, NeteaseMode.WEAPI)
            val radar = body.optJSONArray("recommend").orEmptyObjects().firstOrNull { it.optString("name").contains("私人雷达") }
            radarCover = radar?.text("picUrl") ?: radar?.text("coverImgUrl")
        } catch (_: Exception) { }
        // CNMPlayer's roam tile follows the last played song's cover. Use the
        // in-memory session when available, then fall back to the first song
        // from a fresh radio batch for a cold start.
        val stateCover = userId?.let { key ->
            synchronized(roamStates) { roamStates[key] }?.let { state ->
                state.lastPlayedId?.let { id -> state.tracks.firstOrNull { it.remoteId == id }?.artworkUrl }
            }
        }
        roamCover = stateCover
        if (roamCover == null) try {
            val body = call("/api/v1/radio/get", json("limit" to limit.coerceIn(1, 50), "mode" to "DEFAULT", "subMode" to ""), cookies, NeteaseMode.WEAPI)
            roamCover = body.optJSONArray("data")?.objects()?.firstNotNullOfOrNull { it.optJSONObject("al")?.text("picUrl") ?: it.optJSONObject("album")?.text("picUrl") }
        } catch (_: Exception) { }
        return listOf(
            CatalogItem("PLAYLIST", HomeCatalog.DAILY, "每日推荐", artworkUrl = artwork(dailyCover)),
            CatalogItem("PLAYLIST", HomeCatalog.NETEASE_RADAR, "私人雷达", artworkUrl = artwork(radarCover)),
            CatalogItem("PLAYLIST", HomeCatalog.NETEASE_ROAM, "歌曲漫游", artworkUrl = artwork(roamCover)))
    }

    private fun fixedVirtualPlaylists() = listOf(
        CatalogItem("PLAYLIST", HomeCatalog.DAILY, "每日推荐"),
        CatalogItem("PLAYLIST", HomeCatalog.NETEASE_RADAR, "私人雷达"),
        CatalogItem("PLAYLIST", HomeCatalog.NETEASE_ROAM, "歌曲漫游"))

    private suspend fun search(r: RequestEnvelope, page: Page, cookies: NeteaseCookies): ResultEnvelope {
        val query = r.query?.trim()?.takeIf { it.isNotEmpty() } ?: fail("InvalidRequest", "搜索词不能为空")
        val (type, key, countKey) = when (r.kind) {
            "TRACK" -> Triple(1, "songs", "songCount")
            "ALBUM" -> Triple(10, "albums", "albumCount")
            "ARTIST" -> Triple(100, "artists", "artistCount")
            "PLAYLIST" -> Triple(1000, "playlists", "playlistCount")
            else -> fail("InvalidRequest", "不支持的搜索类型")
        }
        val body = call("/api/cloudsearch/pc", json("s" to query, "type" to type, "limit" to r.pageSize,
            "offset" to page.offset, "total" to true), cookies, NeteaseMode.EAPI)
        val result = body.optJSONObject("result") ?: fail("InvalidResponse", "搜索结果不完整")
        val values = result.optJSONArray(key)
        if (values == null && result.optInt(countKey, -1) != 0) fail("InvalidResponse", "搜索结果不完整")
        val items = values?.objects().orEmpty().take(r.pageSize).map { if (r.kind == "TRACK") track(it) else container(it, r.kind) }
        return page.remoteItems(items, page.offset + items.size < result.optInt(countKey, 0))
    }

    private suspend fun details(r: RequestEnvelope, page: Page, cookies: NeteaseCookies, userId: String?): ResultEnvelope {
        val ids = r.remoteIds.ifEmpty { listOfNotNull(r.remoteId) }
        if (ids.isEmpty()) fail("InvalidRequest", "缺少媒体标识")
        if (ids.size == 1 && !isId(ids.single())) return browse(r, page, cookies, userId, ids.single())
        ids.forEach(::id)
        return trackPage(ids, page, cookies)
    }

    private suspend fun trackPage(ids: List<String>, page: Page, cookies: NeteaseCookies, snapshot: String? = null): ResultEnvelope {
        page.checkOffset(ids.size)
        val selected = ids.drop(page.offset).take(page.size)
        val tracks = fetchTracks(selected, cookies)
        return page.remoteItems(tracks, page.offset + selected.size < ids.size).copy(snapshotId = snapshot)
    }

    private suspend fun fetchTracks(ids: List<String>, cookies: NeteaseCookies): List<CatalogItem> {
        if (ids.isEmpty()) return emptyList()
        val requestIds = JSONArray().apply { ids.distinct().forEach { put(json("id" to id(it).toLong())) } }
        val body = call("/api/v3/song/detail", json("c" to requestIds.toString()), cookies)
        val privileges = body.optJSONArray("privileges")?.objects().orEmpty().associateBy { it.optString("id") }
        val songs = body.array("songs").objects().associateBy { it.optString("id") }
        return ids.map { requested ->
            songs[requested]?.let { track(it, privileges[requested]) }
                ?: CatalogItem(remoteId = requested, title = "不可用歌曲", availability = "UNAVAILABLE")
        }
    }

    private suspend fun resolve(r: RequestEnvelope, cookies: NeteaseCookies, userId: String?): ResultEnvelope {
        if (r.cursor != null) fail("InvalidRequest", "播放请求不接受分页")
        val remoteId = id(r.remoteId)
        val requestedLevel = when (r.quality) {
            "standard" -> "standard"
            "high" -> "exhigh"
            "lossless" -> "lossless"
            "hires" -> "hires"
            else -> fail("InvalidRequest", "不支持的音质")
        }
        val body = call("/api/song/enhance/player/url/v1", json("ids" to JSONArray().put(remoteId.toLong()).toString(),
            "level" to requestedLevel, "encodeType" to "flac"), cookies, NeteaseMode.EAPI)
        val source = body.array("data").objects().firstOrNull { it.optString("id") == remoteId }
            ?: fail("Unavailable", "歌曲暂不可用")
        val code = source.optInt("code", 200)
        val rawUrl = source.text("url")
        if (code != 200 || rawUrl == null) {
            when {
                code == 404 || code == -110 -> fail("Unavailable", "歌曲已下架或暂不可用")
                code == 401 || code == 403 || source.optInt("fee", 0) in setOf(1, 4) ->
                    fail("EntitlementRequired", "当前账号没有此歌曲的播放授权")
                else -> fail("Unavailable", "歌曲暂不可用")
            }
        }
        val url = NeteaseMediaDomains.httpsUrl(rawUrl) ?: fail("UnsafeMediaUrl", "播放地址不在允许的 HTTPS 媒体域名内")
        val bitrate = source.optInt("br", -1).takeIf { it > 0 } ?: -1
        val mediaType = source.text("type")?.lowercase()
        val actualQuality = when (source.text("level")) {
            "hires" -> "hires"
            "lossless" -> "lossless"
            "higher", "exhigh" -> "high"
            "standard" -> "standard"
            else -> when {
                mediaType == "flac" -> "lossless"
                bitrate >= 192000 -> "high"
                else -> "standard"
            }
        }
        val ttlSeconds = source.optLong("expi", -1)
        val expiry = if (ttlSeconds > 0) System.currentTimeMillis() + ttlSeconds.coerceAtMost(86400) * 1000 else -1L
        val preview = source.optJSONObject("freeTrialInfo") != null
        val revision = source.text("md5")?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
            ?.let { "${it.lowercase()}:$actualQuality" }.orEmpty()
        val result = r.result().copy(playback = PlaybackSource(remoteId, url, expiresAtEpochMs = expiry,
            mime = when (mediaType) { "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "aac" -> "audio/aac"; "m4a" -> "audio/mp4"; else -> null },
            length = source.optLong("size", -1).takeIf { it > 0 } ?: -1,
            bitrate = bitrate, actualQuality = actualQuality, isPreview = preview,
            cachePolicy = "NO_STORE", resolverRevision = revision))
        rememberRoamPlayback(remoteId, userId, cookies)
        return result
    }

    /** Record playback and append when the current roam session reaches its tail. */
    private suspend fun rememberRoamPlayback(remoteId: String, userId: String?, cookies: NeteaseCookies) {
        val key = userId ?: return
        val state = synchronized(roamStates) { roamStates[key] } ?: return
        val index = state.tracks.indexOfFirst { it.remoteId == remoteId }
        if (index < 0) return
        state.lastPlayedId = remoteId
        if (index + 1 != state.tracks.size) return
        val fetched = try {
            fetchRoamBatch(cookies)
        } catch (_: NeteaseException) {
            emptyList()
        }
        fetched.forEach { item ->
            if (state.tracks.none { it.remoteId == item.remoteId }) state.tracks += item
        }
    }

    private suspend fun lyrics(r: RequestEnvelope, page: Page, cookies: NeteaseCookies): ResultEnvelope {
        val remoteId = id(r.remoteId)
        val body = call("/api/song/lyric", json("id" to remoteId, "tv" to -1, "lv" to -1, "rv" to -1,
            "kv" to -1, "_nmclfl" to 1), cookies, NeteaseMode.EAPI)
        val original = parseLrc(body.optJSONObject("lrc")?.text("lyric").orEmpty())
        val translation = parseLrc(body.optJSONObject("tlyric")?.text("lyric").orEmpty())
            .filter { it.timeMs >= 0 }.associate { it.timeMs to it.text }
        val lines = original.map { it.copy(translation = translation[it.timeMs]?.takeIf { text -> text != it.text }) }
        page.checkOffset(lines.size)
        val selected = lines.drop(page.offset).take(page.size)
        return r.result().copy(lyrics = selected, nextCursor = page.next(selected.size, page.offset + selected.size < lines.size))
    }

    private suspend fun call(path: String, data: JSONObject, cookies: NeteaseCookies, mode: NeteaseMode = NeteaseMode.WEAPI): JSONObject {
        val result = api.post(path, data, cookies, mode)
        val code = result.optInt("code", -1)
        if (code != 200) when (code) {
            301, 302, 401 -> fail("AuthExpired", "登录已失效，请重新登录")
            403 -> fail("EntitlementRequired", "当前账号无权访问此内容")
            404 -> fail("NotFound", "内容不存在")
            429 -> fail("RateLimited", "请求过于频繁，请稍后重试")
            -1 -> fail("InvalidResponse", "音乐服务返回的数据不完整")
            else -> fail("ServiceError", "音乐服务暂时无法完成请求（$code）")
        }
        return result
    }

    private fun track(song: JSONObject, suppliedPrivilege: JSONObject? = null): CatalogItem {
        val songId = id(song.optString("id"))
        val album = song.optJSONObject("al") ?: song.optJSONObject("album")
        val privilege = suppliedPrivilege ?: song.optJSONObject("privilege")
        val availability = when {
            song.optInt("st", 0) < 0 || privilege?.optInt("st", 0)?.let { it < 0 } == true -> "UNAVAILABLE"
            privilege != null && privilege.has("pl") && privilege.optLong("pl", 0) <= 0 ->
                if (privilege.optInt("fee", song.optInt("fee", 0)) in setOf(1, 4)) "ENTITLEMENT_REQUIRED" else "UNAVAILABLE"
            else -> "AVAILABLE"
        }
        return CatalogItem(remoteId = songId, title = song.text("name")?.take(128) ?: "未命名歌曲",
            artists = artistNames(song), albumId = album?.text("id")?.takeIf(::isId)?.let { "album:$it" },
            albumTitle = album?.text("name")?.take(128), durationMs = song.optLong("dt", song.optLong("duration", -1)).coerceAtLeast(-1),
            artworkUrl = artwork(album?.text("picUrl")), availability = availability)
    }

    private fun container(value: JSONObject, kind: String): CatalogItem {
        val prefix = when (kind) { "PLAYLIST" -> "playlist"; "ALBUM" -> "album"; "ARTIST" -> "artist"; else -> fail("InvalidResponse", "无效目录类型") }
        val names = artistNames(value).ifEmpty { listOfNotNull(value.optJSONObject("creator")?.text("nickname")?.take(64)) }
        return CatalogItem(kind = kind, remoteId = "$prefix:${id(value.optString("id"))}",
            title = value.text("name")?.take(128) ?: "未命名", artists = names,
            artworkUrl = artwork(value.text("coverImgUrl") ?: value.text("picUrl") ?: value.text("img1v1Url")))
    }

    private fun artistNames(value: JSONObject): List<String> {
        val list = value.optJSONArray("ar") ?: value.optJSONArray("artists")
        return list?.objects()?.mapNotNull { it.text("name")?.take(64) }?.take(4)
            ?: listOfNotNull(value.optJSONObject("artist")?.text("name")?.take(64))
    }

    private fun artwork(value: String?): String? {
        val safe = NeteaseMediaDomains.httpsUrl(value) ?: return null
        val uri = URI(safe)
        // Raw components preserve encoded slashes and percent signs when dropping CDN resize queries.
        return URI("${uri.scheme}://${uri.rawAuthority}${uri.rawPath}").toASCIIString().takeIf { it.length <= 512 }
    }
    private fun category(id: String, title: String) = CatalogItem(kind = "PLAYLIST", remoteId = "category:$id", title = title)
    private fun account(r: RequestEnvelope, userId: String?): String {
        if (r.accountContext == "public" || userId == null) fail("AuthRequired", "请先登录网易云音乐")
        return id(userId)
    }

    private class Page(private val request: RequestEnvelope, userId: String?) {
        val size = request.pageSize
        private val binding = JSONArray().put(request.operation).put(request.accountContext).put(userId)
            .put(request.remoteId).put(JSONArray(request.remoteIds)).put(request.query).put(request.kind).put(size).toString()
        private val prefix = "ncm1:" + MessageDigest.getInstance("SHA-256").digest(binding.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ":"
        val offset: Int = request.cursor?.let { cursor ->
            if (!cursor.startsWith(prefix)) fail("InvalidRequest", "分页游标与当前账号或查询不匹配")
            cursor.removePrefix(prefix).toIntOrNull()?.takeIf { it in 0..1_000_000 }
                ?: fail("InvalidRequest", "分页游标无效")
        } ?: 0

        fun checkOffset(total: Int) { if (offset > total) fail("InvalidRequest", "分页位置已失效") }
        fun next(count: Int, more: Boolean): String? = if (more && count > 0) "$prefix${offset + count}" else null
        fun remoteItems(items: List<CatalogItem>, more: Boolean) = request.result().copy(items = items, nextCursor = next(items.size, more))
        fun items(values: List<CatalogItem>): ResultEnvelope {
            checkOffset(values.size)
            val selected = values.drop(offset).take(size)
            return remoteItems(selected, offset + selected.size < values.size)
        }
    }

    companion object {
        private val numericId = Regex("[1-9][0-9]{0,18}")
        private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        private val offsetTag = Regex("\\[offset:([+-]?\\d{1,9})]", RegexOption.IGNORE_CASE)
        private fun isId(value: String) = numericId.matches(value) && value.toLongOrNull() != null
        private fun id(value: String?): String = value?.takeIf(::isId) ?: fail("InvalidRequest", "媒体标识无效")
        private fun fail(code: String, message: String): Nothing = throw NeteaseException(code, message)
        private fun RequestEnvelope.result() = ResultEnvelope(connectionId, requestId, operation)
        private fun json(vararg fields: Pair<String, Any>): JSONObject = JSONObject().apply { fields.forEach { put(it.first, it.second) } }
        private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
        private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: fail("InvalidResponse", "音乐服务返回的列表不完整")
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.ids(): List<String> = (0 until length()).map { id(get(it).toString()) }

        internal fun parseLrc(text: String): List<LyricSegment> {
            if (text.length > 1_048_576) fail("InvalidResponse", "歌词超过大小限制")
            val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val timed = mutableListOf<LyricSegment>()
            val plain = mutableListOf<LyricSegment>()
            text.lineSequence().take(10000).forEach { line ->
                val tags = timestamp.findAll(line).toList()
                if (tags.isNotEmpty()) {
                    val content = line.substring(tags.last().range.last + 1).trim().take(384)
                    tags.take(20).forEach { match ->
                        val seconds = match.groupValues[2].toInt()
                        if (seconds < 60 && timed.size < 10000) {
                            val millis = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                            val time = match.groupValues[1].toLong() * 60000 + seconds * 1000 + millis + offset
                            timed += LyricSegment(time.coerceAtLeast(0), content)
                        }
                    }
                } else if (line.isNotBlank() && !line.trimStart().startsWith("[") && !line.trimStart().startsWith("{")) {
                    plain += LyricSegment(text = line.trim().take(384))
                }
            }
            return if (timed.isNotEmpty()) timed.sortedBy { it.timeMs } else plain
        }
    }
}
