package com.fintracks.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** The data pack: one row (id = 1). [payload] is the same profile JSON the SharedPreferences copy holds. */
@Entity(tableName = "data_pack")
data class DataPackEntity(
    @PrimaryKey val id: Int = 1,
    val payload: String,
    val savedAt: Long
)

@Dao
interface DataPackDao {
    @Query("SELECT * FROM data_pack WHERE id = 1")
    fun get(): DataPackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: DataPackEntity)
}

/** Local Room database of the app. Only the data pack is stored here for now. */
@Database(entities = [DataPackEntity::class], version = 1, exportSchema = false)
abstract class FinTrackRoom : RoomDatabase() {
    abstract fun dataPack(): DataPackDao

    companion object {
        @Volatile private var instance: FinTrackRoom? = null

        fun get(ctx: Context): FinTrackRoom = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, FinTrackRoom::class.java, "fintrack_room.db")
                .build().also { instance = it }
        }
    }
}
