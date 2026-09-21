package app.easepod.contract

/** Account-scoped Browse entry points for the host's primary music screens. */
object HomeCatalog {
    const val CAPABILITY = "catalog.home"
    const val ROOT = "home"
    const val COVERS = "home:covers"
    const val PLAYLISTS = "home:playlists"
    const val TRACKS = "home:tracks"
    const val ALBUMS = "home:albums"
    const val ARTISTS = "home:artists"
    const val GENRES = "home:genres"
    const val FAVORITES = "home:favorites"
    const val DAILY = "home:daily"
    const val NETEASE_RADAR = "home:netease:radar"
    const val NETEASE_ROAM = "home:netease:roam"

    fun isHome(remoteId: String?): Boolean = remoteId == ROOT || remoteId?.startsWith("home:") == true
}
