package app.easepod.playback

import app.easepod.core.QueueEntry
import app.easepod.core.Track
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
import java.net.InetAddress

class PlaybackRulesTest {
    private val repeatedTrack = Track("song", "Repeated Song")
    private fun entries(count: Int) = (0 until count).map { QueueEntry("entry-$it", repeatedTrack) }

    @Test fun shuffleRetainsHistoryAndCurrentOccurrence() {
        val original = entries(20)
        val shuffled = QueueOrder.shuffled(original, "entry-4", Random(13))
        assertEquals(original.take(5), shuffled.take(5))
        assertEquals(original.map { it.id }.toSet(), shuffled.map { it.id }.toSet())
        assertNotEquals(original.drop(5), shuffled.drop(5))
        assertEquals(original, QueueOrder.original(shuffled, original.map { it.id }))
    }

    @Test fun duplicateSongsAreMovedAndRestoredByEntryIdentity() {
        val original = entries(4)
        val moved = QueueOrder.moved(original, "entry-1", 2)
        assertEquals(listOf("entry-0", "entry-2", "entry-3", "entry-1"), moved.map { it.id })
        assertEquals(original, QueueOrder.original(moved, original.map { it.id }))
    }

    @Test fun queueMovesCannotEscapeBoundsOrLoseAnEntry() {
        val original = entries(3)
        assertEquals(listOf("entry-1", "entry-0", "entry-2"), QueueOrder.moved(original, "entry-1", -99).map { it.id })
        assertEquals(original, QueueOrder.moved(original, "missing", 1))
        assertTrue(QueueOrder.shuffled(emptyList(), null).isEmpty())
    }

    @Test fun stableCacheKeySeparatesAccountsAndServices() {
        val first = Track("local-display-id", "Title", sourceId = "provider", accountScope = "account-a", remoteId = "remote-song")
        assertEquals(stableMediaKey(first), stableMediaKey(first.copy(id = "changed-display-id", title = "Changed")))
        assertNotEquals(stableMediaKey(first), stableMediaKey(first.copy(accountScope = "account-b")))
        assertNotEquals(stableMediaKey(first), stableMediaKey(first.copy(sourceId = "other-provider")))
        assertTrue(stableMediaKey(first).matches(Regex("[a-f0-9]{64}")))
    }

    @Test fun offlinePermissionExpiresAtDeadline() {
        assertTrue(OfflineGrant().valid(Long.MAX_VALUE))
        assertTrue(OfflineGrant(expiresAtMs = 100).valid(99))
        assertFalse(OfflineGrant(expiresAtMs = 100).valid(100))
    }

    @Test fun streamCacheIdentityTracksEncodingAndRevisionWithoutSignedUrls() {
        val track = Track("song", "Song", sourceId = "service", accountScope = "account", remoteId = "remote")
        val source = PlayableSource("https://example.com/a?signature=one", allowStreamCache = true, quality = "standard", revision = "r1")
        val key = streamCacheKey(track, source)
        assertEquals(key, streamCacheKey(track, source.copy(uri = "https://example.com/a?signature=two")))
        assertNotEquals(key, streamCacheKey(track, source.copy(quality = "lossless")))
        assertNotEquals(key, streamCacheKey(track, source.copy(revision = "r2")))
        assertNotEquals(key, streamCacheKey(track.copy(accountScope = "other-account"), source))
        assertNotEquals(key, streamCacheKey(track.copy(remoteId = "other-song"), source))
        assertNull(streamCacheKey(track, source.copy(revision = null)))
        assertNull(streamCacheKey(track, source.copy(revision = "")))
        assertNull(streamCacheKey(track, source.copy(allowStreamCache = false)))
    }

    @Test fun streamIdentityCannotCollideThroughFieldSeparators() {
        assertNotEquals(streamAccountPrefix("a\u0000b", "c"), streamAccountPrefix("a", "b\u0000c"))
        val source = PlayableSource("https://example.com/a", allowStreamCache = true, quality = "standard", revision = "r1")
        val track = Track("song", "Song", sourceId = "service", accountScope = "account", remoteId = "remote")
        assertNotEquals(streamCacheKey(track, source.copy(quality = "a\u0000b", revision = "c")),
            streamCacheKey(track, source.copy(quality = "a", revision = "b\u0000c")))
    }

    @Test fun remoteSourceRejectsUnsafeSchemesCredentialsAndHeaders() {
        for (uri in listOf("http://example.com/audio", "file:///etc/passwd", "content://documents/1", "https://user:pass@example.com/a")) {
            assertThrows(IllegalArgumentException::class.java) { validatedSource(PlayableSource(uri)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validatedSource(PlayableSource("https://example.com/a", mapOf("Host" to "other.com"))) }
        assertThrows(IllegalArgumentException::class.java) { validatedSource(PlayableSource("https://example.com/a", mapOf("Authorization" to "token\r\nX: injected"))) }
        assertThrows(IllegalArgumentException::class.java) { validatedSource(PlayableSource("https://example.com/a", offlineGrant = OfflineGrant(sha256 = "bad"))) }
        assertEquals("https://example.com/a", validatedSource(PlayableSource("https://example.com/a", mapOf("User-Agent" to "EasePod"))).uri)
    }

    @Test fun mediaDomainsAreExactAndRejectUndeclaredRedirectHosts() {
        assertTrue(MediaHttpPolicy.hostAllowed("CDN.example.com", setOf("cdn.example.com")))
        assertFalse(MediaHttpPolicy.hostAllowed("nested.cdn.example.com", setOf("cdn.example.com")))
        assertFalse(MediaHttpPolicy.hostAllowed("cdn.example.com.attacker.test", setOf("cdn.example.com")))
        assertThrows(IllegalArgumentException::class.java) {
            validatedSource(PlayableSource("https://other.example.com/a", allowedHosts = setOf("cdn.example.com")))
        }
    }

    @Test fun mediaNetworkCannotResolveToLocalOrReservedAddresses() {
        for (address in listOf("127.0.0.1", "10.0.0.1", "172.16.4.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "198.18.0.1", "0.0.0.0", "::1", "fe80::1", "fc00::1", "224.0.0.1")) {
            assertFalse(address, MediaHttpPolicy.publicAddress(InetAddress.getByName(address)))
        }
        assertTrue(MediaHttpPolicy.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(MediaHttpPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
