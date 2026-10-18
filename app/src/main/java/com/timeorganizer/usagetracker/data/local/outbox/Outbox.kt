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

/** Where the next read of the usage log starts. One row. */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val id: Int = 0,
    val cursorMs: Long
)

@Dao
abstract class OutboxDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSessions(rows: List<PendingSessionEntity>)

    @Upsert
    protected abstract suspend fun saveCursor(cursor: SyncCursorEntity)

    @Query("SELECT cursorMs FROM sync_cursor WHERE id = 0")
    abstract suspend fun cursorMs(): Long?

    /**
     * The sessions and the cursor that skips past them commit together: a crash between the two would either
     * re-read the log (harmless, the unique index ignores the repeats) or — the other way round — skip
     * sessions that were never stored.
     */
    @Transaction
    open suspend fun record(rows: List<PendingSessionEntity>, cursorMs: Long) {
        insertSessions(rows)
        saveCursor(SyncCursorEntity(cursorMs = cursorMs))
    }

    @Query("SELECT * FROM pending_session WHERE sentAtMs IS NULL ORDER BY startMs LIMIT :limit")
    abstract suspend fun unsent(limit: Int): List<PendingSessionEntity>

    @Query("SELECT COUNT(*) FROM pending_session WHERE sentAtMs IS NULL")
    abstract suspend fun unsentCount(): Int

    @Query("UPDATE pending_session SET sentAtMs = :sentAtMs WHERE id IN (:ids)")
    abstract suspend fun markSent(ids: List<Long>, sentAtMs: Long)

    @Query("DELETE FROM pending_session WHERE id = :id")
    abstract suspend fun delete(id: Long)

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
        saveCursor(SyncCursorEntity(cursorMs = cursorMs))
    }

    @Query("DELETE FROM pending_session")
    protected abstract suspend fun clearSessions()
}

@Database(entities = [PendingSessionEntity::class, SyncCursorEntity::class], version = 1, exportSchema = true)
abstract class OutboxDatabase : RoomDatabase() {

    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var instance: OutboxDatabase? = null

        fun get(context: Context): OutboxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, "outbox.db")
                    .build()
                    .also { instance = it }
            }
    }
}
