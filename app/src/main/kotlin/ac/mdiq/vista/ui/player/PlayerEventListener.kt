package ac.mdiq.vista.ui.player

import androidx.media3.common.PlaybackParameters
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.player.playqueue.PlayQueue

interface PlayerEventListener {
    fun onQueueUpdate(queue: PlayQueue?)
    fun onPlaybackUpdate(state: Int, repeatMode: Int, shuffled: Boolean, parameters: PlaybackParameters?)

    fun onProgressUpdate(currentProgress: Int, duration: Int, bufferPercent: Int)
    fun onMetadataUpdate(info: StreamInfo?, queue: PlayQueue?)
    fun onAudioTrackUpdate() {}
    fun onServiceStopped()
}
