package app.easepod.data

import app.easepod.core.AppSettings
import app.easepod.core.LOCAL_SOURCE
import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object DataJson {
    fun track(row: TrackRow): JSONObject = JSONObject().apply {
        put("id", row.id); put("rootId", row.rootId); put("documentId", row.documentId); put("scanId", row.scanId)
        put("title", row.title); put("artist", row.artist); put("genre", row.genre); put("durationMs", row.durationMs)
        put("artworkUri", row.artworkUri); put("contentUri", row.contentUri); put("available", row.available)
        put("sourceId", row.sourceId); put("accountScope", row.accountScope); put("remoteId", row.remoteId)
        put("discNumber", row.discNumber); put("trackNumber", row.trackNumber); put("fileName", row.fileName)
        put("albumTitle", row.snapshotAlbumTitle)
    }
    fun track(json: JSONObject) = TrackRow(json.getString("id"), json.nullable("rootId"), json.nullable("documentId"), json.nullable("scanId"),
        json.getString("title"), json.getString("artist"), json.getString("genre"), json.optLong("durationMs", -1).takeIf { it >= 0 },
        json.nullable("artworkUri"), json.nullable("contentUri"), json.getBoolean("available"), json.getString("sourceId"),
        json.getString("accountScope"), json.getString("remoteId"), json.optInt("discNumber"), json.optInt("trackNumber"), json.optString("fileName"), json.nullable("albumTitle"))
    fun album(row: AlbumRow) = JSONObject().apply {
        put("id", row.id); put("rootId", row.rootId); put("documentId", row.documentId); put("scanId", row.scanId)
        put("title", row.title); put("artist", row.artist); put("artworkUri", row.artworkUri)
    }
    fun album(json: JSONObject) = AlbumRow(json.getString("id"), json.getString("rootId"), json.getString("documentId"), json.getString("scanId"), json.getString("title"), json.getString("artist"), json.nullable("artworkUri"))
    fun member(row: AlbumMemberRow) = JSONObject().apply { put("trackId", row.trackId); put("albumId", row.albumId); put("position", row.position) }
    fun member(json: JSONObject) = AlbumMemberRow(json.getString("trackId"), json.getString("albumId"), json.getInt("position"))
    fun settings(settings: AppSettings) = JSONObject().apply {
        put("themeId", settings.themeId); put("touchGuard", settings.touchGuard); put("haptics", settings.haptics)
        put("clickSound", settings.clickSound); put("wheelSensitivity", settings.wheelSensitivity); put("largeText", settings.largeText)
        put("reducedMotion", settings.reducedMotion); put("cacheLimitMb", settings.cacheLimitMb); put("wifiDownloadsOnly", settings.wifiDownloadsOnly)
        put("networkQuality", settings.networkQuality); put("fullScreen", settings.fullScreen)
    }
    fun settings(json: JSONObject) = AppSettings(
        themeId = json.string("themeId", 120), touchGuard = json.getBoolean("touchGuard"), haptics = json.getBoolean("haptics"),
        clickSound = json.getBoolean("clickSound"), wheelSensitivity = json.getInt("wheelSensitivity").also { require(it in 1..3) },
        largeText = json.getBoolean("largeText"), reducedMotion = json.getBoolean("reducedMotion"),
        cacheLimitMb = json.getInt("cacheLimitMb").also { require(it in 64..8192) }, wifiDownloadsOnly = json.getBoolean("wifiDownloadsOnly"),
        networkQuality = if (json.has("networkQuality")) json.string("networkQuality", 16).also {
            require(it in setOf("standard", "high", "lossless", "hires")) { "备份中的网络音质无效" }
        } else "standard",
        fullScreen = if (json.has("fullScreen")) json.getBoolean("fullScreen") else false,
        lockScreenOverlay = false, safeMode = false,
    ).validated()

    private fun JSONObject.nullable(key: String) = if (isNull(key) || !has(key)) null else getString(key)
    fun JSONObject.string(key: String, limit: Int): String = (get(key) as? String ?: error("备份字段不是文本：$key")).also { require(it.length <= limit && '\u0000' !in it) { "备份字段超长或无效：$key" } }
    fun uuid(value: String): String = value.lowercase().also { require(it.length == 36 && UUID.fromString(it).toString() == it) { "无效的 UUID" } }

    fun portableId(id: String): String = runCatching { uuid(id) }.getOrElse {
        UUID.nameUUIDFromBytes(JSONArray(listOf("easepod.backup.track", id)).toString().toByteArray(Charsets.UTF_8)).toString()
    }

    fun portableTrack(row: TrackRow, album: AlbumRow?): JSONObject = JSONObject().apply {
        put("id", portableId(row.id)); put("title", row.title); put("artist", row.artist); put("genre", row.genre)
        put("albumTitle", album?.title ?: row.snapshotAlbumTitle); put("durationMs", row.durationMs)
        put("sourceId", row.sourceId); put("accountScope", row.accountScope)
        put("remoteId", if (row.sourceId == LOCAL_SOURCE) portableId(row.id) else row.remoteId)
        put("discNumber", row.discNumber); put("trackNumber", row.trackNumber)
    }

    fun restoredTrack(json: JSONObject, exportId: String): TrackRow {
        val source = json.string("sourceId", 200)
        require(source.matches(Regex("[a-zA-Z0-9._-]{1,200}")))
        val id = uuid(json.string("id", 36))
        val account = json.string("accountScope", 200)
        val remote = json.string("remoteId", 4096)
        require(account.isNotBlank() && remote.isNotEmpty()) { "备份曲目来源不完整" }
        val restoredScope = when {
            source == LOCAL_SOURCE -> "local"
            account == "public" -> "public"
            else -> UUID.nameUUIDFromBytes(JSONArray(listOf(exportId, source, account)).toString().toByteArray(Charsets.UTF_8)).toString()
        }
        return TrackRow(id, null, null, null, json.string("title", 512), json.string("artist", 512), json.string("genre", 512),
            json.optLong("durationMs", -1).takeIf { it >= 0 }?.also { require(it <= 7L * 24 * 60 * 60 * 1000) },
            null, null, false, source, restoredScope,
            if (source == LOCAL_SOURCE) "relink/$id" else remote,
            json.getInt("discNumber").also { require(it in 0..99999) }, json.getInt("trackNumber").also { require(it in 0..99999) }, "",
            json.nullable("albumTitle")?.also { require(it.length <= 512) })
    }
}

