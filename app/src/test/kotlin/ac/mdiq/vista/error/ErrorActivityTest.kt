package ac.mdiq.vista.error

import org.junit.Assert
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.ui.activity.RouterActivity
import ac.mdiq.vista.ui.activity.ErrorActivity.Companion.getReturnActivity
import ac.mdiq.vista.ui.fragments.VideoDetailFragment

/**
 * Unit tests for [ErrorActivity].
 */
class ErrorActivityTest {
    val returnActivity: Unit
        get() {
            var returnActivity = getReturnActivity(MainActivity::class.java)
            Assert.assertEquals(MainActivity::class.java, returnActivity)

            returnActivity = getReturnActivity(RouterActivity::class.java)
            Assert.assertEquals(RouterActivity::class.java, returnActivity)

            returnActivity = getReturnActivity(null)
            Assert.assertNull(returnActivity)

            returnActivity = getReturnActivity(Int::class.java)
            Assert.assertEquals(MainActivity::class.java, returnActivity)

            returnActivity = getReturnActivity(VideoDetailFragment::class.java)
            Assert.assertEquals(MainActivity::class.java, returnActivity)
        }
}
