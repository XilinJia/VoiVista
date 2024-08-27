/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
 * ExtractorHelper.kt is part of Vista
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package ac.mdiq.vista.util

import ac.mdiq.vista.R
import ac.mdiq.vista.extractor.*
import ac.mdiq.vista.extractor.InfoItem.InfoType
import ac.mdiq.vista.extractor.ListExtractor.InfoItemsPage
import ac.mdiq.vista.extractor.channel.ChannelInfo
import ac.mdiq.vista.extractor.channel.tabs.ChannelTabInfo
import ac.mdiq.vista.extractor.comments.CommentsInfo
import ac.mdiq.vista.extractor.comments.CommentsInfoItem
import ac.mdiq.vista.extractor.kiosk.KioskInfo
import ac.mdiq.vista.extractor.linkhandler.ListLinkHandler
import ac.mdiq.vista.extractor.playlist.PlaylistInfo
import ac.mdiq.vista.extractor.search.SearchInfo
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.ui.util.TextLinkifier.SET_LINK_MOVEMENT_METHOD
import ac.mdiq.vista.ui.util.TextLinkifier.fromHtml
import android.view.View
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.*
import java.util.function.Consumer

object ExtractorHelper {
    private val TAG: String = ExtractorHelper::class.java.simpleName
    private val CACHE: InfoCache = InfoCache.instance

    private fun checkServiceId(serviceId: Int) {
        require(serviceId != NO_SERVICE_ID) { "serviceId is NO_SERVICE_ID" }
    }

    fun searchFor(serviceId: Int, searchString: String?, contentFilter: List<String?>?, sortFilter: String?): Single<SearchInfo> {
        checkServiceId(serviceId)
        return Single.fromCallable {
            SearchInfo.getInfo(Vista.getService(serviceId), Vista.getService(serviceId).getSearchQHFactory().fromQuery(searchString?:"", contentFilter?.filterNotNull()?: listOf(), sortFilter?:""))
        }
    }

    fun getMoreSearchItems(serviceId: Int, searchString: String?, contentFilter: List<String?>?, sortFilter: String?, page: Page?): Single<InfoItemsPage<InfoItem>> {
        checkServiceId(serviceId)
        return Single.fromCallable {
            SearchInfo.getMoreItems(Vista.getService(serviceId), Vista.getService(serviceId).getSearchQHFactory().fromQuery(searchString?:"", contentFilter?.filterNotNull()?: listOf(), sortFilter?:""), page)
        }
    }

    @JvmStatic
    fun suggestionsFor(serviceId: Int, query: String): Single<List<String>> {
        checkServiceId(serviceId)
        return Single.fromCallable {
            val extractor = Vista.getService(serviceId).getSuggestionExtractor()
            extractor?.suggestionList(query) ?: emptyList()
        }
    }

    @JvmStatic
    fun getStreamInfo(serviceId: Int, url: String, forceLoad: Boolean): Single<StreamInfo> {
        checkServiceId(serviceId)
        return checkCache(forceLoad, serviceId, url, InfoType.STREAM, Single.fromCallable { StreamInfo.getInfo(Vista.getService(serviceId), url) })
    }

    @JvmStatic
    fun getChannelInfo(serviceId: Int, url: String, forceLoad: Boolean): Single<ChannelInfo> {
        checkServiceId(serviceId)
        return checkCache(forceLoad, serviceId, url, InfoType.CHANNEL, Single.fromCallable { ChannelInfo.getInfo(Vista.getService(serviceId), url) })
    }

    @JvmStatic
    fun getChannelTab(serviceId: Int, listLinkHandler: ListLinkHandler, forceLoad: Boolean): Single<ChannelTabInfo> {
        checkServiceId(serviceId)
        return checkCache(forceLoad, serviceId, listLinkHandler.url, InfoType.CHANNEL,
            Single.fromCallable { ChannelTabInfo.getInfo(Vista.getService(serviceId), listLinkHandler) })
    }