internal data class BackupPayload(val exportId: String, val settings: AppSettings, val tracks: List<TrackRow>, val playlists: List<PlaylistRow>, val entries: List<PlaylistEntryRow>)

internal object BackupJson {
    fun parse(bytes: ByteArray): BackupPayload {
        require(bytes.size <= BackupCrypto.MAX_PLAIN_BYTES)
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        JsonReader(StringReader(source)).use { reader ->
            reader.isLenient = false
            validateValue(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "备份数据包含多余内容" }
        }
        val json = JSONObject(source)
        require(json.getInt("version") == 1) { "不支持的备份数据版本" }
        val exportId = DataJson.uuid(json.getString("exportId"))
        val settings = DataJson.settings(json.getJSONObject("settings"))
        val trackArray = json.getJSONArray("tracks")
        val playlistArray = json.getJSONArray("playlists")
        require(trackArray.length() <= 100_000 && playlistArray.length() <= 10_000) { "备份记录数量超过上限" }
        val tracks = (0 until trackArray.length()).map { DataJson.restoredTrack(trackArray.getJSONObject(it), exportId) }
        require(tracks.map { it.id }.distinct().size == tracks.size) { "重复曲目标识" }
        require(tracks.map { Triple(it.sourceId, it.accountScope, it.remoteId) }.distinct().size == tracks.size) { "重复曲目来源键" }
        val trackIds = tracks.map { it.id }.toHashSet()
        val entries = mutableListOf<PlaylistEntryRow>()
        val playlists = (0 until playlistArray.length()).map { index ->
            val item = playlistArray.getJSONObject(index)
            val id = DataJson.uuid(item.getString("id"))
            val title = item.getString("title").trim().also { require(it.length in 1..60 && '\u0000' !in it) { "歌单名称需为 1 至 60 个字符" } }
            val list = item.getJSONArray("entries")
            require(entries.size + list.length() <= 200_000) { "歌单条目超过上限" }
            repeat(list.length()) { position ->
                val entry = list.getJSONObject(position)
                val trackId = DataJson.uuid(entry.getString("trackId"))
                require(trackId in trackIds) { "歌单引用了不存在的曲目" }
                entries += PlaylistEntryRow(DataJson.uuid(entry.getString("id")), id, trackId, position)
            }
            PlaylistRow(id, title, index.toLong())
        }
        require(playlists.map { it.id }.distinct().size == playlists.size && entries.map { it.id }.distinct().size == entries.size) { "重复歌单或条目标识" }
        return BackupPayload(exportId, settings, tracks, playlists, entries)
    }

    private fun validateValue(reader: JsonReader, depth: Int) {
        require(depth <= 16) { "备份数据嵌套过深" }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    require(names.add(reader.nextName())) { "备份数据含重复字段" }
                    validateValue(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) validateValue(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> require(reader.nextString().toLongOrNull() != null) { "备份数值必须是整数" }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> error("备份 JSON 格式无效")
        }
    }
}
