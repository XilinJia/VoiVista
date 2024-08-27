package ac.mdiq.vista.database.feed.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ac.mdiq.vista.database.feed.model.FeedGroupEntity.Companion.FEED_GROUP_TABLE
import ac.mdiq.vista.database.feed.model.FeedGroupEntity.Companion.SORT_ORDER
import ac.mdiq.vista.local.subscription.FeedGroupIcon

@Entity(
    tableName = FEED_GROUP_TABLE,
    indices = [Index(SORT_ORDER)],
)
data class FeedGroupEntity(
        @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = ID)
    val uid: Long,
        @ColumnInfo(name = NAME)
    var name: String,
        @ColumnInfo(name = ICON)
    var icon: FeedGroupIcon?,
        @ColumnInfo(name = SORT_ORDER)
    var sortOrder: Long = -1,
) {
    constructor(): this(0L, "", null, -1)
    companion object {
        const val FEED_GROUP_TABLE = "feed_group"

        const val ID = "uid"
        const val NAME = "name"
        const val ICON = "icon_id"
        const val SORT_ORDER = "sort_order"

        const val GROUP_ALL_ID = -1L
    }
}
