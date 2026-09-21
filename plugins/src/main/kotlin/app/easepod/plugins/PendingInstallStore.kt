package app.easepod.plugins

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.AtomicFile
import app.easepod.contract.Protocol
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class InstallStage { REVIEW, AWAITING_PERMISSION, AWAITING_RESULT }
data class RestoredInstall(val candidate: InstallCandidate, val stage: InstallStage, val installed: Boolean, val fileAvailable: Boolean)

/** A private receipt never authorizes install, trust, or enable by itself. */
class PendingInstallStore internal constructor(
    private val context: Context,
    private val identities: InstallIdentityVerifier,
) {
    constructor(context: Context) : this(context.applicationContext, AndroidInstallIdentityVerifier(context.applicationContext))

    private val receipt = AtomicFile(File(context.noBackupFilesDir, "pending-plugin-install.json"))

    suspend fun save(candidate: InstallCandidate, stage: InstallStage = InstallStage.REVIEW): InstallCandidate = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = candidateFile(candidate)
            val checksum = hash(file, candidate.kind)
            require(candidate.sha256.isBlank() || candidate.sha256 == checksum) { "安装文件已变化" }
            val verified = identities.inspect(candidate.copy(sha256 = checksum, listed = false))
            require(sameIdentity(candidate, verified, allowMissingThemeId = true)) { "安装文件身份已变化" }
            require(hash(file, candidate.kind) == checksum) { "安装文件在核验期间发生变化" }
            val normalized = verified.copy(filePath = file.path, sha256 = checksum, listed = false)
            validate(normalized)
            write(normalized, stage)
            normalized
        }
    }

    suspend fun restore(): RestoredInstall? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val saved = read() ?: return@withLock null
                val candidate = saved.first
                val file = candidateFile(candidate, allowMissing = true)
                val available = file.isFile
                if (available) {
                    require(hash(file, candidate.kind) == candidate.sha256) { "安装文件已变化" }
                    val verified = identities.inspect(candidate)
                    require(sameIdentity(candidate, verified)) { "安装文件身份已变化" }
                    require(hash(file, candidate.kind) == candidate.sha256) { "安装文件在核验期间发生变化" }
                }
                val installed = candidate.kind == "apk" && identities.installed(candidate)
                if (!available && !installed) { receipt.delete(); return@withLock null }
                RestoredInstall(candidate, saved.second, installed, available)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { receipt.delete(); null }
        }
    }

    suspend fun stage(candidate: InstallCandidate, stage: InstallStage): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = read() ?: return@withLock false
            if (!sameReceipt(saved.first, candidate)) return@withLock false
            val file = candidateFile(saved.first)
            require(hash(file, candidate.kind) == candidate.sha256) { "安装文件已变化" }
            write(saved.first, stage)
            true
        }
    }

    suspend fun clear(candidate: InstallCandidate? = null, deleteCandidate: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val saved = runCatching { read()?.first }.getOrNull()
            if (candidate != null && (saved == null || !sameReceipt(saved, candidate))) return@withLock false
            receipt.delete()
            if (deleteCandidate && saved != null) runCatching { candidateFile(saved, allowMissing = true).delete() }
            true
        }
    }

    private fun sameReceipt(a: InstallCandidate, b: InstallCandidate) = a.sha256 == b.sha256 && a.filePath == b.filePath && sameIdentity(a, b)

    private fun sameIdentity(a: InstallCandidate, b: InstallCandidate, allowMissingThemeId: Boolean = false): Boolean =
        a.kind == b.kind && a.packageName == b.packageName && a.version == b.version && a.versionCode == b.versionCode &&
            a.signature == b.signature && (a.pluginId == b.pluginId || allowMissingThemeId && a.kind == "theme" && a.pluginId.isBlank())

    private fun candidateFile(candidate: InstallCandidate, allowMissing: Boolean = false): File {
        val input = File(candidate.filePath)
        require(input.isAbsolute && !Files.isSymbolicLink(input.toPath())) { "安装文件路径无效" }
        val file = input.canonicalFile
        when (candidate.kind) {
            "apk" -> {
                val directory = File(context.cacheDir, "plugin-apks")
                require(!Files.isSymbolicLink(directory.toPath()) && file.parentFile == directory.canonicalFile && file.name.endsWith(".apk"))
            }
            "theme" -> require(file.parentFile == context.cacheDir.canonicalFile && file.name.matches(Regex("plugin-review-[0-9]+")))
            else -> error("未知安装文件类型")
        }
        require(!file.exists() && allowMissing || file.isFile) { "安装文件不存在" }
        return file
    }

    private suspend fun hash(file: File, kind: String): String {
        val maximum = if (kind == "theme") ThemeArchive.MAX_ARCHIVE_BYTES else 100L * 1024 * 1024
        require(file.length() in 1..maximum) { "安装文件大小无效" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                total += count
                require(total <= maximum) { "安装文件过大" }
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validate(candidate: InstallCandidate) {
        require(candidate.name.length in 1..128 && candidate.version.length <= 128 && candidate.filePath.length <= 4096)
        require(listOf(candidate.name, candidate.version, candidate.filePath).none { '\u0000' in it })
        require(candidate.sha256.matches(Regex("[a-f0-9]{64}")))
        require(candidate.pluginId.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{2,127}")))
        if (candidate.kind == "apk") {
            require(candidate.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")))
            require(candidate.signature.matches(Regex("[a-f0-9]{64}(?:,[a-f0-9]{64})*")) && candidate.signature.length <= 1024)
            require(candidate.versionCode > 0)
        } else require(candidate.kind == "theme" && candidate.packageName.isBlank() && candidate.signature.isBlank() && candidate.versionCode == 0L)
    }

    private fun read(): Pair<InstallCandidate, InstallStage>? {
        if (!receipt.baseFile.exists() && !File(receipt.baseFile.path + ".bak").exists()) return null
        val json = receipt.openRead().use { input ->
            val bytes = input.readNBytes(16 * 1024 + 1)
            require(bytes.size <= 16 * 1024)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        require(json.getInt("receiptVersion") == 1)
        val candidate = InstallCandidate(json.getString("kind"), json.getString("name"), json.getString("version"), json.getString("filePath"),
            json.getString("packageName"), json.getString("signature"), json.getString("sha256"), json.getLong("versionCode"), pluginId = json.getString("pluginId"))
        validate(candidate)
        return candidate to InstallStage.valueOf(json.getString("stage"))
    }

    private fun write(candidate: InstallCandidate, stage: InstallStage) {
        val json = JSONObject().put("receiptVersion", 1).put("kind", candidate.kind).put("name", candidate.name).put("version", candidate.version)
            .put("filePath", candidate.filePath).put("packageName", candidate.packageName).put("signature", candidate.signature)
            .put("sha256", candidate.sha256).put("versionCode", candidate.versionCode).put("pluginId", candidate.pluginId).put("stage", stage.name)
        check(context.noBackupFilesDir.isDirectory || context.noBackupFilesDir.mkdirs())
        val output = receipt.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); receipt.finishWrite(output) }
        catch (failure: Exception) { receipt.failWrite(output); throw failure }
    }

    private companion object { val lock = Mutex() }
}

internal interface InstallIdentityVerifier {
    suspend fun inspect(candidate: InstallCandidate): InstallCandidate
    fun installed(candidate: InstallCandidate): Boolean
}

internal class AndroidInstallIdentityVerifier(private val context: Context) : InstallIdentityVerifier {
    private val pm = context.packageManager
    override suspend fun inspect(candidate: InstallCandidate): InstallCandidate {
        if (candidate.kind == "theme") {
            val staging = File.createTempFile("theme-receipt-", ".tmp", context.cacheDir)
            check(staging.delete() && staging.mkdir())
            try {
                val info = ThemeArchive.extract(File(candidate.filePath), staging)
                return candidate.copy(name = info.name, version = info.version, pluginId = info.id)
            } finally { staging.deleteRecursively() }
        }
        val pack = pm.getPackageArchiveInfo(candidate.filePath, FLAGS) ?: error("安装 APK 无效")
        val application = requireNotNull(pack.applicationInfo).apply { sourceDir = candidate.filePath; publicSourceDir = candidate.filePath }
        val manifest = manifest(pack, application)
        return candidate.copy(name = manifest.getString("displayName"), version = pack.versionName.orEmpty(), packageName = pack.packageName,
            versionCode = pack.longVersionCode, signature = signature(pack), pluginId = manifest.getString("pluginId"))
    }

    override fun installed(candidate: InstallCandidate): Boolean = try {
        val pack = pm.getPackageInfo(candidate.packageName, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
        val manifest = manifest(pack, requireNotNull(pack.applicationInfo))
        pack.packageName == candidate.packageName && pack.longVersionCode == candidate.versionCode &&
            pack.versionName.orEmpty() == candidate.version && signature(pack) == candidate.signature && manifest.getString("pluginId") == candidate.pluginId
    } catch (_: Exception) { false }

    private fun manifest(pack: PackageInfo, application: android.content.pm.ApplicationInfo): JSONObject {
        val service = pack.services?.filter { it.exported && it.metaData?.containsKey(Protocol.MANIFEST_META) == true }?.singleOrNull()
            ?: error("APK 必须声明一个音乐插件服务")
        val manifest = pm.getResourcesForApplication(application).openRawResource(service.metaData.getInt(Protocol.MANIFEST_META)).use { input ->
            val bytes = input.readNBytes(16 * 1024 + 1)
            require(bytes.size <= 16 * 1024)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        require(manifest.getInt("manifestVersion") == 1 && manifest.getString("kind") == "music-service" &&
            manifest.getString("packageName") == pack.packageName && manifest.getString("serviceClass") == service.name &&
            manifest.getLong("versionCode") == pack.longVersionCode) { "APK 插件清单与包身份不一致" }
        return manifest
    }

    private fun signature(pack: PackageInfo) = requireNotNull(pack.signingInfo).apkContentsSigners.map { PluginManager.digest(it.toByteArray()) }.sorted().joinToString(",")
    private companion object { val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SERVICES or PackageManager.GET_META_DATA }
}
