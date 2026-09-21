package app.easepod.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.SettingsRepository
import app.easepod.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

enum class DownloadPhase { QUEUED, WAITING_NETWORK, DOWNLOADING, PAUSED, COMPLETE, FAILED, CANCELLED, EXPIRED }
data class DownloadSnapshot(
    val id: String,
    val track: Track,
    val phase: DownloadPhase,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val expiresAtMs: Long? = null,
    val checksum: String? = null,
    val message: String? = null,
    val canSeek: Boolean = true,
    val quality: String = "未知",
    internal val requestId: String? = null,
)

@UnstableApi
class DownloadCoordinator internal constructor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val resolver: StreamResolver,
    private val sourceAllowed: (Track) -> Boolean,
    private val transportFactory: (PlayableSource) -> DataSource = { MediaHttpPolicy.factory(it).createDataSource() },
    private val work: WorkManager = WorkManager.getInstance(context),
    private val awaitSourceInitialization: suspend () -> Unit = {},
) {
    private val database = DownloadDatabase(context)
    private val directory = File(context.filesDir, "offline-audio").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private val transfers = ConcurrentHashMap<String, Mutex>()
    private val watchers = ConcurrentHashMap<String, Job>()
    private val mutableDownloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    val downloads = mutableDownloads.asStateFlow()
    val totalBytes: Long get() = directory.listFiles()?.sumOf { it.length() } ?: 0L

    init {
        val initialWifiOnly = settings.settings.value.wifiDownloadsOnly
        scope.launch {
            try {
                mutex.withLock {
                    database.all().forEach { item ->
                        if (item.phase == DownloadPhase.COMPLETE && !completeFile(item.id).isFile) {
                            database.put(item.copy(phase = DownloadPhase.FAILED, message = "离线文件已丢失，请重新下载"))
                        } else if (item.phase in activePhases) {
                            val requests = work.getWorkInfosForUniqueWork(workName(item.id)).get()
                            val request = if (item.requestId == null) requests.singleOrNull { !it.state.isFinished }
                                else requests.find { it.id.toString() == item.requestId }
                            if (request == null) database.put(item.copy(phase = DownloadPhase.FAILED, message = "下载任务已中断，请重试"))
                            else {
                                database.put(item.copy(requestId = request.id.toString()))
                                if (request.constraints.requiredNetworkType != requiredNetwork()) scheduleLocked(database.get(item.id)!!)
                            }
                        }
                    }
                    refresh()
                    database.all().forEach { observe(it.id) }
                }
                initialized.complete(Unit)
            } catch (error: Throwable) {
                initialized.completeExceptionally(error)
                throw error
            }
        }
        scope.launch {
            initialized.await()
            var previous = initialWifiOnly
            settings.settings.map { it.wifiDownloadsOnly }.distinctUntilChanged().collect { wifiOnly ->
                if (wifiOnly != previous) mutex.withLock {
                    database.all().filter { it.phase in activePhases }.forEach { scheduleLocked(it) }
                }
                previous = wifiOnly
            }
        }
    }

    suspend fun canDownload(track: Track): Boolean = track.sourceId != LOCAL_SOURCE && sourceAllowed(track) &&
        resolver.offlinePolicy(track)?.valid() == true && sourceAllowed(track)

    suspend fun enqueue(track: Track): String = withContext(Dispatchers.IO) {
        initialized.await()
        require(track.sourceId != LOCAL_SOURCE) { "本地文件无需重复下载" }
        require(sourceAllowed(track)) { "音乐来源已停用" }
        val policy = resolver.offlinePolicy(track)?.takeIf { it.valid() } ?: throw SourceUnavailableException("此来源未授权离线保存")
        val id = stableMediaKey(track)
        val existing = database.get(id)
        if (existing?.phase in activePhases) return@withContext id
        if (existing?.phase == DownloadPhase.COMPLETE && offlineSource(track) != null) return@withContext id
        transfers.computeIfAbsent(id) { Mutex() }.withLock {
            val safeTrack = track.copy(contentUri = null, artworkUri = track.artworkUri?.takeIf { !it.startsWith("http") })
            mutex.withLock {
                require(sourceAllowed(track)) { "音乐来源已停用" }
                if (database.get(id)?.phase in activePhases) return@withLock
                partialFile(id).delete()
                scheduleLocked(DownloadSnapshot(id, safeTrack, DownloadPhase.QUEUED, 0L, policy.expectedBytes, policy.expiresAtMs, policy.sha256))
            }
        }
        id
    }

    fun pause(id: String) { scope.launch { pauseNow(id) } }
    private suspend fun pauseNow(id: String) {
        initialized.await()
        mutex.withLock {
            val item = database.get(id)
            if (item?.phase in activePhases) {
                database.put(item!!.copy(phase = DownloadPhase.PAUSED, message = null))
                cancelScheduled(item)
                refresh()
            }
        }
    }

    suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        initialized.await()
        transfers.computeIfAbsent(id) { Mutex() }.withLock {
            val item = database.get(id) ?: throw SourceUnavailableException("下载任务不存在")
            if (item.phase in activePhases || item.phase == DownloadPhase.COMPLETE) return@withLock
            require(sourceAllowed(item.track)) { "音乐来源已停用" }
            val policy = resolver.offlinePolicy(item.track)?.takeIf { it.valid() } ?: throw SourceUnavailableException("离线授权无效，请重新登录")
            mutex.withLock {
                val latest = database.get(id) ?: throw SourceUnavailableException("下载任务不存在")
                require(sourceAllowed(latest.track)) { "音乐来源已停用" }
                if (latest.phase !in activePhases && latest.phase != DownloadPhase.COMPLETE) {
                    scheduleLocked(latest.copy(phase = DownloadPhase.QUEUED, message = null, expiresAtMs = policy.expiresAtMs))
                }
            }
        }
    }

    fun cancel(id: String) { scope.launch {
        initialized.await()
        val cancelled = mutex.withLock {
            val item = database.get(id)
            if (item == null || item.phase == DownloadPhase.COMPLETE) false else {
                database.put(item.copy(phase = DownloadPhase.CANCELLED, message = null))
                cancelScheduled(item)
                refresh(); true
            }
        }
        if (!cancelled) return@launch
        transfers.computeIfAbsent(id) { Mutex() }.withLock {
            mutex.withLock {
                if (database.get(id)?.phase == DownloadPhase.CANCELLED) partialFile(id).delete()
            }
        }
    } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        initialized.await()
        mutex.withLock {
            val item = database.get(id) ?: return@withContext
            database.put(item.copy(phase = DownloadPhase.CANCELLED))
            cancelScheduled(item)
            refresh()
        }
        transfers.computeIfAbsent(id) { Mutex() }.withLock {
        for (file in listOf(partialFile(id), completeFile(id))) {
            if (file.exists() && !file.delete()) throw IOException("无法删除离线文件")
        }
        mutex.withLock { database.delete(id); refresh() }
        watchers.remove(id)?.cancel()
        }
    }

    fun pauseSource(sourceId: String) = pauseMatching { it.sourceId == sourceId }

    fun pauseAccount(sourceId: String, accountScope: String) = pauseMatching { it.sourceId == sourceId && it.accountScope == accountScope }

    fun pauseExternalSources() = pauseMatching { it.sourceId != LOCAL_SOURCE }

    private fun pauseMatching(matches: (Track) -> Boolean) { scope.launch {
        initialized.await()
        mutex.withLock {
            database.all().filter { matches(it.track) && it.phase in activePhases }.forEach {
                database.put(it.copy(phase = DownloadPhase.PAUSED, message = null))
                cancelScheduled(it)
            }
            refresh()
        }
    } }

    internal suspend fun offlineSource(track: Track): PlayableSource? = withContext(Dispatchers.IO) {
        initialized.await()
        if (!sourceAllowed(track)) return@withContext null
        val item = database.get(stableMediaKey(track))?.takeIf { it.phase == DownloadPhase.COMPLETE } ?: return@withContext null
        val policy = resolver.offlinePolicy(track)
        if (policy?.valid() != true || item.expiresAtMs?.let { it <= System.currentTimeMillis() } == true) {
            invalidateComplete(item, DownloadPhase.EXPIRED, "离线授权已到期")
            return@withContext null
        }
        val file = completeFile(item.id)
        if (!file.isFile || file.length() != item.downloadedBytes ||
            policy.expectedBytes?.let { file.length() != it } == true ||
            item.checksum?.let { !runCatching { fileDigest(file).equals(it, ignoreCase = true) }.getOrDefault(false) } == true ||
            policy.sha256?.let { !it.equals(item.checksum, ignoreCase = true) } == true) {
            invalidateComplete(item, DownloadPhase.FAILED, "离线文件不完整")
            return@withContext null
        }
        if (!sourceAllowed(track) || !policy.valid() || item.expiresAtMs?.let { it <= System.currentTimeMillis() } == true) return@withContext null
        PlayableSource(android.net.Uri.fromFile(file).toString(), canSeek = item.canSeek, quality = item.quality)
    }

    private suspend fun invalidateComplete(item: DownloadSnapshot, phase: DownloadPhase, message: String) = mutex.withLock {
        if (database.get(item.id) == item) { database.put(item.copy(phase = phase, message = message)); refresh() }
    }

    internal suspend fun execute(id: String, requestId: String, progress: suspend (DownloadSnapshot) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            initialized.await()
            awaitSourceInitialization()
            transfers.computeIfAbsent(id) { Mutex() }.withLock { transfer(id, requestId, progress) }
        }

    private suspend fun transfer(id: String, requestId: String, progress: suspend (DownloadSnapshot) -> Unit): Boolean = withContext(Dispatchers.IO) {
        var item = database.get(id) ?: return@withContext true
        if (item.phase !in activePhases || item.requestId != requestId) return@withContext true
        try {
            if (!sourceAllowed(item.track)) { pauseNow(id); return@withContext true }
            val policy = resolver.offlinePolicy(item.track)?.takeIf { it.valid() } ?: throw SourceUnavailableException("离线授权已失效")
            if (!sourceAllowed(item.track)) { pauseNow(id); return@withContext true }
            val source = validatedSource(resolver.resolve(item.track))
            if (!sourceAllowed(item.track)) { pauseNow(id); return@withContext true }
            val grant = source.offlineGrant?.takeIf { it.valid() } ?: throw SourceUnavailableException("媒体响应未授权离线保存")
            val expected = grant.expectedBytes ?: policy.expectedBytes
            val checksum = grant.sha256 ?: policy.sha256
            val expiry = listOfNotNull(grant.expiresAtMs, policy.expiresAtMs).minOrNull()
            val partial = partialFile(id)
            // A changed integrity contract cannot be resumed against bytes from another encoding.
            if (checksum == null || checksum != item.checksum || (item.totalBytes != null && expected != null && item.totalBytes != expected)) partial.delete()
            var written = partial.length()
            if (expected != null && written > expected) { partial.delete(); written = 0L }
            item = item.copy(phase = DownloadPhase.DOWNLOADING, downloadedBytes = written, totalBytes = expected, checksum = checksum, expiresAtMs = expiry, message = null,
                canSeek = source.canSeek, quality = source.quality)
            storeActive(item); progress(item)
            val transport = transportFactory(source)
            try {
                val remaining = transport.open(DataSpec.Builder().setUri(source.uri).setPosition(written).setKey(id).build())
                val total = expected ?: remaining.takeIf { it != C.LENGTH_UNSET.toLong() }?.let { it + written }
                if (total != null && directory.usableSpace < (total - written).coerceAtLeast(0L) + 8L * 1024L * 1024L) throw IOException("存储空间不足")
                item = item.copy(totalBytes = total)
                RandomAccessFile(partial, "rw").use { output ->
                    output.seek(written)
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = System.currentTimeMillis()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val active = database.get(id)
                        if (!sourceAllowed(item.track) || active?.phase != DownloadPhase.DOWNLOADING || active.requestId != requestId) throw CancellationException("下载已暂停")
                        if (expiry != null && expiry <= System.currentTimeMillis()) throw SourceUnavailableException("离线授权已到期")
                        val count = transport.read(buffer, 0, buffer.size)
                        if (count == C.RESULT_END_OF_INPUT) break
                        output.write(buffer, 0, count)
                        written += count
                        if (total != null && written > total) throw IntegrityException("下载长度超出媒体声明")
                        if (System.currentTimeMillis() - lastReport >= 500L) {
                            item = item.copy(downloadedBytes = written)
                            storeActive(item); progress(item); lastReport = System.currentTimeMillis()
                        }
                    }
                    output.fd.sync()
                }
                if (written <= 0L || total != null && written != total) throw IntegrityException("音频文件不完整")
                // Unknown-length media requires a provider checksum before it can be declared complete.
                if (total == null && checksum == null) throw IntegrityException("来源缺少离线文件完整性信息")
                val actualChecksum = fileDigest(partial)
                if (checksum != null && !actualChecksum.equals(checksum, ignoreCase = true)) throw IntegrityException("离线文件校验失败")
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    val active = database.get(id)
                    if (!sourceAllowed(item.track) || active?.phase != DownloadPhase.DOWNLOADING || active.requestId != requestId) throw CancellationException("下载已取消")
                    if (expiry != null && expiry <= System.currentTimeMillis()) throw SourceUnavailableException("离线授权已到期")
                    if (!partial.renameTo(completeFile(id))) throw IOException("无法保存完整离线文件")
                    database.put(item.copy(phase = DownloadPhase.COMPLETE, downloadedBytes = written, totalBytes = written, checksum = actualChecksum, message = null))
                    refresh()
                }
            } finally { transport.close() }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error is IntegrityException) partialFile(id).delete()
            val latest = database.get(id)
            if (latest?.phase in activePhases && latest?.requestId == requestId) {
                storeActive(item.copy(phase = DownloadPhase.FAILED, downloadedBytes = partialFile(id).length(),
                    message = error.message?.takeIf { !it.contains("http") } ?: "下载失败，请重试"))
            }
            true
        }
    }

    private suspend fun storeActive(item: DownloadSnapshot) = mutex.withLock {
        val active = database.get(item.id)
        if (active?.phase !in activePhases || active?.requestId != item.requestId) throw CancellationException("下载已暂停或取消")
        database.put(item); refresh()
    }
    private fun refresh() { mutableDownloads.value = database.all() }
    internal fun release() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        database.close()
    }
    private fun partialFile(id: String) = File(directory, "$id.part")
    private fun completeFile(id: String) = File(directory, "$id.audio")
    private fun workName(id: String) = "easepod-download-$id"
    private fun requiredNetwork() = if (settings.settings.value.wifiDownloadsOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
    private fun networkReady(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (!settings.settings.value.wifiDownloadsOnly || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
    private fun cancelScheduled(item: DownloadSnapshot) {
        item.requestId?.let { runCatching { work.cancelWorkById(UUID.fromString(it)) } }
            ?: work.cancelUniqueWork(workName(item.id))
    }
    private fun scheduleLocked(item: DownloadSnapshot) {
        val request = OneTimeWorkRequestBuilder<AudioDownloadWorker>()
            .setInputData(workDataOf("downloadId" to item.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(requiredNetwork()).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        database.put(item.copy(requestId = request.id.toString(), phase = if (networkReady()) DownloadPhase.QUEUED else DownloadPhase.WAITING_NETWORK))
        work.enqueueUniqueWork(workName(item.id), ExistingWorkPolicy.REPLACE, request)
        refresh(); observe(item.id)
    }
    private fun observe(id: String) {
        if (watchers[id]?.isActive == true) return
        watchers[id] = scope.launch {
            work.getWorkInfosForUniqueWorkFlow(workName(id)).collect { requests ->
                reconcileWorkState(id, requests)
            }
        }
    }

    internal suspend fun reconcileWorkState(id: String, requests: List<WorkInfo>) = mutex.withLock {
        val current = database.get(id) ?: return@withLock
        if (current.phase !in activePhases) return@withLock
        var request = requests.find { it.id.toString() == current.requestId } ?: return@withLock
        if (current.phase == DownloadPhase.DOWNLOADING && request.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) {
            // A queued snapshot can arrive after the worker has already started transferring.
            request = work.getWorkInfoById(request.id).get() ?: return@withLock
        }
        val phase = when (request.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> if (networkReady()) DownloadPhase.QUEUED else DownloadPhase.WAITING_NETWORK
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED -> DownloadPhase.FAILED
            WorkInfo.State.RUNNING -> current.phase
        }
        if (phase != current.phase) {
            database.put(current.copy(phase = phase, message = if (phase == DownloadPhase.FAILED) "下载任务已中断，请重试" else null)); refresh()
        }
    }

    companion object {
        private class IntegrityException(message: String) : IOException(message)
        private val activePhases = setOf(DownloadPhase.QUEUED, DownloadPhase.WAITING_NETWORK, DownloadPhase.DOWNLOADING)
        private fun fileDigest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

@UnstableApi
class AudioDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("downloadId") ?: return Result.failure()
        return try {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("offline-downloads", "离线音乐", NotificationManager.IMPORTANCE_LOW))
            setForeground(notification(id, null))
            PlaybackRuntime.controller().downloads.execute(id, this.id.toString()) { item -> setForeground(notification(id, item)) }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure() }
    }

    private fun notification(id: String, item: DownloadSnapshot?): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "offline-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item?.track?.title ?: "正在准备离线音乐")
            .setContentText("EasePod")
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, item?.totalBytes?.takeIf { it > 0 }?.let { (item.downloadedBytes * 100 / it).toInt().coerceIn(0, 100) } ?: 0, item?.totalBytes == null)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

private class DownloadDatabase(context: Context) : SQLiteOpenHelper(context, "offline-downloads.db", null, 2) {
    private var released = false
    private fun ensureOpen() { if (released) throw CancellationException("下载管理器已关闭") }
    @Synchronized override fun close() {
        if (released) return
        released = true
        super.close()
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY NOT NULL, track_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album_id TEXT, album_title TEXT, duration INTEGER, artwork TEXT, source_id TEXT NOT NULL, account_scope TEXT NOT NULL, remote_id TEXT NOT NULL, phase TEXT NOT NULL, bytes INTEGER NOT NULL, total INTEGER, expires INTEGER, checksum TEXT, message TEXT, can_seek INTEGER NOT NULL DEFAULT 1, quality TEXT NOT NULL DEFAULT '未知', request_id TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN can_seek INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE downloads ADD COLUMN quality TEXT NOT NULL DEFAULT '未知'")
            db.execSQL("ALTER TABLE downloads ADD COLUMN request_id TEXT")
        }
    }
    fun get(id: String): DownloadSnapshot? = all("id = ?", arrayOf(id)).firstOrNull()
    @Synchronized fun all(selection: String? = null, args: Array<String>? = null): List<DownloadSnapshot> {
        ensureOpen()
        return readableDatabase.query("downloads", null, selection, args, null, null, "rowid DESC").use { cursor ->
            buildList {
                fun text(name: String): String? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getString(it) }
                fun number(name: String): Long? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getLong(it) }
                while (cursor.moveToNext()) add(DownloadSnapshot(
                    id = text("id")!!,
                    track = Track(id = text("track_id")!!, title = text("title")!!, artist = text("artist")!!, albumId = text("album_id"), albumTitle = text("album_title"), durationMs = number("duration"), artworkUri = text("artwork"), sourceId = text("source_id")!!, accountScope = text("account_scope")!!, remoteId = text("remote_id")!!),
                    phase = runCatching { DownloadPhase.valueOf(text("phase")!!) }.getOrDefault(DownloadPhase.FAILED),
                    downloadedBytes = number("bytes")!!, totalBytes = number("total"), expiresAtMs = number("expires"), checksum = text("checksum"), message = text("message"),
                    canSeek = number("can_seek") != 0L, quality = text("quality") ?: "未知", requestId = text("request_id"),
                ))
            }
        }
    }
    @Synchronized fun put(item: DownloadSnapshot) {
        ensureOpen()
        val values = ContentValues().apply {
            put("id", item.id); put("track_id", item.track.id); put("title", item.track.title); put("artist", item.track.artist)
            put("album_id", item.track.albumId); put("album_title", item.track.albumTitle); put("duration", item.track.durationMs)
            put("artwork", item.track.artworkUri); put("source_id", item.track.sourceId); put("account_scope", item.track.accountScope); put("remote_id", item.track.remoteId)
            put("phase", item.phase.name); put("bytes", item.downloadedBytes); put("total", item.totalBytes); put("expires", item.expiresAtMs); put("checksum", item.checksum); put("message", item.message)
            put("can_seek", if (item.canSeek) 1 else 0); put("quality", item.quality); put("request_id", item.requestId)
        }
        check(writableDatabase.insertWithOnConflict("downloads", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "无法保存下载进度" }
    }
    @Synchronized fun delete(id: String) { ensureOpen(); writableDatabase.delete("downloads", "id = ?", arrayOf(id)) }
}
