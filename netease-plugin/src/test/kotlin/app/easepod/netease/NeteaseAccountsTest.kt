package app.easepod.netease

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseAccountsTest {
    @Test fun qrCredentialsAreSavedOnlyAfterAccountVerificationAndSurviveRecreation() = runBlocking {
        val fixture = Fixture()
        assertTrue(fixture.api.calls.isEmpty())
        val session = fixture.accounts.begin("host-connection")
        assertEquals("WaitingScan", session.state)
        assertEquals("https://music.163.com/login?codekey=fixture-key", session.qrContent)
        assertEquals(fixture.now + 180_000, session.expiresAtElapsedMs)
        assertEquals(NeteaseMode.EAPI, fixture.api.calls.single().mode)
        fixture.qrCode = 801
        assertEquals("WaitingScan", fixture.accounts.poll("host-connection", session.sessionId).state)
        fixture.now += 1_500
        fixture.qrCode = 802
        assertEquals("WaitingConfirm", fixture.accounts.poll("host-connection", session.sessionId).state)
        assertTrue(fixture.store.writes.isEmpty())
        fixture.now += 1_500
        fixture.qrCode = 803
        val signedIn = fixture.accounts.poll("host-connection", session.sessionId)
        assertEquals("SignedIn", signedIn.state)
        assertNull(signedIn.qrContent)
        assertEquals("7", signedIn.account?.pluginAccountId)
        assertEquals(listOf(QR_KEY, QR_CHECK, QR_CHECK, QR_CHECK, ACCOUNT), fixture.api.calls.map { it.path })
        assertEquals(1, fixture.store.writes.size)
        assertEquals("session-secret", fixture.store.stored.single().cookies.single { it.name == "MUSIC_U" }.value)
        val restored = NeteaseAccounts(fixture.api, fixture.store) { fixture.now }
        assertEquals("7", restored.list().single().pluginAccountId)
        restored.use("7") { cookies, id ->
            assertEquals("7", id)
            assertEquals("session-secret", cookies.snapshot().single { it.name == "MUSIC_U" }.value)
        }
    }

    @Test fun successfulQrCodeWithoutAMatchingVerifiedIdentityOrLoginCookieDoesNotSave() = runBlocking {
        val invalidAccounts = listOf(
            JSONObject().put("code", 200),
            JSONObject().put("code", 301),
            accountResponse(userId = 7, accountId = 8),
        )
        for (response in invalidAccounts) {
            val fixture = Fixture().apply { accountResponse = response }
            val session = fixture.accounts.begin("owner")
            expectError("AuthRequired") { fixture.accounts.poll("owner", session.sessionId) }
            assertTrue(fixture.store.writes.isEmpty())
            expectError("AuthRequired") { fixture.accounts.use("7") { _, _ -> Unit } }
        }
        val fixture = Fixture().apply { establishCookie = false }
        val session = fixture.accounts.begin("owner")
        expectError("AuthRequired") { fixture.accounts.poll("owner", session.sessionId) }
        assertTrue(fixture.store.writes.isEmpty())
    }

    @Test fun anotherConnectionCannotPollOrCancelAnOwnersSession() = runBlocking {
        val fixture = Fixture().apply { qrCode = 801 }
        val session = fixture.accounts.begin("owner")
        assertEquals("Cancelled", fixture.accounts.poll("other", session.sessionId).state)
        fixture.accounts.cancel("other", session.sessionId)
        assertEquals(listOf(QR_KEY), fixture.api.calls.map { it.path })
        assertEquals("WaitingScan", fixture.accounts.poll("owner", session.sessionId).state)
        assertEquals(1, fixture.api.calls.count { it.path == QR_CHECK })
    }

    @Test(timeout = 10_000) fun cancellingWhileAccountVerificationIsInFlightCannotPublishCredentials() = runBlocking {
        val fixture = Fixture()
        val verifying = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JSONObject>()
        fixture.api.intercept = { call ->
            if (call.path == ACCOUNT) { verifying.complete(Unit); response.await() } else null
        }
        val session = fixture.accounts.begin("owner")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.accounts.poll("owner", session.sessionId) } }
        verifying.await()
        assertEquals("Cancelled", fixture.accounts.cancel("owner", session.sessionId).state)
        response.complete(accountResponse())
        // Verification may observe that cancellation already cleared MUSIC_U.
        val result = pending.await()
        assertTrue(result.getOrNull()?.state == "Cancelled" || (result.exceptionOrNull() as? NeteaseException)?.code == "AuthRequired")
        assertTrue(fixture.store.writes.isEmpty())
        expectError("AuthRequired") { fixture.accounts.use("7") { _, _ -> Unit } }
    }

    @Test(timeout = 10_000) fun cancellingTheRequestRejectsEvenANonCooperativeLateVerification() = runBlocking {
        val fixture = Fixture()
        val verifying = CompletableDeferred<Unit>()
        val response = CompletableDeferred<JSONObject>()
        fixture.api.intercept = { call ->
            if (call.path == ACCOUNT) {
                verifying.complete(Unit)
                withContext(NonCancellable) { response.await() }
            } else null
        }
        val session = fixture.accounts.begin("owner")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { fixture.accounts.poll("owner", session.sessionId) }
        verifying.await()
        pending.cancel()
        response.complete(accountResponse())
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(fixture.store.writes.isEmpty())
        expectError("AuthRequired") { fixture.accounts.use("7") { _, _ -> Unit } }
        assertEquals("Cancelled", fixture.accounts.cancel("owner", session.sessionId).state)
    }

    @Test fun refreshingQrInvalidatesThePreviousSessionWithoutCancellingOtherOwners() = runBlocking {
        val fixture = Fixture().apply { qrCode = 801 }
        val old = fixture.accounts.begin("owner")
        val other = fixture.accounts.begin("other")
        val replacement = fixture.accounts.begin("owner")
        assertNotEquals(old.sessionId, replacement.sessionId)
        assertEquals("Cancelled", fixture.accounts.poll("owner", old.sessionId).state)
        assertEquals("WaitingScan", fixture.accounts.poll("other", other.sessionId).state)
        assertEquals("WaitingScan", fixture.accounts.poll("owner", replacement.sessionId).state)
        assertTrue(fixture.store.writes.isEmpty())
    }

    @Test(timeout = 10_000) fun anOlderBeginCannotReplaceTheLatestQrForTheSameOwner() = runBlocking {
        val fixture = Fixture().apply { qrCode = 801 }
        val oldestResponse = CompletableDeferred<JSONObject>()
        var keyRequests = 0
        fixture.api.intercept = { call ->
            if (call.path == QR_KEY && ++keyRequests == 1) oldestResponse.await() else null
        }
        val older = async(start = CoroutineStart.UNDISPATCHED) { fixture.accounts.begin("owner") }
        val newer = fixture.accounts.begin("owner")
        oldestResponse.complete(JSONObject().put("code", 200).put("unikey", "old-key"))
        assertEquals("Cancelled", older.await().state)
        assertEquals("WaitingScan", fixture.accounts.poll("owner", newer.sessionId).state)
        assertEquals("fixture-key", fixture.api.calls.last().data.getString("key"))
        assertTrue(fixture.store.writes.isEmpty())
    }

    @Test(timeout = 10_000) fun closingAccountsPreventsAnInFlightBeginFromPublishingANewSession() = runBlocking {
        val fixture = Fixture()
        val keyResponse = CompletableDeferred<JSONObject>()
        fixture.api.intercept = { call -> if (call.path == QR_KEY) keyResponse.await() else null }
        val beginning = async(start = CoroutineStart.UNDISPATCHED) { fixture.accounts.begin("owner") }
        fixture.accounts.close()
        keyResponse.complete(JSONObject().put("code", 200).put("unikey", "late-key"))
        val closed = beginning.await()
        assertEquals("Cancelled", closed.state)
        assertNull(closed.qrContent)
        assertTrue(fixture.store.writes.isEmpty())
        assertEquals(listOf(QR_KEY), fixture.api.calls.map { it.path })
    }

    @Test fun expiryBeforePollingAvoidsTheNetworkAndExpiryDuringVerificationAvoidsSaving() = runBlocking {
        val expired = Fixture()
        val old = expired.accounts.begin("owner")
        expired.now = old.expiresAtElapsedMs
        assertEquals("Expired", expired.accounts.poll("owner", old.sessionId).state)
        assertEquals(listOf(QR_KEY), expired.api.calls.map { it.path })
        assertTrue(expired.store.writes.isEmpty())

        val fixture = Fixture()
        val session = fixture.accounts.begin("owner")
        fixture.api.intercept = { call ->
            if (call.path == ACCOUNT) { fixture.now = session.expiresAtElapsedMs; accountResponse() } else null
        }
        assertEquals("Expired", fixture.accounts.poll("owner", session.sessionId).state)
        assertTrue(fixture.store.writes.isEmpty())
        expectError("AuthRequired") { fixture.accounts.use("7") { _, _ -> Unit } }
    }

    @Test(timeout = 10_000) fun pollingIsThrottledAndConcurrentPollsDoNotIssueDuplicateRequests() = runBlocking {
        val fixture = Fixture().apply { qrCode = 802 }
        val response = CompletableDeferred<JSONObject>()
        fixture.api.intercept = { call -> if (call.path == QR_CHECK) response.await() else null }
        val session = fixture.accounts.begin("owner")
        val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.accounts.poll("owner", session.sessionId) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.accounts.poll("owner", session.sessionId) }
        assertEquals(1, fixture.api.calls.count { it.path == QR_CHECK })
        response.complete(JSONObject().put("code", 802))
        assertEquals("WaitingConfirm", first.await().state)
        assertEquals("WaitingConfirm", second.await().state)
        assertEquals(1, fixture.api.calls.count { it.path == QR_CHECK })
        fixture.now += 1_499
        fixture.accounts.poll("owner", session.sessionId)
        assertEquals(1, fixture.api.calls.count { it.path == QR_CHECK })
        fixture.now += 1
        fixture.accounts.poll("owner", session.sessionId)
        assertEquals(2, fixture.api.calls.count { it.path == QR_CHECK })
    }

    @Test fun serviceReportedExpiryDiscardsSessionAndLimitsConcurrentOwners() = runBlocking {
        val fixture = Fixture().apply { qrCode = 800 }
        val session = fixture.accounts.begin("owner")
        val expired = fixture.accounts.poll("owner", session.sessionId)
        assertEquals("Expired", expired.state)
        assertNull(expired.qrContent)
        assertEquals("Cancelled", fixture.accounts.poll("owner", session.sessionId).state)
        repeat(4) { fixture.accounts.begin("owner-$it") }
        expectError("RateLimited") { fixture.accounts.begin("fifth-owner") }
        assertTrue(fixture.store.writes.isEmpty())
    }

    @Test(timeout = 10_000) fun signedOutAccountCannotBeRevivedByALateReadOrRotatedCookie() = runBlocking {
        val fixture = Fixture(initial = listOf(storedAccount()))
        val resume = CompletableDeferred<Unit>()
        var detached: NeteaseCookies? = null
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            fixture.accounts.use("7") { cookies, _ ->
                detached = cookies
                resume.await()
                cookies.replace(listOf(loginCookie("late-cookie")))
                "late-response"
            }
        } }
        fixture.accounts.signOut("7")
        assertTrue(fixture.store.stored.isEmpty())
        assertTrue(requireNotNull(detached).snapshot().isEmpty())
        resume.complete(Unit)
        assertEquals("AuthRequired", (pending.await().exceptionOrNull() as NeteaseException).code)
        expectError("AuthRequired") { fixture.accounts.requireActive("7", requireNotNull(detached)) }
        expectError("AuthRequired") { fixture.accounts.use("7") { _, _ -> Unit } }
        assertTrue(fixture.store.stored.isEmpty())
        assertEquals(1, fixture.store.writes.size)
    }

    @Test(timeout = 10_000) fun lateAccountVerificationAfterSignOutDoesNotRestoreTheAccount() = runBlocking {
        val fixture = Fixture(initial = listOf(storedAccount()))
        val response = CompletableDeferred<JSONObject>()
        fixture.api.intercept = { call -> if (call.path == ACCOUNT) response.await() else null }
        val listing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.accounts.list() } }
        fixture.accounts.signOut("7")
        response.complete(accountResponse())
        assertEquals("AuthRequired", (listing.await().exceptionOrNull() as NeteaseException).code)
        assertTrue(fixture.store.stored.isEmpty())
        assertEquals(1, fixture.store.writes.size)
    }

    @Test fun failedRemoteSignOutStillRemovesLocalCredentialsAndPrivateAccess() = runBlocking {
        val fixture = Fixture(initial = listOf(storedAccount()))
        fixture.api.intercept = { call -> if (call.path == LOGOUT) throw NeteaseException("NetworkUnavailable", "offline") else null }
        fixture.accounts.signOut("7")
        assertTrue(fixture.store.stored.isEmpty())
        expectError("AuthRequired") { fixture.accounts.use("7") { _, _ -> Unit } }
        assertTrue(fixture.api.calls.single().cookies.snapshot().isEmpty())
    }

    @Test fun accountReadsRejectIdentityChangesAndPersistValidNamesAndCookieRotation() = runBlocking {
        val fixture = Fixture(initial = listOf(storedAccount()))
        fixture.accountResponse = accountResponse(userId = 8, accountId = 8)
        val mismatch = fixture.accounts.list().single()
        assertEquals("7", mismatch.pluginAccountId)
        assertEquals("Expired", mismatch.authState)
        assertTrue(fixture.store.writes.isEmpty())
        fixture.accountResponse = accountResponse(name = "Updated name")
        assertEquals("Updated name", fixture.accounts.list().single().displayName)
        fixture.accounts.use("7") { cookies, id ->
            assertEquals("7", id)
            cookies.saveFromResponse("https://music.163.com/".toHttpUrl(), listOf(loginCookie("rotated-cookie")))
        }
        assertEquals("Updated name", fixture.store.stored.single().name)
        assertEquals("rotated-cookie", fixture.store.stored.single().cookies.single().value)
        val restored = NeteaseAccounts(fixture.api, fixture.store) { fixture.now }
        restored.use("7") { cookies, _ -> assertEquals("rotated-cookie", cookies.snapshot().single().value) }
    }

    @Test fun failedCredentialSaveDoesNotInstallAnAccountAndCorruptStoreNeedsExplicitNewLogin() = runBlocking {
        val fixture = Fixture()
        fixture.store.saveFailure = IllegalStateException("disk unavailable")
        val session = fixture.accounts.begin("owner")
        assertTrue(runCatching { fixture.accounts.poll("owner", session.sessionId) }.exceptionOrNull() is IllegalStateException)
        expectError("AuthRequired") { fixture.accounts.use("7") { _, _ -> Unit } }
        assertTrue(fixture.store.stored.isEmpty())

        val corrupt = Fixture(store = MemoryStore().apply { loadFailure = IllegalStateException("authentication failed") })
        expectError("AuthRequired") { corrupt.accounts.list() }
        assertTrue(corrupt.api.calls.isEmpty())
        corrupt.accounts.begin("new-login")
        assertEquals(listOf(emptyList<StoredNeteaseAccount>()), corrupt.store.writes)
    }

    private class Fixture(initial: List<StoredNeteaseAccount> = emptyList(), val store: MemoryStore = MemoryStore(initial)) {
        var now = 10_000L
        var qrCode = 803
        var establishCookie = true
        var accountResponse = accountResponse()
        val api = FakeApi { call -> when (call.path) {
            QR_KEY -> JSONObject().put("code", 200).put("data", JSONObject().put("unikey", "fixture-key"))
            QR_CHECK -> {
                if (qrCode == 803 && establishCookie) call.cookies.replace(listOf(loginCookie()))
                JSONObject().put("code", qrCode)
            }
            ACCOUNT -> accountResponse
            LOGOUT -> JSONObject().put("code", 200)
            else -> error("Unexpected API operation ${call.path}")
        } }
        val accounts = NeteaseAccounts(api, store) { now }
    }

    private data class Call(val path: String, val data: JSONObject, val cookies: NeteaseCookies, val mode: NeteaseMode)
    private class FakeApi(private val respond: suspend (Call) -> JSONObject) : NeteaseApi {
        val calls = mutableListOf<Call>()
        var intercept: suspend (Call) -> JSONObject? = { null }
        override suspend fun post(path: String, data: JSONObject, cookies: NeteaseCookies, mode: NeteaseMode): JSONObject {
            val call = Call(path, data, cookies, mode)
            calls += call
            return intercept(call) ?: respond(call)
        }
    }

    private class MemoryStore(initial: List<StoredNeteaseAccount> = emptyList()) : NeteaseAccountStore {
        var stored = initial
        val writes = mutableListOf<List<StoredNeteaseAccount>>()
        var loadFailure: Exception? = null
        var saveFailure: Exception? = null
        override fun load(): List<StoredNeteaseAccount> { loadFailure?.let { throw it }; return stored }
        override fun save(accounts: List<StoredNeteaseAccount>) {
            saveFailure?.let { throw it }
            stored = accounts.map { it.copy(cookies = it.cookies.toList()) }
            writes += stored
        }
    }

    private suspend fun expectError(code: String, action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected NeteaseException($code), got $failure", failure is NeteaseException)
        assertEquals(code, (failure as NeteaseException).code)
    }

    companion object {
        private const val QR_KEY = "/api/login/qrcode/unikey"
        private const val QR_CHECK = "/api/login/qrcode/client/login"
        private const val ACCOUNT = "/api/nuser/account/get"
        private const val LOGOUT = "/api/logout"
        private fun loginCookie(value: String = "session-secret") = Cookie.Builder().name("MUSIC_U").value(value)
            .domain("music.163.com").path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 3_600_000).build()
        private fun storedAccount() = StoredNeteaseAccount("7", "Saved name", listOf(loginCookie()))
        private fun accountResponse(userId: Long = 7, accountId: Long = userId, name: String = "Verified name") = JSONObject()
            .put("code", 200).put("profile", JSONObject().put("userId", userId).put("nickname", name))
            .put("account", JSONObject().put("id", accountId))
    }
}
