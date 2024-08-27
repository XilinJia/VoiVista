package ac.mdiq.vista.settings

import java.io.File

/**
 * Locates specific files of Vista based on the home directory of the app.
 */
class VistaFileLocator(private val homeDir: File) {
    val dbDir by lazy { File(homeDir, "/databases") }

    val db by lazy { File(homeDir, "/databases/vista.db") }

    val dbJournal by lazy { File(homeDir, "/databases/vista.db-journal") }

    val dbShm by lazy { File(homeDir, "/databases/vista.db-shm") }

    val dbWal by lazy { File(homeDir, "/databases/vista.db-wal") }

    val settings by lazy { File(homeDir, "/databases/vista.settings") }
}
