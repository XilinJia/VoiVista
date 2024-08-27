package ac.mdiq.vista.ui.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.media3.common.util.UnstableApi
import com.jakewharton.processphoenix.ProcessPhoenix
import ac.mdiq.vista.R
import ac.mdiq.vista.database.VoiVistaDatabase
import ac.mdiq.vista.database.feed.model.FeedGroupEntity
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showUiErrorSnackbar
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.StreamingService
import ac.mdiq.vista.extractor.StreamingService.LinkType
import ac.mdiq.vista.extractor.comments.CommentsInfoItem
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.extractor.stream.*
import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.player.PlayerService
import ac.mdiq.vista.player.PlayerType
import ac.mdiq.vista.player.helper.PlayerHelper
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.settings.SettingsActivity
import ac.mdiq.vista.ui.activity.*
import ac.mdiq.vista.ui.fragments.*
import ac.mdiq.vista.ui.fragments.FeedFragment.Companion.newInstance
import ac.mdiq.vista.ui.holder.PlayerHolder
import ac.mdiq.vista.ui.util.ShareUtils.installApp
import ac.mdiq.vista.ui.util.ShareUtils.tryOpenIntentInApp
import ac.mdiq.vista.util.*
import ac.mdiq.vista.util.ListHelper.getDefaultAudioFormat
import ac.mdiq.vista.util.ListHelper.getDefaultResolutionIndex
import ac.mdiq.vista.util.ListHelper.getSortedStreamVideosList
import ac.mdiq.vista.util.ListHelper.getUrlAndNonTorrentStreams

@UnstableApi object NavigationHelper {
    private const val MAIN_FRAGMENT_TAG: String = "main_fragment_tag"
    private const val SEARCH_FRAGMENT_TAG: String = "search_fragment_tag"

    private val TAG: String = NavigationHelper::class.java.simpleName

