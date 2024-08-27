package ac.mdiq.vista.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import ac.mdiq.vista.database.VoiVistaDatabase.getInstance
import ac.mdiq.vista.R
import ac.mdiq.vista.database.LocalItem
import ac.mdiq.vista.database.playlist.PlaylistDuplicatesEntry
import ac.mdiq.vista.database.playlist.model.PlaylistEntity
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.ui.adapter.LocalItemListAdapter
import ac.mdiq.vista.manager.LocalPlaylistManager
import ac.mdiq.vista.util.Logd

class PlaylistAppendDialog : PlaylistDialog() {
    private var playlistRecyclerView: RecyclerView? = null
    private var playlistAdapter: LocalItemListAdapter? = null
    private var playlistDuplicateIndicator: TextView? = null

    private val playlistDisposables = CompositeDisposable()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.dialog_playlists, container)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val playlistManager = LocalPlaylistManager(getInstance(requireContext()))

        playlistAdapter = LocalItemListAdapter(activity)
        playlistAdapter!!.setSelectedListener { selectedItem: LocalItem? ->
            val entities = streamEntities
            if (selectedItem is PlaylistDuplicatesEntry && entities != null) onPlaylistSelected(playlistManager, selectedItem, entities)
        }

        playlistRecyclerView = view.findViewById(R.id.playlist_list)
        playlistRecyclerView?.layoutManager = LinearLayoutManager(requireContext())
        playlistRecyclerView?.adapter = playlistAdapter

        playlistDuplicateIndicator = view.findViewById(R.id.playlist_duplicate)

        val newPlaylistButton = view.findViewById<View>(R.id.newPlaylist)
        newPlaylistButton.setOnClickListener { openCreatePlaylistDialog() }

        playlistDisposables.add(playlistManager
            .getPlaylistDuplicates(streamEntities!![0].url)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { playlists: List<PlaylistDuplicatesEntry> -> this.onPlaylistsReceived(playlists) })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playlistDisposables.dispose()
        if (playlistAdapter != null) playlistAdapter!!.unsetSelectedListener()
        playlistDisposables.clear()
        playlistRecyclerView = null
        playlistAdapter = null
    }

    /** Display create playlist dialog.  */
    private fun openCreatePlaylistDialog() {
        if (streamEntities == null || !isAdded) return
        val playlistCreationDialog = PlaylistCreationDialog.newInstance(streamEntities)
        // Move the dismissListener to the new dialog.
        playlistCreationDialog.onDismissListener = this.onDismissListener
        this.onDismissListener = null
        playlistCreationDialog.show(parentFragmentManager, TAG)
        requireDialog().dismiss()
    }

    private fun onPlaylistsReceived(playlists: List<PlaylistDuplicatesEntry>) {
        if (playlistAdapter != null && playlistRecyclerView != null && playlistDuplicateIndicator != null) {
            playlistAdapter!!.clearStreamItemList()
            playlistAdapter!!.addItems(playlists)
            playlistRecyclerView!!.visibility = View.VISIBLE
            playlistDuplicateIndicator!!.visibility = if (anyPlaylistContainsDuplicates(playlists)) View.VISIBLE else View.GONE
        }
    }

    private fun anyPlaylistContainsDuplicates(playlists: List<PlaylistDuplicatesEntry>): Boolean {
        return playlists.stream().anyMatch { playlist: PlaylistDuplicatesEntry -> playlist.timesStreamIsContained > 0 }
    }

    private fun onPlaylistSelected(manager: LocalPlaylistManager, playlist: PlaylistDuplicatesEntry, streams: List<StreamEntity>) {
        val toastText = if (playlist.timesStreamIsContained > 0) getString(R.string.playlist_add_stream_success_duplicate,
                playlist.timesStreamIsContained) else getString(R.string.playlist_add_stream_success)
        val successToast = Toast.makeText(context, toastText, Toast.LENGTH_SHORT)

        playlistDisposables.add(manager.appendToPlaylist(playlist.uid, streams)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                successToast.show()
                if (playlist.thumbnailUrl == PlaylistEntity.DEFAULT_THUMBNAIL) {
                    playlistDisposables.add(manager.changePlaylistThumbnail(playlist.uid, streams[0].uid, false)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { successToast.show() })
                }
            })
        requireDialog().dismiss()
    }

    companion object {
        private const val TAG: String = "PlaylistAppendDialog"

        /**
         * Create a new instance of [PlaylistAppendDialog].
         *
         * @param streamEntities    a list of [StreamEntity] to be added to playlists
         * @return a new instance of [PlaylistAppendDialog]
         */
        fun newInstance(streamEntities: List<StreamEntity>?): PlaylistAppendDialog {
            val dialog = PlaylistAppendDialog()
            dialog.streamEntities = streamEntities
            return dialog
        }
    }
}
