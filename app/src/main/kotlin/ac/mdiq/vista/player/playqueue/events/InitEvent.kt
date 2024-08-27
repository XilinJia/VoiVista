package ac.mdiq.vista.player.playqueue.events

class InitEvent : PlayQueueEvent {
    override fun type(): PlayQueueEventType? {
        return PlayQueueEventType.INIT
    }
}
