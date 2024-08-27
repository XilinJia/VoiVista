package ac.mdiq.vista.ui.holder

import ac.mdiq.vista.R
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.extractor.stream.StreamType
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.ui.util.InfoItemBuilder
import ac.mdiq.vista.util.Localization.concatenateStrings
import ac.mdiq.vista.util.Localization.listeningCount
import ac.mdiq.vista.util.Localization.relativeTimeOrTextual
import ac.mdiq.vista.util.Localization.shortViewCount
import ac.mdiq.vista.util.Localization.shortWatchingCount
import android.view.ViewGroup
import android.widget.TextView

/*
* Created by Christian Schabesberger on 01.08.16.
* <p>
* Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* StreamInfoItemHolder.kt is part of Vista.
* </p>
* <p>
* Vista is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
* </p>
* <p>
* Vista is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* </p?
* <p>
* You should have received a copy of the GNU General Public License
* along with Vista. If not, see <http://www.gnu.org/licenses/>.
* </p>
*/
open class StreamInfoItemHolder(infoItemBuilder: InfoItemBuilder, layoutId: Int, parent: ViewGroup?)
    : StreamMiniInfoItemHolder(infoItemBuilder, layoutId, parent) {

    val itemAdditionalDetails: TextView = itemView.findViewById(R.id.itemAdditionalDetails)

    constructor(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?) : this(infoItemBuilder, R.layout.list_stream_item, parent)

    override fun updateFromItem(infoItem: InfoItem, historyRecordManager: HistoryRecordManager?) {
        super.updateFromItem(infoItem, historyRecordManager)
        if (infoItem !is StreamInfoItem) return
        itemAdditionalDetails.text = getStreamInfoDetailLine(infoItem)
    }

    private fun getStreamInfoDetailLine(infoItem: StreamInfoItem): String {
        var viewsAndDate = ""
        if (infoItem.viewCount >= 0) {
            viewsAndDate = when (infoItem.streamType) {
                StreamType.AUDIO_LIVE_STREAM -> listeningCount(itemBuilder.context, infoItem.viewCount)
                StreamType.LIVE_STREAM -> shortWatchingCount(itemBuilder.context, infoItem.viewCount)
                else -> shortViewCount(itemBuilder.context, infoItem.viewCount)
            }
        }
        val uploadDate = relativeTimeOrTextual(itemBuilder.context, infoItem.uploadDate, infoItem.textualUploadDate?:"")
        if (uploadDate.isNotEmpty()) {
            if (viewsAndDate.isEmpty()) return uploadDate
            return concatenateStrings(viewsAndDate, uploadDate)
        }
        return viewsAndDate
    }
}
