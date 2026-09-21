package app.easepod.plugins

import android.os.BadParcelableException
import android.os.Parcel
import app.easepod.contract.CatalogItem
import app.easepod.contract.HostHello
import app.easepod.contract.Protocol
import app.easepod.contract.PlaybackSource
import app.easepod.contract.ResultEnvelope
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PluginContractTest {
    @Test fun playbackIntegrityMetadataRoundTripsInsideEnvelope() {
        val parcel = Parcel.obtain()
        try {
            val source = PlaybackSource("song", "https://example.com/a", length = 1234,
                actualQuality = "lossless", cachePolicy = "OFFLINE", resolverRevision = "encoding-2", contentSha256 = "ab".repeat(32))
            ResultEnvelope("connection", "request", "ResolvePlayback", playback = source, errorCode = "after-playback").writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = ResultEnvelope.CREATOR.createFromParcel(parcel)
            assertEquals(source, restored.playback)
            assertEquals("after-playback", restored.errorCode)
        } finally { parcel.recycle() }
    }

    @Test fun playbackReaderAcceptsOldFrameWithoutConsumingFollowingRecord() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(0); parcel.writeInt(1)
            parcel.writeString("song"); parcel.writeString("https://example.com/a"); parcel.writeInt(0)
            parcel.writeLong(-1); parcel.writeString(null); parcel.writeLong(1234); parcel.writeInt(-1)
            parcel.writeString("standard"); parcel.writeBoolean(true); parcel.writeBoolean(false)
            parcel.writeString("OFFLINE"); parcel.writeString("r1")
            val end = parcel.dataPosition()
            parcel.setDataPosition(0); parcel.writeInt(end); parcel.setDataPosition(end)
            parcel.writeString("next-record"); parcel.setDataPosition(0)
            val source = PlaybackSource.CREATOR.createFromParcel(parcel)
            assertNull(source.contentSha256)
            assertEquals(1234L, source.length)
            assertEquals(end, parcel.dataPosition())
            assertEquals("next-record", parcel.readString())
        } finally { parcel.recycle() }
    }

    @Test fun qualityPreferenceRequiresNegotiatedCapabilityAndOldPluginsKeepStandard() {
        assertEquals("standard", Protocol.playbackQuality("lossless", setOf("playback.resolve")))
        assertEquals("standard", Protocol.playbackQuality("custom", setOf("playback.resolve", "playback.quality.custom")))
        assertEquals("lossless", Protocol.playbackQuality("lossless", setOf("playback.resolve", "playback.quality.lossless")))
        assertEquals("standard", Protocol.playbackQuality("hires", setOf("playback.resolve", "playback.quality.lossless")))
        val plugin = PluginInfo("id", "name", "package", "service", "signature", "1", capabilities = setOf("playback.resolve", "playback.quality.lossless"))
        assertEquals(listOf("standard", "lossless"), plugin.playbackQualities())
        assertTrue(plugin.copy(capabilities = setOf("catalog.browse", "playback.quality.hires")).playbackQualities().isEmpty())
    }

    @Test fun framedRecordSkipsFutureOptionalFields() {
        val parcel = Parcel.obtain()
        try {
            HostHello("connection", "host.package").writeToParcel(parcel, 0)
            parcel.writeString("future optional metadata")
            val end = parcel.dataPosition()
            parcel.setDataPosition(0); parcel.writeInt(end); parcel.setDataPosition(0)
            val restored = HostHello.CREATOR.createFromParcel(parcel)
            assertEquals("connection", restored.connectionId)
            assertEquals(end, parcel.dataPosition())
        } finally { parcel.recycle() }
    }

    @Test fun malformedFrameIsRejected() {
        for (size in listOf(-1, 0, 4, Protocol.MAX_BYTES + 1, Int.MAX_VALUE)) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInt(size); parcel.writeInt(1); parcel.setDataPosition(0)
                assertThrows(BadParcelableException::class.java) { HostHello.CREATOR.createFromParcel(parcel) }
            } finally { parcel.recycle() }
        }
    }

    @Test fun oversizedPageIsRejectedOnReceive() {
        val parcel = Parcel.obtain()
        try {
            ResultEnvelope("c", "r", "Browse", items = List(51) { CatalogItem(remoteId = "$it", title = "Track") }).writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertThrows(BadParcelableException::class.java) { ResultEnvelope.CREATOR.createFromParcel(parcel) }
        } finally { parcel.recycle() }
    }

    @Test fun oversizedPayloadIsRejectedBeforeBinder() {
        assertThrows(Exception::class.java) {
            Protocol.checkSize(ResultEnvelope("c", "r", "Browse", items = List(50) {
                CatalogItem(remoteId = "$it", title = "x".repeat(1024), artists = List(8) { "y".repeat(1024) })
            }))
        }
    }

    @Test fun mediaPolicyRejectsLocalAndUnregisteredAddresses() {
        listOf("http://media.example.com/a", "file:///a", "content://media/a", "https://u:p@media.example.com/a", "https://other.example.com/a", "https://media.example.com:444/a", "https://media.example.com/a#fragment").forEach { value ->
            assertThrows(Exception::class.java) { MediaUriPolicy.validateSyntax(value, setOf("media.example.com")) }
        }
        assertEquals("media.example.com", MediaUriPolicy.validateSyntax("https://media.example.com/a?token=redacted", setOf("media.example.com")).host)
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1").forEach { address ->
            assertFalse(address, MediaUriPolicy.isPublicAddress(InetAddress.getByName(address)))
        }
        assertTrue(MediaUriPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun mediaIdentityKeepsFieldBoundariesAndNamespaces() {
        val first = PluginManager.mediaId("a:b", "c", "TRACK", "d")
        assertNotEquals(first, PluginManager.mediaId("a", "b:c", "TRACK", "d"))
        assertNotEquals(first, PluginManager.mediaId("a:b", "c", "ALBUM", "d"))
        assertNotEquals(first, PluginManager.mediaId("a:b", "private", "TRACK", "d"))
    }
    @Test fun catalogSeparatesSongsAndContainersWithoutLosingRepeatedPositions() {
        val song = CatalogItem(remoteId = "same-id", title = "Song", availability = "UNKNOWN")
        val album = CatalogItem(kind = "ALBUM", remoteId = "same-id", title = "Album", artworkUrl = "https://media.example.org/art.png")
        val page = CatalogMapper.map("verified-source", "private-scope", ResultEnvelope("connection", "request", "Browse", items = listOf(song, album, song), nextCursor = "next"))
        assertEquals(2, page.tracks.size)
        assertEquals(1, page.items.size)
        assertEquals("ALBUM", page.items.single().kind)
        assertEquals(album.artworkUrl, page.items.single().artworkUri)
        assertEquals("verified-source", page.tracks.first().sourceId)
        assertEquals("private-scope", page.tracks.first().accountScope)
        assertNull(page.tracks.first().durationMs)
        assertFalse(page.tracks.first().available)
        assertEquals("next", page.nextCursor)
    }
}
