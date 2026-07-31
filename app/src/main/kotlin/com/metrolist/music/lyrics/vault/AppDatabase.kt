package com.metrolist.music.lyrics.vault

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        SongEntity::class,
        FileEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

object DatabaseProvider {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context, masterFolderPath: String): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val dbFile = File(masterFolderPath, "SongSync.db")
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                dbFile.absolutePath
            )
            .fallbackToDestructiveMigration()
            .build()
            INSTANCE = instance
            instance
        }
    }

    fun closeDatabase() {
        INSTANCE?.close()
        INSTANCE = null
    }
}
