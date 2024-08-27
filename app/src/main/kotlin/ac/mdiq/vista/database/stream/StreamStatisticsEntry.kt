package ac.mdiq.vista.database.stream

import androidx.room.ColumnInfo
import androidx.room.Embedded
import ac.mdiq.vista.database.LocalItem
import ac.mdiq.vista.database.history.model.StreamHistoryEntity
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.database.stream.model.StreamStateEntity.Companion.STREAM_PROGRESS_MILLIS
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.util.image.ImageStrategy
import java.time.OffsetDateTime

class StreamStatisticsEntry(
    @Embedded
    val streamEntity: StreamEntity,
    @ColumnInfo(name = STREAM_PROGRESS_MILLIS, defaultValue = "0")
    val progressMillis: Long,
    @ColumnInfo(name = StreamHistoryEntity.JOIN_STREAM_ID)
    val streamId: Long,
    @ColumnInfo(name = STREAM_LATEST_DATE)
    val latestAccessDate: OffsetDateTime,
    @ColumnInfo(name = STREAM_WATCH_COUNT)
    val watchCount: Long) : LocalItem {

    fun toStreamInfoItem(): StreamInfoItem {
        val item = StreamInfoItem(streamEntity.serviceId, streamEntity.url, streamEntity.title, streamEntity.streamType)
        item.duration = streamEntity.duration
        item.uploaderName = streamEntity.uploader
        item.uploaderUrl = streamEntity.uploaderUrl
        item.thumbnails = ImageStrategy.dbUrlToImageList(streamEntity.thumbnailUrl)

        return item
    }

    override val localItemType: LocalItem.LocalItemType
        get() = LocalItem.LocalItemType.STATISTIC_STREAM_ITEM

    companion object {
        const val STREAM_LATEST_DATE = "latestAccess"
        const val STREAM_WATCH_COUNT = "watchCount"
    }
}
