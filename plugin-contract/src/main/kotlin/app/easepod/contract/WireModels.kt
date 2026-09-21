package app.easepod.contract

import android.os.Parcel

data class HostHello(val connectionId: String, val hostPackage: String,
    val major: Int = Protocol.MAJOR, val minMinor: Int = 0, val maxMinor: Int = Protocol.MINOR) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(connectionId); p.writeString(hostPackage); p.writeInt(major); p.writeInt(minMinor); p.writeInt(maxMinor) }
    companion object { @JvmField val CREATOR = creator { p -> HostHello(p.string(), p.string(), p.readInt(), p.readInt(), p.readInt()) } }
}

data class PluginHello(val connectionId: String, val pluginId: String, val major: Int = 1,
    val minor: Int = 0, val capabilities: List<String> = emptyList(), val error: String? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(connectionId); p.writeString(pluginId); p.writeInt(major); p.writeInt(minor); p.putStrings(capabilities); p.writeString(error) }
    companion object { @JvmField val CREATOR = creator { p -> PluginHello(p.string(), p.string(), p.readInt(), p.readInt(), p.strings(), p.nullableString(512)) } }
}

/** Closed operation union: only Protocol.operations may be dispatched. No URLs or executable names. */
data class RequestEnvelope(val connectionId: String, val requestId: String, val traceId: String,
    val deadlineElapsedMs: Long, val accountContext: String = "public", val operation: String,
    val remoteId: String? = null, val query: String? = null, val cursor: String? = null,
    val pageSize: Int = 30, val kind: String = "TRACK", val quality: String = "standard",
    val sessionId: String? = null, val authMethod: String = "qr", val remoteIds: List<String> = emptyList(),
    val mutation: LibraryMutation? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) {
        p.writeString(connectionId); p.writeString(requestId); p.writeString(traceId); p.writeLong(deadlineElapsedMs)
        p.writeString(accountContext); p.writeString(operation); p.writeString(remoteId); p.writeString(query)
        p.writeString(cursor); p.writeInt(pageSize); p.writeString(kind); p.writeString(quality)
        p.writeString(sessionId); p.writeString(authMethod); p.putStrings(remoteIds); p.writeTypedObject(mutation, flags)
    }
    companion object { @JvmField val CREATOR = framedCreator { p, end ->
        val request = RequestEnvelope(p.string(), p.string(), p.string(), p.readLong(), p.string(), p.string(64), p.nullableString(), p.nullableString(1024), p.nullableString(), p.readInt(), p.string(32), p.string(64), p.nullableString(), p.string(32), p.strings())
        if (p.dataPosition() < end) request.copy(mutation = p.readTypedObject(LibraryMutation.CREATOR)) else request
    } }
}

data class CatalogItem(val kind: String = "TRACK", val remoteId: String, val title: String,
    val artists: List<String> = emptyList(), val albumId: String? = null, val albumTitle: String? = null,
    val durationMs: Long = -1, val artworkUrl: String? = null, val availability: String = "UNKNOWN",
    val playlistEntryId: String? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(kind); p.writeString(remoteId); p.writeString(title); p.putStrings(artists); p.writeString(albumId); p.writeString(albumTitle); p.writeLong(durationMs); p.writeString(artworkUrl); p.writeString(availability); p.writeString(playlistEntryId) }
    companion object { @JvmField val CREATOR = framedCreator { p, end ->
        val item = CatalogItem(p.string(32), p.string(), p.string(1024), p.strings(), p.nullableString(), p.nullableString(1024), p.readLong(), p.nullableString(), p.string(64))
        if (p.dataPosition() < end) item.copy(playlistEntryId = p.nullableString()) else item
    } }
}

data class MediaHeader(val name: String, val value: String) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(name); p.writeString(value) }
    companion object { @JvmField val CREATOR = creator { p -> MediaHeader(p.string(64), p.string()) } }
}

