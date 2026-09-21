package app.easepod.plugins

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "plugin_registry", indices = [Index(value = ["sourceId"], unique = true)])
data class PluginRecord(@PrimaryKey val id: String, val packageName: String, val serviceName: String,
    val approvedSignature: String = "", val enabled: Boolean = false,
    @ColumnInfo(defaultValue = "''") val sourceId: String = "",
    @ColumnInfo(defaultValue = "0") val quarantined: Boolean = false)
@Entity(tableName = "plugin_account", primaryKeys = ["pluginId", "remoteId"],
    indices = [Index(value = ["scope"], unique = true)])
data class AccountRecord(val pluginId: String, val remoteId: String, val scope: String,
    @ColumnInfo(defaultValue = "1") val active: Boolean = true)
@Entity(tableName = "plugin_index_state")
data class PluginIndexState(@PrimaryKey val keyId: String, val highestVersion: Long)
@Dao interface PluginDao {
    @Query("SELECT * FROM plugin_registry") suspend fun all(): List<PluginRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: PluginRecord)
    @Query("SELECT * FROM plugin_account WHERE pluginId = :plugin") suspend fun accounts(plugin: String): List<AccountRecord>
    @Query("SELECT * FROM plugin_account") fun observeAccounts(): Flow<List<AccountRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun account(record: AccountRecord)
    @Query("DELETE FROM plugin_account WHERE pluginId = :plugin AND scope = :scope") suspend fun removeAccount(plugin: String, scope: String)
    @Query("UPDATE plugin_account SET active = 0 WHERE pluginId = :plugin AND scope = :scope") suspend fun deactivateAccount(plugin: String, scope: String)
    @Query("DELETE FROM plugin_account WHERE pluginId = :plugin") suspend fun removeAccounts(plugin: String)
    @Query("DELETE FROM plugin_registry WHERE id = :plugin") suspend fun remove(plugin: String)
    @Query("SELECT * FROM plugin_registry WHERE id = :plugin") suspend fun get(plugin: String): PluginRecord?
    @Query("SELECT * FROM plugin_index_state WHERE keyId = :keyId") suspend fun indexState(keyId: String): PluginIndexState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putIndex(state: PluginIndexState)
}
@Database(entities = [PluginRecord::class, AccountRecord::class, PluginIndexState::class], version = 3, exportSchema = false)
abstract class PluginDatabase : RoomDatabase() {
    abstract fun dao(): PluginDao
    companion object {
        @Volatile private var instance: PluginDatabase? = null
        fun create(context: Context): PluginDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PluginDatabase::class.java, "plugins.db")
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE plugin_registry ADD COLUMN sourceId TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE plugin_registry ADD COLUMN quarantined INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("UPDATE plugin_registry SET sourceId = 'plugin.' || lower(hex(randomblob(16)))")
                        db.execSQL("CREATE UNIQUE INDEX index_plugin_registry_sourceId ON plugin_registry(sourceId)")
                        db.execSQL("CREATE UNIQUE INDEX index_plugin_account_scope ON plugin_account(scope)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS plugin_index_state (keyId TEXT NOT NULL PRIMARY KEY, highestVersion INTEGER NOT NULL)")
                    }
                }, object : Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE plugin_account ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
                    }
                }).build().also { instance = it }
        }
    }
}
