package app.easepod.plugins

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MediaUriPolicy {
    fun validateSyntax(value: String, allowedHosts: Set<String>): URI {
        require(value.length <= 4096) { "InvalidResponse" }
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("InvalidResponse") }
        require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
            uri.fragment == null && uri.port in setOf(-1, 443)) { "UnsupportedTransport" }
        val host = uri.host?.lowercase() ?: error("InvalidResponse")
        require(host == IDN.toASCII(host) && !host.endsWith('.') && host in allowedHosts.map { it.lowercase() }) { "UnregisteredMediaHost" }
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") &&
            !host.endsWith(".internal") && !host.contains(':')) { "UnsafeMediaAddress" }
        return uri
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255
            return a != 0 && a != 127 && a < 224 && !(a == 100 && b in 64..127) &&
                !(a == 192 && b == 0) && !(a == 198 && b in 18..19) && !(a == 169 && b == 254)
        }
        val first = bytes[0].toInt() and 255
        return first and 0xfe != 0xfc && first in 0x20..0x3f
    }

    suspend fun validate(value: String, allowedHosts: Set<String>): String = withContext(Dispatchers.IO) {
        val uri = validateSyntax(value, allowedHosts)
        val addresses = InetAddress.getAllByName(uri.host)
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "UnsafeMediaAddress" }
        value
    }
}
