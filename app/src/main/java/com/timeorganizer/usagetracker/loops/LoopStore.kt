package com.timeorganizer.usagetracker.loops

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A loop the check saw, once it is over: when, which rule, the most pickups and apps counted, and what became of its
 * check-in. Kept on the phone for [KEEP_MS], and sent with the sync since prompt 14 ([sentAtMs] once the server said it
 * holds it). No package names: a loop is a pattern, not a list of apps.
 */
@Entity(tableName = "pickup_loop", indices = [Index(value = ["startMs"])])
data class PickupLoopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    /** [LoopRule]'s name. */
    val rule: String,
    val pickups: Int,
    val apps: Int,
    /** [LoopAnswer]'s name. */
    val answer: String,
    val sentAtMs: Long? = null
)

@Dao
interface PickupLoopDao {

    @Insert
    suspend fun insert(loop: PickupLoopEntity)

    @Query("SELECT COUNT(*) FROM pickup_loop WHERE startMs >= :fromMs")
    suspend fun countSince(fromMs: Long): Int

    @Query("SELECT * FROM pickup_loop WHERE sentAtMs IS NULL ORDER BY startMs LIMIT :limit")
    suspend fun unsent(limit: Int): List<PickupLoopEntity>

    @Query("UPDATE pickup_loop SET sentAtMs = :sentAtMs WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>, sentAtMs: Long)

    @Query("DELETE FROM pickup_loop WHERE endMs < :beforeMs")
    suspend fun deleteEndingBefore(beforeMs: Long)

    @Query("DELETE FROM pickup_loop")
    suspend fun clear()
}

/**
 * Its own database, not the outbox's: a loop table can't break the sessions' store. Version 2 since prompt 14 (the "sent"
 * mark), schema exported to `app/schemas/` like the outbox's.
 */
@Database(entities = [PickupLoopEntity::class], version = 2, exportSchema = true)
abstract class LoopDatabase : RoomDatabase() {

    abstract fun loopDao(): PickupLoopDao

    companion object {
        /** A month of loops is enough to see a pattern, and to send them after a stretch offline. */
        const val KEEP_MS = 30L * 24 * 60 * LoopDetector.MINUTE_MS

        @Volatile
        private var instance: LoopDatabase? = null

        /** Prompt 14: every loop stored before it is unsent, so the first sync after the update sends the month kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pickup_loop` ADD COLUMN `sentAtMs` INTEGER")
            }
        }

        fun get(context: Context): LoopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, LoopDatabase::class.java, "loops.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        /** Stores a loop that is over, and lets go of the ones older than [KEEP_MS]. */
        suspend fun save(context: Context, loop: OpenLoop, nowMs: Long) {
            val dao = get(context).loopDao()
            dao.insert(
                PickupLoopEntity(
                    startMs = loop.startMs,
                    endMs = loop.lastHeldMs,
                    rule = loop.rule.name,
                    pickups = loop.pickups,
                    apps = loop.apps,
                    answer = loop.answer.name
                )
            )
            dao.deleteEndingBefore(nowMs - KEEP_MS)
        }
    }
}
