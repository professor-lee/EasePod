package app.easepod.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignedPluginIndexTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keyId = UUID.randomUUID().toString()
    private val now = 1_800_000_000_000L
    private val verifier get() = SignedPluginIndex(context, mapOf(keyId to keys.public))
    private fun document(version: Long = 1, expires: Long = now + 60_000) = JSONObject()
        .put("schemaVersion", 1).put("indexVersion", version).put("issuedAtEpochMs", now - 1000).put("expiresAtEpochMs", expires)
        .put("plugins", JSONArray().put(JSONObject().put("pluginId", "org.example.sample").put("packageName", "org.example.sample")
            .put("versionCode", 1).put("apkSha256", "a".repeat(64)).put("authorSignature", "b".repeat(64))
            .put("downloadUrl", "https://plugins.example.org/sample.apk").put("license", "MIT").put("revoked", false)
            .put("protocol", JSONObject().put("major", 1).put("minMinor", 0).put("maxMinor", 0))))
    private fun signed(document: JSONObject): ByteArray {
        val payload = SignedPluginIndex.canonical(document).toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("Ed25519").run { initSign(keys.private); update(payload); sign() }
        return JSONObject().put("keyId", keyId).put("payload", Base64.getEncoder().encodeToString(payload))
            .put("signature", Base64.getEncoder().encodeToString(signature)).toString().toByteArray(Charsets.UTF_8)
    }

    @Test fun acceptsTrustedSignatureAndRejectsReplayAcrossInstances() = runBlocking {
        val bytes = signed(document())
        assertEquals("org.example.sample", verifier.verify(bytes, now).single().pluginId)
        assertTrue(runCatching { verifier.verify(bytes, now) }.isFailure)
        assertEquals(1, verifier.verify(signed(document(2)), now).size)
    }

    @Test fun rejectsUnknownKeyTamperingAndExpiredIndex() = runBlocking {
        val bytes = signed(document())
        assertTrue(runCatching { SignedPluginIndex(context).verify(bytes, now) }.isFailure)
        val altered = JSONObject(bytes.toString(Charsets.UTF_8)).put("signature", Base64.getEncoder().encodeToString(ByteArray(64))).toString().toByteArray()
        assertTrue(runCatching { verifier.verify(altered, now) }.isFailure)
        assertTrue(runCatching { verifier.verify(signed(document(expires = now - 1)), now) }.isFailure)
    }

    @Test fun requiresExactArtifactIdentityAndHonorsRevocations(): Unit = runBlocking {
        val item = verifier.verify(signed(document()), now).single()
        val candidate = InstallCandidate("apk", "sample", "1", "/unused", "org.example.sample", "b".repeat(64), "a".repeat(64), 1, pluginId = "org.example.sample")
        assertTrue(verifier.match(candidate, item).listed)
        assertThrows(IllegalArgumentException::class.java) { verifier.match(candidate.copy(sha256 = "c".repeat(64)), item) }
        assertThrows(IllegalArgumentException::class.java) { verifier.match(candidate, item.copy(revoked = true)) }
    }
}
