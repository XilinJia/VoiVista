package ac.mdiq.vista.ui.holder

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import ac.mdiq.vista.R
import ac.mdiq.vista.database.stream.model.StreamStateEntity
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.extractor.stream.StreamType
import ac.mdiq.vista.ui.util.InfoItemBuilder
import ac.mdiq.vista.ui.util.ktx.animate
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.util.DependentPreferenceHelper.getPositionsInListsEnabled
import ac.mdiq.vista.util.Localization.getDurationString
import ac.mdiq.vista.database.stream.StreamTypeUtil.isLiveStream
import ac.mdiq.vista.util.image.PicassoHelper.loadThumbnail
import ac.mdiq.vista.ui.views.AnimatedProgressBar
import ac.mdiq.vista.util.Logd
import java.util.concurrent.TimeUnit

open class StreamMiniInfoItemHolder internal constructor(infoItemBuilder: InfoItemBuilder, layoutId: Int, parent: ViewGroup?)
    : InfoItemHolder(infoItemBuilder, layoutId, parent) {

    val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    val itemVideoTitleView: TextView = itemView.findViewById(R.id.itemVideoTitleView)
    val itemUploaderView: TextView = itemView.findViewById(R.id.itemUploaderView)
    val itemDurationView: TextView = itemView.findViewById(R.id.itemDurationView)
    private val itemProgressView: AnimatedProgressBar = itemView.findViewById(R.id.itemProgressView)

    constructor(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?) : this(infoItemBuilder, R.layout.list_stream_mini_item, parent)

    override fun updateFromItem(infoItem: InfoItem, historyRecordManager: HistoryRecordManager?) {
        if (infoItem !is StreamInfoItem) return

        Logd("StreamMiniInfoItemHolder", "updateFromItem: ${infoItem.name}")
        itemVideoTitleView.text = infoItem.name
        itemUploaderView.text = infoItem.uploaderName

        when {
            infoItem.duration > 0 -> {
                itemDurationView.text = getDurationString(infoItem.duration)
                itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.context,
                    R.color.duration_background_color))
                itemDurationView.visibility = View.VISIBLE

                var state2: StreamStateEntity? = null
                if (getPositionsInListsEnabled(itemProgressView.context)) {
                    val sts = historyRecordManager!!.loadStreamState(infoItem).blockingGet()
                    if (sts.isNotEmpty()) state2 = sts[0]
                }
                if (state2 != null) {
                    itemProgressView.visibility = View.VISIBLE
                    itemProgressView.max = infoItem.duration.toInt()
                    itemProgressView.progress = TimeUnit.MILLISECONDS.toSeconds(state2.progressMillis).toInt()
                } else itemProgressView.visibility = View.GONE
            }
            isLiveStream(infoItem.streamType) -> {
                itemDurationView.setText(R.string.duration_live)
                itemDurationView.setBackgroundColor(ContextCompat.getColor(itemBuilder.context, R.color.live_duration_background_color))
                itemDurationView.visibility = View.VISIBLE
                itemProgressView.visibility = View.GONE
            }
            else -> {
                itemDurationView.visibility = View.GONE
                itemProgressView.visibility = View.GONE
            }
        }

        // Default thumbnail is shown on error, while loading and if the url is empty
        loadThumbnail(infoItem.thumbnails).into(itemThumbnailView)
        itemView.setOnClickListener {
            itemBuilder.onStreamSelectedListener?.selected(infoItem)
        }

        when (infoItem.streamType) {
            StreamType.AUDIO_STREAM, StreamType.VIDEO_STREAM, StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM,
            StreamType.POST_LIVE_STREAM, StreamType.POST_LIVE_AUDIO_STREAM -> enableLongClick(infoItem)
            StreamType.NONE -> disableLongClick()
            else -> disableLongClick()
        }
    }

    override fun updateState(infoItem: InfoItem?, historyRecordManager: HistoryRecordManager?) {
        val item = infoItem as? StreamInfoItem

        var state: StreamStateEntity? = null
        if (getPositionsInListsEnabled(itemProgressView.context)) {
            if (infoItem != null && historyRecordManager != null) state = historyRecordManager.loadStreamState(infoItem).blockingGet()[0]
        }
        if (state != null && item!!.duration > 0 && !isLiveStream(item.streamType)) {
            itemProgressView.max = item.duration.toInt()
            if (itemProgressView.visibility == View.VISIBLE)
                itemProgressView.setProgressAnimated(TimeUnit.MILLISECONDS.toSeconds(state.progressMillis).toInt())
            else {
                itemProgressView.progress = TimeUnit.MILLISECONDS.toSeconds(state.progressMillis).toInt()
                itemProgressView.animate(true, 500)
            }
        } else if (itemProgressView.visibility == View.VISIBLE) itemProgressView.animate(false, 500)
    }

    private fun enableLongClick(item: StreamInfoItem) {
        itemView.isLongClickable = true
        itemView.setOnLongClickListener {
            itemBuilder.onStreamSelectedListener?.held(item)
            true
        }
    }

    private fun disableLongClick() {
        itemView.isLongClickable = false
        itemView.setOnLongClickListener(null)
    }
}
