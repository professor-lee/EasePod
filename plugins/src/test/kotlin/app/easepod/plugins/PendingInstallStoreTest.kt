package app.easepod.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingInstallStoreTest {
    private lateinit var context: Context
    private lateinit var verifier: FakeVerifier
    private lateinit var store: PendingInstallStore
    private val files = mutableListOf<File>()

    @Before fun prepare(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        verifier = FakeVerifier()
        store = PendingInstallStore(context, verifier)
        store.clear()
    }

    @After fun cleanup() = runBlocking { store.clear(); files.forEach(File::delete) }

    @Test fun receiptSurvivesRecreationWithAnExplicitStageAndNoBackupLocation() = runBlocking {
        val original = candidate()
        val saved = store.save(original, InstallStage.AWAITING_PERMISSION)
        assertFalse(saved.listed)
        val restored = PendingInstallStore(context, verifier).restore()!!
        assertEquals(saved, restored.candidate)
        assertEquals(InstallStage.AWAITING_PERMISSION, restored.stage)
        assertFalse(restored.installed)
        assertTrue(restored.fileAvailable)
        assertEquals(2, verifier.inspections)
        val receipt = File(context.noBackupFilesDir, "pending-plugin-install.json")
        assertTrue(receipt.isFile)
        assertFalse(receipt.readText().contains("listed"))
        assertTrue(store.stage(saved, InstallStage.AWAITING_RESULT))
        assertEquals(InstallStage.AWAITING_RESULT, PendingInstallStore(context, verifier).restore()!!.stage)
    }

    @Test fun changedBytesAndChangedParsedIdentityInvalidateTheReceipt() = runBlocking {
        val first = store.save(candidate())
        File(first.filePath).appendText("changed")
        assertNull(store.restore())
        assertNull(store.restore())
        store.save(candidate())
        verifier.rewriteId = "other.plugin"
        assertNull(store.restore())
    }

    @Test fun pathEscapeAndSymlinkAreRejectedWithoutDeletingTheirTargets() = runBlocking {
        val outside = File(context.filesDir, "unrelated-${UUID.randomUUID()}.apk").apply { writeText("preserve") }
        files += outside
        assertRejected { store.save(candidate().copy(filePath = outside.path, sha256 = PluginManager.digest(outside.readBytes()))) }
        val link = File(File(context.cacheDir, "plugin-apks"), "${UUID.randomUUID()}.apk")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        files += link
        assertRejected { store.save(candidate().copy(filePath = link.path, sha256 = PluginManager.digest(outside.readBytes()))) }
        assertTrue(outside.isFile)
        assertEquals("preserve", outside.readText())
    }

    @Test fun missingCacheOnlyRestoresAnExactlyMatchingInstalledPackage() = runBlocking {
        val saved = store.save(candidate(), InstallStage.AWAITING_RESULT)
        verifier.installedCandidate = saved
        File(saved.filePath).delete()
        val restored = store.restore()!!
        assertTrue(restored.installed)
        assertFalse(restored.fileAvailable)
        val next = store.save(candidate(), InstallStage.AWAITING_RESULT)
        verifier.installedCandidate = next.copy(signature = "b".repeat(64))
        File(next.filePath).delete()
        assertNull(store.restore())
    }

    @Test fun staleCallbacksCannotAdvanceOrDeleteANewerCandidate() = runBlocking {
        val first = store.save(candidate())
        val second = store.save(candidate())
        assertFalse(store.stage(first, InstallStage.AWAITING_RESULT))
        assertFalse(store.clear(first))
        assertEquals(second, store.restore()!!.candidate)
        assertTrue(File(second.filePath).isFile)
        assertTrue(store.clear(second))
        assertFalse(File(second.filePath).exists())
        assertNull(store.restore())
    }

    @Test fun malformedReceiptIsDiscardedWithoutUsingItsFilePath() = runBlocking {
        val outside = File(context.filesDir, "unrelated-${UUID.randomUUID()}").apply { writeText("preserve") }
        files += outside
        File(context.noBackupFilesDir, "pending-plugin-install.json").writeText("{\"filePath\":\"${outside.path}\"}")
        assertNull(store.restore())
        assertTrue(outside.isFile)
    }

    @Test fun themeReceiptsGainAnIdentityAndHashButNeverClaimAnApkInstallResult() = runBlocking {
        val file = File(context.cacheDir, "plugin-review-${System.nanoTime()}").apply { writeText("theme archive") }
        files += file
        val candidate = InstallCandidate("theme", "Theme", "1", file.path)
        val saved = store.save(candidate)
        assertEquals("theme.example", saved.pluginId)
        assertEquals(PluginManager.digest(file.readBytes()), saved.sha256)
        verifier.installedCandidate = saved
        assertFalse(store.restore()!!.installed)
        file.delete()
        assertNull(store.restore())
    }

    @Test fun declaredChecksumMismatchDoesNotCreateAReceipt() = runBlocking {
        assertRejected { store.save(candidate().copy(sha256 = "b".repeat(64))) }
        assertNull(store.restore())
    }

    private fun candidate(): InstallCandidate {
        val directory = File(context.cacheDir, "plugin-apks").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.apk").apply { writeText("fixture bytes ${UUID.randomUUID()}") }
        files += file
        return InstallCandidate("apk", "Example", "1", file.path, "org.example.plugin", "a".repeat(64), PluginManager.digest(file.readBytes()), 1, listed = true, pluginId = "example.plugin")
    }

    private suspend fun assertRejected(block: suspend () -> Unit) { assertNotNull(runCatching { block() }.exceptionOrNull()) }

    private class FakeVerifier : InstallIdentityVerifier {
        var inspections = 0
        var rewriteId: String? = null
        var installedCandidate: InstallCandidate? = null
        override suspend fun inspect(candidate: InstallCandidate): InstallCandidate {
            inspections++
            return candidate.copy(pluginId = rewriteId ?: if (candidate.kind == "theme") "theme.example" else candidate.pluginId)
        }
        override fun installed(candidate: InstallCandidate): Boolean = installedCandidate?.let {
            it.packageName == candidate.packageName && it.pluginId == candidate.pluginId && it.versionCode == candidate.versionCode && it.signature == candidate.signature
        } == true
    }
}
