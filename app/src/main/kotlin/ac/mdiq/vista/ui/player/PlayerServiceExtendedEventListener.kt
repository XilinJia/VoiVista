package ac.mdiq.vista.ui.player

import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.player.PlayerService

interface PlayerServiceExtendedEventListener : PlayerServiceEventListener {
    fun onServiceConnected(playerManager: PlayerManager?, playerService: PlayerService?, playAfterConnect: Boolean)

    fun onServiceDisconnected()
}
