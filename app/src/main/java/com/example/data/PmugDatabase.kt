package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PmugDocumentEntity::class,
        PmugProgressEntity::class,
        PmugBookmarkEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PmugDatabase : RoomDatabase() {

    abstract fun pmugDao(): PmugDao

    companion object {
        @Volatile
        private var INSTANCE: PmugDatabase? = null

        fun getInstance(context: Context): PmugDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PmugDatabase::class.java,
                    "pmug_library.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
