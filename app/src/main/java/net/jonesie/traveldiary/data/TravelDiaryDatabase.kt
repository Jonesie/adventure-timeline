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
        IncidentPersonCrossRef::class,
        RoutePoint::class,
        GalleryIndexItem::class
    ],
    version = 11,
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

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Incidents logged before this existed were the owner's own.
                db.execSQL("ALTER TABLE incidents ADD COLUMN involvesMe INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS incident_people (incidentId INTEGER NOT NULL, companionId INTEGER NOT NULL, " +
                        "PRIMARY KEY(incidentId, companionId), " +
                        "FOREIGN KEY(incidentId) REFERENCES incidents(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(companionId) REFERENCES companions(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_incident_people_companionId ON incident_people (companionId)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reasons used to be a fixed list stored by name; they are now editable text.
                db.execSQL(
                    "UPDATE incidents SET reason = CASE reason " +
                        "WHEN 'NOT_MY_FAULT' THEN 'Not my fault' " +
                        "WHEN 'BEYOND_CAPABILITY' THEN 'Situation beyond my capability' " +
                        "WHEN 'LACK_OF_FOCUS' THEN 'Lack of focus' " +
                        "WHEN 'STUPIDITY' THEN 'Stupidity' " +
                        "WHEN 'RATHER_NOT_SAY' THEN 'Rather not say' " +
                        "WHEN 'OTHER' THEN 'Other' ELSE reason END WHERE reason IS NOT NULL"
                )
            }
        }

        fun get(context: Context): TravelDiaryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TravelDiaryDatabase::class.java,
                    "travel-diary.db"
                ).addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
