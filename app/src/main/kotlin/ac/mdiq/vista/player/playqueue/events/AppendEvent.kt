package ac.mdiq.vista.player.playqueue.events

class AppendEvent(@JvmField val amount: Int) : PlayQueueEvent {
    override fun type(): PlayQueueEventType? {
        return PlayQueueEventType.APPEND
    }
}
