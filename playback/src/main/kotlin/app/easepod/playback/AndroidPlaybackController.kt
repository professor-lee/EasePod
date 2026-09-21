package app.easepod.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.PlaybackController
import app.easepod.core.PlaybackSnapshot
import app.easepod.core.QueueCheckpoint
import app.easepod.core.QueueEntry
import app.easepod.core.RepeatMode
import app.easepod.core.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@UnstableApi
class AndroidPlaybackController internal constructor(
    private val context: Context,
    private val dependencies: PlaybackDependencies,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : PlaybackController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val registry = ConcurrentHashMap<String, Track>()
    private data class SourceMetadata(val canSeek: Boolean, val quality: String)
    private val sourceMetadata = ConcurrentHashMap<String, SourceMetadata>()
    private val disabledSources = ConcurrentHashMap.newKeySet<String>()
    private data class SourceAccount(val sourceId: String, val accountScope: String)
    private val disabledAccounts = ConcurrentHashMap.newKeySet<SourceAccount>()
    private val resolutionJobs = ConcurrentHashMap<SourceAccount, MutableSet<Job>>()
    @Volatile private var safeMode = dependencies.settings.settings.value.safeMode
    private var queue = QueueCheckpoint()
    private var restoring = false
    private var sleepDeadline: Long? = null
    private var sleepWallDeadline: Long? = null
    private var reportedHistory: String? = null
    private var automaticTransition = false
    private var automaticRecovery = false
    private var consecutiveErrors = 0
    private var playRequestRevision = 0L
    private var playRequestPending = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private val mediaCache = StreamCache(context, dependencies.settings.settings.value.cacheLimitMb)
    private val guardedResolver = object : StreamResolver {
        override suspend fun resolve(track: Track): PlayableSource = resolving(track) { dependencies.streamResolver.resolve(track) }
        override suspend fun offlinePolicy(track: Track): OfflineGrant? = resolving(track) { dependencies.streamResolver.offlinePolicy(track) }
    }
    val downloads = DownloadCoordinator(context, dependencies.settings, guardedResolver, ::allowed,
        awaitSourceInitialization = dependencies.awaitSourceInitialization)
    private val engine = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory {
            SourceDataSource(context, guardedResolver, mediaCache, registry, ::allowed, downloads::offlineSource, onSourceResolved = { entryId, source ->
                sourceMetadata[entryId] = SourceMetadata(source.canSeek, source.quality)
            })
        }).setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val failure = info.exception
                if (failure is SourceUnavailableException ||
                    failure is HttpDataSource.InvalidResponseCodeException && failure.responseCode in setOf(401, 403)) return C.TIME_UNSET
                return super.getRetryDelayMsFor(info)
            }
        }))
        .build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            volume = 1f
        }
    private val mutablePlayback = MutableStateFlow(PlaybackSnapshot(volume = systemVolume()))
    override val playback: StateFlow<PlaybackSnapshot> = mutablePlayback.asStateFlow()
    val cacheBytes: Long get() = mediaCache.cache.cacheSpace
    internal val hasPlaybackIntent get() = engine.playWhenReady && engine.playbackState != Player.STATE_ENDED

    // Platform controls share the app's queue semantics, including repeat-one manual next.
    internal val player: Player = object : ForwardingPlayer(engine) {
        override fun play() { resume() }
        override fun pause() { this@AndroidPlaybackController.pause() }
        override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) resume() else this@AndroidPlaybackController.pause() }
        override fun seekToNext() { this@AndroidPlaybackController.next() }
        override fun seekToNextMediaItem() { this@AndroidPlaybackController.next() }
        override fun seekToPrevious() { this@AndroidPlaybackController.previous() }
        override fun seekToPreviousMediaItem() { this@AndroidPlaybackController.previous() }
        override fun seekTo(positionMs: Long) { this@AndroidPlaybackController.seek(positionMs) }
        override fun setRepeatMode(repeatMode: Int) { repeat(when (repeatMode) { Player.REPEAT_MODE_ONE -> RepeatMode.ONE; Player.REPEAT_MODE_ALL -> RepeatMode.ALL; else -> RepeatMode.OFF }) }
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) { shuffle(shuffleModeEnabled) }
        override fun getShuffleModeEnabled(): Boolean = queue.shuffle
        override fun stop() { stopPlayback() }
    }

    init {
        engine.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason in setOf(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
                        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG)) {
                    ++playRequestRevision
                    playRequestPending = false
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (restoring) return
                publish()
                if (engine.isPlaying) {
                    consecutiveErrors = 0
                    automaticRecovery = false
                    val entry = engine.currentMediaItem?.mediaId
                    if (entry != reportedHistory) {
                        reportedHistory = entry
                        entry?.let(registry::get)?.let { track -> scope.launch { runCatching { dependencies.library.recordHistory(track.id) } } }
                    }
                }
                if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) persistLater()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                automaticTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) reportedHistory = null
            }
            override fun onPlayerError(error: PlaybackException) {
                val message = error.cause?.message?.takeIf { !it.contains("https://") } ?: "播放失败，请重试或选择下一首"
                if ((automaticTransition || automaticRecovery) && ++consecutiveErrors < minOf(3, queue.entries.size) && queue.repeat != RepeatMode.ONE && engine.hasNextMediaItem()) {
                    automaticRecovery = true
                    engine.seekToNextMediaItem()
                    engine.prepare()
                } else {
                    engine.pause()
                    mutablePlayback.value = mutablePlayback.value.copy(playing = false, buffering = false, error = message)
                    persistLater()
                }
            }
        })
        scope.launch {
            try {
                queue = dependencies.queueStore.loadQueue().let { restored ->
                    val unique = restored.entries.distinctBy { it.id }
                    restored.copy(entries = unique, currentEntryId = restored.currentEntryId?.takeIf { id -> unique.any { it.id == id } }, positionMs = restored.positionMs.coerceAtLeast(0L), originalOrder = restored.originalOrder.ifEmpty { unique.map { it.id } })
                }
                installQueue(queue, play = false, prepare = false)
            } catch (error: Exception) {
                mutablePlayback.value = mutablePlayback.value.copy(error = "播放队列恢复失败")
            } finally { ready.complete(Unit) }
        }
        scope.launch {
            dependencies.settings.settings.collect { settings ->
                setSafeMode(settings.safeMode)
                withContext(Dispatchers.IO) { mediaCache.setLimit(settings.cacheLimitMb) }
            }
        }
        scope.launch {
            dependencies.library.library.collect { library ->
                if (library.ready && engine.currentMediaItem?.mediaId?.let(registry::get)?.let { it.sourceId == LOCAL_SOURCE && !allowed(it) } == true) {
                    suspendCurrent("本地目录授权已失效，请重新选择文件夹")
                }
                publish()
            }
        }
        scope.launch {
            ready.await()
            var ticks = 0
            while (true) {
                if (sleepDeadline?.let { elapsedRealtime() >= it } == true) {
                    sleepDeadline = null; sleepWallDeadline = null
                    pause()
                }
                publish()
                if (++ticks % 10 == 0 && engine.playWhenReady) persistLater()
                delay(500L)
            }
        }
    }

    private fun allowed(track: Track): Boolean {
        if (!track.available || track.sourceId in disabledSources ||
            SourceAccount(track.sourceId, track.accountScope) in disabledAccounts ||
            (safeMode && track.sourceId != LOCAL_SOURCE)) return false
        if (track.sourceId == LOCAL_SOURCE) {
            val library = dependencies.library.library.value
            if (library.ready && (library.root?.permissionValid != true || library.tracks.none { it.id == track.id && it.available })) return false
        } else if (!dependencies.sourceAvailable(track)) return false
        return true
    }

    private suspend fun <T> resolving(track: Track, action: suspend () -> T): T {
        if (!allowed(track)) throw SourceUnavailableException("音乐来源已停用")
        val job = currentCoroutineContext()[Job]!!
        val jobs = resolutionJobs.computeIfAbsent(SourceAccount(track.sourceId, track.accountScope)) { ConcurrentHashMap.newKeySet() }
        jobs.add(job)
        return try {
            if (!allowed(track)) throw SourceUnavailableException("音乐来源已停用")
            action().also { if (!allowed(track)) throw SourceUnavailableException("音乐来源已停用") }
        } finally { jobs.remove(job) }
    }

    private fun command(action: suspend () -> Unit) {
        scope.launch {
            ready.await()
            commands.withLock {
                try { action() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                    mutablePlayback.value = mutablePlayback.value.copy(error = error.message?.takeIf { !it.contains("http") } ?: "操作未完成，请重试")
                }
            }
        }
    }

    private fun checkpoint(): QueueCheckpoint = queue.copy(
        currentEntryId = queue.currentEntryId?.let { engine.currentMediaItem?.mediaId ?: it },
        positionMs = engine.currentPosition.coerceAtLeast(0L),
    )

    private fun persistLater() = command { dependencies.queueStore.saveQueue(checkpoint()) }

    private suspend fun replace(updated: QueueCheckpoint, play: Boolean, prepare: Boolean = true) {
        val revision = playRequestRevision
        dependencies.queueStore.saveQueue(updated)
        queue = updated
        installQueue(updated, play && revision == playRequestRevision, prepare)
    }

    private fun installQueue(value: QueueCheckpoint, play: Boolean, prepare: Boolean) {
        restoring = true
        try {
            value.entries.forEach { registry[it.id] = it.track }
            registry.keys.retainAll(value.entries.map { it.id }.toSet())
            sourceMetadata.keys.retainAll(value.entries.map { it.id }.toSet())
            val items = value.entries.map { entry ->
                MediaItem.Builder().setMediaId(entry.id).setUri("easepod://queue/${entry.id}")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.track.title).setArtist(entry.track.artist)
                        .setAlbumTitle(entry.track.albumTitle).setArtworkUri(entry.track.artworkUri?.let(Uri::parse)).build())
                    .build()
            }
            val selected = value.entries.indexOfFirst { it.id == value.currentEntryId }
            val preserveCurrent = value.currentEntryId != null && engine.currentMediaItem?.mediaId == value.currentEntryId
            if (preserveCurrent) {
                val ids = items.map { it.mediaId }.toSet()
                for (index in engine.mediaItemCount - 1 downTo 0) {
                    if (engine.getMediaItemAt(index).mediaId !in ids) engine.removeMediaItem(index)
                }
                items.forEachIndexed { index, item ->
                    val existing = (index until engine.mediaItemCount).firstOrNull { engine.getMediaItemAt(it).mediaId == item.mediaId }
                    if (existing == null) engine.addMediaItem(index, item)
                    else if (existing != index) engine.moveMediaItem(existing, index)
                }
            } else engine.setMediaItems(items, selected.coerceAtLeast(0), value.positionMs)
            engine.repeatMode = mediaRepeat(value.repeat)
            engine.shuffleModeEnabled = false
            if (prepare && items.isNotEmpty()) engine.prepare()
            mutablePlayback.value = mutablePlayback.value.copy(error = null)
            engine.playWhenReady = play && selected >= 0 && ensureService()
        } finally { restoring = false; publish() }
    }

    private fun ensureService(): Boolean {
        return try { context.startService(Intent(context, PlaybackService::class.java)); true }
        catch (error: IllegalStateException) { engine.pause(); mutablePlayback.value = mutablePlayback.value.copy(error = "请打开 EasePod 后继续播放"); false }
    }

    private fun publish() {
        val current = queue.currentEntryId?.let { engine.currentMediaItem?.mediaId ?: it }
        val duration = engine.duration.takeIf { it != C.TIME_UNSET && it >= 0L } ?: current?.let(registry::get)?.durationMs ?: 0L
        mutablePlayback.value = mutablePlayback.value.copy(
            queue = queue.entries.map { it.copy(track = it.track.copy(available = allowed(it.track))) },
            currentEntryId = current,
            playing = engine.isPlaying,
            buffering = engine.playbackState == Player.STATE_BUFFERING,
            positionMs = engine.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            canSeek = canSeekCurrent(),
            actualQuality = current?.let(sourceMetadata::get)?.quality ?: if (current?.let(registry::get)?.sourceId == LOCAL_SOURCE) "原始" else "未知",
            repeat = queue.repeat,
            shuffle = queue.shuffle,
            volume = systemVolume(),
            sleepEndsAt = sleepWallDeadline,
        )
    }

    private fun canSeekCurrent(): Boolean {
        val entry = engine.currentMediaItem?.mediaId ?: return false
        val sourceCanSeek = sourceMetadata[entry]?.canSeek ?: (registry[entry]?.sourceId == LOCAL_SOURCE)
        return engine.isCurrentMediaItemSeekable && sourceCanSeek
    }

    private fun requestPlayback(action: suspend () -> Unit) {
        val revision = ++playRequestRevision
        playRequestPending = true
        command {
            if (revision != playRequestRevision) return@command
            try { action() } finally { if (revision == playRequestRevision) playRequestPending = false }
        }
    }

    override fun play(tracks: List<Track>, startIndex: Int) = requestPlayback {
        val target = tracks.getOrNull(startIndex) ?: return@requestPlayback
        if (!allowed(target)) throw SourceUnavailableException("所选歌曲暂不可播放")
        val entries = tracks.map { QueueEntry(UUID.randomUUID().toString(), it) }
        consecutiveErrors = 0; automaticTransition = false; automaticRecovery = false
        replace(QueueCheckpoint(entries, entries[startIndex].id, repeat = queue.repeat, originalOrder = entries.map { it.id }), play = true)
    }

    private fun resume() = requestPlayback {
        if (queue.entries.isEmpty()) throw SourceUnavailableException("暂无待播放歌曲")
        val entry = queue.entries.getOrNull(engine.currentMediaItemIndex) ?: queue.entries.first()
        if (!allowed(entry.track)) throw SourceUnavailableException("来源已停用或文件不可用")
        automaticTransition = false; automaticRecovery = false; consecutiveErrors = 0
        mutablePlayback.value = mutablePlayback.value.copy(error = null)
        if (!ensureService()) return@requestPlayback
        queue = queue.copy(currentEntryId = entry.id)
        if (engine.playbackState == Player.STATE_ENDED) engine.seekTo(0L)
        if (engine.playbackState == Player.STATE_IDLE) engine.prepare()
        engine.play()
    }

    override fun toggle() { if (engine.playWhenReady || playRequestPending) pause() else resume() }
    override fun pause() { ++playRequestRevision; playRequestPending = false; engine.pause(); persistLater() }

    private fun stopPlayback() {
        ++playRequestRevision; playRequestPending = false
        sleepDeadline = null; sleepWallDeadline = null
        resolutionJobs.values.flatMap { it.toList() }.forEach { it.cancel() }
        val position = engine.currentPosition
        engine.pause(); engine.stop(); engine.seekTo(position)
        persistLater()
        publish()
    }

    override fun next() = command {
        if (queue.entries.isEmpty()) throw SourceUnavailableException("暂无待播放歌曲")
        val index = engine.currentMediaItemIndex
        val next = if (index < queue.entries.lastIndex) index + 1 else if (queue.repeat == RepeatMode.ALL) 0 else -1
        if (next < 0) { engine.pause(); engine.seekTo(engine.duration.takeIf { it > 0 } ?: engine.currentPosition) }
        else select(next, engine.playWhenReady)
        dependencies.queueStore.saveQueue(checkpoint())
    }

    override fun previous() = command {
        if (queue.entries.isEmpty()) throw SourceUnavailableException("暂无待播放歌曲")
        if (engine.currentPosition >= 3_000L) engine.seekTo(0L)
        else select((engine.currentMediaItemIndex - 1).coerceAtLeast(0), engine.playWhenReady)
        dependencies.queueStore.saveQueue(checkpoint())
    }

    private fun select(index: Int, play: Boolean) {
        val entry = queue.entries.getOrNull(index) ?: return
        automaticTransition = false
        automaticRecovery = false
        consecutiveErrors = 0
        queue = queue.copy(currentEntryId = entry.id)
        engine.seekTo(index, 0L)
        if (!allowed(entry.track)) { suspendCurrent("所选歌曲暂不可播放"); return }
        engine.prepare()
        mutablePlayback.value = mutablePlayback.value.copy(error = null)
        engine.playWhenReady = play && ensureService()
    }

    override fun seek(positionMs: Long) = command {
        if (!canSeekCurrent()) throw SourceUnavailableException("当前音频不支持调整进度")
        engine.seekTo(positionMs.coerceIn(0L, engine.duration.takeIf { it > 0L } ?: Long.MAX_VALUE))
        dependencies.queueStore.saveQueue(checkpoint())
    }

    override fun volume(value: Float) {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value.coerceIn(0f, 1f) * maximum).roundToInt(), 0)
        publish()
    }

    fun adjustVolumeSteps(steps: Int) {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (current.toLong() + steps).coerceIn(0L, maximum.toLong()).toInt(), 0)
        publish()
    }

    private fun systemVolume(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    override fun repeat(mode: RepeatMode) = command {
        val updated = checkpoint().copy(repeat = mode)
        dependencies.queueStore.saveQueue(updated); queue = updated; engine.repeatMode = mediaRepeat(mode); publish()
    }

    override fun shuffle(enabled: Boolean) = command {
        if (queue.shuffle == enabled) return@command
        val current = checkpoint()
        val ordered = if (enabled) QueueOrder.shuffled(current.entries, current.currentEntryId) else QueueOrder.original(current.entries, current.originalOrder)
        replace(current.copy(entries = ordered, shuffle = enabled), engine.playWhenReady, engine.playbackState != Player.STATE_IDLE)
    }

    override fun enqueue(track: Track, next: Boolean) = command {
        val current = checkpoint()
        val entry = QueueEntry(UUID.randomUUID().toString(), track)
        val index = if (next) (current.entries.indexOfFirst { it.id == current.currentEntryId } + 1).coerceAtLeast(0) else current.entries.size
        val entries = current.entries.toMutableList().apply { add(index, entry) }
        val original = current.originalOrder.toMutableList().apply {
            val position = if (next) (indexOf(current.currentEntryId) + 1).coerceAtLeast(0) else size
            add(position, entry.id)
        }
        replace(current.copy(entries = entries, originalOrder = original), engine.playWhenReady, engine.playbackState != Player.STATE_IDLE)
    }

    override fun remove(entryId: String) = command {
        val current = checkpoint()
        val index = current.entries.indexOfFirst { it.id == entryId }
        if (index < 0) return@command
        val entries = current.entries.filterNot { it.id == entryId }
        val removingCurrent = entryId == current.currentEntryId
        val successor = if (removingCurrent) entries.getOrNull(index)?.id else current.currentEntryId
        replace(current.copy(entries = entries, currentEntryId = successor,
            positionMs = if (removingCurrent) 0L else current.positionMs, originalOrder = current.originalOrder - entryId),
            engine.playWhenReady && successor != null, prepare = successor != null)
    }

    override fun move(entryId: String, delta: Int) = command {
        val current = checkpoint()
        val entries = QueueOrder.moved(current.entries, entryId, delta)
        replace(current.copy(entries = entries, originalOrder = if (current.shuffle) current.originalOrder else entries.map { it.id }), engine.playWhenReady, engine.playbackState != Player.STATE_IDLE)
    }

    override fun jump(entryId: String) = requestPlayback {
        val index = queue.entries.indexOfFirst { it.id == entryId }
        if (index >= 0) { select(index, play = true); dependencies.queueStore.saveQueue(checkpoint()) }
    }

    override fun clear() = command {
        sleepDeadline = null; sleepWallDeadline = null
        replace(QueueCheckpoint(repeat = queue.repeat), play = false, prepare = false)
        engine.stop()
    }

    override fun sleep(minutes: Int) {
        require(minutes in setOf(0, 15, 30, 60)) { "睡眠定时支持关闭、15、30、60 分钟" }
        val duration = minutes * 60_000L
        sleepDeadline = if (minutes == 0) null else elapsedRealtime() + duration
        sleepWallDeadline = if (minutes == 0) null else System.currentTimeMillis() + duration
        publish()
    }

    override fun disableSource(sourceId: String) {
        disabledSources.add(sourceId)
        resolutionJobs.filterKeys { it.sourceId == sourceId }.values.flatMap { it.toList() }.forEach { it.cancel() }
        downloads.pauseSource(sourceId)
        if (engine.currentMediaItem?.mediaId?.let(registry::get)?.sourceId == sourceId) suspendCurrent("音乐来源已停用")
        publish()
    }

    fun enableSource(sourceId: String) { disabledSources.remove(sourceId); publish() }

    fun disableAccount(sourceId: String, accountScope: String) {
        val account = SourceAccount(sourceId, accountScope)
        disabledAccounts.add(account)
        resolutionJobs[account]?.toList()?.forEach { it.cancel() }
        downloads.pauseAccount(sourceId, accountScope)
        val current = engine.currentMediaItem?.mediaId?.let(registry::get)
        if (current?.sourceId == sourceId && current.accountScope == accountScope) {
            suspendCurrent("账号已退出，请重新登录")
        }
        val keys = registry.values.filter { it.sourceId == sourceId && it.accountScope == accountScope }.map(::stableMediaKey)
        scope.launch(Dispatchers.IO) { runCatching {
            mediaCache.removeAccount(sourceId, accountScope, keys)
        } }
        publish()
    }

    fun enableAccount(sourceId: String, accountScope: String) {
        disabledAccounts.remove(SourceAccount(sourceId, accountScope))
        publish()
    }

    override fun setSafeMode(enabled: Boolean) {
        safeMode = enabled
        if (enabled) {
            resolutionJobs.filterKeys { it.sourceId != LOCAL_SOURCE }.values.flatMap { it.toList() }.forEach { it.cancel() }
            downloads.pauseExternalSources()
            if (engine.currentMediaItem?.mediaId?.let(registry::get)?.sourceId?.let { it != LOCAL_SOURCE } == true) suspendCurrent("安全模式仅支持本地音乐")
        }
        publish()
    }

    private fun suspendCurrent(message: String) {
        ++playRequestRevision; playRequestPending = false
        val position = engine.currentPosition
        engine.pause()
        engine.stop()
        engine.seekTo(position)
        mutablePlayback.value = mutablePlayback.value.copy(playing = false, buffering = false, error = message)
        persistLater()
    }

    suspend fun clearStreamCache(): Long = withContext(Dispatchers.IO) {
        // Open file descriptors remain readable; complete offline files live in a separate directory.
        mediaCache.clear()
        cacheBytes
    }

    internal fun onServiceDestroyed() {
        stopPlayback()
    }

    internal fun release() {
        scope.cancel()
        engine.release()
        downloads.release()
        mediaCache.release()
    }

    private fun mediaRepeat(mode: RepeatMode) = when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }
}
