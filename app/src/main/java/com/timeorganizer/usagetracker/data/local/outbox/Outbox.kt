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
 * A notification that interrupted (prompt 15, `DerailmentLinker`), waiting to be sent — or sent, and kept until the
 * derailment cursor has passed it. `(atMs, packageName)` is unique: the server's dedup key within a device.
 */
@Entity(
    tableName = "pending_interruption",
    indices = [Index(value = ["atMs", "packageName"], unique = true), Index(value = ["sentAtMs", "atMs"])]
)
data class PendingInterruptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val atMs: Long,
    val sentAtMs: Long? = null
)

/**
 * A settled derailment (prompt 15): [packageName]'s notification at [notifiedMs] opened the app at [startMs], until
 * [endMs]. [endedBy] is `DerailmentEnd.name`. `startMs` is unique: the server's dedup key within a device.
 */
@Entity(
    tableName = "pending_derailment",
    indices = [Index(value = ["startMs"], unique = true), Index(value = ["sentAtMs", "startMs"])]
)
data class PendingDerailmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val notifiedMs: Long,
    val startMs: Long,
    val endMs: Long,
    val endedBy: String,
    val sentAtMs: Long? = null
)

/**
 * A pause (prompt 17): nothing read from the usage log in `[startMs, endMs)` is queued (`PauseClipper`). "Until I resume"
 * ends at `Long.MAX_VALUE` until Resume writes the real end. Kept until every cursor has passed its end, and two days more
 * for the daily limits' estimate (`PausePolicy.KEEP_ENDED_MS`).
 */
@Entity(tableName = "pause_range")
data class PauseRangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long
)

