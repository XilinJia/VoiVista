package ac.mdiq.vista.ui.fragments

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.PignateFooterBinding
import ac.mdiq.vista.ui.adapter.LocalItemListAdapter
//import ac.mdiq.vista.fragments.BaseStateFragment.onDestroyView
import ac.mdiq.vista.ui.util.ListViewContract
import ac.mdiq.vista.ui.util.ItemViewMode
import ac.mdiq.vista.ui.util.ktx.animate
import ac.mdiq.vista.ui.util.ktx.animateHideRecyclerViewAllowingScrolling
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.ui.util.ThemeHelper.getItemViewMode

/**
 * This fragment is design to be used with persistent data such as
 * [ac.mdiq.vista.database.LocalItem], and does not cache the data contained
 * in the list adapter to avoid extra writes when the it exits or re-enters its lifecycle.
 *
 *
 * This fragment destroys its adapter and views when [Fragment.onDestroyView] is
 * called and is memory efficient when in backstack.
 *
 *
 * @param <I> List of [ac.mdiq.vista.database.LocalItem]s
 * @param <N> [Void]
</N></I> */
abstract class BaseLocalListFragment<I, N> : BaseStateFragment<I>(), ListViewContract<I, N>,
    OnSharedPreferenceChangeListener {
    private var headerRootBinding: ViewBinding? = null
    private var footerRootBinding: ViewBinding? = null

    @JvmField
    protected var itemListAdapter: LocalItemListAdapter? = null

    @JvmField
    protected var itemsList: RecyclerView? = null
    private var updateFlags = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        PreferenceManager.getDefaultSharedPreferences(activity!!)
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(activity!!).unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (updateFlags != 0) {
            if ((updateFlags and LIST_MODE_UPDATE_FLAG) != 0) refreshItemViewMode()
            updateFlags = 0
        }
    }

    /**
     * Updates the item view mode based on user preference.
     */
    private fun refreshItemViewMode() {
        val itemViewMode = getItemViewMode(requireContext())
        itemsList!!.layoutManager = if ((itemViewMode == ItemViewMode.GRID)) gridLayoutManager else listLayoutManager
        itemListAdapter!!.setItemViewMode(itemViewMode)
        itemListAdapter!!.notifyDataSetChanged()
    }

    protected open val listHeader: ViewBinding?
        get() = null

    private val listFooter: ViewBinding
        get() = PignateFooterBinding.inflate(activity!!.layoutInflater, itemsList, false)

    private val gridLayoutManager: RecyclerView.LayoutManager
        get() {
            val resources = activity!!.resources
            var width = resources.getDimensionPixelSize(R.dimen.video_item_grid_thumbnail_image_width)
            width = (width + (24 * resources.displayMetrics.density)).toInt()
            val spanCount = Math.floorDiv(resources.displayMetrics.widthPixels, width)
            val lm = GridLayoutManager(activity, spanCount)
            lm.spanSizeLookup = itemListAdapter!!.getSpanSizeLookup(spanCount)
            return lm
        }

    private val listLayoutManager: RecyclerView.LayoutManager
        get() = LinearLayoutManager(activity)

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        itemListAdapter = LocalItemListAdapter(activity)
        itemsList = rootView.findViewById(R.id.items_list)
        refreshItemViewMode()

        headerRootBinding = listHeader
        if (headerRootBinding != null) itemListAdapter!!.setHeader(headerRootBinding!!.root)
        footerRootBinding = listFooter
        itemListAdapter!!.setFooter(footerRootBinding!!.root)
        itemsList?.adapter = itemListAdapter
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        Logd(TAG, "onCreateOptionsMenu() called with: menu = [$menu], inflater = [$inflater]")
        val supportActionBar = activity!!.supportActionBar ?: return
        supportActionBar.setDisplayShowTitleEnabled(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        itemsList = null
        itemListAdapter = null
    }

    public override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)
        resetFragment()
    }

    override fun showLoading() {
        super.showLoading()
         itemsList?.animateHideRecyclerViewAllowingScrolling()
        headerRootBinding?.root?.animate(false, 200)
    }

    override fun hideLoading() {
        super.hideLoading()
        itemsList?.animate(true, 200)
        headerRootBinding?.root?.animate(true, 200)
    }

    override fun showEmptyState() {
        super.showEmptyState()
        showListFooter(false)
    }

    override fun showListFooter(show: Boolean) {
        itemsList?.post { itemListAdapter?.showFooter(show) }
    }

    override fun handleNextItems(result: N) {
        isLoading.set(false)
    }

    protected open fun resetFragment() {
        itemListAdapter?.clearStreamItemList()
    }

    override fun handleError() {
        super.handleError()
        resetFragment()
        showListFooter(false)
        itemsList?.animateHideRecyclerViewAllowingScrolling()
        headerRootBinding?.root?.animate(false, 200)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (getString(R.string.list_view_mode_key) == key) updateFlags = updateFlags or LIST_MODE_UPDATE_FLAG
    }

    companion object {
        private const val LIST_MODE_UPDATE_FLAG = 0x32
    }
}
