package app.easepod.netease

import app.easepod.contract.LibraryMutation
import app.easepod.contract.RequestEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseFavoritesTest {
    @Test fun readReturnsProviderStateWithoutWriting() = runBlocking {
        val api = FakeApi { _, _ -> ids(true) }
        val result = NeteaseFavorites(api).handle(request().copy(operation = "GetFavorite", mutation = null), NeteaseCookies(), "7") {}
        assertEquals(true, result.favoriteState)
        assertEquals(listOf(READ), api.calls.map { it.path })
        assertEquals("7", api.calls.single().data.getString("uid"))
        assertEquals(NeteaseMode.EAPI, api.calls.single().mode)
    }

    @Test fun desiredStateIsIdempotentForBothFavoriteAndUnfavorite() = runBlocking {
        for (desired in listOf(true, false)) {
            val api = FakeApi { _, _ -> ids(desired) }
            val favorites = NeteaseFavorites(api)
            repeat(2) {
                val result = favorites.handle(request(desired), NeteaseCookies(), "7") {}
                assertEquals("APPLIED", result.mutation?.status)
                assertEquals("mutation", result.mutation?.mutationId)
            }
            assertEquals(listOf(READ, READ), api.calls.map { it.path })
        }
    }

    @Test fun writeIsSentOnceAndOnlyConfirmedByReadback() = runBlocking {
        var state = false
        val api = FakeApi { path, data -> if (path == WRITE) {
            assertEquals("42", data.getString("trackId"))
            state = data.getBoolean("like")
            JSONObject().put("code", 200)
        } else ids(state) }
        val result = NeteaseFavorites(api).handle(request(), NeteaseCookies(), "7") {}
        assertEquals("APPLIED", result.mutation?.status)
        assertEquals(listOf(READ, WRITE, READ), api.calls.map { it.path })
        assertEquals(NeteaseMode.WEAPI, api.calls.single { it.path == WRITE }.mode)
    }

    @Test fun failureBeforeWriteDoesNotSendAnyMutation() = runBlocking {
        for (code in listOf("NetworkUnavailable", "AuthRequired")) {
            val api = FakeApi { _, _ -> throw NeteaseException(code, "fixture") }
            val error = failure { NeteaseFavorites(api).handle(request(), NeteaseCookies(), "7") {} }
            assertEquals(code, error.code)
            assertEquals(listOf(READ), api.calls.map { it.path })
        }
        val malformed = FakeApi { _, _ -> JSONObject().put("code", 200) }
        assertEquals("InvalidResponse", failure { NeteaseFavorites(malformed).handle(request(), NeteaseCookies(), "7") {} }.code)
        assertEquals(listOf(READ), malformed.calls.map { it.path })
    }

    @Test fun lostWriteResponseProducesUnknownWithoutRetrying() = runBlocking {
        val api = FakeApi { path, _ -> if (path == WRITE) throw NeteaseException("NetworkUnavailable", "fixture") else ids(false) }
        val result = NeteaseFavorites(api).handle(request(), NeteaseCookies(), "7") {}
        assertEquals("UNKNOWN", result.mutation?.status)
        assertEquals("42", result.mutation?.remoteId)
        assertEquals(listOf(READ, WRITE), api.calls.map { it.path })
    }

    @Test fun mismatchingOrFailedReadbackProducesUnknownWithoutASecondWrite() = runBlocking {
        for (failReadback in listOf(false, true)) {
            var wrote = false
            val api = FakeApi { path, _ -> when {
                path == WRITE -> { wrote = true; JSONObject().put("code", 200) }
                wrote && failReadback -> throw NeteaseException("Timeout", "fixture")
                else -> ids(false)
            } }
            val result = NeteaseFavorites(api).handle(request(), NeteaseCookies(), "7") {}
            assertEquals("UNKNOWN", result.mutation?.status)
            assertEquals(listOf(READ, WRITE, READ), api.calls.map { it.path })
        }
    }

    @Test fun accountExitBeforeWriteStopsTheMutation() = runBlocking {
        var active = true
        val api = FakeApi { _, _ -> active = false; ids(false) }
        val error = failure {
            NeteaseFavorites(api).handle(request(), NeteaseCookies(), "7") {
                if (!active) throw NeteaseException("AuthRequired", "fixture")
            }
        }
        assertEquals("AuthRequired", error.code)
        assertEquals(listOf(READ), api.calls.map { it.path })
    }

    @Test fun accountExitAfterWriteReturnsUnknownAndDoesNotReadOrWriteAgain() = runBlocking {
        var active = true
        val api = FakeApi { path, _ -> if (path == WRITE) { active = false; JSONObject().put("code", 200) } else ids(false) }
        val result = NeteaseFavorites(api).handle(request(), NeteaseCookies(), "7") {
            if (!active) throw NeteaseException("AuthRequired", "fixture")
        }
        assertEquals("UNKNOWN", result.mutation?.status)
        assertEquals(listOf(READ, WRITE), api.calls.map { it.path })
    }

    @Test fun cancellationDuringWritePropagatesWithoutRetry() = runBlocking {
        val api = FakeApi { path, _ -> if (path == WRITE) throw CancellationException("fixture") else ids(false) }
        try {
            NeteaseFavorites(api).handle(request(), NeteaseCookies(), "7") {}
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf(READ, WRITE), api.calls.map { it.path })
    }

    @Test fun concurrentCommandsForTheSameDesiredStateSendOnlyOneWrite() = runBlocking {
        var state = false
        val writeStarted = CompletableDeferred<Unit>()
        val completeWrite = CompletableDeferred<Unit>()
        val api = FakeApi { path, _ -> if (path == WRITE) {
            writeStarted.complete(Unit)
            completeWrite.await()
            state = true
            JSONObject().put("code", 200)
        } else ids(state) }
        val favorites = NeteaseFavorites(api)
        val first = async { favorites.handle(request(), NeteaseCookies(), "7") {} }
        writeStarted.await()
        val second = async { favorites.handle(request().copy(requestId = "second"), NeteaseCookies(), "7") {} }
        completeWrite.complete(Unit)
        assertEquals("APPLIED", first.await().mutation?.status)
        assertEquals("APPLIED", second.await().mutation?.status)
        assertEquals(1, api.calls.count { it.path == WRITE })
        assertEquals(listOf(READ, WRITE, READ, READ), api.calls.map { it.path })
    }

    @Test fun invalidCommandsAndPublicAccountsNeverReachTheApi() = runBlocking {
        val api = FakeApi { _, _ -> error("No request expected") }
        val favorites = NeteaseFavorites(api)
        assertEquals("AuthRequired", favorites.handle(request(), NeteaseCookies(), null) {}.errorCode)
        for (id in listOf("0", "-1", "42/other", "9223372036854775808", "042")) {
            assertEquals("InvalidRequest", favorites.handle(request().copy(remoteId = id), NeteaseCookies(), "7") {}.errorCode)
        }
        assertEquals("InvalidRequest", favorites.handle(request().copy(mutation = null), NeteaseCookies(), "7") {}.errorCode)
        assertTrue(api.calls.isEmpty())
    }

    private fun request(desired: Boolean = true) = RequestEnvelope("connection", "request", "trace", Long.MAX_VALUE,
        "account", "SetFavorite", remoteId = "42", mutation = LibraryMutation("mutation", "FAVORITE", desiredFavorite = desired))
    private fun ids(favorite: Boolean) = JSONObject().put("code", 200)
        .put("ids", JSONArray().apply { if (favorite) put(42) })
    private suspend fun failure(block: suspend () -> Unit): NeteaseException {
        try { block() } catch (error: NeteaseException) { return error }
        throw AssertionError("Expected NeteaseException")
    }

    private data class Call(val path: String, val data: JSONObject, val mode: NeteaseMode)
    private class FakeApi(private val response: suspend (String, JSONObject) -> JSONObject) : NeteaseApi {
        val calls = mutableListOf<Call>()
        override suspend fun post(path: String, data: JSONObject, cookies: NeteaseCookies, mode: NeteaseMode): JSONObject {
            calls += Call(path, data, mode)
            return response(path, data)
        }
    }
    private companion object {
        const val READ = "/api/song/like/get"
        const val WRITE = "/api/radio/like"
    }
}