/**
 * Where the next read of the usage log starts. One row. [pickupCursorMs] is the pickups' own (prompt 14): the start of the
 * earliest pickup still going, usually before [cursorMs]; `null` until the first read since 14, which starts at [cursorMs].
 * [derailmentCursorMs] is the notifications' (prompt 15): the earliest one still undecided (`DerailmentLinker`); `null`
 * until the first read since 15, which starts at [cursorMs].
 */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val id: Int = 0,
    val cursorMs: Long,
    val pickupCursorMs: Long? = null,
    val derailmentCursorMs: Long? = null
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

    @Query("SELECT derailmentCursorMs FROM sync_cursor WHERE id = 0")
    abstract suspend fun derailmentCursorMs(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPickups(rows: List<PendingPickupEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertInterruptions(rows: List<PendingInterruptionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDerailments(rows: List<PendingDerailmentEntity>)

    /**
     * The sessions, the pickups, the interruptions and derailments, and the three cursors that skip past them commit
     * together: a crash between them would either re-read the log (harmless, the unique indexes ignore the repeats) or —
     * the other way round — skip rows that were never stored.
     */
    @Transaction
    open suspend fun record(
        rows: List<PendingSessionEntity>,
        cursorMs: Long,
        pickups: List<PendingPickupEntity>,
        pickupCursorMs: Long,
        interruptions: List<PendingInterruptionEntity>,
        derailments: List<PendingDerailmentEntity>,
        derailmentCursorMs: Long
    ) {
        insertSessions(rows)
        insertPickups(pickups)
        insertInterruptions(interruptions)
        insertDerailments(derailments)
        saveCursor(SyncCursorEntity(cursorMs = cursorMs, pickupCursorMs = pickupCursorMs, derailmentCursorMs = derailmentCursorMs))
    }

    @Query("SELECT * FROM pending_interruption WHERE sentAtMs IS NULL ORDER BY atMs LIMIT :limit")
    abstract suspend fun unsentInterruptions(limit: Int): List<PendingInterruptionEntity>

    @Query("UPDATE pending_interruption SET sentAtMs = :sentAtMs WHERE id IN (:ids)")
    abstract suspend fun markInterruptionsSent(ids: List<Long>, sentAtMs: Long)

    @Query("SELECT * FROM pending_derailment WHERE sentAtMs IS NULL ORDER BY startMs LIMIT :limit")
    abstract suspend fun unsentDerailments(limit: Int): List<PendingDerailmentEntity>

    @Query("UPDATE pending_derailment SET sentAtMs = :sentAtMs WHERE id IN (:ids)")
    abstract suspend fun markDerailmentsSent(ids: List<Long>, sentAtMs: Long)

    /**
     * Sent ones behind the derailment cursor can never be read again. A derailment is read again from its notification,
     * so that is what must be behind it.
     */
    @Transaction
    open suspend fun pruneSentDerailmentRows(derailmentCursorMs: Long) {
        pruneSentInterruptions(derailmentCursorMs)
        pruneSentDerailments(derailmentCursorMs)
    }

    @Query("DELETE FROM pending_interruption WHERE sentAtMs IS NOT NULL AND atMs < :derailmentCursorMs")
    protected abstract suspend fun pruneSentInterruptions(derailmentCursorMs: Long)

    @Query("DELETE FROM pending_derailment WHERE sentAtMs IS NOT NULL AND notifiedMs < :derailmentCursorMs")
    protected abstract suspend fun pruneSentDerailments(derailmentCursorMs: Long)

    /** Unsent ones are bounded like pickups: an older server never acknowledges them. */
    @Transaction
    open suspend fun pruneUnsentDerailmentRowsBefore(beforeMs: Long) {
        pruneUnsentInterruptionsBefore(beforeMs)
        pruneUnsentDerailmentsEndingBefore(beforeMs)
    }

    @Query("DELETE FROM pending_interruption WHERE sentAtMs IS NULL AND atMs < :beforeMs")
    protected abstract suspend fun pruneUnsentInterruptionsBefore(beforeMs: Long)

    @Query("DELETE FROM pending_derailment WHERE sentAtMs IS NULL AND endMs < :beforeMs")
    protected abstract suspend fun pruneUnsentDerailmentsEndingBefore(beforeMs: Long)

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

    /** Every package something unsent names: sessions, and since prompt 15 interruptions and derailments. */
    @Query(
        "SELECT packageName FROM pending_session WHERE sentAtMs IS NULL " +
            "UNION SELECT packageName FROM pending_interruption WHERE sentAtMs IS NULL " +
            "UNION SELECT packageName FROM pending_derailment WHERE sentAtMs IS NULL"
    )
    abstract suspend fun unsentPackages(): List<String>

    /**
     * An ignored package's queued rows: recorded before the phone knew the rule, they must never leave. Its sessions and,
     * since prompt 15, its notifications. Returns the sessions deleted.
     */
    @Transaction
    open suspend fun deleteUnsentOf(packages: List<String>): Int {
        deleteUnsentInterruptionsOf(packages)
        deleteUnsentDerailmentsOf(packages)
        return deleteUnsentSessionsOf(packages)
    }

    @Query("DELETE FROM pending_session WHERE sentAtMs IS NULL AND packageName IN (:packages)")
    protected abstract suspend fun deleteUnsentSessionsOf(packages: List<String>): Int

    @Query("DELETE FROM pending_interruption WHERE sentAtMs IS NULL AND packageName IN (:packages)")
    protected abstract suspend fun deleteUnsentInterruptionsOf(packages: List<String>)

    @Query("DELETE FROM pending_derailment WHERE sentAtMs IS NULL AND packageName IN (:packages)")
    protected abstract suspend fun deleteUnsentDerailmentsOf(packages: List<String>)

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

    @Insert
    abstract suspend fun insertPause(row: PauseRangeEntity)

    /** The pause going at [nowMs], the latest if they ever overlap. */
    @Query("SELECT * FROM pause_range WHERE startMs <= :nowMs AND endMs > :nowMs ORDER BY startMs DESC LIMIT 1")
    abstract suspend fun activePause(nowMs: Long): PauseRangeEntity?

    @Query("SELECT * FROM pause_range WHERE startMs < :toMs AND endMs > :fromMs ORDER BY startMs")
    abstract suspend fun pausesOver(fromMs: Long, toMs: Long): List<PauseRangeEntity>

    @Query("UPDATE pause_range SET endMs = :endMs WHERE id = :id")
    abstract suspend fun endPause(id: Long, endMs: Long)

    @Query("DELETE FROM pause_range WHERE id = :id")
    abstract suspend fun deletePause(id: Long)

    @Query("DELETE FROM pause_range WHERE endMs < :beforeMs")
    abstract suspend fun prunePausesEndingBefore(beforeMs: Long)

    /**
     * Another account signed in: what is queued belongs to the previous one. A pause stays: it is this phone's, like the
     * chime's ticks, and the new account's time in it is as private.
     */
    @Transaction
    open suspend fun reset(cursorMs: Long) {
        clearSessions()
        clearPickups()
        clearInterruptions()
        clearDerailments()
        saveCursor(SyncCursorEntity(cursorMs = cursorMs, pickupCursorMs = cursorMs, derailmentCursorMs = cursorMs))
    }

    @Query("DELETE FROM pending_session")
    protected abstract suspend fun clearSessions()

    @Query("DELETE FROM pending_pickup")
    protected abstract suspend fun clearPickups()

    @Query("DELETE FROM pending_interruption")
    protected abstract suspend fun clearInterruptions()

    @Query("DELETE FROM pending_derailment")
    protected abstract suspend fun clearDerailments()
}

@Database(
    entities = [
        PendingSessionEntity::class, SyncCursorEntity::class, PendingPickupEntity::class,
        PendingInterruptionEntity::class, PendingDerailmentEntity::class, PauseRangeEntity::class
    ],
    version = 4,
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

        /**
         * Prompt 15: the interruptions' and derailments' tables and their cursor. Nothing is rewritten; the new cursor is null
         * until the first read since, which starts at the sessions' cursor. The SQL is Room's own from `app/schemas/…/3.json`.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_cursor` ADD COLUMN `derailmentCursorMs` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_interruption` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, `atMs` INTEGER NOT NULL, `sentAtMs` INTEGER)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_interruption_atMs_packageName` " +
                        "ON `pending_interruption` (`atMs`, `packageName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_interruption_sentAtMs_atMs` ON `pending_interruption` (`sentAtMs`, `atMs`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_derailment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, `notifiedMs` INTEGER NOT NULL, `startMs` INTEGER NOT NULL, " +
                        "`endMs` INTEGER NOT NULL, `endedBy` TEXT NOT NULL, `sentAtMs` INTEGER)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_derailment_startMs` ON `pending_derailment` (`startMs`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_derailment_sentAtMs_startMs` ON `pending_derailment` (`sentAtMs`, `startMs`)"
                )
            }
        }

        /** Prompt 17: the pauses' table, empty. The SQL is Room's own from `app/schemas/…/4.json`. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pause_range` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): OutboxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, OutboxDatabase::class.java, "outbox.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
