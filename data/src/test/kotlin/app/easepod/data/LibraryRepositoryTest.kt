package app.easepod.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.easepod.core.LOCAL_SOURCE
import app.easepod.core.QueueCheckpoint
import app.easepod.core.QueueEntry
import app.easepod.core.ScanPhase
import app.easepod.core.Track
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var repository: AndroidLibraryRepository
    private lateinit var provider: TestDocumentsProvider
    private val tree = Uri.parse("content://test.library/tree/root")
    private val rootId = UUID.randomUUID().toString()
    private val scanId = UUID.randomUUID().toString()
    private val trackId = UUID.randomUUID().toString()
    private val playlistId = UUID.randomUUID().toString()
    private val originalDocumentId = "root/private-music/secret.mp3"

    @Before fun prepare(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        provider = TestDocumentsProvider()
        ShadowContentResolver.registerProviderInternal("test.library", provider)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        dao = database.dao()
        dao.putRoot(RootRow(rootId, "test.library", "root", tree.toString(), "Original library", true))
        dao.putScan(ScanJobRow(scanId, rootId, ScanPhase.COMPLETE.name, tracks = 1))
        dao.putState(LibraryStateRow(activeRootId = rootId, activeScanId = scanId))
        dao.putTracks(listOf(localTrack()))
        dao.putPlaylist(PlaylistRow(playlistId, "Favorites", 1))
        dao.putEntries(listOf(PlaylistEntryRow(UUID.randomUUID().toString(), playlistId, trackId, 0)))
        repository = AndroidLibraryRepository(context, database, AndroidSettingsRepository(context))
        withTimeout(10_000) { repository.library.first { it.ready } }
    }

    @After fun cleanup() {
        repository.close()
        database.close()
    }

    @Test fun scanPreviewAndCancellationKeepPublishedRootAndTracks() = runBlocking {
        provider.rootName = "Renamed folder"
        repository.scanFolder(tree.toString())
        assertEquals(ScanPhase.REVIEW, repository.scan.value.phase)
        assertEquals("Original library", dao.root(rootId)!!.name)
        assertTrue(dao.track(trackId)!!.available)
        assertEquals(scanId, dao.state()!!.activeScanId)
        repository.cancelScan()
        withTimeout(10_000) { repository.scan.first { it.phase == ScanPhase.CANCELLED } }
        assertEquals(scanId, dao.state()!!.activeScanId)
        assertEquals("Original library", repository.library.value.root!!.name)
        assertTrue(dao.candidates(dao.lastScan()!!.id).isEmpty())
    }

    @Test fun cancelledCandidateReleasesItsGrantButKeepsActiveAndUnrelatedGrants() = runBlocking {
        val candidate = Uri.parse("content://test.library/tree/candidate")
        val unrelated = Uri.parse("content://test.library/tree/unrelated")
        context.contentResolver.takePersistableUriPermission(candidate, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(unrelated, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        repository.scanFolder(candidate.toString())
        assertEquals(ScanPhase.REVIEW, repository.scan.value.phase)
        repository.releaseUnusedFolderGrants()
        assertTrue(hasGrant(candidate))
        repository.cancelScan()
        withTimeout(10_000) { repository.scan.first { it.phase == ScanPhase.CANCELLED } }
        assertFalse(hasGrant(candidate))
        assertTrue(hasGrant(tree))
        assertTrue(hasGrant(unrelated))
        assertEquals(rootId, dao.state()!!.activeRootId)
    }

    @Test fun failedCandidateValidationReleasesOnlyItsNewGrant() = runBlocking {
        val candidate = Uri.parse("content://test.library/tree/failed")
        context.contentResolver.takePersistableUriPermission(candidate, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        provider.failQueries = true
        repository.scanFolder(candidate.toString())
        assertEquals(ScanPhase.FAILED, repository.scan.value.phase)
        assertFalse(hasGrant(candidate))
        assertTrue(hasGrant(tree))
        assertEquals(rootId, dao.state()!!.activeRootId)
    }

    @Test fun releasedRootWaitsUntilPlayerNoLongerRetainsItsTrack() = runBlocking {
        repository.removeRoot()
        repository.releaseUnusedFolderGrants(setOf(trackId))
        assertTrue(hasGrant(tree))
        repository.releaseUnusedFolderGrants()
        assertFalse(hasGrant(tree))
        assertFalse(dao.root(rootId)!!.permissionValid)
        assertEquals(trackId, dao.entries(playlistId).single().trackId)
    }

    @Test fun successfulPublicationPreservesUnavailablePlaylistReferences() = runBlocking {
        repository.scanFolder(tree.toString())
        repository.publishScan()
        assertEquals(ScanPhase.COMPLETE, repository.scan.value.phase)
        assertNotEquals(scanId, dao.state()!!.activeScanId)
        assertFalse(dao.track(trackId)!!.available)
        assertEquals(trackId, dao.entries(playlistId).single().trackId)
        repository.cancelScan()
        repository.checkPermission()
        assertEquals(ScanPhase.COMPLETE, repository.scan.value.phase)
    }

    @Test fun incompleteProviderResultsAndTransientReadFailurePreservePublishedLibrary() = runBlocking {
        provider.error = "Provider enumeration failed"
        repository.scanFolder(tree.toString())
        assertEquals(ScanPhase.FAILED, repository.scan.value.phase)
        assertEquals(scanId, dao.state()!!.activeScanId)
        assertTrue(dao.track(trackId)!!.available)
        assertEquals(1, dao.entries(playlistId).size)
        provider.error = null
        provider.failQueries = true
        repository.checkPermission()
        assertTrue(dao.root(rootId)!!.permissionValid)
        assertTrue(dao.track(trackId)!!.available)
    }

    @Test fun permissionLossRetainsQueuePlaylistAndRootForRelinking() = runBlocking {
        repository.saveQueue(QueueCheckpoint(listOf(QueueEntry("entry", localTrack().toTrack())), "entry", 700))
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        repository.checkPermission()
        assertEquals(rootId, dao.state()!!.activeRootId)
        assertFalse(repository.library.value.root!!.permissionValid)
        assertFalse(repository.library.value.tracks.single().available)
        assertEquals(trackId, dao.entries(playlistId).single().trackId)
        assertEquals(trackId, repository.loadQueue().entries.single().track.id)
        assertEquals(700L, repository.loadQueue().positionMs)
    }

    @Test fun supersedingAnInFlightScanCannotOverwriteNewReview() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        provider.blockNextChildren = entered to release
        val first = async(Dispatchers.IO) { repository.scanFolder(tree.toString()) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        provider.rootName = "Latest folder"
        val second = async(Dispatchers.IO) { repository.scanFolder(tree.toString()) }
        try {
            withTimeout(10_000) { repository.scan.first { it.phase == ScanPhase.REVIEW && it.folder == "Latest folder" } }
        } finally { release.countDown() }
        second.await()
        runCatching { first.await() }
        repository.publishScan()
        assertEquals("Latest folder", repository.library.value.root!!.name)
        assertEquals(ScanPhase.COMPLETE, repository.scan.value.phase)
    }

    @Test fun queueRestorationClampsPositionAndRetainsRepeatedTracks() = runBlocking {
        val track = localTrack().toTrack()
        repository.saveQueue(QueueCheckpoint(listOf(QueueEntry("one", track), QueueEntry("two", track)), "two", 9000, originalOrder = listOf("two", "one")))
        val restored = repository.loadQueue()
        assertEquals(listOf("one", "two"), restored.entries.map { it.id })
        assertEquals(listOf("two", "one"), restored.originalOrder)
        assertEquals(1000L, restored.positionMs)
        assertTrue(dao.track(trackId)!!.available)
        assertEquals(originalDocumentId, dao.track(trackId)!!.documentId)
    }

    @Test fun interruptedScanCandidatesAreRemovedOnStartup() = runBlocking {
        val interrupted = UUID.randomUUID().toString()
        dao.putScan(ScanJobRow(interrupted, rootId, ScanPhase.REVIEW.name))
        dao.putCandidates(listOf(ScanCandidateRow(interrupted, "draft", "track", "{}")))
        repository.close()
        repository = AndroidLibraryRepository(context, database, AndroidSettingsRepository(context))
        withTimeout(10_000) { repository.library.first { it.ready } }
        assertEquals(ScanPhase.CANCELLED.name, dao.scanJob(interrupted)!!.phase)
        assertTrue(dao.candidates(interrupted).isEmpty())
        assertTrue(dao.track(trackId)!!.available)
    }

    @Test fun repeatedHistoryRetainsEachSnapshotAndRemovalPreservesQueuePlaylistAndTrack() = runBlocking {
        repository.saveQueue(QueueCheckpoint(listOf(QueueEntry("listening", localTrack().toTrack())), "listening", 700))
        repository.recordHistory(trackId)
        val first = repository.library.value.history.single()
        assertEquals("Local title", first.track.title)
        assertTrue(first.playedAt > 0)
        dao.putTracks(listOf(localTrack().copy(title = "Updated title", artist = "Updated artist", snapshotAlbumTitle = "Updated album")))
        repository.recordHistory(trackId)
        val events = repository.library.value.history
        assertEquals(listOf("Updated title", "Local title"), events.map { it.track.title })
        assertEquals(listOf("Updated artist", "Artist"), events.map { it.track.artist })
        assertEquals("Updated album", events.first().track.albumTitle)
        assertEquals(2, events.map { it.id }.distinct().size)
        assertEquals(listOf(trackId, trackId), events.map { it.track.id })
        assertTrue(events.first().playedAt >= first.playedAt)
        repository.removeHistory(first.id)
        assertEquals(listOf(events.first().id), repository.library.value.history.map { it.id })
        repository.removeHistory(first.id)
        assertEquals(1, repository.library.value.history.size)
        assertEquals("Updated title", dao.track(trackId)!!.title)
        assertEquals(trackId, dao.entries(playlistId).single().trackId)
        assertEquals("listening", repository.loadQueue().currentEntryId)
        assertEquals(700L, repository.loadQueue().positionMs)
        repository.clearHistory()
        assertTrue(repository.library.value.history.isEmpty())
        assertNotNull(dao.track(trackId))
        assertEquals(1, dao.entries(playlistId).size)
        assertEquals(1, repository.loadQueue().entries.size)
    }

    @Test fun historyRetentionUsesEventIdentityAndStableInsertionOrderForEqualTimes() = runBlocking {
        repeat(105) { index -> dao.putHistory(HistoryRow("event-$index", trackId, 1000, "Played $index", "Artist", null)) }
        dao.trimHistory()
        assertEquals((104 downTo 5).map { "event-$it" }, dao.history().map { it.id })
        repository.removeHistory("event-50")
        assertEquals(99, repository.library.value.history.size)
        assertTrue(repository.library.value.history.all { it.track.id == trackId })
        assertFalse(repository.library.value.history.any { it.id == "event-50" })
        assertRejected { database.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = ?", arrayOf(trackId)) }
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun playlistReorderingPreservesDuplicatesAndForeignKeys() = runBlocking {
        repository.addToPlaylist(playlistId, trackId)
        val entries = dao.entries(playlistId)
        repository.movePlaylistEntry(playlistId, entries[1].id, -1)
        assertEquals(listOf(entries[1].id, entries[0].id), dao.entries(playlistId).map { it.id })
        repository.removeFromPlaylist(playlistId, entries[0].id)
        assertEquals(0, dao.entries(playlistId).single().position)
        assertRejected { database.openHelper.writableDatabase.execSQL("DELETE FROM track WHERE id = ?", arrayOf(trackId)) }
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun relinkingUnavailableLocalTrackPreservesPlaylistEntriesAndQueueSnapshot() = runBlocking {
        val importedId = UUID.randomUUID().toString()
        val imported = localTrack().copy(id = importedId, rootId = null, documentId = null, scanId = null,
            contentUri = null, available = false, remoteId = "relink/$importedId")
        dao.putTracks(listOf(imported))
        repository.addToPlaylist(playlistId, importedId)
        repository.addToPlaylist(playlistId, importedId)
        val before = dao.entries(playlistId)
        repository.saveQueue(QueueCheckpoint(listOf(QueueEntry("imported-entry", imported.toTrack())), "imported-entry", 500))
        repository.relinkPlaylistTrack(importedId, trackId)
        val after = dao.entries(playlistId)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.position }, after.map { it.position })
        assertEquals(listOf(trackId, trackId, trackId), after.map { it.trackId })
        assertEquals(importedId, repository.loadQueue().entries.single().track.id)
        assertFalse(dao.track(importedId)!!.available)
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun relinkingRejectsUnavailableTargetOrRevokedPermissionWithoutChangingReferences() = runBlocking {
        val importedId = UUID.randomUUID().toString()
        dao.putTracks(listOf(localTrack().copy(id = importedId, rootId = null, documentId = null, scanId = null,
            contentUri = null, available = false, remoteId = "relink/$importedId")))
        repository.addToPlaylist(playlistId, importedId)
        val before = dao.entries(playlistId)
        assertRejected { repository.relinkPlaylistTrack(trackId, importedId) }
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertRejected { repository.relinkPlaylistTrack(importedId, trackId) }
        assertEquals(before, dao.entries(playlistId))
    }

    @Test fun encryptedBackupOmitsSafIdentityAndImportIsIdempotent() = runBlocking {
        repository.addToPlaylist(playlistId, trackId)
        val password = "test-password".toCharArray()
        val encrypted = repository.exportBackup(password)
        val plain = BackupCrypto.decrypt(encrypted, password).toString(Charsets.UTF_8)
        listOf(originalDocumentId, tree.toString(), "content://", "artworkUri", "documentId", "rootId", "saf/").forEach { assertFalse("Leaked $it", plain.contains(it)) }
        repository.importBackup(encrypted, password)
        assertEquals(2, dao.playlists().size)
        assertEquals(2, dao.tracks().size)
        assertEquals(4, dao.playlistEntries().size)
        val imported = dao.tracks().first { it.id != trackId }
        assertFalse(imported.available)
        assertNull(imported.contentUri)
        assertNull(imported.rootId)
        assertEquals("relink/${imported.id}", imported.remoteId)
        repository.importBackup(encrypted, password)
        assertEquals(2, dao.playlists().size)
        assertEquals(4, dao.playlistEntries().size)
        assertTrue(dao.pendingSettings().isEmpty())
    }

    @Test fun corruptBackupAndInvalidRelationshipsDoNotModifyLibrary() = runBlocking {
        val password = "test-password".toCharArray()
        val encrypted = repository.exportBackup(password)
        val corrupt = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertRejected { repository.importBackup(corrupt, password) }
        val json = JSONObject(BackupCrypto.decrypt(encrypted, password).toString(Charsets.UTF_8))
        json.getJSONArray("playlists").getJSONObject(0).getJSONArray("entries").getJSONObject(0).put("trackId", UUID.randomUUID().toString())
        val invalid = BackupCrypto.encrypt(json.toString().toByteArray(), password)
        assertRejected { repository.importBackup(invalid, password) }
        assertEquals(1, dao.tracks().size)
        assertEquals(1, dao.playlists().size)
        assertEquals(1, dao.playlistEntries().size)
        assertTrue(dao.pendingSettings().isEmpty())
    }

    @Test fun failedRoomImportRollsBackEveryInsertedRecord() = runBlocking {
        val password = "test-password".toCharArray()
        val encrypted = repository.exportBackup(password)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON playlist_entry BEGIN SELECT RAISE(ABORT, 'Injected storage failure'); END")
        assertRejected { repository.importBackup(encrypted, password) }
        assertEquals(1, dao.tracks().size)
        assertEquals(1, dao.playlists().size)
        assertEquals(1, dao.playlistEntries().size)
        val exportId = JSONObject(BackupCrypto.decrypt(encrypted, password).toString(Charsets.UTF_8)).getString("exportId")
        assertNull(dao.imported(exportId))
    }

    @Test fun deviceLockBeforeCommitCancelsBothImportedDataAndSettingsJournal() = runBlocking {
        val password = "test-password".toCharArray()
        val encrypted = repository.exportBackup(password)
        val settings = AndroidSettingsRepository(context)
        settings.update { it.copy(haptics = false) }
        var guardCalls = 0
        assertRejected {
            repository.importBackup(encrypted, password) {
                guardCalls++
                if (guardCalls == 2) throw kotlinx.coroutines.CancellationException("Device locked during import")
            }
        }
        assertEquals(2, guardCalls)
        assertEquals(1, dao.tracks().size)
        assertEquals(1, dao.playlists().size)
        assertTrue(dao.pendingSettings().isEmpty())
        assertFalse(settings.current().haptics)
    }

    @Test fun failedPublicationRollsBackTrackAvailabilityAndActiveScan() = runBlocking {
        repository.scanFolder(tree.toString())
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_publish BEFORE INSERT ON library_state BEGIN SELECT RAISE(ABORT, 'Injected publication failure'); END")
        assertRejected { repository.publishScan() }
        assertEquals(scanId, dao.state()!!.activeScanId)
        assertTrue(dao.track(trackId)!!.available)
        assertEquals(ScanPhase.REVIEW, repository.scan.value.phase)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_publish")
        repository.publishScan()
        assertEquals(ScanPhase.COMPLETE, repository.scan.value.phase)
    }

    @Test fun deviceLockBeforePublicationCommitPreservesOriginalLibraryAndReview() = runBlocking {
        repository.scanFolder(tree.toString())
        val candidateId = dao.lastScan()!!.id
        var guardCalls = 0
        assertRejected {
            repository.publishScan {
                guardCalls++
                if (guardCalls == 2) throw kotlinx.coroutines.CancellationException("Device locked during publication")
            }
        }
        assertEquals(2, guardCalls)
        assertEquals(scanId, dao.state()!!.activeScanId)
        assertTrue(dao.track(trackId)!!.available)
        assertEquals(trackId, dao.entries(playlistId).single().trackId)
        assertEquals(ScanPhase.REVIEW.name, dao.scanJob(candidateId)!!.phase)
        assertEquals(ScanPhase.REVIEW, repository.scan.value.phase)
        repository.publishScan()
        assertEquals(ScanPhase.COMPLETE, repository.scan.value.phase)
    }

    @Test fun permissionRevokedBeforePublicationCommitRollsBackAndMarksOriginalRootUnavailable() = runBlocking {
        repository.scanFolder(tree.toString())
        var guardCalls = 0
        assertRejected {
            repository.publishScan {
                guardCalls++
                if (guardCalls == 2) context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        assertEquals(2, guardCalls)
        assertEquals(scanId, dao.state()!!.activeScanId)
        assertEquals(rootId, dao.state()!!.activeRootId)
        assertEquals(trackId, dao.entries(playlistId).single().trackId)
        assertFalse(repository.library.value.root!!.permissionValid)
        assertFalse(dao.track(trackId)!!.available)
        assertEquals(ScanPhase.REVIEW, repository.scan.value.phase)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        repository.publishScan()
        assertEquals(ScanPhase.COMPLETE, repository.scan.value.phase)
    }

    @Test fun cloudTracksCanBeRememberedWithoutReplacingLocalRows() = runBlocking {
        val cloud = Track(UUID.randomUUID().toString(), "Cloud", sourceId = "example.cloud", accountScope = "public", remoteId = "42", contentUri = "https://media.invalid/?token=secret")
        repository.rememberTracks(listOf(cloud))
        repository.addToPlaylist(playlistId, cloud.id)
        repository.rememberTracks(listOf(cloud.copy(title = "Updated")))
        assertEquals("Updated", dao.track(cloud.id)!!.title)
        assertNull(dao.track(cloud.id)!!.contentUri)
        assertEquals(cloud.id, dao.entries(playlistId).last().trackId)
        assertRejected { repository.rememberTracks(listOf(cloud.copy(id = trackId))) }
        assertEquals(originalDocumentId, dao.track(trackId)!!.documentId)
    }

    @Test fun cloudHashIdentifiersRoundTripInBackupsWithoutDuplicatingExistingSources() = runBlocking {
        val cloud = Track("a".repeat(64), "Cloud", sourceId = "example.cloud", accountScope = "public", remoteId = "42")
        repository.rememberTracks(listOf(cloud, cloud))
        repository.addToPlaylist(playlistId, cloud.id)
        val password = "test-password".toCharArray()
        val encrypted = repository.exportBackup(password)
        val payload = BackupJson.parse(BackupCrypto.decrypt(encrypted, password))
        val portable = payload.tracks.single { it.sourceId == "example.cloud" }
        assertEquals(DataJson.portableId(cloud.id), portable.id)
        assertEquals(portable.id, payload.entries.last().trackId)
        repository.importBackup(encrypted, password)
        assertEquals(1, dao.tracks().count { it.sourceId == "example.cloud" })
        assertEquals(2, dao.playlistEntries().count { it.trackId == cloud.id })
    }

    @Test fun localLyricsUseSiblingFileAndRespectRevokedPermission() = runBlocking {
        provider.children = listOf(TestDocumentsProvider.Document("root/secret.txt", "secret.txt", "text/plain"), TestDocumentsProvider.Document("root/secret.lrc", "secret.lrc", "text/plain"))
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "root/secret.lrc")
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { "[00:01.00]First line".byteInputStream() }
        assertEquals("[00:01.00]First line", repository.localLyrics(trackId))
        context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertRejected { repository.localLyrics(trackId) }
        assertFalse(dao.root(rootId)!!.permissionValid)
    }

    @Test fun creatingPlaylistWithInitialTracksIsAtomicAndRetainsDuplicateEntries() = runBlocking {
        val id = repository.createPlaylist("With tracks", listOf(trackId, trackId))
        val entries = dao.entries(id)
        assertEquals(listOf(trackId, trackId), entries.map { it.trackId })
        assertEquals(listOf(0, 1), entries.map { it.position })
        assertEquals(2, entries.map { it.id }.distinct().size)
        val count = dao.playlists().size
        assertRejected { repository.createPlaylist("Missing track", listOf(trackId, "missing")) }
        assertEquals(count, dao.playlists().size)
    }

    @Test fun lyricsSidecarsDoNotMakeAnEmptyScanPartiallySuccessful() = runBlocking {
        provider.children = listOf(TestDocumentsProvider.Document("root/song.LRC", "song.LRC", "text/plain"),
            TestDocumentsProvider.Document("root/song.txt", "song.txt", "text/plain"))
        repository.scanFolder(tree.toString())
        assertEquals(ScanPhase.REVIEW, repository.scan.value.phase)
        assertEquals(0, repository.scan.value.skipped)
    }

    @Test fun emptySidecarFallsBackToEmbeddedLyricsAndStopsAtId3Tag() = runBlocking {
        provider.children = listOf(TestDocumentsProvider.Document("root/secret.lrc", "secret.lrc", "text/plain"))
        val sidecar = DocumentsContract.buildDocumentUriUsingTree(tree, "root/secret.lrc")
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(sidecar) { "  ".byteInputStream() }
        val audio = DocumentsContract.buildDocumentUriUsingTree(tree, originalDocumentId)
        val tag = LyricsFixtures.id3("USLT", byteArrayOf(3) + "eng\u0000[00:02.00]Embedded line".toByteArray())
        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(audio) { tag.inputStream() }
        assertEquals("[00:02.00]Embedded line", repository.localLyrics(trackId))
    }

    private fun localTrack() = TrackRow(trackId, rootId, originalDocumentId, scanId, "Local title", "Artist", "Genre", 1000,
        "file:///private/artwork.png", DocumentsContract.buildDocumentUriUsingTree(tree, originalDocumentId).toString(), true, LOCAL_SOURCE, "local", "saf/$rootId/private-secret", 1, 1, "secret.mp3")

    private fun hasGrant(uri: Uri) = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private inline fun assertRejected(action: () -> Unit) { assertNotNull("Expected operation to fail", runCatching(action).exceptionOrNull()) }
}

private class TestDocumentsProvider : ContentProvider() {
    data class Document(val id: String, val name: String, val mime: String)
    @Volatile var rootName = "Music"
    @Volatile var error: String? = null
    @Volatile var failQueries = false
    @Volatile var children = emptyList<Document>()
    @Volatile var blockNextChildren: Pair<CountDownLatch, CountDownLatch>? = null

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (failQueries) throw java.io.IOException("Provider temporarily offline")
        val childQuery = uri.lastPathSegment == "children"
        if (childQuery) blockNextChildren?.let { block ->
            blockNextChildren = null
            block.first.countDown()
            check(block.second.await(10, TimeUnit.SECONDS))
        }
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return MatrixCursor(columns).apply {
            if (childQuery) {
                children.forEach { addRow(arrayOf(it.id, it.name, it.mime)) }
                extras = Bundle().apply { error?.let { putString(DocumentsContract.EXTRA_ERROR, it) } }
            } else addRow(arrayOf(DocumentsContract.getTreeDocumentId(uri), rootName, DocumentsContract.Document.MIME_TYPE_DIR))
        }
    }
    override fun getType(uri: Uri) = DocumentsContract.Document.MIME_TYPE_DIR
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unsupported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
