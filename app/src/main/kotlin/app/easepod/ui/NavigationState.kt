package app.easepod.ui

import android.os.Bundle

internal val productionRoutes = setOf("home", "music", "songs", "artists", "artist", "albums", "album", "genres",
    "playlists", "playlist", "create-playlist", "coverflow", "history", "search", "now", "queue", "lyrics",
    "settings", "themes", "wheel-settings", "audio-settings", "sleep", "plugins", "plugin", "services", "service",
    "login", "market", "store", "install", "storage", "local-folders", "permission", "downloads", "about")

internal fun Route.toSavedBundle() = Bundle().apply {
    putString("id", id); putString("key", key); putString("title", title); putString("source", source)
    putInt("focus", focus); putString("focusKey", focusKey); putString("scope", accountScope)
    putBoolean("musicRoot", musicRoot)
    if (id in setOf("search", "create-playlist")) putString("draft", draft.take(256))
    if (id == "create-playlist") putString("pendingTrackId", pendingTrackId)
    if (id == "songs") putString("localArtist", localArtist)
}

internal fun Bundle.toRouteOrNull(): Route? {
    val id = getString("id")?.takeIf { it in productionRoutes } ?: return null
    if (id in setOf("login", "install") || id == "storage" && getString("key").orEmpty().startsWith("backup:")) return null
    return Route(id, getString("key").orEmpty(), getString("title").orEmpty(), getString("source").orEmpty(),
        getInt("focus").coerceAtLeast(0), getString("draft").orEmpty().take(256), getString("focusKey"), getString("scope") ?: "public",
        getString("pendingTrackId").takeIf { id == "create-playlist" }, getString("localArtist")?.take(512).takeIf { id == "songs" }, getBoolean("musicRoot"))
}
