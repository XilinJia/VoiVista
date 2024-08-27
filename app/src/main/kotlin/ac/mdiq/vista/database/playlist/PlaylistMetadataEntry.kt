package ac.mdiq.vista.database.playlist

import androidx.room.ColumnInfo
import ac.mdiq.vista.database.LocalItem.LocalItemType
import ac.mdiq.vista.database.playlist.model.PlaylistEntity

open class PlaylistMetadataEntry(@field:ColumnInfo(name = PlaylistEntity.PLAYLIST_ID) var uid: Long,
                                 @field:ColumnInfo(name = PlaylistEntity.PLAYLIST_NAME) var name: String,
                                 @field:ColumnInfo(name = PlaylistEntity.PLAYLIST_THUMBNAIL_URL) var thumbnailUrl: String,
                                 @field:ColumnInfo(name = PLAYLIST_STREAM_COUNT) var streamCount: Long
) : PlaylistLocalItem {
    override val localItemType: LocalItemType
        get() = LocalItemType.PLAYLIST_LOCAL_ITEM

    override fun getOrderingName(): String {
        return name
    }

    companion object {
        const val PLAYLIST_STREAM_COUNT: String = "streamCount"
    }
}
