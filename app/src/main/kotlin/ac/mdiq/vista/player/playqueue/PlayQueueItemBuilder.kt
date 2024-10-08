package ac.mdiq.vista.player.playqueue

import android.content.Context
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import ac.mdiq.vista.ui.player.PlayQueueItemHolder
import ac.mdiq.vista.util.Localization.concatenateStrings
import ac.mdiq.vista.util.Localization.getDurationString
import ac.mdiq.vista.util.ServiceHelper.getNameOfServiceById
import ac.mdiq.vista.util.image.PicassoHelper.loadThumbnail

class PlayQueueItemBuilder(context: Context?) {
    private var onItemClickListener: OnSelectedListener? = null

    fun setOnSelectedListener(listener: OnSelectedListener?) {
        this.onItemClickListener = listener
    }

    fun buildStreamInfoItem(holder: PlayQueueItemHolder, item: PlayQueueItem) {
        if (!item.title.isEmpty()) holder.itemVideoTitleView.text = item.title
        holder.itemAdditionalDetailsView.text = concatenateStrings(item.uploader, getNameOfServiceById(item.serviceId))
        if (item.duration > 0) holder.itemDurationView.text = getDurationString(item.duration)
        else holder.itemDurationView.visibility = View.GONE

        loadThumbnail(item.thumbnails).into(holder.itemThumbnailView)

        holder.itemRoot.setOnClickListener { view: View ->
            onItemClickListener?.selected(item, view)
        }
        holder.itemRoot.setOnLongClickListener { view: View ->
            if (onItemClickListener != null) {
                onItemClickListener!!.held(item, view)
                return@setOnLongClickListener true
            }
            false
        }
        holder.itemHandle.setOnTouchListener(getOnTouchListener(holder))
    }

    private fun getOnTouchListener(holder: PlayQueueItemHolder): OnTouchListener {
        return OnTouchListener { view: View, motionEvent: MotionEvent ->
            view.performClick()
            if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN && onItemClickListener != null) onItemClickListener!!.onStartDrag(holder)
            false
        }
    }

    interface OnSelectedListener {
        fun selected(item: PlayQueueItem, view: View)
        fun held(item: PlayQueueItem, view: View)
        fun onStartDrag(viewHolder: PlayQueueItemHolder)
    }

    companion object {
        private val TAG = PlayQueueItemBuilder::class.java.toString()
    }
}
