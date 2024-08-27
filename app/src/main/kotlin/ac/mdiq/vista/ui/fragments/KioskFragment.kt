package ac.mdiq.vista.ui.fragments

import android.os.Bundle
import android.view.*
import icepick.State
import io.reactivex.rxjava3.core.Single
import ac.mdiq.vista.R
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.extractor.ListExtractor.InfoItemsPage
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.ServiceList
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.extractor.kiosk.KioskInfo
import ac.mdiq.vista.extractor.localization.ContentCountry
import ac.mdiq.vista.extractor.services.media_ccc.extractors.MediaCCCLiveStreamKiosk
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.util.ExtractorHelper.getKioskInfo
import ac.mdiq.vista.util.ExtractorHelper.getMoreKioskItems
import ac.mdiq.vista.util.KioskTranslator.getTranslatedKioskName
import ac.mdiq.vista.util.Localization.getPreferredContentCountry
import ac.mdiq.vista.util.Logd

/**
 * Created by Christian Schabesberger on 23.09.17.
 *
 * Copyright (C) Christian Schabesberger 2017 <chris.schabesberger></chris.schabesberger>@mailbox.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
 * KioskFragment.kt is part of Vista.
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
 * along with Vista. If not, see <http:></http:>//www.gnu.org/licenses/>.
 *
 */
open class KioskFragment : BaseListInfoFragment<StreamInfoItem, KioskInfo>(UserAction.REQUESTED_KIOSK) {
    @JvmField
    @State
    var kioskId: String = ""
    @JvmField
    var kioskTranslatedName: String? = null

    @JvmField
    @State
    var contentCountry: ContentCountry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        kioskTranslatedName = getTranslatedKioskName(kioskId, requireActivity())
        name = kioskTranslatedName
        contentCountry = getPreferredContentCountry(requireContext())
    }

    override fun onResume() {
        super.onResume()
        if (getPreferredContentCountry(requireContext()) != contentCountry) reloadContent()
        if (useAsFrontPage && activity != null) {
            try {
                setTitle(kioskTranslatedName!!)
            } catch (e: Exception) {
                showSnackBarError(ErrorInfo(e, UserAction.UI_ERROR, "Setting kiosk title"))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_kiosk, container, false)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        val supportActionBar = activity?.supportActionBar
        if (useAsFrontPage) supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    public override fun loadResult(forceLoad: Boolean): Single<KioskInfo> {
        contentCountry = getPreferredContentCountry(requireContext())
        return getKioskInfo(serviceId, url!!, forceLoad)
    }

    public override fun loadMoreItemsLogic(): Single<InfoItemsPage<StreamInfoItem>> {
        return getMoreKioskItems(serviceId, url?:"", currentNextPage)
    }

    override fun handleResult(result: KioskInfo) {
        super.handleResult(result)
        name = kioskTranslatedName
        setTitle(kioskTranslatedName!!)
    }

    override fun showEmptyState() {
        // show "no live streams" for live stream kiosk
        super.showEmptyState()
        if (MediaCCCLiveStreamKiosk.KIOSK_ID == currentInfo!!.id && ServiceList.MediaCCC.serviceId == currentInfo!!.serviceId)
            setEmptyStateMessage(R.string.no_live_streams)
    }

    companion object {
        @Throws(ExtractionException::class)
        fun getInstance(serviceId: Int): KioskFragment {
            return getInstance(serviceId, Vista.getService(serviceId).getKioskList().defaultKioskId)
        }

        @Throws(ExtractionException::class)
        fun getInstance(serviceId: Int, kioskId: String): KioskFragment {
            val instance = KioskFragment()
            val service = Vista.getService(serviceId)
            val kioskLinkHandlerFactory = service.getKioskList().getListLinkHandlerFactoryByType(kioskId)
            instance.setInitialData(serviceId, kioskLinkHandlerFactory.fromId(kioskId).url, kioskId)
            instance.kioskId = kioskId
            return instance
        }
    }
}
