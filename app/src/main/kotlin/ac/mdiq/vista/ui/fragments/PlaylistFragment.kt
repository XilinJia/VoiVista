package ac.mdiq.vista.ui.fragments

import ac.mdiq.vista.R
import ac.mdiq.vista.database.VoiVistaDatabase.getInstance
import ac.mdiq.vista.database.playlist.model.PlaylistRemoteEntity
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.databinding.PlaylistControlBinding
import ac.mdiq.vista.databinding.PlaylistHeaderBinding
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showUiErrorSnackbar
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.extractor.ListExtractor.InfoItemsPage
import ac.mdiq.vista.extractor.ServiceList
import ac.mdiq.vista.extractor.playlist.PlaylistInfo
import ac.mdiq.vista.extractor.services.youtube.YoutubeParsingHelper
import ac.mdiq.vista.extractor.stream.Description
import ac.mdiq.vista.extractor.stream.StreamInfoItem
import ac.mdiq.vista.manager.RemotePlaylistManager
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.player.playqueue.PlayQueueItem
import ac.mdiq.vista.player.playqueue.PlaylistPlayQueue
import ac.mdiq.vista.ui.dialog.InfoItemDialog
import ac.mdiq.vista.ui.dialog.InfoItemDialog.Builder.Companion.reportErrorDuringInitialization
import ac.mdiq.vista.ui.dialog.PlaylistDialog
import ac.mdiq.vista.ui.dialog.StreamDialogDefaultEntry
import ac.mdiq.vista.ui.gesture.PlayButtonHelper.initPlaylistControlClickListener
import ac.mdiq.vista.ui.holder.PlaylistControlViewHolder
import ac.mdiq.vista.ui.util.NavigationHelper.openChannelFragment
import ac.mdiq.vista.ui.util.NavigationHelper.openSettings
import ac.mdiq.vista.ui.util.NavigationHelper.playOnBackgroundPlayer
import ac.mdiq.vista.ui.util.ShareUtils.openUrlInBrowser
import ac.mdiq.vista.ui.util.ShareUtils.shareText
import ac.mdiq.vista.ui.util.TextEllipsizer
import ac.mdiq.vista.ui.util.ktx.animate
import ac.mdiq.vista.ui.util.ktx.animateHideRecyclerViewAllowingScrolling
import ac.mdiq.vista.util.ExtractorHelper.getMorePlaylistItems
import ac.mdiq.vista.util.ExtractorHelper.getPlaylistInfo
import ac.mdiq.vista.util.Localization.localizeStreamCount
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.ServiceHelper.getServiceById
import ac.mdiq.vista.util.image.PicassoHelper.cancelTag
import ac.mdiq.vista.util.image.PicassoHelper.loadAvatar
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import java.util.stream.Collectors
import kotlin.math.max

