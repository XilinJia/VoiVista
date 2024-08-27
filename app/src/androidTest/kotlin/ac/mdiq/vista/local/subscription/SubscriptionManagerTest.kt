package ac.mdiq.vista.local.subscription

import androidx.test.core.app.ApplicationProvider
import org.junit.*
import ac.mdiq.vista.database.AppDatabase
import ac.mdiq.vista.database.feed.model.FeedGroupEntity
import ac.mdiq.vista.database.subscription.SubscriptionEntity
import ac.mdiq.vista.database.subscription.SubscriptionEntity.Companion.from
import ac.mdiq.vista.extractor.channel.ChannelInfo
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.testUtil.TestDatabase.Companion.createReplacingVoiVistaDatabase
import ac.mdiq.vista.testUtil.TrampolineSchedulerRule
import ac.mdiq.vista.manager.SubscriptionManager
import java.io.IOException

class SubscriptionManagerTest {
    private var database: AppDatabase? = null
    private var manager: SubscriptionManager? = null

    @JvmField
    @Rule
    var trampolineScheduler: TrampolineSchedulerRule = TrampolineSchedulerRule()


    private val assertOneSubscriptionEntity: SubscriptionEntity
        get() {
            val entities = manager!!
                .getSubscriptions(FeedGroupEntity.GROUP_ALL_ID, "", false)
                .blockingFirst()
            Assert.assertEquals(1, entities.size.toLong())
            return entities[0]
        }


    @Before
    fun setup() {
        database = createReplacingVoiVistaDatabase()
        manager = SubscriptionManager(ApplicationProvider.getApplicationContext())
    }

    @After
    fun cleanUp() {
        database!!.close()
    }

    @Test
    @Throws(ExtractionException::class, IOException::class)
    fun testInsert() {
        val info = ChannelInfo.getInfo("https://www.youtube.com/c/3blue1brown")
        val subscription = from(info)

        manager!!.insertSubscription(subscription)
        val readSubscription = assertOneSubscriptionEntity

        // the uid has changed, since the uid is chosen upon inserting, but the rest should match
        Assert.assertEquals(subscription.serviceId.toLong(), readSubscription.serviceId.toLong())
        Assert.assertEquals(subscription.url, readSubscription.url)
        Assert.assertEquals(subscription.name, readSubscription.name)
        Assert.assertEquals(subscription.avatarUrl, readSubscription.avatarUrl)
        Assert.assertEquals(subscription.subscriberCount, readSubscription.subscriberCount)
        Assert.assertEquals(subscription.description, readSubscription.description)
    }

    @Test
    @Throws(ExtractionException::class, IOException::class)
    fun testUpdateNotificationMode() {
        val info = ChannelInfo.getInfo("https://www.youtube.com/c/veritasium")
        val subscription = from(info)
        subscription.notificationMode = 0

        manager!!.insertSubscription(subscription)
        manager!!.updateNotificationMode(subscription.serviceId, subscription.url!!, 1)
            .blockingAwait()
        val anotherSubscription = assertOneSubscriptionEntity

        Assert.assertEquals(0, subscription.notificationMode.toLong())
        Assert.assertEquals(subscription.url, anotherSubscription.url)
        Assert.assertEquals(1, anotherSubscription.notificationMode.toLong())
    }
}
