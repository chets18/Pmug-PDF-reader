package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

class DocumentTypeConverter {
    @TypeConverter fun fromType(type: DocumentType): String = type.name
    @TypeConverter fun toType(value: String): DocumentType = runCatching { DocumentType.valueOf(value) }.getOrDefault(DocumentType.PDF)
}

@TypeConverters(DocumentTypeConverter::class)
@Database(
    entities = [
        PmugDocumentEntity::class,
        PmugProgressEntity::class,
        PmugBookmarkEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class PmugDatabase : RoomDatabase() {

    abstract fun pmugDao(): PmugDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE documents ADD COLUMN type TEXT NOT NULL DEFAULT 'PDF'")
            }
        }
        @Volatile
        private var INSTANCE: PmugDatabase? = null

        fun getInstance(context: Context): PmugDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PmugDatabase::class.java,
                    "pmug_library.db"
                ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
