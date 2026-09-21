package app.easepod.ui

import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.Track
import app.easepod.data.AndroidLibraryRepository
import app.easepod.plugins.CatalogPage
import app.easepod.plugins.LyricLine
import app.easepod.plugins.PluginManager

internal interface PageSource {
    suspend fun browse(source: String, parent: String?, cursor: String?, account: String): CatalogPage
    suspend fun search(source: String, query: String, cursor: String?, account: String): CatalogPage
    suspend fun details(source: String, key: String, cursor: String?, account: String): CatalogPage?
    suspend fun lyrics(track: Track?): List<LyricLine>
}

internal class PluginPageSource(private val plugins: PluginManager, private val library: AndroidLibraryRepository) : PageSource {
    override suspend fun browse(source: String, parent: String?, cursor: String?, account: String) =
        plugins.browse(source, parent, cursor, accountScope = account)

    override suspend fun search(source: String, query: String, cursor: String?, account: String) =
        plugins.search(source, query, cursor, accountScope = account)

    override suspend fun details(source: String, key: String, cursor: String?, account: String): CatalogPage? =
        if (plugins.plugins.value.any { it.id == source && "catalog.details" in it.capabilities })
            plugins.details(source, key, cursor, accountScope = account) else null

    override suspend fun lyrics(track: Track?): List<LyricLine> {
        if (track == null) return emptyList()
        if (track.sourceId == LOCAL_SOURCE) return parseLyrics(library.localLyrics(track.id).orEmpty())
        val result = mutableListOf<LyricLine>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = plugins.lyricsPage(track, cursor)
            result.addAll(page.lines)
            cursor = page.nextCursor
            if (cursor != null && !seen.add(cursor)) break
        } while (cursor != null && result.size < 2000)
        return result.take(2000)
    }
}
