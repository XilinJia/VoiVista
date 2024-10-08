package ac.mdiq.vista.manager

import android.content.Context
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.schedulers.Schedulers
//import ac.mdiq.vista.ui.activity.MainActivity.DEBUG
import ac.mdiq.vista.database.VoiVistaDatabase
import ac.mdiq.vista.database.feed.model.FeedEntity
import ac.mdiq.vista.database.feed.model.FeedGroupEntity
import ac.mdiq.vista.database.feed.model.FeedLastUpdatedEntity
import ac.mdiq.vista.database.stream.StreamWithState
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.database.subscription.NotificationMode
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.extractor.stream.StreamType
import ac.mdiq.vista.local.subscription.FeedGroupIcon
import ac.mdiq.vista.util.Logd
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class FeedDatabaseManager(context: Context) {
    private val database = VoiVistaDatabase.getInstance(context)
    private val feedTable = database.feedDAO()
    private val feedGroupTable = database.feedGroupDAO()
    private val streamTable = database.streamDAO()

    companion object {
        /**
         * Only items that are newer than this will be saved.
         */
        val FEED_OLDEST_ALLOWED_DATE: OffsetDateTime = LocalDate.now().minusWeeks(13).atStartOfDay().atOffset(ZoneOffset.UTC)
    }

    fun groups() = feedGroupTable.getAll()

    fun database() = database

    fun getStreams(groupId: Long, includePlayedStreams: Boolean, includePartiallyPlayedStreams: Boolean, includeFutureStreams: Boolean): Maybe<List<StreamWithState>> {
        return feedTable.getStreams(groupId, includePlayedStreams, includePartiallyPlayedStreams, if (includeFutureStreams) null else OffsetDateTime.now())
    }

    fun outdatedSubscriptions(outdatedThreshold: OffsetDateTime) = feedTable.getAllOutdated(outdatedThreshold)

    fun outdatedSubscriptionsWithNotificationMode(outdatedThreshold: OffsetDateTime, @NotificationMode notificationMode: Int) =
        feedTable.getOutdatedWithNotificationMode(outdatedThreshold, notificationMode)

    fun notLoadedCount(groupId: Long = FeedGroupEntity.GROUP_ALL_ID): Flowable<Long> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.notLoadedCount()
            else -> feedTable.notLoadedCountForGroup(groupId)
        }
    }

    fun outdatedSubscriptionsForGroup(groupId: Long = FeedGroupEntity.GROUP_ALL_ID, outdatedThreshold: OffsetDateTime) =
        feedTable.getAllOutdatedForGroup(groupId, outdatedThreshold)

    fun markAsOutdated(subscriptionId: Long) =
        feedTable.setLastUpdatedForSubscription(FeedLastUpdatedEntity(subscriptionId, null))

    fun doesStreamExist(stream: StreamInfoItem): Boolean {
        return streamTable.exists(stream.serviceId, stream.url)
    }

    fun upsertAll(subscriptionId: Long, items: List<StreamInfoItem>, oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE) {
        val itemsToInsert = ArrayList<StreamInfoItem>()
        loop@ for (streamItem in items) {
            val uploadDate = streamItem.uploadDate
            itemsToInsert += when {
                uploadDate == null && streamItem.streamType == StreamType.LIVE_STREAM -> streamItem
                uploadDate != null && uploadDate.offsetDateTime() >= oldestAllowedDate -> streamItem
                else -> continue@loop
            }
        }

        feedTable.unlinkOldLivestreams(subscriptionId)
        if (itemsToInsert.isNotEmpty()) {
            val streamEntities = itemsToInsert.map { StreamEntity(it) }
            val streamIds = streamTable.upsertAll(streamEntities)
            val feedEntities = streamIds.map { FeedEntity(it, subscriptionId) }
            feedTable.insertAll(feedEntities)
        }
        feedTable.setLastUpdatedForSubscription(FeedLastUpdatedEntity(subscriptionId, OffsetDateTime.now(ZoneOffset.UTC)))
    }

    fun removeOrphansOrOlderStreams(oldestAllowedDate: OffsetDateTime = FEED_OLDEST_ALLOWED_DATE) {
        feedTable.unlinkStreamsOlderThan(oldestAllowedDate)
        streamTable.deleteOrphans()
    }

    fun clear() {
        feedTable.deleteAll()
        val deletedOrphans = streamTable.deleteOrphans()
        Logd(this::class.java.simpleName, "clear() → streamTable.deleteOrphans() → $deletedOrphans")
    }

    fun subscriptionIdsForGroup(groupId: Long): Flowable<List<Long>> {
        return feedGroupTable.getSubscriptionIdsFor(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateSubscriptionsForGroup(groupId: Long, subscriptionIds: List<Long>): Completable {
        return Completable
            .fromCallable { feedGroupTable.updateSubscriptionsForGroup(groupId, subscriptionIds) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun createGroup(name: String, icon: FeedGroupIcon): Maybe<Long> {
        return Maybe.fromCallable { feedGroupTable.insert(FeedGroupEntity(0, name, icon)) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun getGroup(groupId: Long): Maybe<FeedGroupEntity> {
        return feedGroupTable.getGroup(groupId)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroup(feedGroupEntity: FeedGroupEntity): Completable {
        return Completable.fromCallable { feedGroupTable.update(feedGroupEntity) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun deleteGroup(groupId: Long): Completable {
        return Completable.fromCallable { feedGroupTable.delete(groupId) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun updateGroupsOrder(groupIdList: List<Long>): Completable {
        var index = 0L
        val orderMap = groupIdList.associateBy({ it }, { index++ })

        return Completable.fromCallable { feedGroupTable.updateOrder(orderMap) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
    }

    fun oldestSubscriptionUpdate(groupId: Long): Flowable<List<OffsetDateTime>> {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> feedTable.oldestSubscriptionUpdateFromAll()
            else -> feedTable.oldestSubscriptionUpdate(groupId)
        }
    }
}
