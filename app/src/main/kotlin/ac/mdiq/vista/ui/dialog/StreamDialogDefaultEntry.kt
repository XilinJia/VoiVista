package ac.mdiq.vista.ui.dialog

import android.net.Uri
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import ac.mdiq.vista.R
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.ui.dialog.StreamDialogEntry.StreamDialogEntryAction
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.player.playqueue.SinglePlayQueue
import ac.mdiq.vista.ui.util.NavigationHelper.enqueueNextOnPlayer
import ac.mdiq.vista.ui.util.NavigationHelper.enqueueOnPlayer
import ac.mdiq.vista.ui.util.NavigationHelper.openChannelFragment
import ac.mdiq.vista.ui.util.NavigationHelper.playOnBackgroundPlayer
import ac.mdiq.vista.ui.util.NavigationHelper.playOnPopupPlayer
import ac.mdiq.vista.ui.util.SparseItemUtil.fetchItemInfoIfSparse
import ac.mdiq.vista.ui.util.SparseItemUtil.fetchStreamInfoAndSaveToDatabase
import ac.mdiq.vista.ui.util.SparseItemUtil.fetchUploaderUrlIfSparse
import ac.mdiq.vista.util.KoreUtils.playWithKore
import ac.mdiq.vista.ui.util.ShareUtils.openUrlInBrowser
import ac.mdiq.vista.ui.util.ShareUtils.shareText
import java.util.function.Consumer

/**
 *
 *
 * This enum provides entries that are accepted
 * by the [InfoItemDialog.Builder].
 *
 * These entries contain a String [.resource] which is displayed in the dialog and
 * a default [.action] that is executed
 * when the entry is selected (via `onClick()`).
 * <br></br>
 * They action can be overridden by using the Builder's
 * [InfoItemDialog.Builder.setAction]
 * method.
 *
 */
@UnstableApi enum class StreamDialogDefaultEntry(@field:StringRes @param:StringRes val resource: Int, val action: StreamDialogEntryAction) {
    SHOW_CHANNEL_DETAILS(R.string.show_channel_details, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        fetchUploaderUrlIfSparse(fragment!!.requireContext(), item!!.serviceId, item.url, item.uploaderUrl, Consumer { url: String? ->
            openChannelFragment(fragment, item, url)
        })
    }),

    /**
     * Enqueues the stream automatically to the current PlayerType.
     */
    ENQUEUE(R.string.enqueue_stream, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        fetchItemInfoIfSparse(fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
            enqueueOnPlayer(fragment.requireContext(), singlePlayQueue)
        })
    }),

    /**
     * Enqueues the stream automatically to the current PlayerType
     * after the currently playing stream.
     */
    ENQUEUE_NEXT(R.string.enqueue_next_stream, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        fetchItemInfoIfSparse(fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
            enqueueNextOnPlayer(fragment.requireContext(), singlePlayQueue)
        })
    }),

    START_HERE_ON_BACKGROUND(R.string.start_here_on_background,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            fetchItemInfoIfSparse(fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
                playOnBackgroundPlayer(fragment.requireContext(), singlePlayQueue, true)
            })
        }),

    START_HERE_ON_POPUP(R.string.start_here_on_popup,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            fetchItemInfoIfSparse(fragment!!.requireContext(), item!!, Consumer { singlePlayQueue: SinglePlayQueue? ->
                playOnPopupPlayer(fragment.requireContext(), singlePlayQueue, true)
            })
        }),

    SET_AS_PLAYLIST_THUMBNAIL(R.string.set_as_playlist_thumbnail,
        StreamDialogEntryAction { _: Fragment?, _: StreamInfoItem? ->
            throw UnsupportedOperationException("This needs to be implemented manually by using InfoItemDialog.Builder.setAction()")
        }),

    DELETE(R.string.delete, StreamDialogEntryAction { _: Fragment?, _: StreamInfoItem? ->
        throw UnsupportedOperationException("This needs to be implemented manually by using InfoItemDialog.Builder.setAction()")
    }),

    /**
     * Opens a [PlaylistDialog] to either append the stream to a playlist
     * or create a new playlist if there are no local playlists.
     */
    APPEND_PLAYLIST(R.string.add_to_playlist, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        PlaylistDialog.createCorrespondingDialog(fragment!!.context, listOf(StreamEntity(item!!))) { dialog ->
            dialog.show(fragment.parentFragmentManager, "StreamDialogEntry@${if (dialog is PlaylistAppendDialog) "append" else "create"}_playlist")
        }
    }),

    PLAY_WITH_KODI(R.string.play_with_kodi_title,
        StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
            playWithKore(fragment!!.requireContext(), Uri.parse(item!!.url))
        }),

    SHARE(R.string.share, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        shareText(fragment!!.requireContext(), item!!.name, item.url, item.thumbnails)
    }),

    /**
     * Opens a [DownloadDialog] after fetching some stream info.
     * If the user quits the current fragment, it will not open a DownloadDialog.
     */
    DOWNLOAD(R.string.download, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        fetchStreamInfoAndSaveToDatabase(fragment!!.requireContext(), item!!.serviceId, item.url, Consumer { info: StreamInfo? ->
            if (fragment.context != null) {
                val downloadDialog = DownloadDialog(fragment.requireContext(), info!!)
                downloadDialog.show(fragment.childFragmentManager, "downloadDialog")
            }
        })
    }),

    OPEN_IN_BROWSER(R.string.open_in_browser, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        openUrlInBrowser(fragment!!.requireContext(), item!!.url)
    }),


    MARK_AS_WATCHED(R.string.mark_as_watched, StreamDialogEntryAction { fragment: Fragment?, item: StreamInfoItem? ->
        HistoryRecordManager(fragment!!.requireContext()).markAsWatched(item!!).onErrorComplete().observeOn(AndroidSchedulers.mainThread()).subscribe()
    });


    fun toStreamDialogEntry(): StreamDialogEntry {
        return StreamDialogEntry(resource, action)
    }
}
