package ac.mdiq.vista.ui.fragments

//import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService.KEY_MODE
//import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService.KEY_VALUE
//import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService.PREVIOUS_EXPORT_MODE
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.*
import com.xwray.groupie.viewbinding.BindableItem
import com.xwray.groupie.viewbinding.GroupieViewHolder
import icepick.Icepick
import icepick.State
import io.reactivex.rxjava3.disposables.CompositeDisposable
import ac.mdiq.vista.R
import ac.mdiq.vista.database.feed.model.FeedGroupEntity
import ac.mdiq.vista.database.feed.model.FeedGroupEntity.Companion.GROUP_ALL_ID
import ac.mdiq.vista.databinding.*
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.extractor.ServiceList
import ac.mdiq.vista.extractor.channel.ChannelInfoItem
import ac.mdiq.vista.giga.io.NoFileManagerSafeGuard
import ac.mdiq.vista.giga.io.StoredFileHelper
import ac.mdiq.vista.ui.util.BackPressable
import ac.mdiq.vista.ui.util.ktx.animate
import ac.mdiq.vista.ui.viewmodel.SubscriptionViewModel.SubscriptionState
import ac.mdiq.vista.local.subscription.FeedGroupIcon
import ac.mdiq.vista.manager.SubscriptionManager
import ac.mdiq.vista.ui.viewmodel.FeedGroupDialogViewModel
import ac.mdiq.vista.ui.viewmodel.FeedGroupDialogViewModel.DialogEvent
import ac.mdiq.vista.ui.dialog.FeedGroupReorderDialogViewModel
import ac.mdiq.vista.ui.dialog.FeedGroupReorderDialogViewModel.DialogEvent.ProcessingEvent
import ac.mdiq.vista.ui.dialog.FeedGroupReorderDialogViewModel.DialogEvent.SuccessEvent
import ac.mdiq.vista.ui.dialog.ImportConfirmationDialog
import ac.mdiq.vista.local.subscription.item.*
import ac.mdiq.vista.local.subscription.services.SubscriptionsExportService
import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService
import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService.Companion.KEY_MODE
import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService.Companion.KEY_VALUE
import ac.mdiq.vista.local.subscription.services.SubscriptionsImportService.Companion.PREVIOUS_EXPORT_MODE
import ac.mdiq.vista.ui.util.NavigationHelper
import ac.mdiq.vista.ui.gesture.OnClickGesture
import ac.mdiq.vista.ui.util.ThemeHelper
import ac.mdiq.vista.util.*
import ac.mdiq.vista.ui.util.ThemeHelper.getGridSpanCount
import ac.mdiq.vista.ui.util.ThemeHelper.getGridSpanCountChannels
import ac.mdiq.vista.ui.util.ShareUtils
import ac.mdiq.vista.ui.viewmodel.SubscriptionViewModel
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*

@UnstableApi class SubscriptionFragment : BaseStateFragment<SubscriptionState>() {
    private var _binding: FragmentSubscriptionBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: SubscriptionViewModel
    private lateinit var subscriptionManager: SubscriptionManager
    private val disposables: CompositeDisposable = CompositeDisposable()

    private val groupAdapter = GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>()
    private lateinit var carouselAdapter: GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>
    private lateinit var feedGroupsCarousel: FeedGroupCarouselItem
    private lateinit var feedGroupsSortMenuItem: GroupsHeader
    private val subscriptionsSection = Section()

    private val requestExportLauncher = registerForActivityResult(StartActivityForResult(), this::requestExportResult)
    private val requestImportLauncher = registerForActivityResult(StartActivityForResult(), this::requestImportResult)

    @State
    @JvmField
    var itemsListState: Parcelable? = null

    @State
    @JvmField
    var feedGroupsCarouselState: Parcelable? = null

    lateinit var fm: FragmentManager

    init {
        setHasOptionsMenu(true)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscriptionManager = SubscriptionManager(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        fm = requireActivity().supportFragmentManager
        return inflater.inflate(R.layout.fragment_subscription, container, false)
    }

    override fun onPause() {
        super.onPause()
        itemsListState = binding.itemsList.layoutManager?.onSaveInstanceState()
        feedGroupsCarouselState = feedGroupsCarousel.onSaveInstanceState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }

    @SuppressLint("UseRequireInsteadOfGet")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        activity!!.supportActionBar?.setDisplayShowTitleEnabled(true)
        activity!!.supportActionBar?.setTitle(R.string.tab_subscriptions)
        buildImportExportMenu(menu)
    }

    private fun buildImportExportMenu(menu: Menu) {
        // -- Import --
        val importSubMenu = menu.addSubMenu(R.string.import_from)

        addMenuItemToSubmenu(importSubMenu, R.string.previous_export) { onImportPreviousSelected() }.setIcon(R.drawable.ic_backup)

        for (service in ServiceList.all()) {
            val subscriptionExtractor = service.getSubscriptionExtractor() ?: continue

            val supportedSources = subscriptionExtractor.supportedSources
            if (supportedSources.isEmpty()) continue

            addMenuItemToSubmenu(importSubMenu, service.serviceInfo.name) {
                onImportFromServiceSelected(service.serviceId)
            }.setIcon(ServiceHelper.getIcon(service.serviceId))
        }

        // -- Export --
        val exportSubMenu = menu.addSubMenu(R.string.export_to)
        addMenuItemToSubmenu(exportSubMenu, R.string.file) { onExportSelected() }.setIcon(R.drawable.ic_save)
    }

