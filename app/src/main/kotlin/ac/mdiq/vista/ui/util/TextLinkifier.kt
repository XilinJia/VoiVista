package ac.mdiq.vista.ui.util

import ac.mdiq.vista.R
import ac.mdiq.vista.util.error.ErrorPanelHelper.Companion.getExceptionDescription
import ac.mdiq.vista.extractor.ServiceList
import ac.mdiq.vista.extractor.StreamingService
import ac.mdiq.vista.extractor.StreamingService.LinkType
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.extractor.exceptions.ParsingException
import ac.mdiq.vista.extractor.stream.Description
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.player.playqueue.SinglePlayQueue
import ac.mdiq.vista.ui.gesture.TouchUtils
import ac.mdiq.vista.ui.util.TextLinkifier.InternalUrlsHandler.handleUrlDescriptionTimestamp
import ac.mdiq.vista.ui.util.TextLinkifier.InternalUrlsHandler.playOnPopup
import ac.mdiq.vista.util.ExtractorHelper
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import io.noties.markwon.Markwon
import io.noties.markwon.linkify.LinkifyPlugin
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.function.Consumer
import java.util.regex.Matcher
import java.util.regex.Pattern

object TextLinkifier {
    val TAG: String = TextLinkifier::class.java.simpleName

    // Looks for hashtags with characters from any language (\p{L}), numbers, or underscores
    private val HASHTAGS_PATTERN: Pattern = Pattern.compile("(#[\\p{L}0-9_]+)")

    @JvmField
    val SET_LINK_MOVEMENT_METHOD: Consumer<TextView> = Consumer { v: TextView -> v.movementMethod = LongPressLinkMovementMethod.instance }

    /**
     * Create links for contents with an [Description] in the various possible formats.
     *
     * This will call one of these three functions based on the format: [.fromHtml],
     * [.fromMarkdown] or [.fromPlainText].
     *
     * @param textView           the TextView to set the htmlBlock linked
     * @param description        the htmlBlock to be linked
     * @param htmlCompatFlag     the int flag to be set if [HtmlCompat.fromHtml]
     * will be called (not used for formats different than HTML)
     * @param relatedInfoService if given, handle hashtags to search for the term in the correct
     * service
     * @param relatedStreamUrl   if given, used alongside `relatedInfoService` to handle
     * timestamps to open the stream in the popup player at the specific
     * time
     * @param disposables        disposables created by the method are added here and their
     * lifecycle should be handled by the calling class
     * @param onCompletion       will be run when setting text to the textView completes; use [                           ][.SET_LINK_MOVEMENT_METHOD] to make links clickable and focusable
     */
    fun fromDescription(textView: TextView, description: Description, htmlCompatFlag: Int, relatedInfoService: StreamingService?,
                        relatedStreamUrl: String?, disposables: CompositeDisposable, onCompletion: Consumer<TextView?>?) {
        when (description.type) {
            Description.HTML -> fromHtml(textView, description.content, htmlCompatFlag, relatedInfoService, relatedStreamUrl, disposables, onCompletion)
            Description.MARKDOWN -> fromMarkdown(textView, description.content, relatedInfoService, relatedStreamUrl, disposables, onCompletion)
            Description.PLAIN_TEXT -> fromPlainText(textView, description.content, relatedInfoService, relatedStreamUrl, disposables, onCompletion)
            else -> fromPlainText(textView, description.content, relatedInfoService, relatedStreamUrl, disposables, onCompletion)
        }
    }

    /**
     * Create links for contents with an HTML description.
     *
     * This method will call [.changeLinkIntents] after having linked the URLs with
     * [HtmlCompat.fromHtml].
     *
     * @param textView           the [TextView] to set the the HTML string block linked
     * @param htmlBlock          the HTML string block to be linked
     * @param htmlCompatFlag     the int flag to be set when [HtmlCompat.fromHtml] will be called
     * @param relatedInfoService if given, handle hashtags to search for the term in the correct
     * service
     * @param relatedStreamUrl   if given, used alongside `relatedInfoService` to handle
     * timestamps to open the stream in the popup player at the specific
     * time
     * @param disposables        disposables created by the method are added here and their
     * lifecycle should be handled by the calling class
     * @param onCompletion       will be run when setting text to the textView completes; use [                           ][.SET_LINK_MOVEMENT_METHOD] to make links clickable and focusable
     */
    fun fromHtml(textView: TextView, htmlBlock: String, htmlCompatFlag: Int, relatedInfoService: StreamingService?, relatedStreamUrl: String?,
                 disposables: CompositeDisposable, onCompletion: Consumer<TextView?>?) {
        changeLinkIntents(textView, HtmlCompat.fromHtml(htmlBlock, htmlCompatFlag), relatedInfoService, relatedStreamUrl, disposables, onCompletion)
    }

