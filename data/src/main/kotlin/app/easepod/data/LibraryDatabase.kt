package app.easepod.data

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.easepod.core.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "library_root", indices = [Index(value = ["authority", "documentId"], unique = true)])
internal data class RootRow(@PrimaryKey val id: String, val authority: String, val documentId: String, val uri: String, val name: String, val permissionValid: Boolean)

@Entity(tableName = "library_state")
internal data class LibraryStateRow(@PrimaryKey val id: Int = 1, val activeRootId: String? = null, val activeScanId: String? = null)

@Entity(tableName = "track", indices = [Index(value = ["rootId", "documentId"], unique = true)])
internal data class TrackRow(
    @PrimaryKey val id: String,
    val rootId: String?, val documentId: String?, val scanId: String?,
    val title: String, val artist: String, val genre: String,
    val durationMs: Long?, val artworkUri: String?, val contentUri: String?,
    val available: Boolean, val sourceId: String, val accountScope: String, val remoteId: String,
    val discNumber: Int, val trackNumber: Int, val fileName: String, val snapshotAlbumTitle: String? = null,
) {
    fun toTrack(album: AlbumRow? = null) = Track(id, title, artist, album?.id, album?.title ?: snapshotAlbumTitle, genre,
        durationMs, artworkUri, contentUri, available, sourceId, accountScope, remoteId, discNumber, trackNumber)
}

@Entity(tableName = "album", indices = [Index(value = ["rootId", "documentId"], unique = true)])
internal data class AlbumRow(@PrimaryKey val id: String, val rootId: String, val documentId: String, val scanId: String, val title: String, val artist: String, val artworkUri: String?)

@Entity(tableName = "album_member", foreignKeys = [
    ForeignKey(entity = AlbumRow::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TrackRow::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["albumId", "position"], unique = true)])
internal data class AlbumMemberRow(@PrimaryKey val trackId: String, val albumId: String, val position: Int)

@Entity(tableName = "playlist")
internal data class PlaylistRow(@PrimaryKey val id: String, val title: String, val createdAt: Long)

@Entity(tableName = "playlist_entry", foreignKeys = [
    ForeignKey(entity = PlaylistRow::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TrackRow::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["playlistId", "position"], unique = true), Index("trackId")])
internal data class PlaylistEntryRow(@PrimaryKey val id: String, val playlistId: String, val trackId: String, val position: Int)

