package app.easepod.plugins

import android.os.Parcel
import app.easepod.contract.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CloudWireContractTest {
    @Test fun preferencesRestorePendingReceiptsAcrossRepositoryInstances() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val receipt = CloudMutationReceipt("mutation", "plugin", "host-account-scope", "EditPlaylist", null, "CREATE", "Playlist title")
        val store = PreferencesCloudReceiptStore(context)
        store.write(listOf(receipt))
        assertEquals(listOf(receipt), PreferencesCloudReceiptStore(context).read())
        store.write(emptyList())
        assertTrue(PreferencesCloudReceiptStore(context).read().isEmpty())
    }

    @Test fun mutationRoundTripRetainsRevisionAndDuplicatePositionIdentity() {
        val request = RequestEnvelope("connection", "request", "trace", 12345, "plugin-account", "EditPlaylist", "playlist",
            mutation = LibraryMutation("mutation", "MOVE", "revision", entryId = "position-2", beforeEntryId = "position-1"))
        val parcel = Parcel.obtain()
        try {
            request.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            assertEquals(request, RequestEnvelope.CREATOR.createFromParcel(parcel))
        } finally { parcel.recycle() }
    }

    @Test fun libraryResponseRoundTripKeepsAllOptionalRecordsBounded() {
        val response = ResultEnvelope("connection", "request", "GetDetails",
            items = listOf(CatalogItem(remoteId = "same-song", title = "Song", playlistEntryId = "entry-1"), CatalogItem(remoteId = "same-song", title = "Song", playlistEntryId = "entry-2")),
            mutation = MutationResult("mutation", "APPLIED", "revision-2", "playlist"), favoriteState = false,
            playlists = listOf(CloudPlaylist("playlist", "Playlist", "revision-2", "Owner", listOf("ADD", "MOVE"))))
        val parcel = Parcel.obtain()
        try {
            response.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            val restored = ResultEnvelope.CREATOR.createFromParcel(parcel)
            assertEquals(response, restored)
            val mapped = CatalogMapper.map("source", "scope", restored)
            assertEquals(listOf("entry-1", "entry-2"), mapped.trackEntryIds)
            assertEquals(2, mapped.tracks.size)
        } finally { parcel.recycle() }
    }

    @Test fun oldRequestFramesStillDecodeWithoutAMutationTail() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(0); parcel.writeInt(1)
            listOf("connection", "request", "trace").forEach(parcel::writeString)
            parcel.writeLong(12345); parcel.writeString("public"); parcel.writeString("Browse")
            repeat(3) { parcel.writeString(null) }; parcel.writeInt(30); parcel.writeString("TRACK"); parcel.writeString("standard")
            parcel.writeString(null); parcel.writeString("qr"); parcel.writeInt(0)
            val end = parcel.dataPosition(); parcel.setDataPosition(0); parcel.writeInt(end); parcel.setDataPosition(end)
            parcel.writeString("following-record"); parcel.setDataPosition(0)
            assertNull(RequestEnvelope.CREATOR.createFromParcel(parcel).mutation)
            assertEquals("following-record", parcel.readString())
        } finally { parcel.recycle() }
    }

    @Test fun editsRequireRevisionAndEntryIdsInsteadOfAmbiguousSongPositions() {
        assertFalse(LibraryMutation("m", "ADD", trackId = "track").validFor("EditPlaylist", "playlist"))
        assertFalse(LibraryMutation("m", "REMOVE", "r", trackId = "track").validFor("EditPlaylist", "playlist"))
        assertTrue(LibraryMutation("m", "REMOVE", "r", entryId = "entry").validFor("EditPlaylist", "playlist"))
        assertFalse(LibraryMutation("m", "MOVE", "r", entryId = "entry", beforeEntryId = "entry").validFor("EditPlaylist", "playlist"))
        assertFalse(LibraryMutation("m", "EXECUTE", "r").validFor("EditPlaylist", "playlist"))
        assertEquals("library.mutation.read", Protocol.operations["GetMutation"])
        assertFalse("GetMutation" in Protocol.writeOperations)
    }
}
