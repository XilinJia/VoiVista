package ac.mdiq.vista.ui.fragments

import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import icepick.State
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import ac.mdiq.vista.database.VoiVistaDatabase.getInstance
import ac.mdiq.vista.R
import ac.mdiq.vista.database.LocalItem
import ac.mdiq.vista.database.playlist.PlaylistLocalItem
import ac.mdiq.vista.database.playlist.PlaylistMetadataEntry
import ac.mdiq.vista.database.playlist.model.PlaylistRemoteEntity
import ac.mdiq.vista.databinding.DialogEditTextBinding
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.manager.LocalPlaylistManager
import ac.mdiq.vista.manager.RemotePlaylistManager
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.ui.util.NavigationHelper.openLocalPlaylistFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openPlaylistFragment
import ac.mdiq.vista.ui.gesture.OnClickGesture

class BookmarkFragment : BaseLocalListFragment<List<PlaylistLocalItem>, Void>() {
    @JvmField
    @State
    var itemsListState: Parcelable? = null

    private var databaseSubscription: Subscription? = null
    private var disposables: CompositeDisposable? = CompositeDisposable()
    private var localPlaylistManager: LocalPlaylistManager? = null
    private var remotePlaylistManager: RemotePlaylistManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (activity == null) return
        val database = getInstance(requireActivity())
        localPlaylistManager = LocalPlaylistManager(database)
        remotePlaylistManager = RemotePlaylistManager(database)
        disposables = CompositeDisposable()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        if (!useAsFrontPage) setTitle(requireActivity().getString(R.string.tab_bookmarks))
        return inflater.inflate(R.layout.fragment_bookmarks, container, false)
    }

    override fun onResume() {
        super.onResume()
        if (activity != null) setTitle(requireActivity().getString(R.string.tab_bookmarks))
    }

    override fun initListeners() {
        super.initListeners()

        itemListAdapter?.setSelectedListener(object : OnClickGesture<LocalItem> {
            override fun selected(selectedItem: LocalItem) {
                val fragmentManager = fM!!
                when (selectedItem) {
                    is PlaylistMetadataEntry -> openLocalPlaylistFragment(fragmentManager, selectedItem.uid, selectedItem.name)
                    is PlaylistRemoteEntity -> openPlaylistFragment(fragmentManager, selectedItem.serviceId, selectedItem.url, selectedItem.name ?: "")
                }
            }
            override fun held(selectedItem: LocalItem) {
                if (selectedItem is PlaylistMetadataEntry) showLocalDialog(selectedItem)
                else if (selectedItem is PlaylistRemoteEntity) showRemoteDeleteDialog(selectedItem)
            }
        })
    }

    override fun startLoading(forceLoad: Boolean) {
        super.startLoading(forceLoad)
        Flowable.combineLatest(localPlaylistManager!!.playlists,
            remotePlaylistManager!!.playlists) { localPlaylists: List<PlaylistMetadataEntry>, remotePlaylists: List<PlaylistRemoteEntity> ->
            PlaylistLocalItem.merge(localPlaylists, remotePlaylists) }
            .onBackpressureLatest()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(playlistsSubscriber)
    }

    override fun onPause() {
        super.onPause()
        itemsListState = itemsList?.layoutManager?.onSaveInstanceState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposables?.clear()
        databaseSubscription?.cancel()
        databaseSubscription = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables?.dispose()
        disposables = null
        localPlaylistManager = null
        remotePlaylistManager = null
        itemsListState = null
    }

    private val playlistsSubscriber: Subscriber<List<PlaylistLocalItem>>
        get() = object : Subscriber<List<PlaylistLocalItem>> {
            override fun onSubscribe(s: Subscription) {
                showLoading()
                databaseSubscription?.cancel()
                databaseSubscription = s
                databaseSubscription!!.request(1)
            }
            override fun onNext(subscriptions: List<PlaylistLocalItem>) {
                handleResult(subscriptions)
                databaseSubscription?.request(1)
            }
            override fun onError(exception: Throwable) {
                showError(ErrorInfo(exception, UserAction.REQUESTED_BOOKMARK, "Loading playlists"))
            }
            override fun onComplete() {}
        }

    override fun handleResult(result: List<PlaylistLocalItem>) {
        super.handleResult(result)
        itemListAdapter?.clearStreamItemList()

        if (result.isEmpty()) {
            showEmptyState()
            return
        }
        itemListAdapter?.addItems(result)
        if (itemsListState != null) {
            itemsList?.layoutManager?.onRestoreInstanceState(itemsListState)
            itemsListState = null
        }
        hideLoading()
    }

    override fun resetFragment() {
        super.resetFragment()
        disposables?.clear()
    }

    private fun showRemoteDeleteDialog(item: PlaylistRemoteEntity) {
        showDeleteDialog(item.name?:"", remotePlaylistManager!!.deletePlaylist(item.uid))
    }

    private fun showLocalDialog(selectedItem: PlaylistMetadataEntry) {
        val rename = getString(R.string.rename)
        val delete = getString(R.string.delete)
        val unsetThumbnail = getString(R.string.unset_playlist_thumbnail)
        val isThumbnailPermanent = localPlaylistManager!!.getIsPlaylistThumbnailPermanent(selectedItem.uid)
        val items = ArrayList<String>()
        items.add(rename)
        items.add(delete)
        if (isThumbnailPermanent) items.add(unsetThumbnail)
        val action = DialogInterface.OnClickListener { _: DialogInterface?, index: Int ->
            when {
                items[index] == rename -> showRenameDialog(selectedItem)
                items[index] == delete -> showDeleteDialog(selectedItem.name, localPlaylistManager!!.deletePlaylist(selectedItem.uid))
                isThumbnailPermanent && items[index] == unsetThumbnail -> {
                    val thumbnailStreamId = localPlaylistManager!!.getAutomaticPlaylistThumbnailStreamId(selectedItem.uid)
                    localPlaylistManager!!
                        .changePlaylistThumbnail(selectedItem.uid, thumbnailStreamId, false)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe()
                }
            }
        }
        AlertDialog.Builder(requireActivity())
            .setItems(items.toTypedArray<String>(), action)
            .show()
    }

    private fun showRenameDialog(selectedItem: PlaylistMetadataEntry) {
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater)
        dialogBinding.dialogEditText.setHint(R.string.name)
        dialogBinding.dialogEditText.inputType = InputType.TYPE_CLASS_TEXT
        dialogBinding.dialogEditText.setText(selectedItem.name)

        AlertDialog.Builder(requireActivity())
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.rename_playlist) { _: DialogInterface?, _: Int ->
                changeLocalPlaylistName(
                    selectedItem.uid,
                    dialogBinding.dialogEditText.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(name: String, deleteReactor: Single<Int>) {
        if (activity == null || disposables == null) return
        AlertDialog.Builder(requireActivity())
            .setTitle(name)
            .setMessage(R.string.delete_playlist_prompt)
            .setCancelable(true)
            .setPositiveButton(R.string.delete) { _: DialogInterface?, _: Int ->
                disposables!!.add(deleteReactor
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ }, { throwable: Throwable? ->
                        showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Deleting playlist"))
                    }))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun changeLocalPlaylistName(id: Long, name: String) {
        if (localPlaylistManager == null) return
        Logd(TAG, "Updating playlist id=[$id] with new name=[$name] items")

        localPlaylistManager!!.renamePlaylist(id, name)
        val disposable = localPlaylistManager!!.renamePlaylist(id, name)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ _: Int? -> }, { throwable: Throwable? ->
                showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Changing playlist name"))
            })
        disposables!!.add(disposable)
    }
}
