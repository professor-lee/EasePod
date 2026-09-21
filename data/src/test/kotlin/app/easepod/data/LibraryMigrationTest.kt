package app.easepod.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryMigrationTest {
    @Test fun versionOneUpgradePreservesHistoryMetadataAndAllReferencedCollections() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-migration-${UUID.randomUUID()}.db"
        try {
            createVersionOne(context, name)
            val firstOpen = open(context, name)
            val migratedIds = try {
                val database = firstOpen
                val dao = database.dao()
                val history = dao.history()
                assertEquals(listOf("second", "first"), history.map { it.trackId })
                assertEquals(listOf(2000L, 1000L), history.map { it.playedAt })
                assertEquals(listOf("Second title", "First title"), history.map { it.title })
                assertEquals(listOf("Embedded album", "Directory album"), history.map { it.albumTitle })
                assertEquals(2, history.map { it.id }.distinct().size)
                assertEquals(listOf("first", "first"), dao.entries("playlist").map { it.trackId })
                assertEquals(listOf("queue-first", "queue-second"), dao.queueEntries().map { it.id })
                assertEquals("queue-second", dao.queueState()!!.currentEntryId)
                assertEquals(555L, dao.queueState()!!.positionMs)
                assertEquals("root", dao.state()!!.activeRootId)
                assertEquals("Directory album", dao.albums().single().title)
                assertNotNull(dao.imported("old-export"))
                database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                history.map { it.id }
            } finally { firstOpen.close() }
            val secondOpen = open(context, name)
            try {
                val database = secondOpen
                val dao = database.dao()
                assertEquals(migratedIds, dao.history().map { it.id })
                dao.putHistory(HistoryRow("new-event", "first", 3000, "Played again", "Artist", "Directory album"))
                assertEquals(listOf("first", "second", "first"), dao.history().map { it.trackId })
                dao.removeHistory(migratedIds.last())
                assertEquals(listOf("new-event", migratedIds.first()), dao.history().map { it.id })
                assertEquals(2, dao.tracks().size)
                assertEquals(2, dao.entries("playlist").size)
                assertEquals(2, dao.queueEntries().size)
            } finally { secondOpen.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun open(context: Context, name: String) = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
        .addMigrations(LibraryDatabase.MIGRATION_1_2).build()

    private fun createVersionOne(context: Context, name: String) {
        val schema = requireNotNull(javaClass.getResourceAsStream("/app.easepod.data.LibraryDatabase/1.json"))
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { database ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                database.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { database.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { database.execSQL(setup.getString(it)) }
            insertTrack(database, "first", "First title", null)
            insertTrack(database, "second", "Second title", "Embedded album")
            database.execSQL("INSERT INTO library_root VALUES ('root', 'test.library', 'root', 'content://test.library/tree/root', 'Music', 1)")
            database.execSQL("INSERT INTO library_state VALUES (1, 'root', 'scan')")
            database.execSQL("INSERT INTO album VALUES ('album', 'root', 'album-doc', 'scan', 'Directory album', 'Artist', NULL)")
            database.execSQL("INSERT INTO album_member VALUES ('first', 'album', 0)")
            database.execSQL("INSERT INTO history VALUES ('first', 1000), ('second', 2000)")
            database.execSQL("INSERT INTO playlist VALUES ('playlist', 'Favorites', 1)")
            database.execSQL("INSERT INTO playlist_entry VALUES ('entry-first', 'playlist', 'first', 0), ('entry-second', 'playlist', 'first', 1)")
            database.execSQL("INSERT INTO queue_entry VALUES ('queue-first', 'first', 0, 0), ('queue-second', 'second', 1, 1)")
            database.execSQL("INSERT INTO queue_state VALUES (1, 'queue-second', 555, 'OFF', 0)")
            database.execSQL("INSERT INTO backup_import VALUES ('old-export', NULL, 100)")
            database.version = 1
        }
    }

    private fun insertTrack(database: SQLiteDatabase, id: String, title: String, album: String?) {
        database.execSQL("INSERT INTO track (id, rootId, documentId, scanId, title, artist, genre, durationMs, artworkUri, contentUri, available, sourceId, accountScope, remoteId, discNumber, trackNumber, fileName, snapshotAlbumTitle) VALUES (?, NULL, NULL, NULL, ?, 'Artist', 'Genre', 1000, NULL, NULL, 0, 'core.local', 'local', ?, 0, 0, '', ?)", arrayOf(id, title, id, album))
    }
}
