package ac.mdiq.vista.settings.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.*
import android.view.View.OnTouchListener
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import ac.mdiq.vista.R
import ac.mdiq.vista.database.LocalItem
import ac.mdiq.vista.database.VoiVistaDatabase
import ac.mdiq.vista.database.playlist.PlaylistLocalItem
import ac.mdiq.vista.database.playlist.PlaylistMetadataEntry
import ac.mdiq.vista.database.playlist.model.PlaylistRemoteEntity
import ac.mdiq.vista.database.subscription.SubscriptionEntity
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.manager.LocalPlaylistManager
import ac.mdiq.vista.manager.RemotePlaylistManager
import ac.mdiq.vista.manager.SubscriptionManager
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showSnackbar
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.ui.util.Tab
import ac.mdiq.vista.ui.util.Tab.*
import ac.mdiq.vista.ui.util.TabsManager
import ac.mdiq.vista.ui.util.ThemeHelper.getMinWidthDialogTheme
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.ServiceHelper.getNameOfServiceById
import ac.mdiq.vista.ui.util.ThemeHelper.setTitleToAppCompatActivity
import ac.mdiq.vista.util.KioskTranslator.getTranslatedKioskName
import ac.mdiq.vista.util.ServiceHelper.getIcon
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showUiErrorSnackbar
import ac.mdiq.vista.util.image.PicassoHelper.loadAvatar
import ac.mdiq.vista.util.image.PicassoHelper.loadPlaylistThumbnail
import android.widget.ProgressBar
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.DialogFragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observer
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign

class ChooseTabsFragment : Fragment() {
    private val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

    private var tabsManager: TabsManager? = null

