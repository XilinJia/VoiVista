package ac.mdiq.vista.ui.util

import android.content.Context
import android.view.View
import android.view.ViewGroup
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.InfoItem.InfoType
import ac.mdiq.vista.extractor.channel.ChannelInfoItem
import ac.mdiq.vista.extractor.comments.CommentsInfoItem
import ac.mdiq.vista.extractor.playlist.PlaylistInfoItem
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.ui.holder.*
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.ui.gesture.OnClickGesture

/*
* Created by Christian Schabesberger on 26.09.16.
* <p>
* Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* InfoItemBuilder.kt is part of Vista.
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
* </p>
* <p>
* You should have received a copy of the GNU General Public License
* along with Vista.  If not, see <http://www.gnu.org/licenses/>.
* </p>
*/
class InfoItemBuilder(val context: Context) {
    var onStreamSelectedListener: OnClickGesture<StreamInfoItem>? = null
    var onChannelSelectedListener: OnClickGesture<ChannelInfoItem>? = null
    var onPlaylistSelectedListener: OnClickGesture<PlaylistInfoItem>? = null
    var onCommentsSelectedListener: OnClickGesture<CommentsInfoItem>? = null

    @JvmOverloads
    fun buildView(parent: ViewGroup, infoItem: InfoItem, historyRecordManager: HistoryRecordManager?, useMiniVariant: Boolean = false): View {
        val holder = holderFromInfoType(parent, infoItem.infoType, useMiniVariant)
        holder.updateFromItem(infoItem, historyRecordManager)
        return holder.itemView
    }

    private fun holderFromInfoType(parent: ViewGroup, infoType: InfoType, useMiniVariant: Boolean): InfoItemHolder {
        return when (infoType) {
            InfoType.STREAM -> if (useMiniVariant) StreamMiniInfoItemHolder(this, parent)
            else StreamInfoItemHolder(this, parent)
            InfoType.CHANNEL -> if (useMiniVariant) ChannelMiniInfoItemHolder(this, parent)
            else ChannelInfoItemHolder(this, parent)
            InfoType.PLAYLIST -> if (useMiniVariant) PlaylistMiniInfoItemHolder(this, parent)
            else PlaylistInfoItemHolder(this, parent)
            InfoType.COMMENT -> CommentInfoItemHolder(this, parent)
            else -> throw RuntimeException("InfoType not expected = " + infoType.name)
        }
    }
}
