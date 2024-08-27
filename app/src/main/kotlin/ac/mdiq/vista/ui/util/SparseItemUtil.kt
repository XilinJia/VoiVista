package ac.mdiq.vista.ui.util

import ac.mdiq.vista.R
import ac.mdiq.vista.database.VoiVistaDatabase
import ac.mdiq.vista.database.stream.StreamTypeUtil
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.error.ErrorUtil.Companion.createNotification
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.player.playqueue.SinglePlayQueue
import ac.mdiq.vista.util.ExtractorHelper.getStreamInfo
import android.content.Context
import android.widget.Toast
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.function.Consumer

/**
 * Utility class for fetching additional data for stream items when needed.
 */
object SparseItemUtil {
    /**
     * Use this to certainly obtain an single play queue with all of the data filled in when the
     * stream info item you are handling might be sparse, e.g. because it was fetched via a [ ]. FeedExtractors provide a fast and
     * lightweight method to fetch info, but the info might be incomplete (see
     * [ac.mdiq.vista.local.feed.service.FeedLoadService] for more details).
     *
     * @param context  Android context
     * @param item     item which is checked and eventually loaded completely
     * @param callback callback to call with the single play queue built from the original item if
     * all info was available, otherwise from the fetched [                 ]
     */
    fun fetchItemInfoIfSparse(context: Context, item: StreamInfoItem, callback: Consumer<SinglePlayQueue?>) {
        if ((StreamTypeUtil.isLiveStream(item.streamType) || item.duration >= 0)
                && !item.uploaderUrl.isNullOrEmpty()) {
            // if the duration is >= 0 (provided that the item is not a livestream) and there is an
            // uploader url, probably all info is already there, so there is no need to fetch it
            callback.accept(SinglePlayQueue(item))
            return
        }

        // either the duration or the uploader url are not available, so fetch more info
        fetchStreamInfoAndSaveToDatabase(context, item.serviceId, item.url) {
            streamInfo: StreamInfo? -> callback.accept(SinglePlayQueue(streamInfo)) }
    }

    /**
     * Use this to certainly obtain an uploader url when the stream info item or play queue item you
     * are handling might not have the uploader url (e.g. because it was fetched with [ ]). A toast is shown if loading details is
     * required.
     *
     * @param context     Android context
     * @param serviceId   serviceId of the item
     * @param url         item url
     * @param uploaderUrl uploaderUrl of the item; if null or empty will be fetched
     * @param callback    callback to be called with either the original uploaderUrl, if it was a
     * valid url, otherwise with the uploader url obtained by fetching the [                    ] corresponding to the item
     */
    fun fetchUploaderUrlIfSparse(context: Context, serviceId: Int, url: String, uploaderUrl: String?, callback: Consumer<String?>) {
        if (!uploaderUrl.isNullOrEmpty()) {
            callback.accept(uploaderUrl)
            return
        }
        fetchStreamInfoAndSaveToDatabase(context, serviceId, url) {
            streamInfo: StreamInfo -> callback.accept(streamInfo.uploaderUrl) }
    }

    /**
     * Loads the stream info corresponding to the given data on an I/O thread, stores the result in
     * the database and calls the callback on the main thread with the result. A toast will be shown
     * to the user about loading stream details, so this needs to be called on the main thread.
     *
     * @param context   Android context
     * @param serviceId service id of the stream to load
     * @param url       url of the stream to load
     * @param callback  callback to be called with the result
     */
    fun fetchStreamInfoAndSaveToDatabase(context: Context, serviceId: Int, url: String, callback: Consumer<StreamInfo>) {
        Toast.makeText(context, R.string.loading_stream_details, Toast.LENGTH_SHORT).show()
        getStreamInfo(serviceId, url, false)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ result: StreamInfo ->
                // save to database in the background (not on main thread)
                Completable.fromAction { VoiVistaDatabase.getInstance(context).streamDAO().upsert(StreamEntity(result)) }
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .doOnError { throwable: Throwable? ->
                        createNotification(context, ErrorInfo(throwable!!, UserAction.REQUESTED_STREAM, "Saving stream info to database", result)) }
                    .subscribe()

                // call callback on main thread with the obtained result
                callback.accept(result)
            }, { throwable: Throwable? ->
                createNotification(context, ErrorInfo(throwable!!, UserAction.REQUESTED_STREAM, "Loading stream info: $url", serviceId)) })
    }
}
