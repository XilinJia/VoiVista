package ac.mdiq.vista.error

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith
import ac.mdiq.vista.extractor.ServiceList
import ac.mdiq.vista.extractor.exceptions.ParsingException
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.util.error.ErrorInfo

/**
 * Instrumented tests for [ErrorInfo].
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ErrorInfoTest {
    @Test
    fun errorInfoTestParcelable() {
        val info = ErrorInfo(ParsingException("Hello"),
            UserAction.USER_REPORT, "request", ServiceList.YouTube.serviceId)
        // Obtain a Parcel object and write the parcelable object to it:
        val parcel = Parcel.obtain()
        info.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
//        TODO: create ErrorInfo.CREATOR
//        val infoFromParcel = ErrorInfo.CREATOR.createFromParcel(parcel) as ErrorInfo

//        Assert.assertTrue(infoFromParcel.stackTraces.contentToString().contains(ErrorInfoTest::class.java.simpleName))
//        Assert.assertEquals(UserAction.USER_REPORT, infoFromParcel.userAction)
//        Assert.assertEquals(ServiceList.YouTube.serviceInfo.name, infoFromParcel.serviceName)
//        Assert.assertEquals("request", infoFromParcel.request)
//        Assert.assertEquals(R.string.parsing_error.toLong(), infoFromParcel.messageStringId.toLong())

        parcel.recycle()
    }
}
