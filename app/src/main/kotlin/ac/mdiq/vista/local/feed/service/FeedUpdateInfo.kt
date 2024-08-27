package ac.mdiq.vista.local.feed.service

import ac.mdiq.vista.database.subscription.NotificationMode
import ac.mdiq.vista.database.subscription.SubscriptionEntity
import ac.mdiq.vista.extractor.Info
import ac.mdiq.vista.extractor.channel.ChannelInfo
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.util.image.ImageStrategy

/**
 * Instances of this class might stay around in memory for some time while fetching the feed,
 * because of [FeedLoadManager.BUFFER_COUNT_BEFORE_INSERT]. Therefore this class should contain
 * as little data as possible to avoid out of memory errors. In particular, avoid storing whole
 * [ChannelInfo] objects, as they might contain raw JSON info in ready channel tabs link handlers.
 */
data class FeedUpdateInfo(
    val uid: Long,
    @NotificationMode
    val notificationMode: Int,
    val name: String,
    val avatarUrl: String?,
    val url: String,
    val serviceId: Int,
    // description and subscriberCount are null if the constructor info is from the fast feed method
    val description: String?,
    val subscriberCount: Long?,
    val streams: List<StreamInfoItem>,
    val errors: List<Throwable>) {

    constructor(subscription: SubscriptionEntity, info: Info, streams: List<StreamInfoItem>, errors: List<Throwable>) : this(
        uid = subscription.uid,
        notificationMode = subscription.notificationMode,
        name = info.name,
        // if the newly fetched info is not from fast feed, then it contains updated avatars
        avatarUrl = (info as? ChannelInfo)?.avatars?.let { ImageStrategy.imageListToDbUrl(it) } ?: subscription.avatarUrl,
        url = info.url,
        serviceId = info.serviceId,
        // there is no description and subscriberCount in the fast feed
        description = (info as? ChannelInfo)?.description,
        subscriberCount = (info as? ChannelInfo)?.subscriberCount,
        streams = streams,
        errors = errors,
    )

    /**
     * Integer id, can be used as notification id, etc.
     */
    val pseudoId: Int
        get() = url.hashCode()

    lateinit var newStreams: List<StreamInfoItem>
}
