package com.timeorganizer.usagetracker.data.local.outbox

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A session built from the usage log, waiting to be sent — or sent, and kept until the cursor has passed its
 * start, so re-reading the log from the cursor cannot queue it again. `(packageName, startMs)` is unique for
 * the same reason; it is also the server's dedup key within a device.
 */
@Entity(
    tableName = "pending_session",
    indices = [Index(value = ["packageName", "startMs"], unique = true), Index(value = ["sentAtMs", "startMs"])]
)
data class PendingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val startMs: Long,
    val endMs: Long,
    val sentAtMs: Long? = null
)

/**
 * A pickup that is over (prompt 14, `PickupCounter`), waiting to be sent — or sent, and kept until the pickup cursor has
 * passed its start, like a session. `startMs` is unique: it is the server's dedup key within a device.
 */
@Entity(
    tableName = "pending_pickup",
    indices = [Index(value = ["startMs"], unique = true), Index(value = ["sentAtMs", "startMs"])]
)
data class PendingPickupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val unlocked: Boolean,
    val sentAtMs: Long? = null
)

/**
 * Where the next read of the usage log starts. One row. [pickupCursorMs] is the pickups' own (prompt 14): the start of the
 * earliest pickup still going, usually before [cursorMs]; `null` until the first read since 14, which starts at [cursorMs].
 */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val id: Int = 0,
    val cursorMs: Long,
    val pickupCursorMs: Long? = null
)

@Dao
abstract class OutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSessions(rows: List<PendingSessionEntity>)

    @Upsert
    protected abstract suspend fun saveCursor(cursor: SyncCursorEntity)

    @Query("SELECT cursorMs FROM sync_cursor WHERE id = 0")
    abstract suspend fun cursorMs(): Long?

    @Query("SELECT pickupCursorMs FROM sync_cursor WHERE id = 0")
    abstract suspend fun pickupCursorMs(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPickups(rows: List<PendingPickupEntity>)

    /**
     * The sessions, the pickups and the two cursors that skip past them commit together: a crash between them would
     * either re-read the log (harmless, the unique indexes ignore the repeats) or — the other way round — skip rows that
     * were never stored.
     */
    @Transaction
    open suspend fun record(
        rows: List<PendingSessionEntity>,
        cursorMs: Long,
        pickups: List<PendingPickupEntity>,
        pickupCursorMs: Long
    ) {
        insertSessions(rows)
        insertPickups(pickups)
        saveCursor(SyncCursorEntity(cursorMs = cursorMs, pickupCursorMs = pickupCursorMs))
    }

    @Query("SELECT * FROM pending_pickup WHERE sentAtMs IS NULL ORDER BY startMs LIMIT :limit")
    abstract suspend fun unsentPickups(limit: Int): List<PendingPickupEntity>

    @Query("UPDATE pending_pickup SET sentAtMs = :sentAtMs WHERE id IN (:ids)")
    abstract suspend fun markPickupsSent(ids: List<Long>, sentAtMs: Long)

    /** A sent pickup whose start is behind the pickup cursor can never be read again, so it guards nothing. */
    @Query("DELETE FROM pending_pickup WHERE sentAtMs IS NOT NULL AND startMs < :pickupCursorMs")
    abstract suspend fun pruneSentPickups(pickupCursorMs: Long)

    /** Unsent ones are bounded like sessions: an older server never acknowledges them (prompt 14). */
    @Query("DELETE FROM pending_pickup WHERE sentAtMs IS NULL AND endMs < :beforeMs")
    abstract suspend fun pruneUnsentPickupsEndingBefore(beforeMs: Long): Int

    @Query("SELECT * FROM pending_session WHERE sentAtMs IS NULL ORDER BY startMs LIMIT :limit")
    abstract suspend fun unsent(limit: Int): List<PendingSessionEntity>

    @Query("SELECT COUNT(*) FROM pending_session WHERE sentAtMs IS NULL")
    abstract suspend fun unsentCount(): Int

    @Query("UPDATE pending_session SET sentAtMs = :sentAtMs WHERE id IN (:ids)")
    abstract suspend fun markSent(ids: List<Long>, sentAtMs: Long)

    @Query("DELETE FROM pending_session WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT DISTINCT packageName FROM pending_session WHERE sentAtMs IS NULL")
    abstract suspend fun unsentPackages(): List<String>

    /** An ignored package's queued sessions: recorded before the phone knew the rule, they must never leave. */
    @Query("DELETE FROM pending_session WHERE sentAtMs IS NULL AND packageName IN (:packages)")
    abstract suspend fun deleteUnsentOf(packages: List<String>): Int

    /** A sent row whose start is behind the cursor can never be rebuilt, so it no longer guards anything. */
    @Query("DELETE FROM pending_session WHERE sentAtMs IS NOT NULL AND startMs < :cursorMs")
    abstract suspend fun pruneSent(cursorMs: Long)

    @Query("DELETE FROM pending_session WHERE sentAtMs IS NULL AND endMs < :beforeMs")
    abstract suspend fun pruneUnsentEndingBefore(beforeMs: Long): Int

    @Query(
        "DELETE FROM pending_session WHERE sentAtMs IS NULL AND id NOT IN " +
            "(SELECT id FROM pending_session WHERE sentAtMs IS NULL ORDER BY startMs DESC LIMIT :keep)"
    )
    abstract suspend fun keepNewestUnsent(keep: Int): Int

    /** Another account signed in: what is queued belongs to the previous one. */
    @Transaction
    open suspend fun reset(cursorMs: Long) {
        clearSessions()
        clearPickups()
        saveCursor(SyncCursorEntity(cursorMs = cursorMs, pickupCursorMs = cursorMs))
    }

    @Query("DELETE FROM pending_session")
    protected abstract suspend fun clearSessions()

    @Query("DELETE FROM pending_pickup")
    protected abstract suspend fun clearPickups()
}

@Database(
    entities = [PendingSessionEntity::class, SyncCursorEntity::class, PendingPickupEntity::class],
    version = 2,
    exportSchema = true
)
abstract class OutboxDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var instance: OutboxDatabase? = null

        /**
         * Prompt 14: the pickups' table and their cursor. Nothing is rewritten; the new cursor is null until the first read
         * since, which starts at the sessions' cursor. The SQL is Room's own from `app/schemas/…/2.json`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_cursor` ADD COLUMN `pickupCursorMs` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_pickup` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `unlocked` INTEGER NOT NULL, `sentAtMs` INTEGER)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_pickup_startMs` ON `pending_pickup` (`startMs`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_pickup_sentAtMs_startMs` ON `pending_pickup` (`sentAtMs`, `startMs`)"
                )
            }
        }

        fun get(context: Context): OutboxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, "outbox.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
