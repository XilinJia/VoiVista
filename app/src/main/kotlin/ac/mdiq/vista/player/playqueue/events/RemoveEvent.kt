package ac.mdiq.vista.player.playqueue.events

class RemoveEvent(@JvmField val removeIndex: Int, @JvmField val queueIndex: Int) : PlayQueueEvent {
    override fun type(): PlayQueueEventType? {
        return PlayQueueEventType.REMOVE
    }
}
