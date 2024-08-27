package ac.mdiq.vista.player.playqueue.events

import java.io.Serializable

interface PlayQueueEvent : Serializable {
    fun type(): PlayQueueEventType?
}