    fun <T> getPlayerIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?, resumePlayback: Boolean): Intent {
        val intent = Intent(context, targetClazz)

        if (playQueue != null) {
            val cacheKey = SerializedCache.instance.put(playQueue, PlayQueue::class.java)
            if (cacheKey != null) intent.putExtra(PlayerManager.PLAY_QUEUE_KEY, cacheKey)
        }
        intent.putExtra(PlayerManager.PLAYER_TYPE, PlayerType.MAIN.valueForIntent())
        intent.putExtra(PlayerManager.RESUME_PLAYBACK, resumePlayback)

        return intent
    }

    fun <T> getPlayerIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?, resumePlayback: Boolean, playWhenReady: Boolean): Intent {
        return getPlayerIntent(context, targetClazz, playQueue, resumePlayback).putExtra(PlayerManager.PLAY_WHEN_READY, playWhenReady)
    }

    private fun <T> getPlayerEnqueueIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?): Intent {
        // when enqueueing `resumePlayback` is always `false` since:
        // - if there is a video already playing, the value of `resumePlayback` just doesn't make
        //   any difference.
        // - if there is nothing already playing, it is useful for the enqueue action to have a
        //   slightly different behaviour than the normal play action: the latter resumes playback,
        //   the former doesn't. (note that enqueue can be triggered when nothing is playing only
        //   by long pressing the video detail fragment, playlist or channel controls
        return getPlayerIntent(context, targetClazz, playQueue, false).putExtra(PlayerManager.ENQUEUE, true)
    }

    private fun <T> getPlayerEnqueueNextIntent(context: Context, targetClazz: Class<T>, playQueue: PlayQueue?): Intent {
        // see comment in `getPlayerEnqueueIntent` as to why `resumePlayback` is false
        return getPlayerIntent(context, targetClazz, playQueue, false).putExtra(PlayerManager.ENQUEUE_NEXT, true)
    }

    fun playOnMainPlayer(activity: AppCompatActivity, playQueue: PlayQueue) {
        val item = playQueue.item ?: return
        openVideoDetailFragment(activity, activity.supportFragmentManager, item.serviceId, item.url, item.title, playQueue, false)
    }

    fun playOnMainPlayer(context: Context, playQueue: PlayQueue, switchingPlayers: Boolean) {
        val item = playQueue.item ?: return
        openVideoDetail(context, item.serviceId, item.url, item.title, playQueue, switchingPlayers)
    }

    fun playOnPopupPlayer(context: Context, queue: PlayQueue?, resumePlayback: Boolean) {
        if (!PermissionHelper.isPopupEnabledElseAsk(context)) return
        Toast.makeText(context, R.string.popup_playing_toast, Toast.LENGTH_SHORT).show()
        val intent = getPlayerIntent(context, PlayerService::class.java, queue, resumePlayback)
        intent.putExtra(PlayerManager.PLAYER_TYPE, PlayerType.POPUP.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    fun playOnBackgroundPlayer(context: Context, queue: PlayQueue?, resumePlayback: Boolean) {
        Toast.makeText(context, R.string.background_player_playing_toast, Toast.LENGTH_SHORT).show()
        val intent = getPlayerIntent(context, PlayerService::class.java, queue, resumePlayback)
        intent.putExtra(PlayerManager.PLAYER_TYPE, PlayerType.AUDIO.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    fun enqueueOnPlayer(context: Context, queue: PlayQueue?, playerType: PlayerType) {
        if (playerType == PlayerType.POPUP && !PermissionHelper.isPopupEnabledElseAsk(context)) return
        Toast.makeText(context, R.string.enqueued, Toast.LENGTH_SHORT).show()
        val intent = getPlayerEnqueueIntent(context, PlayerService::class.java, queue)
        intent.putExtra(PlayerManager.PLAYER_TYPE, playerType.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    fun enqueueOnPlayer(context: Context, queue: PlayQueue?) {
        var playerType = PlayerHolder.instance?.type
        if (playerType == null) {
            Log.e(TAG, "Enqueueing but no player is open; defaulting to background player")
            playerType = PlayerType.AUDIO
        }
        enqueueOnPlayer(context, queue, playerType)
    }

    fun enqueueNextOnPlayer(context: Context, queue: PlayQueue?) {
        var playerType = PlayerHolder.instance?.type
        if (playerType == null) {
            Log.e(TAG, "Enqueueing next but no player is open; defaulting to background player")
            playerType = PlayerType.AUDIO
        }
        Toast.makeText(context, R.string.enqueued_next, Toast.LENGTH_SHORT).show()
        val intent = getPlayerEnqueueNextIntent(context, PlayerService::class.java, queue)
        intent.putExtra(PlayerManager.PLAYER_TYPE, playerType.valueForIntent())
        ContextCompat.startForegroundService(context, intent)
    }

    fun playOnExternalAudioPlayer(context: Context, info: StreamInfo) {
        val audioStreams = info.audioStreams
        if (audioStreams.isEmpty()) {
            Toast.makeText(context, R.string.audio_streams_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val audioStreamsForExternalPlayers: List<AudioStream?> = getUrlAndNonTorrentStreams(audioStreams)
        if (audioStreamsForExternalPlayers.isEmpty()) {
            Toast.makeText(context, R.string.no_audio_streams_available_for_external_players, Toast.LENGTH_SHORT).show()
            return
        }
        val index = getDefaultAudioFormat(context, audioStreamsForExternalPlayers)
        val audioStream = audioStreamsForExternalPlayers[index]!!
        playOnExternalPlayer(context, info.name, info.uploaderName, audioStream)
    }

    fun playOnExternalVideoPlayer(context: Context, info: StreamInfo) {
        val videoStreams = info.videoStreams
        if (videoStreams.isEmpty()) {
            Toast.makeText(context, R.string.video_streams_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val videoStreamsForExternalPlayers = getSortedStreamVideosList(context,
            getUrlAndNonTorrentStreams(videoStreams), null, false, false)
        if (videoStreamsForExternalPlayers.isEmpty()) {
            Toast.makeText(context, R.string.no_video_streams_available_for_external_players, Toast.LENGTH_SHORT).show()
            return
        }
        val index = getDefaultResolutionIndex(context, videoStreamsForExternalPlayers)
        val videoStream = videoStreamsForExternalPlayers[index]
        playOnExternalPlayer(context, info.name, info.uploaderName, videoStream)
    }

    fun playOnExternalPlayer(context: Context, name: String?, artist: String?, stream: Stream) {
        val deliveryMethod = stream.deliveryMethod
        if (!stream.isUrl || deliveryMethod == DeliveryMethod.TORRENT) {
            Toast.makeText(context, R.string.selected_stream_external_player_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = when (deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP ->
                if (stream.format == null) {
                    when (stream) {
                        is AudioStream -> "audio/*"
                        is VideoStream -> "video/*"
                        // This should never be reached, because subtitles are not opened in
                        // external players
                        else -> return
                    }
                } else stream.format!!.mimeType
            DeliveryMethod.HLS -> "application/x-mpegURL"
            DeliveryMethod.DASH -> "application/dash+xml"
            DeliveryMethod.SS -> "application/vnd.ms-sstr+xml"
            // Torrent streams are not exposed to external players
            else -> ""
        }
        val intent = Intent()
        intent.setAction(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.parse(stream.content), mimeType)
        intent.putExtra(Intent.EXTRA_TITLE, name)
        intent.putExtra("title", name)
        intent.putExtra("artist", artist)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        resolveActivityOrAskToInstall(context, intent)
    }

    private fun resolveActivityOrAskToInstall(context: Context, intent: Intent) {
        if (!tryOpenIntentInApp(context, intent)) {
            if (context is Activity) AlertDialog.Builder(context)
                .setMessage(R.string.no_player_found)
                .setPositiveButton(R.string.install) { _: DialogInterface?, _: Int -> installApp(context, context.getString(R.string.vlc_package)) }
                .setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int -> Log.i("NavigationHelper", "You unlocked a secret unicorn.") }
                .show()
            else Toast.makeText(context, R.string.no_player_found_toast, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("CommitTransaction")
    private fun defaultTransaction(fragmentManager: FragmentManager): FragmentTransaction {
        return fragmentManager.beginTransaction()
            .setCustomAnimations(R.animator.custom_fade_in, R.animator.custom_fade_out, R.animator.custom_fade_in, R.animator.custom_fade_out)
    }

    fun gotoMainFragment(fragmentManager: FragmentManager) {
//        if calling popBackStackImmediate directly, it can throw an exception of IllegalStateException: Fragment no longer exists for key f0
        if (fragmentManager.findFragmentByTag(MAIN_FRAGMENT_TAG) != null) fragmentManager.popBackStack(MAIN_FRAGMENT_TAG, 0)
//            fragmentManager.popBackStackImmediate(MAIN_FRAGMENT_TAG, 0)
        else openMainFragment(fragmentManager)
    }

    fun openMainFragment(fragmentManager: FragmentManager) {
        InfoCache.instance.trimCache()
        fragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, MainFragment())
            .addToBackStack(MAIN_FRAGMENT_TAG)
            .commit()
    }

    fun tryGotoSearchFragment(fragmentManager: FragmentManager): Boolean {
        for (i in 0 until fragmentManager.backStackEntryCount) {
            Logd("NavigationHelper", "tryGoToSearchFragment() [$i] = [${fragmentManager.getBackStackEntryAt(i)}]")
        }
        return fragmentManager.popBackStackImmediate(SEARCH_FRAGMENT_TAG, 0)
    }

    fun openSearchFragment(fragmentManager: FragmentManager, serviceId: Int, searchString: String) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, SearchFragment.getInstance(serviceId, searchString))
            .addToBackStack(SEARCH_FRAGMENT_TAG)
            .commit()
    }

    fun expandMainPlayer(context: Context) {
        context.sendBroadcast(Intent(VideoDetailFragment.ACTION_SHOW_MAIN_PLAYER).setPackage(context.packageName))
    }

    fun sendPlayerStartedEvent(context: Context) {
        context.sendBroadcast(Intent(VideoDetailFragment.ACTION_PLAYER_STARTED).setPackage(context.packageName))
    }

    fun showMiniPlayer(fragmentManager: FragmentManager) {
        val instance = VideoDetailFragment.instanceInCollapsedState
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_player_holder, instance)
            .runOnCommit { sendPlayerStartedEvent(instance.requireActivity()) }
            .commitAllowingStateLoss()
    }

    fun openVideoDetailFragment(context: Context, fragmentManager: FragmentManager, serviceId: Int, url: String?, title: String, playQueue: PlayQueue?, switchingPlayers: Boolean) {
        val autoPlay: Boolean
        val playerType = PlayerHolder.instance?.type
        Logd(TAG, "openVideoDetailFragment: $playerType")
        autoPlay = when {
            // no player open
            playerType == null -> PlayerHelper.isAutoplayAllowedByUser(context)
            // switching player to main player
            // keep play/pause state
            switchingPlayers -> PlayerHolder.instance?.isPlaying ?: false
            // opening new stream while already playing in main player
            playerType == PlayerType.MAIN -> PlayerHelper.isAutoplayAllowedByUser(context)
            // opening new stream while already playing in another player
            else -> false
        }

        val onVideoDetailFragmentReady: RunnableWithVideoDetailFragment = object: RunnableWithVideoDetailFragment {
            override fun run(detailFragment: VideoDetailFragment?) {
                if (detailFragment == null) return
                expandMainPlayer(detailFragment.requireActivity())
                detailFragment.setAutoPlay(autoPlay)
                // Situation when user switches from players to main player. All needed data is
                // here, we can start watching (assuming newQueue equals playQueue).
                // Starting directly in fullscreen if the previous player type was popup.
                if (switchingPlayers) detailFragment.openVideoPlayer(
                    playerType == PlayerType.POPUP || PlayerHelper.isStartMainPlayerFullscreenEnabled(context))
                else detailFragment.selectAndLoadVideo(serviceId, url, title, playQueue)
                detailFragment.scrollToTop()
            }
        }

        val fragment = fragmentManager.findFragmentById(R.id.fragment_player_holder)
        if (fragment is VideoDetailFragment && fragment.isVisible()) onVideoDetailFragmentReady.run(fragment as VideoDetailFragment?)
        else {
            val instance = VideoDetailFragment.getInstance(serviceId, url, title, playQueue)
            instance.setAutoPlay(autoPlay)
            defaultTransaction(fragmentManager)
                .replace(R.id.fragment_player_holder, instance)
                .runOnCommit { onVideoDetailFragmentReady.run(instance) }
                .commit()
        }
    }

    fun openChannelFragment(fragmentManager: FragmentManager, serviceId: Int, url: String?, name: String) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, ChannelFragment.getInstance(serviceId, url?:"", name))
            .addToBackStack(null)
            .commit()
    }

    fun openChannelFragment(fragment: Fragment, item: StreamInfoItem, uploaderUrl: String?) {
        // For some reason `getParentFragmentManager()` doesn't work, but this does.
        openChannelFragment(fragment.requireActivity().supportFragmentManager, item.serviceId, uploaderUrl, item.uploaderName?:"")
    }

    /**
     * Opens the comment author channel fragment, if the [CommentsInfoItem.uploaderUrl]
     * of `comment` is non-null. Shows a UI-error snackbar if something goes wrong.
     *
     * @param activity the activity with the fragment manager and in which to show the snackbar
     * @param comment the comment whose uploader/author will be opened
     */
    fun openCommentAuthorIfPresent(activity: FragmentActivity, comment: CommentsInfoItem) {
        if (comment.uploaderUrl.isNullOrEmpty()) return
        try {
            openChannelFragment(activity.supportFragmentManager, comment.serviceId, comment.uploaderUrl, comment.uploaderName?:"")
        } catch (e: Exception) {
            showUiErrorSnackbar(activity, "Opening channel fragment", e)
        }
    }

    fun openCommentRepliesFragment(activity: FragmentActivity, comment: CommentsInfoItem) {
        defaultTransaction(activity.supportFragmentManager)
            .replace(R.id.fragment_holder, CommentRepliesFragment(comment), CommentRepliesFragment.TAG)
            .addToBackStack(CommentRepliesFragment.TAG)
            .commit()
    }

    fun openPlaylistFragment(fragmentManager: FragmentManager, serviceId: Int, url: String?, name: String) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, PlaylistFragment.getInstance(serviceId, url, name))
            .addToBackStack(null)
            .commit()
    }

    @JvmOverloads
    fun openFeedFragment(fragmentManager: FragmentManager, groupId: Long = FeedGroupEntity.GROUP_ALL_ID, groupName: String? = null) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, newInstance(groupId, groupName))
            .addToBackStack(null)
            .commit()
    }

    fun openBookmarksFragment(fragmentManager: FragmentManager) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, BookmarkFragment())
            .addToBackStack(null)
            .commit()
    }

    fun openSubscriptionFragment(fragmentManager: FragmentManager) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, SubscriptionFragment())
            .addToBackStack(null)
            .commit()
    }

    @Throws(ExtractionException::class)
    fun openKioskFragment(fragmentManager: FragmentManager, serviceId: Int, kioskId: String?) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, KioskFragment.getInstance(serviceId, kioskId?:""))
            .addToBackStack(null)
            .commit()
    }

    fun openLocalPlaylistFragment(fragmentManager: FragmentManager, playlistId: Long, name: String?) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, LocalPlaylistFragment.getInstance(playlistId, name ?: ""))
            .addToBackStack(null)
            .commit()
    }

    fun openStatisticFragment(fragmentManager: FragmentManager) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, StatisticsPlaylistFragment())
            .addToBackStack(null)
            .commit()
    }

    fun openSubscriptionsImportFragment(fragmentManager: FragmentManager, serviceId: Int) {
        defaultTransaction(fragmentManager)
            .replace(R.id.fragment_holder, SubscriptionsImportFragment.getInstance(serviceId))
            .addToBackStack(null)
            .commit()
    }

    fun openSearch(context: Context, serviceId: Int, searchString: String?) {
        val mIntent = Intent(context, MainActivity::class.java)
        mIntent.putExtra(KEY_SERVICE_ID, serviceId)
        mIntent.putExtra(KEY_SEARCH_STRING, searchString)
        mIntent.putExtra(KEY_OPEN_SEARCH, true)
        context.startActivity(mIntent)
    }

    fun openVideoDetail(context: Context, serviceId: Int, url: String?, title: String, playQueue: PlayQueue?, switchingPlayers: Boolean) {
        val intent = getStreamIntent(context, serviceId, url, title).putExtra(VideoDetailFragment.KEY_SWITCHING_PLAYERS, switchingPlayers)
        if (playQueue != null) {
            val cacheKey = SerializedCache.instance.put(playQueue, PlayQueue::class.java)
            if (cacheKey != null) intent.putExtra(PlayerManager.PLAY_QUEUE_KEY, cacheKey)
        }
        context.startActivity(intent)
    }

    /**
     * Opens [ChannelFragment].
     * Use this instead of [.openChannelFragment]
     * when no fragments are used / no FragmentManager is available.
     * @param context
     * @param serviceId
     * @param url
     * @param title
     */
    fun openChannelFragmentUsingIntent(context: Context, serviceId: Int, url: String?, title: String) {
        val intent = getOpenIntent(context, url, serviceId, LinkType.CHANNEL)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(KEY_TITLE, title)
        context.startActivity(intent)
    }

    fun openMainActivity(context: Context) {
        val mIntent = Intent(context, MainActivity::class.java)
        mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(mIntent)
    }

    fun openRouterActivity(context: Context, url: String?) {
        val mIntent = Intent(context, RouterActivity::class.java)
        mIntent.setData(Uri.parse(url))
        context.startActivity(mIntent)
    }

    fun openAbout(context: Context) {
        val intent = Intent(context, AboutActivity::class.java)
        context.startActivity(intent)
    }

    fun openSettings(context: Context) {
        val intent = Intent(context, SettingsActivity::class.java)
        context.startActivity(intent)
    }

    fun openDownloads(activity: Activity) {
        if (PermissionHelper.checkStoragePermissions(activity, PermissionHelper.DOWNLOADS_REQUEST_CODE)) {
            val intent = Intent(activity, DownloadActivity::class.java)
            activity.startActivity(intent)
        }
    }

    fun getPlayQueueActivityIntent(context: Context?): Intent {
        val intent = Intent(context, PlayQueueActivity::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    fun openPlayQueue(context: Context) {
        val intent = Intent(context, PlayQueueActivity::class.java)
        context.startActivity(intent)
    }

    private fun getOpenIntent(context: Context, url: String?, serviceId: Int, type: LinkType): Intent {
        val mIntent = Intent(context, MainActivity::class.java)
        mIntent.putExtra(KEY_SERVICE_ID, serviceId)
        mIntent.putExtra(KEY_URL, url)
        mIntent.putExtra(KEY_LINK_TYPE, type)
        return mIntent
    }

    @Throws(ExtractionException::class)
    fun getIntentByLink(context: Context, url: String): Intent {
        return getIntentByLink(context, Vista.getServiceByUrl(url), url)
    }

    @Throws(ExtractionException::class)
    fun getIntentByLink(context: Context, service: StreamingService, url: String): Intent {
        val linkType = service.getLinkTypeByUrl(url)
        if (linkType == LinkType.NONE) throw ExtractionException("Url not known to service. service=$service url=$url")
        return getOpenIntent(context, url, service.serviceId, linkType)
    }

    fun getChannelIntent(context: Context, serviceId: Int, url: String?): Intent {
        return getOpenIntent(context, url, serviceId, LinkType.CHANNEL)
    }

    fun getStreamIntent(context: Context, serviceId: Int, url: String?, title: String?): Intent {
        return getOpenIntent(context, url, serviceId, LinkType.STREAM).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(KEY_TITLE, title)
    }

    /**
     * Finish this `Activity` as well as all `Activities` running below it
     * and then start `MainActivity`.
     * @param activity the activity to finish
     */
    fun restartApp(activity: Activity) {
        VoiVistaDatabase.close()
        ProcessPhoenix.triggerRebirth(activity.applicationContext)
    }

    private interface RunnableWithVideoDetailFragment {
        fun run(detailFragment: VideoDetailFragment?)
    }
}
