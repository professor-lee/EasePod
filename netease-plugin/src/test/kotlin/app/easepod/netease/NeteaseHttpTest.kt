package app.easepod.netease

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseHttpTest {
    @Test fun bothModesStayOnTheirHttpsHostsAndKeepInputUnchanged() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = fixture { request -> requests += request; response(request, "{\"code\":200}") }
        val data = JSONObject().put("id", 347230)
        for (mode in NeteaseMode.entries) client.post("/api/song/detail", data, NeteaseCookies(), mode)
        assertEquals(listOf("https://music.163.com/weapi/song/detail", "https://interface.music.163.com/eapi/song/detail"), requests.map { it.url.toString() })
        assertEquals(1, data.length())
        assertEquals(setOf("params", "encSecKey"), (requests[0].body as FormBody).let { form -> (0 until form.size).map { form.name(it) }.toSet() })
        assertEquals("POST", requests[0].method)
        assertNull(requests[0].header("X-Forwarded-For"))
    }

    @Test fun eapiIncludesOnlyCookiesMatchingItsOriginAndDisablesEncryptedResponses() = runBlocking {
        var captured: Request? = null
        val client = fixture { request -> captured = request; response(request, "{\"code\":200}") }
        val jar = NeteaseCookies()
        jar.replace(listOf(
            Cookie.Builder().name("MUSIC_U").value("fixture-session").domain("music.163.com").secure().build(),
            Cookie.Builder().name("__csrf").value("fixture-csrf").domain("music.163.com").secure().build(),
            Cookie.Builder().name("MUSIC_A").value("host-only-secret").hostOnlyDomain("music.163.com").secure().build(),
        ))
        client.post("/api/test", JSONObject(), jar, NeteaseMode.EAPI)
        val encrypted = (captured!!.body as FormBody).value(0)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        val plaintext = String(cipher.doFinal(encrypted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()), Charsets.UTF_8)
        val payload = JSONObject(plaintext.split("-36cd479b6b5-")[1])
        assertFalse(payload.getBoolean("e_r"))
        val header = payload.getJSONObject("header")
        assertEquals("fixture-session", header.getString("MUSIC_U"))
        assertEquals("fixture-csrf", header.getString("__csrf"))
        assertEquals("fixture-device", header.getString("deviceId"))
        assertFalse(header.has("MUSIC_A"))
        assertEquals("android", header.getString("os"))
    }

    @Test fun qrAndOtherBusinessCodesRemainAvailableToTheCaller() = runBlocking {
        for (code in listOf(200, 301, 403, 800, 801, 802, 803)) {
            val client = fixture { response(it, "{\"code\":$code}") }
            assertEquals(code, client.post("/api/login/qrcode/client/login", JSONObject(), NeteaseCookies()).getInt("code"))
        }
    }

    @Test fun statusAndNetworkFailuresStayDistinctAndNeverExposeServerText() = runBlocking {
        for ((status, expected) in listOf(302 to "ServiceUnavailable", 401 to "AuthRequired", 403 to "AccessDenied",
            404 to "NotFound", 429 to "RateLimited", 503 to "ServiceUnavailable")) {
            val client = fixture { response(it, "private-upstream-body", status) }
            assertEquals(expected, failure { client.post("/api/test", JSONObject(), NeteaseCookies()) }.code)
        }
        for ((exception, code) in listOf(SocketTimeoutException("secret") to "Timeout",
            SSLHandshakeException("secret") to "NetworkUnavailable", IOException("secret") to "NetworkUnavailable")) {
            val client = fixture { throw exception }
            val error = failure { client.post("/api/test", JSONObject(), NeteaseCookies()) }
            assertEquals(code, error.code)
            assertFalse(error.toString().contains("secret"))
            assertNull(error.cause)
        }
    }

    @Test fun invalidPathsAndOversizedRequestsNeverReachTheTransport() = runBlocking {
        val calls = AtomicBoolean(false)
        val client = fixture { calls.set(true); response(it, "{}") }
        for (path in listOf("https://evil.test/api/test", "/api/../test", "/api/test?x=1", "/api//test", "/api/test#fragment", "/api/%2f%2fevil")) {
            assertEquals("InvalidRequest", failure { client.post(path, JSONObject(), NeteaseCookies()) }.code)
        }
        val data = JSONObject().put("data", "x".repeat(NeteaseHttpClient.MAX_REQUEST_BYTES))
        assertEquals("InvalidRequest", failure { client.post("/api/test", data, NeteaseCookies()) }.code)
        assertFalse(calls.get())
    }

    @Test fun malformedJsonTrailingContentAndInvalidUtf8AreRejected() = runBlocking {
        for (body in listOf("[]".toByteArray(), "{} {}".toByteArray(), "<html>error</html>".toByteArray(), byteArrayOf(0x7b, 0x22, 0x61, 0x22, 0x3a, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x7d))) {
            val client = fixture { response(it, body.toResponseBody(JSON)) }
            assertEquals("InvalidResponse", failure { client.post("/api/test", JSONObject(), NeteaseCookies()) }.code)
        }
    }

    @Test fun declaredAndStreamingOversizedBodiesAreClosedAndRejected() = runBlocking {
        for (declaredLength in listOf(NeteaseHttpClient.MAX_RESPONSE_BYTES + 1, -1L)) {
            val closed = AtomicBoolean(false)
            val body = object : ResponseBody() {
                val source = object : ForwardingSource(Buffer().write(ByteArray((NeteaseHttpClient.MAX_RESPONSE_BYTES + 1).toInt()))) {
                    override fun close() { closed.set(true); super.close() }
                }.buffer()
                override fun contentType() = JSON
                override fun contentLength() = declaredLength
                override fun source(): BufferedSource = source
            }
            val client = fixture { response(it, body) }
            assertEquals("PayloadTooLarge", failure { client.post("/api/test", JSONObject(), NeteaseCookies()) }.code)
            assertTrue(closed.get())
        }
    }

    @Test fun coroutineCancellationCancelsTheUnderlyingCall() = runBlocking {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val base = OkHttpClient.Builder().addInterceptor { chain ->
            started.countDown()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                if (chain.call().isCanceled()) stopped.countDown()
                throw IOException("fixture canceled")
            } finally { stopped.countDown() }
        }.build()
        val client = NeteaseHttpClient(base, "fixture-device") { 1_000L }
        val pending = async { client.post("/api/test", JSONObject(), NeteaseCookies()) }
        try {
            kotlinx.coroutines.yield()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(stopped.await(3, TimeUnit.SECONDS))
        } finally { pending.cancelAndJoin() }
    }

    private fun fixture(handler: (Request) -> Response) = NeteaseHttpClient(
        OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build(), "fixture-device") { 1_000L }

    private fun response(request: Request, body: String, status: Int = 200) = response(request, body.toResponseBody(JSON), status)
    private fun response(request: Request, body: ResponseBody, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body).build()

    private suspend fun failure(block: suspend () -> Unit): NeteaseException {
        try { block() } catch (error: NeteaseException) { return error }
        throw AssertionError("Expected NeteaseException")
    }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
