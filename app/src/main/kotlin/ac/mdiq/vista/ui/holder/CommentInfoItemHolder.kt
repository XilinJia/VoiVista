package ac.mdiq.vista.ui.holder

import android.annotation.SuppressLint
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import ac.mdiq.vista.R
import ac.mdiq.vista.extractor.InfoItem
import ac.mdiq.vista.extractor.comments.CommentsInfoItem
import ac.mdiq.vista.ui.util.InfoItemBuilder
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.util.DeviceUtils.dpToPx
import ac.mdiq.vista.util.DeviceUtils.isTv
import ac.mdiq.vista.util.Localization.concatenateStrings
import ac.mdiq.vista.util.Localization.likeCount
import ac.mdiq.vista.util.Localization.relativeTimeOrTextual
import ac.mdiq.vista.util.Localization.replyCount
import ac.mdiq.vista.ui.util.NavigationHelper.openCommentAuthorIfPresent
import ac.mdiq.vista.ui.util.NavigationHelper.openCommentRepliesFragment
import ac.mdiq.vista.util.ServiceHelper.getServiceById
import ac.mdiq.vista.ui.util.ShareUtils.copyToClipboard
import ac.mdiq.vista.util.image.ImageStrategy.shouldLoadImages
import ac.mdiq.vista.util.image.PicassoHelper.loadAvatar
import ac.mdiq.vista.ui.util.TextEllipsizer
import ac.mdiq.vista.ui.gesture.TouchUtils

class CommentInfoItemHolder(infoItemBuilder: InfoItemBuilder, parent: ViewGroup?)
    : InfoItemHolder(infoItemBuilder, R.layout.list_comment_item, parent) {

    private val commentHorizontalPadding = infoItemBuilder.context
        .resources.getDimension(R.dimen.comments_horizontal_padding).toInt()
    private val commentVerticalPadding = infoItemBuilder.context
        .resources.getDimension(R.dimen.comments_vertical_padding).toInt()

    private val itemRoot: RelativeLayout = itemView.findViewById(R.id.itemRoot)
    private val itemThumbnailView: ImageView = itemView.findViewById(R.id.itemThumbnailView)
    private val itemContentView: TextView = itemView.findViewById(R.id.itemCommentContentView)
    private val itemThumbsUpView: ImageView = itemView.findViewById(R.id.detail_thumbs_up_img_view)
    private val itemLikesCountView: TextView = itemView.findViewById(R.id.detail_thumbs_up_count_view)
    private val itemTitleView: TextView = itemView.findViewById(R.id.itemTitleView)
    private val itemHeartView: ImageView = itemView.findViewById(R.id.detail_heart_image_view)
    private val itemPinnedView: ImageView = itemView.findViewById(R.id.detail_pinned_view)
    private val repliesButton: Button = itemView.findViewById(R.id.replies_button)

    private val textEllipsizer: TextEllipsizer = TextEllipsizer(itemContentView, COMMENT_DEFAULT_LINES, null)

    init {
        textEllipsizer.setStateChangeListener { isEllipsized: Boolean ->
            if (java.lang.Boolean.TRUE == isEllipsized) denyLinkFocus()
            else determineMovementMethod()
        }
    }

    override fun updateFromItem(infoItem: InfoItem, historyRecordManager: HistoryRecordManager?) {
        if (infoItem !is CommentsInfoItem) return

        // load the author avatar
        loadAvatar(infoItem.uploaderAvatars).into(itemThumbnailView)
        if (shouldLoadImages()) {
            itemThumbnailView.visibility = View.VISIBLE
            itemRoot.setPadding(commentVerticalPadding, commentVerticalPadding, commentVerticalPadding, commentVerticalPadding)
        } else {
            itemThumbnailView.visibility = View.GONE
            itemRoot.setPadding(commentHorizontalPadding, commentVerticalPadding, commentHorizontalPadding, commentVerticalPadding)
        }
        itemThumbnailView.setOnClickListener { openCommentAuthor(infoItem) }

        // setup the top row, with pinned icon, author name and comment date
        itemPinnedView.visibility = if (infoItem.isPinned) View.VISIBLE else View.GONE
        itemTitleView.text = concatenateStrings(infoItem.uploaderName,
            relativeTimeOrTextual(itemBuilder.context, infoItem.uploadDate, infoItem.textualUploadDate ?: ""))

        // setup bottom row, with likes, heart and replies button
        itemLikesCountView.text = likeCount(itemBuilder.context, infoItem.likeCount)

        itemHeartView.visibility = if (infoItem.isHeartedByUploader) View.VISIBLE else View.GONE

        val hasReplies = infoItem.replies != null
        repliesButton.setOnClickListener(if (hasReplies) View.OnClickListener { openCommentReplies(infoItem) } else null)
        repliesButton.visibility = if (hasReplies) View.VISIBLE else View.GONE
        repliesButton.text = if (hasReplies) replyCount(itemBuilder.context, infoItem.replyCount) else ""

        (itemThumbsUpView.layoutParams as RelativeLayout.LayoutParams).topMargin = if (hasReplies) 0 else dpToPx(6, itemBuilder.context)

        // setup comment content and click listeners to expand/ellipsize it
        textEllipsizer.setStreamingService(getServiceById(infoItem.serviceId))
        textEllipsizer.setStreamUrl(infoItem.url)
        textEllipsizer.setContent(infoItem.commentText)
        textEllipsizer.ellipsize()

        itemContentView.setOnTouchListener(CommentTextOnTouchListener.INSTANCE)

        itemView.setOnClickListener {
            textEllipsizer.toggle()
            itemBuilder.onCommentsSelectedListener?.selected(infoItem)
        }

        itemView.setOnLongClickListener {
            if (isTv(itemBuilder.context)) openCommentAuthor(infoItem)
            else {
                val text = itemContentView.text
                if (text != null) copyToClipboard(itemBuilder.context, text.toString())
            }
            true
        }
    }

    private fun openCommentAuthor(item: CommentsInfoItem) {
        openCommentAuthorIfPresent((itemBuilder.context as FragmentActivity), item)
    }

    private fun openCommentReplies(item: CommentsInfoItem) {
        openCommentRepliesFragment((itemBuilder.context as FragmentActivity), item)
    }

    private fun allowLinkFocus() {
        itemContentView.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun denyLinkFocus() {
        itemContentView.movementMethod = null
    }

    private fun shouldFocusLinks(): Boolean {
        if (itemView.isInTouchMode) return false
        val urls = itemContentView.urls
        return urls != null && urls.isNotEmpty()
    }

    private fun determineMovementMethod() {
        if (shouldFocusLinks()) allowLinkFocus()
        else denyLinkFocus()
    }

    class CommentTextOnTouchListener : OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (v !is TextView) return false
            val text = v.text
            if (text is Spanned) {
                val action = event.action

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                    val offset = TouchUtils.getOffsetForHorizontalLine(v, event)
                    val links = text.getSpans(offset, offset, ClickableSpan::class.java)

                    if (links.isNotEmpty()) {
                        if (action == MotionEvent.ACTION_UP) links[0].onClick(v)
                        // we handle events that intersect links, so return true
                        return true
                    }
                }
            }
            return false
        }

        companion object {
            @JvmField
            val INSTANCE: CommentTextOnTouchListener = CommentTextOnTouchListener()
        }
    }

    companion object {
        private const val COMMENT_DEFAULT_LINES = 2
    }
}