@Entity(tableName = "history", foreignKeys = [ForeignKey(entity = TrackRow::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("trackId"), Index("playedAt")])
internal data class HistoryRow(@PrimaryKey val id: String, val trackId: String, val playedAt: Long, val title: String, val artist: String, val albumTitle: String?)

@Entity(tableName = "scan_job")
internal data class ScanJobRow(@PrimaryKey val id: String, val rootId: String, val phase: String, val tracks: Int = 0, val albums: Int = 0, val skipped: Int = 0, val issues: String = "[]", val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "scan_candidate", primaryKeys = ["scanId", "key"], foreignKeys = [ForeignKey(entity = ScanJobRow::class, parentColumns = ["id"], childColumns = ["scanId"], onDelete = ForeignKey.CASCADE)])
internal data class ScanCandidateRow(val scanId: String, val key: String, val kind: String, val payload: String)

@Entity(tableName = "queue_state")
internal data class QueueStateRow(@PrimaryKey val id: Int = 1, val currentEntryId: String?, val positionMs: Long, val repeat: String, val shuffle: Boolean)

@Entity(tableName = "queue_entry", foreignKeys = [ForeignKey(entity = TrackRow::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("trackId"), Index(value = ["position"], unique = true)])
internal data class QueueEntryRow(@PrimaryKey val id: String, val trackId: String, val position: Int, val originalPosition: Int)

@Entity(tableName = "backup_import")
internal data class BackupImportRow(@PrimaryKey val exportId: String, val settingsJson: String?, val importedAt: Long)

@Dao
internal interface LibraryDao {
    @Query("SELECT * FROM track ORDER BY title COLLATE NOCASE, id") suspend fun tracks(): List<TrackRow>
    @Query("SELECT * FROM track WHERE id = :id") suspend fun track(id: String): TrackRow?
    @Query("SELECT * FROM track") fun trackChanges(): Flow<List<TrackRow>>
    @Query("SELECT * FROM library_state WHERE id = 1") suspend fun state(): LibraryStateRow?
    @Query("SELECT * FROM library_root WHERE id = :id") suspend fun root(id: String): RootRow?
    @Query("SELECT * FROM library_root WHERE authority = :authority AND documentId = :documentId") suspend fun findRoot(authority: String, documentId: String): RootRow?
    @Query("SELECT * FROM library_root") suspend fun roots(): List<RootRow>
    @Query("SELECT * FROM album") suspend fun albums(): List<AlbumRow>
    @Query("SELECT * FROM album_member ORDER BY position") suspend fun albumMembers(): List<AlbumMemberRow>
    @Query("SELECT COALESCE(a.title, t.snapshotAlbumTitle) FROM track t LEFT JOIN album_member m ON m.trackId = t.id LEFT JOIN album a ON a.id = m.albumId WHERE t.id = :trackId") suspend fun historyAlbumTitle(trackId: String): String?
    @Query("SELECT * FROM playlist ORDER BY createdAt, id") suspend fun playlists(): List<PlaylistRow>
    @Query("SELECT * FROM playlist_entry ORDER BY playlistId, position") suspend fun playlistEntries(): List<PlaylistEntryRow>
    @Query("SELECT * FROM playlist_entry WHERE playlistId = :id ORDER BY position") suspend fun entries(id: String): List<PlaylistEntryRow>
    @Query("SELECT * FROM history ORDER BY playedAt DESC, rowid DESC LIMIT 100") suspend fun history(): List<HistoryRow>
    @Query("SELECT * FROM scan_job ORDER BY createdAt DESC LIMIT 1") suspend fun lastScan(): ScanJobRow?
    @Query("SELECT * FROM scan_job WHERE id = :id") suspend fun scanJob(id: String): ScanJobRow?
    @Query("SELECT EXISTS(SELECT 1 FROM scan_job WHERE rootId = :rootId AND phase = 'COMPLETE')") suspend fun hasPublishedRoot(rootId: String): Boolean
    @Query("SELECT * FROM scan_candidate WHERE scanId = :id") suspend fun candidates(id: String): List<ScanCandidateRow>
    @Query("SELECT * FROM queue_state WHERE id = 1") suspend fun queueState(): QueueStateRow?
    @Query("SELECT * FROM queue_entry ORDER BY position") suspend fun queueEntries(): List<QueueEntryRow>
    @Query("SELECT * FROM backup_import WHERE exportId = :id") suspend fun imported(id: String): BackupImportRow?
    @Query("SELECT * FROM backup_import WHERE settingsJson IS NOT NULL") suspend fun pendingSettings(): List<BackupImportRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(row: LibraryStateRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRoot(row: RootRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTracks(rows: List<TrackRow>)
    @androidx.room.Upsert suspend fun putTracks(rows: List<TrackRow>)
    @androidx.room.Upsert suspend fun putAlbums(rows: List<AlbumRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMembers(rows: List<AlbumMemberRow>)
    @androidx.room.Upsert suspend fun putPlaylist(row: PlaylistRow)
    @Insert suspend fun putEntries(rows: List<PlaylistEntryRow>)
    @Insert suspend fun putHistory(row: HistoryRow)
    @androidx.room.Upsert suspend fun putScan(row: ScanJobRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCandidates(rows: List<ScanCandidateRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putQueueState(row: QueueStateRow)
    @Insert suspend fun putQueueEntries(rows: List<QueueEntryRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putImport(row: BackupImportRow)
    @Query("UPDATE track SET available = 0 WHERE rootId IS NOT NULL") suspend fun markLocalUnavailable()
    @Query("UPDATE track SET available = 0 WHERE rootId = :rootId") suspend fun markRootUnavailable(rootId: String)
    @Query("DELETE FROM album_member WHERE albumId IN (SELECT id FROM album WHERE rootId = :rootId)") suspend fun clearMembers(rootId: String)
    @Query("DELETE FROM playlist WHERE id = :id") suspend fun deletePlaylist(id: String)
    @Query("UPDATE playlist SET title = :title WHERE id = :id") suspend fun renamePlaylist(id: String, title: String)
    @Query("DELETE FROM playlist_entry WHERE playlistId = :id") suspend fun clearEntries(id: String)
    @Query("UPDATE playlist_entry SET trackId = :newId WHERE trackId = :oldId") suspend fun relinkPlaylistTrack(oldId: String, newId: String)
    @Query("DELETE FROM history") suspend fun clearHistory()
    @Query("DELETE FROM history WHERE id = :id") suspend fun removeHistory(id: String)
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY playedAt DESC, rowid DESC LIMIT 100)") suspend fun trimHistory()
    @Query("DELETE FROM scan_candidate WHERE scanId = :id") suspend fun clearCandidates(id: String)
    @Query("UPDATE scan_job SET phase = 'CANCELLED' WHERE phase IN ('SCANNING', 'REVIEW')") suspend fun cancelUnfinishedScans()
    @Query("DELETE FROM scan_candidate WHERE scanId IN (SELECT id FROM scan_job WHERE phase = 'CANCELLED')") suspend fun clearCancelledCandidates()
    @Query("DELETE FROM queue_entry") suspend fun clearQueueEntries()
}

@Database(entities = [RootRow::class, LibraryStateRow::class, TrackRow::class, AlbumRow::class, AlbumMemberRow::class,
    PlaylistRow::class, PlaylistEntryRow::class, HistoryRow::class, ScanJobRow::class, ScanCandidateRow::class,
    QueueStateRow::class, QueueEntryRow::class, BackupImportRow::class], version = 2, exportSchema = true)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE history_new (id TEXT NOT NULL PRIMARY KEY, trackId TEXT NOT NULL, playedAt INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, albumTitle TEXT, FOREIGN KEY(trackId) REFERENCES track(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("INSERT INTO history_new (id, trackId, playedAt, title, artist, albumTitle) SELECT 'legacy:' || h.trackId, h.trackId, h.playedAt, t.title, t.artist, COALESCE(a.title, t.snapshotAlbumTitle) FROM history h JOIN track t ON t.id = h.trackId LEFT JOIN album_member m ON m.trackId = t.id LEFT JOIN album a ON a.id = m.albumId ORDER BY h.playedAt, h.trackId")
                db.execSQL("DROP TABLE history")
                db.execSQL("ALTER TABLE history_new RENAME TO history")
                db.execSQL("CREATE INDEX index_history_trackId ON history(trackId)")
                db.execSQL("CREATE INDEX index_history_playedAt ON history(playedAt)")
            }
        }
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "easepod.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