    @JvmStatic
    fun getMoreChannelTabItems(serviceId: Int, listLinkHandler: ListLinkHandler, nextPage: Page?): Single<InfoItemsPage<InfoItem>> {
        checkServiceId(serviceId)
        return Single.fromCallable { ChannelTabInfo.getMoreItems(Vista.getService(serviceId), listLinkHandler, nextPage)
        }
    }

    @JvmStatic
    fun getCommentsInfo(serviceId: Int, url: String, forceLoad: Boolean): Single<CommentsInfo> {
        checkServiceId(serviceId)
        // TODO: deal with the !!
        return checkCache(forceLoad, serviceId, url, InfoType.COMMENT, Single.fromCallable { CommentsInfo.getInfo(Vista.getService(serviceId), url)!! })
    }

    @JvmStatic
    fun getMoreCommentItems(serviceId: Int, info: CommentsInfo, nextPage: Page?): Single<InfoItemsPage<CommentsInfoItem>> {
        checkServiceId(serviceId)
        return Single.fromCallable { CommentsInfo.getMoreItems(Vista.getService(serviceId), info, nextPage) }
    }

    @JvmStatic
    fun getMoreCommentItems(serviceId: Int, url: String, nextPage: Page?): Single<InfoItemsPage<CommentsInfoItem>> {
        checkServiceId(serviceId)
        return Single.fromCallable { CommentsInfo.getMoreItems(Vista.getService(serviceId), url, nextPage) }
    }

    @JvmStatic
    fun getPlaylistInfo(serviceId: Int, url: String, forceLoad: Boolean): Single<PlaylistInfo> {
        checkServiceId(serviceId)
        // TODO: deal with the !!
        return checkCache(forceLoad, serviceId, url, InfoType.PLAYLIST, Single.fromCallable { PlaylistInfo.getInfo(Vista.getService(serviceId), url)!! })
    }

    @JvmStatic
    fun getMorePlaylistItems(serviceId: Int, url: String, nextPage: Page?): Single<InfoItemsPage<StreamInfoItem>> {
        checkServiceId(serviceId)
        // TODO: deal with the !!
        return Single.fromCallable { PlaylistInfo.getMoreItems(Vista.getService(serviceId), url, nextPage)!! }
    }

    @JvmStatic
    fun getKioskInfo(serviceId: Int, url: String, forceLoad: Boolean): Single<KioskInfo> {
        return checkCache(forceLoad, serviceId, url, InfoType.PLAYLIST, Single.fromCallable { KioskInfo.getInfo(Vista.getService(serviceId), url) })
    }

    @JvmStatic
    fun getMoreKioskItems(serviceId: Int, url: String, nextPage: Page?): Single<InfoItemsPage<StreamInfoItem>> {
        // TODO: deal with the !!
        return Single.fromCallable { KioskInfo.getMoreItems(Vista.getService(serviceId), url, nextPage)!! }
    }

    /**
     * Check if we can load it from the cache (forceLoad parameter), if we can't,
     * load from the network (Single loadFromNetwork)
     * and put the results in the cache.
     *
     * @param <I>             the item type's class that extends [Info]
     * @param forceLoad       whether to force loading from the network instead of from the cache
     * @param serviceId       the service to load from
     * @param url             the URL to load
     * @param infoType        the [InfoItem.InfoType] of the item
     * @param loadFromNetwork the [Single] to load the item from the network
     * @return a [Single] that loads the item
    </I> */
    private fun <I : Info> checkCache(forceLoad: Boolean, serviceId: Int, url: String, infoType: InfoType, loadFromNetwork: Single<I>): Single<I> {
        checkServiceId(serviceId)
        val actualLoadFromNetwork = loadFromNetwork.doOnSuccess { info: I -> CACHE.putInfo(serviceId, url, info, infoType) }

        val load: Single<I>
        if (forceLoad) {
            CACHE.removeInfo(serviceId, url, infoType)
            load = actualLoadFromNetwork
        }
        // Take the first valid
        else load = Maybe.concat(loadFromCache(serviceId, url, infoType), actualLoadFromNetwork.toMaybe()).firstElement().toSingle()
        return load
    }

