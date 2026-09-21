package app.easepod.sampleplugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.easepod.contract.HostHello
import app.easepod.contract.IHandshakeCallback
import app.easepod.contract.IMusicPlugin
import app.easepod.contract.IResultCallback
import app.easepod.contract.PackageIdentity
import app.easepod.contract.PluginHello
import app.easepod.contract.Protocol
import app.easepod.contract.RequestEnvelope
import app.easepod.contract.ResultEnvelope
import app.easepod.contract.TrustedHosts
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginBinderContractTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bindings = mutableListOf<ServiceConnection>()
    private var connectionId = UUID.randomUUID().toString()

    @Before fun approveTestCaller() {
        // Test code runs as the sample UID. Production approval UI only permits app.easepod.
        TrustedHosts(context).approve(context.packageName, PackageIdentity.fingerprints(context, context.packageName))
    }
    @After fun close() { bindings.forEach { runCatching { context.unbindService(it) } } }

    private fun bind(fixture: Boolean = false): IMusicPlugin {
        val ready = LinkedBlockingQueue<IBinder>()
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.offer(service) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        bindings.add(binding)
        val name = if (fixture) "ContractFixtureService" else "SampleMusicService"
        assertTrue(context.bindService(Intent(Protocol.ACTION).setComponent(ComponentName(context.packageName, "${context.packageName}.$name")), binding, Context.BIND_AUTO_CREATE))
        val binder = ready.poll(5, TimeUnit.SECONDS) ?: error("Service did not bind")
        assertNull("Must use a real remote Binder proxy", binder.queryLocalInterface("app.easepod.contract.IMusicPlugin"))
        return IMusicPlugin.Stub.asInterface(binder)
    }
    private fun hello(service: IMusicPlugin, major: Int = 1, host: String = context.packageName): PluginHello {
        val result = LinkedBlockingQueue<PluginHello>()
        service.handshake(HostHello(connectionId, host, major = major), object : IHandshakeCallback.Stub() {
            override fun onResult(hello: PluginHello) { result.offer(hello) }
        })
        return result.poll(5, TimeUnit.SECONDS) ?: error("Handshake did not complete")
    }
    private fun request(operation: String = "Browse", query: String? = null, pageSize: Int = 30) = RequestEnvelope(
        connectionId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), SystemClock.elapsedRealtime() + 5000,
        operation = operation, query = query, pageSize = pageSize)
    private fun send(service: IMusicPlugin, request: RequestEnvelope): LinkedBlockingQueue<ResultEnvelope> {
        val queue = LinkedBlockingQueue<ResultEnvelope>()
        service.execute(request, object : IResultCallback.Stub() { override fun onResult(result: ResultEnvelope) { queue.offer(result) } })
        return queue
    }
    private fun result(service: IMusicPlugin, request: RequestEnvelope) = send(service, request).poll(5, TimeUnit.SECONDS) ?: error("Request did not complete")

    @Test fun rejectsForgedHostPackage() { assertEquals("HostNotApproved", hello(bind(), host = "untrusted.host").error) }
    @Test fun rejectsIncompatibleMajor() { assertEquals("IncompatibleProtocol", hello(bind(), major = 99).error) }
    @Test fun browseSearchAndResolveUseStandardModels() {
        val service = bind()
        assertNull(hello(service).error)
        val page = result(service, request(pageSize = 2))
        assertNull(page.errorCode); assertEquals(2, page.items.size); assertNotNull(page.nextCursor)
        val found = result(service, request("Search", "Song 2"))
        assertEquals("soundhelix-2", found.items.single().remoteId)
        val source = result(service, request("ResolvePlayback").copy(remoteId = "soundhelix-2")).playback!!
        assertEquals("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3", source.url)
        assertEquals("NO_STORE", source.cachePolicy)
    }
    @Test fun paginationRetainsPositionAndCursorCannotChangeQuery() {
        val service = bind(); assertNull(hello(service).error)
        val first = result(service, request(pageSize = 2))
        val second = result(service, request(pageSize = 2).copy(cursor = first.nextCursor))
        assertEquals(listOf("soundhelix-3"), second.items.map { it.remoteId })
        assertNull(second.nextCursor)
        assertEquals("InvalidRequest", result(service, request("Search", "Song 2").copy(cursor = first.nextCursor)).errorCode)
        assertEquals("AuthRequired", result(service, request().copy(accountContext = "someone-private")).errorCode)
    }
    @Test fun accountAndLyricsAreBoundedAndDoNotInventLogin() {
        val service = bind(); assertNull(hello(service).error)
        assertTrue(result(service, request("ListAccounts")).accounts.isEmpty())
        assertTrue(result(service, request("GetLyrics").copy(remoteId = "soundhelix-1")).lyrics.isEmpty())
        assertEquals("Unsupported", result(service, request("BeginAuth")).errorCode)
    }
    @Test fun rejectsOversizedPageAndExpiredDeadline() {
        val service = bind(); assertNull(hello(service).error)
        assertEquals("InvalidRequest", result(service, request(pageSize = 51)).errorCode)
        assertEquals("Timeout", result(service, request().copy(deadlineElapsedMs = SystemClock.elapsedRealtime() - 1)).errorCode)
    }
    @Test fun cancellationSuppressesLateCompletion() {
        val service = bind(true); assertNull(hello(service).error)
        val request = request("Search", "slow")
        val queue = send(service, request)
        service.cancel(connectionId, request.requestId)
        assertNull(queue.poll(1500, TimeUnit.MILLISECONDS))
    }
    @Test fun revocationImmediatelyBlocksExistingConnectionAndInFlightResponse() {
        val service = bind(true); assertNull(hello(service).error)
        val running = send(service, request("Search", "slow"))
        TrustedHosts(context).revoke(context.packageName)
        assertNull(running.poll(1200, TimeUnit.MILLISECONDS))
        assertEquals("HostNotApproved", hello(service).error)
        assertNull(send(service, request()).poll(300, TimeUnit.MILLISECONDS))
        TrustedHosts(context).approve(context.packageName, PackageIdentity.fingerprints(context, context.packageName))
        connectionId = UUID.randomUUID().toString()
        assertNull(hello(service).error)
        assertNull(result(service, request()).errorCode)
    }
    @Test fun unbindingInvalidatesPreviousConnection() {
        val oldService = bind(); assertNull(hello(oldService).error)
        context.unbindService(bindings.removeAt(0))
        SystemClock.sleep(200)
        val service = bind()
        assertNull(send(service, request()).poll(300, TimeUnit.MILLISECONDS))
        connectionId = UUID.randomUUID().toString()
        assertNull(hello(service).error)
        assertNull(result(service, request()).errorCode)
    }
    @Test fun thirdConcurrentRequestIsRejectedAndCompletedRequestsAreNotRepeated() {
        val service = bind(true); assertNull(hello(service).error)
        val first = request("Search", "slow")
        val firstResult = send(service, first)
        val secondResult = send(service, request("Search", "slow"))
        assertEquals("RateLimited", result(service, request("Search", "slow")).errorCode)
        assertNull(firstResult.poll(3, TimeUnit.SECONDS)!!.errorCode)
        assertNull(secondResult.poll(3, TimeUnit.SECONDS)!!.errorCode)
        assertEquals("DuplicateRequest", result(service, first).errorCode)
        assertNull(firstResult.poll(200, TimeUnit.MILLISECONDS))
    }
    @Test fun newConnectionCancelsOldGeneration() {
        val service = bind(true); assertNull(hello(service).error)
        val old = send(service, request("Search", "slow"))
        connectionId = UUID.randomUUID().toString()
        assertNull(hello(service).error)
        assertNull(old.poll(1200, TimeUnit.MILLISECONDS))
        assertNull(result(service, request()).errorCode)
    }
    @Test fun pluginProcessDeathIsObservableWithoutKillingHost() {
        val service = bind(true); assertNull(hello(service).error)
        val died = CountDownLatch(1)
        service.asBinder().linkToDeath({ died.countDown() }, 0)
        send(service, request("Search", "die"))
        assertTrue(died.await(5, TimeUnit.SECONDS))
        assertFalse(service.asBinder().isBinderAlive)
    }
}
