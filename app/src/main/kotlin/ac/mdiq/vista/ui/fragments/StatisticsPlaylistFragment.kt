package ac.mdiq.vista.ui.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Parcelable
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.google.android.material.snackbar.Snackbar
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import ac.mdiq.vista.R
import ac.mdiq.vista.database.LocalItem
import ac.mdiq.vista.database.stream.StreamStatisticsEntry
import ac.mdiq.vista.databinding.PlaylistControlBinding
import ac.mdiq.vista.databinding.StatisticPlaylistControlBinding
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.player.playqueue.SinglePlayQueue
import ac.mdiq.vista.settings.fragment.HistorySettingsFragment.Companion.openDeleteWatchHistoryDialog
import ac.mdiq.vista.ui.dialog.InfoItemDialog
import ac.mdiq.vista.ui.dialog.InfoItemDialog.Builder.Companion.reportErrorDuringInitialization
import ac.mdiq.vista.ui.dialog.StreamDialogDefaultEntry
import ac.mdiq.vista.ui.holder.PlaylistControlViewHolder
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.ui.util.NavigationHelper.openVideoDetailFragment
import ac.mdiq.vista.ui.util.NavigationHelper.playOnBackgroundPlayer
import ac.mdiq.vista.ui.gesture.OnClickGesture
import ac.mdiq.vista.ui.gesture.PlayButtonHelper.initPlaylistControlClickListener
import java.util.*
import kotlin.math.max

class StatisticsPlaylistFragment : BaseLocalListFragment<List<StreamStatisticsEntry?>?, Void?>(), PlaylistControlViewHolder {

    private val disposables = CompositeDisposable()

    @JvmField
    @State
    var itemsListState: Parcelable? = null
    private var sortMode = StatisticSortMode.LAST_PLAYED

    private var headerBinding: StatisticPlaylistControlBinding? = null
    private var playlistControlBinding: PlaylistControlBinding? = null

    /* Used for independent events */
    private var databaseSubscription: Subscription? = null
    private var recordManager: HistoryRecordManager? = null

    private fun processResult(results: List<StreamStatisticsEntry>): List<StreamStatisticsEntry> {
        val comparator = when (sortMode) {
            StatisticSortMode.LAST_PLAYED -> Comparator.comparing(StreamStatisticsEntry::latestAccessDate)
            StatisticSortMode.MOST_PLAYED -> Comparator.comparingLong(StreamStatisticsEntry::watchCount)
        }
        Collections.sort(results, comparator.reversed())
        return results
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordManager = HistoryRecordManager(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_playlist, container, false)
    }

