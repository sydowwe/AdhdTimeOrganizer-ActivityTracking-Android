package com.timeorganizer.usagetracker.capture

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * A thought waiting for the server. The text is the user's own words: kept only until the server has it, and never
 * logged (*Privacy and logging*).
 */
@Entity(tableName = "pending_capture")
data class PendingCaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val url: String?,
    val createdMs: Long,
    /** The server refused this body (400 / 413 / 422) at its last try; it is still kept and tried again. */
    val refused: Boolean = false
)

@Dao
interface PendingCaptureDao {

    @Insert
    suspend fun insert(capture: PendingCaptureEntity): Long

    /** Oldest first: captures reach the list in the order they were made. */
    @Query("SELECT * FROM pending_capture ORDER BY createdMs, id")
    suspend fun all(): List<PendingCaptureEntity>

    @Query("DELETE FROM pending_capture WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_capture SET refused = :refused WHERE id = :id")
    suspend fun markRefused(id: Long, refused: Boolean)

    @Query("SELECT COUNT(*) FROM pending_capture")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pending_capture WHERE refused")
    suspend fun refusedCount(): Int

    @Query("DELETE FROM pending_capture")
    suspend fun clear()
}

/**
 * Its own database, like `loops.db`: a capture table can't break the sessions' store, and needs no migration of
 * `outbox.db`. Version 1, schema exported to `app/schemas/`.
 */
@Database(entities = [PendingCaptureEntity::class], version = 1, exportSchema = true)
abstract class CaptureDatabase : RoomDatabase() {

    abstract fun captureDao(): PendingCaptureDao

    companion object {
        @Volatile
        private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, CaptureDatabase::class.java, "capture.db")
                    .build()
                    .also { instance = it }
            }
    }
}
