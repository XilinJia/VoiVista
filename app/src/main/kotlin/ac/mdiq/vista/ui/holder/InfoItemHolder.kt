package ac.mdiq.vista.ui.holder

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.ui.util.InfoItemBuilder
import ac.mdiq.vista.manager.HistoryRecordManager

/*
* Created by Christian Schabesberger on 12.02.17.
*
* Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* InfoItemHolder.kt is part of Vista.
*
* Vista is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* Vista is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Vista.  If not, see <http://www.gnu.org/licenses/>.
*/
abstract class InfoItemHolder(@JvmField protected val itemBuilder: InfoItemBuilder, layoutId: Int, parent: ViewGroup?)
    : RecyclerView.ViewHolder(LayoutInflater.from(itemBuilder.context).inflate(layoutId, parent, false)) {

    abstract fun updateFromItem(infoItem: InfoItem, historyRecordManager: HistoryRecordManager?)

    open fun updateState(infoItem: InfoItem?, historyRecordManager: HistoryRecordManager?) {}
}
