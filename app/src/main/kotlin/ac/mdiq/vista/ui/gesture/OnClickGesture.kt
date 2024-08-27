package ac.mdiq.vista.ui.gesture

import androidx.recyclerview.widget.RecyclerView

fun interface OnClickGesture<T> {
    fun selected(selectedItem: T)

    fun held(selectedItem: T) {
        // Optional gesture
    }

    fun drag(selectedItem: T, viewHolder: RecyclerView.ViewHolder?) {
        // Optional gesture
    }
}
