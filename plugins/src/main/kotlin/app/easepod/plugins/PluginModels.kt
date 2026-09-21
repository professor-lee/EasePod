package app.easepod.plugins

import app.easepod.core.Track
import app.easepod.contract.Protocol

data class PluginInfo(val id: String, val name: String, val packageName: String, val serviceName: String,
    val signature: String, val version: String, val enabled: Boolean = false,
    val status: String = "待启用", val capabilities: Set<String> = emptySet(),
    val sourceId: String = "", val approved: Boolean = false, val failure: String? = null,
    val networkDomains: Set<String> = emptySet(), val versionCode: Long = 0,
    val protocolCompatible: Boolean = true, val builtin: Boolean = false)
data class BundledMusicPlugin(val id: String, val name: String, val serviceClass: String,
    val capabilities: Set<String>, val networkDomains: Set<String>)
fun PluginInfo.playbackQualities(): List<String> = if ("playback.resolve" !in capabilities) emptyList()
    else Protocol.playbackQualities.filter { it == "standard" || "playback.quality.$it" in capabilities }
data class CatalogPage(val tracks: List<Track>, val nextCursor: String? = null,
    val snapshotId: String? = null, val isStale: Boolean = false, val items: List<CatalogEntry> = emptyList(),
    val trackEntryIds: List<String?> = emptyList())
data class CatalogEntry(val kind: String, val remoteId: String, val title: String,
    val subtitle: String = "", val artworkUri: String? = null)
data class PluginAccount(val id: String, val displayName: String, val scope: String, val state: String)
data class LyricLine(val timeMs: Long?, val text: String, val translation: String? = null)
data class LyricsPage(val lines: List<LyricLine>, val nextCursor: String? = null)
data class PluginAuthSession(val id: String, val state: String, val qrContent: String? = null,
    val expiresAtElapsedMs: Long? = null, val account: PluginAccount? = null)
data class PluginPlaybackSource(val uri: String, val headers: Map<String, String> = emptyMap(),
    val cachePolicy: String = "NO_STORE", val expiresAt: Long? = null, val mime: String? = null,
    val revision: String = "1", val quality: String = "standard", val canSeek: Boolean = true,
    val isPreview: Boolean = false, val contentLength: Long? = null, val bitrate: Int? = null,
    val allowedHosts: Set<String> = emptySet(), val contentSha256: String? = null)
data class InstallCandidate(val kind: String, val name: String, val version: String, val filePath: String,
    val packageName: String = "", val signature: String = "", val sha256: String = "",
    val versionCode: Long = 0, val listed: Boolean = false, val pluginId: String = "")
class PluginException(val code: String, override val message: String = code,
    val retryAfterMs: Long? = null) : Exception(message)
data class ThemePalette(val frameTop: Long = 0xfff2f2f2, val frameBottom: Long = 0xffadadad,
    val wheel: Long = 0xfffdfdfd, val key: Long = 0xff7a8794, val centerTop: Long = 0xffb1b1b0,
    val centerBottom: Long = 0xffe1e1e1, val highlight: Long = 0xff2878bd) {
    /** Flat surfaces are useful for displays that cannot render gradients well. */
    val usesFlatSurfaces: Boolean get() = frameTop == frameBottom && centerTop == centerBottom
}
enum class ThemeFont { SYSTEM, SANS, SERIF, MONOSPACE }
enum class ThemeFontSize(val menuSp: Int, val rootMenuSp: Int) {
    STANDARD(15, 19), LARGE(17, 21),
}
enum class ThemeSpacing(val menuRowDp: Int, val rootMenuRowDp: Int, val horizontalPaddingDp: Int, val verticalPaddingDp: Int) {
    STANDARD(32, 42, 7, 4), RELAXED(38, 48, 9, 7),
}
data class ThemeTypography(val font: ThemeFont = ThemeFont.SYSTEM,
    val fontSize: ThemeFontSize = ThemeFontSize.STANDARD, val spacing: ThemeSpacing = ThemeSpacing.STANDARD)
data class ThemeAssets(val shellTexture: String? = null, val albumPlaceholder: String? = null)
data class ThemeInfo(val id: String, val name: String, val version: String, val palette: ThemePalette,
    val hasPrevious: Boolean = false, val typography: ThemeTypography = ThemeTypography(),
    val assets: ThemeAssets = ThemeAssets())
