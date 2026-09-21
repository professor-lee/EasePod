package app.easepod

import app.easepod.plugins.MediaUriPolicy
import coil.ImageLoader
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal class ArtworkLoader(private val app: EasePodApplication) {
    @Volatile var networkingEnabled = false
        set(value) {
            field = value
            if (!value) client.dispatcher.cancelAll()
        }
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !MediaUriPolicy.isPublicAddress(it) }) throw IOException("Artwork address is not public")
            }
        })
        .addInterceptor { chain ->
            var request = chain.request()
            var redirects = 0
            while (true) {
                if (!networkingEnabled) throw IOException("Artwork networking is disabled")
                val hosts = app.plugins.plugins.value.filter { it.enabled }.flatMap { it.networkDomains }.toSet()
                try {
                    MediaUriPolicy.validateSyntax(request.url.toString(), hosts)
                    require(request.url.query == null)
                    if (request.url.host.matches(Regex("[0-9.]+"))) require(MediaUriPolicy.isPublicAddress(InetAddress.getByName(request.url.host)))
                } catch (error: IllegalArgumentException) { throw IOException("Artwork address is not allowed", error) }
                val response = chain.proceed(request)
                if (response.code !in setOf(301, 302, 303, 307, 308)) return@addInterceptor response
                val next = response.header("Location")?.let(request.url::resolve)
                response.close()
                if (next == null || ++redirects > 5) throw IOException("Invalid artwork redirect")
                request = request.newBuilder().url(next).removeHeader("Authorization").removeHeader("Cookie").build()
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Invalid artwork redirect")
        }.build()

    val images: ImageLoader by lazy { ImageLoader.Builder(app).okHttpClient(client).respectCacheHeaders(true).build() }
}
