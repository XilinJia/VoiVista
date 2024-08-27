package ac.mdiq.vista.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import org.mockito.junit.MockitoJUnitRunner
import ac.mdiq.vista.giga.io.StoredFileHelper
import ac.mdiq.vista.giga.io.FileStream
import java.io.File
import java.io.ObjectInputStream
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(MockitoJUnitRunner::class)
class ContentSettingsManagerTest {
    companion object {
        private val classloader = ContentSettingsManager::class.java.classLoader!!
    }

    private lateinit var fileLocator: VistaFileLocator
    private lateinit var storedFileHelper: StoredFileHelper

    @Before
    fun setupFileLocator() {
        fileLocator = Mockito.mock(VistaFileLocator::class.java, withSettings().stubOnly())
        storedFileHelper = Mockito.mock(StoredFileHelper::class.java, withSettings().stubOnly())
    }

    @Test
    fun `The settings must be exported successfully in the correct format`() {
        val db = File(classloader.getResource("settings/voivista.db")!!.file)
        val voivistaSettings = File.createTempFile("voivista_", "")
        `when`(fileLocator.db).thenReturn(db)
        `when`(fileLocator.settings).thenReturn(voivistaSettings)

        val expectedPreferences = mapOf("such pref" to "much wow")
        val sharedPreferences =
            Mockito.mock(SharedPreferences::class.java, withSettings().stubOnly())
        `when`(sharedPreferences.all).thenReturn(expectedPreferences)

        val output = File.createTempFile("voivista_", "")
        `when`(storedFileHelper.stream).thenReturn(FileStream(output))
        ContentSettingsManager(fileLocator).exportDatabase(sharedPreferences, storedFileHelper)

        val zipFile = ZipFile(output)
        val entries = zipFile.entries().toList()
        assertEquals(2, entries.size)

        zipFile.getInputStream(entries.first { it.name == "voivista.db" }).use { actual ->
            db.inputStream().use { expected ->
                assertEquals(expected.reader().readText(), actual.reader().readText())
            }
        }

        zipFile.getInputStream(entries.first { it.name == "voivista.settings" }).use { actual ->
            val actualPreferences = ObjectInputStream(actual).readObject()
            assertEquals(expectedPreferences, actualPreferences)
        }
    }

    @Test
    fun `Settings file must be deleted`() {
        val settings = File.createTempFile("voivista_", "")
        `when`(fileLocator.settings).thenReturn(settings)

        ContentSettingsManager(fileLocator).deleteSettingsFile()

        assertFalse(settings.exists())
    }

    @Test
    fun `Deleting settings file must do nothing if none exist`() {
        val settings = File("non_existent")
        `when`(fileLocator.settings).thenReturn(settings)

        ContentSettingsManager(fileLocator).deleteSettingsFile()

        assertFalse(settings.exists())
    }

    @Test
    fun `Ensuring db directory existence must work`() {
        val dir = Files.createTempDirectory("voivista_").toFile()
        Assume.assumeTrue(dir.delete())
        `when`(fileLocator.dbDir).thenReturn(dir)

        ContentSettingsManager(fileLocator).ensureDbDirectoryExists()
        assertTrue(dir.exists())
    }

    @Test
    fun `Ensuring db directory existence must work when the directory already exists`() {
        val dir = Files.createTempDirectory("voivista_").toFile()
        `when`(fileLocator.dbDir).thenReturn(dir)

        ContentSettingsManager(fileLocator).ensureDbDirectoryExists()
        assertTrue(dir.exists())
    }

    @Test
    fun `The database must be extracted from the zip file`() {
        val db = File.createTempFile("voivista_", "")
        val dbJournal = File.createTempFile("voivista_", "")
        val dbWal = File.createTempFile("voivista_", "")
        val dbShm = File.createTempFile("voivista_", "")
        `when`(fileLocator.db).thenReturn(db)
        `when`(fileLocator.dbJournal).thenReturn(dbJournal)
        `when`(fileLocator.dbShm).thenReturn(dbShm)
        `when`(fileLocator.dbWal).thenReturn(dbWal)

        val zip = File(classloader.getResource("settings/voivista.zip")?.file!!)
        `when`(storedFileHelper.stream).thenReturn(FileStream(zip))
        val success = ContentSettingsManager(fileLocator).extractDb(storedFileHelper)

        assertTrue(success)
        assertFalse(dbJournal.exists())
        assertFalse(dbWal.exists())
        assertFalse(dbShm.exists())
        assertTrue("database file size is zero", Files.size(db.toPath()) > 0)
    }

    @Test
    fun `Extracting the database from an empty zip must not work`() {
        val db = File.createTempFile("voivista_", "")
        val dbJournal = File.createTempFile("voivista_", "")
        val dbWal = File.createTempFile("voivista_", "")
        val dbShm = File.createTempFile("voivista_", "")
        `when`(fileLocator.db).thenReturn(db)

        val emptyZip = File(classloader.getResource("settings/empty.zip")?.file!!)
        `when`(storedFileHelper.stream).thenReturn(FileStream(emptyZip))
        val success = ContentSettingsManager(fileLocator).extractDb(storedFileHelper)

        assertFalse(success)
        assertTrue(dbJournal.exists())
        assertTrue(dbWal.exists())
        assertTrue(dbShm.exists())
        assertEquals(0, Files.size(db.toPath()))
    }

    @Test
    fun `Contains setting must return true if a settings file exists in the zip`() {
        val settings = File.createTempFile("voivista_", "")
        `when`(fileLocator.settings).thenReturn(settings)

        val zip = File(classloader.getResource("settings/voivista.zip")?.file!!)
        `when`(storedFileHelper.stream).thenReturn(FileStream(zip))
        val contains = ContentSettingsManager(fileLocator).extractSettings(storedFileHelper)

        assertTrue(contains)
    }

    @Test
    fun `Contains setting must return false if a no settings file exists in the zip`() {
        val settings = File.createTempFile("voivista_", "")
        `when`(fileLocator.settings).thenReturn(settings)

        val emptyZip = File(classloader.getResource("settings/empty.zip")?.file!!)
        `when`(storedFileHelper.stream).thenReturn(FileStream(emptyZip))
        val contains = ContentSettingsManager(fileLocator).extractSettings(storedFileHelper)

        assertFalse(contains)
    }

    @Test
    fun `Preferences must be set from the settings file`() {
        val settings = File(classloader.getResource("settings/voivista.settings")!!.path)
        `when`(fileLocator.settings).thenReturn(settings)

        val preferences = Mockito.mock(SharedPreferences::class.java, withSettings().stubOnly())
        val editor = Mockito.mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)

        ContentSettingsManager(fileLocator).loadSharedPreferences(preferences)

        verify(editor, atLeastOnce()).putBoolean(anyString(), anyBoolean())
        verify(editor, atLeastOnce()).putString(anyString(), anyString())
        verify(editor, atLeastOnce()).putInt(anyString(), anyInt())
    }
}
