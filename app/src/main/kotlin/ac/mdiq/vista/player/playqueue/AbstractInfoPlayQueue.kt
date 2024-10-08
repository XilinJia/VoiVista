package ac.mdiq.vista.player.playqueue

import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.reactivex.rxjava3.core.SingleObserver
import io.reactivex.rxjava3.disposables.Disposable
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.ListExtractor.InfoItemsPage
import ac.mdiq.vista.extractor.ListInfo
import ac.mdiq.vista.extractor.Page
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import java.util.stream.Collectors

@UnstableApi abstract class AbstractInfoPlayQueue<T : ListInfo<out InfoItem>> protected constructor(
        @JvmField val serviceId: Int,
        @JvmField val baseUrl: String,
        @JvmField var nextPage: Page?,
        streams: List<StreamInfoItem>,
        index: Int)
    : PlayQueue(index, extractListItems(streams)) {

    @JvmField
    var isInitial: Boolean
    override var isComplete: Boolean = false

    @Transient
    private var fetchReactor: Disposable? = null

    protected constructor(info: T) : this(info.serviceId, info.url, info.nextPage,
        info.relatedItems
            .stream()
            .filter { obj: Any? -> StreamInfoItem::class.java.isInstance(obj) }
            .map<StreamInfoItem> { obj: Any? -> StreamInfoItem::class.java.cast(obj) }
            .collect(Collectors.toList<StreamInfoItem>()),
        0)

    init {
        this.isInitial = streams.isEmpty()
        this.isComplete = !isInitial && !Page.isValid(nextPage)
    }

    protected abstract val tag: String?

    val headListObserver: SingleObserver<T>
        get() = object : SingleObserver<T> {
            override fun onSubscribe(d: Disposable) {
                if (isComplete || !isInitial || (fetchReactor != null && !fetchReactor!!.isDisposed)) d.dispose()
                else fetchReactor = d
            }
            override fun onSuccess(result: T) {
                isInitial = false
                if (!result.hasNextPage()) isComplete = true
                nextPage = result.nextPage
                append(extractListItems(result.relatedItems
                    .stream()
                    .filter { obj: Any? -> StreamInfoItem::class.java.isInstance(obj) }
                    .map { obj: Any? -> StreamInfoItem::class.java.cast(obj) }
                    .collect(Collectors.toList())))
                fetchReactor!!.dispose()
                fetchReactor = null
            }
            override fun onError(e: Throwable) {
                Log.e(tag, "Error fetching more playlist, marking playlist as complete.", e)
                isComplete = true
                notifyChange()
            }
        }

    val nextPageObserver: SingleObserver<InfoItemsPage<out InfoItem>>
        get() = object : SingleObserver<InfoItemsPage<out InfoItem>> {
            override fun onSubscribe(d: Disposable) {
                if (isComplete || isInitial || (fetchReactor != null && !fetchReactor!!.isDisposed)) d.dispose()
                else fetchReactor = d
            }
            override fun onSuccess(result: InfoItemsPage<out InfoItem>) {
                if (!result.hasNextPage()) isComplete = true
                nextPage = result.nextPage
                append(extractListItems(result.items
                    .stream()
                    .filter { obj: Any? -> StreamInfoItem::class.java.isInstance(obj) }
                    .map { obj: Any? -> StreamInfoItem::class.java.cast(obj) }
                    .collect(Collectors.toList())))
                fetchReactor!!.dispose()
                fetchReactor = null
            }
            override fun onError(e: Throwable) {
                Log.e(tag, "Error fetching more playlist, marking playlist as complete.", e)
                isComplete = true
                notifyChange()
            }
        }

    override fun dispose() {
        super.dispose()
        fetchReactor?.dispose()
        fetchReactor = null
    }

    companion object {
        private fun extractListItems(infoItems: List<StreamInfoItem>): List<PlayQueueItem> {
            return infoItems.stream().map { item: StreamInfoItem? ->
                PlayQueueItem(item!!)
            }.collect(Collectors.toList())
        }
    }
}
