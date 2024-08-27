package ac.mdiq.vista.local.playlist

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ac.mdiq.vista.database.AppDatabase
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.extractor.stream.StreamType
import ac.mdiq.vista.testUtil.TestDatabase
import ac.mdiq.vista.testUtil.TrampolineSchedulerRule
import ac.mdiq.vista.manager.LocalPlaylistManager

class LocalPlaylistManagerTest {
    private lateinit var manager: LocalPlaylistManager
    private lateinit var database: AppDatabase

    @get:Rule
    val trampolineScheduler = TrampolineSchedulerRule()

    @Before
    fun setup() {
        database = TestDatabase.createReplacingVoiVistaDatabase()
        manager = LocalPlaylistManager(database)
    }

    @After
    fun cleanUp() {
        database.close()
    }

    @Test
    fun createPlaylist() {
        val VOIVISTA_URL = "https://github.com/XilinJia/VoiVista/"
        val stream =
            StreamEntity(
                serviceId = 1,
                url = VOIVISTA_URL,
                title = "title",
                streamType = StreamType.VIDEO_STREAM,
                duration = 1,
                uploader = "uploader",
                uploaderUrl = VOIVISTA_URL,
            )

        val result = manager.createPlaylist("name", listOf(stream))

        // This should not behave like this.
        // Currently list of all stream ids is returned instead of playlist id
        result.test().await().assertValue(listOf(1L))
    }

    @Test
    fun createPlaylist_emptyPlaylistMustReturnEmpty() {
        val result = manager.createPlaylist("name", emptyList())

        // This should not behave like this.
        // It should throw an error because currently the result is null
        result.test().await().assertComplete()
        manager.playlists.test().awaitCount(1).assertValue(emptyList())
    }

    @Test()
    fun createPlaylist_nonExistentStreamsAreUpserted() {
        val stream =
            StreamEntity(
                serviceId = 1,
                url = "https://github.com/XilinJia/VoiVista/",
                title = "title",
                streamType = StreamType.VIDEO_STREAM,
                duration = 1,
                uploader = "uploader",
                uploaderUrl = "https://github.com/XilinJia/VoiVista/",
            )
        database.streamDAO().insert(stream)
        val upserted =
            StreamEntity(
                serviceId = 1,
                url = "https://github.com/XilinJia/VoiVista/2",
                title = "title2",
                streamType = StreamType.VIDEO_STREAM,
                duration = 1,
                uploader = "uploader",
                uploaderUrl = "https://github.com/XilinJia/VoiVista/",
            )

        val result = manager.createPlaylist("name", listOf(stream, upserted))

        result.test().await().assertComplete()
        database.streamDAO().getAll().test().awaitCount(1).assertValue(listOf(stream, upserted))
    }
}
