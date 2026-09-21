package app.easepod.plugins

import app.easepod.contract.ResultEnvelope
import app.easepod.core.Track

internal object CatalogMapper {
    /** Media URLs have already passed the host's policy before this pure domain mapping. */
    fun map(sourceId: String, accountScope: String, result: ResultEnvelope): CatalogPage {
        val tracks = result.items.filter { it.kind == "TRACK" }.map { item ->
            Track(PluginManager.mediaId(sourceId, accountScope, "TRACK", item.remoteId), item.title,
                item.artists.joinToString(" / "), item.albumId, item.albumTitle,
                durationMs = item.durationMs.takeIf { it >= 0 }, artworkUri = item.artworkUrl,
                available = item.availability == "AVAILABLE", sourceId = sourceId,
                accountScope = accountScope, remoteId = item.remoteId)
        }
        val containers = result.items.filter { it.kind != "TRACK" }.map {
            CatalogEntry(it.kind, it.remoteId, it.title, it.artists.joinToString(" / "), it.artworkUrl)
        }
        return CatalogPage(tracks, result.nextCursor, result.snapshotId, result.isStale, containers,
            result.items.filter { it.kind == "TRACK" }.map { it.playlistEntryId })
    }
}
