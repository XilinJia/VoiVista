package ac.mdiq.vista.ui.util

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentManager
import androidx.media3.common.util.UnstableApi
import ac.mdiq.vista.R
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.ui.dialog.DownloadDialog
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.ui.dialog.PlaylistDialog
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.player.playqueue.PlayQueueItem
import ac.mdiq.vista.ui.util.NavigationHelper.openChannelFragmentUsingIntent
import ac.mdiq.vista.ui.util.NavigationHelper.openVideoDetail
import ac.mdiq.vista.ui.util.SparseItemUtil.fetchStreamInfoAndSaveToDatabase
import ac.mdiq.vista.ui.util.SparseItemUtil.fetchUploaderUrlIfSparse
import ac.mdiq.vista.ui.util.ShareUtils.shareText
import java.util.function.Consumer

object QueueItemMenuUtil {
    @OptIn(UnstableApi::class)
    fun openPopupMenu(playQueue: PlayQueue, item: PlayQueueItem, view: View?, hideDetails: Boolean, fragmentManager: FragmentManager, context: Context) {
        val themeWrapper = ContextThemeWrapper(context, R.style.DarkPopupMenu)
        val popupMenu = PopupMenu(themeWrapper, view)
        popupMenu.inflate(R.menu.menu_play_queue_item)
        if (hideDetails) popupMenu.menu.findItem(R.id.menu_item_details).setVisible(false)

        popupMenu.setOnMenuItemClickListener { menuItem: MenuItem ->
            when (menuItem.itemId) {
                R.id.menu_item_remove -> {
                    val index = playQueue.indexOf(item)
                    playQueue.remove(index)
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_details -> {
                    // playQueue is null since we don't want any queue change
                    openVideoDetail(context, item.serviceId, item.url, item.title, null, false)
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_append_playlist -> {
                    PlaylistDialog.createCorrespondingDialog(context, listOf(StreamEntity(item))) { dialog: PlaylistDialog ->
                        dialog.show(fragmentManager, "QueueItemMenuUtil@append_playlist")
                    }
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_channel_details -> {
                    // Opening with FragmentManager transactions is not working,
                    // as PlayQueueActivity doesn't use fragments.
                    // An intent must be used here.
                    fetchUploaderUrlIfSparse(context, item.serviceId, item.url, item.uploaderUrl)
                    { uploaderUrl: String? -> openChannelFragmentUsingIntent(context, item.serviceId, uploaderUrl, item.uploader) }
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_share -> {
                    shareText(context, item.title, item.url, item.thumbnails)
                    return@setOnMenuItemClickListener true
                }
                R.id.menu_item_download -> {
                    fetchStreamInfoAndSaveToDatabase(context, item.serviceId, item.url, Consumer { info: StreamInfo ->
                        val downloadDialog = DownloadDialog(context, info)
                        downloadDialog.show(fragmentManager, "downloadDialog") })
                    return@setOnMenuItemClickListener true
                }
            }
            false
        }

        popupMenu.show()
    }
}
