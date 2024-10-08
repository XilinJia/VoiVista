package ac.mdiq.vista.player.playqueue

import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.extractor.stream.StreamInfoItem

class SinglePlayQueue : PlayQueue {
    constructor(item: StreamInfoItem?) : super(0, listOf<PlayQueueItem>(PlayQueueItem(item!!)))

    constructor(info: StreamInfo?) : super(0, listOf<PlayQueueItem>(PlayQueueItem(info!!)))

    constructor(info: StreamInfo?, startPosition: Long) : super(0, listOf<PlayQueueItem>(PlayQueueItem(info!!))) {
        item!!.recoveryPosition = startPosition
    }

    constructor(items: List<StreamInfoItem>, index: Int) : super(index, playQueueItemsOf(items))

    override val isComplete: Boolean
        get() = true

    override fun fetch() {
        // Item was already passed in constructor.
        // No further items need to be fetched as this is a PlayQueue with only one item
    }

    companion object {
        private fun playQueueItemsOf(items: List<StreamInfoItem>): List<PlayQueueItem> {
            val playQueueItems: MutableList<PlayQueueItem> = ArrayList(items.size)
            for (item in items) {
                playQueueItems.add(PlayQueueItem(item))
            }
            return playQueueItems
        }
    }
}
