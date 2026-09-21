package app.easepod.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import app.easepod.core.Album
import app.easepod.core.HistoryEntry
import app.easepod.core.LibraryRepository
import app.easepod.core.LibraryRoot
import app.easepod.core.LibrarySnapshot
import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.Playlist
import app.easepod.core.PlaylistEntry
import app.easepod.core.QueueCheckpoint
import app.easepod.core.QueueEntry
import app.easepod.core.QueueStore
import app.easepod.core.RepeatMode
import app.easepod.core.ScanPhase
import app.easepod.core.ScanProgress
import app.easepod.core.Track
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AndroidLibraryRepository internal constructor(
    context: Context,
    private val database: LibraryDatabase,
    private val preferences: AndroidSettingsRepository,
) : LibraryRepository, QueueStore {
    constructor(context: Context) : this(context.applicationContext, LibraryDatabase.get(context), AndroidSettingsRepository(context))

    private val context = context.applicationContext
    private val dao = database.dao()
    private val scanner = SafScanner(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = CompletableDeferred<Unit>()
    private val mutations = Mutex()
    private val generation = AtomicLong()
    private val mutableLibrary = MutableStateFlow(LibrarySnapshot())
    private val mutableScan = MutableStateFlow(ScanProgress())
    override val library = mutableLibrary.asStateFlow()
    override val scan = mutableScan.asStateFlow()
    @Volatile private var scanTask: Job? = null
    @Volatile private var pending: Pending? = null
    private var scanningUri: String? = null
    private data class Pending(val id: String, val root: RootRow, val generation: Long)

    init {
        scope.launch {
            try {
                database.withTransaction { dao.cancelUnfinishedScans(); dao.clearCancelledCandidates() }
                releaseUnusedGrants(emptySet(), onlyUnpublished = true)
                applyPendingSettings()
                verifyPermission()
                refresh()
                dao.lastScan()?.let { row -> mutableScan.value = row.toProgress() }
                initialized.complete(Unit)
            } catch (error: Exception) {
                mutableScan.value = ScanProgress(ScanPhase.FAILED, message = "无法打开音乐资料库，请保留应用数据后重试")
                initialized.completeExceptionally(error)
            }
        }
    }

    override suspend fun scanFolder(treeUri: String) {
        initialized.await()
        val caller = currentCoroutineContext()[Job]
        val token = mutations.withLock {
            scanTask?.cancel()
            scanningUri = treeUri
            cancelPending()
            generation.incrementAndGet().also { scanTask = caller }
        }
        val id = UUID.randomUUID().toString()
        try {
            withContext(Dispatchers.IO) {
                mutations.withLock {
                    check(generation.get() == token)
                    mutableScan.value = ScanProgress(ScanPhase.SCANNING)
                    rememberFolderGrant(treeUri)
                }
                val document = scanner.readableRoot(treeUri)
                val tree = Uri.parse(treeUri)
                val authority = requireNotNull(tree.authority)
                val root = mutations.withLock {
                    check(generation.get() == token)
                    val existingRoot = dao.findRoot(authority, DocumentsContract.getTreeDocumentId(tree))
                    RootRow(existingRoot?.id ?: UUID.randomUUID().toString(), authority, document.id, treeUri, document.name.take(512), true).also {
                        if (existingRoot == null) dao.putRoot(it)
                    }
                }
                dao.putScan(ScanJobRow(id, root.id, ScanPhase.SCANNING.name))
                val result = scanner.scan(root, id, dao.tracks(), dao.albums()) { tracks, albums, skipped, folder ->
                    mutations.withLock {
                        check(generation.get() == token)
                        mutableScan.value = ScanProgress(ScanPhase.SCANNING, folder, tracks, albums, skipped)
                    }
                }
                currentCoroutineContext().ensureActive()
                check(generation.get() == token)
                val candidates = result.tracks.map { ScanCandidateRow(id, it.id, "track", DataJson.track(it).toString()) } +
                    result.albums.map { ScanCandidateRow(id, it.id, "album", DataJson.album(it).toString()) } +
                    result.members.map { ScanCandidateRow(id, "member:${it.trackId}", "member", DataJson.member(it).toString()) }
                mutations.withLock {
                    currentCoroutineContext().ensureActive()
                    check(generation.get() == token)
                    database.withTransaction {
                        dao.putCandidates(candidates)
                        dao.putScan(ScanJobRow(id, root.id, ScanPhase.REVIEW.name, result.tracks.size, result.albums.size, result.skipped, JSONArray(result.issues).toString()))
                    }
                    pending = Pending(id, root, token)
                    mutableScan.value = ScanProgress(ScanPhase.REVIEW, root.name, result.tracks.size, result.albums.size, result.skipped, result.issues, "扫描完成，确认后更新音乐库")
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                mutations.withLock {
                    val cancelled = error is CancellationException || generation.get() != token
                    val phase = if (cancelled) ScanPhase.CANCELLED else ScanPhase.FAILED
                    dao.scanJob(id)?.let { dao.putScan(it.copy(phase = phase.name)); dao.clearCandidates(id) }
                    if (generation.get() == token) {
                        pending = null
                        mutableScan.value = mutableScan.value.copy(phase = phase, message = if (cancelled) "已取消扫描，原音乐库保留" else "扫描未完成，原音乐库保留：${error.message?.take(160) ?: "读取失败"}")
                        if (error is SecurityException) verifyPermission()
                        refresh()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mutations.withLock {
                    if (generation.get() == token) { scanTask = null; scanningUri = null }
                    releaseUnusedGrants(emptySet(), onlyUnpublished = true)
                }
            }
        }
    }

    override suspend fun publishScan() = publishScan {}

    suspend fun publishScan(beforeCommit: suspend () -> Unit = {}) = withContext(Dispatchers.IO) {
        initialized.await()
        mutations.withLock {
            val candidate = pending ?: error("没有待确认的扫描结果")
            check(candidate.generation == generation.get() && mutableScan.value.phase == ScanPhase.REVIEW) { "扫描结果已失效" }
            try { scanner.readableRoot(candidate.root.uri) }
            catch (error: SecurityException) { verifyPermission(); refresh(); throw error }
            val rows = dao.candidates(candidate.id)
            val tracks = rows.filter { it.kind == "track" }.map { DataJson.track(JSONObject(it.payload)) }
            val albums = rows.filter { it.kind == "album" }.map { DataJson.album(JSONObject(it.payload)) }
            val members = rows.filter { it.kind == "member" }.map { DataJson.member(JSONObject(it.payload)) }
            validateCandidates(candidate.root.id, candidate.id, tracks, albums, members)
            currentCoroutineContext().ensureActive()
            try {
                // A committed publication stays complete; pre-commit session or grant loss still rolls it back.
                withContext(NonCancellable) {
                    database.withTransaction {
                        beforeCommit()
                        scanner.readableRoot(candidate.root.uri)
                        val job = dao.scanJob(candidate.id) ?: error("扫描记录不存在")
                        check(job.phase == ScanPhase.REVIEW.name && job.tracks == tracks.size && job.albums == albums.size)
                        dao.markLocalUnavailable()
                        dao.clearMembers(candidate.root.id)
                        dao.putTracks(tracks)
                        dao.putAlbums(albums)
                        dao.putMembers(members)
                        dao.putRoot(candidate.root.copy(permissionValid = true))
                        dao.putScan(job.copy(phase = ScanPhase.COMPLETE.name))
                        dao.putState(LibraryStateRow(activeRootId = candidate.root.id, activeScanId = candidate.id))
                        dao.clearCandidates(candidate.id)
                        check(candidate.generation == generation.get()) { "扫描已被取消" }
                        beforeCommit()
                        scanner.readableRoot(candidate.root.uri)
                    }
                    pending = null
                    mutableScan.value = mutableScan.value.copy(phase = ScanPhase.COMPLETE, message = "音乐库已更新")
                    refresh()
                }
            } catch (error: SecurityException) {
                verifyPermission(); refresh(); throw error
            }
        }
    }

    override fun cancelScan() {
        val requestedGeneration = generation.get()
        scope.launch {
            mutations.withLock {
                if (generation.get() == requestedGeneration && mutableScan.value.phase in setOf(ScanPhase.SCANNING, ScanPhase.REVIEW, ScanPhase.FAILED)) {
                    generation.incrementAndGet()
                    scanTask?.cancel()
                    scanTask = null
                    scanningUri = null
                    cancelPending()
                    releaseUnusedGrants(emptySet(), onlyUnpublished = true)
                    mutableScan.value = mutableScan.value.copy(phase = ScanPhase.CANCELLED, message = "已取消扫描，原音乐库保留")
                }
            }
        }
    }

    private suspend fun cancelPending() {
        val cancelled = pending ?: return
        pending = null
        dao.scanJob(cancelled.id)?.takeIf { it.phase != ScanPhase.COMPLETE.name }?.let { dao.putScan(it.copy(phase = ScanPhase.CANCELLED.name)) }
        dao.clearCandidates(cancelled.id)
        releaseUnusedGrants(emptySet(), onlyUnpublished = true)
    }

    override suspend fun removeRoot() = mutate {
        generation.incrementAndGet()
        scanTask?.cancel()
        scanTask = null
        scanningUri = null
        cancelPending()
        mutableScan.value = ScanProgress()
        dao.markLocalUnavailable()
        dao.putState(LibraryStateRow())
    }

    suspend fun releaseUnusedFolderGrants(retainedTrackIds: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        initialized.await()
        mutations.withLock { releaseUnusedGrants(retainedTrackIds, onlyUnpublished = false) }
    }

    private suspend fun rememberFolderGrant(treeUri: String) {
        val uri = Uri.parse(treeUri)
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) ||
            context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return
        val authority = uri.authority ?: return
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        if (dao.findRoot(authority, documentId) == null) {
            dao.putRoot(RootRow(UUID.randomUUID().toString(), authority, documentId, treeUri, "", false))
        }
    }

    private suspend fun releaseUnusedGrants(retainedTrackIds: Set<String>, onlyUnpublished: Boolean) {
        val roots = dao.roots()
        val activeRoot = dao.state()?.activeRootId
        val retainedRoots = if (retainedTrackIds.isEmpty()) emptySet() else dao.tracks().filter { it.id in retainedTrackIds }.mapNotNull { it.rootId }.toSet()
        val protectedUris = roots.filter { it.id == activeRoot || it.id in retainedRoots }.map { it.uri }.toSet() +
            listOfNotNull(pending?.root?.uri, scanningUri)
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        // Only known library roots are ours to release; arbitrary system grants remain untouched.
        for (root in roots) {
            if (root.uri in protectedUris || onlyUnpublished && dao.hasPublishedRoot(root.id)) continue
            val uri = Uri.parse(root.uri)
            if (uri in grants) {
                try { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { continue }
            }
            if (root.permissionValid) dao.putRoot(root.copy(permissionValid = false))
        }
    }

    override suspend fun checkPermission() = withContext(Dispatchers.IO) {
        initialized.await()
        mutations.withLock { verifyPermission(); refresh() }
    }

    private suspend fun verifyPermission() {
        val state = dao.state() ?: return
        val root = state.activeRootId?.let { dao.root(it) } ?: return
        val failure = runCatching { scanner.readableRoot(root.uri) }.exceptionOrNull()
        if (failure is SecurityException) database.withTransaction {
            dao.putRoot(root.copy(permissionValid = false))
            dao.markRootUnavailable(root.id)
        }
    }

    override suspend fun createPlaylist(title: String): String = createPlaylist(title, emptyList())

    suspend fun createPlaylist(title: String, initialTrackIds: List<String>): String {
        val normalized = playlistTitle(title)
        require(initialTrackIds.size <= 100_000) { "歌单条目超过上限" }
        val id = UUID.randomUUID().toString()
        mutate {
            initialTrackIds.distinct().forEach { check(dao.track(it) != null) { "歌曲已不存在" } }
            dao.putPlaylist(PlaylistRow(id, normalized, System.currentTimeMillis()))
            dao.putEntries(initialTrackIds.mapIndexed { position, trackId ->
                PlaylistEntryRow(UUID.randomUUID().toString(), id, trackId, position)
            })
        }
        return id
    }
    override suspend fun renamePlaylist(id: String, title: String) = mutate {
        check(dao.playlists().any { it.id == id }) { "歌单已不存在" }
        dao.renamePlaylist(id, playlistTitle(title))
    }
    override suspend fun deletePlaylist(id: String) = mutate { dao.deletePlaylist(id) }
    override suspend fun addToPlaylist(id: String, trackId: String) = mutate {
        check(dao.track(trackId) != null) { "歌曲已不存在" }
        check(dao.playlists().any { it.id == id }) { "歌单已不存在" }
        val entries = dao.entries(id)
        require(entries.size < 100_000) { "歌单条目超过上限" }
        dao.putEntries(listOf(PlaylistEntryRow(UUID.randomUUID().toString(), id, trackId, entries.size)))
    }
    override suspend fun removeFromPlaylist(id: String, entryId: String) = mutate {
        replaceEntries(id, dao.entries(id).filter { it.id != entryId })
    }
    override suspend fun movePlaylistEntry(id: String, entryId: String, delta: Int) = mutate {
        val entries = dao.entries(id).toMutableList()
        val index = entries.indexOfFirst { it.id == entryId }
        if (index >= 0) {
            val destination = (index.toLong() + delta).coerceIn(0, entries.lastIndex.toLong()).toInt()
            entries.add(destination, entries.removeAt(index))
            replaceEntries(id, entries)
        }
    }
    suspend fun relinkPlaylistTrack(oldId: String, newId: String) = mutate {
        require(oldId != newId) { "请选择另一首本地音乐" }
        val old = dao.track(oldId) ?: error("待关联歌曲已不存在")
        val replacement = dao.track(newId) ?: error("所选歌曲已不存在")
        require(old.sourceId == LOCAL_SOURCE && !old.available) { "只能重新关联不可用的本地歌曲" }
        val rootId = dao.state()?.activeRootId
        require(replacement.sourceId == LOCAL_SOURCE && replacement.available && replacement.rootId != null && replacement.rootId == rootId) { "请选择当前目录中的可用本地歌曲" }
        val root = dao.root(replacement.rootId) ?: error("音乐文件夹已不存在")
        scanner.readableRoot(root.uri)
        dao.relinkPlaylistTrack(oldId, newId)
    }
    private suspend fun replaceEntries(id: String, entries: List<PlaylistEntryRow>) {
        dao.clearEntries(id)
        dao.putEntries(entries.mapIndexed { index, row -> row.copy(position = index) })
    }
    override suspend fun recordHistory(trackId: String) = mutate {
        dao.track(trackId)?.let { track ->
            dao.putHistory(HistoryRow(UUID.randomUUID().toString(), trackId, System.currentTimeMillis(), track.title, track.artist, dao.historyAlbumTitle(trackId)))
            dao.trimHistory()
        }
    }
    override suspend fun removeHistory(id: String) = mutate { dao.removeHistory(id) }
    override suspend fun clearHistory() = mutate { dao.clearHistory() }

    suspend fun rememberTracks(tracks: List<Track>) = mutate {
        require(tracks.size <= 1000) { "曲目数量超过上限" }
        val existing = dao.tracks().associateBy { it.id }
        val identities = existing.values.associateBy { Triple(it.sourceId, it.accountScope, it.remoteId) }.toMutableMap()
        val distinctTracks = tracks.filter { it.sourceId != LOCAL_SOURCE }.groupBy { it.id }.values.map { group ->
            require(group.map { Triple(it.sourceId, it.accountScope, it.remoteId) }.distinct().size == 1) { "曲目标识重复且来源冲突" }
            group.last()
        }
        val rows = distinctTracks.map { track ->
            require(track.id.length in 1..200 && track.sourceId.matches(Regex("[a-zA-Z0-9._-]{1,200}")))
            require(track.accountScope.length in 1..200 && track.remoteId.length in 1..4096)
            val old = existing[track.id]
            require(old == null || (old.sourceId == track.sourceId && old.accountScope == track.accountScope && old.remoteId == track.remoteId)) { "曲目标识与已保存来源冲突" }
            val key = Triple(track.sourceId, track.accountScope, track.remoteId)
            require(identities[key]?.id.let { it == null || it == track.id }) { "同一来源曲目不能创建多个标识" }
            track.toStoredTrack().copy(available = track.available).also { identities[key] = it }
        }
        dao.putTracks(rows)
    }

    suspend fun localLyrics(trackId: String): String? = withContext(Dispatchers.IO) {
        initialized.await()
        val track = dao.track(trackId) ?: return@withContext null
        val root = track.rootId?.let { dao.root(it) } ?: return@withContext null
        if (dao.state()?.activeRootId != root.id || !track.available) return@withContext null
        try {
            val album = dao.albumMembers().firstOrNull { it.trackId == trackId }?.albumId?.let { id -> dao.albums().firstOrNull { it.id == id } }
            scanner.lyrics(root, track, album?.documentId ?: root.documentId)
        } catch (failure: SecurityException) {
            checkPermission()
            throw failure
        }
    }

    override suspend fun loadQueue(): QueueCheckpoint = withContext(Dispatchers.IO) {
        initialized.await()
        database.withTransaction {
            val state = dao.queueState() ?: return@withTransaction QueueCheckpoint()
            val stored = dao.queueEntries()
            val tracks = dao.tracks().associateBy { it.id }
            val albums = dao.albums().associateBy { it.id }
            val members = dao.albumMembers().associateBy { it.trackId }
            val entries = stored.mapNotNull { row -> tracks[row.trackId]?.let { QueueEntry(row.id, it.toTrack(members[it.id]?.albumId?.let(albums::get))) } }
            val current = state.currentEntryId?.takeIf { id -> entries.any { it.id == id } } ?: entries.firstOrNull()?.id
            val duration = entries.firstOrNull { it.id == current }?.track?.durationMs
            val position = if (current == null) 0 else state.positionMs.coerceIn(0, duration?.coerceAtLeast(0) ?: Long.MAX_VALUE)
            QueueCheckpoint(entries, current, position, runCatching { RepeatMode.valueOf(state.repeat) }.getOrDefault(RepeatMode.OFF), state.shuffle,
                stored.sortedBy { it.originalPosition }.map { it.id })
        }
    }

    override suspend fun saveQueue(checkpoint: QueueCheckpoint) = withContext(Dispatchers.IO) {
        initialized.await()
        require(checkpoint.entries.size <= 100_000 && checkpoint.entries.map { it.id }.distinct().size == checkpoint.entries.size)
        require(checkpoint.currentEntryId == null || checkpoint.entries.any { it.id == checkpoint.currentEntryId })
        val original = checkpoint.originalOrder.takeIf { it.size == checkpoint.entries.size && it.toSet() == checkpoint.entries.map { entry -> entry.id }.toSet() }
            ?: checkpoint.entries.map { it.id }
        val positions = original.withIndex().associate { it.value to it.index }
        mutations.withLock {
            database.withTransaction {
                dao.insertTracks(checkpoint.entries.map { it.track.toStoredTrack() })
                dao.clearQueueEntries()
                dao.putQueueEntries(checkpoint.entries.mapIndexed { index, entry -> QueueEntryRow(entry.id, entry.track.id, index, positions.getValue(entry.id)) })
                dao.putQueueState(QueueStateRow(currentEntryId = checkpoint.currentEntryId, positionMs = checkpoint.positionMs.coerceAtLeast(0), repeat = checkpoint.repeat.name, shuffle = checkpoint.shuffle))
            }
            refresh()
        }
    }

    override suspend fun exportBackup(passphrase: CharArray): ByteArray = withContext(Dispatchers.IO) {
        initialized.await()
        val settings = preferences.current()
        val json = database.withTransaction {
            val entries = dao.playlistEntries()
            val referenced = entries.map { it.trackId }.toSet()
            val albums = dao.albums().associateBy { it.id }
            val members = dao.albumMembers().associateBy { it.trackId }
            JSONObject().apply {
                put("version", 1); put("exportId", UUID.randomUUID().toString()); put("settings", DataJson.settings(settings))
                put("tracks", JSONArray(dao.tracks().filter { it.id in referenced }.map { DataJson.portableTrack(it, members[it.id]?.albumId?.let(albums::get)) }))
                put("playlists", JSONArray(dao.playlists().map { list -> JSONObject().apply {
                    put("id", list.id); put("title", list.title)
                    put("entries", JSONArray(entries.filter { it.playlistId == list.id }.map { entry -> JSONObject().apply { put("id", entry.id); put("trackId", DataJson.portableId(entry.trackId)) } }))
                } }))
            }
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        try { BackupCrypto.encrypt(bytes, passphrase) } finally { bytes.fill(0) }
    }

    override suspend fun importBackup(bytes: ByteArray, passphrase: CharArray) = importBackup(bytes, passphrase) {}

    suspend fun importBackup(bytes: ByteArray, passphrase: CharArray, beforeCommit: suspend () -> Unit) = withContext(Dispatchers.IO) {
        initialized.await()
        val plain = BackupCrypto.decrypt(bytes, passphrase)
        val payload = try { BackupJson.parse(plain) } finally { plain.fill(0) }
        mutations.withLock {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                beforeCommit()
                if (dao.imported(payload.exportId) != null) return@withTransaction
                val existingTracks = dao.tracks().associateBy { it.id }
                val existingSources = existingTracks.values.filter { it.sourceId != LOCAL_SOURCE }.associateBy { Triple(it.sourceId, it.accountScope, it.remoteId) }
                val trackMapping = payload.tracks.associate { row ->
                    val matching = existingSources[Triple(row.sourceId, row.accountScope, row.remoteId)]
                    row.id to (matching?.id ?: if (existingTracks.containsKey(row.id)) UUID.randomUUID().toString() else row.id)
                }
                val existingPlaylists = dao.playlists().map { it.id }.toSet()
                val existingEntries = dao.playlistEntries().map { it.id }.toSet()
                val playlistMapping = payload.playlists.associate { row -> row.id to if (row.id in existingPlaylists) UUID.randomUUID().toString() else row.id }
                dao.putTracks(payload.tracks.filter { trackMapping.getValue(it.id) !in existingTracks }.map {
                    val mappedId = trackMapping.getValue(it.id)
                    it.copy(id = mappedId, remoteId = if (it.sourceId == LOCAL_SOURCE) "relink/$mappedId" else it.remoteId)
                })
                payload.playlists.forEach { row -> dao.putPlaylist(row.copy(id = playlistMapping.getValue(row.id), createdAt = System.currentTimeMillis() + row.createdAt)) }
                dao.putEntries(payload.entries.map { it.copy(id = if (it.id in existingEntries) UUID.randomUUID().toString() else it.id, playlistId = playlistMapping.getValue(it.playlistId), trackId = trackMapping.getValue(it.trackId)) })
                dao.putImport(BackupImportRow(payload.exportId, DataJson.settings(payload.settings).toString(), System.currentTimeMillis()))
                currentCoroutineContext().ensureActive()
                beforeCommit()
            }
            // The Room commit completes the import; its settings journal survives a later lock or interruption.
            applyPendingSettings()
            refresh()
        }
    }

    private suspend fun applyPendingSettings() {
        dao.pendingSettings().sortedBy { it.importedAt }.forEach { row ->
            val restored = DataJson.settings(JSONObject(requireNotNull(row.settingsJson)))
            preferences.update { current -> restored.copy(safeMode = current.safeMode, lockScreenOverlay = false) }
            dao.putImport(row.copy(settingsJson = null))
        }
    }

    private suspend fun mutate(action: suspend () -> Unit) = withContext(Dispatchers.IO) {
        initialized.await()
        mutations.withLock { database.withTransaction { action() }; refresh() }
    }

    private suspend fun refresh() {
        mutableLibrary.value = database.withTransaction {
            val state = dao.state()
            val root = state?.activeRootId?.let { dao.root(it) }
            val allAlbums = dao.albums().associateBy { it.id }
            val members = dao.albumMembers()
            val memberByTrack = members.associateBy { it.trackId }
            val tracks = dao.tracks().map { it.toTrack(memberByTrack[it.id]?.albumId?.let(allAlbums::get)) }
            val albums = allAlbums.values.filter { it.rootId == state?.activeRootId && it.scanId == state.activeScanId }.map { row ->
                Album(row.id, row.title, row.artist, row.artworkUri, members.filter { it.albumId == row.id }.sortedBy { it.position }.map { it.trackId })
            }.sortedWith { a, b -> ScanRules.compareNatural(a.title, b.title).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
            val entries = dao.playlistEntries().groupBy { it.playlistId }
            val tracksById = tracks.associateBy { it.id }
            val history = dao.history().mapNotNull { row -> tracksById[row.trackId]?.let { track ->
                HistoryEntry(row.id, track.copy(title = row.title, artist = row.artist, albumTitle = row.albumTitle), row.playedAt)
            } }
            LibrarySnapshot(tracks, albums, dao.playlists().map { Playlist(it.id, it.title, entries[it.id].orEmpty().map { entry -> PlaylistEntry(entry.id, entry.trackId) }) },
                history, root?.let { LibraryRoot(it.uri, it.name, it.permissionValid) }, ready = true)
        }
    }

    fun close() { scope.cancel() }

    private fun playlistTitle(title: String) = title.trim().also { require(it.length in 1..60 && '\u0000' !in it) { "歌单名称需为 1 至 60 个字符" } }
    private fun Track.toStoredTrack() = TrackRow(id, null, null, null, title.take(512), artist.take(512), genre.take(512), durationMs,
        null, null, false,
        sourceId, accountScope, remoteId, discNumber, trackNumber, "", albumTitle)
    private fun ScanJobRow.toProgress(): ScanProgress {
        val issuesArray = JSONArray(issues)
        return ScanProgress(runCatching { ScanPhase.valueOf(phase) }.getOrDefault(ScanPhase.FAILED), tracks = tracks, albums = albums, skipped = skipped,
            issues = (0 until issuesArray.length()).map { issuesArray.getString(it) })
    }

    companion object {
        internal fun validateCandidates(rootId: String, scanId: String, tracks: List<TrackRow>, albums: List<AlbumRow>, members: List<AlbumMemberRow>) {
            require(tracks.all { it.rootId == rootId && it.scanId == scanId } && albums.all { it.rootId == rootId && it.scanId == scanId })
            require(tracks.map { it.id }.distinct().size == tracks.size && tracks.map { it.documentId }.distinct().size == tracks.size)
            require(albums.map { it.id }.distinct().size == albums.size && albums.map { it.documentId }.distinct().size == albums.size)
            val trackIds = tracks.map { it.id }.toSet()
            val albumIds = albums.map { it.id }.toSet()
            require(members.map { it.trackId }.distinct().size == members.size && members.all { it.trackId in trackIds && it.albumId in albumIds })
            require(albums.all { album -> members.any { it.albumId == album.id } })
            members.groupBy { it.albumId }.values.forEach { list -> require(list.map { it.position }.sorted() == list.indices.toList()) }
        }
    }
}
