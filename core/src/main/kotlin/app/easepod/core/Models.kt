package app.easepod.core

import kotlinx.coroutines.flow.StateFlow

const val LOCAL_SOURCE = "core.local"

data class Track(
    val id: String,
    val title: String,
    val artist: String = "未知艺人",
    val albumId: String? = null,
    val albumTitle: String? = null,
    val genre: String = "未分类",
    val durationMs: Long? = null,
    val artworkUri: String? = null,
    val contentUri: String? = null,
    val available: Boolean = true,
    val sourceId: String = LOCAL_SOURCE,
    val accountScope: String = "local",
    val remoteId: String = id,
    val discNumber: Int = 0,
    val trackNumber: Int = 0,
)

data class Album(val id: String, val title: String, val artist: String, val artworkUri: String?, val trackIds: List<String>)
data class PlaylistEntry(val id: String, val trackId: String)
data class Playlist(val id: String, val title: String, val entries: List<PlaylistEntry> = emptyList())
data class HistoryEntry(val id: String, val track: Track, val playedAt: Long)
data class LibraryRoot(val uri: String, val name: String, val permissionValid: Boolean = true)
data class LibrarySnapshot(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val root: LibraryRoot? = null,
    val ready: Boolean = false,
)

enum class ScanPhase { IDLE, SCANNING, REVIEW, COMPLETE, CANCELLED, FAILED }
data class ScanProgress(val phase: ScanPhase = ScanPhase.IDLE, val folder: String = "", val tracks: Int = 0, val albums: Int = 0, val skipped: Int = 0, val issues: List<String> = emptyList(), val message: String? = null)

data class AppSettings(
    val themeId: String = "silver",
    val lockScreenOverlay: Boolean = false,
    val touchGuard: Boolean = false,
    val haptics: Boolean = true,
    val clickSound: Boolean = true,
    val wheelSensitivity: Int = 2,
    val largeText: Boolean = false,
    val reducedMotion: Boolean = false,
    val safeMode: Boolean = false,
    val cacheLimitMb: Int = 256,
    val wifiDownloadsOnly: Boolean = true,
    val networkQuality: String = "standard",
    val fullScreen: Boolean = false,
)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

interface LibraryRepository {
    val library: StateFlow<LibrarySnapshot>
    val scan: StateFlow<ScanProgress>
    suspend fun scanFolder(treeUri: String)
    suspend fun publishScan()
    fun cancelScan()
    suspend fun removeRoot()
    suspend fun checkPermission()
    suspend fun createPlaylist(title: String): String
    suspend fun renamePlaylist(id: String, title: String)
    suspend fun deletePlaylist(id: String)
    suspend fun addToPlaylist(id: String, trackId: String)
    suspend fun removeFromPlaylist(id: String, entryId: String)
    suspend fun movePlaylistEntry(id: String, entryId: String, delta: Int)
    suspend fun recordHistory(trackId: String)
    suspend fun removeHistory(id: String)
    suspend fun clearHistory()
    suspend fun exportBackup(passphrase: CharArray): ByteArray
    suspend fun importBackup(bytes: ByteArray, passphrase: CharArray)
}

enum class RepeatMode { OFF, ALL, ONE }
data class QueueEntry(val id: String, val track: Track)
data class QueueCheckpoint(val entries: List<QueueEntry> = emptyList(), val currentEntryId: String? = null, val positionMs: Long = 0, val repeat: RepeatMode = RepeatMode.OFF, val shuffle: Boolean = false, val originalOrder: List<String> = emptyList())
interface QueueStore {
    suspend fun loadQueue(): QueueCheckpoint
    suspend fun saveQueue(checkpoint: QueueCheckpoint)
}

data class PlaybackSnapshot(
    val queue: List<QueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    val volume: Float = 0.8f,
    val sleepEndsAt: Long? = null,
    val error: String? = null,
    val canSeek: Boolean = false,
    val actualQuality: String = "原始",
) { val current: Track? get() = queue.find { it.id == currentEntryId }?.track }

interface PlaybackController {
    val playback: StateFlow<PlaybackSnapshot>
    fun play(tracks: List<Track>, startIndex: Int = 0)
    fun toggle()
    fun pause()
    fun next()
    fun previous()
    fun seek(positionMs: Long)
    fun volume(value: Float)
    fun repeat(mode: RepeatMode)
    fun shuffle(enabled: Boolean)
    fun enqueue(track: Track, next: Boolean = false)
    fun remove(entryId: String)
    fun move(entryId: String, delta: Int)
    fun jump(entryId: String)
    fun clear()
    fun sleep(minutes: Int)
    fun disableSource(sourceId: String)
    fun setSafeMode(enabled: Boolean)
}
