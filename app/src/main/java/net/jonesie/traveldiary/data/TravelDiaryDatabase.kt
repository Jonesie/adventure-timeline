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
        Incident::class,
        RoutePoint::class,
        GalleryIndexItem::class
    ],
    version = 9,
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

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS incidents (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "tripId INTEGER, legId INTEGER, category TEXT NOT NULL, " +
                        "severity INTEGER NOT NULL DEFAULT 0, notes TEXT NOT NULL, timestamp INTEGER NOT NULL, " +
                        "lat REAL, lon REAL, " +
                        "FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(legId) REFERENCES legs(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_incidents_tripId ON incidents (tripId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_incidents_legId ON incidents (legId)")

                // SQLite can't relax NOT NULL in place, so media_items is rebuilt with a nullable tripId
                // and the new incidentId column. Nothing references this table, so the swap is safe.
                db.execSQL(
                    "CREATE TABLE media_items_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, tripId INTEGER, " +
                        "legId INTEGER, type TEXT NOT NULL, uri TEXT NOT NULL, timestamp INTEGER NOT NULL, " +
                        "caption TEXT NOT NULL, lat REAL, lon REAL, locationChecked INTEGER NOT NULL DEFAULT 0, " +
                        "incidentId INTEGER, " +
                        "FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(legId) REFERENCES legs(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL(
                    "INSERT INTO media_items_new (id, tripId, legId, type, uri, timestamp, caption, lat, lon, locationChecked) " +
                        "SELECT id, tripId, legId, type, uri, timestamp, caption, lat, lon, locationChecked FROM media_items"
                )
                db.execSQL("DROP TABLE media_items")
                db.execSQL("ALTER TABLE media_items_new RENAME TO media_items")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_tripId ON media_items (tripId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_legId ON media_items (legId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_incidentId ON media_items (incidentId)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE incidents ADD COLUMN reason TEXT")
            }
        }

        fun get(context: Context): TravelDiaryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TravelDiaryDatabase::class.java,
                    "travel-diary.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
