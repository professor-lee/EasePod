package app.easepod.playback

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.easepod.core.AppSettings
import app.easepod.core.SettingsRepository
import app.easepod.core.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@UnstableApi
@RunWith(AndroidJUnit4::class)
class DownloadCoordinatorDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var isolated: Context
    private lateinit var work: WorkManager
    private lateinit var coordinator: DownloadCoordinator
    private val settings = MemorySettings()
    private val audio = ByteArray(256 * 1024) { (it % 251).toByte() }
    private val positions = mutableListOf<Long>()
    private val prefix = "download-fixture-${UUID.randomUUID()}"
    private val track = Track(prefix, "Download fixture", sourceId = "fixture.source", accountScope = "fixture.account")
    private var policy = OfflineGrant(expectedBytes = audio.size.toLong(), sha256 = digest(audio))
    @Volatile private var allowed = true
    private var resolves = 0
    private var onPolicy: suspend () -> Unit = {}
    private var onResolve: suspend () -> Unit = {}
    private var onRead: () -> Unit = {}
    private var onInitialize: suspend () -> Unit = {}
    private var sourceBytes = audio

    @Before fun createCoordinator() {
        // The test driver opens each request's constraints explicitly, without foreground notifications.
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor())
                .setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                        if (workerClassName != AudioDownloadWorker::class.java.name) return null
                        return object : CoroutineWorker(appContext, workerParameters) {
                            override suspend fun doWork(): Result {
                                coordinator.execute(inputData.getString("downloadId")!!, id.toString()) {}
                                return Result.success()
                            }
                        }
                    }
                }).build())
        work = WorkManager.getInstance(context)
        isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(context.cacheDir, prefix).apply { mkdirs() }
            override fun getDatabasePath(name: String): File = context.getDatabasePath("$prefix-$name")
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                context.openOrCreateDatabase("$prefix-$name", mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                context.openOrCreateDatabase("$prefix-$name", mode, factory, errorHandler)
        }
        coordinator = DownloadCoordinator(isolated, settings, object : StreamResolver {
            override suspend fun offlinePolicy(track: Track): OfflineGrant {
                onPolicy()
                return policy
            }
            override suspend fun resolve(track: Track): PlayableSource {
                resolves++
                onResolve()
                return PlayableSource("https://media.example.com/fixture", offlineGrant = policy, canSeek = false, quality = "无损")
            }
        }, { allowed }, { BytesSource(sourceBytes, positions) { onRead() } }, work,
            awaitSourceInitialization = { onInitialize() })
    }

    @After fun cleanFixture() {
        if (::coordinator.isInitialized) coordinator.release()
        if (::work.isInitialized) work.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        if (::isolated.isInitialized) {
            isolated.filesDir.deleteRecursively()
            context.deleteDatabase("$prefix-offline-downloads.db")
        }
    }

    @Test fun completedDownloadPersistsDigestAndSourceMetadata() = runBlocking {
        val id = coordinator.enqueue(track)
        execute(id)
        val complete = item(id)
        assertEquals(DownloadPhase.COMPLETE, complete.phase)
        assertEquals(audio.size.toLong(), complete.downloadedBytes)
        assertEquals(digest(audio), complete.checksum)
        val offline = coordinator.offlineSource(track)!!
        assertFalse(offline.canSeek)
        assertEquals("无损", offline.quality)
        assertArrayEquals(audio, File(Uri.parse(offline.uri).path!!).readBytes())
    }

    @Test fun checksumFailureRemovesBadPartialAndRetryStartsAtZero() = runBlocking {
        sourceBytes = audio.copyOf().also { it[0] = 99 }
        val id = coordinator.enqueue(track)
        execute(id)
        assertEquals(DownloadPhase.FAILED, item(id).phase)
        assertEquals(0L, item(id).downloadedBytes)
        assertFalse(partial(id).exists())
        sourceBytes = audio
        coordinator.resume(id)
        execute(id)
        assertEquals(listOf(0L, 0L), positions)
        assertEquals(DownloadPhase.COMPLETE, item(id).phase)
    }

    @Test fun pauseRetainsVerifiedPrefixAndResumeUsesNewRequest() = runBlocking {
        val id = coordinator.enqueue(track)
        val oldRequest = item(id).requestId!!
        var paused = false
        onRead = {
            if (!paused) {
                paused = true
                coordinator.pause(id)
                await { item(id).phase == DownloadPhase.PAUSED }
            }
        }
        execute(id)
        await { partial(id).length() > 0L }
        val retainedBytes = partial(id).length()
        assertTrue(retainedBytes in 1 until audio.size.toLong())
        onRead = {}
        coordinator.resume(id)
        assertNotEquals(oldRequest, item(id).requestId)
        execute(id)
        assertEquals(listOf(0L, retainedBytes), positions)
        assertEquals(DownloadPhase.COMPLETE, item(id).phase)
    }

    @Test fun changedWifiConstraintReplacesRequestAndOldWorkerCannotResolve() = runBlocking {
        val id = coordinator.enqueue(track)
        val first = item(id).requestId!!
        assertEquals(NetworkType.UNMETERED, work.getWorkInfoById(UUID.fromString(first)).get()!!.constraints.requiredNetworkType)
        settings.update { it.copy(wifiDownloadsOnly = false) }
        await { item(id).requestId != first }
        val replacement = item(id).requestId!!
        assertEquals(NetworkType.CONNECTED, work.getWorkInfoById(UUID.fromString(replacement)).get()!!.constraints.requiredNetworkType)
        coordinator.execute(id, first) { fail("Stale request reported progress") }
        assertEquals(0, resolves)
        assertEquals(replacement, item(id).requestId)
        execute(id)
        assertEquals(DownloadPhase.COMPLETE, item(id).phase)
    }

    @Test fun missingProviderDigestRestartsPartialInsteadOfCombiningUnverifiedBytes() = runBlocking {
        policy = policy.copy(sha256 = null)
        val id = coordinator.enqueue(track)
        var paused = false
        onRead = {
            if (!paused) {
                paused = true
                coordinator.pause(id)
                await { item(id).phase == DownloadPhase.PAUSED }
            }
        }
        execute(id)
        await { partial(id).length() > 0L }
        sourceBytes = ByteArray(audio.size) { 17 }
        onRead = {}
        coordinator.resume(id)
        execute(id)
        assertEquals(listOf(0L, 0L), positions)
        assertArrayEquals(sourceBytes, File(Uri.parse(coordinator.offlineSource(track)!!.uri).path!!).readBytes())
    }

    @Test fun changedProviderDigestDiscardsRetainedEncodingBeforeResume() = runBlocking {
        val id = coordinator.enqueue(track)
        var paused = false
        onRead = {
            if (!paused) {
                paused = true
                coordinator.pause(id)
                await { item(id).phase == DownloadPhase.PAUSED }
            }
        }
        execute(id)
        await { partial(id).length() > 0L }
        sourceBytes = ByteArray(audio.size) { 23 }
        policy = policy.copy(sha256 = digest(sourceBytes))
        onRead = {}
        coordinator.resume(id)
        execute(id)
        assertEquals(listOf(0L, 0L), positions)
        assertEquals(digest(sourceBytes), item(id).checksum)
        assertArrayEquals(sourceBytes, File(Uri.parse(coordinator.offlineSource(track)!!.uri).path!!).readBytes())
    }

    @Test fun releaseStopsObserversAndLateResolutionCannotReopenDatabase() = runBlocking {
        val id = coordinator.enqueue(track)
        val resolutionStarted = CompletableDeferred<Unit>()
        val resolutionFinished = CompletableDeferred<Unit>()
        onResolve = { resolutionStarted.complete(Unit); resolutionFinished.await() }
        val execution = async(Dispatchers.Default) { runCatching { coordinator.execute(id, item(id).requestId!!) {} } }
        resolutionStarted.await()
        coordinator.release()
        resolutionFinished.complete(Unit)
        assertTrue(execution.await().exceptionOrNull() is CancellationException)
        assertTrue(positions.isEmpty())
        assertTrue(runCatching { coordinator.enqueue(track) }.exceptionOrNull() is CancellationException)
        coordinator.release()
    }

    @Test fun accountRevocationDuringPolicyLookupDoesNotResolveMedia() = runBlocking {
        val id = coordinator.enqueue(track)
        onPolicy = { allowed = false }
        execute(id)
        assertEquals(DownloadPhase.PAUSED, item(id).phase)
        assertEquals(0, resolves)
        assertTrue(positions.isEmpty())
    }

    @Test fun accountRevocationDuringResolutionDoesNotOpenTransport() = runBlocking {
        val id = coordinator.enqueue(track)
        onResolve = { allowed = false }
        execute(id)
        assertEquals(DownloadPhase.PAUSED, item(id).phase)
        assertEquals(1, resolves)
        assertTrue(positions.isEmpty())
    }

    @Test fun modifiedCompletedFileCannotBeReusedOffline() = runBlocking {
        val id = coordinator.enqueue(track)
        execute(id)
        val file = File(Uri.parse(coordinator.offlineSource(track)!!.uri).path!!)
        file.writeBytes(audio.copyOf().also { it[0] = 42 })
        assertNull(coordinator.offlineSource(track))
        assertEquals(DownloadPhase.FAILED, item(id).phase)
    }

    @Test fun restoredWorkWaitsForAuthorizationInitializationBeforeEvaluatingSource() = runBlocking {
        val id = coordinator.enqueue(track)
        val initializationStarted = CompletableDeferred<Unit>()
        val initializationFinished = CompletableDeferred<Unit>()
        onInitialize = { initializationStarted.complete(Unit); initializationFinished.await() }
        allowed = false
        val execution = async(Dispatchers.Default) { coordinator.execute(id, item(id).requestId!!) {} }
        initializationStarted.await()
        assertEquals(0, resolves)
        assertTrue(item(id).phase in setOf(DownloadPhase.QUEUED, DownloadPhase.WAITING_NETWORK))
        allowed = true
        initializationFinished.complete(Unit)
        execution.await()
        assertEquals(DownloadPhase.COMPLETE, item(id).phase)
        assertEquals(1, resolves)
    }

    @Test fun completedOfflineFileDoesNotBypassUnverifiedAccountAndRemainsRecoverable() = runBlocking {
        val id = coordinator.enqueue(track)
        execute(id)
        allowed = false
        onPolicy = { fail("Unverified account must not start an authorization request") }
        assertNull(coordinator.offlineSource(track))
        assertEquals(DownloadPhase.COMPLETE, item(id).phase)
        allowed = true
        onPolicy = {}
        assertNotNull(coordinator.offlineSource(track))
    }

    @Test fun delayedQueuedWorkInfoDoesNotInterruptActiveTransfer() = runBlocking {
        val id = coordinator.enqueue(track)
        val requestId = UUID.fromString(item(id).requestId!!)
        await { work.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS) != null }
        val queued = work.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS)!!
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        val readStarted = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        onRead = {
            readStarted.countDown()
            check(finishRead.await(10, TimeUnit.SECONDS)) { "Timed out releasing the transfer fixture" }
        }
        val startWorker = async(Dispatchers.IO) {
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(requestId)
        }
        try {
            assertTrue("The actual worker must reach its DataSource", readStarted.await(10, TimeUnit.SECONDS))
            assertEquals(WorkInfo.State.RUNNING, work.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS)!!.state)
            assertEquals(DownloadPhase.DOWNLOADING, item(id).phase)
            coordinator.reconcileWorkState(id, listOf(queued))
            assertEquals("A delayed queued event must not cancel the active transfer", DownloadPhase.DOWNLOADING, item(id).phase)
        } finally {
            finishRead.countDown()
            startWorker.await()
        }
        await { item(id).phase !in setOf(DownloadPhase.QUEUED, DownloadPhase.WAITING_NETWORK, DownloadPhase.DOWNLOADING) }
        assertEquals(DownloadPhase.COMPLETE, item(id).phase)
        assertNotNull(coordinator.offlineSource(track))
    }

    private fun execute(id: String) {
        val requestId = UUID.fromString(item(id).requestId!!)
        await { work.getWorkInfoById(requestId).get(10, TimeUnit.SECONDS) != null }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(requestId)
        await { item(id).phase !in setOf(DownloadPhase.QUEUED, DownloadPhase.WAITING_NETWORK, DownloadPhase.DOWNLOADING) }
    }
    private fun item(id: String) = coordinator.downloads.value.single { it.id == id }
    private fun partial(id: String) = File(isolated.filesDir, "offline-audio/$id.part")
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate()) {
            if (System.nanoTime() >= deadline) fail("Timed out: ${coordinator.downloads.value}")
            Thread.sleep(20)
        }
    }

    private class MemorySettings : SettingsRepository {
        override val settings = MutableStateFlow(AppSettings())
        override suspend fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
    }

    private class BytesSource(
        private val bytes: ByteArray,
        private val positions: MutableList<Long>,
        private val onRead: () -> Unit,
    ) : DataSource {
        private var position = 0
        private var openedUri: Uri? = null
        override fun open(dataSpec: DataSpec): Long {
            positions += dataSpec.position
            position = dataSpec.position.toInt()
            openedUri = dataSpec.uri
            if (position !in 0..bytes.size) throw IOException("Invalid fixture range")
            return (bytes.size - position).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            onRead()
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun getUri(): Uri? = openedUri
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun close() = Unit
    }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