    private fun addMenuItemToSubmenu(subMenu: SubMenu, @StringRes title: Int, onClick: Runnable): MenuItem {
        return setClickListenerToMenuItem(subMenu.add(title), onClick)
    }

    private fun addMenuItemToSubmenu(subMenu: SubMenu, title: String, onClick: Runnable): MenuItem {
        return setClickListenerToMenuItem(subMenu.add(title), onClick)
    }

    private fun setClickListenerToMenuItem(menuItem: MenuItem, onClick: Runnable): MenuItem {
        menuItem.setOnMenuItemClickListener { onClick.run(); true }
        return menuItem
    }

    @OptIn(UnstableApi::class) private fun onImportFromServiceSelected(serviceId: Int) {
        val fragmentManager = fm
        NavigationHelper.openSubscriptionsImportFragment(fragmentManager, serviceId)
    }

    private fun onImportPreviousSelected() {
        NoFileManagerSafeGuard.launchSafe(requestImportLauncher, StoredFileHelper.getPicker(requireContext(), JSON_MIME_TYPE), TAG, requireContext())
    }

    private fun onExportSelected() {
        val date = SimpleDateFormat("yyyyMMddHHmm", Locale.ENGLISH).format(Date())
        val exportName = "voivista_subscriptions_$date.json"
        NoFileManagerSafeGuard.launchSafe(requestExportLauncher,
            StoredFileHelper.getNewPicker(requireActivity(), exportName, JSON_MIME_TYPE, null), TAG, requireContext())
    }

    private fun openReorderDialog() {
        FeedGroupReorderDialog().show(parentFragmentManager, null)
    }

    @SuppressLint("UseRequireInsteadOfGet")
    private fun requestExportResult(result: ActivityResult) {
        if (result.data != null && result.resultCode == Activity.RESULT_OK) {
            activity!!.startService(Intent(activity, SubscriptionsExportService::class.java)
                .putExtra(SubscriptionsExportService.KEY_FILE_PATH, result.data?.data))
        }
    }

    private fun requestImportResult(result: ActivityResult) {
        if (result.data != null && result.resultCode == Activity.RESULT_OK) {
            ImportConfirmationDialog.show(this,
                Intent(activity, SubscriptionsImportService::class.java)
                    .putExtra(KEY_MODE, PREVIOUS_EXPORT_MODE)
                    .putExtra(KEY_VALUE, result.data?.data),
            )
        }
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        _binding = FragmentSubscriptionBinding.bind(rootView)

        groupAdapter.spanCount = if (SubscriptionViewModel.shouldUseGridForSubscription(requireContext())) getGridSpanCountChannels(requireContext()) else 1
        binding.itemsList.layoutManager = GridLayoutManager(requireContext(),
            groupAdapter.spanCount).apply { spanSizeLookup = groupAdapter.spanSizeLookup }
        binding.itemsList.adapter = groupAdapter
        binding.itemsList.itemAnimator = null

        viewModel = ViewModelProvider(this)[SubscriptionViewModel::class.java]
        viewModel.stateLiveData.observe(viewLifecycleOwner) { it?.let(this::handleResult) }
        viewModel.feedGroupsLiveData.observe(viewLifecycleOwner) {
            it?.let { (groups, listViewMode) ->
                handleFeedGroups(groups, listViewMode)
            }
        }
        setupInitialLayout()
    }

    private fun setupInitialLayout() {
        Section().apply {
            carouselAdapter = GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>()
            carouselAdapter.setOnItemClickListener { item, _ ->
                when (item) {
                    is FeedGroupCardItem -> NavigationHelper.openFeedFragment(fm, item.groupId, item.name)
                    is FeedGroupCardGridItem -> NavigationHelper.openFeedFragment(fm, item.groupId, item.name)
                    is FeedGroupAddNewItem -> FeedGroupDialog.newInstance().show(fm, null)
                    is FeedGroupAddNewGridItem -> FeedGroupDialog.newInstance().show(fm, null)
                }
            }
            carouselAdapter.setOnItemLongClickListener { item, _ ->
                if ((item is FeedGroupCardItem && item.groupId == GROUP_ALL_ID) || (item is FeedGroupCardGridItem && item.groupId == GROUP_ALL_ID))
                    return@setOnItemLongClickListener false
                when (item) {
                    is FeedGroupCardItem -> FeedGroupDialog.newInstance(item.groupId).show(fm, null)
                    is FeedGroupCardGridItem -> FeedGroupDialog.newInstance(item.groupId).show(fm, null)
                }
                return@setOnItemLongClickListener true
            }
            feedGroupsCarousel = FeedGroupCarouselItem(carouselAdapter = carouselAdapter, listViewMode = viewModel.getListViewMode())
            feedGroupsSortMenuItem = GroupsHeader(title = getString(R.string.feed_groups_header_title), onSortClicked = ::openReorderDialog,
                onToggleListViewModeClicked = ::toggleListViewMode, listViewMode = viewModel.getListViewMode())

            add(Section(feedGroupsSortMenuItem, listOf(feedGroupsCarousel)))
            groupAdapter.clear()
            groupAdapter.add(this)
        }
        subscriptionsSection.setPlaceholder(ImportSubscriptionsHintPlaceholderItem())
        subscriptionsSection.setHideWhenEmpty(true)
        groupAdapter.add(Section(Header(getString(R.string.tab_subscriptions)), listOf(subscriptionsSection)))
    }

