package ac.mdiq.vista.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.HtmlCompat
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.CommentRepliesHeaderBinding
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.extractor.ListExtractor.InfoItemsPage
import ac.mdiq.vista.extractor.ListInfo
import ac.mdiq.vista.extractor.comments.CommentsInfoItem
import ac.mdiq.vista.extractor.linkhandler.ListLinkHandler
import ac.mdiq.vista.ui.util.ItemViewMode
import ac.mdiq.vista.util.DeviceUtils.dpToPx
import ac.mdiq.vista.util.ExtractorHelper.getMoreCommentItems
import ac.mdiq.vista.util.Localization.likeCount
import ac.mdiq.vista.util.Localization.relativeTimeOrTextual
import ac.mdiq.vista.util.Localization.replyCount
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.ui.util.NavigationHelper.openCommentAuthorIfPresent
import ac.mdiq.vista.util.ServiceHelper.getServiceById
import ac.mdiq.vista.util.image.ImageStrategy.shouldLoadImages
import ac.mdiq.vista.util.image.PicassoHelper.loadAvatar
import ac.mdiq.vista.ui.util.TextLinkifier.fromDescription
import java.util.*
import java.util.function.Supplier

// only called by the Android framework, after which readFrom is called and restores all data
class CommentRepliesFragment()
    : BaseListInfoFragment<CommentsInfoItem, CommentRepliesFragment.CommentRepliesInfo>(UserAction.REQUESTED_COMMENT_REPLIES) {
    /**
     * @return the comment to which the replies are shown
     */
    var commentsInfoItem: CommentsInfoItem? = null // the comment to show replies of
        private set
    private val disposables = CompositeDisposable()

    constructor(commentsInfoItem: CommentsInfoItem) : this() {
        this.commentsInfoItem = commentsInfoItem
        // setting "" as title since the title will be properly set right after
        setInitialData(commentsInfoItem.serviceId, commentsInfoItem.url, "")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_comments, container, false)
    }

    override fun onDestroyView() {
        disposables.clear()
        super.onDestroyView()
    }

    override val listHeaderSupplier: Supplier<View>
        get() = Supplier {
            val binding = CommentRepliesHeaderBinding.inflate(requireActivity().layoutInflater, itemsList, false)
            val item = commentsInfoItem

            // load the author avatar
            loadAvatar(item!!.uploaderAvatars).into(binding.authorAvatar)
            binding.authorAvatar.visibility = if (shouldLoadImages()) View.VISIBLE else View.GONE

            // setup author name and comment date
            binding.authorName.text = item.uploaderName
            binding.uploadDate.text = relativeTimeOrTextual(
                context, item.uploadDate, item.textualUploadDate?:"")
            binding.authorTouchArea.setOnClickListener {
                openCommentAuthorIfPresent(requireActivity(), item)
            }

            // setup like count, hearted and pinned
            binding.thumbsUpCount.text = likeCount(requireContext(), item.likeCount)
            // for heartImage goneMarginEnd was used, but there is no way to tell ConstraintLayout
            // not to use a different margin only when both the next two views are gone
            (binding.thumbsUpCount.layoutParams as ConstraintLayout.LayoutParams).marginEnd =
                dpToPx((if (item.isHeartedByUploader || item.isPinned) 8 else 16), requireContext())
            binding.heartImage.visibility = if (item.isHeartedByUploader) View.VISIBLE else View.GONE
            binding.pinnedImage.visibility = if (item.isPinned) View.VISIBLE else View.GONE

            // setup comment content
            fromDescription(binding.commentContent, item.commentText, HtmlCompat.FROM_HTML_MODE_LEGACY, getServiceById(item.serviceId),
                item.url, disposables, null)
            binding.root
        }

    override fun writeTo(objectsToSave: Queue<Any>?) {
        super.writeTo(objectsToSave)
        objectsToSave!!.add(commentsInfoItem)
    }

    @Throws(Exception::class)
    override fun readFrom(savedObjects: Queue<Any>) {
        super.readFrom(savedObjects)
        commentsInfoItem = savedObjects.poll() as CommentsInfoItem
    }

    override fun loadResult(forceLoad: Boolean): Single<CommentRepliesInfo> {
        return Single.fromCallable {
            // the reply count string will be shown as the activity title
            CommentRepliesInfo(commentsInfoItem!!, replyCount(requireContext(), commentsInfoItem!!.replyCount))
        }
    }

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<CommentsInfoItem>> {
        // commentsInfoItem.getUrl() should contain the url of the original
        // ListInfo<CommentsInfoItem>, which should be the stream url
        return getMoreCommentItems(serviceId, commentsInfoItem!!.url, currentNextPage)
    }

    override val itemViewMode: ItemViewMode
        get() = ItemViewMode.LIST

    class CommentRepliesInfo(comment: CommentsInfoItem, name: String?)
        : ListInfo<CommentsInfoItem>(comment.serviceId, ListLinkHandler("", "", "", emptyList(), ""), name?:"") {
        /**
         * This class is used to wrap the comment replies page into a ListInfo object.
         *
         * @param comment the comment from which to get replies
         * @param name will be shown as the fragment title
         */
        init {
            nextPage = comment.replies
            relatedItems = emptyList() // since it must be non-null
        }
    }

    companion object {
        val TAG: String = CommentRepliesFragment::class.java.simpleName
    }
}
