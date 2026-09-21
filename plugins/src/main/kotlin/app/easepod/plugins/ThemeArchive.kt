package app.easepod.plugins

import android.graphics.BitmapFactory
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object ThemeArchive {
    const val MAX_ARCHIVE_BYTES = 10L * 1024 * 1024
    const val MAX_EXPANDED_BYTES = 30L * 1024 * 1024
    const val MAX_ENTRIES = 200
    const val MAX_PIXELS = 4_000_000L
    private const val MAX_JSON_BYTES = 64 * 1024
    private val idPattern = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")
    private val pathPattern = Regex("[A-Za-z0-9_. /-]+")
    private val bitmapExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
    private val colorNames = setOf("frameTop", "frameBottom", "wheel", "key", "centerTop", "centerBottom", "highlight")

    fun copyArchive(input: InputStream, destination: File) {
        destination.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_ARCHIVE_BYTES) { "Theme archive exceeds 10 MiB" }
                output.write(buffer, 0, read)
            }
            require(total > 0) { "Theme archive is empty" }
        }
    }

    fun extract(archive: File, directory: File): ThemeInfo {
        require(archive.length() in 1..MAX_ARCHIVE_BYTES) { "Theme archive exceeds 10 MiB or is empty" }
        require(directory.isDirectory && directory.list().orEmpty().isEmpty()) { "Theme staging directory must be empty" }
        var total = 0L
        val seen = mutableMapOf<String, Pair<String, Boolean>>()
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(++count <= MAX_ENTRIES) { "Theme archive contains too many entries" }
                require(!entry.isUnixSymlink && entry.unixMode.and(0xf000) in setOf(0, 0x4000, 0x8000)) { "Theme links and special files are forbidden" }
                require(zip.canReadEntryData(entry)) { "Unsupported or encrypted theme entry" }
                val path = validatePath(entry.name, entry.isDirectory)
                registerPath(path, entry.isDirectory, seen)
                val output = File(directory, path)
                require(output.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Theme path escapes staging directory" }
                if (entry.isDirectory) {
                    require(entry.size <= 0) { "Theme directory contains data" }
                    require(output.isDirectory || output.mkdirs()) { "Cannot create theme directory" }
                    continue
                }
                require(entry.size <= MAX_EXPANDED_BYTES) { "Theme entry exceeds expanded size limit" }
                require(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs()) { "Cannot create theme directory" }
                var entryBytes = 0L
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { target ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            entryBytes += read
                            require(total <= MAX_EXPANDED_BYTES) { "Theme expands beyond 30 MiB" }
                            if (path.endsWith(".json")) require(entryBytes <= MAX_JSON_BYTES) { "Theme JSON exceeds 64 KiB" }
                            crc.update(buffer, 0, read)
                            target.write(buffer, 0, read)
                        }
                    }
                }
                require(entryBytes == entry.size && crc.value == entry.crc) { "Theme resource checksum or size mismatch" }
                if (path.endsWith(".json")) readJson(output) else validateBitmap(output)
            }
        }
        return readInfo(directory)
    }

    fun validateInstalled(directory: File): ThemeInfo {
        require(directory.isDirectory) { "Theme version is missing" }
        var size = 0L
        var count = 0
        val seen = mutableMapOf<String, Pair<String, Boolean>>()
        directory.walkTopDown().drop(1).forEach { file ->
            require(++count <= MAX_ENTRIES) { "Theme contains too many entries" }
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "Theme links are forbidden" }
            val path = validatePath(file.relativeTo(directory).invariantSeparatorsPath + if (file.isDirectory) "/" else "", file.isDirectory)
            registerPath(path, file.isDirectory, seen)
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Theme path escapes its version" }
            if (file.isFile) {
                size += file.length()
                require(size <= MAX_EXPANDED_BYTES) { "Theme exceeds 30 MiB" }
                if (path.endsWith(".json")) readJson(file) else validateBitmap(file)
            }
        }
        return readInfo(directory)
    }

    private fun validatePath(name: String, directory: Boolean): String {
        require(name.length in 1..240 && pathPattern.matches(name)) { "Invalid theme path" }
        require(!name.startsWith('/') && !name.contains('\\') && !name.contains(':')) { "Absolute theme paths are forbidden" }
        val path = if (directory) name.removeSuffix("/") else name
        val segments = path.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && it == it.trim() && !it.endsWith('.') }) { "Invalid theme path segment" }
        if (directory) {
            require(segments.first() == "assets") { "Only assets directories are supported" }
        } else {
            require(path == "manifest.json" || path == "tokens.json" ||
                (segments.size > 1 && segments.first() == "assets" && (path.endsWith(".json") || path.substringAfterLast('.').lowercase(Locale.ROOT) in bitmapExtensions))) {
                "Theme contains an unsupported resource"
            }
        }
        return path
    }

    private fun registerPath(path: String, directory: Boolean, seen: MutableMap<String, Pair<String, Boolean>>) {
        val normalized = path.lowercase(Locale.ROOT)
        require(normalized !in seen) { "Duplicate or colliding theme path" }
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            val known = seen[parent.lowercase(Locale.ROOT)]
            require(known == null || (known.first == parent && known.second)) { "Theme file collides with a directory" }
            require(seen.values.none { it.first.startsWith(parent, ignoreCase = true) && it.first.length > parent.length && it.first[parent.length] == '/' && !it.first.startsWith("$parent/") }) { "Theme parent directories collide" }
            parent = parent.substringBeforeLast('/', "")
        }
        require(directory || seen.keys.none { it.startsWith("$normalized/") }) { "Theme file collides with a directory" }
        require(seen.values.none { it.first.startsWith("$path/", ignoreCase = true) && !it.first.startsWith("$path/") }) { "Theme directories collide" }
        seen[normalized] = path to directory
    }

    private fun validateBitmap(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= MAX_PIXELS) { "Theme bitmap is invalid or exceeds 4 million pixels" }
        val expected = when (file.extension.lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> error("Unsupported bitmap")
        }
        require(bounds.outMimeType == expected) { "Theme bitmap content does not match its extension" }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        requireNotNull(bitmap) { "Theme bitmap cannot be decoded" }
        bitmap.recycle()
    }

    private fun readInfo(directory: File): ThemeInfo {
        val manifest = readJson(File(directory, "manifest.json"))
        require(manifest.keys().asSequence().toSet().all { it in setOf("manifestVersion", "kind", "pluginId", "displayName", "version") }) { "Unsupported theme manifest field" }
        require(manifest.opt("manifestVersion") == 1 && manifest.opt("kind") == "theme") { "Unsupported theme manifest version or kind" }
        val id = manifest.opt("pluginId") as? String ?: error("Theme pluginId is required")
        require(id.length <= 128 && idPattern.matches(id) && !id.startsWith("theme.bundled.") && !id.startsWith("core.")) { "Invalid or reserved theme ID" }
        val name = manifest.opt("displayName") as? String ?: error("Theme displayName is required")
        require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl)) { "Invalid theme display name" }
        val version = manifest.opt("version") as? String ?: error("Theme version is required")
        require(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}").matches(version)) { "Invalid theme version" }
        val tokens = readJson(File(directory, "tokens.json"))
        require(tokens.keys().asSequence().toSet().all { it in setOf("colors", "font", "fontSize", "spacing", "assets") }) { "Unsupported theme token" }
        return ThemeInfo(id, name, version, parsePalette(tokens), typography = parseTypography(tokens), assets = parseAssets(tokens, directory))
    }

    private fun parsePalette(tokens: JSONObject): ThemePalette {
        val colors = tokens.optJSONObject("colors") ?: JSONObject()
        require(colors.keys().asSequence().toSet().all { it in colorNames }) { "Unsupported theme color token" }
        val defaults = ThemePalette()
        fun color(name: String, fallback: Long): Long {
            val value = colors.opt(name) as? String ?: return fallback
            if (!Regex("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?").matches(value)) return fallback
            val parsed = value.drop(1).toLong(16).let { if (value.length == 7) it or 0xff000000 else it }
            return parsed.takeIf { it ushr 24 == 0xffL } ?: fallback
        }
        val wheel = color("wheel", defaults.wheel)
        val requestedKey = color("key", defaults.key)
        val key = if (contrast(wheel, requestedKey) >= 3.0) requestedKey else if (contrast(wheel, 0xff000000) >= 3.0) 0xff000000 else 0xffffffff
        val requestedHighlight = color("highlight", defaults.highlight)
        return ThemePalette(
            color("frameTop", defaults.frameTop), color("frameBottom", defaults.frameBottom), wheel, key,
            color("centerTop", defaults.centerTop), color("centerBottom", defaults.centerBottom),
            requestedHighlight.takeIf { contrast(it, 0xffffffff) >= 3.0 } ?: defaults.highlight,
        )
    }

    private fun parseTypography(tokens: JSONObject): ThemeTypography = ThemeTypography(
        font = when (tokens.opt("font")) {
            "sans" -> ThemeFont.SANS
            "serif" -> ThemeFont.SERIF
            "monospace" -> ThemeFont.MONOSPACE
            else -> ThemeFont.SYSTEM
        },
        fontSize = when (tokens.opt("fontSize")) {
            "large" -> ThemeFontSize.LARGE
            else -> ThemeFontSize.STANDARD
        },
        spacing = when (tokens.opt("spacing")) {
            "relaxed" -> ThemeSpacing.RELAXED
            else -> ThemeSpacing.STANDARD
        },
    )

    private fun parseAssets(tokens: JSONObject, directory: File): ThemeAssets {
        if (!tokens.has("assets")) return ThemeAssets()
        val assets = tokens.optJSONObject("assets") ?: error("Theme assets must be an object")
        require(assets.keys().asSequence().toSet().all { it in setOf("shellTexture", "albumPlaceholder") }) { "Unsupported theme asset slot" }
        fun asset(slot: String): String? {
            if (!assets.has(slot)) return null
            val path = assets.opt(slot) as? String ?: error("Theme asset path must be a string")
            validatePath(path, false)
            require(path.startsWith("assets/") && path.substringAfterLast('.').lowercase(Locale.ROOT) in bitmapExtensions) { "Theme slot requires a packaged bitmap" }
            val file = File(directory, path)
            require(file.isFile && file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Theme asset is missing or outside its version" }
            return file.canonicalPath
        }
        return ThemeAssets(shellTexture = asset("shellTexture"), albumPlaceholder = asset("albumPlaceholder"))
    }

    private fun contrast(first: Long, second: Long): Double {
        fun luminance(color: Long): Double {
            fun channel(shift: Int): Double {
                val value = ((color shr shift) and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    internal fun readJson(file: File): JSONObject {
        require(file.isFile && file.length() in 2..MAX_JSON_BYTES.toLong()) { "Theme JSON is missing or exceeds 64 KiB" }
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val source = decoder.decode(java.nio.ByteBuffer.wrap(file.readBytes())).toString()
        // JsonReader enforces JSON syntax; Android JSONObject alone accepts comments and duplicate keys.
        JsonReader(StringReader(source)).use { reader ->
            reader.isLenient = false
            validateJsonValue(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing theme JSON content" }
        }
        return JSONObject(source)
    }

    private fun validateJsonValue(reader: JsonReader, depth: Int) {
        require(depth <= 12) { "Theme JSON is nested too deeply" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    require(names.add(reader.nextName())) { "Duplicate theme JSON field" }
                    validateJsonValue(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) validateJsonValue(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> error("Invalid theme JSON")
        }
    }
}
