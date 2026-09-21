package app.easepod.plugins

import app.easepod.contract.CloudPlaylist
import app.easepod.contract.LibraryMutation
import app.easepod.contract.MutationResult
import app.easepod.contract.ResultEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudLibraryTest {
    private val store = MemoryStore()
    private val transport = Transport()
    private var online = true
    private val library = CloudLibrary(transport, store) { online }
    private val favorite = LibraryMutation("mutation-1", "FAVORITE", desiredFavorite = true)
    private val add = LibraryMutation("mutation-2", "ADD", "revision-1", trackId = "track")
    private suspend fun submit(mutation: LibraryMutation = favorite, scope: String = "scope-a") =
        library.submit("plugin", scope, "target", "Title", mutation) { true }

    @Test fun offlineAndPublicWritesAreRejectedBeforeAnyRequestOrReceipt() = runBlocking {
        online = false
        assertEquals("NetworkUnavailable", runCatching { submit() }.exceptionOrNull().let { (it as PluginException).code })
        online = true
        assertEquals("AuthRequired", runCatching { submit(scope = "public") }.exceptionOrNull().let { (it as PluginException).code })
        assertTrue(transport.requests.isEmpty()); assertTrue(store.receipts.isEmpty())
    }

    @Test fun timeoutReadsBackWithoutResendingTheWrite() = runBlocking {
        transport.handler = { request -> when (request.operation) {
            "SetFavorite" -> throw PluginException("Timeout")
            "GetMutation" -> result(request, MutationResult(favorite.mutationId, "APPLIED"))
            else -> error(request.operation)
        } }
        assertEquals("APPLIED", submit().status)
        assertEquals(listOf("SetFavorite", "GetMutation"), transport.requests.map { it.operation })
        assertTrue(library.pending.value.isEmpty())
        assertEquals("scope-a", transport.requests.last().account)
        assertEquals("READ", transport.requests.last().mutation?.action)
    }

    @Test fun favoriteReadbackConfirmsTheDesiredStateWithoutOptionalMutationLookup() = runBlocking {
        transport.capabilities.remove("library.mutation.read")
        transport.handler = { request -> if (request.operation == "SetFavorite") throw PluginException("PluginDisconnected")
            else ResultEnvelope("connection", "request", request.operation, favoriteState = true) }
        assertEquals("APPLIED", submit().status)
        assertEquals(listOf("SetFavorite", "GetFavorite"), transport.requests.map { it.operation })
        assertTrue(store.receipts.isEmpty())
    }

    @Test fun unknownPlaylistReceiptSurvivesRestartAndBlocksReplay() = runBlocking {
        transport.capabilities.remove("library.mutation.read")
        transport.handler = { request -> if (request.operation == "GetPlaylistInfo") playlist(request) else throw PluginException("MutationUnknown") }
        assertEquals("UNKNOWN", submit(add).status)
        val count = transport.requests.size
        val reopened = CloudLibrary(transport, store) { online }
        assertEquals(count, transport.requests.size)
        assertEquals("UNKNOWN", reopened.refresh(reopened.pending.value.single()).status)
        assertEquals(count, transport.requests.size)
        val error = runCatching { reopened.submit("plugin", "scope-a", "target", "Title", add.copy(mutationId = "new-id")) { true } }.exceptionOrNull()
        assertEquals("MutationPending", (error as PluginException).code)
        assertEquals(1, transport.requests.count { it.operation == "EditPlaylist" })
    }

    @Test fun responseWithWrongMutationIdentityNeverBecomesSuccess() = runBlocking {
        transport.handler = { request -> result(request, MutationResult("another-request", "APPLIED")) }
        assertEquals("UNKNOWN", submit().status)
        assertEquals(favorite.mutationId, store.receipts.single().id)
    }

    @Test fun versionConflictAndMissingOwnershipDoNotSendAWrite() = runBlocking {
        transport.handler = { request -> playlist(request) }
        assertEquals("CONFLICT", submit(add.copy(expectedRevision = "old-revision")).status)
        transport.handler = { request -> playlist(request, allowed = emptyList()) }
        assertEquals("Rejected", (runCatching { submit(add) }.exceptionOrNull() as PluginException).code)
        assertEquals(listOf("GetPlaylistInfo", "GetPlaylistInfo"), transport.requests.map { it.operation })
        assertTrue(store.receipts.isEmpty())
    }

    @Test fun lockingWhileCheckingPlaylistPermissionsPreventsTheWrite() = runBlocking {
        var unlocked = true
        transport.handler = { request -> unlocked = false; playlist(request) }
        val error = runCatching { library.submit("plugin", "scope-a", "target", "Title", add) { unlocked } }.exceptionOrNull()
        assertEquals("OperationCancelled", (error as PluginException).code)
        assertEquals(listOf("GetPlaylistInfo"), transport.requests.map { it.operation })
        assertTrue(store.receipts.isEmpty())
    }

    @Test fun receiptMustBeDurableBeforeDispatch() = runBlocking {
        store.fail = true
        assertTrue(runCatching { submit() }.isFailure)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun goingOfflineAfterSendingRetainsUnknownReceipt() = runBlocking {
        transport.handler = { online = false; throw PluginException("Timeout") }
        assertEquals("UNKNOWN", submit().status)
        assertEquals(1, transport.requests.size); assertEquals(1, store.receipts.size)
    }

    @Test fun createRequiresItsOwnCapabilityAndAnAuthoritativeIdentityAndRevision() = runBlocking {
        val create = LibraryMutation("create-id", "CREATE", title = "New playlist")
        transport.capabilities.remove("library.playlist.create")
        assertEquals("Unsupported", (runCatching { library.submit("plugin", "scope-a", null, "New playlist", create) { true } }.exceptionOrNull() as PluginException).code)
        assertTrue(transport.requests.isEmpty())
        transport.capabilities.add("library.playlist.create")
        transport.handler = { request -> result(request, MutationResult(create.mutationId, "APPLIED")) }
        assertEquals("UNKNOWN", library.submit("plugin", "scope-a", null, "New playlist", create) { true }.status)
        transport.handler = { request -> result(request, MutationResult(create.mutationId, "APPLIED", "revision-new", "playlist-new")) }
        assertEquals("playlist-new", library.refresh(library.pending.value.single()).remoteId)
        assertEquals(1, transport.requests.count { it.operation == "EditPlaylist" })
        assertTrue(store.receipts.isEmpty())
    }

    private fun result(request: Request, mutation: MutationResult) = ResultEnvelope("connection", "request", request.operation, mutation = mutation)
    private fun playlist(request: Request, allowed: List<String> = listOf("ADD")) = ResultEnvelope("connection", "request", request.operation,
        playlists = listOf(CloudPlaylist("target", "Playlist", "revision-1", "Owner", allowed)))
    private data class Request(val operation: String, val account: String, val remoteId: String?, val mutation: LibraryMutation?)
    private class MemoryStore : CloudReceiptStore {
        var receipts = emptyList<CloudMutationReceipt>(); var fail = false
        override fun read() = receipts
        override fun write(receipts: List<CloudMutationReceipt>) { check(!fail) { "Disk unavailable" }; this.receipts = receipts }
    }
    private class Transport : CloudLibraryTransport {
        val capabilities = mutableSetOf("library.favorite", "library.playlist.write", "library.playlist.create", "library.mutation.read")
        val requests = mutableListOf<Request>()
        var handler: suspend (Request) -> ResultEnvelope = { request -> ResultEnvelope("connection", "request", request.operation,
            mutation = MutationResult(requireNotNull(request.mutation).mutationId, "APPLIED")) }
        override fun supports(pluginId: String, capability: String) = capability in capabilities
        override suspend fun request(pluginId: String, accountScope: String, operation: String, remoteId: String?, cursor: String?, mutation: LibraryMutation?, authorized: (() -> Boolean)?): ResultEnvelope {
            if (operation in setOf("SetFavorite", "EditPlaylist")) check(authorized?.invoke() == true)
            val request = Request(operation, accountScope, remoteId, mutation); requests += request; return handler(request)
        }
    }
}
