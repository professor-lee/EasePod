package app.easepod.contract

import android.os.Parcel

/** Entry IDs identify playlist positions, including repeated tracks. Revisions are opaque. */
data class LibraryMutation(val mutationId: String, val action: String,
    val expectedRevision: String? = null, val desiredFavorite: Boolean = false,
    val title: String? = null, val trackId: String? = null, val entryId: String? = null,
    val beforeEntryId: String? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) {
        p.writeString(mutationId); p.writeString(action); p.writeString(expectedRevision)
        p.writeBoolean(desiredFavorite); p.writeString(title); p.writeString(trackId)
        p.writeString(entryId); p.writeString(beforeEntryId)
    }
    fun validFor(operation: String, remoteId: String?): Boolean {
        if (mutationId.isBlank() || mutationId.length > 128) return false
        if (operation == "GetMutation") return action == "READ"
        if (operation == "SetFavorite") return action == "FAVORITE" && !remoteId.isNullOrBlank()
        if (operation != "EditPlaylist" || action !in playlistActions) return false
        if (action != "CREATE" && (remoteId.isNullOrBlank() || expectedRevision.isNullOrBlank())) return false
        if (action in setOf("CREATE", "RENAME") && (title.isNullOrBlank() || title.codePointCount(0, title.length) !in 1..60)) return false
        if (action == "ADD" && trackId.isNullOrBlank()) return false
        if (action in setOf("REMOVE", "MOVE") && entryId.isNullOrBlank()) return false
        if (action == "MOVE" && entryId == beforeEntryId) return false
        return listOfNotNull(remoteId, expectedRevision, trackId, entryId, beforeEntryId).all { it.length <= 4096 }
    }
    companion object {
        val playlistActions = setOf("CREATE", "ADD", "REMOVE", "MOVE", "RENAME", "DELETE")
        @JvmField val CREATOR = creator { p -> LibraryMutation(p.string(128), p.string(32), p.nullableString(), p.readBoolean(), p.nullableString(1024), p.nullableString(), p.nullableString(), p.nullableString()) }
    }
}

data class CloudPlaylist(val remoteId: String, val title: String, val revision: String,
    val owner: String = "", val allowedActions: List<String> = emptyList()) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(remoteId); p.writeString(title); p.writeString(revision); p.writeString(owner); p.putStrings(allowedActions) }
    companion object { @JvmField val CREATOR = creator { p -> CloudPlaylist(p.string(), p.string(1024), p.string(), p.string(1024), p.strings(6)) } }
}

/** APPLIED is authoritative. UNKNOWN must never trigger automatic replay. */
data class MutationResult(val mutationId: String, val status: String, val revision: String? = null,
    val remoteId: String? = null, val message: String? = null) : WireRecord() {
    override fun writeFields(p: Parcel, flags: Int) { p.writeString(mutationId); p.writeString(status); p.writeString(revision); p.writeString(remoteId); p.writeString(message) }
    companion object { @JvmField val CREATOR = creator { p -> MutationResult(p.string(128), p.string(32), p.nullableString(), p.nullableString(), p.nullableString(512)) } }
}
