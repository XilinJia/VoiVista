package ac.mdiq.vista.local.subscription.item

import android.view.View
import com.xwray.groupie.viewbinding.BindableItem
import ac.mdiq.vista.R
import ac.mdiq.vista.database.feed.model.FeedGroupEntity
import ac.mdiq.vista.databinding.FeedGroupCardItemBinding
import ac.mdiq.vista.local.subscription.FeedGroupIcon

data class FeedGroupCardItem(
        val groupId: Long = FeedGroupEntity.GROUP_ALL_ID,
        val name: String,
        val icon: FeedGroupIcon?)
    : BindableItem<FeedGroupCardItemBinding>() {
    constructor (feedGroupEntity: FeedGroupEntity) : this(feedGroupEntity.uid, feedGroupEntity.name, feedGroupEntity.icon)

    override fun getId(): Long {
        return when (groupId) {
            FeedGroupEntity.GROUP_ALL_ID -> super.getId()
            else -> groupId
        }
    }

    override fun getLayout(): Int = R.layout.feed_group_card_item

    override fun bind(viewBinding: FeedGroupCardItemBinding, position: Int) {
        viewBinding.title.text = name
        if (icon != null) viewBinding.icon.setImageResource(icon.getDrawableRes())
    }

    override fun initializeViewBinding(view: View) = FeedGroupCardItemBinding.bind(view)
}
