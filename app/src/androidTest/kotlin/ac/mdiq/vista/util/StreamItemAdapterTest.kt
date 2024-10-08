package ac.mdiq.vista.util

import android.content.Context
import android.view.View
import android.view.View.*
import android.widget.Spinner
import androidx.collection.SparseArrayCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.internal.runner.junit4.statement.UiThreadStatement
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ac.mdiq.vista.R
import ac.mdiq.vista.extractor.MediaFormat
import ac.mdiq.vista.extractor.downloader.Response
import ac.mdiq.vista.extractor.stream.AudioStream
import ac.mdiq.vista.extractor.stream.Stream
import ac.mdiq.vista.extractor.stream.SubtitlesStream
import ac.mdiq.vista.extractor.stream.VideoStream
import ac.mdiq.vista.ui.util.SecondaryStreamHelper
import ac.mdiq.vista.ui.adapter.StreamItemAdapter
import ac.mdiq.vista.ui.adapter.StreamItemAdapter.StreamInfoWrapper

@MediumTest
@RunWith(AndroidJUnit4::class)
class StreamItemAdapterTest {
    private lateinit var context: Context
    private lateinit var spinner: Spinner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        UiThreadStatement.runOnUiThread {
            spinner = Spinner(context)
        }
    }

    @Test
    fun videoStreams_noSecondaryStream() {
        val adapter = StreamItemAdapter<VideoStream, AudioStream>(getVideoStreams(true, true, true, true))

        spinner.adapter = adapter
        assertIconVisibility(spinner, 0, VISIBLE, VISIBLE)
        assertIconVisibility(spinner, 1, VISIBLE, VISIBLE)
        assertIconVisibility(spinner, 2, VISIBLE, VISIBLE)
        assertIconVisibility(spinner, 3, VISIBLE, VISIBLE)
    }

    @Test
    fun videoStreams_hasSecondaryStream() {
        val adapter = StreamItemAdapter(
            getVideoStreams(false, true, false, true),
            getAudioStreams(false, true, false, true),
        )

        spinner.adapter = adapter
        assertIconVisibility(spinner, 0, GONE, GONE)
        assertIconVisibility(spinner, 1, GONE, GONE)
        assertIconVisibility(spinner, 2, GONE, GONE)
        assertIconVisibility(spinner, 3, GONE, GONE)
    }

    @Test
    fun videoStreams_Mixed() {
        val adapter = StreamItemAdapter(
            getVideoStreams(true, true, true, true, true, false, true, true),
            getAudioStreams(false, true, false, false, false, true, true, true),
        )

        spinner.adapter = adapter
        assertIconVisibility(spinner, 0, VISIBLE, VISIBLE)
        assertIconVisibility(spinner, 1, GONE, INVISIBLE)
        assertIconVisibility(spinner, 2, VISIBLE, VISIBLE)
        assertIconVisibility(spinner, 3, VISIBLE, VISIBLE)
        assertIconVisibility(spinner, 4, VISIBLE, VISIBLE)
        assertIconVisibility(spinner, 5, GONE, INVISIBLE)
        assertIconVisibility(spinner, 6, GONE, INVISIBLE)
        assertIconVisibility(spinner, 7, GONE, INVISIBLE)
    }

    @Test
    fun subtitleStreams_noIcon() {
        val adapter = StreamItemAdapter<SubtitlesStream, Stream>(
            StreamInfoWrapper((0 until 5).map {
                SubtitlesStream.Builder()
                    .setContent("https://example.com", true)
                    .setMediaFormat(MediaFormat.SRT)
                    .setLanguageCode("pt-BR")
                    .setAutoGenerated(false)
                    .build()
            },
                context,
            ),
        )
        spinner.adapter = adapter
        for (i in 0 until spinner.count) {
            assertIconVisibility(spinner, i, GONE, GONE)
        }
    }

    @Test
    fun audioStreams_noIcon() {
        val adapter = StreamItemAdapter<AudioStream, Stream>(
            StreamInfoWrapper((0 until 5).map {
                AudioStream.Builder()
                    .setId(Stream.ID_UNKNOWN)
                    .setContent("https://example.com/$it", true)
                    .setMediaFormat(MediaFormat.OPUS)
                    .setAverageBitrate(192)
                    .build()
            },
                context,
            ),
        )
        spinner.adapter = adapter
        for (i in 0 until spinner.count) {
            assertIconVisibility(spinner, i, GONE, GONE)
        }
    }

    @Test
    fun retrieveMediaFormatFromFileTypeHeaders() {
        val streams = getIncompleteAudioStreams(5)
        val wrapper = StreamInfoWrapper(streams, context)
        val retrieveMediaFormat = { stream: AudioStream, response: Response ->
            StreamInfoWrapper.retrieveMediaFormatFromFileTypeHeaders(stream, wrapper, response)
        }
        val helper = AssertionHelper(streams, wrapper, retrieveMediaFormat)

        helper.assertInvalidResponse(getResponse(mapOf(Pair("content-length", "mp3"))), 0)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("file-type", "mp0"))), 1)

        helper.assertValidResponse(getResponse(mapOf(Pair("x-amz-meta-file-type", "aiff"))), 2, MediaFormat.AIFF)
        helper.assertValidResponse(getResponse(mapOf(Pair("file-type", "mp3"))), 3, MediaFormat.MP3)
    }

    @Test
    fun retrieveMediaFormatFromContentDispositionHeader() {
        val streams = getIncompleteAudioStreams(11)
        val wrapper = StreamInfoWrapper(streams, context)
        val retrieveMediaFormat = { stream: AudioStream, response: Response ->
            StreamInfoWrapper.retrieveMediaFormatFromContentDispositionHeader(stream, wrapper, response)
        }
        val helper = AssertionHelper(streams, wrapper, retrieveMediaFormat)

        helper.assertInvalidResponse(getResponse(mapOf(Pair("content-length", "mp3"))), 0)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Disposition", "filename=\"train.png\""))), 1)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Disposition", "form-data; name=\"data.csv\""))), 2)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Disposition", "form-data; filename=\"data.csv\""))), 3)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Disposition", "form-data; name=\"fieldName\"; filename*=\"filename.jpg\""))), 4)

        helper.assertValidResponse(getResponse(mapOf(Pair("Content-Disposition", "filename=\"train.ogg\""))), 5, MediaFormat.OGG)
        helper.assertValidResponse(getResponse(mapOf(Pair("Content-Disposition", "some-form-data; filename=\"audio.flac\""))), 6, MediaFormat.FLAC)
        helper.assertValidResponse(getResponse(mapOf(Pair("Content-Disposition", "form-data; name=\"audio.aiff\"; filename=\"audio.aiff\""))),
            7, MediaFormat.AIFF, )
        helper.assertValidResponse(
            getResponse(mapOf(Pair("Content-Disposition", "form-data; name=\"alien?\"; filename*=UTF-8''%CE%B1%CE%BB%CE%B9%CF%B5%CE%BD.m4a"))),
            8, MediaFormat.M4A, )
        helper.assertValidResponse(
            getResponse(mapOf(Pair("Content-Disposition", "form-data; name=\"audio.mp3\"; filename=\"audio.opus\"; filename*=UTF-8''alien.opus"))),
            9, MediaFormat.OPUS, )
        helper.assertValidResponse(
            getResponse(mapOf(Pair("Content-Disposition", "form-data; name=\"audio.mp3\"; filename=\"audio.opus\"; filename*=\"UTF-8''alien.opus\""))),
            10, MediaFormat.OPUS, )
    }

    @Test
    fun retrieveMediaFormatFromContentTypeHeader() {
        val streams = getIncompleteAudioStreams(12)
        val wrapper = StreamInfoWrapper(streams, context)
        val retrieveMediaFormat = { stream: AudioStream, response: Response ->
            StreamInfoWrapper.retrieveMediaFormatFromContentTypeHeader(stream, wrapper, response)
        }
        val helper = AssertionHelper(streams, wrapper, retrieveMediaFormat)

        helper.assertInvalidResponse(getResponse(mapOf(Pair("content-length", "984501"))), 0)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Type", "audio/xyz"))), 1)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Type", "mp3"))), 2)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Type", "mp3"))), 3)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Type", "audio/mpeg"))), 4)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Type", "audio/aif"))), 5)
        helper.assertInvalidResponse(getResponse(mapOf(Pair("Content-Type", "whatever"))), 6)
        helper.assertInvalidResponse(getResponse(mapOf()), 7)

        helper.assertValidResponse(getResponse(mapOf(Pair("Content-Type", "audio/flac"))), 8, MediaFormat.FLAC)
        helper.assertValidResponse(getResponse(mapOf(Pair("Content-Type", "audio/wav"))), 9, MediaFormat.WAV)
        helper.assertValidResponse(getResponse(mapOf(Pair("Content-Type", "audio/opus"))), 10, MediaFormat.OPUS)
        helper.assertValidResponse(getResponse(mapOf(Pair("Content-Type", "audio/aiff"))), 11, MediaFormat.AIFF)
    }

    /**
     * @return a list of video streams, in which their video only property mirrors the provided
     * [videoOnly] vararg.
     */
    private fun getVideoStreams(vararg videoOnly: Boolean) =
        StreamInfoWrapper(
            videoOnly.map {
                VideoStream.Builder()
                    .setId(Stream.ID_UNKNOWN)
                    .setContent("https://example.com", true)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setResolution("720p")
                    .setIsVideoOnly(it)
                    .build()
            },
            context,
        )

    /**
     * @return a list of audio streams, containing valid and null elements mirroring the provided
     * [shouldBeValid] vararg.
     */
    private fun getAudioStreams(vararg shouldBeValid: Boolean) =
        getSecondaryStreamsFromList(
            shouldBeValid.map {
//                if (it) {
//                    AudioStream.Builder()
//                        .setId(Stream.ID_UNKNOWN)
//                        .setContent("https://example.com", true)
//                        .setMediaFormat(MediaFormat.OPUS)
//                        .setAverageBitrate(192)
//                        .build()
//                } else {
//                    null
//                }
//                TODO:
                AudioStream.Builder()
                    .setId(Stream.ID_UNKNOWN)
                    .setContent("https://example.com", true)
                    .setMediaFormat(MediaFormat.OPUS)
                    .setAverageBitrate(192)
                    .build()
            },
        )

    private fun getIncompleteAudioStreams(size: Int): List<AudioStream> {
        val list = ArrayList<AudioStream>(size)
        for (i in 1..size) {
            list.add(
                AudioStream.Builder()
                    .setId(Stream.ID_UNKNOWN)
                    .setContent("https://example.com/$i", true)
                    .build(),
            )
        }
        return list
    }

    /**
     * Checks whether the item at [position] in the [spinner] has the correct icon visibility when
     * it is shown in normal mode (selected) and in dropdown mode (user is choosing one of a list).
     */
    private fun assertIconVisibility(spinner: Spinner, position: Int, normalVisibility: Int, dropDownVisibility: Int) {
        spinner.setSelection(position)
        spinner.adapter.getView(position, null, spinner).run {
            assertEquals(
                "normal visibility (pos=[$position]) is not correct",
                findViewById<View>(R.id.wo_sound_icon).visibility,
                normalVisibility,
            )
        }
        spinner.adapter.getDropDownView(position, null, spinner).run {
            assertEquals(
                "drop down visibility (pos=[$position]) is not correct",
                findViewById<View>(R.id.wo_sound_icon).visibility,
                dropDownVisibility,
            )
        }
    }

    /**
     * Helper function that builds a secondary stream list.
     */
    private fun <T : Stream> getSecondaryStreamsFromList(streams: List<T>) =
        SparseArrayCompat<SecondaryStreamHelper<T>>(streams.size).apply {
            streams.forEachIndexed { index, stream ->
                val secondaryStreamHelper: SecondaryStreamHelper<T>? =
                    stream.let {
                        val param = StreamInfoWrapper(streams, context)
                        SecondaryStreamHelper(param, it)
                    }
                put(index, secondaryStreamHelper)
            }
        }

    private fun getResponse(headers: Map<String, String>): Response {
        val listHeaders = HashMap<String, List<String>>()
        headers.forEach { entry ->
            listHeaders[entry.key] = listOf(entry.value)
        }
        return Response(200, "", listHeaders, "", "")
    }

    /**
     * Helper class for assertion related to extractions of [MediaFormat]s.
     */
    class AssertionHelper<T : Stream>(
        private val streams: List<T>,
        private val wrapper: StreamInfoWrapper<T>,
        private val retrieveMediaFormat: (stream: T, response: Response) -> Boolean, ) {
        /**
         * Assert that an invalid response does not result in wrongly extracted [MediaFormat].
         */
        fun assertInvalidResponse(response: Response, index: Int) {
            assertFalse("invalid header returns valid value", retrieveMediaFormat(streams[index], response))
            assertNull("Media format extracted although stated otherwise", wrapper.getFormat(index))
        }

        /**
         * Assert that a valid response results in correctly extracted and handled [MediaFormat].
         */
        fun assertValidResponse(response: Response, index: Int, format: MediaFormat) {
            assertTrue("header was not recognized", retrieveMediaFormat(streams[index], response))
            assertEquals("Wrong media format extracted", format, wrapper.getFormat(index))
        }
    }
}
