package ac.mdiq.vista.ui.holder

import ac.mdiq.vista.player.playqueue.PlayQueue

/**
 * Interface for `R.layout.playlist_control` view holders
 * to give access to the play queue.
 */
interface PlaylistControlViewHolder {
    val playQueue: PlayQueue?
}
