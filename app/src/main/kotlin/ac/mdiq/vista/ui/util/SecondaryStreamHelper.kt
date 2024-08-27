package ac.mdiq.vista.ui.util

import android.content.Context
import ac.mdiq.vista.extractor.MediaFormat
import ac.mdiq.vista.extractor.stream.AudioStream
import ac.mdiq.vista.extractor.stream.Stream
import ac.mdiq.vista.extractor.stream.VideoStream
import ac.mdiq.vista.util.ListHelper.getAudioFormatComparator
import ac.mdiq.vista.util.ListHelper.getAudioIndexByHighestRank
import ac.mdiq.vista.util.ListHelper.isLimitingDataUsage
import ac.mdiq.vista.ui.adapter.StreamItemAdapter.StreamInfoWrapper

class SecondaryStreamHelper<T : Stream>(private val streams: StreamInfoWrapper<T>, selectedStream: T) {

    private val position = streams.streamsList.indexOf(selectedStream)
    val stream: T
        get() = streams.streamsList[position]

    val sizeInBytes: Long
        get() = streams.getSizeInBytes(position)

    init {
        if (this.position < 0) throw RuntimeException("selected stream not found")
    }

    companion object {
        /**
         * Finds an audio stream compatible with the provided video-only stream, so that the two streams
         * can be combined in a single file by the downloader. If there are multiple available audio
         * streams, chooses either the highest or the lowest quality one based on
         * [ListHelper.isLimitingDataUsage].
         *
         * @param context      Android context
         * @param audioStreams list of audio streams
         * @param videoStream  desired video-ONLY stream
         * @return the selected audio stream or null if a candidate was not found
         */
        fun getAudioStreamFor(context: Context, audioStreams: List<AudioStream?>, videoStream: VideoStream): AudioStream? {
            val mediaFormat = videoStream.format ?: return null

            when (mediaFormat) {
                MediaFormat.WEBM, MediaFormat.MPEG_4 -> {}
                else -> return null
            }
            val m4v = mediaFormat == MediaFormat.MPEG_4
            val isLimitingDataUsage = isLimitingDataUsage(context)

            var comparator = getAudioFormatComparator(if (m4v) MediaFormat.M4A else MediaFormat.WEBMA, isLimitingDataUsage)
            var preferredAudioStreamIndex = getAudioIndexByHighestRank(audioStreams, comparator)

            if (preferredAudioStreamIndex == -1) {
                if (m4v) return null
                comparator = getAudioFormatComparator(MediaFormat.WEBMA_OPUS, isLimitingDataUsage)
                preferredAudioStreamIndex = getAudioIndexByHighestRank(audioStreams, comparator)
                if (preferredAudioStreamIndex == -1) return null
            }
            return audioStreams[preferredAudioStreamIndex]
        }
    }
}