    /**
     * Default implementation uses the [InfoCache] to get cached results.
     *
     * @param <I>       the item type's class that extends [Info]
     * @param serviceId the service to load from
     * @param url       the URL to load
     * @param infoType  the [InfoItem.InfoType] of the item
     * @return a [Single] that loads the item
    </I> */
    private fun <I : Info> loadFromCache(serviceId: Int, url: String, infoType: InfoType): Maybe<I> {
        checkServiceId(serviceId)
        return Maybe.defer {
            val info = CACHE.getFromKey(serviceId, url, infoType) as? I
            Logd(TAG, "loadFromCache() called, info > $info")
            // Only return info if it's not null (it is cached)
            if (info != null) Maybe.just(info)
            else Maybe.empty()
        }
    }

    @JvmStatic
    fun isCached(serviceId: Int, url: String, infoType: InfoType): Boolean {
        return null != loadFromCache<Info>(serviceId, url, infoType).blockingGet()
    }

    /**
     * Formats the text contained in the meta info list as HTML and puts it into the text view,
     * while also making the separator visible. If the list is null or empty, or the user chose not
     * to see meta information, both the text view and the separator are hidden
     *
     * @param metaInfos         a list of meta information, can be null or empty
     * @param metaInfoTextView  the text view in which to show the formatted HTML
     * @param metaInfoSeparator another view to be shown or hidden accordingly to the text view
     * @param disposables       disposables created by the method are added here and their lifecycle
     * should be handled by the calling class
     */
    fun showMetaInfoInTextView(metaInfos: List<MetaInfo>?, metaInfoTextView: TextView, metaInfoSeparator: View, disposables: CompositeDisposable?) {
        val context = metaInfoTextView.context
        if (metaInfos.isNullOrEmpty() || !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.show_meta_info_key), true)) {
            metaInfoTextView.visibility = View.GONE
            metaInfoSeparator.visibility = View.GONE
        } else {
            val stringBuilder = StringBuilder()
            for (metaInfo in metaInfos) {
                if (metaInfo.title.isNotEmpty()) stringBuilder.append("<b>").append(metaInfo.title).append("</b>").append(Localization.DOT_SEPARATOR)

                var content = metaInfo.content?.content?.trim { it <= ' ' } ?:""
                if (content.endsWith(".")) { content = content.substring(0, content.length - 1) /* remove . at end */ }
                stringBuilder.append(content)

                for (i in metaInfo.getUrls().indices) {
                    if (i == 0) stringBuilder.append(Localization.DOT_SEPARATOR)
                    else stringBuilder.append("<br/><br/>")

                    stringBuilder.append("<a href=\"").append(metaInfo.getUrls()[i]).append("\">")
                        .append(capitalizeIfAllUppercase(metaInfo.getUrlTexts()[i].trim { it <= ' ' }))
                        .append("</a>")
                }
            }

            metaInfoSeparator.visibility = View.VISIBLE
            fromHtml(metaInfoTextView, stringBuilder.toString(), HtmlCompat.FROM_HTML_SEPARATOR_LINE_BREAK_HEADING, null, null, disposables!!,
                SET_LINK_MOVEMENT_METHOD as? Consumer<TextView?>)
        }
    }

    private fun capitalizeIfAllUppercase(text: String): String {
        for (element in text) {
            // there is at least a lowercase letter -> not all uppercase
            if (Character.isLowerCase(element)) return text
        }
        return if (text.isEmpty()) text
        else text.substring(0, 1).uppercase(Locale.getDefault()) + text.substring(1).lowercase(Locale.getDefault())
    }
}
