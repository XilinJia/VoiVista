package ac.mdiq.vista.ui.holder

import ac.mdiq.vista.R
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.playlist.PlaylistInfoItem
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.ui.util.InfoItemBuilder
import ac.mdiq.vista.util.Localization.localizeStreamCountMini
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.image.PicassoHelper.loadPlaylistThumbnail
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

open class PlaylistMiniInfoItemHolder(infoItemBuilder: InfoItemBuilder, layoutId: Int, parent: ViewGroup?)
    : InfoItemHolder(infoItemBuilder, layoutId, parent) {

    val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    private val itemStreamCountView: TextView = itemView.findViewById(R.id.itemStreamCountView)
    val itemTitleView: TextView = itemView.findViewById(R.id.itemTitleView)
    val itemUploaderView: TextView = itemView.findViewById(R.id.itemUploaderView)

    constructor(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?) : this(infoItemBuilder, R.layout.list_playlist_mini_item, parent)

    override fun updateFromItem(infoItem: InfoItem, historyRecordManager: HistoryRecordManager?) {
        if (infoItem !is PlaylistInfoItem) return

        Logd("PlaylistMiniInfoItemHolder", "updateFromItem: ${infoItem.name}")
        itemTitleView.text = infoItem.name
        itemStreamCountView.text = localizeStreamCountMini(itemStreamCountView.context, infoItem.streamCount)
        itemUploaderView.text = infoItem.uploaderName

        loadPlaylistThumbnail(infoItem.thumbnails).into(itemThumbnailView)
        itemView.setOnClickListener {
            itemBuilder.onPlaylistSelectedListener?.selected(infoItem)
        }
        itemView.isLongClickable = true
        itemView.setOnLongClickListener {
            itemBuilder.onPlaylistSelectedListener?.held(infoItem)
            true
        }
    }
}