    override fun onResume() {
        super.onResume()
        if (activity != null) setTitle(requireActivity().getString(R.string.title_activity_history))
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_history, menu)
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        if (!useAsFrontPage) setTitle(getString(R.string.title_last_played))
    }

    override val listHeader: ViewBinding?
        get() {
            headerBinding = StatisticPlaylistControlBinding.inflate(requireActivity().layoutInflater, itemsList, false)
            playlistControlBinding = headerBinding!!.playlistControl
            return headerBinding
        }

    override fun initListeners() {
        super.initListeners()

        itemListAdapter?.setSelectedListener(object : OnClickGesture<LocalItem> {
            override fun selected(selectedItem: LocalItem) {
                if (selectedItem is StreamStatisticsEntry) {
                    val item = selectedItem.streamEntity
                    openVideoDetailFragment(requireContext(), fM!!, item.serviceId, item.url, item.title, null, false)
                }
            }
            override fun held(selectedItem: LocalItem) {
                if (selectedItem is StreamStatisticsEntry) showInfoItemDialog(selectedItem)
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_history_clear) openDeleteWatchHistoryDialog(requireContext(), recordManager, disposables)
        else return super.onOptionsItemSelected(item)
        return true
    }

    override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)
        recordManager!!.streamStatistics
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(historyObserver)
    }

    override fun onPause() {
        super.onPause()
        itemsListState = itemsList?.layoutManager?.onSaveInstanceState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        itemListAdapter?.unsetSelectedListener()
        headerBinding = null
        playlistControlBinding = null
        if (databaseSubscription != null) databaseSubscription!!.cancel()
        databaseSubscription = null
    }

    override fun onDestroy() {
        super.onDestroy()
        recordManager = null
        itemsListState = null
    }

    private val historyObserver: Subscriber<List<StreamStatisticsEntry?>?>
        get() = object : Subscriber<List<StreamStatisticsEntry?>?> {
            override fun onSubscribe(s: Subscription) {
                showLoading()
                if (databaseSubscription != null) databaseSubscription!!.cancel()
                databaseSubscription = s
                databaseSubscription!!.request(1)
            }
            override fun onNext(streams: List<StreamStatisticsEntry?>?) {
                handleResult(streams)
                if (databaseSubscription != null) databaseSubscription!!.request(1)
            }
            override fun onError(exception: Throwable) {
                showError(ErrorInfo(exception, UserAction.SOMETHING_ELSE, "History Statistics"))
            }
            override fun onComplete() {}
        }

    @SuppressLint("UseRequireInsteadOfGet")
    override fun handleResult(result: List<StreamStatisticsEntry?>?) {
        super.handleResult(result)
        if (itemListAdapter == null) return

        playlistControlBinding!!.root.visibility = View.VISIBLE
        itemListAdapter?.clearStreamItemList()
        if (result.isNullOrEmpty()) {
            showEmptyState()
            return
        }

        itemListAdapter?.addItems(processResult(result.filterNotNull()))
        if (itemsListState != null && itemsList?.layoutManager != null) {
            itemsList!!.layoutManager!!.onRestoreInstanceState(itemsListState)
            itemsListState = null
        }

        initPlaylistControlClickListener(activity!!, playlistControlBinding!!, this)
        headerBinding!!.sortButton.setOnClickListener { toggleSortMode() }
        hideLoading()
    }

    override fun resetFragment() {
        super.resetFragment()
        if (databaseSubscription != null) databaseSubscription!!.cancel()
    }

    private fun toggleSortMode() {
        if (sortMode == StatisticSortMode.LAST_PLAYED) {
            sortMode = StatisticSortMode.MOST_PLAYED
            setTitle(getString(R.string.title_most_played))
            headerBinding!!.sortButtonIcon.setImageResource(R.drawable.ic_history)
            headerBinding!!.sortButtonText.setText(R.string.title_last_played)
        } else {
            sortMode = StatisticSortMode.LAST_PLAYED
            setTitle(getString(R.string.title_last_played))
            headerBinding!!.sortButtonIcon.setImageResource(R.drawable.ic_filter_list)
            headerBinding!!.sortButtonText.setText(R.string.title_most_played)
        }
        startLoading(true)
    }

    private fun getPlayQueueStartingAt(infoItem: StreamStatisticsEntry): PlayQueue {
        if (itemListAdapter == null) return getPlayQueue(0)
        return getPlayQueue(max(itemListAdapter!!.itemsList.indexOf(infoItem).toDouble(), 0.0).toInt())
    }

    private fun showInfoItemDialog(item: StreamStatisticsEntry) {
        val context = context
        val infoItem = item.toStreamInfoItem()

        try {
            val dialogBuilder = InfoItemDialog.Builder(requireActivity(), context!!, this, infoItem)

            // set entries in the middle; the others are added automatically
            dialogBuilder
                .addEntry(StreamDialogDefaultEntry.DELETE)
                .setAction(StreamDialogDefaultEntry.DELETE) { _: Fragment?, _: StreamInfoItem? ->
                    if (itemListAdapter != null) deleteEntry(max(itemListAdapter!!.itemsList.indexOf(item).toDouble(), 0.0).toInt()) }
                .setAction(StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND) { _: Fragment?, _: StreamInfoItem? ->
                    playOnBackgroundPlayer(context, getPlayQueueStartingAt(item), true)
                }
                .create()
                .show()
        } catch (e: IllegalArgumentException) {
            reportErrorDuringInitialization(e, infoItem)
        }
    }

    private fun deleteEntry(index: Int) {
        if (itemListAdapter == null) return
        val infoItem = itemListAdapter!!.itemsList[index]
        if (infoItem is StreamStatisticsEntry) {
            val onDelete = recordManager!!
                .deleteStreamHistoryAndState(infoItem.streamId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({
                    if (view != null) Snackbar.make(requireView(), R.string.one_item_deleted, Snackbar.LENGTH_SHORT).show()
                    else Toast.makeText(context, R.string.one_item_deleted, Toast.LENGTH_SHORT).show()
                }, { throwable: Throwable? -> showSnackBarError(ErrorInfo(throwable!!, UserAction.DELETE_FROM_HISTORY, "Deleting item")) })
            disposables.add(onDelete)
        }
    }

    override val playQueue: PlayQueue
        get() = getPlayQueue(0)

    private fun getPlayQueue(index: Int): PlayQueue {
        if (itemListAdapter == null) return SinglePlayQueue(emptyList(), 0)

        val infoItems: List<LocalItem> = itemListAdapter!!.itemsList
        val streamInfoItems: MutableList<StreamInfoItem> = ArrayList(infoItems.size)
        for (item in infoItems) {
            if (item is StreamStatisticsEntry) streamInfoItems.add(item.toStreamInfoItem())
        }
        return SinglePlayQueue(streamInfoItems, index)
    }

    private enum class StatisticSortMode {
        LAST_PLAYED,
        MOST_PLAYED,
    }
}
