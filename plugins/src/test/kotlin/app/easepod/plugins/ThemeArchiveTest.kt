package app.easepod.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeArchiveTest {
    private lateinit var temporary: File

    @Before fun prepare() {
        temporary = Files.createTempDirectory("theme-test").toFile()
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "themes").deleteRecursively()
    }

    @After fun cleanup() { temporary.deleteRecursively() }

    @Test fun deferredStartupLeavesExternalThemesUnreadUntilExplicitInitialization() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ThemeManager(context).install(archive("deferred").path)
        val recovered = ThemeManager(context, autoLoad = false)
        assertTrue(recovered.hasExternalThemeRegistry)
        assertEquals(ThemeManager.BUNDLED_THEMES, recovered.themes.value)
        assertEquals(ThemeManager.BUNDLED_SILVER, recovered.theme("org.example.theme"))
        recovered.initialize()
        assertEquals("org.example.theme", recovered.theme("org.example.theme").id)
    }

    @Test fun installInspectRollbackAndUninstallArePersistentAndAtomic() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ThemeManager(context)
        val first = archive("first", version = "1")
        val second = archive("second", version = "2", tokens = "{\"colors\":{\"frameTop\":\"#123456\"}}")
        assertEquals("1", manager.inspect(first.path).version)
        assertEquals(ThemeManager.BUNDLED_THEMES, manager.themes.value)
        assertFalse(manager.install(first.path).hasPrevious)
        assertTrue(manager.install(second.path).hasPrevious)
        assertEquals(0xff123456, manager.palette("org.example.theme").frameTop)
        val invalid = archive("invalid", extras = listOf(Resource("../escape.json", "{}".toByteArray())))
        assertRejected { manager.install(invalid.path) }
        assertEquals("2", manager.themes.value.last().version)
        val restarted = ThemeManager(context)
        assertEquals("1", restarted.rollback("org.example.theme").version)
        assertEquals(ThemePalette().frameTop, restarted.palette("org.example.theme").frameTop)
        assertEquals("2", restarted.rollback("org.example.theme").version)
        restarted.uninstall("org.example.theme")
        assertEquals(ThemeManager.BUNDLED_THEMES, restarted.themes.value)
        assertEquals(ThemePalette(), restarted.palette("missing"))
        assertRejected { restarted.uninstall(ThemeManager.BUNDLED_SILVER.id) }
        assertRejected { restarted.uninstall(ThemeManager.BUNDLED_BLACK.id) }
        assertRejected { restarted.uninstall(ThemeManager.BUNDLED_OLED.id) }
    }

    @Test fun rejectsTraversalAbsolutePathsDuplicatesAndCaseCollisions() {
        listOf("/tmp/a.json", "../a.json", "assets/../a.json", "assets//a.json", "assets/./a.json", "C:/a.json", "assets\\a.json", "assets/a.json/", "assets/a .json.").forEach { path ->
            rejectsArchive(archive(path.hashCode().toString(), extras = listOf(Resource(path, "{}".toByteArray()))))
        }
        rejectsArchive(archive("duplicate", extras = listOf(Resource("tokens.json", "{}".toByteArray()))))
        rejectsArchive(archive("case", extras = listOf(Resource("assets/A.json", "{}".toByteArray()), Resource("assets/a.json", "{}".toByteArray()))))
        rejectsArchive(archive("parentCase", extras = listOf(Resource("assets/A/a.json", "{}".toByteArray()), Resource("assets/a/b.json", "{}".toByteArray()))))
        rejectsArchive(archive("parentFile", extras = listOf(Resource("assets/a.json", "{}".toByteArray()), Resource("assets/a.json/b.json", "{}".toByteArray()))))
    }

    @Test fun rejectsLinksExecutablesUnsupportedTokensAndMalformedJson() {
        rejectsArchive(archive("symlink", extras = listOf(Resource("assets/link.json", "../../outside".toByteArray(), 0xa1ff))))
        listOf("dex", "js", "html", "svg", "ttf", "so").forEach { extension ->
            rejectsArchive(archive(extension, extras = listOf(Resource("assets/a.$extension", byteArrayOf(1)))))
        }
        listOf("{\"colors\":{},\"colors\":{}}", "{/* comment */}", "{\"script\":\"alert(1)\"}", "{\"colors\":{\"unknown\":\"#123456\"}}", "{} trailing").forEachIndexed { index, tokens ->
            rejectsArchive(archive("json$index", tokens = tokens))
        }
        rejectsArchive(archive("reserved", id = "theme.bundled.silver"))
    }

    @Test fun enforcesActualExpandedSizeEntryCountAndJsonSize() {
        rejectsArchive(archive("many", extras = (1..199).map { Resource("assets/$it.json", "{}".toByteArray()) }))
        rejectsArchive(archive("expanded", extras = listOf(Resource("assets/bomb.png", ByteArray(31 * 1024 * 1024)))))
        rejectsArchive(archive("jsonLimit", tokens = "{\"font\":\"${"a".repeat(65 * 1024)}\"}"))
        val oversized = File(temporary, "large.zip")
        java.io.RandomAccessFile(oversized, "rw").use { it.setLength(ThemeArchive.MAX_ARCHIVE_BYTES + 1) }
        rejectsArchive(oversized)
    }

    @Test fun invalidColorAndLowContrastFallBackToReadablePalette() {
        val directory = staging()
        val info = ThemeArchive.extract(archive("colors", tokens = "{\"colors\":{\"frameTop\":\"bad\",\"frameBottom\":\"#00112233\",\"wheel\":\"#000000\",\"key\":\"#000000\",\"highlight\":\"#FFFFFF\"},\"font\":\"external\",\"fontSize\":\"tiny\"}"), directory)
        assertEquals(ThemePalette().frameTop, info.palette.frameTop)
        assertEquals(ThemePalette().frameBottom, info.palette.frameBottom)
        assertNotEquals(info.palette.wheel, info.palette.key)
        assertEquals(ThemePalette().highlight, info.palette.highlight)
    }

    @Test fun validatesRealBitmapAndRejectsOversizedDimensions() {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val data = java.io.ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        bitmap.recycle()
        val info = ThemeArchive.extract(archive("bitmap", extras = listOf(Resource("assets/cover.png", data))), staging())
        assertEquals("org.example.theme", info.id)
        rejectsArchive(archive("fake", extras = listOf(Resource("assets/fake.png", "not a bitmap".toByteArray()))))
        rejectsArchive(archive("wrongMime", extras = listOf(Resource("assets/cover.jpg", data))))
        val large = android.graphics.Bitmap.createBitmap(2001, 2000, android.graphics.Bitmap.Config.ARGB_8888)
        val largeBytes = java.io.ByteArrayOutputStream().apply { large.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        large.recycle()
        rejectsArchive(archive("pixels", extras = listOf(Resource("assets/cover.png", largeBytes))))
    }

    @Test fun typographyUsesFiniteReadableTokensAndInvalidValuesFallBack() {
        val info = ThemeArchive.extract(archive("typography", tokens = """{"font":"monospace","fontSize":"large","spacing":"relaxed"}"""), staging())
        assertEquals(ThemeTypography(ThemeFont.MONOSPACE, ThemeFontSize.LARGE, ThemeSpacing.RELAXED), info.typography)
        assertEquals(17, info.typography.fontSize.menuSp)
        assertEquals(21, info.typography.fontSize.rootMenuSp)
        assertEquals(38, info.typography.spacing.menuRowDp)
        assertEquals(ThemePalette(), info.palette)
        for ((index, tokens) in listOf("{}", """{"font":"https://example.com/font.ttf","fontSize":4,"spacing":-100}""", """{"font":null,"fontSize":"tiny","spacing":"compressed"}""").withIndex()) {
            val defaults = ThemeArchive.extract(archive("typographyFallback$index", tokens = tokens), staging())
            assertEquals(ThemeTypography(), defaults.typography)
            assertEquals(15, defaults.typography.fontSize.menuSp)
            assertEquals(19, defaults.typography.fontSize.rootMenuSp)
            assertEquals(ThemeAssets(), defaults.assets)
        }
    }

    @Test fun assetSlotsRequireExistingPackagedBitmapsAndKnownSlotNames() {
        for ((index, assets) in listOf(
            """{"shellTexture":"https://example.com/shell.png"}""",
            """{"albumPlaceholder":"../cover.png"}""",
            """{"albumPlaceholder":"/tmp/cover.png"}""",
            """{"albumPlaceholder":"assets/missing.png"}""",
            """{"albumPlaceholder":"tokens.json"}""",
            """{"albumPlaceholder":"assets/extra.json"}""",
            """{"albumPlaceholder":null}""",
            """{"screenBackground":"assets/cover.png"}""",
            "\"assets/cover.png\"",
        ).withIndex()) {
            rejectsArchive(archive("invalidSlot$index", tokens = "{\"assets\":$assets}",
                extras = listOf(Resource("assets/cover.png", png(0xff124578.toInt())), Resource("assets/extra.json", "{}".toByteArray()))))
        }
    }

    @Test fun installedAssetPathsFollowPublishedVersionAndRollback() = runBlocking {
        val manager = ThemeManager(ApplicationProvider.getApplicationContext())
        val tokens = """{"font":"serif","assets":{"shellTexture":"assets/shell.png","albumPlaceholder":"assets/cover.png"}}"""
        val firstPackage = archive("assetsFirst", version = "1", tokens = tokens,
            extras = listOf(Resource("assets/shell.png", png(0xff123456.toInt())), Resource("assets/cover.png", png(0xffabcdef.toInt()))))
        val inspected = manager.inspect(firstPackage.path)
        assertEquals(ThemeAssets(), inspected.assets)
        assertEquals(ThemeFont.SERIF, inspected.typography.font)
        val first = manager.install(firstPackage.path)
        val firstShell = File(first.assets.shellTexture!!)
        assertTrue(firstShell.isFile)
        assertTrue(firstShell.path.contains("/themes/versions/"))
        val firstBytes = firstShell.readBytes()
        val second = manager.install(archive("assetsSecond", version = "2", tokens = tokens,
            extras = listOf(Resource("assets/shell.png", png(0xff876543.toInt())), Resource("assets/cover.png", png(0xfffedcba.toInt())))).path)
        assertNotEquals(first.assets, second.assets)
        assertTrue(File(second.assets.albumPlaceholder!!).isFile)
        assertEquals(second, manager.theme(second.id))
        assertRejected { manager.install(archive("assetsInvalidUpdate", version = "3", tokens = tokens).path) }
        assertEquals(second, manager.theme(second.id))
        val restored = manager.rollback(first.id)
        assertEquals(first.assets, restored.assets)
        assertTrue(firstBytes.contentEquals(File(restored.assets.shellTexture!!).readBytes()))
        assertEquals(first.typography, restored.typography)
    }

    @Test fun rollbackRejectsCorruptedPreviousAssetWithoutChangingCurrentVersion() = runBlocking {
        val manager = ThemeManager(ApplicationProvider.getApplicationContext())
        val tokens = """{"assets":{"albumPlaceholder":"assets/cover.png"}}"""
        val first = manager.install(archive("corruptFirst", version = "1", tokens = tokens,
            extras = listOf(Resource("assets/cover.png", png(0xff123456.toInt())))).path)
        val current = manager.install(archive("corruptSecond", version = "2", tokens = tokens,
            extras = listOf(Resource("assets/cover.png", png(0xffabcdef.toInt())))).path)
        File(first.assets.albumPlaceholder!!).writeText("invalid bitmap")
        assertRejected { manager.rollback(first.id) }
        assertEquals(current, manager.theme(first.id))
        assertTrue(File(current.assets.albumPlaceholder!!).isFile)
    }

    private fun png(color: Int): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private data class Resource(val name: String, val data: ByteArray, val mode: Int? = null)

    private class RawNameEntry(rawName: String, mode: Int?) : ZipArchiveEntry() {
        init {
            setPlatform(PLATFORM_UNIX)
            setName(rawName)
            unixMode = mode ?: if (rawName.endsWith('/')) 0x41ed else 0x81a4
        }
    }

    private fun archive(name: String, version: String = "1", id: String = "org.example.theme", tokens: String = "{}", extras: List<Resource> = emptyList()): File {
        val file = File(temporary, "$name.ep-theme")
        val manifest = "{\"manifestVersion\":1,\"kind\":\"theme\",\"pluginId\":\"$id\",\"displayName\":\"Test Theme\",\"version\":\"$version\"}"
        ZipArchiveOutputStream(file).use { output ->
            (listOf(Resource("manifest.json", manifest.toByteArray()), Resource("tokens.json", tokens.toByteArray())) + extras).forEach { resource ->
                val entry = RawNameEntry(resource.name, resource.mode)
                output.putArchiveEntry(entry)
                output.write(resource.data)
                output.closeArchiveEntry()
            }
        }
        return file
    }

    private fun staging() = File(temporary, java.util.UUID.randomUUID().toString()).apply { mkdir() }

    private fun rejectsArchive(file: File) { assertRejected { ThemeArchive.extract(file, staging()) } }

    private inline fun assertRejected(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected theme validation to reject input", failure != null)
    }
}