    private fun toggleListViewMode() {
        viewModel.setListViewMode(!viewModel.getListViewMode())
    }

    private fun showLongTapDialog(selectedItem: ChannelInfoItem) {
        val commands = arrayOf(getString(R.string.share), getString(R.string.open_in_browser), getString(R.string.unsubscribe))
        val actions = DialogInterface.OnClickListener { _, i ->
            when (i) {
                0 -> ShareUtils.shareText(requireContext(), selectedItem.name, selectedItem.url, selectedItem.thumbnails)
                1 -> ShareUtils.openUrlInBrowser(requireContext(), selectedItem.url)
                2 -> deleteChannel(selectedItem)
            }
        }

        val dialogTitleBinding = DialogTitleBinding.inflate(LayoutInflater.from(requireContext()))
        dialogTitleBinding.root.isSelected = true
        dialogTitleBinding.itemTitleView.text = selectedItem.name
        dialogTitleBinding.itemAdditionalDetails.visibility = View.GONE

        AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitleBinding.root)
            .setItems(commands, actions)
            .show()
    }

    private fun deleteChannel(selectedItem: ChannelInfoItem) {
        disposables.add(subscriptionManager.deleteSubscription(selectedItem.serviceId, selectedItem.url).subscribe {
            Toast.makeText(requireContext(), getString(R.string.channel_unsubscribed), Toast.LENGTH_SHORT).show() })
    }

    override fun doInitialLoadLogic() = Unit

    override fun startLoading(forceLoad: Boolean) = Unit

    private val listenerChannelItem = object : OnClickGesture<ChannelInfoItem> {
        override fun selected(selectedItem: ChannelInfoItem) =
            NavigationHelper.openChannelFragment(fm, selectedItem.serviceId, selectedItem.url, selectedItem.name)
        override fun held(selectedItem: ChannelInfoItem) = showLongTapDialog(selectedItem)
    }

    override fun handleResult(result: SubscriptionState) {
        super.handleResult(result)

        when (result) {
            is SubscriptionState.LoadedState -> {
                result.subscriptions.forEach {
                    if (it is ChannelItem) {
                        it.gesturesListener = listenerChannelItem
                        it.itemVersion =
                            if (SubscriptionViewModel.shouldUseGridForSubscription(requireContext())) ChannelItem.ItemVersion.GRID
                            else ChannelItem.ItemVersion.MINI
                    }
                }
                subscriptionsSection.update(result.subscriptions)
                subscriptionsSection.setHideWhenEmpty(false)
                if (itemsListState != null) {
                    binding.itemsList.layoutManager?.onRestoreInstanceState(itemsListState)
                    itemsListState = null
                }
            }
            is SubscriptionState.ErrorState -> {
                result.error?.let {
                    showError(ErrorInfo(result.error, UserAction.SOMETHING_ELSE, "Subscriptions"))
                }
            }
        }
    }

    private fun handleFeedGroups(groups: List<Group>, listViewMode: Boolean) {
        if (feedGroupsCarouselState != null) {
            feedGroupsCarousel.onRestoreInstanceState(feedGroupsCarouselState)
            feedGroupsCarouselState = null
        }

        binding.itemsList.post {
            // since this part was posted to the next UI cycle, the fragment might have been
            // removed in the meantime
            if (context == null) return@post

            feedGroupsCarousel.listViewMode = listViewMode
            feedGroupsSortMenuItem.showSortButton = groups.size > 1
            feedGroupsSortMenuItem.listViewMode = listViewMode
            feedGroupsCarousel.notifyChanged(FeedGroupCarouselItem.PAYLOAD_UPDATE_LIST_VIEW_MODE)
            feedGroupsSortMenuItem.notifyChanged(GroupsHeader.PAYLOAD_UPDATE_ICONS)

            // update items here to prevent flickering
            carouselAdapter.apply {
                clear()
                if (listViewMode) {
                    add(FeedGroupAddNewItem())
                    add(FeedGroupCardItem(GROUP_ALL_ID, getString(R.string.all), FeedGroupIcon.WHATS_NEW))
                } else {
                    add(FeedGroupAddNewGridItem())
                    add(FeedGroupCardGridItem(GROUP_ALL_ID, getString(R.string.all), FeedGroupIcon.WHATS_NEW))
                }
                addAll(groups)
            }
        }
    }

    override fun showLoading() {
        super.showLoading()
        binding.itemsList.animate(false, 100)
    }

    override fun hideLoading() {
        super.hideLoading()
        binding.itemsList.animate(true, 200)
    }

    class Header(private val title: String) : BindableItem<SubscriptionHeaderBinding>() {
        override fun getLayout(): Int = R.layout.subscription_header
        override fun bind(viewBinding: SubscriptionHeaderBinding, position: Int) {
            viewBinding.root.text = title
        }
        override fun initializeViewBinding(view: View) = SubscriptionHeaderBinding.bind(view)
    }

    class GroupsHeader(private val title: String, private val onSortClicked: () -> Unit, private val onToggleListViewModeClicked: () -> Unit,
                       var showSortButton: Boolean = true, var listViewMode: Boolean = true) : BindableItem<SubscriptionGroupsHeaderBinding>() {
        override fun getLayout(): Int = R.layout.subscription_groups_header
        override fun bind(viewBinding: SubscriptionGroupsHeaderBinding, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_UPDATE_ICONS)) {
                updateIcons(viewBinding)
                return
            }
            super.bind(viewBinding, position, payloads)
        }
        override fun bind(viewBinding: SubscriptionGroupsHeaderBinding, position: Int) {
            viewBinding.headerTitle.text = title
            viewBinding.headerSort.setOnClickListener { onSortClicked() }
            viewBinding.headerToggleViewMode.setOnClickListener { onToggleListViewModeClicked() }
            updateIcons(viewBinding)
        }
        override fun initializeViewBinding(view: View) = SubscriptionGroupsHeaderBinding.bind(view)
        private fun updateIcons(viewBinding: SubscriptionGroupsHeaderBinding) {
            viewBinding.headerToggleViewMode.setImageResource(if (listViewMode) R.drawable.ic_apps else R.drawable.ic_list)
            viewBinding.headerSort.isVisible = showSortButton
        }
        companion object {
            const val PAYLOAD_UPDATE_ICONS = 1
        }
    }

    class FeedGroupCarouselItem(private val carouselAdapter: GroupAdapter<GroupieViewHolder<FeedItemCarouselBinding>>, var listViewMode: Boolean)
        : BindableItem<FeedItemCarouselBinding>() {

        companion object {
            const val PAYLOAD_UPDATE_LIST_VIEW_MODE = 2
        }

        private var carouselLayoutManager: LinearLayoutManager? = null
        private var listState: Parcelable? = null

        override fun getLayout() = R.layout.feed_item_carousel
        fun onSaveInstanceState(): Parcelable? {
            listState = carouselLayoutManager?.onSaveInstanceState()
            return listState
        }
        fun onRestoreInstanceState(state: Parcelable?) {
            carouselLayoutManager?.onRestoreInstanceState(state)
            listState = state
        }
        override fun initializeViewBinding(view: View): FeedItemCarouselBinding {
            val viewBinding = FeedItemCarouselBinding.bind(view)
            updateViewMode(viewBinding)
            return viewBinding
        }

        override fun bind(viewBinding: FeedItemCarouselBinding, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_UPDATE_LIST_VIEW_MODE)) {
                updateViewMode(viewBinding)
                return
            }
            super.bind(viewBinding, position, payloads)
        }

        override fun bind(viewBinding: FeedItemCarouselBinding, position: Int) {
            viewBinding.recyclerView.apply { adapter = carouselAdapter }
            carouselLayoutManager?.onRestoreInstanceState(listState)
        }

        override fun unbind(viewHolder: GroupieViewHolder<FeedItemCarouselBinding>) {
            super.unbind(viewHolder)
            listState = carouselLayoutManager?.onSaveInstanceState()
        }

        private fun updateViewMode(viewBinding: FeedItemCarouselBinding) {
            viewBinding.recyclerView.apply { adapter = carouselAdapter }
            val context = viewBinding.root.context
            carouselLayoutManager = if (listViewMode) LinearLayoutManager(context)
            else GridLayoutManager(context, getGridSpanCount(context, DeviceUtils.dpToPx(112, context)))
            viewBinding.recyclerView.apply {
                layoutManager = carouselLayoutManager
                adapter = carouselAdapter
            }
        }
    }

    class FeedGroupAddNewGridItem : BindableItem<FeedGroupAddNewGridItemBinding>() {
        override fun getLayout(): Int = R.layout.feed_group_add_new_grid_item
        override fun initializeViewBinding(view: View) = FeedGroupAddNewGridItemBinding.bind(view)
        override fun bind(viewBinding: FeedGroupAddNewGridItemBinding, position: Int) {
            // this is a static item, nothing to do here
        }
    }

    class FeedGroupAddNewItem : BindableItem<FeedGroupAddNewItemBinding>() {
        override fun getLayout(): Int = R.layout.feed_group_add_new_item
        override fun initializeViewBinding(view: View) = FeedGroupAddNewItemBinding.bind(view)
        override fun bind(viewBinding: FeedGroupAddNewItemBinding, position: Int) {
            // this is a static item, nothing to do here
        }
    }

    class FeedGroupReorderDialog : DialogFragment() {
        private val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

        private var _binding: DialogFeedGroupReorderBinding? = null
        private val binding get() = _binding!!
        private lateinit var viewModel: FeedGroupReorderDialogViewModel

        @State
        @JvmField
        var groupOrderedIdList = ArrayList<Long>()
        private val groupAdapter = GroupieAdapter()
        private val itemTouchHelper = ItemTouchHelper(getItemTouchCallback())

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Icepick.restoreInstanceState(this, savedInstanceState)
            setStyle(STYLE_NO_TITLE, ThemeHelper.getMinWidthDialogTheme(requireContext()))
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            Logd(TAG, "onCreateView")
            return inflater.inflate(R.layout.dialog_feed_group_reorder, container)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _binding = DialogFeedGroupReorderBinding.bind(view)
            viewModel = ViewModelProvider(this).get(FeedGroupReorderDialogViewModel::class.java)
            viewModel.groupsLiveData.observe(viewLifecycleOwner, androidx.lifecycle.Observer(::handleGroups))
            viewModel.dialogEventLiveData.observe(viewLifecycleOwner) {
                when (it) {
                    ProcessingEvent -> disableInput()
                    SuccessEvent -> dismiss()
                }
            }

            binding.feedGroupsList.layoutManager = LinearLayoutManager(requireContext())
            binding.feedGroupsList.adapter = groupAdapter
            itemTouchHelper.attachToRecyclerView(binding.feedGroupsList)

            binding.confirmButton.setOnClickListener {
                viewModel.updateOrder(groupOrderedIdList)
            }
        }

        override fun onDestroyView() {
            _binding = null
            super.onDestroyView()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            Icepick.saveInstanceState(this, outState)
        }

        private fun handleGroups(list: List<FeedGroupEntity>) {
            val groupList: List<FeedGroupEntity>

            if (groupOrderedIdList.isEmpty()) {
                groupList = list
                groupOrderedIdList.addAll(groupList.map { it.uid })
            } else groupList = list.sortedBy { groupOrderedIdList.indexOf(it.uid) }

            groupAdapter.update(groupList.map { FeedGroupReorderItem(it, itemTouchHelper) })
        }

        private fun disableInput() {
            _binding?.confirmButton?.isEnabled = false
            isCancelable = false
        }

        private fun getItemTouchCallback(): SimpleCallback {
            return object : TouchCallback() {
                override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    val sourceIndex = source.bindingAdapterPosition
                    val targetIndex = target.bindingAdapterPosition
                    groupAdapter.notifyItemMoved(sourceIndex, targetIndex)
                    Collections.swap(groupOrderedIdList, sourceIndex, targetIndex)
                    return true
                }
                override fun isLongPressDragEnabled(): Boolean = false
                override fun isItemViewSwipeEnabled(): Boolean = false
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, swipeDir: Int) {}
            }
        }

        data class FeedGroupReorderItem(val groupId: Long = GROUP_ALL_ID, val name: String, val icon: FeedGroupIcon?,
                                        val dragCallback: ItemTouchHelper) : BindableItem<FeedGroupReorderItemBinding>() {
            constructor (feedGroupEntity: FeedGroupEntity, dragCallback: ItemTouchHelper)
                    : this(feedGroupEntity.uid, feedGroupEntity.name, feedGroupEntity.icon, dragCallback)

            override fun getId(): Long {
                return when (groupId) {
                    GROUP_ALL_ID -> super.getId()
                    else -> groupId
                }
            }

            override fun getLayout(): Int = R.layout.feed_group_reorder_item
            override fun bind(viewBinding: FeedGroupReorderItemBinding, position: Int) {
                viewBinding.groupName.text = name
                if (icon != null) viewBinding.groupIcon.setImageResource(icon.getDrawableRes())
            }
            override fun bind(viewHolder: GroupieViewHolder<FeedGroupReorderItemBinding>, position: Int, payloads: MutableList<Any>) {
                super.bind(viewHolder, position, payloads)
                viewHolder.binding.handle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        dragCallback.startDrag(viewHolder)
                        return@setOnTouchListener true
                    }
                    false
                }
            }
            override fun getDragDirs(): Int {
                return UP or DOWN
            }
            override fun initializeViewBinding(view: View) = FeedGroupReorderItemBinding.bind(view)
        }
    }

    class FeedGroupDialog : DialogFragment(), BackPressable {
        private val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())

        private var _feedGroupCreateBinding: DialogFeedGroupCreateBinding? = null
        private val feedGroupCreateBinding get() = _feedGroupCreateBinding!!

        private var _searchLayoutBinding: ToolbarSearchLayoutBinding? = null
        private val searchLayoutBinding get() = _searchLayoutBinding!!

        private lateinit var viewModel: FeedGroupDialogViewModel
        private var groupId: Long = NO_GROUP_SELECTED
        private var groupIcon: FeedGroupIcon? = null
        private var groupSortOrder: Long = -1

        sealed class ScreenState : Serializable {
            data object InitialScreen : ScreenState()
            data object IconPickerScreen : ScreenState()
            data object SubscriptionsPickerScreen : ScreenState()
            data object DeleteScreen : ScreenState()
            fun readResolve(): Any {
                return this
            }
        }

        @State @JvmField
        var selectedIcon: FeedGroupIcon? = null

        @State @JvmField
        var selectedSubscriptions: HashSet<Long> = HashSet()

        @State @JvmField
        var wasSubscriptionSelectionChanged: Boolean = false

        @State @JvmField
        var currentScreen: ScreenState = ScreenState.InitialScreen

        @State @JvmField
        var subscriptionsListState: Parcelable? = null

        @State @JvmField
        var iconsListState: Parcelable? = null

        @State @JvmField
        var wasSearchSubscriptionsVisible = false

        @State @JvmField
        var subscriptionsCurrentSearchQuery = ""

        @State @JvmField
        var subscriptionsShowOnlyUngrouped = false

        private val subscriptionMainSection = Section()
        private val subscriptionEmptyFooter = Section()
        private lateinit var subscriptionGroupAdapter: GroupieAdapter

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            Icepick.restoreInstanceState(this, savedInstanceState)

            setStyle(STYLE_NO_TITLE, ThemeHelper.getMinWidthDialogTheme(requireContext()))
            groupId = arguments?.getLong(KEY_GROUP_ID, NO_GROUP_SELECTED) ?: NO_GROUP_SELECTED
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            Logd(TAG, "onCreateView")
            return inflater.inflate(R.layout.dialog_feed_group_create, container)
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return object : Dialog(requireActivity(), theme) {
                @Deprecated("Deprecated in Java")
                override fun onBackPressed() {
                    if (!this@FeedGroupDialog.onBackPressed()) super.onBackPressed()
                }
            }
        }

        override fun onPause() {
            super.onPause()
            wasSearchSubscriptionsVisible = isSearchVisible()
        }

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)
            iconsListState = feedGroupCreateBinding.iconSelector.layoutManager?.onSaveInstanceState()
            subscriptionsListState = feedGroupCreateBinding.subscriptionsSelectorList.layoutManager?.onSaveInstanceState()
            Icepick.saveInstanceState(this, outState)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            _feedGroupCreateBinding = DialogFeedGroupCreateBinding.bind(view)
            _searchLayoutBinding = feedGroupCreateBinding.subscriptionsHeaderSearchContainer

            viewModel = ViewModelProvider(this,
                FeedGroupDialogViewModel.getFactory(requireContext(), groupId, subscriptionsCurrentSearchQuery, subscriptionsShowOnlyUngrouped))[FeedGroupDialogViewModel::class.java]

            viewModel.groupLiveData.observe(viewLifecycleOwner, androidx.lifecycle.Observer(::handleGroup))
            viewModel.subscriptionsLiveData.observe(viewLifecycleOwner) { setupSubscriptionPicker(it.first, it.second) }
            viewModel.dialogEventLiveData.observe(viewLifecycleOwner) {
                when (it) {
                    DialogEvent.ProcessingEvent -> disableInput()
                    DialogEvent.SuccessEvent -> dismiss()
                }
            }

            subscriptionGroupAdapter = GroupieAdapter().apply {
                add(subscriptionMainSection)
                add(subscriptionEmptyFooter)
                spanCount = 4
            }
            feedGroupCreateBinding.subscriptionsSelectorList.apply {
                // Disable animations, too distracting.
                itemAnimator = null
                adapter = subscriptionGroupAdapter
                layoutManager = GridLayoutManager(requireContext(), subscriptionGroupAdapter.spanCount, RecyclerView.VERTICAL, false).apply {
                    spanSizeLookup = subscriptionGroupAdapter.spanSizeLookup }
            }

            setupIconPicker()
            setupListeners()
            showScreen(currentScreen)

            if (currentScreen == ScreenState.SubscriptionsPickerScreen && wasSearchSubscriptionsVisible) showSearch()
            else if (currentScreen == ScreenState.InitialScreen && groupId == NO_GROUP_SELECTED) showKeyboard()
        }

        override fun onDestroyView() {
            super.onDestroyView()
            feedGroupCreateBinding.subscriptionsSelectorList.adapter = null
            feedGroupCreateBinding.iconSelector.adapter = null
            _feedGroupCreateBinding = null
            _searchLayoutBinding = null
        }

        override fun onBackPressed(): Boolean {
            if (currentScreen is ScreenState.SubscriptionsPickerScreen && isSearchVisible()) {
                hideSearch()
                return true
            } else if (currentScreen !is ScreenState.InitialScreen) {
                showScreen(ScreenState.InitialScreen)
                return true
            }
            return false
        }

        private fun setupListeners() {
            feedGroupCreateBinding.deleteButton.setOnClickListener { showScreen(ScreenState.DeleteScreen) }
            feedGroupCreateBinding.cancelButton.setOnClickListener {
                when (currentScreen) {
                    ScreenState.InitialScreen -> dismiss()
                    else -> showScreen(ScreenState.InitialScreen)
                }
            }
            feedGroupCreateBinding.groupNameInputContainer.error = null
            feedGroupCreateBinding.groupNameInput.doOnTextChanged { text, _, _, _ ->
                if (feedGroupCreateBinding.groupNameInputContainer.isErrorEnabled && !text.isNullOrBlank())
                    feedGroupCreateBinding.groupNameInputContainer.error = null
            }
            feedGroupCreateBinding.confirmButton.setOnClickListener { handlePositiveButton() }
            feedGroupCreateBinding.selectChannelButton.setOnClickListener {
                feedGroupCreateBinding.subscriptionsSelectorList.scrollToPosition(0)
                showScreen(ScreenState.SubscriptionsPickerScreen)
            }

            val headerMenu = feedGroupCreateBinding.subscriptionsHeaderToolbar.menu
            requireActivity().menuInflater.inflate(R.menu.menu_feed_group_dialog, headerMenu)
            headerMenu.findItem(R.id.action_search).setOnMenuItemClickListener {
                showSearch()
                true
            }

            headerMenu.findItem(R.id.feed_group_toggle_show_only_ungrouped_subscriptions).apply {
                isChecked = subscriptionsShowOnlyUngrouped
                setOnMenuItemClickListener {
                    subscriptionsShowOnlyUngrouped = !subscriptionsShowOnlyUngrouped
                    it.isChecked = subscriptionsShowOnlyUngrouped
                    viewModel.toggleShowOnlyUngrouped(subscriptionsShowOnlyUngrouped)
                    true
                }
            }

            searchLayoutBinding.toolbarSearchClear.setOnClickListener {
                if (searchLayoutBinding.toolbarSearchEditText.text.isNullOrEmpty()) {
                    hideSearch()
                    return@setOnClickListener
                }
                resetSearch()
                showKeyboardSearch()
            }
            searchLayoutBinding.toolbarSearchEditText.setOnClickListener {
                if (DeviceUtils.isTv(requireContext())) showKeyboardSearch()
            }
            searchLayoutBinding.toolbarSearchEditText.doOnTextChanged { _, _, _, _ ->
                val newQuery: String = searchLayoutBinding.toolbarSearchEditText.text.toString()
                subscriptionsCurrentSearchQuery = newQuery
                viewModel.filterSubscriptionsBy(newQuery)
            }
            subscriptionGroupAdapter.setOnItemClickListener(subscriptionPickerItemListener)
        }

        private fun handlePositiveButton() =
            when {
                currentScreen is ScreenState.InitialScreen -> handlePositiveButtonInitialScreen()
                currentScreen is ScreenState.DeleteScreen -> viewModel.deleteGroup()
                currentScreen is ScreenState.SubscriptionsPickerScreen && isSearchVisible() -> hideSearch()
                else -> showScreen(ScreenState.InitialScreen)
            }

        private fun handlePositiveButtonInitialScreen() {
            val name = feedGroupCreateBinding.groupNameInput.text.toString().trim()
            val icon = selectedIcon ?: groupIcon ?: FeedGroupIcon.ALL

            if (name.isBlank()) {
                feedGroupCreateBinding.groupNameInputContainer.error = getString(R.string.feed_group_dialog_empty_name)
                feedGroupCreateBinding.groupNameInput.text = null
                feedGroupCreateBinding.groupNameInput.requestFocus()
                return
            } else feedGroupCreateBinding.groupNameInputContainer.error = null
            if (selectedSubscriptions.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.feed_group_dialog_empty_selection), Toast.LENGTH_SHORT).show()
                return
            }
            when (groupId) {
                NO_GROUP_SELECTED -> viewModel.createGroup(name, icon, selectedSubscriptions)
                else -> viewModel.updateGroup(name, icon, selectedSubscriptions, groupSortOrder)
            }
        }

        private fun handleGroup(feedGroupEntity: FeedGroupEntity? = null) {
            val icon = feedGroupEntity?.icon ?: FeedGroupIcon.ALL
            val name = feedGroupEntity?.name ?: ""
            groupIcon = feedGroupEntity?.icon
            groupSortOrder = feedGroupEntity?.sortOrder ?: -1
            val feedGroupIcon = if (selectedIcon == null) icon else selectedIcon!!
            feedGroupCreateBinding.iconPreview.setImageResource(feedGroupIcon.getDrawableRes())
            if (feedGroupCreateBinding.groupNameInput.text.isNullOrBlank()) feedGroupCreateBinding.groupNameInput.setText(name)
        }

        private val subscriptionPickerItemListener =
            OnItemClickListener { item, view ->
                if (item is PickerSubscriptionItem) {
                    val subscriptionId = item.subscriptionEntity.uid
                    wasSubscriptionSelectionChanged = true

                    val isSelected = if (this.selectedSubscriptions.contains(subscriptionId)) {
                        this.selectedSubscriptions.remove(subscriptionId)
                        false
                    } else {
                        this.selectedSubscriptions.add(subscriptionId)
                        true
                    }
                    item.updateSelected(view, isSelected)
                    updateSubscriptionSelectedCount()
                }
            }

        private fun setupSubscriptionPicker(subscriptions: List<PickerSubscriptionItem>, selectedSubscriptions: Set<Long>) {
            if (!wasSubscriptionSelectionChanged) this.selectedSubscriptions.addAll(selectedSubscriptions)
            updateSubscriptionSelectedCount()
            if (subscriptions.isEmpty()) {
                subscriptionEmptyFooter.clear()
                subscriptionEmptyFooter.add(ImportSubscriptionsHintPlaceholderItem())
            } else subscriptionEmptyFooter.clear()

            subscriptions.forEach {
                it.isSelected = this@FeedGroupDialog.selectedSubscriptions.contains(it.subscriptionEntity.uid)
            }

            subscriptionMainSection.update(subscriptions, false)
            if (subscriptionsListState != null) {
                feedGroupCreateBinding.subscriptionsSelectorList.layoutManager?.onRestoreInstanceState(subscriptionsListState)
                subscriptionsListState = null
            } else feedGroupCreateBinding.subscriptionsSelectorList.scrollToPosition(0)
        }

        private fun updateSubscriptionSelectedCount() {
            val selectedCount = this.selectedSubscriptions.size
            val selectedCountText = resources.getQuantityString(
                R.plurals.feed_group_dialog_selection_count,
                selectedCount,
                selectedCount,
            )
            feedGroupCreateBinding.selectedSubscriptionCountView.text = selectedCountText
            feedGroupCreateBinding.subscriptionsHeaderInfo.text = selectedCountText
        }

        private fun setupIconPicker() {
            val groupAdapter = GroupieAdapter()
            groupAdapter.addAll(FeedGroupIcon.entries.map { PickerIconItem(it) })

            feedGroupCreateBinding.iconSelector.apply {
                layoutManager = GridLayoutManager(requireContext(), 7, RecyclerView.VERTICAL, false)
                adapter = groupAdapter
                if (iconsListState != null) {
                    layoutManager?.onRestoreInstanceState(iconsListState)
                    iconsListState = null
                }
            }

            groupAdapter.setOnItemClickListener { item, _ ->
                when (item) {
                    is PickerIconItem -> {
                        selectedIcon = item.icon
                        feedGroupCreateBinding.iconPreview.setImageResource(item.iconRes)
                        showScreen(ScreenState.InitialScreen)
                    }
                }
            }
            feedGroupCreateBinding.iconPreview.setOnClickListener {
                feedGroupCreateBinding.iconSelector.scrollToPosition(0)
                showScreen(ScreenState.IconPickerScreen)
            }

            if (groupId == NO_GROUP_SELECTED) {
                val icon = selectedIcon ?: FeedGroupIcon.ALL
                feedGroupCreateBinding.iconPreview.setImageResource(icon.getDrawableRes())
            }
        }

        private fun showScreen(screen: ScreenState) {
            currentScreen = screen

            feedGroupCreateBinding.optionsRoot.onlyVisibleIn(ScreenState.InitialScreen)
            feedGroupCreateBinding.iconSelector.onlyVisibleIn(ScreenState.IconPickerScreen)
            feedGroupCreateBinding.subscriptionsSelector.onlyVisibleIn(ScreenState.SubscriptionsPickerScreen)
            feedGroupCreateBinding.deleteScreenMessage.onlyVisibleIn(ScreenState.DeleteScreen)
            feedGroupCreateBinding.separator.onlyVisibleIn(ScreenState.SubscriptionsPickerScreen,
                ScreenState.IconPickerScreen)
            feedGroupCreateBinding.cancelButton.onlyVisibleIn(ScreenState.InitialScreen, ScreenState.DeleteScreen)
            feedGroupCreateBinding.confirmButton.setText(
                when {
                    currentScreen == ScreenState.InitialScreen && groupId == NO_GROUP_SELECTED -> R.string.create
                    else -> R.string.ok
                },
            )
            feedGroupCreateBinding.deleteButton.isGone = currentScreen != ScreenState.InitialScreen || groupId == NO_GROUP_SELECTED
            hideKeyboard()
            hideSearch()
        }

        private fun View.onlyVisibleIn(vararg screens: ScreenState) {
            isVisible = currentScreen in screens
        }

        private fun isSearchVisible() = _searchLayoutBinding?.root?.visibility == View.VISIBLE

        private fun resetSearch() {
            searchLayoutBinding.toolbarSearchEditText.setText("")
            subscriptionsCurrentSearchQuery = ""
            viewModel.clearSubscriptionsFilter()
        }

        private fun hideSearch() {
            resetSearch()
            searchLayoutBinding.root.visibility = View.GONE
            feedGroupCreateBinding.subscriptionsHeaderInfoContainer.visibility = View.VISIBLE
            feedGroupCreateBinding.subscriptionsHeaderToolbar.menu.findItem(R.id.action_search).isVisible = true
            hideKeyboardSearch()
        }

        private fun showSearch() {
            searchLayoutBinding.root.visibility = View.VISIBLE
            feedGroupCreateBinding.subscriptionsHeaderInfoContainer.visibility = View.GONE
            feedGroupCreateBinding.subscriptionsHeaderToolbar.menu.findItem(R.id.action_search).isVisible = false
            showKeyboardSearch()
        }

        private val inputMethodManager by lazy {
            requireActivity().getSystemService<InputMethodManager>()!!
        }

        private fun showKeyboardSearch() {
            if (searchLayoutBinding.toolbarSearchEditText.requestFocus())
                inputMethodManager.showSoftInput(searchLayoutBinding.toolbarSearchEditText, InputMethodManager.SHOW_IMPLICIT)
        }

        private fun hideKeyboardSearch() {
            inputMethodManager.hideSoftInputFromWindow(searchLayoutBinding.toolbarSearchEditText.windowToken, InputMethodManager.RESULT_UNCHANGED_SHOWN)
            searchLayoutBinding.toolbarSearchEditText.clearFocus()
        }

        private fun showKeyboard() {
            if (feedGroupCreateBinding.groupNameInput.requestFocus())
                inputMethodManager.showSoftInput(feedGroupCreateBinding.groupNameInput, InputMethodManager.SHOW_IMPLICIT)
        }

        private fun hideKeyboard() {
            inputMethodManager.hideSoftInputFromWindow(feedGroupCreateBinding.groupNameInput.windowToken, InputMethodManager.RESULT_UNCHANGED_SHOWN)
            feedGroupCreateBinding.groupNameInput.clearFocus()
        }

        private fun disableInput() {
            _feedGroupCreateBinding?.deleteButton?.isEnabled = false
            _feedGroupCreateBinding?.confirmButton?.isEnabled = false
            _feedGroupCreateBinding?.cancelButton?.isEnabled = false
            isCancelable = false
            hideKeyboard()
        }

        class PickerIconItem(val icon: FeedGroupIcon) : BindableItem<PickerIconItemBinding>() {
            @DrawableRes
            val iconRes: Int = icon.getDrawableRes()
            override fun getLayout(): Int = R.layout.picker_icon_item
            override fun bind(viewBinding: PickerIconItemBinding, position: Int) {
                viewBinding.iconView.setImageResource(iconRes)
            }
            override fun initializeViewBinding(view: View) = PickerIconItemBinding.bind(view)
        }

        companion object {
            private const val KEY_GROUP_ID = "KEY_GROUP_ID"
            private const val NO_GROUP_SELECTED = -1L
            fun newInstance(groupId: Long = NO_GROUP_SELECTED): FeedGroupDialog {
                val dialog = FeedGroupDialog()
                dialog.arguments = bundleOf(KEY_GROUP_ID to groupId)
                return dialog
            }
        }
    }

    /**
     * When there are no subscriptions, show a hint to the user about how to import subscriptions
     */
    class ImportSubscriptionsHintPlaceholderItem : BindableItem<ListEmptyViewBinding>() {
        override fun getLayout(): Int = R.layout.list_empty_view_subscriptions

        override fun bind(viewBinding: ListEmptyViewBinding, position: Int) {}override fun getSpanSize(spanCount: Int, position: Int): Int = spanCount

        override fun initializeViewBinding(view: View) = ListEmptyViewBinding.bind(view)
    }

    companion object {
//        const val JSON_MIME_TYPE = "application/json"
        const val JSON_MIME_TYPE = "*/*"    // TODO: some file pickers don't recognize this
    }
}
