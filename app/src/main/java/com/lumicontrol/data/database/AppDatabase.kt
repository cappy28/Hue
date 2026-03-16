package com.lumicontrol.data.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumicontrol.models.*
import kotlinx.coroutines.flow.Flow

// ══════════════════════════════════════════
// DATABASE
// ══════════════════════════════════════════

/**
 * Base de données Room (SQLite local).
 * Toutes les données sont stockées localement — aucun cloud.
 */
@Database(
    entities = [Bulb::class, Scene::class, Schedule::class, BulbGroup::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(SceneConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bulbDao(): BulbDao
    abstract fun sceneDao(): SceneDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun groupDao(): GroupDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lumicontrol.db"
                )
                .addCallback(DatabaseCallback())
                .build()
                .also { INSTANCE = it }
            }
    }

    /** Pré-peuple la base avec les scènes prédéfinies. */
    private class DatabaseCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            // Les scènes prédéfinies seront insérées par le Repository au premier lancement
        }
    }
}

// ══════════════════════════════════════════
// DAO — AMPOULES
// ══════════════════════════════════════════

@Dao
interface BulbDao {

    @Query("SELECT * FROM bulbs ORDER BY isFavorite DESC, lastConnected DESC")
    fun getAllBulbs(): Flow<List<Bulb>>

    @Query("SELECT * FROM bulbs WHERE macAddress = :mac LIMIT 1")
    suspend fun getBulbByMac(mac: String): Bulb?

    @Query("SELECT * FROM bulbs WHERE isFavorite = 1")
    fun getFavorites(): Flow<List<Bulb>>

    @Query("SELECT * FROM bulbs WHERE groupId = :groupId")
    fun getBulbsByGroup(groupId: Long): Flow<List<Bulb>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBulb(bulb: Bulb)

    @Update
    suspend fun updateBulb(bulb: Bulb)

    @Delete
    suspend fun deleteBulb(bulb: Bulb)

    @Query("DELETE FROM bulbs WHERE macAddress = :mac")
    suspend fun deleteBulbByMac(mac: String)

    @Query("UPDATE bulbs SET isFavorite = :fav WHERE macAddress = :mac")
    suspend fun setFavorite(mac: String, fav: Boolean)

    @Query("UPDATE bulbs SET lastConnected = :timestamp WHERE macAddress = :mac")
    suspend fun updateLastConnected(mac: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM bulbs")
    suspend fun countBulbs(): Int
}

// ══════════════════════════════════════════
// DAO — SCÈNES
// ══════════════════════════════════════════

@Dao
interface SceneDao {

    @Query("SELECT * FROM scenes ORDER BY isFavorite DESC, createdAt DESC")
    fun getAllScenes(): Flow<List<Scene>>

    @Query("SELECT * FROM scenes WHERE isPreset = 1")
    fun getPresets(): Flow<List<Scene>>

    @Query("SELECT * FROM scenes WHERE isPreset = 0")
    fun getCustomScenes(): Flow<List<Scene>>

    @Query("SELECT * FROM scenes WHERE id = :id LIMIT 1")
    suspend fun getSceneById(id: Long): Scene?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScene(scene: Scene): Long

    @Update
    suspend fun updateScene(scene: Scene)

    @Delete
    suspend fun deleteScene(scene: Scene)

    @Query("DELETE FROM scenes WHERE isPreset = 0")
    suspend fun deleteAllCustomScenes()

    @Query("SELECT COUNT(*) FROM scenes WHERE isPreset = 1")
    suspend fun countPresets(): Int
}

// ══════════════════════════════════════════
// DAO — PLANIFICATIONS
// ══════════════════════════════════════════

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules ORDER BY hour ASC, minute ASC")
    fun getAllSchedules(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE isEnabled = 1")
    fun getEnabledSchedules(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: Long): Schedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: Schedule): Long

    @Update
    suspend fun updateSchedule(schedule: Schedule)

    @Delete
    suspend fun deleteSchedule(schedule: Schedule)

    @Query("UPDATE schedules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

// ══════════════════════════════════════════
// DAO — GROUPES
// ══════════════════════════════════════════

@Dao
interface GroupDao {

    @Query("SELECT * FROM groups ORDER BY name ASC")
    fun getAllGroups(): Flow<List<BulbGroup>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: BulbGroup): Long

    @Update
    suspend fun updateGroup(group: BulbGroup)

    @Delete
    suspend fun deleteGroup(group: BulbGroup)
}
