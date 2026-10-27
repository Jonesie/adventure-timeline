package net.jonesie.traveldiary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Trip::class,
        Companion::class,
        TripCompanionCrossRef::class,
        Leg::class,
        MediaItem::class,
        Impression::class,
        RoutePoint::class,
        GalleryIndexItem::class
    ],
    version = 7,
    exportSchema = false
)
abstract class TravelDiaryDatabase : RoomDatabase() {
    abstract fun dao(): TravelDiaryDao

    companion object {
        @Volatile private var instance: TravelDiaryDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE legs ADD COLUMN transportEdited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trips ADD COLUMN titleEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE legs ADD COLUMN difficulty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE legs ADD COLUMN enjoyment INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN locationChecked INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS gallery_index (uri TEXT NOT NULL, type TEXT NOT NULL, " +
                        "takenAt INTEGER NOT NULL, lat REAL, lon REAL, PRIMARY KEY(uri))"
                )
            }
        }

        fun get(context: Context): TravelDiaryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TravelDiaryDatabase::class.java,
                    "travel-diary.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
