package ac.mdiq.vista.ui.holder

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import ac.mdiq.vista.R
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.channel.ChannelInfoItem
import ac.mdiq.vista.ui.util.InfoItemBuilder
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.util.Localization.concatenateStrings
import ac.mdiq.vista.util.Localization.localizeStreamCount
import ac.mdiq.vista.util.Localization.shortSubscriberCount
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.image.PicassoHelper.loadAvatar

open class ChannelMiniInfoItemHolder internal constructor(infoItemBuilder: InfoItemBuilder, layoutId: Int, parent: ViewGroup?)
    : InfoItemHolder(infoItemBuilder, layoutId, parent) {

    private val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    private val itemTitleView: TextView = itemView.findViewById(R.id.itemTitleView)
    private val itemAdditionalDetailView: TextView = itemView.findViewById(R.id.itemAdditionalDetails)
    private val itemChannelDescriptionView: TextView? = itemView.findViewById(R.id.itemChannelDescriptionView)

    constructor(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?) : this(infoItemBuilder, R.layout.list_channel_mini_item, parent)

    override fun updateFromItem(infoItem: InfoItem, historyRecordManager: HistoryRecordManager?) {
        if (infoItem !is ChannelInfoItem) return

        Logd("ChannelMiniInfoItemHolder", "updateFromItem: ${infoItem.name}")
        itemTitleView.text = infoItem.name
        itemTitleView.isSelected = true

        val detailLine = getDetailLine(infoItem)
        if (detailLine == null) itemAdditionalDetailView.visibility = View.GONE
        else {
            itemAdditionalDetailView.visibility = View.VISIBLE
            itemAdditionalDetailView.text = getDetailLine(infoItem)
        }
        loadAvatar(infoItem.thumbnails).into(itemThumbnailView)
        itemView.setOnClickListener {
            itemBuilder.onChannelSelectedListener?.selected(infoItem)
        }
        itemView.setOnLongClickListener {
            itemBuilder.onChannelSelectedListener?.held(infoItem)
            true
        }
        if (itemChannelDescriptionView != null) {
            // itemChannelDescriptionView will be null in the mini variant
            if (infoItem.description.isNullOrBlank()) itemChannelDescriptionView.visibility = View.GONE
            else {
                itemChannelDescriptionView.visibility = View.VISIBLE
                itemChannelDescriptionView.text = infoItem.description
                // setMaxLines utilize the line space for description if the additional details
                // (sub / video count) are not present.
                // Case1: 2 lines of description + 1 line additional details
                // Case2: 3 lines of description (additionalDetails is GONE)
                itemChannelDescriptionView.maxLines = getDescriptionMaxLineCount(detailLine)
            }
        }
    }

    /**
     * Returns max number of allowed lines for the description field.
     * @param content additional detail content (video / sub count)
     * @return max line count
     */
    protected open fun getDescriptionMaxLineCount(content: String?): Int {
        return if (content == null) 3 else 2
    }

    private fun getDetailLine(item: ChannelInfoItem): String? {
        return when {
            item.streamCount >= 0 && item.subscriberCount >= 0 ->
                concatenateStrings(shortSubscriberCount(itemBuilder.context, item.subscriberCount), localizeStreamCount(itemBuilder.context, item.streamCount))
            item.streamCount >= 0 -> localizeStreamCount(itemBuilder.context, item.streamCount)
            item.subscriberCount >= 0 -> shortSubscriberCount(itemBuilder.context, item.subscriberCount)
            else -> null
        }
    }
}
