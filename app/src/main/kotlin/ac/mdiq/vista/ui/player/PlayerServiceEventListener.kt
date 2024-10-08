package ac.mdiq.vista.ui.player

import androidx.media3.common.PlaybackException

interface PlayerServiceEventListener : PlayerEventListener {
    fun onViewCreated()

    fun onFullscreenStateChanged(fullscreen: Boolean)

    fun onScreenRotationButtonClicked()

    fun onMoreOptionsLongClicked()

    fun onPlayerError(error: PlaybackException?, isCatchableException: Boolean)

    fun hideSystemUiIfNeeded()
}
