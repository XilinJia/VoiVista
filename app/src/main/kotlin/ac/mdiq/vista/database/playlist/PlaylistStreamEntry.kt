package ac.mdiq.vista.database.playlist

import androidx.room.ColumnInfo
import androidx.room.Embedded
import ac.mdiq.vista.database.LocalItem
import ac.mdiq.vista.database.playlist.model.PlaylistStreamEntity
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.database.stream.model.StreamStateEntity
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.util.image.ImageStrategy

data class PlaylistStreamEntry(
    @Embedded
    val streamEntity: StreamEntity,
    @ColumnInfo(name = StreamStateEntity.STREAM_PROGRESS_MILLIS, defaultValue = "0")
    val progressMillis: Long,
    @ColumnInfo(name = PlaylistStreamEntity.JOIN_STREAM_ID)
    val streamId: Long,
    @ColumnInfo(name = PlaylistStreamEntity.JOIN_INDEX)
    val joinIndex: Int,
) : LocalItem {
    @Throws(IllegalArgumentException::class)
    fun toStreamInfoItem(): StreamInfoItem {
        val item = StreamInfoItem(streamEntity.serviceId, streamEntity.url, streamEntity.title, streamEntity.streamType)
        item.duration = streamEntity.duration
        item.uploaderName = streamEntity.uploader
        item.uploaderUrl = streamEntity.uploaderUrl
        item.thumbnails = ImageStrategy.dbUrlToImageList(streamEntity.thumbnailUrl)

        return item
    }

    override val localItemType: LocalItem.LocalItemType
        get() = LocalItem.LocalItemType.PLAYLIST_STREAM_ITEM
}
