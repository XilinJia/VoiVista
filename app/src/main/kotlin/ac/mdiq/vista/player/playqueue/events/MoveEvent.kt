package ac.mdiq.vista.player.playqueue.events

class MoveEvent(@JvmField val fromIndex: Int, @JvmField val toIndex: Int) : PlayQueueEvent {
    override fun type(): PlayQueueEventType? {
        return PlayQueueEventType.MOVE
    }
}
