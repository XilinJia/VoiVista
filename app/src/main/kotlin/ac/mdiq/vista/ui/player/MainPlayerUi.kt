package ac.mdiq.vista.ui.player

import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.PlayerBinding
import ac.mdiq.vista.extractor.ServiceList
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.extractor.stream.StreamSegment
import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.player.helper.AudioReactor
import ac.mdiq.vista.player.helper.PlayerHelper
import ac.mdiq.vista.player.helper.PlayerHelper.MinimizeMode
import ac.mdiq.vista.player.notification.NotificationConstants
import ac.mdiq.vista.player.playqueue.PlayQueueItem
import ac.mdiq.vista.player.playqueue.PlayQueueItemBuilder
import ac.mdiq.vista.ui.dialog.PlaybackParameterDialog
import ac.mdiq.vista.ui.dialog.PlaylistDialog.Companion.showForPlayQueue
import ac.mdiq.vista.ui.fragments.VideoDetailFragment
import ac.mdiq.vista.ui.gesture.BasePlayerGestureListener
import ac.mdiq.vista.ui.gesture.DisplayPortion
import ac.mdiq.vista.ui.gesture.OnScrollBelowItemsListener
import ac.mdiq.vista.ui.player.MainPlayerUi.StreamSegmentAdapter.StreamSegmentItem
import ac.mdiq.vista.ui.util.NavigationHelper.playOnPopupPlayer
import ac.mdiq.vista.ui.util.QueueItemMenuUtil.openPopupMenu
import ac.mdiq.vista.ui.util.ShareUtils.shareText
import ac.mdiq.vista.ui.util.ThemeHelper.getAndroidDimenPx
import ac.mdiq.vista.ui.util.ktx.AnimationType
import ac.mdiq.vista.ui.util.ktx.animate
import ac.mdiq.vista.util.DeviceUtils.dpToPx
import ac.mdiq.vista.util.DeviceUtils.isLandscape
import ac.mdiq.vista.util.DeviceUtils.isTablet
import ac.mdiq.vista.util.DeviceUtils.isTv
import ac.mdiq.vista.util.DeviceUtils.spToPx
import ac.mdiq.vista.util.KoreUtils.shouldShowPlayWithKodi
import ac.mdiq.vista.util.Localization
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.image.PicassoHelper
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.View.OnLayoutChangeListener
import android.view.View.OnTouchListener
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.GroupieViewHolder
import com.xwray.groupie.Item
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@UnstableApi class MainPlayerUi(playerManager: PlayerManager, playerBinding: PlayerBinding)
    : VideoPlayerUi(playerManager, playerBinding), OnLayoutChangeListener {

    override var isFullscreen = false
    var isVerticalVideo: Boolean = false
        private set
    private var fragmentIsVisible = false

    private var settingsContentObserver: ContentObserver? = null

    private var playQueueAdapter: PlayQueueAdapter? = null
    private var segmentAdapter: StreamSegmentAdapter? = null
    private var isQueueVisible = false
    private var areSegmentsVisible = false

    // fullscreen player
    private var itemTouchHelper: ItemTouchHelper? = null

    private val queueScrollListener: OnScrollBelowItemsListener
        get() = object : OnScrollBelowItemsListener() {
            override fun onScrolledDown(recyclerView: RecyclerView?) {
                val playQueue = playerManager.playQueue
                if (playQueue != null && !playQueue.isComplete) playQueue.fetch()
                else binding.itemsList.clearOnScrollListeners()
            }
        }

    private val streamSegmentListener: StreamSegmentAdapter.StreamSegmentListener
        get() = object : StreamSegmentAdapter.StreamSegmentListener {
            override fun onItemClick(item: StreamSegmentItem, seconds: Int) {
                segmentAdapter!!.selectSegment(item)
                playerManager.seekTo(seconds * 1000L)
                playerManager.triggerProgressUpdate()
            }

            override fun onItemLongClick(item: StreamSegmentItem, seconds: Int) {
                val currentMetadata = playerManager.currentMetadata
                if (currentMetadata == null || currentMetadata.serviceId != ServiceList.YouTube.serviceId) return

                val currentItem = playerManager.currentItem
                if (currentItem != null) {
                    var videoUrl = playerManager.videoUrl
                    videoUrl += ("&t=$seconds")
                    shareText(context, currentItem.title, videoUrl, currentItem.thumbnails)
                }
            }
        }

    private val itemTouchCallback: ItemTouchHelper.SimpleCallback
        get() = object : PlayQueueItemTouchCallback() {
            override fun onMove(sourceIndex: Int, targetIndex: Int) {
                val playQueue = playerManager.playQueue
                playQueue?.move(sourceIndex, targetIndex)
            }
            override fun onSwiped(index: Int) {
                val playQueue = playerManager.playQueue
                if (index != -1) playQueue?.remove(index)
            }
        }

    private val onSelectedListener: PlayQueueItemBuilder.OnSelectedListener
        get() = object : PlayQueueItemBuilder.OnSelectedListener {
            override fun selected(item: PlayQueueItem, view: View) {
                playerManager.selectQueueItem(item)
            }

            override fun held(item: PlayQueueItem, view: View) {
                val playQueue = playerManager.playQueue
                val parentActivity: AppCompatActivity? = parentActivity.orElse(null)
                if (playQueue != null && parentActivity != null && playQueue.indexOf(item) != -1)
                    openPopupMenu(playerManager.playQueue!!, item, view, true, parentActivity.supportFragmentManager, context)
            }

            override fun onStartDrag(viewHolder: PlayQueueItemHolder) {
                itemTouchHelper?.startDrag(viewHolder)
            }
        }

    override val isAnyListViewOpen: Boolean
        get() = isQueueVisible || areSegmentsVisible

    private val parentContext: Optional<Context>
        get() = Optional.ofNullable(binding.root.parent)
            .filter { obj: ViewParent? -> ViewGroup::class.java.isInstance(obj) }
            .map { parent: ViewParent -> (parent as ViewGroup).context }

    val parentActivity: Optional<AppCompatActivity?>
        get() = parentContext
            .filter { obj: Context? -> AppCompatActivity::class.java.isInstance(obj) }
            .map { obj: Context? -> AppCompatActivity::class.java.cast(obj) }

    // DisplayMetrics from activity context knows about MultiWindow feature
    // while DisplayMetrics from app context doesn't
    private val isLandscape: Boolean
        get() = isLandscape(parentContext.orElse(playerManager.service)) //endregion

    /**
     * Open fullscreen on tablets where the option to have the main player start automatically in
     * fullscreen mode is on. Rotating the device to landscape is already done in [ ][VideoDetailFragment.openVideoPlayer] when the thumbnail is clicked, and that's
     * enough for phones, but not for tablets since the mini player can be also shown in landscape.
     */
    private fun directlyOpenFullscreenIfNeeded() {
        if (PlayerHelper.isStartMainPlayerFullscreenEnabled(playerManager.service) && isTablet(playerManager.service)
                && PlayerHelper.globalScreenOrientationLocked(playerManager.service))
            playerManager.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.onScreenRotationButtonClicked() }
    }

    override fun setupAfterIntent() {
        // needed for tablets, check the function for a better explanation
        directlyOpenFullscreenIfNeeded()
        super.setupAfterIntent()
        initVideoPlayer()
        // Android TV: without it focus will frame the whole player
        binding.playPauseButton.requestFocus()

        // Note: This is for automatically playing (when "Resume playback" is off), see #6179
        if (playerManager.playWhenReady) playerManager.play()
        else playerManager.pause()
    }

    override fun buildGestureListener(): BasePlayerGestureListener {
        return MainPlayerGestureListener(this)
    }

    override fun initListeners() {
        super.initListeners()

        binding.screenRotationButton.setOnClickListener(makeOnClickListener {
            // Only if it's not a vertical video or vertical video but in landscape with locked
            // orientation a screen orientation can be changed automatically
            if (!isVerticalVideo || (isLandscape && PlayerHelper.globalScreenOrientationLocked(context)))
                playerManager.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.onScreenRotationButtonClicked() }
            else toggleFullscreen()
        })
        binding.queueButton.setOnClickListener { onQueueClicked() }
        binding.segmentsButton.setOnClickListener { onSegmentsClicked() }

        binding.addToPlaylistButton.setOnClickListener {
            parentActivity.map { obj: AppCompatActivity? -> obj!!.supportFragmentManager }
                .ifPresent { fragmentManager: FragmentManager? -> showForPlayQueue(playerManager, fragmentManager!!) }
        }

        settingsContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                setupScreenRotationButton()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, settingsContentObserver!!)

        binding.root.addOnLayoutChangeListener(this)

        binding.moreOptionsButton.setOnLongClickListener {
            playerManager.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.onMoreOptionsLongClicked() }
            hideControls(0, 0)
            hideSystemUIIfNeeded()
            true
        }
    }

    override fun deinitListeners() {
        super.deinitListeners()

        binding.queueButton.setOnClickListener(null)
        binding.segmentsButton.setOnClickListener(null)
        binding.addToPlaylistButton.setOnClickListener(null)

        context.contentResolver.unregisterContentObserver(settingsContentObserver!!)

        binding.root.removeOnLayoutChangeListener(this)
    }

    override fun initPlayback() {
        super.initPlayback()
        playQueueAdapter?.dispose()

        if (playerManager.playQueue != null) playQueueAdapter = PlayQueueAdapter(context, playerManager.playQueue!!)
        segmentAdapter = StreamSegmentAdapter(streamSegmentListener)
    }

    override fun removeViewFromParent() {
        // view was added to fragment
        val parent = binding.root.parent
        if (parent is ViewGroup) parent.removeView(binding.root)
    }

    override fun destroy() {
        super.destroy()

        // Exit from fullscreen when user closes the player via notification
        if (isFullscreen) toggleFullscreen()

        removeViewFromParent()
    }

    override fun destroyPlayer() {
        Logd(TAG, "destroyPlayer")
        super.destroyPlayer()
        playQueueAdapter?.unsetSelectedListener()
        playQueueAdapter?.dispose()
    }

    override fun smoothStopForImmediateReusing() {
        super.smoothStopForImmediateReusing()
        // Android TV will handle back button in case controls will be visible
        // (one more additional unneeded click while the player is hidden)
        hideControls(0, 0)
        closeItemsList()
    }

    private fun initVideoPlayer() {
        // restore last resize mode
        setResizeMode(PlayerHelper.retrieveResizeModeFromPrefs(playerManager))
        binding.root.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun setupElementsVisibility() {
        super.setupElementsVisibility()

        closeItemsList()
        showHideKodiButton()
        binding.fullScreenButton.visibility = View.GONE
        setupScreenRotationButton()
        binding.resizeTextView.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.metadataView).visibility = View.VISIBLE
        binding.moreOptionsButton.visibility = View.VISIBLE
        binding.topControls.orientation = LinearLayout.VERTICAL
        binding.primaryControls.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        binding.secondaryControls.visibility = View.INVISIBLE
        binding.moreOptionsButton.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_expand_more))
        binding.share.visibility = View.VISIBLE
        binding.openInBrowser.visibility = View.VISIBLE
        binding.switchMute.visibility = View.VISIBLE
        binding.playerCloseButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        // Top controls have a large minHeight which is allows to drag the player
        // down in fullscreen mode (just larger area to make easy to locate by finger)
        binding.topControls.isClickable = true
        binding.topControls.isFocusable = true

        binding.titleTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        binding.channelTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
    }

    override fun setupElementsSize(resources: Resources) {
        setupElementsSize(resources.getDimensionPixelSize(R.dimen.player_main_buttons_min_width), resources.getDimensionPixelSize(R.dimen.player_main_top_padding), resources.getDimensionPixelSize(R.dimen.player_main_controls_padding), resources.getDimensionPixelSize(R.dimen.player_main_buttons_padding))
    }

    override fun onBroadcastReceived(intent: Intent?) {
        Logd(TAG, "onBroadcastReceived ${intent?.action}")
        super.onBroadcastReceived(intent)
        when (intent?.action) {
            // Close it because when changing orientation from portrait
            // (in fullscreen mode) the size of queue layout can be larger than the screen size
            Intent.ACTION_CONFIGURATION_CHANGED -> closeItemsList()
            // Ensure that we have audio-only stream playing when a user
            // started to play from notification's play button from outside of the app
            NotificationConstants.ACTION_PLAY_PAUSE -> if (!fragmentIsVisible) onFragmentStopped()
            VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED -> {
                fragmentIsVisible = false
                onFragmentStopped()
            }
            VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED -> {
                // Restore video source when user returns to the fragment
                fragmentIsVisible = true
                playerManager.useVideoSource(true)

                // When a user returns from background, the system UI will always be shown even if
                // controls are invisible: hide it in that case
                if (!isControlsVisible) hideSystemUIIfNeeded()
            }
        }
    }

    override fun onFragmentListenerSet() {
        super.onFragmentListenerSet()
        fragmentIsVisible = true
        // Apply window insets because Android will not do it when orientation changes
        // from landscape to portrait
        if (!isFullscreen) binding.playbackControlRoot.setPadding(0, 0, 0, 0)

        binding.itemsListPanel.setPadding(0, 0, 0, 0)
        playerManager.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.onViewCreated() }
    }

    /**
     * This will be called when a user goes to another app/activity, turns off a screen.
     * We don't want to interrupt playback and don't want to see notification so
     * next lines of code will enable audio-only playback only if needed
     */
    private fun onFragmentStopped() {
        if (playerManager.isPlaying || playerManager.isLoading) {
            when (PlayerHelper.getMinimizeOnExitAction(context)) {
                MinimizeMode.MINIMIZE_ON_EXIT_MODE_BACKGROUND -> {
                    playerManager.useVideoSource(false)
//                    player.exoPlayer?.clearVideoSurface()
                }
                MinimizeMode.MINIMIZE_ON_EXIT_MODE_POPUP -> parentActivity.ifPresent { activity: AppCompatActivity? ->
                    playerManager.setRecovery()
                    playOnPopupPlayer(activity!!, playerManager.playQueue, true)
                }
                MinimizeMode.MINIMIZE_ON_EXIT_MODE_NONE -> playerManager.pause()
                else -> playerManager.pause()
            }
        }
    }

    override fun onUpdateProgress(currentProgress: Int, duration: Int, bufferPercent: Int) {
        super.onUpdateProgress(currentProgress, duration, bufferPercent)
        if (areSegmentsVisible) segmentAdapter?.selectSegmentAt(getNearestStreamSegmentPosition(currentProgress.toLong()))
        if (isQueueVisible) updateQueueTime(currentProgress)
    }

    override fun onPlaying() {
        super.onPlaying()
        checkLandscape()
    }

    override fun onCompleted() {
        super.onCompleted()
        if (isFullscreen) toggleFullscreen()
    }

    override fun showOrHideButtons() {
        super.showOrHideButtons()
        val playQueue = playerManager.playQueue ?: return

        val showQueue = playQueue.streams.isNotEmpty()
        val showSegment = !playerManager.currentStreamInfo
            .map { obj: StreamInfo -> obj.streamSegments }
            .map { obj: List<StreamSegment> -> obj.isEmpty() }
            .orElse( true)

        binding.queueButton.visibility = if (showQueue) View.VISIBLE else View.GONE
        binding.queueButton.alpha = if (showQueue) 1.0f else 0.0f
        binding.segmentsButton.visibility = if (showSegment) View.VISIBLE else View.GONE
        binding.segmentsButton.alpha = if (showSegment) 1.0f else 0.0f
    }

    public override fun showSystemUIPartially() {
        if (isFullscreen) {
            parentActivity.map { obj: AppCompatActivity? -> obj!!.window }.ifPresent { window: Window ->
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                val visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                window.decorView.systemUiVisibility = visibility
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }
    }

    public override fun hideSystemUIIfNeeded() {
        playerManager.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.hideSystemUiIfNeeded() }
    }

    /**
     * Calculate the maximum allowed height for the [R.id.endScreen]
     * to prevent it from enlarging the player.
     *
     * The calculating follows these rules:
     *
     * Show at least stream title and content creator on TVs and tablets when in landscape
     * (always the case for TVs) and not in fullscreen mode. This requires to have at least
     * [.DETAIL_ROOT_MINIMUM_HEIGHT] free space for [R.id.detail_root] and
     * additional space for the stream title text size ([R.id.detail_title_root_layout]).
     * The text size is [.DETAIL_TITLE_TEXT_SIZE_TABLET] on tablets and
     * [.DETAIL_TITLE_TEXT_SIZE_TV] on TVs, see [R.id.titleTextView].
     *
     * Otherwise, the max thumbnail height is the screen height.
     *
     * @param bitmap the bitmap that needs to be resized to fit the end screen
     * @return the maximum height for the end screen thumbnail
     */
    override fun calculateMaxEndScreenThumbnailHeight(bitmap: Bitmap): Float {
        val screenHeight = context.resources.displayMetrics.heightPixels

        when {
            isTv(context) && !isFullscreen -> {
                val videoInfoHeight = (dpToPx(DETAIL_ROOT_MINIMUM_HEIGHT, context) + spToPx(DETAIL_TITLE_TEXT_SIZE_TV, context))
                return min(bitmap.height.toDouble(), (screenHeight - videoInfoHeight).toDouble()).toFloat()
            }
            isTablet(context) && isLandscape && !isFullscreen -> {
                val videoInfoHeight = (dpToPx(DETAIL_ROOT_MINIMUM_HEIGHT, context) + spToPx(
                    DETAIL_TITLE_TEXT_SIZE_TABLET, context))
                return min(bitmap.height.toDouble(), (screenHeight - videoInfoHeight).toDouble()).toFloat()
            }
            // fullscreen player: max height is the device height
            else -> return min(bitmap.height.toDouble(), screenHeight.toDouble()).toFloat()
        }
    }

    private fun showHideKodiButton() {
        // show kodi button if it supports the current service and it is enabled in settings
        val playQueue = playerManager.playQueue
        binding.playWithKodi.visibility =
            if (playQueue?.item != null && shouldShowPlayWithKodi(context, playQueue.item!!.serviceId)) View.VISIBLE else View.GONE
    }

    override fun setupSubtitleView(captionScale: Float) {
        val metrics = context.resources.displayMetrics
        val minimumLength = min(metrics.heightPixels.toDouble(), metrics.widthPixels.toDouble()).toInt()
        val captionRatioInverse = 20f + 4f * (1.0f - captionScale)
        binding.subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, minimumLength / captionRatioInverse)
    }

    override fun onLayoutChange(view: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
        if (l != ol || t != ot || r != or || b != ob) {
            // Use a smaller value to be consistent across screen orientations, and to make usage
            // easier. Multiply by 3/4 to ensure the user does not need to move the finger up to the
            // screen border, in order to reach the maximum volume/brightness.
            val width = r - l
            val height = b - t
            val min = min(width.toDouble(), height.toDouble()).toInt()
            val maxGestureLength = (min * 0.75).toInt()
            Logd(TAG, "maxGestureLength = $maxGestureLength")

            binding.volumeProgressBar.max = maxGestureLength
            binding.brightnessProgressBar.max = maxGestureLength

            setInitialGestureValues()
            binding.itemsListPanel.layoutParams.height = height - binding.itemsListPanel.top
        }
    }

    private fun setInitialGestureValues() {
        if (playerManager.audioReactor != null) {
            val currentVolumeNormalized = playerManager.audioReactor!!.volume.toFloat() / playerManager.audioReactor!!.maxVolume
            binding.volumeProgressBar.progress = (binding.volumeProgressBar.max * currentVolumeNormalized).toInt()
        }
    }

    override fun onMetadataChanged(info: StreamInfo) {
        super.onMetadataChanged(info)
        showHideKodiButton()
        if (areSegmentsVisible) {
            if (segmentAdapter!!.setItems(info)) {
                val adapterPosition = getNearestStreamSegmentPosition(playerManager.exoPlayer!!.currentPosition)
                segmentAdapter!!.selectSegmentAt(adapterPosition)
                binding.itemsList.scrollToPosition(adapterPosition)
            } else closeItemsList()
        }
    }

    override fun onPlayQueueEdited() {
        super.onPlayQueueEdited()
        showOrHideButtons()
    }

    private fun onQueueClicked() {
        isQueueVisible = true

        hideSystemUIIfNeeded()
        buildQueue()

        binding.itemsListHeaderTitle.visibility = View.GONE
        binding.itemsListHeaderDuration.visibility = View.VISIBLE
        binding.shuffleButton.visibility = View.VISIBLE
        binding.repeatButton.visibility = View.VISIBLE
        binding.addToPlaylistButton.visibility = View.VISIBLE

        hideControls(0, 0)
        binding.itemsListPanel.requestFocus()
        binding.itemsListPanel.animate(true, DEFAULT_CONTROLS_DURATION, AnimationType.SLIDE_AND_ALPHA)

        val playQueue = playerManager.playQueue
        if (playQueue != null) binding.itemsList.scrollToPosition(playQueue.index)

        updateQueueTime(playerManager.exoPlayer!!.currentPosition.toInt())
    }

    private fun buildQueue() {
        binding.itemsList.adapter = playQueueAdapter
        binding.itemsList.isClickable = true
        binding.itemsList.isLongClickable = true

        binding.itemsList.clearOnScrollListeners()
        binding.itemsList.addOnScrollListener(queueScrollListener)

        itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper!!.attachToRecyclerView(binding.itemsList)

        playQueueAdapter!!.setSelectedListener(onSelectedListener)

        binding.itemsListClose.setOnClickListener { closeItemsList() }
    }

    private fun onSegmentsClicked() {
        areSegmentsVisible = true

        hideSystemUIIfNeeded()
        buildSegments()

        binding.itemsListHeaderTitle.visibility = View.VISIBLE
        binding.itemsListHeaderDuration.visibility = View.GONE
        binding.shuffleButton.visibility = View.GONE
        binding.repeatButton.visibility = View.GONE
        binding.addToPlaylistButton.visibility = View.GONE

        hideControls(0, 0)
        binding.itemsListPanel.requestFocus()
        binding.itemsListPanel.animate(true, DEFAULT_CONTROLS_DURATION, AnimationType.SLIDE_AND_ALPHA)

        val adapterPosition = getNearestStreamSegmentPosition(playerManager.exoPlayer!!.currentPosition)
        segmentAdapter!!.selectSegmentAt(adapterPosition)
        binding.itemsList.scrollToPosition(adapterPosition)
    }

    private fun buildSegments() {
        binding.itemsList.adapter = segmentAdapter
        binding.itemsList.isClickable = true
        binding.itemsList.isLongClickable = true

        binding.itemsList.clearOnScrollListeners()
        itemTouchHelper?.attachToRecyclerView(null)

        playerManager.currentStreamInfo.ifPresent { info: StreamInfo? ->
            segmentAdapter!!.setItems(info!!)
        }

        binding.shuffleButton.visibility = View.GONE
        binding.repeatButton.visibility = View.GONE
        binding.addToPlaylistButton.visibility = View.GONE
        binding.itemsListClose.setOnClickListener { closeItemsList() }
    }

    fun closeItemsList() {
        if (isQueueVisible || areSegmentsVisible) {
            isQueueVisible = false
            areSegmentsVisible = false

            itemTouchHelper?.attachToRecyclerView(null)
            // Even when queueLayout is GONE it receives touch events
            binding.itemsListPanel.animate(false, DEFAULT_CONTROLS_DURATION, AnimationType.SLIDE_AND_ALPHA, 0) {
                // and ruins normal behavior of the app. This line fixes it
                binding.itemsListPanel.translationY = -binding.itemsListPanel.height * 5.0f
            }

            // clear focus, otherwise a white rectangle remains on top of the player
            binding.itemsListClose.clearFocus()
            binding.playPauseButton.requestFocus()
        }
    }

    private fun getNearestStreamSegmentPosition(playbackPosition: Long): Int {
        var nearestPosition = 0
        val segments = playerManager.currentStreamInfo
            .map { obj: StreamInfo -> obj.streamSegments }
            .orElse(emptyList())

        for (i in segments.indices) {
            if (segments[i].startTimeSeconds * 1000L > playbackPosition) break
            nearestPosition++
        }
        return max(0.0, (nearestPosition - 1).toDouble()).toInt()
    }

    private fun updateQueueTime(currentTime: Int) {
        val playQueue = playerManager.playQueue ?: return

        val currentStream = playQueue.index
        var before = 0
        var after = 0

        val streams = playQueue.streams
        val nStreams = streams.size

        for (i in 0 until nStreams) {
            if (i < currentStream) before += streams[i].duration.toInt()
            else after += streams[i].duration.toInt()
        }

        before *= 1000
        after *= 1000

        binding.itemsListHeaderDuration.text = String.format("%s/%s",
            PlayerHelper.getTimeString(currentTime + before),
            PlayerHelper.getTimeString(before + after)
        )
    }

    override fun onPlaybackSpeedClicked() {
        parentActivity.ifPresent { activity: AppCompatActivity? ->
            PlaybackParameterDialog.newInstance(playerManager.playbackSpeed.toDouble(),
                playerManager.playbackPitch.toDouble(), playerManager.playbackSkipSilence,
                object : PlaybackParameterDialog.Callback {
                    override fun onPlaybackParameterChanged(playbackTempo: Float, playbackPitch: Float, playbackSkipSilence: Boolean) {
                        playerManager.setPlaybackParameters(playbackTempo, playbackPitch, playbackSkipSilence)
                    }
                })
                .show(activity!!.supportFragmentManager, null)
        }
    }

    override fun onKeyDown(keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE && isFullscreen) {
            playerManager.playPause()
            if (playerManager.isPlaying) hideControls(0, 0)
            return true
        }
        return super.onKeyDown(keyCode)
    }

    private fun setupScreenRotationButton() {
        binding.screenRotationButton.visibility =
            if (PlayerHelper.globalScreenOrientationLocked(context) || isVerticalVideo || isTablet(context)) View.VISIBLE else View.GONE
        binding.screenRotationButton.setImageDrawable(AppCompatResources.getDrawable(context,
            if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen))
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        super.onVideoSizeChanged(videoSize)
        isVerticalVideo = videoSize.width < videoSize.height

        // set correct orientation
        if ((PlayerHelper.globalScreenOrientationLocked(context) && isFullscreen) && isLandscape == isVerticalVideo && !isTv(context) && !isTablet(context))
            playerManager.getFragmentListener().ifPresent { obj: PlayerServiceEventListener -> obj.onScreenRotationButtonClicked() }

        setupScreenRotationButton()
    }

    fun toggleFullscreen() {
        Logd(TAG, "toggleFullscreen() called")
        val fragmentListener = playerManager.getFragmentListener().orElse(null)
        if (fragmentListener == null || playerManager.exoPlayerIsNull()) return

        isFullscreen = !isFullscreen
        // Android needs tens milliseconds to send new insets but a user is able to see
        // how controls changes it's position from `0` to `nav bar height` padding.
        // So just hide the controls to hide this visual inconsistency
        if (isFullscreen) hideControls(0, 0)
        // Apply window insets because Android will not do it when orientation changes
        // from landscape to portrait (open vertical video to reproduce)
        else binding.playbackControlRoot.setPadding(0, 0, 0, 0)

        fragmentListener.onFullscreenStateChanged(isFullscreen)

        binding.titleTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        binding.channelTextView.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        binding.playerCloseButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        setupScreenRotationButton()
    }

    fun checkLandscape() {
        // check if landscape is correct
        val videoInLandscapeButNotInFullscreen = (isLandscape && !isFullscreen && !playerManager.isAudioOnly)
        val notPaused = (playerManager.currentState != PlayerManager.STATE_COMPLETED && playerManager.currentState != PlayerManager.STATE_PAUSED)
        if (videoInLandscapeButNotInFullscreen && notPaused && !isTablet(context)) toggleFullscreen()
    }

    /**
     * Custom RecyclerView.Adapter/GroupieAdapter for [StreamSegmentItem] for handling selection state.
     */
    class StreamSegmentAdapter(private val listener: StreamSegmentListener) : GroupieAdapter() {
        private var currentIndex: Int = 0

        /**
         * Returns `true` if the provided [StreamInfo] contains segments, `false` otherwise.
         */
        fun setItems(info: StreamInfo): Boolean {
            if (info.streamSegments.isNotEmpty()) {
                clear()
                addAll(info.streamSegments.map { StreamSegmentItem(it, listener) })
                return true
            }
            return false
        }

        fun selectSegment(segment: StreamSegmentItem) {
            unSelectCurrentSegment()
            currentIndex = max(0, getAdapterPosition(segment))
            segment.isSelected = true
            segment.notifyChanged(StreamSegmentItem.PAYLOAD_SELECT)
        }

        fun selectSegmentAt(position: Int) {
            try {
                selectSegment(getGroupAtAdapterPosition(position) as StreamSegmentItem)
            } catch (e: IndexOutOfBoundsException) {
                // Just to make sure that getGroupAtAdapterPosition doesn't close the app
                // Shouldn't happen since setItems is always called before select-methods but just in case
                currentIndex = 0
                Log.e("StreamSegmentAdapter", "selectSegmentAt: ${e.message}")
            }
        }

        private fun unSelectCurrentSegment() {
            try {
                val segmentItem = getGroupAtAdapterPosition(currentIndex) as StreamSegmentItem
                currentIndex = 0
                segmentItem.isSelected = false
                segmentItem.notifyChanged(StreamSegmentItem.PAYLOAD_SELECT)
            } catch (e: IndexOutOfBoundsException) {
                // Just to make sure that getGroupAtAdapterPosition doesn't close the app
                // Shouldn't happen since setItems is always called before select-methods but just in case
                currentIndex = 0
                Log.e("StreamSegmentAdapter", "unSelectCurrentSegment: ${e.message}")
            }
        }

        interface StreamSegmentListener {
            fun onItemClick(item: StreamSegmentItem, seconds: Int)
            fun onItemLongClick(item: StreamSegmentItem, seconds: Int)
        }

        class StreamSegmentItem(private val item: StreamSegment, private val onClick: StreamSegmentListener) : Item<GroupieViewHolder>() {
            var isSelected = false

            companion object {
                const val PAYLOAD_SELECT = 1
            }

            override fun bind(viewHolder: GroupieViewHolder, position: Int) {
                item.previewUrl?.let {
                    PicassoHelper.loadThumbnail(it).into(viewHolder.root.findViewById<ImageView>(R.id.previewImage))
                }
                val title_ =viewHolder.root.findViewById<TextView>(R.id.textViewTitle)
                title_.text = item.title
                if (item.channelName == null) {
                    viewHolder.root.findViewById<TextView>(R.id.textViewChannel).visibility = View.GONE
                    // When the channel name is displayed there is less space
                    // and thus the segment title needs to be only one line height.
                    // But when there is no channel name displayed, the title can be two lines long.
                    // The default maxLines value is set to 1 to display all elements in the AS preview,
                    title_.maxLines = 2
                } else {
                    viewHolder.root.findViewById<TextView>(R.id.textViewChannel).text = item.channelName
                    viewHolder.root.findViewById<TextView>(R.id.textViewChannel).visibility = View.VISIBLE
                }
                viewHolder.root.findViewById<TextView>(R.id.textViewStartSeconds).text = Localization.getDurationString(item.startTimeSeconds.toLong())
                viewHolder.root.setOnClickListener { onClick.onItemClick(this, item.startTimeSeconds) }
                viewHolder.root.setOnLongClickListener {
                    onClick.onItemLongClick(this, item.startTimeSeconds)
                    true
                }
                viewHolder.root.isSelected = isSelected
            }

            override fun bind(viewHolder: GroupieViewHolder, position: Int, payloads: MutableList<Any>) {
                if (payloads.contains(PAYLOAD_SELECT)) {
                    viewHolder.root.isSelected = isSelected
                    return
                }
                super.bind(viewHolder, position, payloads)
            }

            override fun getLayout() = R.layout.item_stream_segment
        }
    }

    /**
     * GestureListener for the player
     *
     * While [BasePlayerGestureListener] contains the logic behind the single gestures
     * this class focuses on the visual aspect like hiding and showing the controls or changing
     * volume/brightness during scrolling for specific events.
     */
    class MainPlayerGestureListener(private val playerUi: MainPlayerUi) : BasePlayerGestureListener(playerUi),
        OnTouchListener {

        private var isMoving = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            super.onTouch(v, event)
            if (event.action == MotionEvent.ACTION_UP && isMoving) {
                isMoving = false
                onScrollEnd(event)
            }
            return when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent?.requestDisallowInterceptTouchEvent(playerUi.isFullscreen)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    false
                }
                else -> true
            }
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Logd(TAG, "onSingleTapConfirmed() called with: e = [$e]")
            if (isDoubleTapping) return true
            super.onSingleTapConfirmed(e)

            if (playerManager.currentState != PlayerManager.STATE_BLOCKED) onSingleTap()
            return true
        }

        private fun onScrollVolume(distanceY: Float) {
            val bar: ProgressBar = binding.volumeProgressBar
            val audioReactor: AudioReactor = playerManager.audioReactor ?: return

            // If we just started sliding, change the progress bar to match the system volume
            if (!binding.volumeRelativeLayout.isVisible) {
                val volumePercent: Float = audioReactor.volume / audioReactor.maxVolume.toFloat()
                bar.progress = (volumePercent * bar.max).toInt()
            }

            // Update progress bar
            binding.volumeProgressBar.incrementProgressBy(distanceY.toInt())

            // Update volume
            val currentProgressPercent: Float = bar.progress / bar.max.toFloat()
            val currentVolume = (audioReactor.maxVolume * currentProgressPercent).toInt()
            audioReactor.volume = currentVolume

            Logd(TAG, "onScroll().volumeControl, currentVolume = $currentVolume")

            // Update player center image
            binding.volumeImageView.setImageDrawable(
                AppCompatResources.getDrawable(playerManager.context,
                    when {
                        currentProgressPercent <= 0 -> R.drawable.ic_volume_off
                        currentProgressPercent < 0.25 -> R.drawable.ic_volume_mute
                        currentProgressPercent < 0.75 -> R.drawable.ic_volume_down
                        else -> R.drawable.ic_volume_up
                    },
                ),
            )

            // Make sure the correct layout is visible
            if (!binding.volumeRelativeLayout.isVisible) binding.volumeRelativeLayout.animate(true, 200, AnimationType.SCALE_AND_ALPHA)
            binding.brightnessRelativeLayout.isVisible = false
        }

        private fun onScrollBrightness(distanceY: Float) {
            val parent: AppCompatActivity = playerUi.parentActivity.orElse(null) ?: return
            val window = parent.window
            val layoutParams = window.attributes
            val bar: ProgressBar = binding.brightnessProgressBar

            // Update progress bar
            val oldBrightness = layoutParams.screenBrightness
            bar.progress = (bar.max * oldBrightness.coerceIn(0f, 1f)).toInt()
            bar.incrementProgressBy(distanceY.toInt())

            // Update brightness
            val currentProgressPercent = bar.progress.toFloat() / bar.max
            layoutParams.screenBrightness = currentProgressPercent
            window.attributes = layoutParams

            // Save current brightness level
            PlayerHelper.setScreenBrightness(parent, currentProgressPercent)
            Logd(TAG, "onScroll().brightnessControl, currentBrightness = $currentProgressPercent")

            // Update player center image
            binding.brightnessImageView.setImageDrawable(
                AppCompatResources.getDrawable(playerManager.context,
                    when {
                        currentProgressPercent < 0.25 -> R.drawable.ic_brightness_low
                        currentProgressPercent < 0.75 -> R.drawable.ic_brightness_medium
                        else -> R.drawable.ic_brightness_high
                    },
                ),
            )

            // Make sure the correct layout is visible
            if (!binding.brightnessRelativeLayout.isVisible)
                binding.brightnessRelativeLayout.animate(true, 200, AnimationType.SCALE_AND_ALPHA)
            binding.volumeRelativeLayout.isVisible = false
        }

        override fun onScrollEnd(event: MotionEvent) {
            super.onScrollEnd(event)
            if (binding.volumeRelativeLayout.isVisible)
                binding.volumeRelativeLayout.animate(false, 200, AnimationType.SCALE_AND_ALPHA, 200)

            if (binding.brightnessRelativeLayout.isVisible)
                binding.brightnessRelativeLayout.animate(false, 200, AnimationType.SCALE_AND_ALPHA, 200)
        }

        override fun onScroll(initialEvent: MotionEvent?, movingEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (initialEvent == null || !playerUi.isFullscreen) return false

            // Calculate heights of status and navigation bars
            val statusBarHeight = getAndroidDimenPx(playerManager.context, "status_bar_height")
            val navigationBarHeight = getAndroidDimenPx(playerManager.context, "navigation_bar_height")

            // Do not handle this event if initially it started from status or navigation bars
            val isTouchingStatusBar = initialEvent.y < statusBarHeight
            val isTouchingNavigationBar = initialEvent.y > (binding.root.height - navigationBarHeight)
            if (isTouchingStatusBar || isTouchingNavigationBar) return false

            val insideThreshold = abs(movingEvent.y - initialEvent.y) <= MOVEMENT_THRESHOLD
            if (!isMoving && (insideThreshold || abs(distanceX) > abs(distanceY)) || playerManager.currentState == PlayerManager.STATE_COMPLETED)
                return false

            isMoving = true

            // -- Brightness and Volume control --
            if (getDisplayHalfPortion(initialEvent) == DisplayPortion.RIGHT_HALF)
                when (PlayerHelper.getActionForRightGestureSide(playerManager.context)) {
                    playerManager.context.getString(R.string.volume_control_key) -> onScrollVolume(distanceY)
                    playerManager.context.getString(R.string.brightness_control_key) -> onScrollBrightness(distanceY)
                }
            else
                when (PlayerHelper.getActionForLeftGestureSide(playerManager.context)) {
                    playerManager.context.getString(R.string.volume_control_key) -> onScrollVolume(distanceY)
                    playerManager.context.getString(R.string.brightness_control_key) -> onScrollBrightness(distanceY)
                }
            return true
        }

        override fun getDisplayPortion(e: MotionEvent): DisplayPortion {
            return when {
                e.x < binding.root.width / 3.0 -> DisplayPortion.LEFT
                e.x > binding.root.width * 2.0 / 3.0 -> DisplayPortion.RIGHT
                else -> DisplayPortion.MIDDLE
            }
        }

        override fun getDisplayHalfPortion(e: MotionEvent): DisplayPortion {
            return when {
                e.x < binding.root.width / 2.0 -> DisplayPortion.LEFT_HALF
                else -> DisplayPortion.RIGHT_HALF
            }
        }

        companion object {
            private val TAG = MainPlayerGestureListener::class.java.simpleName
//            private const val DEBUG = MainActivity.DEBUG
            private const val MOVEMENT_THRESHOLD = 40
        }
    }

    companion object {
        private val TAG: String = MainPlayerUi::class.java.simpleName

        // see the Javadoc of calculateMaxEndScreenThumbnailHeight for information
        private const val DETAIL_ROOT_MINIMUM_HEIGHT = 85 // dp
        private const val DETAIL_TITLE_TEXT_SIZE_TV = 16 // sp
        private const val DETAIL_TITLE_TEXT_SIZE_TABLET = 15 // sp
    }
}
