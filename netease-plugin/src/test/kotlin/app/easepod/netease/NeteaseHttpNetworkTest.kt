package app.easepod.netease

import java.net.InetAddress
import java.net.Proxy
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseHttpNetworkTest {
    @Test fun emptyAccountJarSendsTheSameEapiHeaderInTheActualHttpCookieWithoutPersistingIt() = runBlocking {
        fixture { client, server ->
            val cookies = NeteaseCookies()
            server.enqueue(success())
            client.post("/api/login/qrcode/unikey", JSONObject().put("type", 3), cookies, NeteaseMode.EAPI)
            val request = server.request()
            assertEquals("/eapi/login/qrcode/unikey", request.path)
            assertEquals("interface.music.163.com:${server.port}", request.getHeader("Host"))
            assertNotNull("The request must complete a real TLS handshake", request.handshake)
            assertHeaderCookiesMatch(request)
            assertTrue("Generated request cookies must not become stored account cookies", cookies.snapshot().isEmpty())
        }
    }

    @Test fun eapiCookiesKeepAccountScopeAndSaveOnlyValidResponseCookies() = runBlocking {
        fixture { client, server ->
            val original = listOf(
                cookie("MUSIC_U", "session-before", "music.163.com"),
                cookie("__csrf", "csrf-before", "music.163.com"),
                cookie("deviceId", "retained-device+id/ ~*", "music.163.com"),
                cookie("server_extra", "keep-matching-cookie", "interface.music.163.com", "/eapi/login"),
                cookie("MUSIC_A", "wrong-host-secret", "music.163.com", hostOnly = true),
                cookie("MUSIC_U", "wrong-path-secret", "music.163.com", "/weapi"),
                cookie("__csrf", "foreign-domain-secret", "example.org"),
            )
            val cookies = NeteaseCookies().apply { replace(original) }
            server.enqueue(success()
                .addHeader("Set-Cookie", "MUSIC_U=session-after; Domain=music.163.com; Path=/; Secure; HttpOnly")
                .addHeader("Set-Cookie", "server_reply=reply-value; Path=/eapi/login; Secure")
                .addHeader("Set-Cookie", "foreign_response=must-not-save; Domain=example.org; Path=/; Secure"))
            client.post("/api/login/qrcode/client/login", JSONObject().put("key", "fixture-key").put("type", 3), cookies, NeteaseMode.EAPI)
            val first = server.request()
            val sent = requestCookies(first)
            assertHeaderCookiesMatch(first)
            assertEquals("session-before", sent["MUSIC_U"])
            assertEquals("csrf-before", sent["__csrf"])
            assertEquals("retained-device+id/ ~*", sent["deviceId"])
            assertTrue(first.getHeader("Cookie").orEmpty().contains("deviceId=retained-device%2Bid%2F%20~%2A"))
            assertEquals("keep-matching-cookie", sent["server_extra"])
            assertFalse(sent.containsKey("MUSIC_A"))
            assertFalse(first.getHeader("Cookie").orEmpty().contains("secret"))
            val saved = cookies.snapshot()
            assertEquals(original.size + 1, saved.size)
            assertEquals(original.map { it.name }.toSet() + "server_reply", saved.map { it.name }.toSet())
            assertEquals("session-after", saved.single { it.name == "MUSIC_U" && it.path == "/" }.value)
            assertEquals("reply-value", saved.single { it.name == "server_reply" }.value)
            assertEquals("wrong-host-secret", saved.single { it.name == "MUSIC_A" }.value)
            server.enqueue(success())
            client.post("/api/login/qrcode/client/login", JSONObject().put("key", "fixture-key").put("type", 3), cookies, NeteaseMode.EAPI)
            val second = server.request()
            assertHeaderCookiesMatch(second)
            assertEquals("session-after", requestCookies(second)["MUSIC_U"])
            assertEquals("reply-value", requestCookies(second)["server_reply"])
        }
    }

    @Test fun weapiKeepsItsNormalCookieJarWithoutEapiMetadata() = runBlocking {
        fixture { client, server ->
            val cookies = NeteaseCookies().apply {
                replace(listOf(cookie("MUSIC_U", "web-session", "music.163.com", hostOnly = true),
                    cookie("MUSIC_A", "other-host-secret", "interface.music.163.com", hostOnly = true)))
            }
            server.enqueue(success().addHeader("Set-Cookie", "web_reply=web-value; Path=/; Secure"))
            client.post("/api/nuser/account/get", JSONObject(), cookies, NeteaseMode.WEAPI)
            val request = server.request()
            assertEquals("/weapi/nuser/account/get", request.path)
            assertEquals(mapOf("MUSIC_U" to "web-session"), requestCookies(request))
            assertEquals("web-value", cookies.snapshot().single { it.name == "web_reply" }.value)
        }
    }

    private fun assertHeaderCookiesMatch(request: RecordedRequest) {
        val encrypted = request.body.clone().readUtf8().split('&').associate { field ->
            val parts = field.split('=', limit = 2)
            decode(parts[0]) to decode(parts[1])
        }.getValue("params")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
        val plaintext = String(cipher.doFinal(encrypted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()), Charsets.UTF_8)
        val payload = JSONObject(plaintext.split("-36cd479b6b5-")[1])
        val header = payload.getJSONObject("header")
        val sent = requestCookies(request)
        header.keys().forEach { name ->
            assertEquals("EAPI HTTP Cookie must match encrypted header field $name", header.getString(name), sent[name])
        }
        assertFalse(payload.getBoolean("e_r"))
        assertEquals("android", header.getString("os"))
        assertNull(request.getHeader("X-Forwarded-For"))
        assertNull(request.getHeader("X-Real-IP"))
    }

    private fun requestCookies(request: RecordedRequest): Map<String, String> = request.getHeader("Cookie")
        ?.split(';')?.associate { field ->
            val parts = field.trim().split('=', limit = 2)
            decode(parts[0]) to decode(parts[1])
        }.orEmpty()

    private fun decode(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())
    private fun cookie(name: String, value: String, domain: String, path: String = "/", hostOnly: Boolean = false) =
        Cookie.Builder().name(name).value(value).path(path).secure().apply {
            if (hostOnly) hostOnlyDomain(domain) else domain(domain)
        }.build()
    private fun success() = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"code\":200}")
    private fun MockWebServer.request() = requireNotNull(takeRequest(5, TimeUnit.SECONDS)) { "No HTTPS request received" }

    private suspend fun fixture(test: suspend (NeteaseHttpClient, MockWebServer) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("Netease HTTP test")
            .addSubjectAlternativeName("interface.music.163.com").addSubjectAlternativeName("music.163.com").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start(InetAddress.getLoopbackAddress(), 0)
            val base = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .protocols(listOf(Protocol.HTTP_1_1))
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        check(hostname in setOf("music.163.com", "interface.music.163.com"))
                        return listOf(InetAddress.getLoopbackAddress())
                    }
                })
                // Only redirect the port; TLS hostname checks and the complete HTTP stack still run.
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().port(server.port).build()).build())
                }.build()
            try { test(NeteaseHttpClient(base, "fixture-device") { 1_000L }, server) }
            finally { base.connectionPool.evictAll(); base.dispatcher.executorService.shutdown() }
        }
    }
}
