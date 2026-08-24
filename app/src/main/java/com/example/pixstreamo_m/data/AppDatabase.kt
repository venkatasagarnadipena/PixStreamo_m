/**
 * PixStreamo Data Persistence Layer
 *
 * This file defines the local Room Database and its associated entities and DAOs.
 * Responsible for storing the configuration list of MEGA folders.
 */
package com.example.pixstreamo_m.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Represents a MEGA folder/category in the stream.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String,
    val sourceUrl: String? = null // To track which config URL this folder belongs to
)

/**
 * Data Access Object for folder operations.
 */
@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: Int): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<FolderEntity>)

    @Query("DELETE FROM folders")
    suspend fun deleteAllFolders()

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE sourceUrl = :sourceUrl")
    suspend fun deleteFoldersBySource(sourceUrl: String)
}

/**
 * Main Room Database singleton.
 */
@Database(entities = [FolderEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pixstreamo_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
