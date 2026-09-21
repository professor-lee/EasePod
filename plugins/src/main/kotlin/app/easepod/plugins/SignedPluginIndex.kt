package app.easepod.plugins

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

data class IndexedPlugin(val pluginId: String, val packageName: String, val versionCode: Long,
    val apkSha256: String, val authorSignature: String, val downloadUrl: String,
    val license: String, val revoked: Boolean, val protocolMajor: Int, val minMinor: Int, val maxMinor: Int)

/** Optional distribution verifier. Trusted public keys are supplied by the host build, never the index. */
class SignedPluginIndex(context: Context, private val trustedKeys: Map<String, PublicKey> = emptyMap()) {
    private val dao = PluginDatabase.create(context.applicationContext).dao()

    suspend fun verify(bytes: ByteArray, nowEpochMs: Long = System.currentTimeMillis()): List<IndexedPlugin> =
        withContext(Dispatchers.IO) { verificationLock.withLock {
            require(bytes.size <= 512 * 1024) { "IndexTooLarge" }
            val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
            require(envelope.keys().asSequence().toSet() == setOf("keyId", "payload", "signature")) { "InvalidIndexEnvelope" }
            val keyId = envelope.getString("keyId")
            val key = trustedKeys[keyId] ?: error("UntrustedIndexKey")
            val payload = Base64.getDecoder().decode(envelope.getString("payload"))
            val signature = Base64.getDecoder().decode(envelope.getString("signature"))
            require(Signature.getInstance("Ed25519").run { initVerify(key); update(payload); verify(signature) }) { "InvalidIndexSignature" }
            val document = JSONObject(payload.toString(Charsets.UTF_8))
            require(payload.contentEquals(canonical(document).toByteArray(Charsets.UTF_8))) { "NonCanonicalIndex" }
            require(document.getInt("schemaVersion") == 1) { "IncompatibleIndex" }
            val version = document.getLong("indexVersion")
            val issued = document.getLong("issuedAtEpochMs")
            val expires = document.getLong("expiresAtEpochMs")
            require(version > 0 && issued <= nowEpochMs + 300_000 && expires > nowEpochMs && expires > issued) { "ExpiredIndex" }
            require(version > (dao.indexState(keyId)?.highestVersion ?: 0)) { "IndexReplay" }
            val items = document.getJSONArray("plugins")
            require(items.length() <= 1000) { "IndexTooLarge" }
            val plugins = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val protocol = item.getJSONObject("protocol")
                IndexedPlugin(item.getString("pluginId"), item.getString("packageName"), item.getLong("versionCode"),
                    item.getString("apkSha256"), item.getString("authorSignature"), item.getString("downloadUrl"),
                    item.getString("license"), item.getBoolean("revoked"), protocol.getInt("major"),
                    protocol.getInt("minMinor"), protocol.getInt("maxMinor")).also {
                    require(it.pluginId.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{2,127}")) && it.packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]{2,255}")))
                    require(it.versionCode > 0 && it.apkSha256.matches(Regex("[0-9a-f]{64}")) && it.authorSignature.matches(Regex("[0-9a-f]{64}")))
                    require(it.license.length in 1..1024 && it.minMinor >= 0 && it.maxMinor >= it.minMinor)
                    val url = URI(it.downloadUrl)
                    require(url.scheme == "https" && url.host != null && url.userInfo == null && url.fragment == null && url.port in setOf(-1, 443))
                }
            }
            require(plugins.map { it.pluginId }.distinct().size == plugins.size) { "DuplicateIndexIdentity" }
            dao.putIndex(PluginIndexState(keyId, version))
            plugins
        } }

    fun match(candidate: InstallCandidate, item: IndexedPlugin): InstallCandidate {
        require(!item.revoked) { "RevokedPlugin" }
        require(item.protocolMajor == 1 && item.minMinor == 0) { "IncompatibleProtocol" }
        require(candidate.pluginId == item.pluginId && candidate.packageName == item.packageName && candidate.versionCode == item.versionCode &&
            candidate.sha256 == item.apkSha256 && candidate.signature == item.authorSignature) { "IndexArtifactMismatch" }
        return candidate.copy(listed = true)
    }

    companion object {
        private val verificationLock = Mutex()
        // This schema accepts only integral numeric fields, avoiding floating-point canonicalization ambiguity.
        fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key -> "${JSONObject.quote(key)}:${canonical(value.get(key))}" }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Boolean, is Int, is Long -> value.toString()
            else -> error("UnsupportedIndexValue")
        }
    }
}
