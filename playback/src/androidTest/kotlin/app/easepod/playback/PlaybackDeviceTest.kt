package app.easepod.playback

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.easepod.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var track: Track
    private lateinit var controller: AndroidPlaybackController
    private lateinit var store: MemoryQueue
    private lateinit var historyLibrary: MemoryLibrary
    private val time = AtomicLong(10_000L)
    private var runtimeInstalled = false

    @Before fun createFixture() {
        val file = File(context.cacheDir, "playback-fixtures/silence.wav").apply { parentFile!!.mkdirs() }
        val length = 8_000 * 2 * 30
        val wav = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(length)
        file.writeBytes(wav.array())
        track = Track("silent-fixture", "Silence", durationMs = 30_000L,
            contentUri = FileProvider.getUriForFile(context, "app.easepod.playback.test.fixtures", file).toString())
        store = MemoryQueue()
    }

    private fun start(checkpoint: QueueCheckpoint = QueueCheckpoint(), resolver: StreamResolver = StreamResolver { error("Unexpected cloud request") },
        sourceAvailable: ((Track) -> Boolean)? = null) {
        store.value = checkpoint
        val library = MemoryLibrary(track)
        historyLibrary = library
        val isolated = object : ContextWrapper(context) {
            override fun startService(service: Intent): ComponentName = ComponentName(context, PlaybackService::class.java)
        }
        val dependencies = PlaybackDependencies(library, store, MemorySettings(), resolver)
        main { controller = AndroidPlaybackController(isolated, sourceAvailable?.let { dependencies.copy(sourceAvailable = it) } ?: dependencies, time::get) }
        await { controller.playback.value.queue.size == checkpoint.entries.size }
    }

    @After fun close() {
        if (runtimeInstalled) {
            context.stopService(Intent(context, PlaybackService::class.java))
            await { playbackService() == null }
            main { PlaybackRuntime.shutdown() }
        } else if (::controller.isInitialized) main { controller.release() }
    }

    @Test fun wavDecodesAndQueueEditsPreserveCurrentOccurrenceAndPosition() {
        start()
        main { controller.play(listOf(track, track, track)) }
        await { controller.playback.value.playing && controller.playback.value.positionMs >= 250L }
        val before = controller.playback.value
        assertEquals(3, before.queue.map { it.id }.toSet().size)
        assertEquals(30_000L, before.durationMs)
        main { controller.enqueue(track, next = true) }
        await { controller.playback.value.queue.size == 4 }
        main { controller.shuffle(true) }
        await { controller.playback.value.shuffle }
        val shuffled = controller.playback.value
        assertEquals(before.currentEntryId, shuffled.currentEntryId)
        assertTrue(shuffled.positionMs >= before.positionMs)
        main { controller.repeat(RepeatMode.ONE); controller.player.seekToNextMediaItem() }
        await { controller.playback.value.currentEntryId != before.currentEntryId && controller.playback.value.playing }
        main { controller.pause() }
        await { !controller.playback.value.playing }
        val last = controller.playback.value.queue.last().id
        main { controller.jump(last) }
        await { controller.playback.value.currentEntryId == last && controller.playback.value.playing }
        main { controller.remove(last) }
        await { controller.playback.value.queue.size == 3 && controller.playback.value.currentEntryId == null }
        assertFalse(controller.playback.value.playing)
    }

    @Test fun checkpointRestoresPositionWithoutStartingAudio() {
        val entry = QueueEntry("restored-entry", track)
        start(QueueCheckpoint(listOf(entry), entry.id, 4_000L, RepeatMode.ALL, true, listOf(entry.id)))
        await { controller.playback.value.currentEntryId == entry.id }
        assertFalse(controller.playback.value.playing)
        assertFalse(controller.playback.value.buffering)
        assertEquals(4_000L, controller.playback.value.positionMs)
        assertTrue(controller.playback.value.shuffle)
        main { controller.toggle() }
        await { controller.playback.value.playing && controller.playback.value.positionMs >= 4_000L }
    }

    @Test fun naturalSingleTrackRepeatRecordsAnotherEventButSeekAndPauseDoNot() {
        start()
        main { controller.play(listOf(track)) }
        await { controller.playback.value.playing && historyLibrary.history.size == 1 }
        main { controller.repeat(RepeatMode.ONE) }
        await { controller.playback.value.repeat == RepeatMode.ONE }
        main { controller.seek(2000); controller.pause() }
        await { !controller.playback.value.playing }
        main { controller.toggle() }
        await { controller.playback.value.playing }
        assertEquals(listOf(track.id), historyLibrary.history.toList())
        main { controller.seek(29_900) }
        await { historyLibrary.history.size == 2 }
        assertEquals(listOf(track.id, track.id), historyLibrary.history.toList())
        main { controller.pause() }
    }

    @Test fun pauseDuringQueueSaveCancelsPendingPlaybackIntent() {
        start()
        store.gate = CompletableDeferred()
        main { controller.play(listOf(track)) }
        await { store.waiting }
        main { controller.pause() }
        store.gate!!.complete(Unit)
        await { controller.playback.value.queue.size == 1 }
        Thread.sleep(700L)
        assertFalse(controller.playback.value.playing)
        assertEquals(0L, controller.playback.value.positionMs)
    }

    @Test fun sleepUsesMonotonicDeadlineAndFiresOnce() {
        start()
        main { controller.play(listOf(track)) }
        await { controller.playback.value.playing }
        main { controller.sleep(15) }
        assertNotNull(controller.playback.value.sleepEndsAt)
        time.addAndGet(15 * 60_000L)
        await { controller.playback.value.sleepEndsAt == null && !controller.playback.value.playing }
        main { controller.toggle() }
        await { controller.playback.value.playing }
        Thread.sleep(700L)
        assertTrue(controller.playback.value.playing)
    }

    @Test fun disablingSourceCancelsPendingResolutionAndRetainsQueue() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        start(resolver = StreamResolver {
            started.complete(Unit)
            try { CompletableDeferred<PlayableSource>().await() } finally { cancelled.complete(Unit) }
        }, sourceAvailable = { true })
        val remote = track.copy(id = "cloud", contentUri = null, sourceId = "test.cloud", accountScope = "test")
        main { controller.play(listOf(remote)) }
        await { started.isCompleted }
        main { controller.disableSource("test.cloud") }
        await { cancelled.isCompleted && !controller.playback.value.playing }
        assertEquals(1, controller.playback.value.queue.size)
        assertFalse(controller.playback.value.current!!.available)
    }

    @Test fun platformStopCancelsSleepAndRetainsPlaybackPosition() {
        start()
        main { controller.play(listOf(track)) }
        await { controller.playback.value.playing && controller.playback.value.positionMs >= 250L }
        val entry = controller.playback.value.currentEntryId
        main { controller.sleep(30); controller.player.stop() }
        await { !controller.playback.value.playing && !controller.playback.value.buffering }
        assertNull(controller.playback.value.sleepEndsAt)
        assertEquals(entry, controller.playback.value.currentEntryId)
        assertTrue(controller.playback.value.positionMs >= 250L)
    }

    @Test fun unverifiedRestoredCloudSourceCannotResolveOrReuseOfflineWithoutUi() {
        val calls = AtomicInteger()
        val remote = track.copy(id = "unverified-cloud", contentUri = null, sourceId = "test.cloud", accountScope = "private")
        val entry = QueueEntry("unverified-entry", remote)
        start(QueueCheckpoint(listOf(entry), entry.id), object : StreamResolver {
            override suspend fun resolve(track: Track): PlayableSource { calls.incrementAndGet(); error("Unverified source must not resolve") }
            override suspend fun offlinePolicy(track: Track): OfflineGrant? { calls.incrementAndGet(); return OfflineGrant() }
        })
        assertFalse(controller.playback.value.current!!.available)
        kotlinx.coroutines.runBlocking { assertNull(controller.downloads.offlineSource(remote)) }
        main { controller.enableSource(remote.sourceId); controller.toggle() }
        await { controller.playback.value.error != null }
        assertFalse(controller.playback.value.current!!.available)
        assertFalse(controller.playback.value.playing)
        assertEquals(0, calls.get())
    }

    @Test fun authorizationCallbackRejectsLateResolutionWhenAccountChangesWithoutUi() {
        val verified = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<PlayableSource>()
        val remote = track.copy(id = "private-cloud", contentUri = null, sourceId = "test.cloud", accountScope = "private")
        val entry = QueueEntry("private-entry", remote)
        start(QueueCheckpoint(listOf(entry), entry.id), StreamResolver { started.complete(Unit); result.await() },
            sourceAvailable = { verified.get() })
        assertFalse(controller.playback.value.current!!.available)
        verified.set(true)
        await { controller.playback.value.current!!.available }
        main { controller.toggle() }
        await { started.isCompleted }
        verified.set(false)
        result.complete(PlayableSource("https://media.example.com/never-open.wav"))
        await { !controller.playback.value.buffering && controller.playback.value.error != null }
        assertFalse(controller.playback.value.current!!.available)
        assertFalse(controller.playback.value.playing)
        assertEquals(entry.id, controller.playback.value.currentEntryId)
    }

    @Test fun accountSignOutCancelsOnlyItsResolutionAndQueueAvailability() {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        start(resolver = StreamResolver {
            started.complete(Unit)
            try { CompletableDeferred<PlayableSource>().await() } finally { cancelled.complete(Unit) }
        }, sourceAvailable = { true })
        val first = track.copy(id = "account-a-song", sourceId = "test.cloud", accountScope = "account-a", contentUri = null)
        val second = first.copy(id = "account-b-song", accountScope = "account-b")
        main { controller.play(listOf(first, second)) }
        await { started.isCompleted }
        main { controller.disableAccount("test.cloud", "account-a") }
        await { cancelled.isCompleted }
        assertFalse(controller.playback.value.queue[0].track.available)
        assertTrue(controller.playback.value.queue[1].track.available)
        assertFalse(controller.playback.value.playing)
        main { controller.enableAccount("test.cloud", "account-a") }
        assertTrue(controller.playback.value.queue[0].track.available)
        assertFalse(controller.playback.value.playing)
    }

    @Test fun directPlaybackRegistersMediaSessionAndContinuesInForegroundService() {
        ActivityScenario.launch(PlaybackFixtureActivity::class.java).use { activity ->
            activity.onActivity {
                controller = PlaybackRuntime.install(it, PlaybackDependencies(MemoryLibrary(track), store, MemorySettings()))
                runtimeInstalled = true
                controller.play(listOf(track))
            }
            await { controller.playback.value.playing && playbackService()?.foreground == true }
            assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty())
            val before = controller.playback.value.positionMs
            activity.moveToState(Lifecycle.State.CREATED)
            await { controller.playback.value.positionMs > before + 700L }
            assertTrue(playbackService()!!.foreground)
            main { controller.player.stop() }
            await { !controller.playback.value.playing }
        }
    }

    @Suppress("DEPRECATION")
    private fun playbackService(): ActivityManager.RunningServiceInfo? =
        context.getSystemService(ActivityManager::class.java).getRunningServices(30)
            .find { it.service.className == PlaybackService::class.java.name }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadline) fail("Timed out: ${if (::controller.isInitialized) controller.playback.value else "not created"}")
            Thread.sleep(25L)
        }
    }

    private class MemoryQueue : QueueStore {
        @Volatile var value = QueueCheckpoint()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var waiting = false
        override suspend fun loadQueue() = value
        override suspend fun saveQueue(checkpoint: QueueCheckpoint) {
            if (checkpoint.entries.isNotEmpty()) { waiting = true; gate?.await() }
            value = checkpoint
        }
    }

    private class MemorySettings : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
    }

    private class MemoryLibrary(track: Track) : LibraryRepository {
        val history = CopyOnWriteArrayList<String>()
        override val library = MutableStateFlow(LibrarySnapshot(tracks = listOf(track), root = LibraryRoot("content://test", "Fixture"), ready = true))
        override val scan = MutableStateFlow(ScanProgress())
        override suspend fun recordHistory(trackId: String) { history += trackId }
        override suspend fun removeHistory(id: String) = Unit
        override suspend fun clearHistory() = Unit
        override suspend fun scanFolder(treeUri: String) = Unit
        override suspend fun publishScan() = Unit
        override fun cancelScan() = Unit
        override suspend fun removeRoot() = Unit
        override suspend fun checkPermission() = Unit
        override suspend fun createPlaylist(title: String): String = error("unused")
        override suspend fun renamePlaylist(id: String, title: String) = Unit
        override suspend fun deletePlaylist(id: String) = Unit
        override suspend fun addToPlaylist(id: String, trackId: String) = Unit
        override suspend fun removeFromPlaylist(id: String, entryId: String) = Unit
        override suspend fun movePlaylistEntry(id: String, entryId: String, delta: Int) = Unit
        override suspend fun exportBackup(passphrase: CharArray): ByteArray = error("unused")
        override suspend fun importBackup(bytes: ByteArray, passphrase: CharArray) = Unit
    }
}

class PlaybackFixtureActivity : Activity()