    private val tabList: MutableList<Tab> = ArrayList()
    private var selectedTabsAdapter: SelectedTabsAdapter? = null

    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
        get() = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.START or ItemTouchHelper.END) {
            override fun interpolateOutOfBoundsScroll(recyclerView: RecyclerView, viewSize: Int, viewSizeOutOfBounds: Int, totalSize: Int,
                                                      msSinceStartScroll: Long): Int {
                val standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView, viewSize, viewSizeOutOfBounds, totalSize, msSinceStartScroll)
                val minimumAbsVelocity = max(12.0, abs(standardSpeed.toDouble())).toInt()
                return minimumAbsVelocity * sign(viewSizeOutOfBounds.toDouble()).toInt()
            }

            override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (source.itemViewType != target.itemViewType || selectedTabsAdapter == null) return false

                val sourceIndex = source.bindingAdapterPosition
                val targetIndex = target.bindingAdapterPosition
                selectedTabsAdapter!!.swapItems(sourceIndex, targetIndex)
                return true
            }

            override fun isLongPressDragEnabled(): Boolean {
                return false
            }

            override fun isItemViewSwipeEnabled(): Boolean {
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {
                val position = viewHolder.bindingAdapterPosition
                tabList.removeAt(position)
                selectedTabsAdapter!!.notifyItemRemoved(position)

                if (tabList.isEmpty()) {
                    tabList.add(Type.BLANK.tab)
                    selectedTabsAdapter!!.notifyItemInserted(0)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tabsManager = TabsManager.getManager(requireContext())
        updateTabList()
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_choose_tabs, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootView, savedInstanceState)

        initButton(rootView)

        val listSelectedTabs = rootView.findViewById<RecyclerView>(R.id.selectedTabs)
        listSelectedTabs.layoutManager = LinearLayoutManager(requireContext())

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(listSelectedTabs)

        selectedTabsAdapter = SelectedTabsAdapter(requireContext(), itemTouchHelper)
        listSelectedTabs.adapter = selectedTabsAdapter
    }

    override fun onResume() {
        super.onResume()
        setTitleToAppCompatActivity(activity, getString(R.string.main_page_content))
    }

    override fun onPause() {
        super.onPause()
        saveChanges()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_chooser_fragment, menu)
        menu.findItem(R.id.menu_item_restore_default).setOnMenuItemClickListener {
            restoreDefaults()
            true
        }
    }

    private fun updateTabList() {
        tabList.clear()
        tabList.addAll(tabsManager!!.tabs)
    }

    private fun saveChanges() {
        tabsManager!!.saveTabs(tabList)
    }

    private fun restoreDefaults() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.restore_defaults)
            .setMessage(R.string.restore_defaults_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
                tabsManager!!.resetTabs()
                updateTabList()
                selectedTabsAdapter!!.notifyDataSetChanged()
            }
            .show()
    }

    private fun initButton(rootView: View) {
        val fab = rootView.findViewById<FloatingActionButton>(R.id.addTabsButton)
        fab.setOnClickListener {
            val availableTabs = getAvailableTabs(requireContext())
            if (availableTabs.isEmpty()) {
                //Toast.makeText(requireContext(), "No available tabs", Toast.LENGTH_SHORT).show();
                return@setOnClickListener
            }

            val actionListener = DialogInterface.OnClickListener { _: DialogInterface?, which: Int ->
                val selected = availableTabs[which]
                addTab(selected.tabId)
            }
            AddTabDialog(requireContext(), availableTabs, actionListener).show()
        }
    }

    private fun addTab(tab: Tab) {
        tabList.add(tab)
        selectedTabsAdapter!!.notifyDataSetChanged()
    }

    private fun addTab(tabId: Int) {
        val type = Tab.typeFrom(tabId)

        if (type == null) {
            showSnackbar(this, ErrorInfo(IllegalStateException("Tab id not found: $tabId"), UserAction.SOMETHING_ELSE, "Choosing tabs on settings"))
            return
        }

        when (type) {
            Type.KIOSK -> {
                val selectKioskFragment = SelectKioskFragment()
                selectKioskFragment.setOnSelectedListener { serviceId: Int, kioskId: String?, _: String? ->
                    addTab(KioskTab(serviceId, kioskId))
                }
                selectKioskFragment.show(parentFragmentManager, "select_kiosk")
                return
            }
            Type.CHANNEL -> {
                val selectChannelFragment = SelectChannelFragment()
                selectChannelFragment.setOnSelectedListener { serviceId: Int, url: String?, name: String? ->
                    addTab(ChannelTab(serviceId, url, name))
                }
                selectChannelFragment.show(parentFragmentManager, "select_channel")
                return
            }
            Type.PLAYLIST -> {
                val selectPlaylistFragment = SelectPlaylistFragment()
                selectPlaylistFragment.setOnSelectedListener(
                    object : SelectPlaylistFragment.OnSelectedListener {
                        override fun onLocalPlaylistSelected(id: Long, name: String?) {
                            addTab(PlaylistTab(id, name))
                        }
                        override fun onRemotePlaylistSelected(serviceId: Int, url: String?, name: String?) {
                            addTab(PlaylistTab(serviceId, url, name))
                        }
                    })
                selectPlaylistFragment.show(parentFragmentManager, "select_playlist")
                return
            }
            else -> addTab(type.tab)
        }
    }

    private fun getAvailableTabs(context: Context): Array<AddTabDialog.ChooseTabListItem> {
        val returnList = ArrayList<AddTabDialog.ChooseTabListItem>()

        for (type in Type.entries.toTypedArray()) {
            val tab = type.tab
            when (type) {
                Type.BLANK -> if (!tabList.contains(tab))
                    returnList.add(AddTabDialog.ChooseTabListItem(tab.tabId,
                        getString(R.string.blank_page_summary),
                        tab.getTabIconRes(context)))
                Type.KIOSK -> returnList.add(AddTabDialog.ChooseTabListItem(tab.tabId,
                    getString(R.string.kiosk_page_summary),
                    R.drawable.ic_whatshot))
                Type.CHANNEL -> returnList.add(AddTabDialog.ChooseTabListItem(tab.tabId,
                    getString(R.string.channel_page_summary),
                    tab.getTabIconRes(context)))
                Type.DEFAULT_KIOSK -> if (!tabList.contains(tab))
                    returnList.add(AddTabDialog.ChooseTabListItem(tab.tabId,
                        getString(R.string.default_kiosk_page_summary),
                        R.drawable.ic_whatshot))
                Type.PLAYLIST -> returnList.add(AddTabDialog.ChooseTabListItem(tab.tabId,
                    getString(R.string.playlist_page_summary),
                    tab.getTabIconRes(context)))
                else -> if (!tabList.contains(tab)) returnList.add(AddTabDialog.ChooseTabListItem(context, tab))
            }
        }
        return returnList.toTypedArray<AddTabDialog.ChooseTabListItem>()
    }

    private inner class SelectedTabsAdapter(context: Context?, private val itemTouchHelper: ItemTouchHelper?) :
        RecyclerView.Adapter<SelectedTabsAdapter.TabViewHolder>() {
        private val inflater: LayoutInflater = LayoutInflater.from(context)

        fun swapItems(fromPosition: Int, toPosition: Int) {
            Collections.swap(tabList, fromPosition, toPosition)
            notifyItemMoved(fromPosition, toPosition)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
            val view = inflater.inflate(R.layout.list_choose_tabs, parent, false)
            return TabViewHolder(view)
        }

        override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
            holder.bind(position, holder)
        }

        override fun getItemCount(): Int {
            return tabList.size
        }

        inner class TabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tabIconView: AppCompatImageView = itemView.findViewById(R.id.tabIcon)
            private val tabNameView: TextView = itemView.findViewById(R.id.tabName)
            private val handle: ImageView = itemView.findViewById(R.id.handle)

            @SuppressLint("ClickableViewAccessibility")
            fun bind(position: Int, holder: TabViewHolder) {
                handle.setOnTouchListener(getOnTouchListener(holder))

                val tab = tabList[position]
                val type = Tab.typeFrom(tab.tabId) ?: return

                tabNameView.text = getTabName(type, tab)
                tabIconView.setImageResource(tab.getTabIconRes(requireContext()))
            }

            private fun getTabName(type: Type, tab: Tab): String {
                when (type) {
                    Type.BLANK -> return getString(R.string.blank_page_summary)
                    Type.DEFAULT_KIOSK -> return getString(R.string.default_kiosk_page_summary)
                    Type.KIOSK -> return (getNameOfServiceById((tab as KioskTab).kioskServiceId) + "/" + tab.getTabName(requireContext()))
                    Type.CHANNEL -> return (getNameOfServiceById((tab as ChannelTab).channelServiceId) + "/" + tab.getTabName(requireContext()))
                    Type.PLAYLIST -> {
                        val serviceId = (tab as PlaylistTab).playlistServiceId
                        val serviceName = if (serviceId == -1) getString(R.string.local) else getNameOfServiceById(serviceId)
                        return serviceName + "/" + tab.getTabName(requireContext())
                    }
                    else -> return tab.getTabName(requireContext())?:""
                }
            }

            @SuppressLint("ClickableViewAccessibility")
            private fun getOnTouchListener(item: RecyclerView.ViewHolder): OnTouchListener {
                return OnTouchListener { _: View?, motionEvent: MotionEvent ->
                    if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (itemTouchHelper != null && itemCount > 1) {
                            itemTouchHelper.startDrag(item)
                            return@OnTouchListener true
                        }
                    }
                    false
                }
            }
        }
    }

    class AddTabDialog internal constructor(context: Context, items: Array<ChooseTabListItem>, actions: DialogInterface.OnClickListener) {
        private val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.tab_choose))
            .setAdapter(DialogListAdapter(context, items), actions)
            .create()

        fun show() {
            dialog.show()
        }

        internal class ChooseTabListItem(val tabId: Int, val itemName: String, @field:DrawableRes @param:DrawableRes val itemIcon: Int) {
            constructor(context: Context, tab: Tab) : this(tab.tabId, tab.getTabName(context)?:"", tab.getTabIconRes(context))
        }

        private class DialogListAdapter(context: Context, private val items: Array<ChooseTabListItem>) : BaseAdapter() {
            private val inflater: LayoutInflater = LayoutInflater.from(context)

            @DrawableRes
            private val fallbackIcon = R.drawable.ic_whatshot

            override fun getCount(): Int {
                return items.size
            }

            override fun getItem(position: Int): ChooseTabListItem {
                return items[position]
            }

            override fun getItemId(position: Int): Long {
                return getItem(position).tabId.toLong()
            }

            override fun getView(position: Int, view: View?, parent: ViewGroup): View {
                val convertView = view ?: inflater.inflate(R.layout.list_choose_tabs_dialog, parent, false)

                val item = getItem(position)
                val tabIconView = convertView.findViewById<AppCompatImageView>(R.id.tabIcon)
                val tabNameView = convertView.findViewById<TextView>(R.id.tabName)

                tabIconView.setImageResource(if (item.itemIcon > 0) item.itemIcon else fallbackIcon)
                tabNameView.text = item.itemName

                return convertView
            }
        }
    }

    class SelectChannelFragment : DialogFragment() {
        private val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

        private var onSelectedListener: OnSelectedListener? = null
        private var onCancelListener: OnCancelListener? = null

        private var progressBar: ProgressBar? = null
        private var emptyView: TextView? = null
        private var recyclerView: RecyclerView? = null

        private var subscriptions: List<SubscriptionEntity> = Vector()

        private val subscriptionObserver: Observer<List<SubscriptionEntity>>
            get() = object : Observer<List<SubscriptionEntity>> {
                override fun onSubscribe(disposable: Disposable) {}

                override fun onNext(newSubscriptions: List<SubscriptionEntity>) {
                    displayChannels(newSubscriptions)
                }

                override fun onError(exception: Throwable) {
                    showUiErrorSnackbar(this@SelectChannelFragment, "Loading subscription", exception)
                }

                override fun onComplete() {}
            }

        fun setOnSelectedListener(listener: OnSelectedListener?) {
            onSelectedListener = listener
        }

        fun setOnCancelListener(listener: OnCancelListener?) {
            onCancelListener = listener
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setStyle(STYLE_NO_TITLE, getMinWidthDialogTheme(requireContext()))
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            Logd(TAG, "onCreateView")
            val v = inflater.inflate(R.layout.select_channel_fragment, container, false)
            recyclerView = v.findViewById(R.id.items_list)
            recyclerView!!.layoutManager = LinearLayoutManager(context)
            val channelAdapter = SelectChannelAdapter()
            recyclerView!!.adapter = channelAdapter

            progressBar = v.findViewById(R.id.progressBar)
            emptyView = v.findViewById(R.id.empty_state_view)
            progressBar!!.visibility = View.VISIBLE
            recyclerView!!.visibility = View.GONE
            emptyView!!.visibility = View.GONE

            val subscriptionManager = SubscriptionManager(requireContext())
            subscriptionManager.subscriptions().toObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(subscriptionObserver)

            return v
        }

        override fun onCancel(dialogInterface: DialogInterface) {
            super.onCancel(dialogInterface)
            onCancelListener?.onCancel()
        }

        private fun clickedItem(position: Int) {
            if (onSelectedListener != null) {
                val entry = subscriptions[position]
                onSelectedListener!!.onChannelSelected(entry.serviceId, entry.url, entry.name)
            }
            dismiss()
        }

        private fun displayChannels(newSubscriptions: List<SubscriptionEntity>) {
            this.subscriptions = newSubscriptions
            progressBar!!.visibility = View.GONE
            if (newSubscriptions.isEmpty()) {
                emptyView!!.visibility = View.VISIBLE
                return
            }
            recyclerView!!.visibility = View.VISIBLE
        }

        fun interface OnSelectedListener {
            fun onChannelSelected(serviceId: Int, url: String?, name: String?)
        }

        interface OnCancelListener {
            fun onCancel()
        }

        private inner class SelectChannelAdapter : RecyclerView.Adapter<SelectChannelAdapter.SelectChannelItemHolder?>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectChannelItemHolder {
                val item = LayoutInflater.from(parent.context).inflate(R.layout.select_channel_item, parent, false)
                return SelectChannelItemHolder(item)
            }

            override fun onBindViewHolder(holder: SelectChannelItemHolder, position: Int) {
                val entry = subscriptions[position]
                holder.titleView.text = entry.name
                holder.view.setOnClickListener { clickedItem(position) }
                loadAvatar(entry.avatarUrl).into(holder.thumbnailView)
            }

            override fun getItemCount(): Int {
                return subscriptions.size
            }

            inner class SelectChannelItemHolder internal constructor(val view: View) : RecyclerView.ViewHolder(view) {
                val thumbnailView: ImageView = view.findViewById(R.id.itemThumbnailView)
                val titleView: TextView = view.findViewById(R.id.itemTitleView)
            }
        }
    }

    class SelectKioskFragment : DialogFragment() {
        private val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

        private var selectKioskAdapter: SelectKioskAdapter? = null
        private var onSelectedListener: OnSelectedListener? = null

        fun setOnSelectedListener(listener: OnSelectedListener?) {
            onSelectedListener = listener
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setStyle(STYLE_NO_TITLE, getMinWidthDialogTheme(requireContext()))
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            Logd(TAG, "onCreateView")
            val v = inflater.inflate(R.layout.select_kiosk_fragment, container, false)
            val recyclerView = v.findViewById<RecyclerView>(R.id.items_list)
            recyclerView.layoutManager = LinearLayoutManager(context)
            try {
                selectKioskAdapter = SelectKioskAdapter()
            } catch (e: Exception) {
                showUiErrorSnackbar(this, "Selecting kiosk", e)
            }
            recyclerView.adapter = selectKioskAdapter

            return v
        }

        private fun clickedItem(entry: SelectKioskAdapter.Entry) {
            onSelectedListener?.onKioskSelected(entry.serviceId, entry.kioskId, entry.kioskName)
            dismiss()
        }

        fun interface OnSelectedListener {
            fun onKioskSelected(serviceId: Int, kioskId: String?, kioskName: String?)
        }

        private inner class SelectKioskAdapter : RecyclerView.Adapter<SelectKioskAdapter.SelectKioskItemHolder>() {
            private val kioskList: MutableList<Entry> = Vector()

            init {
                for (service in Vista.services) {
                    val kList = service.getKioskList()
                    for (kioskId in kList.availableKiosks) {
                        val name = String.format(getString(R.string.service_kiosk_string), service.serviceInfo.name, getTranslatedKioskName(kioskId!!, context!!))
                        kioskList.add(Entry(getIcon(service.serviceId), service.serviceId, kioskId, name))
                    }
                }
            }

            override fun getItemCount(): Int {
                return kioskList.size
            }

            override fun onCreateViewHolder(parent: ViewGroup, type: Int): SelectKioskItemHolder {
                val item = LayoutInflater.from(parent.context).inflate(R.layout.select_kiosk_item, parent, false)
                return SelectKioskItemHolder(item)
            }

            override fun onBindViewHolder(holder: SelectKioskItemHolder, position: Int) {
                val entry = kioskList[position]
                holder.titleView.text = entry.kioskName
                holder.thumbnailView.setImageDrawable(AppCompatResources.getDrawable(requireContext(), entry.icon))
                holder.view.setOnClickListener { clickedItem(entry) }
            }

            inner class Entry(val icon: Int, val serviceId: Int, val kioskId: String, val kioskName: String)

            inner class SelectKioskItemHolder(val view: View) : RecyclerView.ViewHolder(view) {
                val thumbnailView: ImageView = view.findViewById(R.id.itemThumbnailView)
                val titleView: TextView = view.findViewById(R.id.itemTitleView)
            }
        }
    }

    class SelectPlaylistFragment : DialogFragment() {
        private val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

        private var onSelectedListener: OnSelectedListener? = null

        private var progressBar: ProgressBar? = null
        private var emptyView: TextView? = null
        private var recyclerView: RecyclerView? = null
        private var disposable: Disposable? = null

        private var playlists: List<PlaylistLocalItem> = Vector()

        fun setOnSelectedListener(listener: OnSelectedListener?) {
            onSelectedListener = listener
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            Logd(TAG, "onCreateView")
            val v = inflater.inflate(R.layout.select_playlist_fragment, container, false)
            progressBar = v.findViewById(R.id.progressBar)
            recyclerView = v.findViewById(R.id.items_list)
            emptyView = v.findViewById(R.id.empty_state_view)

            recyclerView!!.layoutManager = LinearLayoutManager(context)
            val playlistAdapter = SelectPlaylistAdapter()
            recyclerView!!.adapter = playlistAdapter

            loadPlaylists()
            return v
        }

        override fun onDestroy() {
            super.onDestroy()
            disposable?.dispose()
        }

        private fun loadPlaylists() {
            progressBar!!.visibility = View.VISIBLE
            recyclerView!!.visibility = View.GONE
            emptyView!!.visibility = View.GONE

            val database = VoiVistaDatabase.getInstance(requireContext())
            val localPlaylistManager = LocalPlaylistManager(database)
            val remotePlaylistManager = RemotePlaylistManager(database)

            disposable = Flowable.combineLatest(localPlaylistManager.playlists, remotePlaylistManager.playlists) {
                    localPlaylists: List<PlaylistMetadataEntry>, remotePlaylists: List<PlaylistRemoteEntity> ->
                PlaylistLocalItem.merge(localPlaylists, remotePlaylists) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ newPlaylists: List<PlaylistLocalItem> -> this.displayPlaylists(newPlaylists) }, { e: Throwable? -> this.onError(e) })
        }

        private fun displayPlaylists(newPlaylists: List<PlaylistLocalItem>) {
            playlists = newPlaylists
            progressBar!!.visibility = View.GONE
            emptyView!!.visibility = if (newPlaylists.isEmpty()) View.VISIBLE else View.GONE
            recyclerView!!.visibility = if (newPlaylists.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun onError(e: Throwable?) {
            showSnackbar(requireActivity(), ErrorInfo(e!!, UserAction.UI_ERROR, "Loading playlists"))
        }

        private fun clickedItem(position: Int) {
            if (onSelectedListener != null) {
                val selectedItem: LocalItem = playlists[position]

                if (selectedItem is PlaylistMetadataEntry) {
                    onSelectedListener!!.onLocalPlaylistSelected(selectedItem.uid, selectedItem.name)
                } else if (selectedItem is PlaylistRemoteEntity) {
                    onSelectedListener!!.onRemotePlaylistSelected(selectedItem.serviceId,
                        selectedItem.url,
                        selectedItem.name)
                }
            }
            dismiss()
        }

        interface OnSelectedListener {
            fun onLocalPlaylistSelected(id: Long, name: String?)
            fun onRemotePlaylistSelected(serviceId: Int, url: String?, name: String?)
        }

        private inner class SelectPlaylistAdapter : RecyclerView.Adapter<SelectPlaylistAdapter.SelectPlaylistItemHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectPlaylistItemHolder {
                val item = LayoutInflater.from(parent.context).inflate(R.layout.list_playlist_mini_item, parent, false)
                return SelectPlaylistItemHolder(item)
            }

            override fun onBindViewHolder(holder: SelectPlaylistItemHolder, position: Int) {
                val selectedItem = playlists[position]

                if (selectedItem is PlaylistMetadataEntry) {
                    holder.titleView.text = selectedItem.name
                    holder.view.setOnClickListener { clickedItem(position) }
                    loadPlaylistThumbnail(selectedItem.thumbnailUrl).into(holder.thumbnailView)
                } else if (selectedItem is PlaylistRemoteEntity) {
                    holder.titleView.text = selectedItem.name
                    holder.view.setOnClickListener { clickedItem(position) }
                    loadPlaylistThumbnail(selectedItem.thumbnailUrl).into(holder.thumbnailView)
                }
            }

            override fun getItemCount(): Int {
                return playlists.size
            }

            inner class SelectPlaylistItemHolder internal constructor(val view: View) : RecyclerView.ViewHolder(view) {
                val thumbnailView: ImageView = view.findViewById(R.id.itemThumbnailView)
                val titleView: TextView = view.findViewById(R.id.itemTitleView)
            }
        }
    }

}
