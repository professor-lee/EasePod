package app.easepod.playback

import app.easepod.core.LibraryRepository
import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.QueueStore
import app.easepod.core.SettingsRepository
import app.easepod.core.Track
import java.security.MessageDigest

data class OfflineGrant(
    val expiresAtMs: Long? = null,
    val expectedBytes: Long? = null,
    val sha256: String? = null,
) {
    fun valid(now: Long = System.currentTimeMillis()): Boolean = expiresAtMs == null || expiresAtMs > now
}

data class PlayableSource(
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val allowStreamCache: Boolean = false,
    val offlineGrant: OfflineGrant? = null,
    val allowedHosts: Set<String> = emptySet(),
    val expiresAtMs: Long? = null,
    val canSeek: Boolean = true,
    val quality: String = "原始",
    val revision: String? = null,
)

fun interface StreamResolver {
    suspend fun resolve(track: Track): PlayableSource

    // A resolver must explicitly revalidate account and offline permission to enable downloads.
    suspend fun offlinePolicy(track: Track): OfflineGrant? = null
}

data class PlaybackDependencies(
    val library: LibraryRepository,
    val queueStore: QueueStore,
    val settings: SettingsRepository,
    val streamResolver: StreamResolver = StreamResolver { throw SourceUnavailableException("音乐服务尚未启用") },
    val sourceAvailable: (Track) -> Boolean = { it.sourceId == LOCAL_SOURCE },
    val awaitSourceInitialization: suspend () -> Unit = {},
)

class SourceUnavailableException(message: String) : java.io.IOException(message)

internal fun stableMediaKey(track: Track): String = MessageDigest.getInstance("SHA-256")
    .digest(listOf(track.sourceId, track.accountScope, track.remoteId).joinToString("\u0000").toByteArray())
    .joinToString("") { "%02x".format(it) }

internal fun streamAccountPrefix(sourceId: String, accountScope: String): String = "v2:${mediaDigest(sourceId, accountScope)}:"

internal fun streamCacheKey(track: Track, source: PlayableSource): String? {
    if (!source.allowStreamCache || source.revision.isNullOrBlank()) return null
    return streamAccountPrefix(track.sourceId, track.accountScope) + mediaDigest(track.remoteId, source.quality, source.revision)
}

private fun mediaDigest(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun validatedSource(source: PlayableSource): PlayableSource {
    val uri = java.net.URI(source.uri)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "云媒体必须使用 HTTPS 地址" }
    require(source.headers.size <= 16) { "媒体请求头数量超限" }
    val allowed = setOf("authorization", "cookie", "referer", "user-agent", "accept", "origin")
    require(source.headers.all { (key, value) -> key.lowercase() in allowed && value.length <= 8192 && '\n' !in value && '\r' !in value }) { "媒体请求头无效" }
    require(source.allowedHosts.size <= 64 && source.allowedHosts.all { it.matches(Regex("[A-Za-z0-9.-]+")) && !it.startsWith('.') && !it.endsWith('.') }) { "媒体域名范围无效" }
    require(MediaHttpPolicy.hostAllowed(uri.host, source.allowedHosts.ifEmpty { setOf(uri.host) })) { "媒体地址超出来源域名范围" }
    require(MediaHttpPolicy.publicHost(uri.host)) { "媒体地址不可访问本地网络" }
    require(source.expiresAtMs == null || source.expiresAtMs > System.currentTimeMillis()) { "媒体地址已到期，请重新解析" }
    require(source.offlineGrant?.expectedBytes == null || source.offlineGrant.expectedBytes > 0L) { "离线文件长度无效" }
    require(source.offlineGrant?.sha256 == null || source.offlineGrant.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "离线校验和无效" }
    return source
}
