package ac.mdiq.vista.player.playqueue.events

class RecoveryEvent(val index: Int, val position: Long) : PlayQueueEvent {
    override fun type(): PlayQueueEventType? {
        return PlayQueueEventType.RECOVERY
    }
}
