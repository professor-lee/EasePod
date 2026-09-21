package app.easepod.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.easepod.EasePodApplication
import app.easepod.BuildConfig
import app.easepod.core.*
import app.easepod.plugins.*
import app.easepod.playback.DownloadPhase
import app.easepod.contract.CloudPlaylist
import app.easepod.contract.LibraryMutation
import app.easepod.contract.MutationResult
import app.easepod.contract.HomeCatalog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

data class Route(val id: String, val key: String = "", val title: String = "", val source: String = "", val focus: Int = 0, val draft: String = "", val focusKey: String? = null, val accountScope: String = "public", val pendingTrackId: String? = null, val localArtist: String? = null, val musicRoot: Boolean = false)
data class CoverItem(val id: String, val title: String, val artist: String, val artworkUri: String?, val kind: String = "ALBUM", val track: Track? = null)
data class LcdRow(val id: String, val title: String, val detail: String = "", val artwork: String? = null,
    val toggle: Boolean? = null, val color: Long? = null, val enabled: Boolean = true,
    val action: () -> Unit, val hold: (() -> Unit)? = null, val sourceId: String = LOCAL_SOURCE, val accountScope: String = "local")
data class LcdPage(val title: String, val rows: List<LcdRow> = emptyList(), val split: Boolean = false,
    val caption: String = "", val empty: String = "", val kind: String = "list")
data class LcdDialog(val title: String, val message: String, val rows: List<LcdRow>, val focus: Int = 0, val document: Boolean = false, val scroll: Int = 0)
data class ValueDraft(val title: String, val value: Int, val minimum: Int, val maximum: Int, val step: Int, val unit: String, val commit: (Int) -> Unit, val live: Boolean = false)
private data class PageContent(val tracks: List<Track>, val search: List<Track>, val items: List<CatalogEntry>, val cursor: String?, val remoteStatus: String, val searchStatus: String, val searchAppend: Boolean, val entryIds: List<String?>)
private val historyDate = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val NETEASE_PLUGIN_ID = "app.easepod.netease"
private const val NETEASE_PLUGIN_ASSET = "plugins/easepod-netease-plugin-0.1.0-debug.apk"
private const val INK_THEME_ID = "org.easepod.theme.ink"
private const val INK_THEME_ASSET = "plugins/easepod-ink-screen-1.0.2.ep-theme"
private const val INK_THEME_VERSION = "1.0.2"
sealed interface SystemEffect {
    data object PickFolder : SystemEffect
    data object PickPlugin : SystemEffect
    data class ExportBackup(val bytes: ByteArray) : SystemEffect
    data class ExportDiagnostics(val bytes: ByteArray) : SystemEffect
    data class OpenExternal(val url: String) : SystemEffect
    data object ImportBackup : SystemEffect
    data object Unlock : SystemEffect
    data class Install(val candidate: InstallCandidate) : SystemEffect
    data class Uninstall(val pluginId: String) : SystemEffect
    data class ApproveHost(val pluginId: String) : SystemEffect
}

class AppModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val app = application as EasePodApplication
    internal val deviceStatus = DeviceStatus(app)
    override fun onCleared() { deviceStatus.close(); super.onCleared() }
    internal var pageSource: PageSource = PluginPageSource(app.plugins, app.library)
    internal var accountSource: AccountSource = PluginAccountSource(app.plugins)
    internal var cloudLibrary: CloudLibraryAccess = app.plugins.cloudLibrary
    private var cloudReceipts by mutableStateOf(app.plugins.cloudLibrary.pending.value)
    private var remoteEntryIds by mutableStateOf(emptyList<String?>())
    var library by mutableStateOf(LibrarySnapshot()); private set
    var settings by mutableStateOf(AppSettings()); private set
    var playback by mutableStateOf(PlaybackSnapshot()); private set
    var scan by mutableStateOf(ScanProgress()); private set
    var pluginList by mutableStateOf(emptyList<PluginInfo>()); private set
    var themeList by mutableStateOf(emptyList<ThemeInfo>()); private set
    var route by mutableStateOf(Route("home")); private set
    var dialog by mutableStateOf<LcdDialog?>(null); private set
    var valueDraft by mutableStateOf<ValueDraft?>(null); private set
    var editing by mutableStateOf(false); private set
    var hidingIme by mutableStateOf(false); private set
    private var imeVisible = false
    var inputException by mutableStateOf(false); private set
    var locked by mutableStateOf(false); private set
    var gestureEpoch by mutableIntStateOf(0); private set
    var notice by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var nowMode by mutableStateOf("normal"); private set
    var coverPosition by mutableIntStateOf(0); private set
    var searchResults by mutableStateOf(emptyList<Track>()); private set
    var searchStatus by mutableStateOf(""); private set
    var remoteTracks by mutableStateOf(emptyList<Track>()); private set
    var lyricText by mutableStateOf("暂无歌词"); private set
    var accounts by mutableStateOf(emptyList<PluginAccount>()); private set
    var authSession by mutableStateOf<PluginAuthSession?>(null); private set
    var authStatus by mutableStateOf(""); private set
    var catalogItems by mutableStateOf(emptyList<CatalogEntry>()); private set
    var nextCursor by mutableStateOf<String?>(null); private set
    var remoteStatus by mutableStateOf(""); private set
    var lyricLines by mutableStateOf(emptyList<LyricLine>()); private set
    var lyricFocus by mutableIntStateOf(0); private set
    var installCandidate by mutableStateOf<InstallCandidate?>(null); private set
    var installStatus by mutableStateOf(""); private set
    private var installChecking by mutableStateOf(false)
    private var installPermissionGranted by mutableStateOf(false)
    private var installationRequestedPermission = false
    private var installFileAvailable by mutableStateOf(true)
    private var installCompleted by mutableStateOf(false)
    private var inspectingPluginFile = false
    private var installSystemActive by mutableStateOf(false)
    private var marketChecking by mutableStateOf(false)
    private val pendingInstallStore = PendingInstallStore(app)
    var previewThemeId by mutableStateOf<String?>(null); private set
    val activeThemeId get() = if (settings.safeMode) "silver" else previewThemeId ?: settings.themeId
    var downloads by mutableStateOf(app.player.downloads.downloads.value); private set
    var effectHandler: ((SystemEffect) -> Unit)? = null
    var lockQuery: (() -> Boolean)? = null
    private val backStack = mutableListOf<Route>()
    private val pageSnapshots = mutableMapOf<Int, PageContent>()
    private var searchJob: Job? = null
    private var searchAppend = false
    private var authJob: Job? = null
    private var lyricJob: Job? = null
    private var volumeJob: Job? = null
    private var seekJob: Job? = null
    private var lyricFollowJob: Job? = null
    private var transientVolume = false
    private var followingLyrics = true
    private var lyricTrackId: String? = null
    internal val selectedAccounts = mutableStateMapOf<String, String>()
    internal var selectedAccountsStore = SelectedAccountsStore(app)
    internal var musicSourceStore = MusicSourceStore(app)
    internal var musicSource by mutableStateOf<MusicSourceSelection?>(null); private set
    private var musicSourceChanged = false
    private val changedAccountSelections = mutableSetOf<String>()
    private var accountSelectionsRestored = false
    private var accountsPlugin: String? = null
    private var pageJob: Job? = null
    private var toastJob: Job? = null
    private var heldJob: Job? = null
    private var heldPosition: Long? = null
    private var seekDraft by mutableStateOf<Long?>(null)
    private var pendingUnlock: (() -> Unit)? = null
    private var pendingRoute: Route? = null
    private var backupPassword: CharArray? = null
    private var sensitiveContext = false
    private var generation = 0L
    private var restoringPage = false
    private var pageNeedsReload = false
    val currentPosition get() = seekDraft ?: playback.positionMs
    val sessionGeneration get() = generation
    fun acceptsFileResult(token: Long?): Boolean = token == generation && lockQuery?.invoke() == false
    fun artworkAllowed(sourceId: String, accountScope: String) = sourceId == LOCAL_SOURCE || !settings.safeMode && app.plugins.isAccountActive(sourceId, accountScope)
    val localTracks get() = library.tracks.filter { it.available && it.sourceId == LOCAL_SOURCE }
    val coverItems: List<CoverItem> get() = if (route.source.isBlank()) library.albums.map { CoverItem(it.id, it.title, it.artist, it.artworkUri) }
        else catalogItems.map { CoverItem(it.remoteId, it.title, it.subtitle, it.artworkUri, it.kind) } +
            remoteTracks.map { CoverItem(it.id, it.title, it.artist, it.artworkUri, "TRACK", it) }
    private val remotePageIds = setOf("songs", "albums", "artists", "genres", "playlists", "album", "playlist", "artist", "coverflow")
    private val backupMode get() = route.key.takeIf { route.id == "storage" && it.startsWith("backup:") }?.substringAfter(':')

    init {
        restoreNavigation()
        viewModelScope.launch { app.library.library.collect {
            val albumId = selectedAlbum()?.id
            library = it
            val selectedId = albumId ?: savedState.get<String>("coverAlbum")
            if (route.source.isBlank() && selectedId != null) { val index = it.albums.indexOfFirst { album -> album.id == selectedId }; if (index >= 0) coverPosition += index - Math.floorMod(coverPosition, it.albums.size) }
            clampFocus()
        } }
        viewModelScope.launch { app.library.scan.collect { scan = it; clampFocus() } }
        viewModelScope.launch { app.settings.settings.collect {
            if (settings.touchGuard != it.touchGuard || settings.safeMode != it.safeMode) gestureEpoch++
            settings = it
            if (it.safeMode) { if (musicSource != null) selectMusicSource(null); pageJob?.cancel(); searchJob?.cancel(); cancelAuth(); remoteStatus = "安全模式" }
            clampFocus()
        } }
        viewModelScope.launch { app.player.playback.collect {
            val changedTrack = playback.currentEntryId != it.currentEntryId
            playback = it
            if (changedTrack) { endHold(true); nowMode = "normal"; seekDraft = null; transientVolume = false; loadLyrics() }
            if (followingLyrics && lyricTrackId == it.current?.id) {
                val line = lyricLines.indexOfLast { line -> line.timeMs?.let { time -> time <= it.positionMs } == true }
                if (line >= 0) lyricFocus = line
            }
        } }
        viewModelScope.launch { app.plugins.plugins.collect { list ->
            pluginList = list
            if (accountSelectionsRestored) selectedAccounts.keys.toList().filter { id -> list.none { it.id == id } }.forEach { clearSelectedAccount(it) }
            if (accountSelectionsRestored && musicSource?.let { source -> list.none { it.id == source.pluginId && it.enabled && it.approved } } == true) selectMusicSource(null)
            clampFocus()
        } }
        viewModelScope.launch {
            app.plugins.awaitAccountState()
            var knownAccounts = app.plugins.activeAccountScopes.value
            app.plugins.activeAccountScopes.collect { active ->
                (knownAccounts - active).forEach { (plugin, account) -> clearSelectedAccount(plugin, account) }
                knownAccounts = active
            }
        }
        viewModelScope.launch { app.plugins.cloudLibrary.pending.collect { cloudReceipts = it } }
        viewModelScope.launch { app.themes.themes.collect { themeList = it } }
        viewModelScope.launch { app.player.downloads.downloads.collect { downloads = it } }
        work {
            app.plugins.refresh()
            app.awaitStartupResources()
            app.plugins.awaitAccountState()
            selectedAccountsStore.restore(app.plugins.plugins.value.map { it.id }.toSet(), app.plugins.activeAccountScopes.value)
                .filterKeys { it !in changedAccountSelections }.forEach { (plugin, account) -> selectedAccounts[plugin] = account }
            accountSelectionsRestored = true
            if (!musicSourceChanged) {
                val stored = if (musicSourceStore.hasSelection()) musicSourceStore.read() else selectedAccounts.entries
                    .firstOrNull { (id, _) -> pluginList.any { it.id == id && it.enabled && it.approved && "catalog.browse" in it.capabilities } }
                    ?.let { MusicSourceSelection(it.key, it.value) }
                val valid = stored?.takeIf { !settings.safeMode && (it.pluginId to it.accountScope) in app.plugins.activeAccountScopes.value &&
                    pluginList.any { p -> p.id == it.pluginId && p.enabled && p.approved } }
                musicSource = valid; musicSourceStore.select(valid)
                if (route.musicRoot) route = rebindMusicRoute(route)
            }
            fun available(saved: Route): Boolean = saved.source.isBlank() || !settings.safeMode &&
                pluginList.any { it.id == saved.source && it.enabled && it.approved } &&
                (saved.accountScope == "public" || (saved.source to saved.accountScope) in app.plugins.activeAccountScopes.value)
            backStack.removeAll { !available(it) }
            backStack.indices.forEach { index -> if (backStack[index].musicRoot) backStack[index] = rebindMusicRoute(backStack[index]) }
            if (!available(route)) route = Route("music")
            restorePendingInstall()
            app.library.library.first { it.ready }
            if (restoringPage) { restoringPage = false; reloadPage(); clampFocus() }
        }
    }

    fun toast(text: String) { notice = text; toastJob?.cancel(); toastJob = viewModelScope.launch { delay(2800); notice = "" } }
    private fun restoreNavigation() {
        val stack = savedState.get<ArrayList<Bundle>>("navigation")?.mapNotNull { it.toRouteOrNull() }.orEmpty()
        if (stack.isNotEmpty()) { backStack.addAll(stack.dropLast(1)); route = stack.last(); restoringPage = true }
    }
    private fun saveNavigation() {
        savedState["navigation"] = ArrayList((backStack + route).takeLast(32).map { it.toSavedBundle() })
    }
    fun work(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message?.take(140) ?: "操作失败，请重试") } } }
    fun updateSettings(transform: (AppSettings) -> AppSettings) = work { app.settings.update(transform) }
    private fun item(title: String, detail: String = "", id: String = title, toggle: Boolean? = null, action: () -> Unit) = LcdRow(id, title, detail, toggle = toggle, action = action)
    private fun nav(title: String, id: String, detail: String = "", key: String = "", source: String = "") = item(title, detail) { navigate(Route(id, key, title, source)) }
    private fun musicRoute(id: String, title: String = ""): Route {
        val source = musicSource
        val home = pluginList.any { it.id == source?.pluginId && HomeCatalog.CAPABILITY in it.capabilities }
        val key = if (!home) "" else when (id) {
            "coverflow" -> HomeCatalog.COVERS
            "playlists" -> HomeCatalog.PLAYLISTS
            "artists" -> HomeCatalog.ARTISTS
            "albums" -> HomeCatalog.ALBUMS
            "songs" -> HomeCatalog.TRACKS
            "genres" -> HomeCatalog.GENRES
            else -> ""
        }
        return Route(id, key, title, source?.pluginId.orEmpty(), accountScope = source?.accountScope ?: "local", musicRoot = true)
    }
    private fun rebindMusicRoute(previous: Route): Route {
        val target = musicRoute(previous.id, previous.title)
        val daily = previous.key == HomeCatalog.DAILY
        return target.copy(key = if (daily && target.key == HomeCatalog.TRACKS) HomeCatalog.DAILY else target.key,
            title = if (daily && target.source.isBlank()) "歌曲" else target.title,
            draft = previous.draft, focus = previous.focus, focusKey = previous.focusKey)
    }
    private fun musicNav(title: String, id: String, detail: String = "") = item(title, detail) { navigate(musicRoute(id, title)) }
    internal fun selectMusicSource(selection: MusicSourceSelection?) {
        if (selection != null && (settings.safeMode || pluginList.none { it.id == selection.pluginId && it.enabled && it.approved })) {
            toast("音乐服务不可用"); return
        }
        musicSourceStore.select(selection); musicSourceChanged = true
        if (musicSource == selection) return
        musicSource = selection
        generation++; pageJob?.cancel(); searchJob?.cancel(); busy = false
        remoteTracks = emptyList(); searchResults = emptyList(); catalogItems = emptyList(); remoteEntryIds = emptyList()
        nextCursor = null; remoteStatus = ""; searchStatus = ""; searchAppend = false; coverPosition = 0
        savedState.remove<String>("coverAlbum")
        // Cached pages belong to the source/account that produced them.
        pageSnapshots.clear()
        backStack.removeAll { it.source.isNotBlank() || it.musicRoot || it.id in remotePageIds || it.id == "search" }
        if (route.musicRoot) route = rebindMusicRoute(route).copy(focus = 0, focusKey = null)
        else if (route.source.isNotBlank() || route.id in remotePageIds || route.id == "search") route = Route("music")
        dialog = null; finishEditing(); reloadPage(); clampFocus(); saveNavigation()
    }
    private fun chooseMusicSource() {
        dialog = LcdDialog("音乐来源", "", listOf(item("本地音乐", if (musicSource == null) "当前来源" else "") { selectMusicSource(null); cancelDialog() }) +
            pluginList.filter { it.enabled && it.approved && !settings.safeMode && "catalog.browse" in it.capabilities && selectedAccounts[it.id] != null }
                .map { p -> item(p.name, if (musicSource?.pluginId == p.id) "当前来源" else "") {
                    selectMusicSource(MusicSourceSelection(p.id, selectedAccounts.getValue(p.id))); cancelDialog()
                } } + listOf(nav("音乐服务", "services"), item("取消") { cancelDialog() }))
    }
    private fun accountReadFailed(error: Exception, owner: Route): Boolean {
        if (error !is PluginException || error.code !in setOf("AuthRequired", "AuthExpired") || owner.accountScope in setOf("public", "local")) return false
        val token = generation
        clearSelectedAccount(owner.source, owner.accountScope)
        if (token == generation) {
            remoteTracks = emptyList(); searchResults = emptyList(); catalogItems = emptyList(); remoteEntryIds = emptyList(); nextCursor = null
            remoteStatus = "账号需要重新登录"; searchStatus = "账号需要重新登录"
        }
        toast("账号需要重新登录")
        return true
    }
    private fun shuffleMusic() {
        val source = musicSource
        if (source == null) {
            if (localTracks.isEmpty()) toast("没有可播放的本地音乐") else { app.player.play(localTracks); app.player.shuffle(true); navigate(Route("now")) }
            return
        }
        val token = generation
        pageJob?.cancel(); busy = true
        pageJob = viewModelScope.launch {
            try {
                val tracks = mutableListOf<Track>(); val seen = mutableSetOf<String>(); var cursor: String? = null
                val parent = musicRoute("songs").key.takeIf { it.isNotBlank() }
                do {
                    val page = pageSource.browse(source.pluginId, parent, cursor, source.accountScope)
                    tracks += page.tracks.filter(::playable)
                    cursor = page.nextCursor?.takeIf { seen.add(it) }
                } while (cursor != null && tracks.size < 500 && seen.size < 20 && token == generation)
                if (token != generation) return@launch
                val queue = tracks.distinctBy { it.id }.take(500).shuffled()
                if (queue.isEmpty()) toast("没有可播放的歌曲") else {
                    app.library.rememberTracks(queue)
                    if (token == generation) { app.player.play(queue); app.player.shuffle(true); navigate(Route("now")) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (token == generation) toast("读取失败，请重试") }
            finally { if (token == generation) busy = false }
        }
    }
    private fun playable(track: Track): Boolean = track.available && artworkAllowed(track.sourceId, track.accountScope)
    private fun rowsForTracks(tracks: List<Track>, entryIds: List<String?>? = null): List<LcdRow> = tracks.mapIndexed { index, track ->
        LcdRow(entryIds?.getOrNull(index) ?: track.id, track.title, if (playable(track)) track.artist else "不可用", track.artworkUri,
            action = { if (!playable(track)) toast("音乐不可用，请检查目录或来源") else { app.player.play(tracks, index); navigate(Route("now")) } },
            hold = { trackMenu(track, entryIds?.getOrNull(index)) }, sourceId = track.sourceId, accountScope = track.accountScope)
    }
    private fun historyTime(entry: HistoryEntry) = historyDate.format(Instant.ofEpochMilli(entry.playedAt).atZone(ZoneId.systemDefault()))
    private fun historyRows() = library.history.map { entry ->
        val track = entry.track
        val source = if (track.sourceId == LOCAL_SOURCE) "本地音乐" else pluginList.find { it.sourceId == track.sourceId }?.name ?: "来源不可用"
        LcdRow("history:${entry.id}", track.title, "${historyTime(entry)} · ${if (playable(track)) track.artist else "不可用"} · $source", track.artworkUri,
            action = { playHistory(entry) }, hold = { historyMenu(entry) }, sourceId = track.sourceId, accountScope = track.accountScope)
    }
    private fun playHistory(entry: HistoryEntry) {
        if (!playable(entry.track)) toast("音乐不可用，请检查目录或来源")
        else { app.player.play(listOf(entry.track)); navigate(Route("now")) }
    }
    private fun historyMenu(entry: HistoryEntry) {
        val plugin = pluginList.find { it.sourceId == entry.track.sourceId }
        dialog = LcdDialog(entry.track.title, "${entry.track.artist}\n${historyTime(entry)}", listOf(
            item("再次播放") { cancelDialog(); playHistory(entry) },
            item("曲目操作") { trackMenu(entry.track) },
            if (entry.track.sourceId == LOCAL_SOURCE) nav("本地音乐文件夹", "local-folders")
            else if (plugin != null) nav("插件详情", "plugin", key = plugin.id) else nav("安装音乐服务", "store"),
            item("移除记录") { sensitive { cancelDialog(); work { app.library.removeHistory(entry.id) } } },
            item("取消") { cancelDialog() },
        ))
    }
    fun page(): LcdPage {
        val root = library.root
        val selectedPlugin = pluginList.find { it.id == route.key }
        val selectedPlaylist = library.playlists.find { it.id == route.key }
        fun list(title: String, rows: List<LcdRow>, empty: String = "", caption: String = "") = LcdPage(title, rows, empty = empty, caption = caption)
        return when (route.id) {
            "home" -> LcdPage("EasePod", listOf(nav("音乐", "music"), nav("设置", "settings"), item("随机播放歌曲") { shuffleMusic() }, nav("正在播放", "now")), split = true)
            "music" -> LcdPage("音乐", listOf(musicNav("Cover Flow", "coverflow"), musicNav("歌单", "playlists"), musicNav("艺人", "artists"), musicNav("专辑", "albums"), musicNav("歌曲", "songs", if (musicSource == null) "${localTracks.size}" else ""), musicNav("流派", "genres"), musicNav("搜索", "search"), nav("最近播放", "history")) +
                listOf(item("音乐来源", musicSource?.let { source -> pluginList.find { it.id == source.pluginId }?.name } ?: "本地音乐") { chooseMusicSource() }), split = true)
            "songs" -> if (route.source.isNotBlank()) list(route.title.ifBlank { "歌曲" }, remoteRows(), if (busy) "正在读取" else "没有歌曲", remoteStatus)
                else list(route.title.ifBlank { "歌曲" }, rowsForTracks(localTracks.filter { (route.localArtist == null || it.artist == route.localArtist) && (route.key.isBlank() || it.genre == route.key) }).ifEmpty { listOf(nav("本地音乐文件夹", "local-folders")) }, if (localTracks.isEmpty()) "还没有音乐" else "")
            "artists" -> if (route.source.isNotBlank()) list("艺人", remoteRows(), if (busy) "正在读取" else "没有艺人", remoteStatus)
                else list("艺人", localTracks.map { it.artist }.distinct().sorted().map { nav(it, "artist", key = it) }, "没有艺人")
            "artist" -> if (route.source.isNotBlank()) list(route.title, remoteRows(), if (busy) "正在读取" else "没有音乐", remoteStatus) else {
                val tracks = localTracks.filter { it.artist == route.key }
                val albums = library.albums.filter { album -> tracks.any { it.albumId == album.id } }
                list(route.key, (if (tracks.isEmpty()) emptyList() else listOf(item("播放全部", "${tracks.size} 首") { app.player.play(tracks); navigate(Route("now")) }, item("全部歌曲", "${tracks.size} 首") { navigate(Route("songs", title = route.key, localArtist = route.key)) })) + albums.map { nav(it.title, "album", key = it.id) } + rowsForTracks(tracks.filter { it.albumId == null }), "没有音乐")
            }
            "albums" -> if (route.source.isNotBlank()) list("专辑", remoteRows(), if (busy) "正在读取" else "没有专辑", remoteStatus)
                else list("专辑", library.albums.map { nav(it.title, "album", "${it.trackIds.size} 首", it.id) }, "没有专辑")
            "album" -> if (route.source.isNotBlank()) list(route.title, remoteRows(), if (busy) "正在读取" else "没有歌曲", remoteStatus)
                else library.albums.find { it.id == route.key }?.let { a ->
                    val tracks = a.trackIds.mapNotNull { id -> library.tracks.find { it.id == id } }
                    list(a.title, listOf(item("播放全部") { if (tracks.isNotEmpty()) { app.player.play(tracks); navigate(Route("now")) } }) + rowsForTracks(tracks), "没有歌曲", a.artist)
                } ?: list("专辑", emptyList(), "专辑不可用")
            "genres" -> if (route.source.isNotBlank()) list("流派", remoteRows(), if (busy) "正在读取" else "没有流派", remoteStatus)
                else list("流派", localTracks.map { it.genre }.distinct().sorted().map { nav(it, "songs", key = it) }, "没有流派")
            "playlists" -> if (route.source.isNotBlank()) list("歌单", remoteRows(), if (busy) "正在读取" else "没有歌单", remoteStatus)
                else list("歌单", listOf(item("新建歌单") { navigate(Route("create-playlist", draft = "")) }) + library.playlists.map { nav(it.title, "playlist", "${it.entries.size} 首", it.id) })
            "playlist" -> if (route.source.isNotBlank()) list(route.title, remoteRows(), if (busy) "正在读取" else "没有歌曲", remoteStatus) else list(selectedPlaylist?.title ?: "歌单", selectedPlaylist?.let { p ->
                val pairs = p.entries.mapNotNull { e -> library.tracks.find { it.id == e.trackId }?.let { e.id to it } }
                listOf(item("播放全部") { val tracks = pairs.map { it.second }.filter(::playable); if (tracks.isEmpty()) toast("歌单中没有可播放音乐") else { app.player.play(tracks); navigate(Route("now")) } }, item("歌单选项") { playlistMenu(p) }) + rowsForTracks(pairs.map { it.second }, pairs.map { it.first }) + if (pairs.isEmpty()) listOf(nav("添加歌曲", "songs")) else emptyList()
            } ?: emptyList(), "歌单不可用")
            "create-playlist" -> LcdPage(if (route.key.isBlank()) "新建歌单" else "重命名歌单", listOf(item("编辑名称") { beginInput() }, item(if (route.key.isBlank()) "创建歌单" else "保存名称") { savePlaylist() }, item("取消") { back() }), kind = "form")
            "coverflow" -> LcdPage("Cover Flow", (if (coverItems.isEmpty()) {
                if (route.source.isBlank()) listOf(nav("本地音乐文件夹", "local-folders")) else emptyList()
            } else listOf(item(when (selectedCover()?.kind?.uppercase()) { "PLAYLIST" -> "打开歌单"; "TRACK" -> "播放歌曲"; "ALBUM" -> "打开专辑"; else -> "打开" }) { selectCover(0) }.copy(hold = {
                dialog = LcdDialog("Cover Flow", remoteStatus, pagingRows(false).map { row -> row.copy(action = { cancelDialog(); row.action() }) } + listOf(item("取消") { cancelDialog() }))
            }))) +
                if (route.source.isNotBlank()) pagingRows(false) else emptyList(), caption = remoteStatus,
                empty = if (busy && coverItems.isEmpty()) "正在读取" else if (coverItems.isEmpty()) "没有封面" else "", kind = "coverflow")
            "history" -> list("最近播放", historyRows() + if (library.history.isNotEmpty()) listOf(item("清空历史") { sensitive { confirm("清空播放历史？", "") { work { app.library.clearHistory() } } } }) else listOf(nav("歌曲", "songs")), "尚无播放记录")
            "search" -> LcdPage("搜索", listOf(item("输入搜索词") { beginInput() }, item("来源", if (route.source.isBlank()) "本地音乐" else pluginList.find { it.id == route.source }?.name.orEmpty()) { chooseSearchSource() }) + catalogRows() + rowsForTracks(searchResults) + pagingRows(true), caption = searchStatus, kind = "search")
            "now" -> LcdPage("正在播放", if (playback.current == null) listOf(nav("音乐", "music")) else emptyList(), empty = if (playback.current == null) "尚未播放" else "", kind = "now")
            "queue" -> list("播放队列", playback.queue.map { entry -> LcdRow(entry.id, entry.track.title, if (entry.id == playback.currentEntryId) "当前" else entry.track.artist, entry.track.artworkUri,
                action = { app.player.jump(entry.id); navigate(Route("now")) }, hold = { queueMenu(entry) }, sourceId = entry.track.sourceId, accountScope = entry.track.accountScope) } + if (playback.queue.isNotEmpty()) listOf(item("清空队列") { confirm("清空队列？", "播放将停止。") { app.player.clear() } }) else emptyList(), "队列为空")
            "lyrics" -> LcdPage("歌词", if (lyricLines.isEmpty() && lyricText == "歌词读取失败") listOf(item("重试") { retryLyrics() }) else emptyList(), caption = lyricText, kind = "lyrics")
            "settings" -> LcdPage("设置", listOf(nav("主题", "themes", themeName()), nav("轮盘", "wheel-settings"), nav("播放设置", "audio-settings"), nav("睡眠定时", "sleep", if (playback.sleepEndsAt == null) "关闭" else "已设定"), item("锁屏覆盖", toggle = settings.lockScreenOverlay) {
                if (settings.lockScreenOverlay) updateSettings { it.copy(lockScreenOverlay = false) } else confirm("开启锁屏覆盖？", "锁定时，歌曲、封面、歌词与音乐列表可能被旁人看到。") { updateSettings { it.copy(lockScreenOverlay = true) } }
            }, item("全屏显示", toggle = settings.fullScreen) { updateSettings { it.copy(fullScreen = !it.fullScreen) } }, item("防误触", toggle = settings.touchGuard) { updateSettings { it.copy(touchGuard = !it.touchGuard) } }, nav("插件", "plugins"), nav("本地与存储", "storage"), nav("离线音乐", "downloads"), nav("关于", "about")), split = true)
            "themes" -> list("主题", themeList.map { theme -> LcdRow(theme.id, theme.name, if (settings.themeId == theme.id) "已选" else theme.version, color = theme.palette.frameTop,
                action = { previewTheme(theme) }, hold = { themeMenu(theme) }) } + listOf(nav("更多主题", "store")))
            "wheel-settings" -> list("轮盘设置", listOf(item("旋转灵敏度", "${WheelGeometry.stepAngle(settings.wheelSensitivity).toInt()}°") { editValue("旋转灵敏度", settings.wheelSensitivity, 1, 3, 1, "") { value -> updateSettings { it.copy(wheelSensitivity = value) } } }, item("触觉反馈", toggle = settings.haptics) { updateSettings { it.copy(haptics = !it.haptics) } }, item("点击音", toggle = settings.clickSound) { updateSettings { it.copy(clickSound = !it.clickSound) } }, item("大字体", toggle = settings.largeText) { updateSettings { it.copy(largeText = !it.largeText) } }, item("减少动态效果", toggle = settings.reducedMotion) { updateSettings { it.copy(reducedMotion = !it.reducedMotion) } }))
            "audio-settings" -> list("播放设置", listOf(item("随机播放", toggle = playback.shuffle) { app.player.shuffle(!playback.shuffle) }, item("重复", repeatName()) { app.player.repeat(RepeatMode.entries[(playback.repeat.ordinal + 1) % 3]) }, item("音量", "${(playback.volume * 100).toInt()}%") { editValue("音量", (playback.volume * 100).toInt(), 0, 100, 5, "%") { app.player.volume(it / 100f) } }) +
                (if (qualityOptions().size > 1) listOf(item("网络音质", qualityName(settings.networkQuality)) { chooseQuality() }) else emptyList()) +
                (if (playback.current != null) listOf(item("实际音质", qualityName(playback.actualQuality)) { message("实际音质", qualityName(playback.actualQuality)) }) else emptyList()) +
                listOf(item("音频输出", deviceStatus.audioOutput.ifBlank { "系统默认" }) { message("音频输出", deviceStatus.audioOutput.ifBlank { "系统默认" }) }, nav("睡眠定时", "sleep")))
            "sleep" -> list("睡眠定时", listOf(0, 15, 30, 60).map { minutes -> item(if (minutes == 0) "关闭" else "$minutes 分钟后停止") { app.player.sleep(minutes); back() } })
            "plugins" -> list("插件管理", listOf(nav("Local Library", "local-folders", "内置"), nav("经典主题", "themes", "内置")) + pluginList.map { nav(it.name, "plugin", it.status, it.id) } + themeList.filter { it.id !in setOf("silver", "black", "oled") }.map { theme -> item(theme.name, "主题") { themeMenu(theme) } } + listOf(nav("安装插件", "store"), item("安全模式", toggle = settings.safeMode) { updateSettings { it.copy(safeMode = !it.safeMode) } }))
            "plugin" -> list(selectedPlugin?.name ?: "插件详情", selectedPlugin?.let(::pluginRows) ?: emptyList(), "插件不可用", selectedPlugin?.status.orEmpty())
            "services" -> list("音乐服务", (if (settings.safeMode) listOf(nav("插件管理", "plugins")) else pluginList.filter { it.enabled }.map { nav(it.name, "service", it.status, it.id) } + listOf(nav("添加音乐服务", "store"))) + if (cloudReceipts.isNotEmpty()) listOf(item("待确认操作", "${cloudReceipts.size}") { pendingCloudMenu() }) else emptyList(), if (settings.safeMode) "安全模式" else if (pluginList.none { it.enabled }) "未启用音乐服务" else "")
            "service" -> list(selectedPlugin?.name ?: "音乐服务", selectedPlugin?.let { p -> listOf(nav("账号", "login", key = p.id)) + (if ("catalog.search" in p.capabilities) listOf(item("搜索音乐") { navigate(Route("search", source = p.id, accountScope = selectedAccounts[p.id] ?: "public")) }) else emptyList()) + (if ("catalog.browse" in p.capabilities) listOf(item("浏览歌曲") { navigate(Route("songs", title = p.name, source = p.id, accountScope = selectedAccounts[p.id] ?: "public")) }) else emptyList()) + cloudServiceRows(p) + listOf(nav("插件详情", "plugin", key = p.id)) } ?: emptyList(), "服务不可用")
            "login" -> LcdPage("账号", accounts.map { a -> item(a.displayName, if (selectedAccounts[route.key] == a.scope) "当前账号" else a.state) { accountMenu(a) } } +
                (if ("account.qr" in selectedPlugin?.capabilities.orEmpty()) listOf(item(if (authSession == null) "继续授权" else "刷新二维码") { startAuth() }) else emptyList()) +
                listOf(item("刷新账号") { loadAccounts() }, item("取消") { cancelAuth(); back() }), caption = authStatus, empty = if (accounts.isEmpty() && authSession == null) "尚未登录" else "", kind = "auth")
            "market" -> pluginMarketPage()
            "store" -> list("安装插件", listOfNotNull(nav("插件市场", "market", "本地"), installCandidate?.let { item("继续安装", it.name) { sensitive { navigate(Route("install")) } } }, item("从文件安装") { if (installSystemActive) toast("请先完成系统安装流程") else sensitive { effectHandler?.invoke(SystemEffect.PickPlugin) } }, item("刷新已安装插件") { work { app.plugins.refresh(); toast("插件列表已更新") } }), caption = "本地 APK / .ep-theme")
            "install" -> {
                val candidate = installCandidate
                val installed = candidate?.let(::matchingInstalledPlugin)
                list("安装插件", if (installed != null || installCompleted) listOfNotNull(installed?.let { nav("插件详情", "plugin", it.status, it.id) }, item("完成") { discardInstall(); back() })
                    else listOf(LcdRow("install-confirm", if (installSystemActive) "等待系统确认" else if (installChecking) "正在核验" else if (installPermissionGranted) "继续安装" else "安装", enabled = candidate != null && installFileAvailable && !installChecking && !installSystemActive, action = { installSelected() }), item(if (installSystemActive) "返回" else "取消") { discardInstall(); back() }),
                    caption = candidate?.let { "${it.name} · ${it.version}\n${if (installed != null) "已安装 · ${installed.status}" else installStatus.ifBlank { if (it.kind == "theme") "主题包" else "独立 APK · 未收录" }}\n${it.signature}" } ?: "未选择安装文件")
            }
            "storage" -> if (backupMode != null) LcdPage(if (backupMode == "import") "导入备份" else "导出备份", listOf(item("输入备份口令") { beginInput() }, item("继续") { submitBackup() }, item("取消") { back() }), caption = "口令至少 8 个字符", kind = "password")
                else list("本地与存储", listOf(nav("本地音乐文件夹", "local-folders", root?.name ?: "未选择"), item("重新扫描") { rescan() }, nav("目录授权", "permission", if (root?.permissionValid == true) "已授权" else "未授权"), item("缓存上限", "${settings.cacheLimitMb} MB") { editValue("缓存上限", settings.cacheLimitMb, 64, 2048, 64, " MB") { value -> updateSettings { it.copy(cacheLimitMb = value) } } }, item("清除流媒体缓存") { sensitive { confirm("清除流媒体缓存？", "已保存的离线音乐不会删除。") { work { app.player.clearStreamCache(); toast("缓存已清除") } } } }, item("导出歌单与设置") { backupForm(false) }, item("导入备份") { backupForm(true) }), caption = "本地音乐 ${localTracks.size} 首 · 缓存 ${app.player.cacheBytes / 1024 / 1024} MB")
            "local-folders" -> when (scan.phase) {
                ScanPhase.SCANNING -> list("本地音乐文件夹", listOf(item("取消扫描") { app.library.cancelScan() }), caption = "正在扫描 ${scan.folder}\n${scan.tracks} 首 · ${scan.albums} 张专辑")
                ScanPhase.REVIEW -> list("本地音乐文件夹", listOf(item("应用此文件夹") { applyScannedFolder() }, item("查看扫描结果") { scanReport() }, item("取消") { app.library.cancelScan() }), caption = "扫描完成\n${scan.tracks} 首 · ${scan.albums} 张专辑 · 跳过 ${scan.skipped} 项")
                ScanPhase.FAILED -> list("本地音乐文件夹", listOf(item("重试") { rescan() }, item("更换文件夹") { chooseFolder() }, item("返回当前文件夹") { app.library.cancelScan() }), caption = scan.message ?: "扫描失败，原音乐库已保留")
                else -> list("本地音乐文件夹", listOf(item(if (root == null) "选择文件夹" else "更换文件夹") { chooseFolder() }) + if (root != null) listOf(item("重新扫描") { rescan() }, item("扫描结果") { scanReport() }, item("移除文件夹") { sensitive { confirm("移除音乐文件夹？", "保留原始文件，歌单和队列中的引用将标记为不可用。") { work { app.library.removeRoot() } } } }) else emptyList(), caption = root?.let { "${it.name} · ${if (it.permissionValid) "已授权" else "授权失效"}\n${localTracks.size} 首 · ${library.albums.size} 张专辑" } ?: "尚未选择文件夹")
            }
            "permission" -> list("目录授权", listOf(item("重新选择文件夹") { chooseFolder() }, item("返回") { back() }), caption = root?.name ?: "尚未选择文件夹", empty = if (root?.permissionValid == true) "目录已授权" else "需要选择音乐文件夹")
            "downloads" -> list("离线音乐", listOf(item("仅 Wi-Fi 下载", toggle = settings.wifiDownloadsOnly) { updateSettings { it.copy(wifiDownloadsOnly = !it.wifiDownloadsOnly) } }) + downloads.map { d -> item(d.track.title, "${downloadName(d.phase)} ${if (d.totalBytes != null && d.totalBytes!! > 0) "${d.downloadedBytes * 100 / d.totalBytes!!}%" else ""}", d.id) { downloadMenu(d.id) } }, if (downloads.isEmpty()) "没有离线音乐" else "")
            "about" -> list("关于", listOf(item("开源许可") { showLicense("AGPL-3.0-only", "LICENSE") }, item("第三方许可") { thirdPartyLicenses() }, item("参考致谢") { referenceCredits() }, item("隐私") { message("隐私", "音乐目录仅在你授权的范围读取。账号凭据由所属音乐服务插件保存。无默认遥测，不上传本地音乐或搜索记录。备份使用口令加密，不包含账号凭据。") }, item("诊断预览") { diagnosticPreview() }), caption = "EasePod ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid ${android.os.Build.VERSION.RELEASE} · AGPL-3.0-only")
            else -> list("EasePod", listOf(nav("主菜单", "home")), "页面不可用")
        }
    }

    /** Bundled market entries use the same review path as manually selected files. */
    private fun pluginMarketPage(): LcdPage {
        val installed = pluginList.find { it.id == NETEASE_PLUGIN_ID }
        val inkTheme = themeList.find { it.id == INK_THEME_ID }
        val status = installed?.let { "${it.status} · 查看详情" } ?: if (marketChecking) "正在读取安装包" else "未安装 · 安装"
        val action = if (installed != null) {
            { navigate(Route("plugin", key = installed.id, title = installed.name)) }
        } else {
            {
                if (installSystemActive) toast("请先完成系统安装流程")
                else if (marketChecking) Unit
                else sensitive { installBundledNetease() }
            }
        }
        val themeUpdateAvailable = inkTheme != null && inkTheme.version != INK_THEME_VERSION
        val themeStatus = inkTheme?.let { if (themeUpdateAvailable) "${it.version} · 可更新" else "已安装 · 管理主题" } ?: if (marketChecking) "正在读取安装包" else "未安装 · 安装"
        val themeAction: () -> Unit = if (inkTheme != null && !themeUpdateAvailable) {
            { themeMenu(inkTheme) }
        } else {
            { if (installSystemActive) toast("请先完成系统安装流程") else if (!marketChecking) sensitive { installBundledInkTheme() } }
        }
        val rows = listOf(
            item("网易云音乐", status, NETEASE_PLUGIN_ID, action = action),
            item("墨水屏", themeStatus, INK_THEME_ID, action = themeAction)
        )
        return LcdPage("插件市场", rows,
            caption = "随 EasePod 提供的本地插件\n选择插件可安装或查看详情")
    }

    /** Stage the APK shipped in the host assets and enter the normal install review. */
    private fun installBundledNetease() {
        if (marketChecking) return
        installCandidate?.let { navigate(Route("install")); return }
        marketChecking = true
        val token = generation
        work {
            try {
                val candidate = app.plugins.inspectBundledApk(NETEASE_PLUGIN_ASSET)
                if (token != generation || lockQuery?.invoke() != false) {
                    withContext(Dispatchers.IO) { File(candidate.filePath).delete() }
                    return@work
                }
                val verified = try { pendingInstallStore.save(candidate) }
                    catch (failure: Exception) {
                        withContext(Dispatchers.IO) { File(candidate.filePath).delete() }
                        throw failure
                    }
                if (token == generation && lockQuery?.invoke() == false) {
                    installCandidate = verified
                    installFileAvailable = true
                    installCompleted = matchingInstalledPlugin(verified) != null
                    installStatus = "来自插件市场"
                    navigate(Route("install"))
                } else pendingInstallStore.clear(verified)
            } finally { marketChecking = false }
        }
    }

    /** Stage the bundled theme in the same review flow as a user-selected archive. */
    private fun installBundledInkTheme() {
        if (marketChecking) return
        installCandidate?.let { navigate(Route("install")); return }
        marketChecking = true
        val token = generation
        work {
            val target = File(app.cacheDir, "plugin-review-${System.nanoTime()}")
            var retainTarget = false
            try {
                val verified = withContext(Dispatchers.IO) {
                    app.assets.open(INK_THEME_ASSET).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    val theme = app.themes.inspect(target.path)
                    val candidate = InstallCandidate("theme", theme.name, theme.version, target.path, pluginId = theme.id)
                    pendingInstallStore.save(candidate)
                }
                retainTarget = true
                if (token == generation && lockQuery?.invoke() == false) {
                    installCandidate = verified
                    installFileAvailable = true
                    installCompleted = false
                    installStatus = "来自插件市场"
                    navigate(Route("install"))
                } else pendingInstallStore.clear(verified)
            } finally {
                if (!retainTarget) withContext(Dispatchers.IO) { target.delete() }
                marketChecking = false
            }
        }
    }

    fun navigate(target: Route) {
        restoringPage = false; pageNeedsReload = false; lyricFollowJob?.cancel()
        endHold(true); endRotation(true); cancelAuth(); generation++; pageJob?.cancel(); searchJob?.cancel(); finishEditing(); cancelDialog(); valueDraft = null
        pageSnapshots[backStack.size] = PageContent(remoteTracks, searchResults, catalogItems, nextCursor, remoteStatus, searchStatus, searchAppend, remoteEntryIds)
        backStack.add(route); route = target; nowMode = "normal"; remoteTracks = emptyList(); searchResults = emptyList(); searchStatus = ""
        catalogItems = emptyList(); nextCursor = null; remoteStatus = ""; busy = false; searchAppend = false; remoteEntryIds = emptyList()
        if (target.id == "search" || target.id == "create-playlist") { if (!settings.touchGuard) beginInput() }
        if (target.id == "lyrics") loadLyrics()
        if (target.id == "login") loadAccounts()
        if (target.source.isNotBlank() && target.id in remotePageIds) loadRemote(target.source)
        clampFocus()
    }
    fun back(): Boolean {
        endHold(true); endRotation(true)
        if (hidingIme) return true
        if (editing) { finishEditing(); return true }
        if (dialog != null) { cancelDialog(); return true }
        if (valueDraft != null) { valueDraft = null; return true }
        if (route.id == "now" && nowMode != "normal") { nowMode = "normal"; seekDraft = null; return true }
        return popRoute()
    }
    private fun popRoute(): Boolean {
        if (backStack.isEmpty()) return false
        restoringPage = false; pageNeedsReload = false; lyricFollowJob?.cancel()
        if (route.id == "install") discardInstall()
        cancelAuth(); generation++; pageJob?.cancel(); searchJob?.cancel(); pendingUnlock = null; backupPassword?.fill('\u0000'); backupPassword = null
        val snapshot = pageSnapshots.remove(backStack.lastIndex)
        route = backStack.removeAt(backStack.lastIndex)
        remoteTracks = snapshot?.tracks.orEmpty(); searchResults = snapshot?.search.orEmpty(); catalogItems = snapshot?.items.orEmpty()
        nextCursor = snapshot?.cursor; remoteStatus = snapshot?.remoteStatus.orEmpty(); searchStatus = snapshot?.searchStatus.orEmpty(); busy = false
        searchAppend = snapshot?.searchAppend ?: false
        remoteEntryIds = snapshot?.entryIds.orEmpty()
        if (route.id == "search" && (snapshot == null || searchStatus == "正在搜索")) queryChanged(route.draft, append = searchAppend)
        else if (route.source.isNotBlank() && route.id in remotePageIds && (snapshot == null || remoteStatus == "正在读取")) loadRemote(route.source)
        if (route.id == "login") loadAccounts()
        clampFocus()
        return true
    }
    fun home() {
        if (editing || hidingIme) { back(); return }
        while (back()) { if (route.id == "home") break }
        route = Route("home"); backStack.clear(); pageSnapshots.clear(); saveNavigation()
    }
    private fun clampFocus() {
        if (restoringPage || busy || route.id == "search" && searchStatus == "正在搜索") return
        if (!library.ready && route.id in setOf("songs", "artists", "artist", "albums", "album", "genres", "playlists", "playlist", "history", "local-folders", "storage")) return
        val rows = page().rows
        val stableIndex = rows.indexOfFirst { it.id == route.focusKey }
        val index = if (stableIndex >= 0) stableIndex else route.focus.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
        route = route.copy(focus = index, focusKey = rows.getOrNull(index)?.id ?: route.focusKey)
        saveNavigation()
    }
    fun focus(index: Int) { val rows = page().rows; val bounded = index.coerceIn(0, rows.lastIndex.coerceAtLeast(0)); route = route.copy(focus = bounded, focusKey = rows.getOrNull(bounded)?.id); saveNavigation() }
    fun activate(index: Int) { focus(index); page().rows.getOrNull(index)?.takeIf { it.enabled }?.action?.invoke() }
    fun rotate(steps: Int, fromPointer: Boolean = false) {
        if (steps == 0 || hidingIme) return
        dialog?.let { dialog = if (it.document) it.copy(scroll = (it.scroll + steps).coerceIn(0, it.message.lines().lastIndex.coerceAtLeast(0))) else it.copy(focus = (it.focus + steps).coerceIn(0, it.rows.lastIndex.coerceAtLeast(0))); return }
        valueDraft?.let { setDraftValue(it.value + steps * it.step); return }
        if (route.id == "coverflow" && coverItems.isNotEmpty()) {
            if (route.source.isNotBlank() && nextCursor != null && !busy && Math.floorMod(coverPosition, coverItems.size) + steps >= coverItems.lastIndex) loadRemote(route.source, true)
            val delta = steps % coverItems.size
            if (delta != 0) { coverPosition += delta; savedState["coverAlbum"] = selectedAlbum()?.id }
            return
        }
        if (route.id == "lyrics" || route.id == "now" && nowMode == "lyrics") { scrollLyrics(steps); return }
        if (route.id == "now" && playback.current != null) {
            if (nowMode == "seek") {
                seekDraft = (currentPosition + steps * 5000L).coerceIn(0, playback.durationMs.coerceAtLeast(0))
                seekJob?.cancel(); if (!fromPointer) seekJob = viewModelScope.launch { delay(300); endRotation(false) }
            } else {
                if (nowMode == "normal") { nowMode = "volume"; transientVolume = true }
                app.player.adjustVolumeSteps(steps)
                if (transientVolume) { volumeJob?.cancel(); volumeJob = viewModelScope.launch { delay(3000); if (nowMode == "volume" && transientVolume) nowMode = "normal"; transientVolume = false } }
            }
            return
        }
        focus(route.focus + steps)
    }
    fun key(key: WheelKey) {
        if (hidingIme) return
        when (key) {
            WheelKey.MENU -> back()
            WheelKey.PLAY -> app.player.toggle()
            WheelKey.PREVIOUS -> app.player.previous()
            WheelKey.NEXT -> app.player.next()
            WheelKey.CENTER -> {
                dialog?.let { it.rows.getOrNull(it.focus)?.action?.invoke(); return }
                valueDraft?.let { valueDraft = null; it.commit(it.value); return }
                if (route.id == "now" && playback.current != null) {
                    if (nowMode == "seek") { seekDraft?.let(app.player::seek); seekDraft = null }
                    volumeJob?.cancel(); transientVolume = false
                    nowMode = when (nowMode) { "normal" -> if (playback.canSeek) "seek" else "volume"; "seek" -> "volume"; "volume" -> if (lyricLines.isNotEmpty()) "lyrics" else "normal"; else -> "normal" }
                    if (nowMode == "lyrics") loadLyrics()
                } else if (route.id == "lyrics" && lyricLines.isNotEmpty()) seekLyric(lyricFocus) else activate(route.focus)
            }
        }
    }
    fun hold(key: WheelKey) {
        if (hidingIme) return
        when (key) {
            WheelKey.MENU -> home()
            WheelKey.CENTER -> if (route.id == "now") playback.current?.let { trackMenu(it) } else page().rows.getOrNull(route.focus)?.hold?.invoke()
            WheelKey.PREVIOUS, WheelKey.NEXT -> if (playback.current != null && playback.canSeek) {
                heldPosition = playback.positionMs; seekDraft = playback.positionMs
                heldJob = viewModelScope.launch { while (isActive) { seekDraft = (currentPosition + if (key == WheelKey.NEXT) 2000 else -2000).coerceIn(0, playback.durationMs); delay(250) } }
            }
            WheelKey.PLAY -> app.player.pause()
        }
    }
    fun endHold(cancel: Boolean) { heldJob?.cancel(); heldJob = null; if (heldPosition != null) { if (!cancel) seekDraft?.let(app.player::seek); heldPosition = null; seekDraft = null } }
    fun endRotation(cancel: Boolean) { seekJob?.cancel(); seekJob = null; if (nowMode == "seek" && seekDraft != null) { if (!cancel) app.player.seek(seekDraft!!); seekDraft = null } }
    fun previewSeek(position: Long) { if (playback.canSeek) seekDraft = position.coerceIn(0, playback.durationMs.coerceAtLeast(0)) }
    fun finishSeek(cancel: Boolean) { if (!cancel && playback.canSeek) seekDraft?.let(app.player::seek); seekDraft = null }
    fun selectedCover(): CoverItem? = coverItems.let { if (it.isEmpty()) null else it[Math.floorMod(coverPosition, it.size)] }
    fun selectedAlbum(): Album? = if (route.source.isBlank()) library.albums.let { if (it.isEmpty()) null else it[Math.floorMod(coverPosition, it.size)] }
        else selectedCover()?.let { Album(it.id, it.title, it.artist, it.artworkUri, emptyList()) }
    fun selectCover(delta: Int) {
        if (delta != 0) { rotate(delta); return }
        selectedCover()?.let { entry ->
            if (entry.track != null) rowsForTracks(remoteTracks).find { it.id == entry.track.id }?.action?.invoke()
            else navigate(Route(catalogDestination(entry.kind, entry.id), entry.id, entry.title, route.source, accountScope = route.accountScope))
        }
    }
    fun beginInput() {
        if (hidingIme) return
        if (backupMode != null && lockQuery?.invoke() != false) { sensitive { beginInput() }; return }
        if (settings.touchGuard && !inputException) confirm("输入期间允许触屏？", "", "允许输入") { inputException = true; editing = true; gestureEpoch++ }
        else { editing = true; gestureEpoch++ }
    }
    fun finishEditing() {
        if (editing && imeVisible) hidingIme = true
        editing = false; inputException = false; gestureEpoch++
    }
    fun imeVisibilityChanged(visible: Boolean) {
        val wasVisible = imeVisible
        imeVisible = visible
        if (!visible) {
            hidingIme = false
            if (wasVisible && editing) finishEditing()
        }
    }
    fun inputChanged(value: String) { route = route.copy(draft = value); saveNavigation(); if (route.id == "search") queryChanged(value) }
    fun submitInput() { if (route.id == "create-playlist") savePlaylist() else if (backupMode != null) submitBackup() else { if (route.id == "search") queryChanged(route.draft, immediate = true); finishEditing() } }
    private fun queryChanged(query: String, append: Boolean = false, immediate: Boolean = false) {
        searchJob?.cancel(); val token = ++generation; val owner = route
        searchAppend = append
        val cursor = if (append) nextCursor else null
        if (!append) { nextCursor = null; catalogItems = emptyList() }
        if (query.isBlank()) { searchResults = emptyList(); searchStatus = ""; return }
        searchStatus = "正在搜索"
        searchJob = viewModelScope.launch {
            if (!immediate) delay(300)
            try {
                val result = if (owner.source.isBlank()) CatalogPage(localTracks.filter { "${it.title} ${it.artist} ${it.albumTitle.orEmpty()}".contains(query.trim(), true) })
                    else { check(!settings.safeMode) { "安全模式" }; pageSource.search(owner.source, query.trim(), cursor, owner.accountScope) }
                if (token == generation) {
                    app.library.rememberTracks(result.tracks.filter { it.sourceId != LOCAL_SOURCE })
                    if (token != generation) return@launch
                    searchResults = (if (append) searchResults + result.tracks else result.tracks).distinctBy { it.id }
                    catalogItems = (if (append) catalogItems + result.items else result.items).distinctBy { it.kind to it.remoteId }
                    nextCursor = result.nextCursor?.takeUnless { it == cursor }
                    searchStatus = if (searchResults.isEmpty() && catalogItems.isEmpty()) "没有搜索结果" else "${searchResults.size + catalogItems.size} 项"
                    clampFocus()
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (token == generation && !accountReadFailed(e, owner)) searchStatus = "搜索失败，可重新输入重试" }
        }
    }
    private fun chooseSearchSource() {
        dialog = LcdDialog("搜索来源", "", listOf(item("本地音乐") { changeSearchSource("") }) +
            pluginList.filter { it.enabled && !settings.safeMode && "catalog.search" in it.capabilities }.map { p -> item(p.name) { changeSearchSource(p.id) } } + listOf(item("取消") { cancelDialog() }))
    }
    private fun changeSearchSource(id: String) { dialog = null; route = route.copy(source = id, accountScope = selectedAccounts[id] ?: "public", focus = 0, focusKey = null, musicRoot = false); searchResults = emptyList(); queryChanged(route.draft) }
    private fun catalogDestination(kind: String, remoteId: String? = null) = when {
        remoteId != null && HomeCatalog.isHome(remoteId) -> "songs"
        kind.equals("album", true) -> "album"
        kind.equals("playlist", true) -> "playlist"
        kind.equals("artist", true) -> "artist"
        else -> "songs"
    }
    private fun catalogRows() = catalogItems.map { entry -> LcdRow("${entry.kind}:${entry.remoteId}", entry.title, entry.subtitle, entry.artworkUri, action = {
        val destination = catalogDestination(entry.kind, entry.remoteId)
        navigate(Route(destination, entry.remoteId, entry.title, route.source, accountScope = route.accountScope))
    }, sourceId = pluginList.find { it.id == route.source }?.sourceId.orEmpty(), accountScope = route.accountScope) }
    private fun pagingRows(search: Boolean): List<LcdRow> {
        if (searchStatus.startsWith("搜索失败") && search) return listOf(item("重试") { queryChanged(route.draft, append = searchAppend, immediate = true) })
        if (remoteStatus.startsWith("读取失败") && !search) return listOf(item("重试") { loadRemote(route.source, nextCursor != null) })
        if (nextCursor == null || busy) return emptyList()
        return listOf(item("加载更多") { if (search) queryChanged(route.draft, append = true, immediate = true) else loadRemote(route.source, true) })
    }
    private fun remoteRows(): List<LcdRow> {
        val plugin = pluginList.find { it.id == route.source }
        val options = if (route.id == "playlist" && plugin != null && cloudAllowed(plugin, route.accountScope, "library.playlist.write"))
            listOf(item("云端歌单选项") { cloudPlaylistMenu(plugin.id, route.accountScope, route.key) }) else emptyList()
        val tracks = if (route.id == "playlist") rowsForTracks(remoteTracks, remoteEntryIds).mapIndexed { index, row ->
            row.copy(id = remoteEntryIds.getOrNull(index) ?: "remote-position:$index:${row.id}")
        } else rowsForTracks(remoteTracks)
        return options + catalogRows() + tracks + pagingRows(false)
    }
    private fun savePlaylist() {
        val title = route.draft.trim()
        if (title.codePointCount(0, title.length) !in 1..60) { toast("名称须为 1 至 60 个字符"); return }
        if (route.source.isNotBlank()) { saveCloudPlaylist(title); return }
        if (busy) return
        val token = generation; val rename = route.key.takeIf { it.isNotBlank() }; val pendingTrack = route.pendingTrackId
        busy = true; work { try { val id = rename?.also { app.library.renamePlaylist(it, title) } ?: app.library.createPlaylist(title, listOfNotNull(pendingTrack))
            if (token == generation && route.id == "create-playlist") {
                finishEditing(); popRoute()
                if (rename == null) { navigate(Route("playlist", id, title)); if (pendingTrack != null) toast("已创建歌单并添加歌曲") }
                else if (route.id == "playlist" && route.key == id) { route = route.copy(title = title); saveNavigation() }
            }
        } finally { if (token == generation || route.id == "playlist") busy = false } }
    }
    fun confirm(title: String, text: String, yes: String = "确认", action: () -> Unit) {
        val requiresUnlock = sensitiveContext
        val token = generation
        dialog = LcdDialog(title, text, listOf(item("取消") { cancelDialog() }, item(yes) {
            dialog = null
            if (token != generation) toast("操作已失效，请重试")
            else if (requiresUnlock && lockQuery?.invoke() != false) toast("请先解锁设备")
            else action()
        }))
    }
    fun message(title: String, text: String) { dialog = LcdDialog(title, text, listOf(item("返回") { cancelDialog() }), document = text.length > 500) }
    fun dialogActivate(index: Int) { dialog?.rows?.getOrNull(index)?.action?.invoke() }
    fun cancelDialog() { dialog = null; previewThemeId = null; pendingUnlock = null }
    fun sensitive(action: () -> Unit) {
        refreshLock(lockQuery?.invoke() ?: locked)
        if (!locked) { sensitiveContext = true; try { action() } finally { sensitiveContext = false }; return }
        pendingUnlock = action; pendingRoute = route
        dialog = LcdDialog("解锁后继续", "此操作需要系统解锁。", listOf(item("取消") { cancelDialog() }, item("解锁") { dialog = null; effectHandler?.invoke(SystemEffect.Unlock) }))
    }
    fun unlockOverlay() {
        refreshLock(lockQuery?.invoke() ?: locked)
        if (!settings.lockScreenOverlay || !locked) return
        cancelDialog(); pendingRoute = null; valueDraft = null
        endHold(true); endRotation(true); finishEditing()
        effectHandler?.invoke(SystemEffect.Unlock)
    }
    fun unlockResult(success: Boolean) {
        refreshLock(lockQuery?.invoke() ?: locked)
        val action = pendingUnlock
        if (!success || locked || pendingRoute?.let { it.id != route.id || it.key != route.key || it.source != route.source } != false || action == null) { pendingUnlock = null; return }
        confirm("继续操作？", "", "继续") { if (lockQuery?.invoke() == false) { pendingUnlock = null; sensitive(action) } else { pendingUnlock = null; toast("设备已锁定，请重试") } }
    }
    fun refreshLock(isLocked: Boolean) {
        if (locked == isLocked) return
        locked = isLocked; gestureEpoch++; endHold(true); endRotation(true); finishEditing()
        if (isLocked) {
            val searchInterrupted = searchJob?.isActive == true || searchStatus == "正在搜索"
            val pageInterrupted = pageJob?.isActive == true || remoteStatus == "正在读取"
            generation++; pageJob?.cancel(); searchJob?.cancel()
            if (searchInterrupted) searchStatus = "搜索失败，锁定状态已变化，请重试"
            if (pageInterrupted) { remoteStatus = "读取失败，锁定状态已变化，请重试"; busy = false }
            if (searchInterrupted || pageInterrupted) pageNeedsReload = false
            cancelAuth(); pendingUnlock = null; cancelDialog(); valueDraft = null
            backupPassword?.fill('\u0000'); backupPassword = null
            if (backupMode != null) route = route.copy(draft = "")
        }
    }
    fun suspended(preserveFileSession: Boolean = false, preserveUnlock: Boolean = false) {
        gestureEpoch++; endHold(true); endRotation(true); finishEditing(); cancelAuth(); previewThemeId = null; dialog = null
        if (!preserveUnlock) pendingUnlock = null
        if (!preserveFileSession && !preserveUnlock) {
            pageNeedsReload = pageJob?.isActive == true || searchJob?.isActive == true
            pageJob?.cancel(); searchJob?.cancel(); busy = false
            backupPassword?.fill('\u0000'); backupPassword = null; generation++; if (backupMode != null) route = route.copy(draft = "")
        }
    }
    private fun reloadPage() {
        pageNeedsReload = false
        when {
            route.id == "search" -> queryChanged(route.draft, append = searchAppend, immediate = true)
            route.id == "login" -> loadAccounts()
            route.id == "lyrics" -> loadLyrics()
            route.source.isNotBlank() && route.id in remotePageIds -> loadRemote(route.source)
        }
    }
    fun resumed() { work { app.library.checkPermission(); app.plugins.refresh(); if (pageNeedsReload && !restoringPage) reloadPage() }; refreshLock(lockQuery?.invoke() ?: false) }
    fun windowLostFocus() { gestureEpoch++; endHold(true); endRotation(true) }
    private fun editValue(title: String, value: Int, min: Int, max: Int, step: Int, unit: String, commit: (Int) -> Unit) { valueDraft = ValueDraft(title, value, min, max, step, unit, commit, live = title == "音量") }
    fun setDraftValue(value: Int) { valueDraft = valueDraft?.let { draft ->
        val normalized = (draft.minimum + kotlin.math.round((value - draft.minimum).toFloat() / draft.step).toInt() * draft.step).coerceIn(draft.minimum, draft.maximum)
        if (draft.live) draft.commit(normalized)
        draft.copy(value = normalized)
    } }
    private fun themeName() = themeList.find { it.id == settings.themeId }?.name ?: "银色"
    private fun repeatName() = when (playback.repeat) { RepeatMode.OFF -> "关闭"; RepeatMode.ALL -> "全部"; RepeatMode.ONE -> "单曲" }
    private fun qualityName(quality: String) = when (quality) { "standard" -> "标准"; "high" -> "高音质"; "lossless" -> "无损"; "hires" -> "高解析度"; else -> quality }
    private fun qualityOptions(): List<String> {
        if (settings.safeMode) return emptyList()
        val source = playback.current?.sourceId?.takeUnless { it == LOCAL_SOURCE }
        return pluginList.filter { it.enabled && (source == null || it.sourceId == source) }
            .flatMap { it.playbackQualities() }.distinct().sortedBy { listOf("standard", "high", "lossless", "hires").indexOf(it) }
    }
    private fun chooseQuality() {
        val options = qualityOptions()
        if (options.size < 2) return
        dialog = LcdDialog("网络音质", "", options.map { quality -> item(qualityName(quality), if (settings.networkQuality == quality) "已选" else "") {
            updateSettings { it.copy(networkQuality = quality) }; cancelDialog(); toast("音质偏好已保存，下次解析生效")
        } } + item("取消") { cancelDialog() })
    }
    private fun previewTheme(theme: ThemeInfo) { previewThemeId = theme.id; confirm("应用主题？", theme.name, "应用") { updateSettings { it.copy(themeId = theme.id) }; previewThemeId = null } }
    private fun themeMenu(theme: ThemeInfo) {
        val rows = mutableListOf(item("预览主题") { dialog = null; previewTheme(theme) })
        if (theme.hasPrevious) rows += item("恢复上一版本") {
            sensitive { work { app.themes.rollback(theme.id); cancelDialog() } }
        }
        if (theme.id !in setOf("silver", "black", "oled")) rows += item("卸载主题") {
            sensitive {
                confirm("卸载主题？", theme.name) {
                    work {
                        app.themes.uninstall(theme.id)
                        if (settings.themeId == theme.id) app.settings.update { it.copy(themeId = "silver") }
                    }
                }
            }
        }
        rows += item("取消") { cancelDialog() }
        dialog = LcdDialog(theme.name, theme.version, rows)
    }
    private fun playlistMenu(p: Playlist) { dialog = LcdDialog(p.title, "", listOf(item("重命名") { dialog = null; navigate(Route("create-playlist", key = p.id, draft = p.title)) }, item("删除歌单") { sensitive { confirm("删除歌单？", "原始音频不会被删除。") { val token = generation; work { app.library.deletePlaylist(p.id); if (token == generation && route.key == p.id) back() } } } }, item("取消") { cancelDialog() })) }
    private fun trackMenu(track: Track, entryId: String? = null) {
        val playlistId = route.key.takeIf { route.id == "playlist" && route.source.isBlank() }
        val rows = mutableListOf<LcdRow>()
        if (playlistId != null && track.sourceId == LOCAL_SOURCE && !track.available) rows += item("重新关联本地文件") {
            dialog = LcdDialog("选择本地歌曲", "", localTracks.map { replacement -> item(replacement.title, replacement.artist, replacement.id) {
                sensitive { confirm("关联本地歌曲？", "${track.title} → ${replacement.title}\n更新此曲目在本地歌单中的引用。", "关联") {
                    work { app.library.relinkPlaylistTrack(track.id, replacement.id); toast("本地歌曲已关联") }
                } }
            } } + listOf(nav("本地音乐文件夹", "local-folders"), item("取消") { cancelDialog() }))
        }
        if (playable(track)) rows += listOf(
            item("下一首播放") { app.player.enqueue(track, true); cancelDialog() },
            item("加入播放队列") { app.player.enqueue(track); cancelDialog() })
        rows += item("添加到歌单") {
            dialog = LcdDialog("添加到歌单", "", library.playlists.map { p -> item(p.title) {
                val owner = dialog
                work { app.library.rememberTracks(listOf(track)); app.library.addToPlaylist(p.id, track.id); if (dialog === owner) cancelDialog(); toast("已添加") }
            } } + listOf(item("新建歌单") {
                val owner = dialog; val token = generation
                work {
                    app.library.rememberTracks(listOf(track))
                    if (token == generation && dialog === owner) { cancelDialog(); navigate(Route("create-playlist", pendingTrackId = track.id)) }
                }
            }, item("取消") { cancelDialog() }))
        }
        rows += cloudTrackRows(track, entryId)
        rows += nav("播放队列", "queue")
        if (track.sourceId == LOCAL_SOURCE || pluginList.any { it.sourceId == track.sourceId && it.enabled && "lyrics.read" in it.capabilities }) rows += item("歌词") { navigate(Route("lyrics", track.id)) }
        rows += listOf(
            nav("睡眠定时", "sleep"),
            item("随机播放", toggle = playback.shuffle) { app.player.shuffle(!playback.shuffle); cancelDialog() },
            item("重复", repeatName()) { app.player.repeat(RepeatMode.entries[(playback.repeat.ordinal + 1) % 3]); cancelDialog() },
            item("曲目信息") { message(track.title, "艺人：${track.artist}\n专辑：${track.albumTitle.orEmpty()}\n来源：${if (track.sourceId == LOCAL_SOURCE) "本地音乐" else pluginList.find { it.sourceId == track.sourceId }?.name ?: "来源不可用"}\n时长：${track.durationMs?.let { "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}" } ?: "未知"}") })
        if (playlistId != null && entryId != null) rows += listOf(
            item("从歌单移除") { work { app.library.removeFromPlaylist(playlistId, entryId); cancelDialog() } },
            item("向前移动") { work { app.library.movePlaylistEntry(playlistId, entryId, -1); cancelDialog() } },
            item("向后移动") { work { app.library.movePlaylistEntry(playlistId, entryId, 1); cancelDialog() } })
        rows += item("取消") { cancelDialog() }
        val owner = LcdDialog(track.title, track.artist, rows)
        val token = generation
        dialog = owner
        if (track.sourceId != LOCAL_SOURCE && playable(track)) work {
            val allowed = runCatching { app.player.downloads.canDownload(track) }.getOrDefault(false)
            val current = dialog
            if (allowed && token == generation && current?.rows === owner.rows) {
                val download = item("保存离线") { work { app.player.downloads.enqueue(track); cancelDialog(); toast("已加入下载") } }
                dialog = current.copy(rows = current.rows.dropLast(1) + download + current.rows.last())
            }
        }
    }
    private fun cloudAllowed(plugin: PluginInfo, account: String, capability: String) =
        CloudMenuPolicy.allowed(plugin, account, capability, settings.safeMode,
            app.plugins.isAccountActive(plugin.sourceId, account))

    private fun cloudServiceRows(plugin: PluginInfo): List<LcdRow> {
        val account = selectedAccounts[plugin.id] ?: return emptyList()
        return if (cloudAllowed(plugin, account, "library.playlist.write"))
            listOf(item("云端歌单") { cloudPlaylists(plugin.id, account) }) else emptyList()
    }

    private fun cloudTrackRows(track: Track, entryId: String?): List<LcdRow> {
        val plugin = pluginList.find { it.sourceId == track.sourceId } ?: return emptyList()
        val rows = mutableListOf<LcdRow>()
        if (cloudAllowed(plugin, track.accountScope, "library.favorite"))
            rows += item("云端收藏") { cloudFavoriteMenu(plugin.id, track) }
        if (cloudAllowed(plugin, track.accountScope, "library.playlist.write")) {
            rows += item("添加到云端歌单") { cloudPlaylists(plugin.id, track.accountScope, track) }
            if (route.id == "playlist" && route.source == plugin.id && route.accountScope == track.accountScope && entryId != null)
                rows += item("云端歌单项目") { cloudPlaylistEntryMenu(plugin.id, track.accountScope, route.key, entryId, track) }
        }
        return rows
    }

    private fun cloudLoad(title: String, load: suspend () -> LcdDialog) = sensitive {
        val owner = LcdDialog(title, "正在读取", listOf(item("取消") { cancelDialog() }))
        val token = generation; dialog = owner
        work {
            try {
                val result = load()
                if (token == generation && dialog === owner && lockQuery?.invoke() == false) dialog = result
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (token == generation && dialog === owner) message(title, error.message?.take(140) ?: "读取失败，请重试")
            }
        }
    }

    internal fun cloudFavoriteMenu(plugin: String, track: Track) = cloudLoad("云端收藏") {
        val current = cloudLibrary.favorite(plugin, track.accountScope, track.remoteId)
        val label = if (current) "取消收藏" else "收藏歌曲"
        LcdDialog(track.title, if (current) "已收藏" else "未收藏", listOf(item(label) {
            confirmCloudMutation(plugin, track.accountScope, track.remoteId, track.title,
                LibraryMutation(java.util.UUID.randomUUID().toString(), "FAVORITE", desiredFavorite = !current), "$label？")
        }, item("取消") { cancelDialog() }))
    }

    private fun cloudPlaylists(plugin: String, account: String, track: Track? = null,
        cursor: String? = null, previous: List<CloudPlaylist> = emptyList()): Unit = cloudLoad(if (track == null) "云端歌单" else "添加到云端歌单") {
        val page = cloudLibrary.playlists(plugin, account, cursor)
        val playlists = (previous + page.items).distinctBy { it.remoteId }
        val rows = playlists.filter { track == null || "ADD" in it.allowedActions }.map { playlist ->
            item(playlist.title, playlist.owner, playlist.remoteId) {
                if (track == null) navigate(Route("playlist", playlist.remoteId, playlist.title, plugin, accountScope = account))
                else confirmCloudMutation(plugin, account, playlist.remoteId, playlist.title,
                    LibraryMutation(java.util.UUID.randomUUID().toString(), "ADD", playlist.revision, trackId = track.remoteId),
                    "添加到云端歌单？", track.title)
            }
        }.toMutableList()
        if (page.nextCursor != null) rows += item("加载更多") { cloudPlaylists(plugin, account, track, page.nextCursor, playlists) }
        if (pluginList.any { it.id == plugin && "library.playlist.create" in it.capabilities }) rows += item("新建云端歌单") {
            val token = generation; val owner = dialog
            work {
                if (track != null) app.library.rememberTracks(listOf(track))
                if (token == generation && dialog === owner) sensitive { navigate(Route("create-playlist", source = plugin, accountScope = account, pendingTrackId = track?.id)) }
            }
        }
        rows += item("取消") { cancelDialog() }
        LcdDialog(if (track == null) "云端歌单" else "添加到云端歌单", if (playlists.isEmpty()) "没有可编辑歌单" else "", rows)
    }

    private fun cloudPlaylistMenu(plugin: String, account: String, key: String) = cloudLoad("云端歌单选项") {
        val playlist = cloudLibrary.playlist(plugin, account, key)
        val rows = mutableListOf<LcdRow>()
        if ("RENAME" in playlist.allowedActions) rows += item("重命名") {
            sensitive { navigate(Route("create-playlist", key, source = plugin, accountScope = account, draft = playlist.title)) }
        }
        if ("DELETE" in playlist.allowedActions) rows += item("删除云端歌单") {
            confirmCloudMutation(plugin, account, key, playlist.title,
                LibraryMutation(java.util.UUID.randomUUID().toString(), "DELETE", playlist.revision), "删除云端歌单？")
        }
        rows += item("刷新") { cancelDialog(); loadRemote(plugin) }
        rows += item("取消") { cancelDialog() }
        LcdDialog(playlist.title, "${playlist.owner}\n${if (playlist.allowedActions.isEmpty()) "只读歌单" else "云端歌单"}".trim(), rows)
    }

    private fun cloudPlaylistEntryMenu(plugin: String, account: String, key: String, entryId: String, track: Track) = cloudLoad(track.title) {
        val playlist = cloudLibrary.playlist(plugin, account, key)
        val rows = mutableListOf<LcdRow>()
        fun edit(label: String, action: String, before: String? = null) = item(label) {
            confirmCloudMutation(plugin, account, key, playlist.title,
                LibraryMutation(java.util.UUID.randomUUID().toString(), action, playlist.revision, entryId = entryId, beforeEntryId = before), "$label？", track.title)
        }
        if ("REMOVE" in playlist.allowedActions) rows += edit("从云端歌单移除", "REMOVE")
        val index = remoteEntryIds.indexOf(entryId)
        if ("MOVE" in playlist.allowedActions && index >= 0) {
            if (index > 0 && remoteEntryIds[index - 1] != null) rows += edit("向前移动", "MOVE", remoteEntryIds[index - 1])
            if (index + 1 < remoteEntryIds.size && (index + 2 < remoteEntryIds.size && remoteEntryIds[index + 2] != null || index + 2 == remoteEntryIds.size && nextCursor == null))
                rows += edit("向后移动", "MOVE", remoteEntryIds.getOrNull(index + 2))
        }
        rows += item("取消") { cancelDialog() }
        LcdDialog(track.title, playlist.title, rows)
    }

    private fun saveCloudPlaylist(title: String) {
        val owner = route
        val initial = library.tracks.find { it.id == owner.pendingTrackId }
        if (initial != null && (initial.accountScope != owner.accountScope || pluginList.none { it.id == owner.source && it.sourceId == initial.sourceId })) {
            toast("歌曲与目标账号不匹配"); return
        }
        finishEditing()
        cloudLoad(if (owner.key.isBlank()) "创建云端歌单" else "重命名云端歌单") {
            val playlist = owner.key.takeIf(String::isNotBlank)?.let { cloudLibrary.playlist(owner.source, owner.accountScope, it) }
            val mutation = LibraryMutation(java.util.UUID.randomUUID().toString(), if (playlist == null) "CREATE" else "RENAME",
                expectedRevision = playlist?.revision, title = title, trackId = initial?.remoteId)
            LcdDialog(if (playlist == null) "创建云端歌单？" else "重命名云端歌单？", title,
                listOf(item("取消") { cancelDialog() }, item("确认") {
                    confirmCloudMutation(owner.source, owner.accountScope, playlist?.remoteId, title, mutation, "保存到云端？")
                }))
        }
    }

    internal fun confirmCloudMutation(plugin: String, account: String, remoteId: String?, title: String,
        mutation: LibraryMutation, prompt: String, detail: String = "") = sensitive {
        confirm(prompt, listOf(title, detail).filter(String::isNotBlank).joinToString("\n")) {
            val token = generation
            val owner = LcdDialog(title, "正在提交", listOf(item("返回") { cancelDialog() }))
            dialog = owner
            work {
                try {
                    val result = cloudLibrary.submit(plugin, account, remoteId, title, mutation) {
                        token == generation && !settings.safeMode && lockQuery?.invoke() == false
                    }
                    if (token == generation && dialog === owner) cloudMutationResult(result, mutation, plugin, account)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (token == generation && dialog === owner) message(title, error.message?.take(140) ?: "云端操作失败") }
            }
        }
    }

    private fun cloudMutationResult(result: MutationResult, mutation: LibraryMutation, plugin: String, account: String) {
        val action = mutation.action
        when (result.status) {
            "APPLIED" -> {
                cancelDialog(); toast("云端已更新")
                if (route.id == "create-playlist") {
                    finishEditing(); popRoute()
                    val remoteId = result.remoteId
                    if (action == "CREATE" && remoteId != null) navigate(Route("playlist", remoteId, mutation.title.orEmpty(), source = plugin, accountScope = account))
                    else if (route.id == "playlist") { route = route.copy(title = mutation.title ?: route.title); saveNavigation(); loadRemote(plugin) }
                } else if (route.id == "playlist" && action == "DELETE") back()
                else if (route.id == "playlist" && route.source == plugin) loadRemote(plugin)
            }
            "CONFLICT" -> {
                dialog = LcdDialog("歌单已变化", "此次修改未应用。", listOf(item("刷新") {
                    cancelDialog(); if (route.source == plugin && route.id == "playlist") loadRemote(plugin) else cloudPlaylists(plugin, account)
                }, item("返回") { cancelDialog() }))
            }
            "REJECTED" -> message("未完成云端操作", result.message ?: "服务拒绝了此次修改。")
            else -> pendingCloudMenu()
        }
    }

    private fun pendingCloudMenu(): Unit = sensitive {
        val receipts = cloudLibrary.pending.value
        dialog = LcdDialog("待确认操作", if (receipts.isEmpty()) "没有待确认操作" else "结果尚未确认。", receipts.map { receipt ->
            item(receipt.title, "待确认", receipt.id) {
                dialog = LcdDialog(receipt.title, "尚未获得确定结果。", listOf(item("刷新结果") {
                    cloudLoad(receipt.title) {
                        val result = cloudLibrary.refresh(receipt)
                        LcdDialog(receipt.title, when (result.status) {
                            "APPLIED" -> "已核对，云端已更新"
                            "REJECTED" -> "已核对，修改未应用"
                            "CONFLICT" -> "歌单版本冲突，修改未应用"
                            else -> "结果仍待确认，请稍后刷新或在服务端核对。"
                        }, listOf(item("返回") { pendingCloudMenu() }))
                    }
                }, item("返回") { pendingCloudMenu() }))
            }
        } + item("返回") { cancelDialog() })
    }

    private fun thirdPartyLicenses() {
        val files = listOf("第三方组件" to "THIRD-PARTY.txt", "依赖与声明" to "DEPENDENCIES.txt",
            "Apache 2.0" to "licenses/Apache-2.0.txt", "Protobuf BSD" to "licenses/Protobuf.txt", "Bouncy Castle" to "licenses/BouncyCastle.html")
        dialog = LcdDialog("第三方许可", "", files.map { (title, path) -> item(title) { showLicense(title, path) } } + item("返回") { cancelDialog() })
    }
    private fun referenceCredits() {
        val references = listOf(
            Triple("Classipod", "Aditya R\n界面结构与轮盘交互设计参考。", "https://github.com/adeeteya/Classipod"),
            Triple("CNMPlayer", "网易云服务适配流程的实现参考。", "https://github.com/professor-lee/CNMPlayer"),
        )
        dialog = LcdDialog("参考致谢", "EasePod 使用系统字体与独立实现的界面。", references.map { (name, credit, url) -> item(name) {
            dialog = LcdDialog(name, "$credit\n$url", listOf(item("打开项目网站") { sensitive { effectHandler?.invoke(SystemEffect.OpenExternal(url)) } }, item("返回") { referenceCredits() }))
        } } + item("返回") { cancelDialog() })
    }
    private fun diagnosticPreview() {
        val text = buildString {
            appendLine("EasePod 诊断")
            appendLine("应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android API：${android.os.Build.VERSION.SDK_INT}")
            appendLine("安全模式：${settings.safeMode}")
            appendLine("锁屏覆盖：${settings.lockScreenOverlay}")
            appendLine("全屏显示：${settings.fullScreen}")
            appendLine("防误触：${settings.touchGuard}")
            appendLine("目录授权：${library.root?.permissionValid == true}")
            appendLine("音乐库就绪：${library.ready}")
            appendLine("本地歌曲：${localTracks.size}")
            appendLine("专辑：${library.albums.size}")
            appendLine("歌单：${library.playlists.size}")
            appendLine("扫描阶段：${scan.phase.name}")
            appendLine("扫描跳过：${scan.skipped}")
            appendLine("队列长度：${playback.queue.size}")
            appendLine("播放中：${playback.playing}")
            appendLine("缓冲中：${playback.buffering}")
            appendLine("可定位：${playback.canSeek}")
            appendLine("播放错误：${playback.error != null}")
            appendLine("网络音质偏好：${qualityName(settings.networkQuality)}")
            appendLine("已安装服务：${pluginList.size}")
            appendLine("已启用服务：${pluginList.count { it.enabled }}")
            appendLine("服务故障：${pluginList.count { it.failure != null }}")
            appendLine("缓存 MB：${app.player.cacheBytes / 1024 / 1024}")
            DownloadPhase.entries.forEach { phase -> appendLine("下载 ${phase.name}：${downloads.count { it.phase == phase }}") }
        }
        dialog = LcdDialog("诊断预览", text, listOf(item("导出诊断") {
            sensitive { confirm("导出诊断？", "将保存预览中的诊断内容。", "导出") { effectHandler?.invoke(SystemEffect.ExportDiagnostics(text.toByteArray(Charsets.UTF_8))) } }
        }), document = true)
    }
    private fun showLicense(title: String, path: String) {
        val token = generation
        work {
            val text = withContext(Dispatchers.IO) { app.assets.open(path).bufferedReader().use { it.readText() } }
            if (token == generation) message(title, if (path.endsWith(".html")) android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString() else text)
        }
    }
    private fun queueMenu(entry: QueueEntry) { dialog = LcdDialog(entry.track.title, "", listOf(item("播放") { app.player.jump(entry.id); dialog = null }, item("向前移动") { app.player.move(entry.id, -1); dialog = null }, item("向后移动") { app.player.move(entry.id, 1); dialog = null }, item("移除") { app.player.remove(entry.id); dialog = null }, item("取消") { cancelDialog() })) }
    private fun chooseFolder() = sensitive { effectHandler?.invoke(SystemEffect.PickFolder) }
    private fun applyScannedFolder() = sensitive {
        confirm("应用此文件夹？", "${library.root?.name ?: "尚未选择"} → ${scan.folder}\n${scan.tracks} 首 · ${scan.albums} 张专辑\n旧目录中的歌单引用将保留，原始文件不会更改。", "应用") {
            val token = generation
            work { app.library.publishScan { withContext(Dispatchers.Main.immediate) {
                if (token != generation || lockQuery?.invoke() != false) throw CancellationException("Folder review ended")
            } } }
        }
    }
    fun folderChosen(uri: Uri) { if (lockQuery?.invoke() == true) { toast("设备已锁定，请重新选择"); return }; work { app.library.scanFolder(uri.toString()) }; if (route.id != "local-folders") navigate(Route("local-folders")) }
    private fun rescan() { library.root?.let { root -> work { app.library.scanFolder(root.uri) }; if (route.id != "local-folders") navigate(Route("local-folders")) } ?: chooseFolder() }
    private fun scanReport() { message("扫描结果", "${scan.tracks} 首 · ${scan.albums} 张专辑 · 跳过 ${scan.skipped} 项\n${scan.issues.joinToString("\n")}") }
    private fun backupForm(import: Boolean) = sensitive { navigate(Route("storage", if (import) "backup:import" else "backup:export")); if (!settings.touchGuard) beginInput() }
    private fun submitBackup() {
        if (lockQuery?.invoke() != false) { toast("请先解锁设备"); return }
        if (route.draft.length < 8) { toast("口令至少 8 个字符"); return }
        val token = generation
        val password = route.draft.toCharArray(); route = route.copy(draft = ""); finishEditing()
        if (backupMode == "import") { backupPassword = password; effectHandler?.invoke(SystemEffect.ImportBackup) }
        else work { try { val bytes = app.library.exportBackup(password); if (token == generation && lockQuery?.invoke() == false && effectHandler != null) effectHandler?.invoke(SystemEffect.ExportBackup(bytes)) else bytes.fill(0) } finally { password.fill('\u0000') } }
    }
    fun backupImported(bytes: ByteArray) {
        val password = backupPassword ?: run { bytes.fill(0); return }; backupPassword = null
        if (lockQuery?.invoke() != false || backupMode != "import") { password.fill('\u0000'); bytes.fill(0); return }
        val token = generation
        work { try {
            app.library.importBackup(bytes, password) {
                withContext(Dispatchers.Main.immediate) { if (token != generation || lockQuery?.invoke() != false || backupMode != "import") throw CancellationException("Import session ended") }
            }
            if (token == generation) { toast("备份已导入"); back() }
        } finally { password.fill('\u0000'); bytes.fill(0) } }
    }
    fun fileResultCancelled() { backupPassword?.fill('\u0000'); backupPassword = null; toast("已取消") }
    fun pluginFileChosen(uri: Uri) {
        if (inspectingPluginFile || installSystemActive) return
        inspectingPluginFile = true
        val token = generation
        work { try {
            val candidate = withContext(Dispatchers.IO) {
                val target = File(app.cacheDir, "plugin-review-${System.nanoTime()}")
                var retainTarget = false
                try {
                    app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out -> val buffer = ByteArray(8192); var total = 0L; while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 100L * 1024 * 1024) { "安装文件过大" }; out.write(buffer, 0, count) } } } ?: error("无法读取文件")
                    val theme = runCatching { app.themes.inspect(target.path) }.getOrNull()
                    if (theme != null) InstallCandidate("theme", theme.name, theme.version, target.path).also { retainTarget = true }
                    else app.plugins.inspectApk(Uri.fromFile(target))
                } finally { if (!retainTarget) target.delete() }
            }
            if (token == generation && lockQuery?.invoke() == false) {
                discardInstall()
                val verified = try { pendingInstallStore.save(candidate) }
                    catch (failure: Exception) { withContext(Dispatchers.IO) { File(candidate.filePath).delete() }; throw failure }
                if (token == generation && lockQuery?.invoke() == false) {
                    installCandidate = verified; navigate(Route("install"))
                } else pendingInstallStore.clear(verified)
            } else withContext(Dispatchers.IO) { File(candidate.filePath).delete() }
        } finally { inspectingPluginFile = false } }
    }
    private suspend fun restorePendingInstall() {
        val token = generation
        val restored = pendingInstallStore.restore() ?: return
        if (installCandidate != null || inspectingPluginFile) return
        pluginList = app.plugins.plugins.value
        installCandidate = restored.candidate; installFileAvailable = restored.fileAvailable
        installCompleted = restored.installed
        installationRequestedPermission = restored.stage == InstallStage.AWAITING_PERMISSION
        installPermissionGranted = installationRequestedPermission && app.plugins.canInstallPackages()
        installStatus = when {
            restored.installed -> if (matchingInstalledPlugin(restored.candidate) != null) "安装已核验" else "APK 已安装，插件服务不可用"
            installPermissionGranted -> "安装权限已开启，请继续安装"
            installationRequestedPermission -> "等待安装权限"
            restored.stage == InstallStage.AWAITING_RESULT -> "安装未完成，可重试"
            else -> "已恢复安装检查"
        }
        if (token == generation && lockQuery?.invoke() == false) navigate(Route("install"))
    }
    private fun matchingInstalledPlugin(candidate: InstallCandidate) = pluginList.find {
        candidate.kind == "apk" && it.id == candidate.pluginId && it.packageName == candidate.packageName &&
            it.versionCode == candidate.versionCode && it.version == candidate.version && it.signature == candidate.signature
    }
    private fun discardInstall() {
        if (installSystemActive) return
        installCandidate?.let { candidate -> work { pendingInstallStore.clear(candidate) } }
        installCandidate = null; installStatus = ""; installChecking = false; installPermissionGranted = false; installationRequestedPermission = false; installFileAvailable = true; installCompleted = false
    }
    private fun installSelected() {
        val candidate = installCandidate ?: return
        if (installChecking || !installFileAvailable || installCompleted || installSystemActive) return
        sensitive {
            if (candidate.kind == "theme") {
                val token = generation; installChecking = true
                work { try {
                    app.themes.install(candidate.filePath)
                    pendingInstallStore.clear(candidate)
                    if (installCandidate == candidate) {
                        installCompleted = true; installFileAvailable = false; installStatus = "主题已安装"
                        if (token == generation) { discardInstall(); back(); toast("主题已安装") }
                    }
                } finally { if (installCandidate == candidate) installChecking = false } }
            } else {
                val token = generation
                installChecking = true
                work { try {
                    val permissionStep = !app.plugins.canInstallPackages()
                    check(pendingInstallStore.stage(candidate, if (permissionStep) InstallStage.AWAITING_PERMISSION else InstallStage.AWAITING_RESULT)) { "安装检查已失效，请重新选择文件" }
                    if (token == generation && installCandidate == candidate && lockQuery?.invoke() == false) {
                        installationRequestedPermission = permissionStep
                        installStatus = if (permissionStep) "等待安装权限" else "等待系统确认"
                        effectHandler?.invoke(SystemEffect.Install(candidate))
                    }
                } finally { if (installCandidate == candidate) installChecking = false } }
            }
        }
    }
    fun installationWindowActive(active: Boolean) { installSystemActive = active }
    fun installationReturned() {
        installSystemActive = false
        val candidate = installCandidate ?: return
        val token = generation; val permissionStep = installationRequestedPermission
        installationRequestedPermission = false; installChecking = true
        work { try {
            app.plugins.refresh()
            if (installCandidate == candidate && installFileAvailable) pendingInstallStore.stage(candidate, InstallStage.REVIEW)
            if (token == generation && installCandidate == candidate) {
                pluginList = app.plugins.plugins.value
                installCompleted = matchingInstalledPlugin(candidate) != null
                installPermissionGranted = permissionStep && app.plugins.canInstallPackages()
                installStatus = when {
                    matchingInstalledPlugin(candidate) != null -> "安装已核验"
                    installPermissionGranted -> "安装权限已开启"
                    permissionStep -> "安装权限未开启"
                    else -> "安装未完成，未发现与所选文件一致的插件"
                }
                clampFocus()
            }
        } finally { if (installCandidate == candidate) installChecking = false } }
    }
    private fun togglePlugin(p: PluginInfo) {
        if (p.enabled) { app.player.disableSource(p.sourceId); work { app.plugins.setEnabled(p.id, false) } }
        else if (p.builtin) sensitive { work { app.plugins.setEnabled(p.id, true) } }
        else sensitive { confirm("启用 ${p.name}？", "独立插件可使用其已获授的系统权限。\n签名：${p.signature}", "信任并启用") { work { app.plugins.approve(p.id, p.signature); app.plugins.setEnabled(p.id, true) } } }
    }
    private fun pluginRows(p: PluginInfo): List<LcdRow> {
        val rows = mutableListOf(item(if (p.enabled) "禁用插件" else "启用插件") { togglePlugin(p) },
            item("配置信息", if (p.builtin) "内置" else "独立 APK") { message(p.name, "包名：${p.packageName}\n版本：${p.version}\n签名：${p.signature}\n能力：${p.capabilities.joinToString()}\n网络：${p.networkDomains.joinToString()}") })
        if ("account.qr" in p.capabilities) rows += item("账号登录", when {
            !p.approved -> "请先批准插件"
            !p.enabled -> "请先启用插件"
            else -> "二维码登录"
        }) {
            when {
                !p.approved -> toast("请先批准插件后再登录")
                !p.enabled -> toast("请先启用插件后再登录")
                else -> navigate(Route("login", key = p.id, title = p.name))
            }
        }
        if (!p.builtin) rows += item("在插件中批准 EasePod") { sensitive { effectHandler?.invoke(SystemEffect.ApproveHost(p.id)) } }
        rows += item("重新连接") { work { app.plugins.retry(p.id) } }
        if (!p.builtin) rows += item("卸载插件") { sensitive { confirm("卸载 ${p.name}？", "系统将确认卸载，插件账号与数据可能被删除。") { effectHandler?.invoke(SystemEffect.Uninstall(p.id)) } } }
        return rows
    }
    private fun loadRemote(plugin: String, append: Boolean = false) {
        pageJob?.cancel(); val token = generation; val owner = route; val cursor = if (append) nextCursor else null
        busy = true; remoteStatus = "正在读取"
        pageJob = viewModelScope.launch {
            try {
                check(!settings.safeMode) { "安全模式" }
                val details = if (owner.id in setOf("album", "artist", "playlist") && owner.key.isNotBlank() && !HomeCatalog.isHome(owner.key))
                    pageSource.details(plugin, owner.key, cursor, owner.accountScope) else null
                var page = details ?: pageSource.browse(plugin, owner.key.takeIf { it.isNotBlank() }, cursor, owner.accountScope)
                val restoreCover = if (owner.id == "coverflow" && !append && catalogItems.isEmpty()) savedState.get<String>("coverAlbum") else null
                val visited = mutableSetOf<String>()
                var coverRestoreFailed = false
                // Reload enough bounded pages to recover a previously selected cloud cover.
                while (restoreCover != null && page.items.none { it.remoteId == restoreCover } && page.tracks.none { it.id == restoreCover } &&
                    page.nextCursor != null && visited.size < 20 && token == generation) {
                    val next = page.nextCursor ?: break
                    if (next in visited) break
                    val more = try {
                        pageSource.browse(plugin, owner.key.takeIf { it.isNotBlank() }, next, owner.accountScope)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        if (e is PluginException && e.code in setOf("AuthRequired", "AuthExpired")) throw e
                        coverRestoreFailed = true; break
                    }
                    visited.add(next)
                    page = more.copy(tracks = (page.tracks + more.tracks).distinctBy { it.id },
                        items = (page.items + more.items).distinctBy { it.kind to it.remoteId }, isStale = page.isStale || more.isStale)
                }
                if (token == generation) {
                    app.library.rememberTracks(page.tracks)
                    if (token != generation) return@launch
                    val selected = if (owner.id == "coverflow") selectedCover() else null
                    if (owner.id == "playlist") {
                        remoteTracks = if (append) remoteTracks + page.tracks else page.tracks
                        val entries = page.tracks.indices.map { page.trackEntryIds.getOrNull(it) }
                        remoteEntryIds = if (append) remoteEntryIds + entries else entries
                    } else { remoteTracks = (if (append) remoteTracks + page.tracks else page.tracks).distinctBy { it.id }; remoteEntryIds = emptyList() }
                    catalogItems = (if (append) catalogItems + page.items else page.items).distinctBy { it.kind to it.remoteId }
                    if (owner.id == "coverflow") {
                        val selectedId = selected?.id ?: savedState.get<String>("coverAlbum")
                        val index = coverItems.indexOfFirst { it.id == selectedId && (selected == null || it.kind == selected.kind) }
                        if (index >= 0) coverPosition += index - Math.floorMod(coverPosition, coverItems.size)
                    }
                    nextCursor = page.nextCursor?.takeUnless { it == cursor || it in visited }
                    remoteStatus = if (coverRestoreFailed) "读取失败，请重试" else if (page.isStale) "离线目录" else ""
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (token == generation && !accountReadFailed(e, owner)) remoteStatus = if (e is PluginException && e.code == "Unsupported") "此服务不支持此目录" else "读取失败，请重试" }
            finally { if (token == generation) { busy = false; clampFocus() } }
        }
    }
    private fun loadAccounts() {
        val plugin = route.key; val token = generation
        if (accountsPlugin != plugin) { accounts = emptyList(); accountsPlugin = plugin }
        if (pluginList.none { it.id == plugin && "account.list" in it.capabilities }) { accounts = emptyList(); authStatus = "此服务无需账号"; return }
        work { val result = accountSource.accounts(plugin); if (token == generation && route.id == "login") {
            accounts = result
            selectedAccounts[plugin]?.let { scope -> if (result.none { it.scope == scope && it.state == "SignedIn" }) clearSelectedAccount(plugin, scope) }
            clampFocus()
        } }
    }
    private fun selectAccount(plugin: String, account: PluginAccount) {
        if (account.state != "SignedIn" || settings.safeMode || pluginList.none { it.id == plugin && it.enabled && it.approved }) return
        changedAccountSelections += plugin
        selectedAccounts[plugin] = account.scope
        selectedAccountsStore.select(plugin, account.scope)
        selectMusicSource(MusicSourceSelection(plugin, account.scope))
    }
    private fun clearSelectedAccount(plugin: String, scope: String? = null) {
        if (scope == null || selectedAccounts[plugin] == scope) {
            changedAccountSelections += plugin
            selectedAccounts.remove(plugin)
        }
        selectedAccountsStore.remove(plugin, scope)
        if (musicSource?.let { it.pluginId == plugin && (scope == null || it.accountScope == scope) } == true) selectMusicSource(null)
    }
    private fun accountMenu(account: PluginAccount) {
        val plugin = route.key
        dialog = LcdDialog(account.displayName, account.state, listOfNotNull(
            if (account.state == "SignedIn") item("使用此账号") {
                if (accountsPlugin == plugin && accounts.any { it.scope == account.scope && it.state == "SignedIn" }) selectAccount(plugin, account)
                dialog = null
            } else null,
            item("退出账号") {
            sensitive { confirm("退出账号？", account.displayName) {
                val source = pluginList.find { it.id == plugin }?.sourceId.orEmpty()
                app.player.disableAccount(source, account.scope); clearSelectedAccount(plugin, account.scope)
                val token = generation
                work { try { accountSource.signOut(plugin, account.scope) } finally { if (token == generation) loadAccounts() } }
            } }
        }, item("取消") { cancelDialog() }))
    }
    private fun startAuth() = sensitive {
        val plugin = route.key; val token = generation
        val source = accountSource
        cancelAuth(); authStatus = "正在准备授权"
        authJob = viewModelScope.launch {
            var active: PluginAuthSession? = null
            try {
                check(!settings.safeMode)
                var session = source.beginAuth(plugin)
                active = session
                while (isActive && token == generation && route.id == "login" && lockQuery?.invoke() == false) {
                    authSession = session
                    authStatus = when (session.state) { "WaitingScan" -> "等待扫码"; "WaitingConfirm" -> "等待确认"; "VerifyingAccount" -> "正在验证账号"; "SignedIn" -> "授权成功"; "Expired" -> "二维码已过期"; "Cancelled" -> "已取消"; "Failed" -> "授权失败"; else -> "正在准备授权" }
                    if (session.state in setOf("SignedIn", "Expired", "Cancelled", "Failed")) {
                        authSession = null
                        if (session.state == "SignedIn") { active = null; session.account?.let { account -> selectAccount(plugin, account); pluginList.find { it.id == plugin }?.let { app.player.enableAccount(it.sourceId, account.scope) } }; loadAccounts() }
                        return@launch
                    }
                    val remaining = (session.expiresAtElapsedMs ?: (SystemClock.elapsedRealtime() + 180_000)) - SystemClock.elapsedRealtime()
                    if (remaining <= 0) { authSession = null; authStatus = "二维码已过期"; return@launch }
                    delay(minOf(2000L, remaining))
                    if (session.expiresAtElapsedMs?.let { it <= SystemClock.elapsedRealtime() } == true) { authSession = null; authStatus = "二维码已过期"; return@launch }
                    val next = source.pollAuth(plugin, session.id)
                    check(next.id == session.id) { "授权会话不匹配" }; session = next
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (token == generation) { authStatus = "授权失败，请重新发起"; authSession = null } }
            finally { active?.let { session -> withContext(NonCancellable) { withTimeoutOrNull(3000) { runCatching { source.cancelAuth(plugin, session.id) } } } } }
        }
    }
    private fun cancelAuth() { authJob?.cancel(); authJob = null; authSession = null; authStatus = "" }
    private fun loadLyrics() {
        lyricJob?.cancel(); lyricFollowJob?.cancel(); followingLyrics = true; lyricFocus = 0
        val track = if (route.id == "lyrics" && route.key.isNotBlank()) library.tracks.find { it.id == route.key } else playback.current
        lyricTrackId = track?.id; lyricLines = emptyList(); lyricText = "正在读取歌词"
        lyricJob = viewModelScope.launch {
            try {
                val lines = pageSource.lyrics(track)
                if (lyricTrackId == track?.id) { lyricLines = lines; lyricText = if (lines.isEmpty()) "暂无歌词" else "" }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (lyricTrackId == track?.id) lyricText = "歌词读取失败" }
        }
    }
    fun retryLyrics() = loadLyrics()
    fun scrollLyrics(steps: Int) { followingLyrics = false; lyricFocus = (lyricFocus + steps).coerceIn(0, lyricLines.lastIndex.coerceAtLeast(0)); lyricFollowJob?.cancel(); lyricFollowJob = viewModelScope.launch { delay(5000); followLyrics() } }
    fun followLyrics() { followingLyrics = true; lyricFocus = lyricLines.indexOfLast { it.timeMs?.let { time -> time <= playback.positionMs } == true }.coerceAtLeast(0) }
    fun seekLyric(index: Int) { if (lyricTrackId == playback.current?.id && playback.canSeek) lyricLines.getOrNull(index)?.timeMs?.let(app.player::seek); followLyrics() }
    private fun downloadName(phase: DownloadPhase) = when (phase) { DownloadPhase.QUEUED -> "等待"; DownloadPhase.WAITING_NETWORK -> "等待网络"; DownloadPhase.DOWNLOADING -> "下载中"; DownloadPhase.PAUSED -> "已暂停"; DownloadPhase.COMPLETE -> "已完成"; DownloadPhase.FAILED -> "失败"; DownloadPhase.CANCELLED -> "已取消"; DownloadPhase.EXPIRED -> "授权过期" }
    private fun downloadMenu(id: String) {
        val d = downloads.find { it.id == id } ?: return
        val rows = mutableListOf<LcdRow>()
        if (d.phase == DownloadPhase.COMPLETE && playable(d.track)) rows += item("播放") { app.player.play(listOf(d.track)); navigate(Route("now")) }
        if (d.phase in setOf(DownloadPhase.QUEUED, DownloadPhase.WAITING_NETWORK, DownloadPhase.DOWNLOADING)) rows += item("暂停下载") { app.player.downloads.pause(id); cancelDialog() }
        if (d.phase in setOf(DownloadPhase.PAUSED, DownloadPhase.FAILED, DownloadPhase.CANCELLED, DownloadPhase.EXPIRED) && playable(d.track)) rows += item("继续下载") { work { app.player.downloads.resume(id); cancelDialog() } }
        if (d.phase in setOf(DownloadPhase.QUEUED, DownloadPhase.WAITING_NETWORK, DownloadPhase.DOWNLOADING, DownloadPhase.PAUSED)) rows += item("取消下载") { app.player.downloads.cancel(id); cancelDialog() }
        rows += item("删除下载") { sensitive { confirm("删除下载？", d.track.title) { work { app.player.downloads.delete(id); cancelDialog() } } } }
        rows += item("返回") { cancelDialog() }
        dialog = LcdDialog(d.track.title, d.message.orEmpty(), rows)
    }
}
