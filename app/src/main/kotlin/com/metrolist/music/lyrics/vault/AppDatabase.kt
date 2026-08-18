package com.metrolist.music.lyrics.vault

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE songs ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE songs ADD COLUMN all_formats_present INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE song_index ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE song_index ADD COLUMN all_formats_present INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        SongEntity::class,
        FileEntity::class,
        SongIndexEntity::class
    ],
    version = 3,
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
            .addMigrations(MIGRATION_2_3)
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

