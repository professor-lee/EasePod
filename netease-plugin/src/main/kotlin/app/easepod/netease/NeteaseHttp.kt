package app.easepod.netease

import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONTokener

enum class NeteaseMode { WEAPI, EAPI }

class NeteaseException(val code: String, message: String) : Exception(message)

interface NeteaseApi {
    // HTTP failures throw; callers interpret the unchanged API code, including QR states 800..803.
    suspend fun post(path: String, data: JSONObject, cookies: NeteaseCookies,
        mode: NeteaseMode = NeteaseMode.WEAPI): JSONObject
}

class NeteaseCookies(private val clock: () -> Long = System::currentTimeMillis) : CookieJar {
    private data class Key(val name: String, val domain: String, val path: String)
    private val entries = linkedMapOf<Key, Cookie>()

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        prune()
        cookies.filter { cookie ->
            cookie.domain == url.host || (!cookie.hostOnly && url.host.endsWith(".${cookie.domain}"))
        }.forEach(::store)
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        prune()
        return entries.values.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }

    @Synchronized fun snapshot(): List<Cookie> {
        prune()
        return entries.values.toList()
    }

    @Synchronized fun replace(cookies: List<Cookie>) {
        entries.clear()
        cookies.forEach(::store)
    }

    @Synchronized fun clear() = entries.clear()

    private fun store(cookie: Cookie) {
        val key = Key(cookie.name, cookie.domain, cookie.path)
        if (cookie.expiresAt <= clock()) entries.remove(key) else entries[key] = cookie
    }

    private fun prune() { entries.entries.removeAll { it.value.expiresAt <= clock() } }
}

