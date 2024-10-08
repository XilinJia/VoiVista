package ac.mdiq.vista.player.playqueue

import androidx.media3.common.util.UnstableApi
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.player.playqueue.events.*
import java.io.Serializable
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * PlayQueue is responsible for keeping track of a list of streams and the index of
 * the stream that should be currently playing.
 *
 *
 * This class contains basic manipulation of a playlist while also functions as a
 * message bus, providing all listeners with new updates to the play queue.
 *
 *
 *
 * This class can be serialized for passing intents, but in order to start the
 * message bus, it must be initialized.
 *
 */
@UnstableApi abstract class PlayQueue internal constructor(
        index: Int,
        startWith: List<PlayQueueItem>?) : Serializable {

    private val queueIndex: AtomicInteger
    private val history: MutableList<PlayQueueItem> = ArrayList()

    private var backup: MutableList<PlayQueueItem>? = null
    internal var streams: MutableList<PlayQueueItem>

    @Transient
    private var eventBroadcast: BehaviorSubject<PlayQueueEvent>? = null

    /**
     * Returns the play queue's update broadcast.
     * May be null if the play queue message bus is not initialized.
     *
     * @return the play queue's update broadcast
     */
    @Transient
    var broadcastReceiver: Flowable<PlayQueueEvent>? = null
        private set

    @Transient
    var isDisposed: Boolean = false
        private set

    @set:Synchronized
    var index: Int = 0
        /**
         * @return the current index that should be played
         */
        get() = queueIndex.get()
        /**
         * Changes the current playing index to a new index.
         *
         * This method is guarded using in a circular manner for index exceeding the play queue size.
         *
         * Will emit a [SelectEvent] if the index is not the current playing index.
         *
         * @param value the index to be set
         */
        set(value) {
            val oldIndex = field
            val newIndex = when {
                value < 0 -> 0
                // Regular assignment for index in bounds
                value < streams.size -> value
                // Out of bounds from here on
                // Need to check if stream is empty to prevent arithmetic error and negative index
                streams.isEmpty() -> 0
                // Circular indexing
                isComplete -> value % streams.size
                // Index of last element
                else -> streams.size - 1
            }
            queueIndex.set(newIndex)
            if (oldIndex != newIndex) history.add(streams[newIndex])

            /*
        TODO: Documentation states that a SelectEvent will only be emitted if the new index is...
        different from the old one but this is emitted regardless? Not sure what this what it does
        exactly so I won't touch it
         */
            broadcast(SelectEvent(oldIndex, newIndex))
            field = value
        }

    /**
     * @return the current item that should be played, or null if the queue is empty
     */
    val item: PlayQueueItem?
        get() = getItem(index)

    /**
     * Checks if the play queue is empty.
     *
     * @return whether the play queue is empty
     */
    val isEmpty: Boolean
        get() = streams.isEmpty()

    /**
     * Determines if the current play queue is shuffled.
     *
     * @return whether the play queue is shuffled
     */
    val isShuffled: Boolean
        get() = backup != null

    init {
        streams = if (startWith != null) ArrayList(startWith) else ArrayList()
        if (streams.size > index) history.add(streams[index])
        queueIndex = AtomicInteger(index)
    }

    /**
     * Initializes the play queue message buses.
     *
     *
     * Also starts a self reporter for logging if debug mode is enabled.
     *
     */
    fun init() {
        eventBroadcast = BehaviorSubject.create()
        broadcastReceiver = eventBroadcast!!.toFlowable(BackpressureStrategy.BUFFER)
            .observeOn(AndroidSchedulers.mainThread())
            .startWithItem(InitEvent())
    }

    /**
     * Dispose the play queue by stopping all message buses.
     */
    open fun dispose() {
        eventBroadcast?.onComplete()
        eventBroadcast = null
        broadcastReceiver = null
        isDisposed = true
    }

    /**
     * Checks if the queue is complete.
     *
     * A queue is complete if it has loaded all items in an external playlist
     * single stream or local queues are always complete.
     *
     * @return whether the queue is complete
     */
    abstract val isComplete: Boolean

    /**
     * Load partial queue in the background, does nothing if the queue is complete.
     */
    abstract fun fetch()

    /**
     * @param index the index of the item to return
     * @return the item at the given index, or null if the index is out of bounds
     */
    fun getItem(index: Int): PlayQueueItem? {
        if (index < 0 || index >= streams.size) return null
        return streams[index]
    }

    /**
     * Returns the index of the given item using referential equality.
     * May be null despite play queue contains identical item.
     *
     * @param item the item to find the index of
     * @return the index of the given item
     */
    fun indexOf(item: PlayQueueItem): Int {
        return streams.indexOf(item)
    }

    /**
     * @return the current size of play queue.
     */
    fun size(): Int {
        return streams.size
    }

    /**
     * @return an immutable view of the play queue
     */
    fun getStreams(): List<PlayQueueItem?> {
        return Collections.unmodifiableList(streams)
    }

    /**
     * Changes the current playing index by an offset amount.
     * Will emit a [SelectEvent] if offset is non-zero.
     * @param offset the offset relative to the current index
     */
    @Synchronized
    fun offsetIndex(offset: Int) {
        index += offset
    }

    /**
     * Notifies that a change has occurred.
     */
    @Synchronized
    fun notifyChange() {
        broadcast(AppendEvent(0))
    }

    /**
     * Appends the given [PlayQueueItem]s to the current play queue.
     *
     *
     * If the play queue is shuffled, then append the items to the backup queue as is and
     * append the shuffle items to the play queue.
     *
     *
     *
     * Will emit a [AppendEvent] on any given context.
     *
     *
     * @param items [PlayQueueItem]s to append
     */
    @Synchronized
    fun append(items: List<PlayQueueItem>) {
        val itemList: MutableList<PlayQueueItem> = ArrayList(items)

        if (isShuffled) {
            backup!!.addAll(itemList)
            itemList.shuffle()
        }
        if (streams.isNotEmpty() && streams[streams.size - 1].isAutoQueued && !itemList[0].isAutoQueued) streams.removeAt(streams.size - 1)

        streams.addAll(itemList)

        broadcast(AppendEvent(itemList.size))
    }

    /**
     * Removes the item at the given index from the play queue.
     *
     *
     * The current playing index will decrement if it is greater than the index being removed.
     * On cases where the current playing index exceeds the playlist range, it is set to 0.
     *
     *
     *
     * Will emit a [RemoveEvent] if the index is within the play queue index range.
     *
     *
     * @param index the index of the item to remove
     */
    @Synchronized
    fun remove(index: Int) {
        if (index >= streams.size || index < 0) return

        removeInternal(index)
        broadcast(RemoveEvent(index, this.index))
    }

    /**
     * Report an exception for the item at the current index in order and skip to the next one
     *
     *
     * This is done as a separate event as the underlying manager may have
     * different implementation regarding exceptions.
     *
     */
    @Synchronized
    fun error() {
        val oldIndex = index
        queueIndex.incrementAndGet()
        if (streams.size > queueIndex.get()) history.add(streams[queueIndex.get()])
        broadcast(ErrorEvent(oldIndex, index))
    }

    @Synchronized
    private fun removeInternal(removeIndex: Int) {
        val currentIndex = queueIndex.get()
        val size = size()
        when {
            currentIndex > removeIndex -> queueIndex.decrementAndGet()
            currentIndex >= size -> queueIndex.set(currentIndex % (size - 1))
            currentIndex == removeIndex && currentIndex == size - 1 -> queueIndex.set(0)
        }
        backup?.remove(getItem(removeIndex))
        history.remove(streams.removeAt(removeIndex))
        if (streams.size > queueIndex.get()) history.add(streams[queueIndex.get()])
    }

    /**
     * Moves a queue item at the source index to the target index.
     *
     *
     * If the item being moved is the currently playing, then the current playing index is set
     * to that of the target.
     * If the moved item is not the currently playing and moves to an index **AFTER** the
     * current playing index, then the current playing index is decremented.
     * Vice versa if the an item after the currently playing is moved **BEFORE**.
     *
     *
     * @param source the original index of the item
     * @param target the new index of the item
     */
    @Synchronized
    fun move(source: Int, target: Int) {
        if (source < 0 || target < 0) return
        if (source >= streams.size || target >= streams.size) return

        val current = index
        when {
            source == current -> queueIndex.set(target)
            current in (source + 1)..target -> queueIndex.decrementAndGet()
            current in target..<source -> queueIndex.incrementAndGet()
        }

        val playQueueItem = streams.removeAt(source)
        playQueueItem.isAutoQueued = false
        streams.add(target, playQueueItem)
        broadcast(MoveEvent(source, target))
    }

    /**
     * Sets the recovery record of the item at the index.
     *
     * Broadcasts a recovery event.
     *
     * @param index    index of the item
     * @param position the recovery position
     */
    @Synchronized
    fun setRecovery(index: Int, position: Long) {
        if (index < 0 || index >= streams.size) return

        streams[index].recoveryPosition = position
        broadcast(RecoveryEvent(index, position))
    }

    /**
     * Revoke the recovery record of the item at the index.
     *
     * Broadcasts a recovery event.
     *
     * @param index index of the item
     */
    @Synchronized
    fun unsetRecovery(index: Int) {
        setRecovery(index, PlayQueueItem.RECOVERY_UNSET)
    }

    /**
     * Shuffles the current play queue
     *
     *
     * This method first backs up the existing play queue and item being played. Then a newly
     * shuffled play queue will be generated along with currently playing item placed at the
     * beginning of the queue. This item will also be added to the history.
     *
     * Will emit a [ReorderEvent] if shuffled.
     *
     *
     * @implNote Does nothing if the queue has a size <= 2 (the currently playing video must stay on
     * top, so shuffling a size-2 list does nothing)
     */
    @Synchronized
    fun shuffle() {
        // Create a backup if it doesn't already exist
        // Note: The backup-list has to be created at all cost (even when size <= 2).
        // Otherwise it's not possible to enter shuffle-mode!
        if (backup == null) backup = ArrayList(streams)

        // Can't shuffle a list that's empty or only has one element
        if (size() <= 2) return

        val originalIndex = index
        val currentItem = item

        if (currentItem != null) {
            streams.shuffle()
            // Move currentItem to the head of the queue
            streams.remove(currentItem)
            streams.add(0, currentItem)
            queueIndex.set(0)

            history.add(currentItem)
        }

        broadcast(ReorderEvent(originalIndex, 0))
    }

    /**
     * Unshuffles the current play queue if a backup play queue exists.
     *
     * This method undoes shuffling and index will be set to the previously playing item if found,
     * otherwise, the index will reset to 0.
     *
     * Will emit a [ReorderEvent] if a backup exists.
     *
     */
    @Synchronized
    fun unshuffle() {
        if (backup == null) return

        val originIndex = index
        val current = item

        streams = backup!!
        backup = null

        val newIndex = streams.indexOf(current)
        if (newIndex != -1) queueIndex.set(newIndex)
        else queueIndex.set(0)

        if (streams.size > queueIndex.get()) history.add(streams[queueIndex.get()])

        broadcast(ReorderEvent(originIndex, queueIndex.get()))
    }

    /**
     * Selects previous played item.
     *
     * This method removes currently playing item from history and
     * starts playing the last item from history if it exists
     *
     * @return true if history is not empty and the item can be played
     */
    @Synchronized
    fun previous(): Boolean {
        if (history.size <= 1) return false
        history.removeAt(history.size - 1)
        val last = history.removeAt(history.size - 1)
        index = indexOf(last)
        return true
    }

    /*
     * Compares two PlayQueues. Useful when a user switches players but queue is the same so
     * we don't have to do anything with new queue.
     * This method also gives a chance to track history of items in a queue in
     * VideoDetailFragment without duplicating items from two identical queues
     */
    fun equalStreams(other: PlayQueue?): Boolean {
        if (other == null) return false
        if (size() != other.size()) return false

        for (i in 0 until size()) {
            val stream = streams[i]
            val otherStream = other.streams[i]
            // Check is based on serviceId and URL
            if (stream.serviceId != otherStream.serviceId || stream.url != otherStream.url) return false
        }
        return true
    }

    fun equalStreamsAndIndex(other: PlayQueue?): Boolean {
        if (equalStreams(other)) return other!!.index == index //NOSONAR: other is not null
        return false
    }

    private fun broadcast(event: PlayQueueEvent) {
        eventBroadcast?.onNext(event)
    }

    companion object {
        const val DEBUG: Boolean = MainActivity.DEBUG
    }
}
