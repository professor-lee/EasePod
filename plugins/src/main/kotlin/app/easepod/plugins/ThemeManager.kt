package app.easepod.plugins

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ThemeManager(context: Context, autoLoad: Boolean = true) {
    private data class Version(val current: String, val previous: String? = null)

    private val root = File(context.applicationContext.filesDir, "themes")
    private val versions = File(root, "versions")
    private val registry = AtomicFile(File(root, "registry.json"))
    private val mutex = Mutex()
    private val installed = linkedMapOf<String, Version>()
    private var loaded = false
    private val mutableThemes = MutableStateFlow(BUNDLED_THEMES)
    val themes: StateFlow<List<ThemeInfo>> = mutableThemes.asStateFlow()
    val hasExternalThemeRegistry: Boolean get() = registry.baseFile.exists() || File(registry.baseFile.path + ".bak").exists()

    init {
        if (autoLoad) CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { initialize() }
    }

    suspend fun initialize(): Unit = withContext(Dispatchers.IO) { mutex.withLock { load() } }

    fun palette(id: String): ThemePalette = themes.value.firstOrNull { it.id == id }?.palette ?: BUNDLED_SILVER.palette
    fun theme(id: String): ThemeInfo = themes.value.firstOrNull { it.id == id } ?: BUNDLED_SILVER

    suspend fun inspect(filePath: String): ThemeInfo = withContext(Dispatchers.IO) {
        prepareDirectories()
        val archive = File.createTempFile("inspect-", ".zip", root)
        val staging = File(root, "inspect-${UUID.randomUUID()}")
        try {
            File(filePath).inputStream().use { ThemeArchive.copyArchive(it, archive) }
            check(staging.mkdir()) { "Cannot create theme staging directory" }
            ThemeArchive.extract(archive, staging).copy(assets = ThemeAssets())
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    suspend fun install(filePath: String): ThemeInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val archive = File.createTempFile("install-", ".zip", root)
            val versionId = UUID.randomUUID().toString()
            val staging = File(root, "install-$versionId")
            val destination = File(versions, versionId)
            var committed = false
            try {
                File(filePath).inputStream().use { ThemeArchive.copyArchive(it, archive) }
                check(staging.mkdir()) { "Cannot create theme staging directory" }
                ThemeArchive.extract(archive, staging)
                check(staging.renameTo(destination)) { "Cannot publish theme resources" }
                val info = ThemeArchive.validateInstalled(destination)
                val previous = installed[info.id]
                val next = installed.toMutableMap().apply { put(info.id, Version(versionId, previous?.current)) }
                persist(next)
                committed = true
                installed.clear()
                installed.putAll(next)
                val result = info.copy(hasPrevious = previous != null)
                mutableThemes.value = (mutableThemes.value.filterNot { it.id == info.id } + result).sortedBy { it.id != BUNDLED_SILVER.id }
                previous?.previous?.let { File(versions, it).deleteRecursively() }
                result
            } finally {
                archive.delete()
                staging.deleteRecursively()
                if (!committed) destination.deleteRecursively()
            }
        }
    }

    suspend fun rollback(id: String): ThemeInfo = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            val current = installed[id] ?: error("Theme is not installed")
            val previous = current.previous ?: error("Theme has no previous version")
            val info = ThemeArchive.validateInstalled(File(versions, previous))
            require(info.id == id) { "Theme version identity mismatch" }
            val next = installed.toMutableMap().apply { put(id, Version(previous, current.current)) }
            persist(next)
            installed.clear()
            installed.putAll(next)
            val result = info.copy(hasPrevious = true)
            mutableThemes.value = mutableThemes.value.map { if (it.id == id) result else it }
            result
        }
    }

    suspend fun uninstall(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            load()
            require(BUNDLED_THEMES.none { it.id == id }) { "Bundled theme cannot be uninstalled" }
            val current = installed[id] ?: return@withLock
            val next = installed.toMutableMap().apply { remove(id) }
            persist(next)
            installed.remove(id)
            mutableThemes.value = mutableThemes.value.filterNot { it.id == id }
            File(versions, current.current).deleteRecursively()
            current.previous?.let { File(versions, it).deleteRecursively() }
        }
    }

    private fun prepareDirectories() {
        check(versions.isDirectory || versions.mkdirs() || versions.isDirectory) { "Cannot create theme storage" }
    }

    private fun load() {
        if (loaded) return
        prepareDirectories()
        val recovered = BUNDLED_THEMES.toMutableList()
        runCatching {
            registry.openRead().use { input ->
                val bytes = input.readNBytes(256 * 1024 + 1)
                require(bytes.size <= 256 * 1024) { "Theme registry exceeds limit" }
                val document = JSONObject(bytes.toString(Charsets.UTF_8))
                require(document.getInt("schemaVersion") == 1) { "Unsupported theme registry" }
                val entries = document.getJSONArray("themes")
                require(entries.length() <= 1000) { "Too many installed themes" }
                for (index in 0 until entries.length()) {
                    runCatching {
                        val item = entries.getJSONObject(index)
                        val id = item.getString("id")
                        val current = safeVersionId(item.getString("current"))
                        val previous = if (item.isNull("previous")) null else safeVersionId(item.getString("previous"))
                        val info = ThemeArchive.validateInstalled(File(versions, current))
                        require(info.id == id && id !in installed) { "Theme registry identity mismatch" }
                        val validPrevious = previous?.takeIf { version ->
                            runCatching { ThemeArchive.validateInstalled(File(versions, version)).id == id }.getOrDefault(false)
                        }
                        installed[id] = Version(current, validPrevious)
                        recovered.add(info.copy(hasPrevious = validPrevious != null))
                    }
                }
            }
        }
        mutableThemes.value = recovered
        loaded = true
    }

    private fun safeVersionId(value: String): String {
        require(UUID.fromString(value).toString() == value) { "Invalid theme version directory" }
        return value
    }

    private fun persist(entries: Map<String, Version>) {
        val document = JSONObject().put("schemaVersion", 1).put("themes", JSONArray().apply {
            entries.forEach { (id, version) ->
                put(JSONObject().put("id", id).put("current", version.current).put("previous", version.previous ?: JSONObject.NULL))
            }
        })
        val stream = registry.startWrite()
        try {
            stream.write(document.toString().toByteArray(Charsets.UTF_8))
            registry.finishWrite(stream)
        } catch (failure: Throwable) {
            registry.failWrite(stream)
            throw failure
        }
    }

    companion object {
        val BUNDLED_SILVER = ThemeInfo("silver", "银色 iPod", "1", ThemePalette())
        val BUNDLED_BLACK = ThemeInfo("black", "黑色 iPod", "1", ThemePalette(
            frameTop = 0xff939295, frameBottom = 0xff262527, wheel = 0xff212122, key = 0xffffffff,
            centerTop = 0xff282829, centerBottom = 0xff676467))
        val BUNDLED_OLED = ThemeInfo("oled", "OLED 黑", "1", BUNDLED_BLACK.palette.copy(frameTop = 0xff000000, frameBottom = 0xff000000))
        val BUNDLED_THEMES = listOf(BUNDLED_SILVER, BUNDLED_BLACK, BUNDLED_OLED)
    }
}