data class PlaybackSource(val remoteId: String, val url: String, val headers: List<MediaHeader> = emptyList(),
    val expiresAtEpochMs: Long = -1, val mime: String? = null, val length: Long = -1,
    val bitrate: Int = -1, val actualQuality: String = "standard", val canSeek: Boolean = true,
    val isPreview: Boolean = false, val cachePolicy: String = "NO_STORE", val resolverRevision: String = "1",
    val contentSha256: String? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(remoteId); p.writeString(url); p.putRecords(headers, flags); p.writeLong(expiresAtEpochMs); p.writeString(mime); p.writeLong(length); p.writeInt(bitrate); p.writeString(actualQuality); p.writeBoolean(canSeek); p.writeBoolean(isPreview); p.writeString(cachePolicy); p.writeString(resolverRevision); p.writeString(contentSha256) }
    companion object { @JvmField val CREATOR = framedCreator { p, end ->
        val source = PlaybackSource(p.string(), p.string(), p.records(MediaHeader.CREATOR, 8), p.readLong(), p.nullableString(128), p.readLong(), p.readInt(), p.string(64), p.readBoolean(), p.readBoolean(), p.string(64), p.string())
        if (p.dataPosition() < end) source.copy(contentSha256 = p.nullableString(64)) else source
    } }
}

data class AccountSummary(val pluginAccountId: String, val displayName: String,
    val authState: String, val avatarUrl: String? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(pluginAccountId); p.writeString(displayName); p.writeString(authState); p.writeString(avatarUrl) }
    companion object { @JvmField val CREATOR = creator { p -> AccountSummary(p.string(), p.string(1024), p.string(64), p.nullableString()) } }
}

data class LyricSegment(val timeMs: Long = -1, val text: String, val translation: String? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeLong(timeMs); p.writeString(text); p.writeString(translation) }
    companion object { @JvmField val CREATOR = creator { p -> LyricSegment(p.readLong(), p.string(1024), p.nullableString(1024)) } }
}

data class AuthSession(val sessionId: String, val state: String, val qrContent: String? = null,
    val expiresAtElapsedMs: Long = -1, val account: AccountSummary? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(sessionId); p.writeString(state); p.writeString(qrContent); p.writeLong(expiresAtElapsedMs); p.writeTypedObject(account, flags) }
    companion object { @JvmField val CREATOR = creator { p -> AuthSession(p.string(), p.string(64), p.nullableString(), p.readLong(), p.readTypedObject(AccountSummary.CREATOR)) } }
}

data class ResultEnvelope(val connectionId: String, val requestId: String, val operation: String,
    val items: List<CatalogItem> = emptyList(), val nextCursor: String? = null,
    val snapshotId: String? = null, val isStale: Boolean = false,
    val playback: PlaybackSource? = null, val lyrics: List<LyricSegment> = emptyList(),
    val accounts: List<AccountSummary> = emptyList(), val auth: AuthSession? = null,
    val errorCode: String? = null, val errorMessage: String? = null, val retryAfterMs: Long = -1,
    val mutation: MutationResult? = null, val favoriteState: Boolean? = null,
    val playlists: List<CloudPlaylist> = emptyList()) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) {
        p.writeString(connectionId); p.writeString(requestId); p.writeString(operation); p.putRecords(items, flags)
        p.writeString(nextCursor); p.writeString(snapshotId); p.writeBoolean(isStale); p.writeTypedObject(playback, flags)
        p.putRecords(lyrics, flags); p.putRecords(accounts, flags); p.writeTypedObject(auth, flags)
        p.writeString(errorCode); p.writeString(errorMessage); p.writeLong(retryAfterMs)
        p.writeTypedObject(mutation, flags); p.writeInt(favoriteState?.let { if (it) 1 else 0 } ?: -1); p.putRecords(playlists, flags)
    }
    companion object { @JvmField val CREATOR = framedCreator { p, end ->
        val result = ResultEnvelope(p.string(), p.string(), p.string(64), p.records(CatalogItem.CREATOR), p.nullableString(), p.nullableString(), p.readBoolean(), p.readTypedObject(PlaybackSource.CREATOR), p.records(LyricSegment.CREATOR), p.records(AccountSummary.CREATOR), p.readTypedObject(AuthSession.CREATOR), p.nullableString(64), p.nullableString(512), p.readLong())
        if (p.dataPosition() < end) result.copy(mutation = p.readTypedObject(MutationResult.CREATOR), favoriteState = when (p.readInt()) { 0 -> false; 1 -> true; -1 -> null; else -> throw android.os.BadParcelableException("Invalid favorite state") }, playlists = p.records(CloudPlaylist.CREATOR)) else result
    } }
}
