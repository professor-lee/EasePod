package app.easepod.data

import app.easepod.core.AppSettings
import java.nio.ByteBuffer
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupValidationTest {
    @Test fun fullScreenRoundTripsAndLegacyBackupsRemainWindowed() {
        for (fullScreen in listOf(false, true)) {
            val original = AppSettings(fullScreen = fullScreen, themeId = "black", networkQuality = "high")
            val backup = payload().put("settings", DataJson.settings(original))
            assertEquals(original, BackupJson.parse(backup.toString().toByteArray()).settings)
        }
        val legacy = payload()
        legacy.getJSONObject("settings").remove("fullScreen")
        assertFalse(BackupJson.parse(legacy.toString().toByteArray()).settings.fullScreen)
        legacy.getJSONObject("settings").put("fullScreen", 1)
        assertRejected { BackupJson.parse(legacy.toString().toByteArray()) }
    }

    @Test fun networkQualityRoundTripsAndLegacyBackupsRetainTheDefault() {
        for (quality in listOf("standard", "high", "lossless", "hires")) {
            assertEquals(quality, DataJson.settings(DataJson.settings(AppSettings(networkQuality = quality))).networkQuality)
        }
        val legacy = DataJson.settings(AppSettings())
        legacy.remove("networkQuality")
        assertEquals("standard", DataJson.settings(legacy).networkQuality)
        assertRejected { DataJson.settings(legacy.put("networkQuality", "unsupported")) }
    }

    @Test fun encryptionAuthenticatesHeaderContentAndPassphrase() {
        val plain = "backup content".toByteArray()
        val password = "strong-passphrase".toCharArray()
        val encrypted = BackupCrypto.encrypt(plain, password)
        assertArrayEquals(plain, BackupCrypto.decrypt(encrypted, password))
        assertFalse(encrypted.contentEquals(BackupCrypto.encrypt(plain, password)))
        assertRejected { BackupCrypto.decrypt(encrypted, "wrong-password".toCharArray()) }
        assertRejected { BackupCrypto.decrypt(encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, password) }
        val excessiveKdf = encrypted.copyOf().apply { ByteBuffer.wrap(this).putInt(8, Int.MAX_VALUE) }
        assertRejected { BackupCrypto.decrypt(excessiveKdf, password) }
        assertRejected { BackupCrypto.decrypt(encrypted.copyOf(47), password) }
    }

    @Test fun duplicateKeysMalformedUtf8AndDeepJsonAreRejected() {
        val valid = payload().toString()
        assertRejected { BackupJson.parse(valid.replaceFirst("\"version\":1", "\"version\":1,\"version\":1").toByteArray()) }
        assertRejected { BackupJson.parse((valid + " trailing").toByteArray()) }
        assertRejected { BackupJson.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertRejected { BackupJson.parse(("[".repeat(18) + "0" + "]".repeat(18)).toByteArray()) }
    }

    @Test fun duplicateSourceKeysAreRejectedAndPrivateScopesAreRemappedToUuid() {
        val originalScope = UUID.randomUUID().toString()
        val first = cloudTrack(UUID.randomUUID().toString(), originalScope)
        val valid = payload().put("tracks", JSONArray(listOf(first)))
        val restored = BackupJson.parse(valid.toString().toByteArray()).tracks.single()
        assertNotEquals(originalScope, restored.accountScope)
        assertEquals(restored.accountScope, UUID.fromString(restored.accountScope).toString())
        assertFalse(restored.available)
        valid.getJSONArray("tracks").put(cloudTrack(UUID.randomUUID().toString(), originalScope))
        assertRejected { BackupJson.parse(valid.toString().toByteArray()) }
    }

    @Test fun uuidCaseCannotBypassDuplicateIdentityValidation() {
        val id = UUID.randomUUID().toString()
        val valid = payload().put("tracks", JSONArray(listOf(cloudTrack(id, "public"), cloudTrack(id.uppercase(), "another-account").put("remoteId", "second"))))
        assertRejected { BackupJson.parse(valid.toString().toByteArray()) }
    }

    @Test fun candidateValidationRejectsCrossRootMembershipAndDuplicatePositions() {
        val root = "root"
        val scan = "scan"
        val album = AlbumRow("album", root, "directory", scan, "Album", "Artist", null)
        val tracks = listOf(candidate("first", root, scan), candidate("second", root, scan))
        AndroidLibraryRepository.validateCandidates(root, scan, tracks, listOf(album), listOf(AlbumMemberRow("first", "album", 0), AlbumMemberRow("second", "album", 1)))
        assertRejected { AndroidLibraryRepository.validateCandidates(root, scan, tracks, listOf(album.copy(rootId = "other")), listOf(AlbumMemberRow("first", "album", 0))) }
        assertRejected { AndroidLibraryRepository.validateCandidates(root, scan, tracks, listOf(album), listOf(AlbumMemberRow("first", "album", 0), AlbumMemberRow("second", "album", 0))) }
    }

    private fun payload() = JSONObject().put("version", 1).put("exportId", UUID.randomUUID().toString())
        .put("settings", DataJson.settings(AppSettings())).put("tracks", JSONArray()).put("playlists", JSONArray())

    private fun cloudTrack(id: String, scope: String) = JSONObject().put("id", id).put("sourceId", "example.cloud").put("accountScope", scope)
        .put("remoteId", "42").put("title", "Track").put("artist", "Artist").put("genre", "Genre").put("durationMs", 1000).put("discNumber", 0).put("trackNumber", 0)

    private fun candidate(id: String, root: String, scan: String) = TrackRow(id, root, "doc-$id", scan, "Track", "Artist", "Genre", 1000, null, null, true, "core.local", "local", id, 0, 0, "$id.mp3")
    private inline fun assertRejected(action: () -> Unit) { assertNotNull("Expected validation failure", runCatching(action).exceptionOrNull()) }
}
