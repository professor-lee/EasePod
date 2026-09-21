package app.easepod.netease

import java.net.URI

/** Host-side copy of the CDN allow-list advertised by the optional网易云 plugin. */
object NeteaseMediaDomains {
    val hosts: Set<String> = NeteasePlugin.networkDomains - "music.163.com"
    fun httpsUrl(value: String?): String? {
        if (value.isNullOrBlank() || value.length > 4096 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host?.lowercase() !in hosts ||
            uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1, 443) ||
            uri.rawPath.isNullOrEmpty() || '\\' in value) return null
        return if (uri.scheme == "http") "https:" + value.substringAfter(':') else value
    }
}
