package ac.mdiq.vista.player.playqueue

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import ac.mdiq.vista.extractor.Image
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.extractor.stream.StreamType
import ac.mdiq.vista.util.ExtractorHelper.getStreamInfo
import java.io.Serializable

class PlayQueueItem private constructor(
        name: String?,
        url: String?,
        @JvmField val serviceId: Int,
        @JvmField val duration: Long,
        @JvmField val thumbnails: List<Image>,
        uploader: String?,
        val uploaderUrl: String,
        val streamType: StreamType) : Serializable {

    @JvmField
    val title: String
    @JvmField
    val url: String
    @JvmField
    val uploader: String

    @JvmField
    var isAutoQueued: Boolean = false

    @JvmField
    var recoveryPosition: Long
    var error: Throwable? = null
        private set

    internal constructor(info: StreamInfo) : this(info.name, info.url, info.serviceId, info.duration,
        info.thumbnails, info.uploaderName,
        info.uploaderUrl, info.streamType) {
        if (info.startPosition > 0) recoveryPosition = info.startPosition * 1000
    }

    internal constructor(item: StreamInfoItem)
            : this(item.name, item.url, item.serviceId, item.duration, item.thumbnails, item.uploaderName, item.uploaderUrl?:"", item.streamType)

    init {
        this.title = name ?: EMPTY_STRING
        this.url = url ?: EMPTY_STRING
        this.uploader = uploader ?: EMPTY_STRING

        this.recoveryPosition = RECOVERY_UNSET
    }

    val stream: Single<StreamInfo>
        get() = getStreamInfo(this.serviceId, this.url, false)
            .subscribeOn(Schedulers.io())
            .doOnError { throwable: Throwable? -> error = throwable }

    companion object {
        const val RECOVERY_UNSET: Long = Long.MIN_VALUE
        private const val EMPTY_STRING = ""
    }
}
