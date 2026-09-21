package app.easepod.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

internal object MediaHttpPolicy {
    fun hostAllowed(host: String, allowed: Set<String>): Boolean = allowed.any {
        host.equals(it, ignoreCase = true)
    }

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            if (bytes[0] == 0 || bytes[0] >= 224) return false
            if (bytes[0] == 100 && bytes[1] in 64..127) return false
            if (bytes[0] == 198 && bytes[1] in 18..19) return false
        } else if (bytes[0] and 0xfe == 0xfc) return false
        return true
    }

    fun publicHost(host: String): Boolean =
        if (':' in host || host.matches(Regex("[0-9.]+"))) runCatching { publicAddress(InetAddress.getByName(host)) }.getOrDefault(false)
        else true

    @UnstableApi
    fun factory(source: PlayableSource): OkHttpDataSource.Factory {
        val hosts = source.allowedHosts.ifEmpty { setOf(URI(source.uri).host) }
        val client = baseClient.newBuilder().addInterceptor { chain ->
            var request = chain.request()
            var redirects = 0
            while (true) {
                if (!request.url.isHttps || request.url.username.isNotEmpty() || request.url.password.isNotEmpty() ||
                    !hostAllowed(request.url.host, hosts)) throw SourceUnavailableException("媒体重定向超出来源授权范围")
                if (!publicHost(request.url.host)) throw SourceUnavailableException("媒体地址不可访问本地网络")
                val response = chain.proceed(request)
                if (response.code !in setOf(301, 302, 303, 307, 308)) return@addInterceptor response
                val location = response.header("Location")
                val redirected = location?.let(request.url::resolve)
                response.close()
                if (redirected == null || ++redirects > 5) throw IOException("媒体重定向无效")
                val next = request.newBuilder().url(redirected)
                if (redirected.host != request.url.host || redirected.port != request.url.port) {
                    source.headers.keys.forEach(next::removeHeader)
                    next.removeHeader("Authorization").removeHeader("Cookie")
                }
                request = next.build()
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("媒体重定向无效")
        }.build()
        return OkHttpDataSource.Factory(client).setDefaultRequestProperties(source.headers)
    }

    private val baseClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) {
                    throw SourceUnavailableException("媒体地址不可访问本地网络")
                }
            }
        }).build()
}
