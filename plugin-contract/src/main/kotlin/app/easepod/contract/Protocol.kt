package app.easepod.contract

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable

object Protocol {
    const val ACTION = "app.easepod.action.MUSIC_PLUGIN"
    const val APPROVE_HOST_ACTION = "app.easepod.action.APPROVE_HOST"
    const val MANIFEST_META = "app.easepod.plugin.MANIFEST"
    const val MAJOR = 1
    const val MINOR = 0
    const val MAX_BYTES = 128 * 1024
    const val MAX_ITEMS = 50
    const val DEFAULT_PAGE = 30
    const val REQUEST_TIMEOUT_MS = 10_000L
    const val RESOLVE_TIMEOUT_MS = 8_000L
    val playbackQualities = listOf("standard", "high", "lossless", "hires")
    val capabilities = setOf("catalog.browse", "catalog.search", "catalog.details", HomeCatalog.CAPABILITY,
        "playback.resolve", "lyrics.read", "account.list", "account.qr", "account.signOut",
        "library.favorite", "library.playlist.write", "library.playlist.create", "library.mutation.read") +
        playbackQualities.map { "playback.quality.$it" }
    val operations = mapOf("Browse" to "catalog.browse", "Search" to "catalog.search",
        "GetDetails" to "catalog.details", "ResolvePlayback" to "playback.resolve",
        "GetLyrics" to "lyrics.read", "ListAccounts" to "account.list", "BeginAuth" to "account.qr",
        "PollAuth" to "account.qr", "CancelAuth" to "account.qr", "SignOut" to "account.signOut",
        "GetFavorite" to "library.favorite", "SetFavorite" to "library.favorite",
        "ListEditablePlaylists" to "library.playlist.write", "GetPlaylistInfo" to "library.playlist.write",
        "EditPlaylist" to "library.playlist.write", "GetMutation" to "library.mutation.read")
    val writeOperations = setOf("SetFavorite", "EditPlaylist")

    fun playbackQuality(requested: String, negotiatedCapabilities: Set<String>): String =
        requested.takeIf { it in playbackQualities && (it == "standard" || "playback.quality.$it" in negotiatedCapabilities) } ?: "standard"

    fun checkSize(value: Parcelable) {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            // Leave room for the Binder interface token and callback handles.
            require(parcel.dataSize() + 1024 <= MAX_BYTES) { "PayloadTooLarge" }
        } finally { parcel.recycle() }
    }
}

// Every record is size framed. Future optional trailing fields are skipped by old readers.
abstract class WireRecord : Parcelable {
    final override fun describeContents() = 0
    final override fun writeToParcel(destination: Parcel, flags: Int) {
        val start = destination.dataPosition()
        destination.writeInt(0)
        destination.writeInt(1)
        writeFields(destination, flags)
        val end = destination.dataPosition()
        if (end - start > Protocol.MAX_BYTES) throw BadParcelableException("PayloadTooLarge")
        destination.setDataPosition(start)
        destination.writeInt(end - start)
        destination.setDataPosition(end)
    }
    protected abstract fun writeFields(p: Parcel, flags: Int)
}

internal inline fun <reified T> creator(crossinline read: (Parcel) -> T) = framedCreator { p, _ -> read(p) }

internal inline fun <reified T> framedCreator(crossinline read: (Parcel, Int) -> T) = object : Parcelable.Creator<T> {
    override fun newArray(size: Int): Array<T?> = arrayOfNulls(size)
    override fun createFromParcel(p: Parcel): T {
        val start = p.dataPosition()
        val size = p.readInt()
        if (size < 8 || size > Protocol.MAX_BYTES || start > p.dataSize() - size)
            throw BadParcelableException("Invalid record size")
        val end = start + size
        try {
            if (p.readInt() < 1) throw BadParcelableException("Unsupported record version")
            val value = read(p, end)
            if (p.dataPosition() > end) throw BadParcelableException("Truncated record")
            return value
        } finally { p.setDataPosition(end) }
    }
}

internal fun Parcel.string(limit: Int = 4096): String =
    (readString() ?: throw BadParcelableException("Missing string")).also {
        if (it.length > limit) throw BadParcelableException("String exceeds limit")
    }
internal fun Parcel.nullableString(limit: Int = 4096): String? = readString()?.also {
    if (it.length > limit) throw BadParcelableException("String exceeds limit")
}
internal fun Parcel.strings(limit: Int = 50): List<String> {
    val count = readInt()
    if (count !in 0..limit) throw BadParcelableException("List exceeds limit")
    return List(count) { string() }
}
internal fun Parcel.putStrings(values: List<String>) { writeInt(values.size); values.forEach(::writeString) }
internal fun <T> Parcel.records(creator: Parcelable.Creator<T>, limit: Int = 50): List<T> {
    val count = readInt()
    if (count !in 0..limit) throw BadParcelableException("List exceeds limit")
    return List(count) { readTypedObject(creator) ?: throw BadParcelableException("Missing item") }
}
internal fun <T : Parcelable> Parcel.putRecords(values: List<T>, flags: Int) {
    writeInt(values.size); values.forEach { writeTypedObject(it, flags) }
}
