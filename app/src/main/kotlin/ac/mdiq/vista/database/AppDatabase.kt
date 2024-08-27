package ac.mdiq.vista.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ac.mdiq.vista.database.Migrations.DB_VER_7
import ac.mdiq.vista.database.feed.dao.FeedDAO
import ac.mdiq.vista.database.feed.dao.FeedGroupDAO
import ac.mdiq.vista.database.feed.model.FeedEntity
import ac.mdiq.vista.database.feed.model.FeedGroupEntity
import ac.mdiq.vista.database.feed.model.FeedGroupSubscriptionEntity
import ac.mdiq.vista.database.feed.model.FeedLastUpdatedEntity
import ac.mdiq.vista.database.history.dao.SearchHistoryDAO
import ac.mdiq.vista.database.history.dao.StreamHistoryDAO
import ac.mdiq.vista.database.history.model.SearchHistoryEntry
import ac.mdiq.vista.database.history.model.StreamHistoryEntity
import ac.mdiq.vista.database.playlist.dao.PlaylistDAO
import ac.mdiq.vista.database.playlist.dao.PlaylistRemoteDAO
import ac.mdiq.vista.database.playlist.dao.PlaylistStreamDAO
import ac.mdiq.vista.database.playlist.model.PlaylistEntity
import ac.mdiq.vista.database.playlist.model.PlaylistRemoteEntity
import ac.mdiq.vista.database.playlist.model.PlaylistStreamEntity
import ac.mdiq.vista.database.stream.dao.StreamDAO
import ac.mdiq.vista.database.stream.dao.StreamStateDAO
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.database.stream.model.StreamStateEntity
import ac.mdiq.vista.database.subscription.SubscriptionDAO
import ac.mdiq.vista.database.subscription.SubscriptionEntity

@TypeConverters(Converters::class)
@Database(entities = [
    SubscriptionEntity::class,
    SearchHistoryEntry::class,
    StreamEntity::class,
    StreamHistoryEntity::class,
    StreamStateEntity::class,
    PlaylistEntity::class,
    PlaylistStreamEntity::class,
    PlaylistRemoteEntity::class,
    FeedEntity::class,
    FeedGroupEntity::class,
    FeedGroupSubscriptionEntity::class,
    FeedLastUpdatedEntity::class], version = DB_VER_7)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDAO(): SearchHistoryDAO

    abstract fun streamDAO(): StreamDAO

    abstract fun streamHistoryDAO(): StreamHistoryDAO

    abstract fun streamStateDAO(): StreamStateDAO

    abstract fun playlistDAO(): PlaylistDAO

    abstract fun playlistStreamDAO(): PlaylistStreamDAO

    abstract fun playlistRemoteDAO(): PlaylistRemoteDAO

    abstract fun feedDAO(): FeedDAO

    abstract fun feedGroupDAO(): FeedGroupDAO

    abstract fun subscriptionDAO(): SubscriptionDAO

    companion object {
        const val DATABASE_NAME: String = "vista.db"
    }
}
