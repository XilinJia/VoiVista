package ac.mdiq.vista.ui.dialog

import android.content.Context
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import ac.mdiq.vista.extractor.stream.StreamInfoItem

class StreamDialogEntry(@field:StringRes @param:StringRes val resource: Int, val action: StreamDialogEntryAction) {
    fun getString(context: Context): String {
        return context.getString(resource)
    }

    fun interface StreamDialogEntryAction {
        fun onClick(fragment: Fragment?, infoItem: StreamInfoItem?)
    }
}
