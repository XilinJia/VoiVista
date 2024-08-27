package ac.mdiq.vista.testUtil

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertSame
import ac.mdiq.vista.database.VoiVistaDatabase
import ac.mdiq.vista.database.AppDatabase

class TestDatabase {
    companion object {
        fun createReplacingVoiVistaDatabase(): AppDatabase {
            val database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    AppDatabase::class.java,
                )
                    .allowMainThreadQueries()
                    .build()

            val databaseField = VoiVistaDatabase::class.java.getDeclaredField("databaseInstance")
            databaseField.isAccessible = true
            databaseField.set(VoiVistaDatabase::class, database)

            assertSame(
                "Mocking database failed!",
                database,
                VoiVistaDatabase.getInstance(ApplicationProvider.getApplicationContext()),
            )

            return database
        }
    }
}
