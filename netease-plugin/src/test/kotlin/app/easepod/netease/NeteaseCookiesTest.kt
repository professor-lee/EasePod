package app.easepod.netease

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCookiesTest {
    @Test fun domainPathSecureAndHostOnlyRulesArePreserved() {
        val jar = NeteaseCookies { 1_000L }
        jar.saveFromResponse("https://music.163.com/login".toHttpUrl(), listOf(
            cookie("domain", "one", domain = "music.163.com"),
            cookie("host", "two", hostOnly = true),
            cookie("scoped", "three", path = "/weapi/account"),
        ))
        assertEquals(listOf("scoped", "domain", "host"), jar.loadForRequest("https://music.163.com/weapi/account/get".toHttpUrl()).map { it.name })
        assertEquals(listOf("domain"), jar.loadForRequest("https://interface.music.163.com/eapi/test".toHttpUrl()).map { it.name })
        assertTrue(jar.loadForRequest("https://music.163.com.evil.test/weapi/account".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://music.163.com/weapi/account".toHttpUrl()).isEmpty())
        assertEquals(2, jar.loadForRequest("https://music.163.com/weapi/accounts".toHttpUrl()).size)
    }

    @Test fun replacementsExpiryAndDeletionUseTheCookieIdentity() {
        var now = 1_000L
        val jar = NeteaseCookies { now }
        val url = "https://music.163.com/".toHttpUrl()
        jar.saveFromResponse(url, listOf(cookie("session", "old")))
        jar.saveFromResponse(url, listOf(cookie("session", "new"), cookie("short", "expires", expires = 1_500)))
        assertEquals("new", jar.snapshot().first { it.name == "session" }.value)
        now = 1_501L
        assertEquals(listOf("session"), jar.snapshot().map { it.name })
        jar.saveFromResponse(url, listOf(cookie("session", "deleted", expires = 1L)))
        assertTrue(jar.snapshot().isEmpty())
    }

    @Test fun unrelatedResponseDomainsAreRejectedAndPersistedCopiesAreIndependent() {
        val jar = NeteaseCookies { 1_000L }
        jar.saveFromResponse("https://music.163.com/".toHttpUrl(), listOf(cookie("foreign", "secret", domain = "evil.test")))
        assertTrue(jar.snapshot().isEmpty())
        val records = mutableListOf(cookie("session", "one"))
        jar.replace(records)
        records.clear()
        val snapshot = jar.snapshot()
        jar.clear()
        assertEquals(1, snapshot.size)
        assertTrue(jar.snapshot().isEmpty())
    }

    private fun cookie(name: String, value: String, domain: String = "music.163.com", path: String = "/",
        expires: Long = 10_000, hostOnly: Boolean = false): Cookie = Cookie.Builder()
        .name(name).value(value).apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
        .path(path).secure().expiresAt(expires).build()
}