class NeteaseHttpClient internal constructor(
    private val baseClient: OkHttpClient,
    private val deviceId: String,
    private val clock: () -> Long,
) : NeteaseApi {
    private val requestRandom = SecureRandom()

    constructor(deviceId: String = UUID.randomUUID().toString().replace("-", "")) :
        this(OkHttpClient(), deviceId, System::currentTimeMillis)

    override suspend fun post(path: String, data: JSONObject, cookies: NeteaseCookies,
        mode: NeteaseMode): JSONObject = withContext(Dispatchers.IO) {
        ensureActive()
        if (!PATH.matches(path)) throw NeteaseException("InvalidRequest", "网易云请求路径无效")
        val original = data.toString()
        if (original.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw NeteaseException("InvalidRequest", "网易云请求内容超出大小限制")
        }
        val payload = JSONObject(original)
        val prefix = if (mode == NeteaseMode.WEAPI) "https://music.163.com/weapi/" else
            "https://interface.music.163.com/eapi/"
        val url = (prefix + path.removePrefix("/api/")).toHttpUrl()
        val matchingCookies = cookies.loadForRequest(url)
        val csrf = matchingCookies.firstOrNull { it.name == "__csrf" }?.value.orEmpty()
        val protocolHeader = if (mode == NeteaseMode.EAPI) eapiHeader(matchingCookies, csrf) else null
        val form = when (mode) {
            NeteaseMode.WEAPI -> {
                payload.put("csrf_token", csrf)
                NeteaseCrypto.weapi(payload.toString())
            }
            NeteaseMode.EAPI -> {
                payload.put("header", protocolHeader)
                payload.put("e_r", false)
                NeteaseCrypto.eapi(path, payload.toString())
            }
        }
        val body = FormBody.Builder().apply { form.forEach { (name, value) -> add(name, value) } }.build()
        val request = Request.Builder().url(url).post(body)
            .header("Accept", "application/json")
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", if (mode == NeteaseMode.WEAPI) WEB_USER_AGENT else ANDROID_USER_AGENT)
            .build()
        val requestCookies = if (protocolHeader == null) cookies else {
            val protocolCookies = protocolHeader.keys().asSequence().map { name ->
                val value = URLEncoder.encode(protocolHeader.getString(name), Charsets.UTF_8.name())
                    .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
                Cookie.Builder().name(name).value(value).hostOnlyDomain(url.host).path(url.encodedPath).secure().build()
            }.toList()
            // BridgeInterceptor builds Cookie from this request-only view; generated metadata is never stored.
            object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val protocol = protocolCookies.filter { it.matches(url) }
                    val names = protocol.mapTo(hashSetOf()) { it.name }
                    return cookies.loadForRequest(url).filterNot { it.name in names } + protocol
                }
                override fun saveFromResponse(url: HttpUrl, responseCookies: List<Cookie>) =
                    cookies.saveFromResponse(url, responseCookies)
            }
        }
        // Each account supplies its own jar; shared connection pools never carry account state.
        val client = baseClient.newBuilder().cookieJar(requestCookies).cache(null)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        client.newCall(request).awaitJson()
    }

    private fun eapiHeader(cookies: List<Cookie>, csrf: String): JSONObject {
        fun value(name: String, fallback: String) = cookies.firstOrNull { it.name == name }?.value ?: fallback
        val now = clock()
        return JSONObject().apply {
            put("os", "android")
            put("osver", "14")
            put("appver", "9.1.65.240927161425")
            put("versioncode", "9001065")
            put("channel", "netease")
            put("deviceId", value("deviceId", deviceId))
            put("mobilename", "Android")
            put("resolution", "1920x1080")
            put("buildver", (now / 1000).toString())
            put("__csrf", csrf)
            put("requestId", "${now}_${requestRandom.nextInt(1000).toString().padStart(4, '0')}")
            listOf("MUSIC_U", "MUSIC_A").forEach { name ->
                cookies.firstOrNull { it.name == name }?.let { put(name, it.value) }
            }
        }
    }

    private suspend fun Call.awaitJson(): JSONObject = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(networkError(e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { parseResponse(it) }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    val failure = when (e) {
                        is NeteaseException -> e
                        is CharacterCodingException -> NeteaseException("InvalidResponse", "网易云响应编码无效")
                        is IOException -> networkError(e)
                        else -> NeteaseException("InvalidResponse", "网易云返回了无效响应")
                    }
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun parseResponse(response: Response): JSONObject {
        if (!response.isSuccessful) throw when (response.code) {
            401 -> NeteaseException("AuthRequired", "请重新登录网易云音乐")
            403 -> NeteaseException("AccessDenied", "网易云暂不允许此请求")
            404 -> NeteaseException("NotFound", "内容不存在或已下架")
            429 -> NeteaseException("RateLimited", "请求过于频繁，请稍后重试")
            else -> NeteaseException("ServiceUnavailable", "网易云服务暂不可用 (HTTP ${response.code})")
        }
        val body = response.body ?: throw NeteaseException("InvalidResponse", "网易云返回了空响应")
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw NeteaseException("PayloadTooLarge", "网易云响应超出大小限制")
        }
        val source = body.source()
        if (source.request(MAX_RESPONSE_BYTES + 1L)) {
            throw NeteaseException("PayloadTooLarge", "网易云响应超出大小限制")
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(source.readByteArray())).toString().removePrefix("\uFEFF")
        val tokener = JSONTokener(text)
        val result = tokener.nextValue()
        if (result !is JSONObject || tokener.nextClean() != '\u0000') {
            throw NeteaseException("InvalidResponse", "网易云返回了无效响应")
        }
        return result
    }

    private fun networkError(error: IOException): NeteaseException = when (error) {
        is InterruptedIOException -> NeteaseException("Timeout", "网易云请求超时，请稍后重试")
        is SSLException -> NeteaseException("NetworkUnavailable", "无法建立网易云安全连接")
        else -> NeteaseException("NetworkUnavailable", "无法连接网易云音乐，请检查网络")
    }

    internal companion object {
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024L
        private val PATH = Regex("/api/[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")
        private const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val ANDROID_USER_AGENT = "NeteaseMusic/9.1.65.240927161425(9001065);Dalvik/2.1.0 (Linux; U; Android 14)"
    }
}
