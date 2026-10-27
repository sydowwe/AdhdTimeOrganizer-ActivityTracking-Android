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

/**
 * A loop the check saw, once it is over: when, which rule, the most pickups and apps counted, and what became of its
 * check-in. Kept on the phone only, for [KEEP_MS]; nothing is sent (sending them is planned with prompt 14's pickups).
 * No package names: a loop is a pattern, not a list of apps.
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
    val answer: String
)

@Dao
interface PickupLoopDao {

    @Insert
    suspend fun insert(loop: PickupLoopEntity)

    @Query("SELECT COUNT(*) FROM pickup_loop WHERE startMs >= :fromMs")
    suspend fun countSince(fromMs: Long): Int

    @Query("DELETE FROM pickup_loop WHERE endMs < :beforeMs")
    suspend fun deleteEndingBefore(beforeMs: Long)

    @Query("DELETE FROM pickup_loop")
    suspend fun clear()
}

/**
 * Its own database, not the outbox's: a loop table can't break the sessions' store, and adding it needs no migration of
 * `outbox.db`. Version 1, schema exported to `app/schemas/` like the outbox's.
 */
@Database(entities = [PickupLoopEntity::class], version = 1, exportSchema = true)
abstract class LoopDatabase : RoomDatabase() {

    abstract fun loopDao(): PickupLoopDao

    companion object {
        /** A month of loops is enough to see a pattern, and to send them once there is a place on the server. */
        const val KEEP_MS = 30L * 24 * 60 * LoopDetector.MINUTE_MS

        @Volatile
        private var instance: LoopDatabase? = null

        fun get(context: Context): LoopDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, LoopDatabase::class.java, "loops.db")
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