    /**
     * Create links for contents with a plain text description.
     *
     * This method will call [.changeLinkIntents] after having linked the URLs with
     * [TextView.setAutoLinkMask] and
     * [TextView.setText].
     *
     * @param textView           the [TextView] to set the plain text block linked
     * @param plainTextBlock     the block of plain text to be linked
     * @param relatedInfoService if given, handle hashtags to search for the term in the correct
     * service
     * @param relatedStreamUrl   if given, used alongside `relatedInfoService` to handle
     * timestamps to open the stream in the popup player at the specific
     * time
     * @param disposables        disposables created by the method are added here and their
     * lifecycle should be handled by the calling class
     * @param onCompletion       will be run when setting text to the textView completes; use [                           ][.SET_LINK_MOVEMENT_METHOD] to make links clickable and focusable
     */
    fun fromPlainText(textView: TextView, plainTextBlock: String, relatedInfoService: StreamingService?, relatedStreamUrl: String?,
                      disposables: CompositeDisposable, onCompletion: Consumer<TextView?>?) {
        textView.autoLinkMask = Linkify.WEB_URLS
        textView.setText(plainTextBlock, TextView.BufferType.SPANNABLE)
        changeLinkIntents(textView, textView.text, relatedInfoService, relatedStreamUrl, disposables, onCompletion)
    }

    /**
     * Create links for contents with a markdown description.
     *
     * This method will call [.changeLinkIntents] after creating a [Markwon] object and using
     * [Markwon.setMarkdown].
     *
     * @param textView           the [TextView] to set the plain text block linked
     * @param markdownBlock      the block of markdown text to be linked
     * @param relatedInfoService if given, handle hashtags to search for the term in the correct
     * service
     * @param relatedStreamUrl   if given, used alongside `relatedInfoService` to handle
     * timestamps to open the stream in the popup player at the specific
     * time
     * @param disposables        disposables created by the method are added here and their
     * lifecycle should be handled by the calling class
     * @param onCompletion       will be run when setting text to the textView completes; use [                           ][.SET_LINK_MOVEMENT_METHOD] to make links clickable and focusable
     */
    private fun fromMarkdown(textView: TextView, markdownBlock: String, relatedInfoService: StreamingService?, relatedStreamUrl: String?,
                     disposables: CompositeDisposable, onCompletion: Consumer<TextView?>?) {
        val markwon = Markwon.builder(textView.context).usePlugin(LinkifyPlugin.create()).build()
        changeLinkIntents(textView, markwon.toMarkdown(markdownBlock), relatedInfoService, relatedStreamUrl, disposables, onCompletion)
    }

