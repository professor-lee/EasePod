package app.easepod.sampleplugin

import app.easepod.contract.CatalogItem
import app.easepod.contract.MusicPluginService
import app.easepod.contract.PlaybackSource
import app.easepod.contract.RequestEnvelope
import app.easepod.contract.ResultEnvelope

/** Public, instrumental test catalog. No accounts, credentials, downloads, or commercial service API. */
class SampleMusicService : MusicPluginService() {
    override val pluginId = "app.easepod.sampleplugin"
    override val capabilities = setOf("catalog.browse", "catalog.search", "catalog.details",
        "playback.resolve", "lyrics.read", "account.list")
    private val tracks = (1..3).map { index -> CatalogItem(remoteId = "soundhelix-$index",
        title = "SoundHelix Song $index", artists = listOf("T. Schuerger"),
        albumTitle = "SoundHelix Examples", availability = "AVAILABLE") }

    override suspend fun handle(request: RequestEnvelope): ResultEnvelope {
        fun result() = ResultEnvelope(request.connectionId, request.requestId, request.operation)
        if (request.accountContext != "public") return result().copy(errorCode = "AuthRequired")
        return when (request.operation) {
            "Browse", "Search", "GetDetails" -> {
                if (request.remoteId != null && request.remoteId != "root") return result().copy(errorCode = "NotFound")
                val filtered = when (request.operation) {
                    "Search" -> tracks.filter { it.title.contains(request.query.orEmpty(), ignoreCase = true) }
                    "GetDetails" -> tracks.filter { it.remoteId in request.remoteIds }
                    else -> tracks
                }
                // Cursor binds operation and query; arbitrary input cannot become a network URL.
                val prefix = "v1:${request.operation}:${request.query.orEmpty().hashCode()}:"
                val cursor = request.cursor
                val offset = if (cursor == null) 0 else {
                    if (!cursor.startsWith(prefix)) return result().copy(errorCode = "InvalidRequest")
                    cursor.removePrefix(prefix).toIntOrNull() ?: return result().copy(errorCode = "InvalidRequest")
                }
                if (offset !in 0..filtered.size) return result().copy(errorCode = "InvalidRequest")
                val items = filtered.drop(offset).take(request.pageSize)
                result().copy(items = items, nextCursor = (offset + items.size).takeIf { it < filtered.size }?.let { "$prefix$it" }, snapshotId = "soundhelix-v1")
            }
            "ResolvePlayback" -> {
                val track = tracks.find { it.remoteId == request.remoteId } ?: return result().copy(errorCode = "NotFound")
                val number = tracks.indexOf(track) + 1
                result().copy(playback = PlaybackSource(track.remoteId,
                    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-$number.mp3",
                    mime = "audio/mpeg", actualQuality = "original", cachePolicy = "NO_STORE"))
            }
            "GetLyrics" -> if (tracks.none { it.remoteId == request.remoteId }) result().copy(errorCode = "NotFound") else result()
            "ListAccounts" -> result()
            else -> result().copy(errorCode = "Unsupported")
        }
    }
}
