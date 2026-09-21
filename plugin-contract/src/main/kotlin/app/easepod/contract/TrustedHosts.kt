package app.easepod.contract

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

object PackageIdentity {
    fun fingerprints(context: Context, packageName: String, history: Boolean = false): Set<String> {
        val info = context.packageManager.getPackageInfo(packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signing = requireNotNull(info.signingInfo)
        val certificates = if (history && !signing.hasMultipleSigners()) signing.signingCertificateHistory
            else signing.apkContentsSigners
        return certificates.map { certificate -> sha256(certificate.toByteArray()) }.toSet()
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Plugin-owned trust store. Approval must follow an explicit action in the plugin's own UI. */
class TrustedHosts(private val context: Context) {
    private val trustFile = AtomicFile(File(context.filesDir, "approved-hosts.json"))
    fun approve(packageName: String, fingerprints: Set<String>) {
        require(fingerprints.isNotEmpty() && PackageIdentity.fingerprints(context, packageName) == fingerprints)
        synchronized(writeLock) { write(read().put(packageName, JSONArray(fingerprints.sorted()))) }
    }
    fun revoke(packageName: String) { synchronized(writeLock) { write(read().apply { remove(packageName) }) } }
    fun isTrusted(uid: Int, expectedPackage: String? = null): Boolean {
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        // Shared UIDs make attribution ambiguous and are outside the contract.
        if (packages.size != 1 || (expectedPackage != null && packages.single() != expectedPackage)) return false
        val packageName = packages.single()
        val approved = read().optJSONArray(packageName)?.let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.orEmpty()
        return approved.isNotEmpty() && runCatching {
            val current = PackageIdentity.fingerprints(context, packageName)
            if (current.size > 1) current == approved
            else approved.any { it in PackageIdentity.fingerprints(context, packageName, true) }
        }.getOrDefault(false)
    }
    private fun read(): JSONObject = runCatching {
        trustFile.openRead().use { input ->
            val bytes = input.readNBytes(64 * 1024 + 1)
            require(bytes.size <= 64 * 1024)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }.getOrElse { JSONObject() }
    private fun write(document: JSONObject) {
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 64 * 1024)
        val output = trustFile.startWrite()
        try { output.write(bytes); trustFile.finishWrite(output) }
        catch (failure: Throwable) { trustFile.failWrite(output); throw failure }
    }
    companion object { private val writeLock = Any() }
}