class PlaylistFragment : BaseListInfoFragment<StreamInfoItem, PlaylistInfo>(UserAction.REQUESTED_PLAYLIST),
    PlaylistControlViewHolder {

    private var disposables: CompositeDisposable? = null
    private var bookmarkReactor: Subscription? = null
    private var isBookmarkButtonReady: AtomicBoolean? = null

    private var remotePlaylistManager: RemotePlaylistManager? = null
    private var playlistEntity: PlaylistRemoteEntity? = null

    private var headerBinding: PlaylistHeaderBinding? = null
    private var playlistControlBinding: PlaylistControlBinding? = null

    private var playlistBookmarkButton: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disposables = CompositeDisposable()
        isBookmarkButtonReady = AtomicBoolean(false)
        remotePlaylistManager = RemotePlaylistManager(getInstance(requireContext()))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Logd(TAG, "onCreateView")
        return inflater.inflate(R.layout.fragment_playlist, container, false)
    }

    override val listHeaderSupplier: Supplier<View>
        get() {
            headerBinding = PlaylistHeaderBinding.inflate(requireActivity().layoutInflater, itemsList, false)
            playlistControlBinding = headerBinding!!.playlistControl

            return Supplier { headerBinding!!.root }
        }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)

        // Is mini variant still relevant?
        // Only the remote playlist screen uses it now
        infoListAdapter!!.setUseMiniVariant(true)
    }

    private fun getPlayQueueStartingAt(infoItem: StreamInfoItem): PlayQueue {
        return getPlayQueue(max(infoListAdapter!!.itemsList.indexOf(infoItem).toDouble(), 0.0).toInt())
    }

    override fun showInfoItemDialog(item: StreamInfoItem?) {
        val context = context
        try {
            val dialogBuilder = InfoItemDialog.Builder(requireActivity(), context!!, this, item!!)
            dialogBuilder.setAction(StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND) { _: Fragment?, infoItem: StreamInfoItem? ->
                if (infoItem != null) playOnBackgroundPlayer(context, getPlayQueueStartingAt(infoItem), true)
            }
                .create()
                .show()
        } catch (e: IllegalArgumentException) {
            reportErrorDuringInitialization(e, item!!)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        Logd(TAG, "onCreateOptionsMenu() called with: menu = [$menu], inflater = [$inflater]")
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_playlist, menu)

        playlistBookmarkButton = menu.findItem(R.id.menu_item_bookmark)
        updateBookmarkButtons()
    }

    override fun onDestroyView() {
        headerBinding = null
        playlistControlBinding = null
        super.onDestroyView()
        isBookmarkButtonReady?.set(false)
        disposables?.clear()
        bookmarkReactor?.cancel()
        bookmarkReactor = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables?.dispose()
        disposables = null
        remotePlaylistManager = null
        playlistEntity = null
        isBookmarkButtonReady = null
    }

    override fun loadMoreItemsLogic(): Single<InfoItemsPage<StreamInfoItem>> {
        return getMorePlaylistItems(serviceId, url?:"", currentNextPage)
    }

    override fun loadResult(forceLoad: Boolean): Single<PlaylistInfo> {
        return getPlaylistInfo(serviceId, url!!, forceLoad)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> openSettings(requireContext())
            R.id.menu_item_openInBrowser -> openUrlInBrowser(requireContext(), url)
            R.id.menu_item_share -> shareText(requireContext(), name!!, url, if (currentInfo == null) listOf() else currentInfo!!.thumbnails)
            R.id.menu_item_bookmark -> onBookmarkClicked()
            R.id.menu_item_append_playlist -> if (currentInfo != null) {
                disposables!!.add(PlaylistDialog.createCorrespondingDialog(context, playQueue.streams.stream()
                        .map { item: PlayQueueItem -> StreamEntity(item) }.collect(Collectors.toList()))
                { dialog -> dialog.show(fM!!, TAG) })
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun showLoading() {
        super.showLoading()
        headerBinding!!.root.animate(false, 200)
        itemsList.animateHideRecyclerViewAllowingScrolling()

        cancelTag(PICASSO_PLAYLIST_TAG)
        headerBinding!!.uploaderLayout.animate(false, 200)
    }

    override fun handleResult(result: PlaylistInfo) {
        super.handleResult(result)

        headerBinding!!.root.animate(true, 100)
        headerBinding!!.uploaderLayout.animate(true, 300)
        headerBinding!!.uploaderLayout.setOnClickListener(null)
        // If we have an uploader put them into the UI
        if (result.uploaderName.isNotEmpty()) {
            headerBinding!!.uploaderName.text = result.uploaderName
            if (result.uploaderUrl.isNotEmpty()) {
                headerBinding!!.uploaderLayout.setOnClickListener {
                    try {
                        openChannelFragment(fM!!, result.serviceId, result.uploaderUrl, result.uploaderName)
                    } catch (e: Exception) {
                        showUiErrorSnackbar(this, "Opening channel fragment", e)
                    }
                }
            }
        } else { // Otherwise say we have no uploader
            headerBinding!!.uploaderName.setText(R.string.playlist_no_uploader)
        }

        playlistControlBinding!!.root.visibility = View.VISIBLE

        if (result.serviceId == ServiceList.YouTube.serviceId
                && (YoutubeParsingHelper.isYoutubeMixId(result.id) || YoutubeParsingHelper.isYoutubeMusicMixId(result.id))) {
            // this is an auto-generated playlist (e.g. Youtube mix), so a radio is shown
            val model = ShapeAppearanceModel.builder().setAllCorners(CornerFamily.ROUNDED, 0f).build() // this turns the image back into a square
            headerBinding!!.uploaderAvatarView.shapeAppearanceModel = model
            headerBinding!!.uploaderAvatarView.strokeColor = AppCompatResources.getColorStateList(requireContext(), R.color.transparent_background_color)
            headerBinding!!.uploaderAvatarView.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_radio))
        } else loadAvatar(result.uploaderAvatars).tag(PICASSO_PLAYLIST_TAG).into(headerBinding!!.uploaderAvatarView)

        headerBinding!!.playlistStreamCount.text = localizeStreamCount(requireContext(), result.streamCount)

        val description = result.description
        if (description != null && description !== Description.EMPTY_DESCRIPTION && description.content.isNotBlank()) {
            val ellipsizer = TextEllipsizer(headerBinding!!.playlistDescription, 5, getServiceById(result.serviceId))
            ellipsizer.setStateChangeListener { isEllipsized: Boolean ->
                headerBinding!!.playlistDescriptionReadMore.setText(if (java.lang.Boolean.TRUE == isEllipsized) R.string.show_more else R.string.show_less)
            }
            ellipsizer.setOnContentChanged { canBeEllipsized: Boolean ->
                headerBinding!!.playlistDescriptionReadMore.visibility = if (java.lang.Boolean.TRUE == canBeEllipsized) View.VISIBLE else View.GONE
                if (java.lang.Boolean.TRUE == canBeEllipsized) ellipsizer.ellipsize()
            }
            ellipsizer.setContent(description)
            headerBinding!!.playlistDescriptionReadMore.setOnClickListener { ellipsizer.toggle() }
        } else {
            headerBinding!!.playlistDescription.visibility = View.GONE
            headerBinding!!.playlistDescriptionReadMore.visibility = View.GONE
        }

        if (result.errors.isNotEmpty()) showSnackBarError(ErrorInfo(result.errors, UserAction.REQUESTED_PLAYLIST, result.url, result))

        remotePlaylistManager!!.getPlaylist(result)
            .flatMap({ lists: List<PlaylistRemoteEntity> -> getUpdateProcessor(lists, result) },
                { lists: List<PlaylistRemoteEntity>, _: Int -> lists })
            .onBackpressureLatest()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(playlistBookmarkSubscriber)

        initPlaylistControlClickListener(requireActivity() as AppCompatActivity, playlistControlBinding!!, this)
    }

    override val playQueue: PlayQueue
        get() = getPlayQueue(0)

    private fun getPlayQueue(index: Int): PlayQueue {
        val infoItems: MutableList<StreamInfoItem> = ArrayList()
        for (i in infoListAdapter!!.itemsList) {
            if (i is StreamInfoItem) infoItems.add(i)
        }
        return PlaylistPlayQueue(currentInfo!!.serviceId, currentInfo!!.url, currentInfo!!.nextPage, infoItems, index)
    }

    private fun getUpdateProcessor(playlists: List<PlaylistRemoteEntity>, result: PlaylistInfo): Flowable<Int> {
        val noItemToUpdate = Flowable.just( -1)
        if (playlists.isEmpty()) return noItemToUpdate

        val playlistRemoteEntity = playlists[0]
        if (playlistRemoteEntity.isIdenticalTo(result)) return noItemToUpdate

        return remotePlaylistManager!!.onUpdate(playlists[0].uid, result).toFlowable()
    }

    private val playlistBookmarkSubscriber: Subscriber<List<PlaylistRemoteEntity>>
        get() = object : Subscriber<List<PlaylistRemoteEntity>> {
            override fun onSubscribe(s: Subscription) {
                bookmarkReactor?.cancel()
                bookmarkReactor = s
                bookmarkReactor!!.request(1)
            }

            override fun onNext(playlist: List<PlaylistRemoteEntity>) {
                playlistEntity = if (playlist.isEmpty()) null else playlist[0]

                updateBookmarkButtons()
                isBookmarkButtonReady!!.set(true)
                bookmarkReactor?.request(1)
            }

            override fun onError(throwable: Throwable) {
                showError(ErrorInfo(throwable, UserAction.REQUESTED_BOOKMARK, "Get playlist bookmarks"))
            }

            override fun onComplete() {}
        }

    override fun setTitle(title: String) {
        super.setTitle(title)
        headerBinding?.playlistTitleView?.text = title
    }

    private fun onBookmarkClicked() {
        if (isBookmarkButtonReady == null || !isBookmarkButtonReady!!.get() || remotePlaylistManager == null) return

        val action: Disposable
        when {
            currentInfo != null && playlistEntity == null -> {
                action = remotePlaylistManager!!.onBookmark(currentInfo!!)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({ }, { throwable: Throwable? ->
                        showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Adding playlist bookmark")) })
            }
            playlistEntity != null -> {
                action = remotePlaylistManager!!.deletePlaylist(playlistEntity!!.uid)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doFinally { playlistEntity = null }
                    .subscribe({ _: Int? -> }, { throwable: Throwable? ->
                        showError(ErrorInfo(throwable!!, UserAction.REQUESTED_BOOKMARK, "Deleting playlist bookmark")) })
            }
            else -> action = Disposable.empty()
        }

        disposables!!.add(action)
    }

    private fun updateBookmarkButtons() {
        if (playlistBookmarkButton == null || activity == null) return
        val drawable = if (playlistEntity == null) R.drawable.ic_playlist_add else R.drawable.ic_playlist_add_check
        val titleRes = if (playlistEntity == null) R.string.bookmark_playlist else R.string.unbookmark_playlist

        playlistBookmarkButton!!.setIcon(drawable)
        playlistBookmarkButton!!.setTitle(titleRes)
    }

    companion object {
        private const val PICASSO_PLAYLIST_TAG = "PICASSO_PLAYLIST_TAG"

        fun getInstance(serviceId: Int, url: String?, name: String?): PlaylistFragment {
            val instance = PlaylistFragment()
            instance.setInitialData(serviceId, url, name)
            return instance
        }
    }
}