    /**
     * Change links generated by libraries in the description of a content to a custom link action
     * and add click listeners on timestamps in this description.
     *
     * Instead of using an [android.content.Intent.ACTION_VIEW] intent in the description of
     * a content, this method will parse the [CharSequence] and replace all current web links
     * with [ShareUtils.openUrlInBrowser].
     *
     * This method will also add click listeners on timestamps in this description, which will play
     * the content in the popup player at the time indicated in the timestamp, by using
     * [TextLinkifier.addClickListenersOnTimestamps] method and click listeners on hashtags, by
     * using [TextLinkifier.addClickListenersOnHashtags], which will open a search on the current service with the hashtag.
     *
     * This method is required in order to intercept links and e.g. show a confirmation dialog
     * before opening a web link.
     *
     * @param textView           the [TextView] to which the converted [CharSequence]
     * will be applied
     * @param chars              the [CharSequence] to be parsed
     * @param relatedInfoService if given, handle hashtags to search for the term in the correct
     * service
     * @param relatedStreamUrl   if given, used alongside `relatedInfoService` to handle
     * timestamps to open the stream in the popup player at the specific
     * time
     * @param disposables        disposables created by the method are added here and their
     * lifecycle should be handled by the calling class
     * @param onCompletion       will be run when setting text to the textView completes; use [                           ][.SET_LINK_MOVEMENT_METHOD] to make links clickable and focusable
     */
    private fun changeLinkIntents(textView: TextView, chars: CharSequence, relatedInfoService: StreamingService?, relatedStreamUrl: String?,
                                  disposables: CompositeDisposable, onCompletion: Consumer<TextView?>?) {
        disposables.add(Single.fromCallable {
            val context = textView.context
            // add custom click actions on web links
            val textBlockLinked = SpannableStringBuilder(chars)
            val urls = textBlockLinked.getSpans(0, chars.length, URLSpan::class.java)

            for (span in urls) {
                val url = span.url
                val longPressClickableSpan: LongPressClickableSpan = UrlLongPressClickableSpan(context, disposables, url)
                textBlockLinked.setSpan(longPressClickableSpan, textBlockLinked.getSpanStart(span), textBlockLinked.getSpanEnd(span), textBlockLinked.getSpanFlags(span))
                textBlockLinked.removeSpan(span)
            }

            // add click actions on plain text timestamps only for description of contents,
            // unneeded for meta-info or other TextViews
            if (relatedInfoService != null) {
                if (relatedStreamUrl != null) addClickListenersOnTimestamps(context, textBlockLinked, relatedInfoService, relatedStreamUrl, disposables)
                addClickListenersOnHashtags(context, textBlockLinked, relatedInfoService)
            }
            textBlockLinked
        }.subscribeOn(Schedulers.computation())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ textBlockLinked: SpannableStringBuilder? -> setTextViewCharSequence(textView, textBlockLinked, onCompletion) },
                { throwable: Throwable? ->
                    Log.e(TAG, "Unable to linkify text", throwable)
                    // this should never happen, but if it does, just fallback to it
                    setTextViewCharSequence(textView, chars, onCompletion)
                }))
    }

    /**
     * Add click listeners which opens a search on hashtags in a plain text.
     *
     * This method finds all timestamps in the [SpannableStringBuilder] of the description
     * using a regular expression, adds for each a [LongPressClickableSpan] which opens
     * [NavigationHelper.openSearch] and makes a search on the hashtag,
     * in the service of the content when pressed, and copy the hashtag to clipboard when
     * long-pressed, if allowed by the caller method (parameter `addLongClickCopyListener`).
     *
     *
     * @param context              the [Context] to use
     * @param spannableDescription the [SpannableStringBuilder] with the text of the
     * content description
     * @param relatedInfoService   used to search for the term in the correct service
     */
    private fun addClickListenersOnHashtags(context: Context, spannableDescription: SpannableStringBuilder, relatedInfoService: StreamingService) {
        val descriptionText = spannableDescription.toString()
        val hashtagsMatches = HASHTAGS_PATTERN.matcher(descriptionText)

        while (hashtagsMatches.find()) {
            val hashtagStart = hashtagsMatches.start(1)
            val hashtagEnd = hashtagsMatches.end(1)
            val parsedHashtag = descriptionText.substring(hashtagStart, hashtagEnd)

            // Don't add a LongPressClickableSpan if there is already one, which should be a part
            // of an URL, already parsed before
            if (spannableDescription.getSpans(hashtagStart,
                        hashtagEnd,
                        LongPressClickableSpan::class.java).isEmpty()) {
                val serviceId = relatedInfoService.serviceId
                spannableDescription.setSpan(HashtagLongPressClickableSpan(context, parsedHashtag, serviceId), hashtagStart, hashtagEnd, 0)
            }
        }
    }

    /**
     * Add click listeners which opens the popup player on timestamps in a plain text.
     *
     * This method finds all timestamps in the [SpannableStringBuilder] of the description
     * using a regular expression, adds for each a [LongPressClickableSpan] which opens the
     * popup player at the time indicated in the timestamps and copy the timestamp in clipboard
     * when long-pressed.
     *
     * @param context              the [Context] to use
     * @param spannableDescription the [SpannableStringBuilder] with the text of the
     * content description
     * @param relatedInfoService   the service of the `relatedStreamUrl`
     * @param relatedStreamUrl     what to open in the popup player when timestamps are clicked
     * @param disposables          disposables created by the method are added here and their
     * lifecycle should be handled by the calling class
     */
    private fun addClickListenersOnTimestamps(context: Context, spannableDescription: SpannableStringBuilder, relatedInfoService: StreamingService,
                                              relatedStreamUrl: String, disposables: CompositeDisposable) {
        val descriptionText = spannableDescription.toString()
        val timestampsMatches = TimestampExtractor.TIMESTAMPS_PATTERN.matcher(descriptionText)

        while (timestampsMatches.find()) {
            val timestampMatchDTO = TimestampExtractor.getTimestampFromMatcher(timestampsMatches, descriptionText) ?: continue
            spannableDescription.setSpan(
                TimestampLongPressClickableSpan(context, descriptionText, disposables, relatedInfoService, relatedStreamUrl, timestampMatchDTO),
                timestampMatchDTO.timestampStart(), timestampMatchDTO.timestampEnd(), 0)
        }
    }

    private fun setTextViewCharSequence(textView: TextView, charSequence: CharSequence?, onCompletion: Consumer<TextView?>?) {
        textView.text = charSequence
        textView.visibility = View.VISIBLE
        onCompletion?.accept(textView)
    }

    internal class HashtagLongPressClickableSpan(
            private val context: Context,
            private val parsedHashtag: String,
            private val relatedInfoServiceId: Int)
        : LongPressClickableSpan() {
        override fun onClick(view: View) {
            NavigationHelper.openSearch(context, relatedInfoServiceId, parsedHashtag)
        }
        override fun onLongClick(view: View) {
            ShareUtils.copyToClipboard(context, parsedHashtag)
        }
    }

    // Class adapted from https://stackoverflow.com/a/31786969
    class LongPressLinkMovementMethod : LinkMovementMethod() {
        private var longClickHandler: Handler? = null
        private var isLongPressed = false

        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
            val action = event.action
            if (action == MotionEvent.ACTION_CANCEL && longClickHandler != null) longClickHandler!!.removeCallbacksAndMessages(null)
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
                val offset = TouchUtils.getOffsetForHorizontalLine(widget, event)
                val link = buffer.getSpans(offset, offset, LongPressClickableSpan::class.java)
                if (link.isNotEmpty()) {
                    if (action == MotionEvent.ACTION_UP) {
                        if (longClickHandler != null) longClickHandler!!.removeCallbacksAndMessages(null)
                        if (!isLongPressed) link[0].onClick(widget)
                        isLongPressed = false
                    } else {
                        Selection.setSelection(buffer, buffer.getSpanStart(link[0]), buffer.getSpanEnd(link[0]))
                        longClickHandler?.postDelayed({ link[0].onLongClick(widget); isLongPressed = true }, LONG_PRESS_TIME.toLong())
                    }
                    return true
                }
            }
            return super.onTouchEvent(widget, buffer, event)
        }

        companion object {
            private val LONG_PRESS_TIME = ViewConfiguration.getLongPressTimeout()
            var instance: LongPressLinkMovementMethod? = null
                get() {
                    if (field == null) {
                        field = LongPressLinkMovementMethod()
                        field!!.longClickHandler = Handler(Looper.myLooper()!!)
                    }
                    return field
                }
                private set
        }
    }

    internal class TimestampLongPressClickableSpan(private val context: Context,
                                                   private val descriptionText: String,
                                                   private val disposables: CompositeDisposable,
                                                   private val relatedInfoService: StreamingService,
                                                   private val relatedStreamUrl: String,
                                                   private val timestampMatchDTO: TimestampExtractor.TimestampMatchDTO)
        : LongPressClickableSpan() {
        override fun onClick(view: View) {
            playOnPopup(context, relatedStreamUrl, relatedInfoService, timestampMatchDTO.seconds(), disposables)
        }
        override fun onLongClick(view: View) {
            ShareUtils.copyToClipboard(context, getTimestampTextToCopy(relatedInfoService, relatedStreamUrl, descriptionText, timestampMatchDTO))
        }
        companion object {
            private fun getTimestampTextToCopy(relatedInfoService: StreamingService, relatedStreamUrl: String, descriptionText: String,
                                               timestampMatchDTO: TimestampExtractor.TimestampMatchDTO): String {
                // TODO: use extractor methods to get timestamps when this feature will be implemented in it
                return when {
                    relatedInfoService === ServiceList.YouTube -> relatedStreamUrl + "&t=" + timestampMatchDTO.seconds()
                    relatedInfoService === ServiceList.SoundCloud || relatedInfoService === ServiceList.MediaCCC -> relatedStreamUrl + "#t=" + timestampMatchDTO.seconds()
                    relatedInfoService === ServiceList.PeerTube -> relatedStreamUrl + "?start=" + timestampMatchDTO.seconds()
                    // Return timestamp text for other services
                    else -> descriptionText.subSequence(timestampMatchDTO.timestampStart(), timestampMatchDTO.timestampEnd()).toString()
                }
            }
        }
    }

    internal class UrlLongPressClickableSpan(private val context: Context, private val disposables: CompositeDisposable, private val url: String)
        : LongPressClickableSpan() {
        override fun onClick(view: View) {
            if (!handleUrlDescriptionTimestamp(disposables, context, url)) ShareUtils.openUrlInApp(context, url)
        }
        override fun onLongClick(view: View) {
            ShareUtils.copyToClipboard(context, url)
        }
    }

    abstract class LongPressClickableSpan : ClickableSpan() {
        abstract fun onLongClick(view: View)
    }

    object InternalUrlsHandler {
        private val TAG: String = InternalUrlsHandler::class.java.simpleName
//        private val DEBUG = MainActivity.DEBUG
        private val AMPERSAND_TIMESTAMP_PATTERN: Pattern = Pattern.compile("(.*)&t=(\\d+)")
        private val HASHTAG_TIMESTAMP_PATTERN: Pattern = Pattern.compile("(.*)#timestamp=(\\d+)")

        /**
         * Handle a YouTube timestamp comment URL in Vista.
         *
         * This method will check if the provided url is a YouTube comment description URL (`https://www.youtube.com/watch?v=`video_id`#timestamp=`time_in_seconds). If yes, the
         * popup player will be opened when the user will click on the timestamp in the comment,
         * at the time and for the video indicated in the timestamp.
         *
         * @param disposables a field of the Activity/Fragment class that calls this method
         * @param context     the context to use
         * @param url         the URL to check if it can be handled
         * @return true if the URL can be handled by Vista, false if it cannot
         */
        fun handleUrlCommentsTimestamp(disposables: CompositeDisposable, context: Context, url: String): Boolean {
            return handleUrl(context, url, HASHTAG_TIMESTAMP_PATTERN, disposables)
        }

        /**
         * Handle a YouTube timestamp description URL in Vista.
         *
         * This method will check if the provided url is a YouTube timestamp description URL (`https://www.youtube.com/watch?v=`video_id`&t=`time_in_seconds). If yes, the popup
         * player will be opened when the user will click on the timestamp in the video description,
         * at the time and for the video indicated in the timestamp.
         *
         * @param disposables a field of the Activity/Fragment class that calls this method
         * @param context     the context to use
         * @param url         the URL to check if it can be handled
         * @return true if the URL can be handled by Vista, false if it cannot
         */

        fun handleUrlDescriptionTimestamp(disposables: CompositeDisposable, context: Context, url: String): Boolean {
            return handleUrl(context, url, AMPERSAND_TIMESTAMP_PATTERN, disposables)
        }

        /**
         * Handle an URL in Vista.
         *
         * This method will check if the provided url can be handled in Vista or not. If this is a
         * service URL with a timestamp, the popup player will be opened and true will be returned;
         * else, false will be returned.
         *
         * @param context     the context to use
         * @param url         the URL to check if it can be handled
         * @param pattern     the pattern to use
         * @param disposables a field of the Activity/Fragment class that calls this method
         * @return true if the URL can be handled by Vista, false if it cannot
         */
        private fun handleUrl(context: Context, url: String, pattern: Pattern, disposables: CompositeDisposable): Boolean {
            val matcher = pattern.matcher(url)
            if (!matcher.matches()) return false
            val matchedUrl = matcher.group(1)!!
            val seconds = matcher.group(2)!!.toInt()

            val service: StreamingService
            val linkType: LinkType
            try {
                service = Vista.getServiceByUrl(matchedUrl)
                linkType = service.getLinkTypeByUrl(matchedUrl)
                if (linkType == LinkType.NONE) return false
            } catch (e: ExtractionException) {
                return false
            }
            if (linkType == LinkType.STREAM && seconds != -1) return playOnPopup(context, matchedUrl, service, seconds, disposables)
            else {
                NavigationHelper.openRouterActivity(context, matchedUrl)
                return true
            }
        }

        /**
         * Play a content in the floating player.
         *
         * @param context     the context to be used
         * @param url         the URL of the content
         * @param service     the service of the content
         * @param seconds     the position in seconds at which the floating player will start
         * @param disposables disposables created by the method are added here and their lifecycle
         * should be handled by the calling class
         * @return true if the playback of the content has successfully started or false if not
         */

        fun playOnPopup(context: Context, url: String, service: StreamingService, seconds: Int, disposables: CompositeDisposable): Boolean {
            val factory = service.getStreamLHFactory()
            val cleanUrl: String

            try {
                cleanUrl = factory.getUrl(factory.getId(url))
            } catch (e: ParsingException) {
                return false
            }

            val single = ExtractorHelper.getStreamInfo(service.serviceId, cleanUrl, false)
            disposables.add(single.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ info: StreamInfo? ->
                    val playQueue: PlayQueue = SinglePlayQueue(info, seconds * 1000L)
                    NavigationHelper.playOnPopupPlayer(context, playQueue, false)
                }, { throwable: Throwable? ->
                    Log.e(TAG, "Could not play on popup: $url", throwable)
                    AlertDialog.Builder(context)
                        .setTitle(R.string.player_stream_failure)
                        .setMessage(getExceptionDescription(throwable))
                        .setPositiveButton(R.string.ok, null)
                        .show()
                }))
            return true
        }
    }

    object TimestampExtractor {
        @JvmField
        val TIMESTAMPS_PATTERN: Pattern = Pattern.compile("(?:^|(?!:)\\W)(?:([0-5]?[0-9]):)?([0-5]?[0-9]):([0-5][0-9])(?=$|(?!:)\\W)")

        /**
         * Gets a single timestamp from a matcher.
         *
         * @param timestampMatches the matcher which was created using [.TIMESTAMPS_PATTERN]
         * @param baseText         the text where the pattern was applied to / where the matcher is
         * based upon
         * @return if a match occurred, a [TimestampMatchDTO] filled with information, otherwise
         * `null`.
         */

        fun getTimestampFromMatcher(timestampMatches: Matcher, baseText: String): TimestampMatchDTO? {
            var timestampStart = timestampMatches.start(1)
            if (timestampStart == -1) timestampStart = timestampMatches.start(2)
            val timestampEnd = timestampMatches.end(3)

            val parsedTimestamp = baseText.substring(timestampStart, timestampEnd)
            val timestampParts = parsedTimestamp.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val seconds: Int = when (timestampParts.size) {
                // timestamp format: XX:XX:XX
                3 -> timestampParts[0].toInt() * 3600 + (timestampParts[1].toInt() * 60) + timestampParts[2].toInt()
                // timestamp format: XX:XX
                2 -> (timestampParts[0].toInt() * 60 + timestampParts[1].toInt())
                else -> return null
            }
            return TimestampMatchDTO(timestampStart, timestampEnd, seconds)
        }

        class TimestampMatchDTO(private val timestampStart: Int, private val timestampEnd: Int, private val seconds: Int) {
            fun timestampStart(): Int {
                return timestampStart
            }
            fun timestampEnd(): Int {
                return timestampEnd
            }
            fun seconds(): Int {
                return seconds
            }
        }
    }
}
