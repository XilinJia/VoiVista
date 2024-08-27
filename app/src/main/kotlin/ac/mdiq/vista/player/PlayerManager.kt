package ac.mdiq.vista.player

import ac.mdiq.vista.R
import ac.mdiq.vista.database.stream.StreamTypeUtil.isAudio
import ac.mdiq.vista.database.stream.StreamTypeUtil.isLiveStream
import ac.mdiq.vista.database.stream.StreamTypeUtil.isVideo
import ac.mdiq.vista.database.stream.model.StreamStateEntity
import ac.mdiq.vista.databinding.PlayerBinding
import ac.mdiq.vista.extractor.Image
import ac.mdiq.vista.extractor.ServiceList
import ac.mdiq.vista.extractor.exceptions.ExtractionException
import ac.mdiq.vista.extractor.services.youtube.YoutubeParsingHelper
import ac.mdiq.vista.extractor.services.youtube.dashmanifestcreators.CreationException
import ac.mdiq.vista.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import ac.mdiq.vista.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import ac.mdiq.vista.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import ac.mdiq.vista.extractor.stream.*
import ac.mdiq.vista.giga.DownloaderImpl
import ac.mdiq.vista.manager.HistoryRecordManager
import ac.mdiq.vista.player.PlayerManager.PlaybackResolver.Companion.buildMediaSource
import ac.mdiq.vista.player.PlayerManager.PlaybackResolver.Companion.cacheKeyOf
import ac.mdiq.vista.player.PlayerManager.PlaybackResolver.Companion.maybeBuildLiveMediaSource
import ac.mdiq.vista.player.PlayerType.Companion.retrieveFromIntent
import ac.mdiq.vista.player.helper.*
import ac.mdiq.vista.player.helper.PlayerHelper.getProgressiveLoadIntervalBytes
import ac.mdiq.vista.player.helper.PlayerHelper.preferredCacheSize
import ac.mdiq.vista.player.notification.NotificationConstants
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.player.playqueue.PlayQueueItem
import ac.mdiq.vista.player.playqueue.events.*
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.ui.fragments.VideoDetailFragment
import ac.mdiq.vista.ui.player.*
import ac.mdiq.vista.ui.util.NavigationHelper.sendPlayerStartedEvent
import ac.mdiq.vista.util.DependentPreferenceHelper.getResumePlaybackEnabled
import ac.mdiq.vista.util.ListHelper.getAudioFormatIndex
import ac.mdiq.vista.util.ListHelper.getDefaultResolutionIndex
import ac.mdiq.vista.util.ListHelper.getFilteredAudioStreams
import ac.mdiq.vista.util.ListHelper.getPlayableStreams
import ac.mdiq.vista.util.ListHelper.getPopupDefaultResolutionIndex
import ac.mdiq.vista.util.ListHelper.getPopupResolutionIndex
import ac.mdiq.vista.util.ListHelper.getResolutionIndex
import ac.mdiq.vista.util.ListHelper.getSortedStreamVideosList
import ac.mdiq.vista.util.Localization.assureCorrectAppLanguage
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.NO_SERVICE_ID
import ac.mdiq.vista.util.SerializedCache
import ac.mdiq.vista.util.ServiceHelper.getCacheExpirationMillis
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.error.ErrorUtil.Companion.createNotification
import ac.mdiq.vista.util.image.ImageStrategy.choosePreferredImage
import ac.mdiq.vista.util.image.PicassoHelper.cancelTag
import ac.mdiq.vista.util.image.PicassoHelper.loadScaledDownThumbnail
import android.content.*
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.collection.ArraySet
import androidx.core.math.MathUtils
import androidx.media3.common.*
import androidx.media3.common.MediaItem.LiveConfiguration
import androidx.media3.common.Player.DiscontinuityReason
import androidx.media3.common.Player.PositionInfo
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Assertions
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.smoothstreaming.DefaultSsChunkSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifestParser
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.preference.PreferenceManager
import com.google.common.base.Predicate
import com.google.common.net.HttpHeaders
import com.squareup.picasso.Picasso.LoadedFrom
import com.squareup.picasso.Target
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.disposables.SerialDisposable
import io.reactivex.rxjava3.internal.subscriptions.EmptySubscription
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.io.*
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.stream.IntStream
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min

interface PlaybackListener {
    /**
     * Called to check if the currently playing stream is approaching the end of its playback.
     * Implementation should return true when the current playback position is progressing within
     * timeToEndMillis or less to its playback during.
     *
     * May be called at any time.
     *
     * @param timeToEndMillis
     * @return whether the stream is approaching end of playback
     */
    fun isApproachingPlaybackEdge(timeToEndMillis: Long): Boolean

    /**
     * Called when the stream at the current queue index is not ready yet.
     * Signals to the listener to block the player from playing anything and notify the source
     * is now invalid.
     *
     * May be called at any time.
     *
     */
    fun onPlaybackBlock()

    /**
     * Called when the stream at the current queue index is ready.
     * Signals to the listener to resume the player by preparing a new source.
     *
     *
     * May be called only when the player is blocked.
     *
     *
     * @param mediaSource
     */
    fun onPlaybackUnblock(mediaSource: MediaSource)

    /**
     * Called when the queue index is refreshed.
     * Signals to the listener to synchronize the player's window to the manager's
     * window.
     *
     *
     * May be called anytime at any amount once unblock is called.
     *
     *
     * @param item          item the player should be playing/synchronized to
     * @param wasBlocked    was the player recently released from blocking state
     */
    fun onPlaybackSynchronize(item: PlayQueueItem, wasBlocked: Boolean)

    /**
     * Requests the listener to resolve a stream info into a media source
     * according to the listener's implementation (background, popup or main video player).
     * May be called at any time.
     *
     * @param item
     * @param info
     * @return the corresponding [MediaSource]
     */
    fun sourceOf(item: PlayQueueItem, info: StreamInfo): MediaSource?

    /**
     * Called when the play queue can no longer be played or used.
     * Currently, this means the play queue is empty and complete.
     * Signals to the listener that it should shutdown.
     *
     *
     * May be called at any time.
     *
     */
    fun onPlaybackShutdown()

    /**
     * Called whenever the play queue was edited (items were added, deleted or moved),
     * use this to e.g. update notification buttons or fragment ui.
     *
     *
     * May be called at any time.
     *
     */
    fun onPlayQueueEdited()
}

//TODO try to remove and replace everything with context
@UnstableApi
class PlayerManager(@JvmField val service: PlayerService) : PlaybackListener, Player.Listener {
    // play queue might be null e.g. while player is starting
    var playQueue: PlayQueue? = null
        private set

    private var playQueueManager: MediaSourceManager? = null

    var currentItem: PlayQueueItem? = null
        private set
    var currentMetadata: MediaItemTag? = null
        private set
    var thumbnail: Bitmap? = null
        private set

    var exoPlayer: ExoPlayer? = null
        private set
    var audioReactor: AudioReactor? = null
        private set

    @JvmField
    val trackSelector: DefaultTrackSelector
    private val loadController: LoadController
    private val renderFactory: DefaultRenderersFactory

    private val videoResolver: VideoPlaybackResolver
    private val audioResolver: AudioPlaybackResolver

    var playerType: PlayerType = PlayerType.MAIN
        private set
    var currentState: Int = STATE_PREFLIGHT
        private set

    // audio only mode does not mean that player type is background, but that the player was
    // minimized to background but will resume automatically to the original player type
    var isAudioOnly: Boolean = false
        private set
    private var isPrepared = false

    // keep the unusual member name
    val UIs: PlayerUiList

    private lateinit var broadcastReceiver: BroadcastReceiver
    private lateinit var intentFilter: IntentFilter

    private var fragmentListener: PlayerServiceEventListener? = null
    private var activityListener: PlayerEventListener? = null

    private val progressUpdateDisposable = SerialDisposable()
    private val databaseUpdateDisposable = CompositeDisposable()

    // This is the only listener we need for thumbnail loading, since there is always at most only
    // one thumbnail being loaded at a time. This field is also here to maintain a strong reference,
    // which would otherwise be garbage collected since Picasso holds weak references to targets.
    private val currentThumbnailTarget: Target

    @JvmField
    val context: Context = service

    @JvmField
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val recordManager = HistoryRecordManager(context)

    var playbackSpeed: Float
        get() = playbackParameters.speed
        set(speed) {
            setPlaybackParameters(speed, playbackPitch, playbackSkipSilence)
        }

    val playbackPitch: Float
        get() = playbackParameters.pitch

    val playbackSkipSilence: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.skipSilenceEnabled

    val playbackParameters: PlaybackParameters
        get() {
            if (exoPlayerIsNull()) return PlaybackParameters.DEFAULT
            return exoPlayer!!.playbackParameters
        }

    private val qualityResolver: VideoPlaybackResolver.QualityResolver
        get() = object : VideoPlaybackResolver.QualityResolver {
            override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>?): Int {
                return when {
                    sortedVideos == null -> 0
                    videoPlayerSelected() -> getDefaultResolutionIndex(context, sortedVideos)
                    else -> getPopupDefaultResolutionIndex(context, sortedVideos)
                }
            }
            override fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>?, playbackQuality: String?): Int {
                return when {
                    sortedVideos == null -> 0
                    videoPlayerSelected() -> getResolutionIndex(context, sortedVideos, playbackQuality)
                    else -> getPopupResolutionIndex(context, sortedVideos, playbackQuality)
                }
            }
        }

    val selectedVideoStream: Optional<VideoStream>
        get() = Optional.ofNullable(currentMetadata)
            .flatMap { obj: MediaItemTag -> obj.maybeQuality }
            .filter { quality: MediaItemTag.Quality ->
                val selectedStreamIndex = quality.selectedVideoStreamIndex
                (selectedStreamIndex >= 0 && selectedStreamIndex < quality.sortedVideoStreams.size)
            }
            .map { quality: MediaItemTag.Quality -> quality.sortedVideoStreams[quality.selectedVideoStreamIndex] }

    val selectedAudioStream: Optional<AudioStream?>?
        get() = Optional.ofNullable<MediaItemTag>(currentMetadata)
            .flatMap { obj: MediaItemTag? -> obj?.maybeAudioTrack }
            .map { it.selectedAudioStream }

    val captionRendererIndex: Int
        get() {
            if (exoPlayerIsNull()) return RENDERER_UNAVAILABLE
            for (t in 0 until exoPlayer!!.rendererCount) {
                if (exoPlayer!!.getRendererType(t) == C.TRACK_TYPE_TEXT) return t
            }
            return RENDERER_UNAVAILABLE
        }

    val currentStreamInfo: Optional<StreamInfo>
        get() = Optional.ofNullable(currentMetadata).flatMap { obj: MediaItemTag -> obj.maybeStreamInfo }

    val isStopped: Boolean
        get() = exoPlayerIsNull() || exoPlayer!!.playbackState == ExoPlayer.STATE_IDLE

    val isPlaying: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.isPlaying

    val playWhenReady: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.playWhenReady

    val isLoading: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.isLoading

    private val isLive: Boolean
        get() {
            try {
                return !exoPlayerIsNull() && exoPlayer!!.isCurrentMediaItemDynamic
            } catch (e: IndexOutOfBoundsException) {
                // Why would this even happen =(... but lets log it anyway, better safe than sorry
                Logd(TAG, "player.isCurrentWindowDynamic() failed: $e")
                return false
            }
        }

    /**
     * Get the video renderer index of the current playing stream.
     * This method returns the video renderer index of the current
     * [MappedTrackInfo] or [.RENDERER_UNAVAILABLE] if the current
     * [MappedTrackInfo] is null or if there is no video renderer index.
     * @return the video renderer index or [.RENDERER_UNAVAILABLE] if it cannot be get
     */
    private val videoRendererIndex: Int
        get() {
            val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return RENDERER_UNAVAILABLE
            // Check every renderer
            return IntStream.range(0, mappedTrackInfo.rendererCount) // Check the renderer is a video renderer and has at least one track
                // Return the first index found (there is at most one renderer per renderer type)
                .filter { i: Int -> (!mappedTrackInfo.getTrackGroups(i).isEmpty && exoPlayer!!.getRendererType(i) == C.TRACK_TYPE_VIDEO) }
                .findFirst() // No video renderer index with at least one track found: return unavailable index
                .orElse(RENDERER_UNAVAILABLE)
        }

    val isProgressLoopRunning: Boolean
        get() = progressUpdateDisposable.get() != null

    var repeatMode: @Player.RepeatMode Int
        get() = if (exoPlayerIsNull()) Player.REPEAT_MODE_OFF else exoPlayer!!.repeatMode
        set(repeatMode) {
            if (!exoPlayerIsNull()) exoPlayer!!.repeatMode = repeatMode
        }

    val isMuted: Boolean
        get() = !exoPlayerIsNull() && exoPlayer!!.volume == 0f

    val isLiveEdge: Boolean
        /**
         * Checks if the current playback is a livestream AND is playing at or beyond the live edge.
         *
         * @return whether the livestream is playing at or beyond the edge
         */
        get() {
            if (exoPlayerIsNull() || !isLive) return false
            val currentTimeline = exoPlayer!!.currentTimeline
            val currentWindowIndex = exoPlayer!!.currentMediaItemIndex
            if (currentTimeline.isEmpty || currentWindowIndex < 0 || currentWindowIndex >= currentTimeline.windowCount) return false
            val timelineWindow = Timeline.Window()
            currentTimeline.getWindow(currentWindowIndex, timelineWindow)
            return timelineWindow.defaultPositionMs <= exoPlayer!!.currentPosition
        }

    val videoUrl: String
        get() = currentMetadata?.streamUrl ?: context.getString(R.string.unknown_content)

    val videoUrlAtCurrentTime: String
        get() {
            val timeSeconds = exoPlayer!!.currentPosition / 1000
            var videoUrl = videoUrl
            // Timestamp doesn't make sense in a live stream so drop it
            if (!isLive && timeSeconds >= 0 && currentMetadata?.serviceId == ServiceList.YouTube.serviceId) videoUrl += ("&t=$timeSeconds")
            return videoUrl
        }

    val videoTitle: String
        get() = currentMetadata?.title ?: context.getString(R.string.unknown_content)

    val uploaderName: String
        get() = currentMetadata?.uploaderName ?: context.getString(R.string.unknown_content)

    init {
        setupBroadcastReceiver()

        trackSelector = DefaultTrackSelector(context, PlayerHelper.qualitySelector)
        val dataSource = PlayerDataSource(context, DefaultBandwidthMeter.Builder(context).build())
        loadController = LoadController()

        renderFactory =
            if (prefs.getBoolean(context.getString(R.string.always_use_exoplayer_set_output_surface_workaround_key), false))
                CustomRenderersFactory(context)
            else DefaultRenderersFactory(context)

        renderFactory.setEnableDecoderFallback(prefs.getBoolean(context.getString(R.string.use_exoplayer_decoder_fallback_key), false))

        videoResolver = VideoPlaybackResolver(context, dataSource, qualityResolver)
        audioResolver = AudioPlaybackResolver(context, dataSource)

        currentThumbnailTarget = getCurrentThumbnailTarget()

        // The UIs added here should always be present. They will be initialized when the player
        // reaches the initialization step. Make sure the media session ui is before the
        // notification ui in the UIs list, since the notification depends on the media session in
        // PlayerUi#initPlayer(), and UIs.call() guarantees UI order is preserved.
        UIs = PlayerUiList(MediaSessionPlayerUi(this), NotificationPlayerUi(this))
    }

    fun handleIntent(intent: Intent) {
        // fail fast if no play queue was provided
        val queueCache = intent.getStringExtra(PLAY_QUEUE_KEY) ?: return
        val newQueue: PlayQueue = SerializedCache.instance.take(queueCache, PlayQueue::class.java) ?: return

        val oldPlayerType = playerType
        playerType = retrieveFromIntent(intent)
        initUIsForCurrentPlayerType()
        // We need to setup audioOnly before super(), see "sourceOf"
        isAudioOnly = audioPlayerSelected()

        if (intent.hasExtra(PLAYBACK_QUALITY)) videoResolver.playbackQuality = intent.getStringExtra(PLAYBACK_QUALITY)

        // Resolve enqueue intents// If playerType changes from one to another we should reload the player
        // (to disable/enable video stream or to set quality)
// Good to go...
        // In a case of equal PlayQueues we can re-init old one but only when it is disposed
// Completed but not found in history
        // In case any error we can start playback without history
        // resume playback only if the stream was not played to the end
        // Do not place initPlayback() in doFinally() because
        // it restarts playback after destroy()
        //.doFinally()
// Do not re-init the same PlayQueue. Save time
        // Player can have state = IDLE when playback is stopped or failed
        // and we should retry in this case
        // Player can have state = IDLE when playback is stopped or failed
        // and we should retry in this case
        when {
            intent.getBooleanExtra(ENQUEUE, false) && playQueue != null -> {
                playQueue!!.append(newQueue.streams)
                return
                // Resolve enqueue next intents
            }
            intent.getBooleanExtra(ENQUEUE_NEXT, false) && playQueue != null -> {
                val currentIndex = playQueue!!.index
                playQueue!!.append(newQueue.streams)
                playQueue!!.move(playQueue!!.size() - 1, currentIndex + 1)
                return
            }

            /*
             * TODO As seen in #7427 this does not work:
             * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
             * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
             * 2. User changed a player from, for example. main to popup, or from audio to main, etc
             * 3. User chose to resume a video based on a saved timestamp from history of played videos
             * In those cases time will be saved because re-init of the play queue is a not an instant
             *  task and requires network calls
             * */
            // seek to timestamp if stream is already playing
            else -> {
                val savedParameters = PlayerHelper.retrievePlaybackParametersFromPrefs(this)
                val playbackSpeed = savedParameters.speed
                val playbackPitch = savedParameters.pitch
                val playbackSkipSilence = prefs.getBoolean(context.getString(R.string.playback_skip_silence_key), playbackSkipSilence)

                val samePlayQueue = playQueue != null && playQueue!!.equalStreamsAndIndex(newQueue)
                val repeatMode = intent.getIntExtra(REPEAT_MODE, repeatMode)
                val playWhenReady = intent.getBooleanExtra(PLAY_WHEN_READY, true)
                val isMuted = intent.getBooleanExtra(IS_MUTED, isMuted)

                /*
             * TODO As seen in #7427 this does not work:
             * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
             * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
             * 2. User changed a player from, for example. main to popup, or from audio to main, etc
             * 3. User chose to resume a video based on a saved timestamp from history of played videos
             * In those cases time will be saved because re-init of the play queue is a not an instant
             *  task and requires network calls
             * */
                // seek to timestamp if stream is already playing
                when {
                    !exoPlayerIsNull() && newQueue.size() == 1 && newQueue.item != null && playQueue != null && playQueue!!.size() == 1
                            && playQueue!!.item != null && newQueue.item!!.url == playQueue!!.item!!.url
                            && newQueue.item!!.recoveryPosition != PlayQueueItem.RECOVERY_UNSET -> {
                        // Player can have state = IDLE when playback is stopped or failed
                        // and we should retry in this case
                        if (exoPlayer!!.playbackState == Player.STATE_IDLE) exoPlayer!!.prepare()
                        exoPlayer!!.seekTo(playQueue!!.index, newQueue.item!!.recoveryPosition)
                        exoPlayer!!.playWhenReady = playWhenReady
                    }
                    (!exoPlayerIsNull() && samePlayQueue) && playQueue != null && !playQueue!!.isDisposed -> {
                        // Do not re-init the same PlayQueue. Save time
                        // Player can have state = IDLE when playback is stopped or failed
                        // and we should retry in this case
                        if (exoPlayer!!.playbackState == Player.STATE_IDLE) exoPlayer!!.prepare()
                        exoPlayer!!.playWhenReady = playWhenReady
                    }
                    (intent.getBooleanExtra(RESUME_PLAYBACK, false) && getResumePlaybackEnabled(context) && !samePlayQueue && !newQueue.isEmpty)
                            && newQueue.item != null && newQueue.item!!.recoveryPosition == PlayQueueItem.RECOVERY_UNSET -> {
                        databaseUpdateDisposable.add(recordManager.loadStreamState(newQueue.item!!)
                            .observeOn(AndroidSchedulers.mainThread()) // Do not place initPlayback() in doFinally() because
                            // it restarts playback after destroy()
                            //.doFinally()
                            .subscribe(
                                { state: StreamStateEntity ->
                                    // resume playback only if the stream was not played to the end
                                    if (!state.isFinished(newQueue.item!!.duration)) newQueue.setRecovery(newQueue.index, state.progressMillis)
                                    initPlayback(newQueue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                                },
                                { error: Throwable? ->
                                    Log.w(TAG, "Failed to start playback", error)
                                    // In case any error we can start playback without history
                                    initPlayback(newQueue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                                },
                                {
                                    // Completed but not found in history
                                    initPlayback(newQueue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                                }
                            ))
                    }
                    // Good to go...
                    // In a case of equal PlayQueues we can re-init old one but only when it is disposed
                    else -> initPlayback((if (samePlayQueue) playQueue else newQueue)!!, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence, playWhenReady, isMuted)
                }

                if (oldPlayerType != playerType && playQueue != null) {
                    // If playerType changes from one to another we should reload the player
                    // (to disable/enable video stream or to set quality)
                    setRecovery()
                    reloadPlayQueueManager()
                }

                UIs.call { obj: PlayerUi -> obj.setupAfterIntent() }
                sendPlayerStartedEvent(context)
            }
        }
    }

    private fun initUIsForCurrentPlayerType() {
        // correct UI already in place
        if ((UIs.get(MainPlayerUi::class.java).isPresent && playerType == PlayerType.MAIN)
                || (UIs.get(PopupPlayerUi::class.java).isPresent && playerType == PlayerType.POPUP)) return

        // try to reuse binding if possible
        val binding = UIs.get(VideoPlayerUi::class.java).map { obj: VideoPlayerUi -> obj.binding }.orElseGet {
            if (playerType == PlayerType.AUDIO) return@orElseGet null
            else return@orElseGet PlayerBinding.inflate(LayoutInflater.from(context))
        }

        when (playerType) {
            PlayerType.MAIN -> {
                UIs.destroyAll(PopupPlayerUi::class.java)
                UIs.addAndPrepare(MainPlayerUi(this, binding!!))
            }
            PlayerType.POPUP -> {
                UIs.destroyAll(MainPlayerUi::class.java)
                UIs.addAndPrepare(PopupPlayerUi(this, binding!!))
            }
            PlayerType.AUDIO -> UIs.destroyAll(VideoPlayerUi::class.java)
        }
    }

    private fun initPlayback(queue: PlayQueue, repeatMode: @Player.RepeatMode Int, playbackSpeed: Float,
                             playbackPitch: Float, playbackSkipSilence: Boolean, playOnReady: Boolean, isMuted: Boolean) {
        destroyPlayer()
        initPlayer(playOnReady)
        this.repeatMode = repeatMode
        setPlaybackParameters(playbackSpeed, playbackPitch, playbackSkipSilence)

        playQueue = queue
        playQueue!!.init()
        reloadPlayQueueManager()

        UIs.call { obj: PlayerUi -> obj.initPlayback() }

        exoPlayer!!.volume = (if (isMuted) 0 else 1).toFloat()
        notifyQueueUpdateToListeners()
    }

    private fun initPlayer(playOnReady: Boolean) {
        Logd(TAG, "initPlayer() called with: playOnReady = [$playOnReady]")

        exoPlayer = ExoPlayer.Builder(context, renderFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadController)
            .setUsePlatformDiagnostics(false)
            .build()
        exoPlayer!!.addListener(this)
        exoPlayer!!.playWhenReady = playOnReady
        exoPlayer!!.setSeekParameters(PlayerHelper.getSeekParameters(context))
        exoPlayer!!.setWakeMode(C.WAKE_MODE_NETWORK)
        exoPlayer!!.setHandleAudioBecomingNoisy(true)

        audioReactor = AudioReactor(context, exoPlayer!!)

        registerBroadcastReceiver()

        // Setup UIs
        UIs.call { obj: PlayerUi -> obj.initPlayer() }

        // Disable media tunneling if requested by the user from ExoPlayer settings
        if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.disable_media_tunneling_key), false))
            trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(true))
    }

    private fun destroyPlayer() {
        Logd(TAG, "destroyPlayer() called")
        UIs.call { obj: PlayerUi -> obj.destroyPlayer() }
        if (!exoPlayerIsNull()) {
            exoPlayer?.removeListener(this)
//            exoPlayer?.stop()     // TODO: test
//            exoPlayer?.release()  // to be release in mediasession
        }
        if (isProgressLoopRunning) stopProgressLoop()

        playQueue?.dispose()
        audioReactor?.dispose()
        playQueueManager?.dispose()
    }

    fun destroy() {
        Logd(TAG, "destroy() called")
        saveStreamProgressState()
        setRecovery()
        stopActivityBinding()

        destroyPlayer()
        unregisterBroadcastReceiver()

        databaseUpdateDisposable.clear()
        progressUpdateDisposable.set(null)
        cancelLoadingCurrentThumbnail()

        UIs.destroyAll(Any::class.java) // destroy every UI: obviously every UI extends Object
    }

    fun setRecovery() {
        if (playQueue == null || exoPlayerIsNull()) return
        val queuePos = playQueue!!.index
        val windowPos = exoPlayer!!.currentPosition
        val duration = exoPlayer!!.duration

        // No checks due to https://github.com/XilinJia/VoiVista/pull/7195#issuecomment-962624380
        setRecovery(queuePos, MathUtils.clamp(windowPos, 0, duration))
    }

    private fun setRecovery(queuePos: Int, windowPos: Long) {
        if (playQueue == null || playQueue!!.size() <= queuePos) return
        Logd(TAG, "Setting recovery, queue: $queuePos, pos: $windowPos")
        playQueue!!.setRecovery(queuePos, windowPos)
    }

    private fun reloadPlayQueueManager() {
        playQueueManager?.dispose()
        if (playQueue != null) playQueueManager = MediaSourceManager(this, playQueue!!)
    }

    // own playback listener
    override fun onPlaybackShutdown() {
        Logd(TAG, "onPlaybackShutdown() called")
        // destroys the service, which in turn will destroy the player
        service.stopService()
    }

    fun smoothStopForImmediateReusing() {
        // Pausing would make transition from one stream to a new stream not smooth, so only stop
        exoPlayer!!.stop()
        setRecovery()
        UIs.call { obj: PlayerUi -> obj.smoothStopForImmediateReusing() }
    }

    /**
     * This function prepares the broadcast receiver and is called only in the constructor.
     * Therefore if you want any PlayerUi to receive a broadcast action, you should add it here,
     * even if that player ui might never be added to the player. In that case the received
     * broadcast would not do anything.
     */
    private fun setupBroadcastReceiver() {
        Logd(TAG, "setupBroadcastReceiver() called")
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                onBroadcastReceived(intent)
            }
        }
        intentFilter = IntentFilter()
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        intentFilter.addAction(NotificationConstants.ACTION_CLOSE)
        intentFilter.addAction(NotificationConstants.ACTION_PLAY_PAUSE)
        intentFilter.addAction(NotificationConstants.ACTION_PLAY_PREVIOUS)
        intentFilter.addAction(NotificationConstants.ACTION_PLAY_NEXT)
        intentFilter.addAction(NotificationConstants.ACTION_FAST_REWIND)
        intentFilter.addAction(NotificationConstants.ACTION_FAST_FORWARD)
        intentFilter.addAction(NotificationConstants.ACTION_REPEAT)
        intentFilter.addAction(NotificationConstants.ACTION_SHUFFLE)
        intentFilter.addAction(NotificationConstants.ACTION_RECREATE_NOTIFICATION)
        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_RESUMED)
        intentFilter.addAction(VideoDetailFragment.ACTION_VIDEO_FRAGMENT_STOPPED)
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        intentFilter.addAction(Intent.ACTION_SCREEN_ON)
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(Intent.ACTION_HEADSET_PLUG)
    }

    private fun onBroadcastReceived(intent: Intent?) {
//        if (intent?.action == null) return
        Logd(TAG, "onBroadcastReceived() called with: intent = [$intent]")
        when (intent?.action) {
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> pause()
            NotificationConstants.ACTION_CLOSE -> service.stopService()
            NotificationConstants.ACTION_PLAY_PAUSE -> playPause()
            NotificationConstants.ACTION_PLAY_PREVIOUS -> playPrevious()
            NotificationConstants.ACTION_PLAY_NEXT -> playNext()
            NotificationConstants.ACTION_FAST_REWIND -> fastRewind()
            NotificationConstants.ACTION_FAST_FORWARD -> fastForward()
            NotificationConstants.ACTION_REPEAT -> cycleNextRepeatMode()
            NotificationConstants.ACTION_SHUFFLE -> toggleShuffleModeEnabled()
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                assureCorrectAppLanguage(service)
                Logd(TAG, "ACTION_CONFIGURATION_CHANGED received")
            }
        }
        UIs.call { playerUi: PlayerUi -> playerUi.onBroadcastReceived(intent) }
    }

    private fun registerBroadcastReceiver() {
        // Try to unregister current first
        unregisterBroadcastReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(broadcastReceiver, intentFilter)
    }

    private fun unregisterBroadcastReceiver() {
        try {
            context.unregisterReceiver(broadcastReceiver)
        } catch (unregisteredException: IllegalArgumentException) {
            Log.w(TAG, "Broadcast receiver already unregistered: " + unregisteredException.message)
        }
    }

    private fun getCurrentThumbnailTarget(): Target {
        // a Picasso target is just a listener for thumbnail loading events
        return object : Target {
            override fun onBitmapLoaded(bitmap: Bitmap, from: LoadedFrom) {
                Logd(TAG, "Thumbnail - onBitmapLoaded() called with: bitmap = [$bitmap -> ${bitmap.width}x${bitmap.height}], from = [$from]")
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(bitmap)
            }
            override fun onBitmapFailed(e: Exception, errorDrawable: Drawable) {
                Log.e(TAG, "Thumbnail - onBitmapFailed() called", e)
                // there is a new thumbnail, so e.g. the end screen thumbnail needs to change, too.
                onThumbnailLoaded(null)
            }
            override fun onPrepareLoad(placeHolderDrawable: Drawable) {
                Logd(TAG, "Thumbnail - onPrepareLoad() called")
            }
        }
    }

    private fun loadCurrentThumbnail(thumbnails: List<Image>) {
        Logd(TAG, "Thumbnail - loadCurrentThumbnail() called with thumbnails = [${thumbnails.size}]")
        // first cancel any previous loading
        cancelLoadingCurrentThumbnail()

        // Unset currentThumbnail, since it is now outdated. This ensures it is not used in media
        // session metadata while the new thumbnail is being loaded by Picasso.
        onThumbnailLoaded(null)
        if (thumbnails.isEmpty()) return

        // scale down the notification thumbnail for performance
        loadScaledDownThumbnail(context, thumbnails)
            .tag(PICASSO_PLAYER_THUMBNAIL_TAG)
            .into(currentThumbnailTarget)
    }

    private fun cancelLoadingCurrentThumbnail() {
        // cancel the Picasso job associated with the player thumbnail, if any
        cancelTag(PICASSO_PLAYER_THUMBNAIL_TAG)
    }

    private fun onThumbnailLoaded(bitmap: Bitmap?) {
        // Avoid useless thumbnail updates, if the thumbnail has not actually changed. Based on the
        // thumbnail loading code, this if would be skipped only when both bitmaps are `null`, since
        // onThumbnailLoaded won't be called twice with the same nonnull bitmap by Picasso's target.
        if (thumbnail != bitmap) {
            thumbnail = bitmap
            UIs.call { playerUi: PlayerUi -> playerUi.onThumbnailLoaded(bitmap) }
        }
    }

    /**
     * Sets the playback parameters of the player, and also saves them to shared preferences.
     * Speed and pitch are rounded up to 2 decimal places before being used or saved.
     *
     * @param speed       the playback speed, will be rounded to up to 2 decimal places
     * @param pitch       the playback pitch, will be rounded to up to 2 decimal places
     * @param skipSilence skip silence during playback
     */
    fun setPlaybackParameters(speed: Float, pitch: Float, skipSilence: Boolean) {
        val roundedSpeed = Math.round(speed * 100.0f) / 100.0f
        val roundedPitch = Math.round(pitch * 100.0f) / 100.0f

        PlayerHelper.savePlaybackParametersToPrefs(this, roundedSpeed, roundedPitch, skipSilence)
        exoPlayer!!.playbackParameters = PlaybackParameters(roundedSpeed, roundedPitch)
        exoPlayer!!.skipSilenceEnabled = skipSilence
    }

    private fun onUpdateProgress(currentProgress: Int, duration: Int, bufferPercent: Int) {
        if (isPrepared) {
            UIs.call { ui: PlayerUi -> ui.onUpdateProgress(currentProgress, duration, bufferPercent) }
            notifyProgressUpdateToListeners(currentProgress, duration, bufferPercent)
        }
    }

    fun startProgressLoop() {
        progressUpdateDisposable.set(getProgressUpdateDisposable())
    }

    private fun stopProgressLoop() {
        progressUpdateDisposable.set(null)
    }

    fun triggerProgressUpdate() {
        if (exoPlayerIsNull()) return
        onUpdateProgress(max(exoPlayer!!.currentPosition.toInt().toDouble(), 0.0).toInt(), exoPlayer!!.duration.toInt(), exoPlayer!!.bufferedPercentage)
    }

    private fun getProgressUpdateDisposable(): Disposable {
        return Observable.interval(PROGRESS_LOOP_INTERVAL_MILLIS.toLong(), TimeUnit.MILLISECONDS,
            AndroidSchedulers.mainThread())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ triggerProgressUpdate() }, { error: Throwable? -> Log.e(TAG, "Progress update failure: ", error) })
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Logd(TAG, "ExoPlayer - onPlayWhenReadyChanged() called with: playWhenReady = [$playWhenReady], reason = [$reason]")
        val playbackState = if (exoPlayerIsNull()) Player.STATE_IDLE else exoPlayer!!.playbackState
        updatePlaybackState(playWhenReady, playbackState)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Logd(TAG, "ExoPlayer - onPlaybackStateChanged() called with: playbackState = [$playbackState]")
        updatePlaybackState(playWhenReady, playbackState)
    }

    private fun updatePlaybackState(playWhenReady: Boolean, playbackState: Int) {
        Logd(TAG, "ExoPlayer - updatePlaybackState() called with: playWhenReady = [$playWhenReady], playbackState = [$playbackState]")
        if (currentState == STATE_PAUSED_SEEK) {
            Logd(TAG, "updatePlaybackState() is currently blocked")
            return
        }

        when (playbackState) {
            Player.STATE_IDLE -> isPrepared = false
            Player.STATE_BUFFERING -> if (isPrepared) changeState(STATE_BUFFERING)
            Player.STATE_READY -> {
                if (!isPrepared) {
                    isPrepared = true
                    onPrepared(playWhenReady)
                }
                changeState(if (playWhenReady) STATE_PLAYING else STATE_PAUSED)
            }
            Player.STATE_ENDED -> {
                changeState(STATE_COMPLETED)
                saveStreamProgressStateCompleted()
                isPrepared = false
            }
        }
    }

    // exoplayer listener
    override fun onIsLoadingChanged(isLoading: Boolean) {
        when {
            !isLoading && currentState == STATE_PAUSED && isProgressLoopRunning -> stopProgressLoop()
            isLoading && !isProgressLoopRunning -> startProgressLoop()
        }
    }

    // own playback listener
    override fun onPlaybackBlock() {
        if (exoPlayerIsNull()) return
        Logd(TAG, "Playback - onPlaybackBlock() called")
        currentItem = null
        currentMetadata = null
        exoPlayer!!.stop()
        isPrepared = false
        changeState(STATE_BLOCKED)
    }

    // own playback listener
    override fun onPlaybackUnblock(mediaSource: MediaSource) {
        Logd(TAG, "Playback - onPlaybackUnblock() called")
        if (exoPlayerIsNull()) return
        if (currentState == STATE_BLOCKED) changeState(STATE_BUFFERING)
        exoPlayer!!.setMediaSource(mediaSource, false)
        exoPlayer!!.prepare()
    }

    fun changeState(state: Int) {
        Logd(TAG, "changeState() called with: state = [$state]")
        currentState = state
        when (state) {
            STATE_BLOCKED -> onBlocked()
            STATE_PLAYING -> onPlaying()
            STATE_BUFFERING -> onBuffering()
            STATE_PAUSED -> onPaused()
            STATE_PAUSED_SEEK -> onPausedSeek()
            STATE_COMPLETED -> onCompleted()
        }
        notifyPlaybackUpdateToListeners()
    }

    private fun onPrepared(playWhenReady: Boolean) {
        Logd(TAG, "onPrepared() called with: playWhenReady = [$playWhenReady]")
        UIs.call { obj: PlayerUi -> obj.onPrepared() }
        if (playWhenReady && !isMuted) audioReactor!!.requestAudioFocus()
    }

    private fun onBlocked() {
        Logd(TAG, "onBlocked() called")
        if (!isProgressLoopRunning) startProgressLoop()
        UIs.call { obj: PlayerUi -> obj.onBlocked() }
    }

    private fun onPlaying() {
        Logd(TAG, "onPlaying() called")
        if (!isProgressLoopRunning) startProgressLoop()
        UIs.call { obj: PlayerUi -> obj.onPlaying() }
    }

    private fun onBuffering() {
        Logd(TAG, "onBuffering() called")
        UIs.call { obj: PlayerUi -> obj.onBuffering() }
    }

    private fun onPaused() {
        Logd(TAG, "onPaused() called")
        if (isProgressLoopRunning) stopProgressLoop()
        UIs.call { obj: PlayerUi -> obj.onPaused() }
    }

    private fun onPausedSeek() {
        Logd(TAG, "onPausedSeek() called")
        UIs.call { obj: PlayerUi -> obj.onPausedSeek() }
    }

    private fun onCompleted() {
        Logd(TAG, "onCompleted() called" + (if (playQueue == null) ". playQueue is null" else ""))
        if (playQueue == null) return
        UIs.call { obj: PlayerUi -> obj.onCompleted() }
        if (playQueue!!.index < playQueue!!.size() - 1) playQueue!!.offsetIndex(+1)
        if (isProgressLoopRunning) stopProgressLoop()
    }

    fun cycleNextRepeatMode() {
        repeatMode = PlayerHelper.nextRepeatMode(repeatMode)
    }

    override fun onRepeatModeChanged(repeatMode: @Player.RepeatMode Int) {
        Logd(TAG, "ExoPlayer - onRepeatModeChanged() called with: repeatMode = [$repeatMode]")
        UIs.call { playerUi: PlayerUi -> playerUi.onRepeatModeChanged(repeatMode) }
        notifyPlaybackUpdateToListeners()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        Logd(TAG, "ExoPlayer - onShuffleModeEnabledChanged() called with: mode = [$shuffleModeEnabled]")
        if (shuffleModeEnabled) playQueue?.shuffle()
        else playQueue?.unshuffle()
        UIs.call { playerUi: PlayerUi -> playerUi.onShuffleModeEnabledChanged(shuffleModeEnabled) }
        notifyPlaybackUpdateToListeners()
    }

    fun toggleShuffleModeEnabled() {
        if (!exoPlayerIsNull()) exoPlayer!!.shuffleModeEnabled = !exoPlayer!!.shuffleModeEnabled
    }

    fun toggleMute() {
        val wasMuted = isMuted
        exoPlayer!!.volume = (if (wasMuted) 1 else 0).toFloat()
        if (wasMuted) audioReactor!!.requestAudioFocus()
        else audioReactor!!.abandonAudioFocus()
        UIs.call { playerUi: PlayerUi -> playerUi.onMuteUnmuteChanged(!wasMuted) }
        notifyPlaybackUpdateToListeners()
    }

    /**
     * Listens for event or state changes on ExoPlayer. When any event happens, we check for
     * changes in the currently-playing metadata and update the encapsulating
     * [PlayerManager]. Downstream listeners are also informed.
     * When the renewed metadata contains any error, it is reported as a notification.
     * This is done because not all source resolution errors are [PlaybackException], which
     * are also captured by [ExoPlayer] and stops the playback.
     * @param player The [androidx.media3.common.Player] whose state changed.
     * @param events The [androidx.media3.common.Player.Events] that has triggered
     * the player state changes.
     */
    override fun onEvents(player: Player, events: Player.Events) {
        super.onEvents(player, events)
        MediaItemTag.from(player.currentMediaItem).ifPresent { tag: MediaItemTag ->
            if (tag === currentMetadata) return@ifPresent  // we still have the same metadata, no need to do anything

            val previousInfo = Optional.ofNullable(currentMetadata).flatMap { obj: MediaItemTag -> obj.maybeStreamInfo }.orElse(null)
            val previousAudioTrack = Optional.ofNullable(currentMetadata).flatMap { obj: MediaItemTag -> obj.maybeAudioTrack }.orElse(null)
            currentMetadata = tag

            if (!currentMetadata?.errors.isNullOrEmpty()) {
                // new errors might have been added even if previousInfo == tag.getMaybeStreamInfo()
                val errorInfo = ErrorInfo(currentMetadata!!.errors!!.filterNotNull(), UserAction.PLAY_STREAM,
                    "Loading failed for [" + currentMetadata!!.title + "]: " + currentMetadata!!.streamUrl!!, currentMetadata!!.serviceId)
                createNotification(context, errorInfo)
            }
            currentMetadata!!.maybeStreamInfo.ifPresent { info: StreamInfo ->
                Logd(TAG, "ExoPlayer - onEvents() update stream info: " + info.name)
                when {
                    // only update with the new stream info if it has actually changed
                    previousInfo == null || previousInfo.url != info.url -> updateMetadataWith(info)
                    previousAudioTrack == null || tag.maybeAudioTrack
                        .map { t: MediaItemTag.AudioTrack -> t.selectedAudioStreamIndex != previousAudioTrack.selectedAudioStreamIndex }
                        .orElse(false) -> {
                        notifyAudioTrackUpdateToListeners()
                    }
                }
            }
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        Logd(TAG, "ExoPlayer - onTracksChanged(), track group size = ${tracks.groups.size}")
        UIs.call { playerUi: PlayerUi -> playerUi.onTextTracksChanged(tracks) }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        Logd(TAG, "ExoPlayer - playbackParameters(), speed = [${playbackParameters.speed}], pitch = [${playbackParameters.pitch}]")
        UIs.call { playerUi: PlayerUi -> playerUi.onPlaybackParametersChanged(playbackParameters) }
    }

    override fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, discontinuityReason: @DiscontinuityReason Int) {
        Logd(TAG, "ExoPlayer - onPositionDiscontinuity() called with oldPositionIndex = [${oldPosition.mediaItemIndex}], oldPositionMs = [${oldPosition.positionMs}], newPositionIndex = [${newPosition.mediaItemIndex}], newPositionMs = [${newPosition.positionMs}], discontinuityReason = [$discontinuityReason]")
        if (playQueue == null) return

        // Refresh the playback if there is a transition to the next video
        val newIndex = newPosition.mediaItemIndex
        when (discontinuityReason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION, Player.DISCONTINUITY_REASON_REMOVE -> {
                // When player is in single repeat mode and a period transition occurs,
                // we need to register a view count here since no metadata has changed
                if (repeatMode == Player.REPEAT_MODE_ONE && newIndex == playQueue!!.index) registerStreamViewed()
                else {
                    Logd(TAG, "ExoPlayer - onSeekProcessed() called")
                    if (isPrepared) saveStreamProgressState()
                    // Player index may be invalid when playback is blocked
                    if (currentState != STATE_BLOCKED && newIndex != playQueue!!.index) {
                        saveStreamProgressStateCompleted() // current stream has ended
                        playQueue!!.index = newIndex
                    }
                }
            }
            Player.DISCONTINUITY_REASON_SEEK -> {
                Logd(TAG, "ExoPlayer - onSeekProcessed() called")
                if (isPrepared) saveStreamProgressState()
                if (currentState != STATE_BLOCKED && newIndex != playQueue!!.index) {
                    saveStreamProgressStateCompleted()
                    playQueue!!.index = newIndex
                }
            }
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT, Player.DISCONTINUITY_REASON_INTERNAL ->
                if (currentState != STATE_BLOCKED && newIndex != playQueue!!.index) {
                    saveStreamProgressStateCompleted()
                    playQueue!!.index = newIndex
                }
            Player.DISCONTINUITY_REASON_SKIP -> {}
            Player.DISCONTINUITY_REASON_SILENCE_SKIP -> {}
        }
    }

    override fun onRenderedFirstFrame() {
        UIs.call { obj: PlayerUi -> obj.onRenderedFirstFrame() }
    }

    override fun onCues(cueGroup: CueGroup) {
        UIs.call { playerUi: PlayerUi -> playerUi.onCues(cueGroup.cues) }
    }

    /**
     * There are multiple types of errors:
     *
     *  * [BEHIND_LIVE_WINDOW][PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW]:
     * If the playback on livestreams are lagged too far behind the current playable
     * window. Then we seek to the latest timestamp and restart the playback.
     * This error is **catchable**.
     *
     *  * From [BAD_IO][PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE] to
     * [UNSUPPORTED_FORMATS][PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED]:
     * If the stream source is validated by the extractor but not recognized by the player,
     * then we can try to recover playback by signalling an error on the [PlayQueue].
     *  * For [PLAYER_TIMEOUT][PlaybackException.ERROR_CODE_TIMEOUT],
     * [MEDIA_SOURCE_RESOLVER_TIMEOUT][PlaybackException.ERROR_CODE_IO_UNSPECIFIED] and
     * [NO_NETWORK][PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED]:
     * We can keep set the recovery record and keep to player at the current state until
     * it is ready to play by restarting the [MediaSourceManager].
     *  * On any ExoPlayer specific issue internal to its device interaction, such as
     * [DECODER_ERROR][PlaybackException.ERROR_CODE_DECODER_INIT_FAILED]:
     * We terminate the playback.
     *  * For any other unspecified issue internal: We set a recovery and try to restart
     * the playback.
     * For any error above that is **not** explicitly **catchable**, the player will
     * create a notification so users are aware.
     *
     *
     * @see androidx.media3.common.Player.Listener.onPlayerError
     */
    // Any error code not explicitly covered here are either unrelated to Vista use case
    // (e.g. DRM) or not recoverable (e.g. Decoder error). In both cases, the player should
    // shutdown.
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "ExoPlayer - onPlayerError() called with:", error)
        saveStreamProgressState()
        var isCatchableException = false
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                isCatchableException = true
                exoPlayer!!.seekToDefaultPosition()
                exoPlayer!!.prepare()
                // Inform the user that we are reloading the stream by
                // switching to the buffering state
                onBuffering()
            }
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->                 // Source errors, signal on playQueue and move on:
                if (!exoPlayerIsNull()) playQueue?.error()
            PlaybackException.ERROR_CODE_TIMEOUT, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_UNSPECIFIED -> {
                // Reload playback on unexpected errors:
                setRecovery()
                reloadPlayQueueManager()
            }
            // API, remote and renderer errors belong here:
            else -> onPlaybackShutdown()
        }
        if (!isCatchableException) createErrorNotification(error)
        fragmentListener?.onPlayerError(error, isCatchableException)
    }

    private fun createErrorNotification(error: PlaybackException) {
        val errorInfo = if (currentMetadata == null)
            ErrorInfo(error, UserAction.PLAY_STREAM, "Player error[type=" + error.errorCodeName + "] occurred, currentMetadata is null")
        else
            ErrorInfo(error, UserAction.PLAY_STREAM, "Player error[type=${error.errorCodeName}] occurred while playing ${currentMetadata!!.streamUrl}", currentMetadata!!.serviceId)
        createNotification(context, errorInfo)
    }

    override fun isApproachingPlaybackEdge(timeToEndMillis: Long): Boolean {
        // If live, then not near playback edge
        // If not playing, then not approaching playback edge
        if (exoPlayerIsNull() || isLive || !isPlaying) return false

        val currentPositionMillis = exoPlayer!!.currentPosition
        val currentDurationMillis = exoPlayer!!.duration
        return currentDurationMillis - currentPositionMillis < timeToEndMillis
    }

    // own playback listener
    override fun onPlaybackSynchronize(item: PlayQueueItem, wasBlocked: Boolean) {
        Logd(TAG, "Playback - onPlaybackSynchronize(was blocked: $wasBlocked) called with item=[${item.title}], url=[${item.url}]")

        if (exoPlayerIsNull() || playQueue == null || currentItem === item) return  // nothing to synchronize

        val playQueueIndex = playQueue!!.indexOf(item)
        val playlistIndex = exoPlayer!!.currentMediaItemIndex
        val playlistSize = exoPlayer!!.currentTimeline.windowCount
        val removeThumbnailBeforeSync = currentItem == null || currentItem!!.serviceId != item.serviceId || currentItem!!.url != item.url

        currentItem = item
        when {
            // wrong window (this should be impossible, as this method is called with
            // `item=playQueue.getItem()`, so the index of that item must be equal to `getIndex()`)
            playQueueIndex != playQueue!!.index -> Log.e(TAG, "Playback - Play Queue may be not in sync: item index=[$playQueueIndex], queue index=[${playQueue!!.index}]")
            // the queue and the player's timeline are not in sync, since the play queue index
            // points outside of the timeline
            playlistSize in 1..playQueueIndex || playQueueIndex < 0 -> Log.e(TAG, "Playback - Trying to seek to invalid index=[$playQueueIndex] with playlist length=[$playlistSize]")
            // either the player needs to be unblocked, or the play queue index has just been
            // changed and needs to be synchronized, or the player is not playing
            wasBlocked || playlistIndex != playQueueIndex || !isPlaying -> {
                Logd(TAG, "Playback - Rewinding to correct index=[$playQueueIndex], from=[$playlistIndex], size=[$playlistSize].")
                // unset the current (now outdated) thumbnail to ensure it is not used during sync
                if (removeThumbnailBeforeSync) onThumbnailLoaded(null)

                // sync the player index with the queue index, and seek to the correct position
                if (item.recoveryPosition != PlayQueueItem.RECOVERY_UNSET) {
                    exoPlayer!!.seekTo(playQueueIndex, item.recoveryPosition)
                    playQueue!!.unsetRecovery(playQueueIndex)
                } else exoPlayer!!.seekToDefaultPosition(playQueueIndex)
            }
        }
    }

    fun seekTo(positionMillis: Long) {
        Logd(TAG, "seekBy() called with: position = [$positionMillis]")
        // prevent invalid positions when fast-forwarding/-rewinding
        if (!exoPlayerIsNull()) exoPlayer!!.seekTo(MathUtils.clamp(positionMillis, 0, exoPlayer!!.duration))
    }

    private fun seekBy(offsetMillis: Long) {
        Logd(TAG, "seekBy() called with: offsetMillis = [$offsetMillis]")
        seekTo(exoPlayer!!.currentPosition + offsetMillis)
    }

    fun seekToDefault() {
        if (!exoPlayerIsNull()) exoPlayer!!.seekToDefaultPosition()
    }

    fun play() {
        Logd(TAG, "play() called")
        if (audioReactor == null || playQueue == null || exoPlayerIsNull()) return

        if (!isMuted) audioReactor!!.requestAudioFocus()

        if (currentState == STATE_COMPLETED) {
            if (playQueue!!.index == 0) seekToDefault()
            else playQueue!!.index = 0
        }

        exoPlayer!!.play()
        saveStreamProgressState()
    }

    fun pause() {
        Logd(TAG, "pause() called")
        if (audioReactor == null || exoPlayerIsNull()) return

        audioReactor!!.abandonAudioFocus()
        exoPlayer!!.pause()
        saveStreamProgressState()
    }

    fun playPause() {
        Logd(TAG, "onPlayPause() called")
        // When state is completed (replay button is shown) then (re)play and do not pause
        if (playWhenReady && currentState != STATE_COMPLETED) pause()
        else play()
    }

    fun playPrevious() {
        Logd(TAG, "onPlayPrevious() called")
        if (exoPlayerIsNull() || playQueue == null) return

        /* If current playback has run for PLAY_PREV_ACTIVATION_LIMIT_MILLIS milliseconds,
         * restart current track. Also restart the track if the current track
         * is the first in a queue.*/
        if (exoPlayer!!.currentPosition > PLAY_PREV_ACTIVATION_LIMIT_MILLIS || playQueue!!.index == 0) {
            seekToDefault()
            playQueue!!.offsetIndex(0)
        } else {
            saveStreamProgressState()
            playQueue!!.offsetIndex(-1)
        }
        triggerProgressUpdate()
    }

    fun playNext() {
        Logd(TAG, "onPlayNext() called")
        if (playQueue == null) return

        saveStreamProgressState()
        playQueue!!.offsetIndex(+1)
        triggerProgressUpdate()
    }

    fun fastForward() {
        Logd(TAG, "fastRewind() called")
        seekBy(PlayerHelper.retrieveSeekDurationFromPreferences(this).toLong())
        triggerProgressUpdate()
    }

    fun fastRewind() {
        Logd(TAG, "fastRewind() called")
        seekBy(-PlayerHelper.retrieveSeekDurationFromPreferences(this).toLong())
        triggerProgressUpdate()
    }

    private fun registerStreamViewed() {
        currentStreamInfo.ifPresent { info: StreamInfo? ->
            databaseUpdateDisposable.add(recordManager.onViewed(info).onErrorComplete().subscribe())
        }
    }

    private fun saveStreamProgressState(progressMillis: Long) {
        currentStreamInfo.ifPresent { info: StreamInfo ->
            if (!prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)) return@ifPresent
            Logd(TAG, "saveStreamProgressState() called with: progressMillis=$progressMillis, currentMetadata=[${info.name}]")

            databaseUpdateDisposable.add(recordManager.saveStreamState(info, progressMillis)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError { e: Throwable -> if (DEBUG) e.printStackTrace() }
                .onErrorComplete()
                .subscribe())
        }
    }

    fun saveStreamProgressState() {
        // Make sure play queue and current window index are equal, to prevent saving state for
        // the wrong stream on discontinuity (e.g. when the stream just changed but the
        // playQueue index and currentMetadata still haven't updated)
        if (exoPlayerIsNull() || currentMetadata == null || playQueue == null || playQueue!!.index != exoPlayer!!.currentMediaItemIndex) return

        // Save current position. It will help to restore this position once a user
        // wants to play prev or next stream from the queue
        playQueue!!.setRecovery(playQueue!!.index, exoPlayer!!.contentPosition)
        saveStreamProgressState(exoPlayer!!.currentPosition)
    }

    private fun saveStreamProgressStateCompleted() {
        // current stream has ended, so the progress is its duration (+1 to overcome rounding)
        currentStreamInfo.ifPresent { info: StreamInfo -> saveStreamProgressState((info.duration + 1) * 1000) }
    }

    private fun updateMetadataWith(info: StreamInfo) {
        Logd(TAG, "Playback - onMetadataChanged() called, playing: " + info.name)
        if (exoPlayerIsNull()) return

        maybeAutoQueueNextStream(info)
        loadCurrentThumbnail(info.thumbnails)
        registerStreamViewed()

        notifyMetadataUpdateToListeners()
        notifyAudioTrackUpdateToListeners()
        UIs.call { playerUi: PlayerUi -> playerUi.onMetadataChanged(info) }
    }

    private fun maybeAutoQueueNextStream(info: StreamInfo) {
        if (playQueue == null || playQueue!!.index != playQueue!!.size() - 1 || repeatMode != Player.REPEAT_MODE_OFF
                || !PlayerHelper.isAutoQueueEnabled(context)) return

        // auto queue when starting playback on the last item when not repeating
        val autoQueue = PlayerHelper.autoQueueOf(info, playQueue!!.streams)
        if (autoQueue != null) playQueue!!.append(autoQueue.streams)
    }

    fun selectQueueItem(item: PlayQueueItem?) {
        if (playQueue == null || exoPlayerIsNull()) return

        val index = playQueue!!.indexOf(item!!)
        if (index == -1) return

        if (playQueue!!.index == index && exoPlayer!!.currentMediaItemIndex == index) seekToDefault()
        else saveStreamProgressState()

        playQueue!!.index = index
    }

    override fun onPlayQueueEdited() {
        notifyPlaybackUpdateToListeners()
        UIs.call { obj: PlayerUi -> obj.onPlayQueueEdited() }
    }

    override fun sourceOf(item: PlayQueueItem, info: StreamInfo): MediaSource? {
        if (audioPlayerSelected()) return audioResolver.resolve(info)

        // If the current info has only video streams with audio and if the stream is played as
        // audio, we need to use the audio resolver, otherwise the video stream will be played
        // in background.
        if (isAudioOnly && videoResolver.getStreamSourceType().orElse(VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)
                == VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)
            return audioResolver.resolve(info)

        // Even if the stream is played in background, we need to use the video resolver if the
        // info played is separated video-only and audio-only streams; otherwise, if the audio
        // resolver was called when the app was in background, the app will only stream audio when
        // the user come back to the app and will never fetch the video stream.
        // Note that the video is not fetched when the app is in background because the video
        // renderer is fully disabled (see useVideoSource method), except for HLS streams
        // (see https://github.com/google/ExoPlayer/issues/9282).
        return videoResolver.resolve(info)
    }

    fun disablePreloadingOfCurrentTrack() {
        loadController.disablePreloadingOfCurrentTrack()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        Logd(TAG, "onVideoSizeChanged() called with: width / height = [${videoSize.width} / ${videoSize.height} = ${(videoSize.width.toFloat()) / videoSize.height}], unappliedRotationDegrees = [${videoSize.unappliedRotationDegrees}], pixelWidthHeightRatio = [${videoSize.pixelWidthHeightRatio}]")
        if (videoSize.width > 0 && videoSize.height > 0) UIs.call { playerUi: PlayerUi -> playerUi.onVideoSizeChanged(videoSize) }
    }

    fun setFragmentListener(listener: PlayerServiceEventListener?) {
        fragmentListener = listener
        UIs.call { obj: PlayerUi -> obj.onFragmentListenerSet() }
        notifyQueueUpdateToListeners()
        notifyMetadataUpdateToListeners()
        notifyPlaybackUpdateToListeners()
        triggerProgressUpdate()
    }

    fun removeFragmentListener(listener: PlayerServiceEventListener) {
        if (fragmentListener === listener) fragmentListener = null
    }

    fun setActivityListener(listener: PlayerEventListener?) {
        activityListener = listener
        // TODO: why not queue update?
        notifyMetadataUpdateToListeners()
        notifyPlaybackUpdateToListeners()
        triggerProgressUpdate()
    }

    fun removeActivityListener(listener: PlayerEventListener) {
        if (activityListener === listener) activityListener = null
    }

    private fun stopActivityBinding() {
        fragmentListener?.onServiceStopped()
        fragmentListener = null
        activityListener?.onServiceStopped()
        activityListener = null
    }

    private fun notifyQueueUpdateToListeners() {
        if (playQueue != null) {
            fragmentListener?.onQueueUpdate(playQueue)
            activityListener?.onQueueUpdate(playQueue)
        }
    }

    private fun notifyMetadataUpdateToListeners() {
        currentStreamInfo.ifPresent { info: StreamInfo? ->
            fragmentListener?.onMetadataUpdate(info, playQueue)
            activityListener?.onMetadataUpdate(info, playQueue)
        }
    }

    private fun notifyPlaybackUpdateToListeners() {
        if (!exoPlayerIsNull() && playQueue != null)
            fragmentListener?.onPlaybackUpdate(currentState, repeatMode, playQueue!!.isShuffled, exoPlayer!!.playbackParameters)

        if (!exoPlayerIsNull() && playQueue != null)
            activityListener?.onPlaybackUpdate(currentState, repeatMode, playQueue!!.isShuffled, playbackParameters)
    }

    private fun notifyProgressUpdateToListeners(currentProgress: Int, duration: Int, bufferPercent: Int) {
        fragmentListener?.onProgressUpdate(currentProgress, duration, bufferPercent)
        activityListener?.onProgressUpdate(currentProgress, duration, bufferPercent)
    }

    private fun notifyAudioTrackUpdateToListeners() {
        fragmentListener?.onAudioTrackUpdate()
        activityListener?.onAudioTrackUpdate()
    }

    fun useVideoSource(videoEnabled: Boolean) {
        if (playQueue == null || audioPlayerSelected()) return

        isAudioOnly = !videoEnabled
        currentStreamInfo.ifPresentOrElse({ info: StreamInfo ->
            // In case we don't know the source type, fall back to either video-with-audio, or
            // audio-only source type
            val sourceType = videoResolver.getStreamSourceType().orElse(VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY)
            if (playQueueManagerReloadingNeeded(sourceType, info, videoRendererIndex)) reloadPlayQueueManager()
            setRecovery()
            // Disable or enable video and subtitles renderers depending of the videoEnabled value
            trackSelector.setParameters(trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !videoEnabled)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoEnabled))
        }, {
            /*
            The current metadata may be null sometimes (for e.g. when using an unstable connection
            in livestreams) so we will be not able to execute the block below

            Reload the play queue manager in this case, which is the behavior when we don't know the
            index of the video renderer or playQueueManagerReloadingNeeded returns true
            */
            reloadPlayQueueManager()
            setRecovery()
        })
    }

    /**
     * Return whether the play queue manager needs to be reloaded when switching player type.
     *
     * The play queue manager needs to be reloaded if the video renderer index is not known and if
     * the content is not an audio content, but also if none of the following cases is met:
     *
     *  * the content is an [audio stream][StreamType.AUDIO_STREAM], an
     * [audio live stream][StreamType.AUDIO_LIVE_STREAM], or a
     * [ended audio live stream][StreamType.POST_LIVE_AUDIO_STREAM];
     *  * the content is a [live stream][StreamType.LIVE_STREAM] and the source type is a
     * [live source][SourceType.LIVE_STREAM];
     *  * the content's source is [a video stream][SourceType.VIDEO_WITH_SEPARATED_AUDIO] or has no audio-only streams available **and** is a
     * [video stream][StreamType.VIDEO_STREAM], an
     * [ended live stream][StreamType.POST_LIVE_STREAM], or a
     * [live stream][StreamType.LIVE_STREAM].
     *
     * @param sourceType         the [SourceType] of the stream
     * @param streamInfo         the [StreamInfo] of the stream
     * @param videoRendererIndex the video renderer index of the video source, if that's a video
     * source (or [.RENDERER_UNAVAILABLE])
     * @return whether the play queue manager needs to be reloaded
     */
    private fun playQueueManagerReloadingNeeded(sourceType: VideoPlaybackResolver.SourceType, streamInfo: StreamInfo, videoRendererIndex: Int): Boolean {
        val streamType = streamInfo.streamType
        val isStreamTypeAudio = isAudio(streamType)

        if (videoRendererIndex == RENDERER_UNAVAILABLE && !isStreamTypeAudio) return true

        // The content is an audio stream, an audio live stream, or a live stream with a live
        // source: it's not needed to reload the play queue manager because the stream source will
        // be the same
        if (isStreamTypeAudio || (streamType == StreamType.LIVE_STREAM && sourceType == VideoPlaybackResolver.SourceType.LIVE_STREAM)) return false

        // The content's source is a video with separated audio or a video with audio -> the video
        // and its fetch may be disabled
        // The content's source is a video with embedded audio and the content has no separated
        // audio stream available: it's probably not needed to reload the play queue manager
        // because the stream source will be probably the same as the current played
        // It's not needed to reload the play queue manager only if the content's stream type
        // is a video stream, a live stream or an ended live stream
        if (sourceType == VideoPlaybackResolver.SourceType.VIDEO_WITH_SEPARATED_AUDIO
                || (sourceType == VideoPlaybackResolver.SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY && streamInfo.audioStreams.isEmpty())) return !isVideo(streamType)

        // Other cases: the play queue manager reload is needed
        return true
    }

    fun exoPlayerIsNull(): Boolean {
        return exoPlayer == null
    }

    fun setPlaybackQuality(quality: String?) {
        saveStreamProgressState()
        setRecovery()
        videoResolver.playbackQuality = quality
        reloadPlayQueueManager()
    }

    fun setAudioTrack(audioTrackId: String?) {
        saveStreamProgressState()
        setRecovery()
        videoResolver.audioTrack = audioTrackId
        audioResolver.audioTrack = audioTrackId
        reloadPlayQueueManager()
    }

    fun audioPlayerSelected(): Boolean {
        return playerType == PlayerType.AUDIO
    }

    fun videoPlayerSelected(): Boolean {
        return playerType == PlayerType.MAIN
    }

    fun popupPlayerSelected(): Boolean {
        return playerType == PlayerType.POPUP
    }

    fun getFragmentListener(): Optional<PlayerServiceEventListener> {
        return Optional.ofNullable(fragmentListener)
    }

    @OptIn(UnstableApi::class) class LoadController : DefaultLoadControl() {
        private var preloadingEnabled = true

        @Deprecated("Deprecated in Java")
        override fun onPrepared() {
            preloadingEnabled = true
            super.onPrepared()
        }
        @Deprecated("Deprecated in Java")
        override fun onStopped() {
            preloadingEnabled = true
            super.onStopped()
        }
        @Deprecated("Deprecated in Java")
        override fun onReleased() {
            preloadingEnabled = true
            super.onReleased()
        }
        @Deprecated("Deprecated in Java")
        override fun shouldContinueLoading(playbackPositionUs: Long, bufferedDurationUs: Long, playbackSpeed: Float): Boolean {
            if (!preloadingEnabled) return false
            return super.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)
        }
        fun disablePreloadingOfCurrentTrack() {
            preloadingEnabled = false
        }
    }

    @UnstableApi
    class CustomRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(context: Context, extensionRendererMode: @ExtensionRendererMode Int, mediaCodecSelector: MediaCodecSelector,
                                         enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
                                         allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
            out.add(CustomMediaCodecVideoRenderer(context, codecAdapterFactory,
                mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler,
                eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY))
        }

        @UnstableApi
        class CustomMediaCodecVideoRenderer(
                context: Context?,
                codecAdapterFactory: MediaCodecAdapter.Factory?,
                mediaCodecSelector: MediaCodecSelector?,
                allowedJoiningTimeMs: Long,
                enableDecoderFallback: Boolean,
                eventHandler: Handler?,
                eventListener: VideoRendererEventListener?,
                maxDroppedFramesToNotify: Int)
            : MediaCodecVideoRenderer(context!!, codecAdapterFactory!!, mediaCodecSelector!!, allowedJoiningTimeMs,
            enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify) {

            override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean {
                return true
            }
        }
    }

    @UnstableApi class AudioPlaybackResolver(
            private val context: Context,
            private val dataSource: PlayerDataSource)
        : PlaybackResolver {

        var audioTrack: String? = null

        /**
         * Get a media source providing audio. If a service has no separate [AudioStream]s we
         * use a video stream as audio source to support audio background playback.
         *
         * @param source of the stream
         * @return the audio source to use or null if none could be found
         */
        override fun resolve(source: StreamInfo): MediaSource? {
            val liveSource = maybeBuildLiveMediaSource(dataSource, source)
            if (liveSource != null) return liveSource

            val audioStreams: List<AudioStream> = getFilteredAudioStreams(context, source.audioStreams).filterNotNull()
            val stream: Stream?
            val tag: MediaItemTag

            if (audioStreams.isNotEmpty()) {
                val audioIndex = getAudioFormatIndex(context, audioStreams, audioTrack)
                stream = getStreamForIndex(audioIndex, audioStreams)
                tag = StreamInfoTag.of(source, audioStreams, audioIndex)
            } else {
                val videoStreams: List<VideoStream> = getPlayableStreams(source.videoStreams, source.serviceId)
                if (videoStreams.isNotEmpty()) {
                    val index = getDefaultResolutionIndex(context, videoStreams)
                    stream = getStreamForIndex(index, videoStreams)
                    tag = StreamInfoTag.of(source)
                } else return null
            }

            try {
                return buildMediaSource(dataSource, stream!!, source, cacheKeyOf(source, stream), tag)
            } catch (e: PlaybackResolver.ResolverException) {
                Log.e(TAG, "Unable to create audio source", e)
                return null
            }
        }

        private fun getStreamForIndex(index: Int, streams: List<Stream?>): Stream? {
            if (index >= 0 && index < streams.size) return streams[index]
            return null
        }
    }

    @UnstableApi class VideoPlaybackResolver(
            private val context: Context,
            private val dataSource: PlayerDataSource,
            private val qualityResolver: QualityResolver) : PlaybackResolver {

        private var streamSourceType: SourceType? = null

        var playbackQuality: String? = null
        var audioTrack: String? = null

        enum class SourceType {
            LIVE_STREAM,
            VIDEO_WITH_SEPARATED_AUDIO,
            VIDEO_WITH_AUDIO_OR_AUDIO_ONLY
        }

        override fun resolve(source: StreamInfo): MediaSource? {
            val liveSource = maybeBuildLiveMediaSource(dataSource, source)
            if (liveSource != null) {
                streamSourceType = SourceType.LIVE_STREAM
                return liveSource
            }

            val mediaSources: MutableList<MediaSource> = ArrayList()

            // Create video stream source
            val videoStreamsList = getSortedStreamVideosList(context, getPlayableStreams(source.videoStreams, source.serviceId),
                getPlayableStreams(source.videoOnlyStreams, source.serviceId), ascendingOrder = false, preferVideoOnlyStreams = true)
            val audioStreamsList: List<AudioStream> = getFilteredAudioStreams(context, source.audioStreams).filterNotNull()
            val videoIndex = when {
                videoStreamsList.isEmpty() -> -1
                playbackQuality == null -> qualityResolver.getDefaultResolutionIndex(videoStreamsList)
                else -> qualityResolver.getOverrideResolutionIndex(videoStreamsList, playbackQuality)
            }

            val audioIndex = getAudioFormatIndex(context, audioStreamsList, audioTrack)
            val tag: MediaItemTag = StreamInfoTag.of(source, videoStreamsList, videoIndex, audioStreamsList, audioIndex)
            val video = tag.maybeQuality.map<VideoStream?> { it.selectedVideoStream }.orElse(null)
            val audio = tag.maybeAudioTrack.map<AudioStream?> { it.selectedAudioStream }.orElse(null)

            if (video != null) {
                try {
                    val streamSource = buildMediaSource(dataSource, video, source, cacheKeyOf(source, video), tag)
                    mediaSources.add(streamSource)
                } catch (e: PlaybackResolver.ResolverException) {
                    Log.e(TAG, "Unable to create video source", e)
                    return null
                }
            }

            // Use the audio stream if there is no video stream, or
            // merge with audio stream in case if video does not contain audio
            if (audio != null && (video == null || video.isVideoOnly || audioTrack != null)) {
                try {
                    val audioSource = buildMediaSource(dataSource, audio, source, cacheKeyOf(source, audio), tag)
                    mediaSources.add(audioSource)
                    streamSourceType = SourceType.VIDEO_WITH_SEPARATED_AUDIO
                } catch (e: PlaybackResolver.ResolverException) {
                    Log.e(TAG, "Unable to create audio source", e)
                    return null
                }
            } else streamSourceType = SourceType.VIDEO_WITH_AUDIO_OR_AUDIO_ONLY

            // If there is no audio or video sources, then this media source cannot be played back
            if (mediaSources.isEmpty()) return null

            // Below are auxiliary media sources
//            TODO: test
            // Create subtitle sources
//            val subtitlesStreams = source.subtitles
//            // Torrent and non URL subtitles are not supported by ExoPlayer
//            val nonTorrentAndUrlStreams: List<SubtitlesStream> = getUrlAndNonTorrentStreams(subtitlesStreams)
//            for (subtitle in nonTorrentAndUrlStreams) {
//                val mediaFormat = subtitle.format
//                if (mediaFormat != null) {
//                    val textRoleFlag: @RoleFlags Int = if (subtitle.isAutoGenerated) C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else C.ROLE_FLAG_CAPTION
//                    val textMediaItem = SubtitleConfiguration.Builder(
//                        Uri.parse(subtitle.content))
//                        .setMimeType(mediaFormat.mimeType)
//                        .setRoleFlags(textRoleFlag)
//                        .setLanguage(PlayerHelper.captionLanguageOf(context, subtitle))
//                        .build()
//                    val textSource: MediaSource = dataSource.singleSampleMediaSourceFactory.createMediaSource(textMediaItem, C.TIME_UNSET)
//                    mediaSources.add(textSource)
//                }
//            }

            return if (mediaSources.size == 1) mediaSources[0]
            else MergingMediaSource(true, *mediaSources.toTypedArray<MediaSource>())
        }

        /**
         * Returns the last resolved [StreamInfo]'s [source type][SourceType].
         *
         * @return [Optional.empty] if nothing was resolved, otherwise the [SourceType]
         * of the last resolved [StreamInfo] inside an [Optional]
         */
        fun getStreamSourceType(): Optional<SourceType> {
            return Optional.ofNullable(streamSourceType)
        }

        interface QualityResolver {
            fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>?): Int
            fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>?, playbackQuality: String?): Int
        }
    }

    /**
     * Creates a [PlayerUiList] starting with the provided player uis. The provided player uis
     * will not be prepared like those passed to [.addAndPrepare], because when
     * the [PlayerUiList] constructor is called, the player is still not running and it
     * wouldn't make sense to initialize uis then. Instead the player will initialize them by doing
     * proper calls to [.call].
     *
     * @param initialPlayerUis the player uis this list should start with; the order will be kept
     */
    @OptIn(UnstableApi::class)
    class PlayerUiList(vararg initialPlayerUis: PlayerUi) {
        private val playerUis: MutableList<PlayerUi> = ArrayList()

        init {
            playerUis.addAll(listOf(*initialPlayerUis))
        }

        /**
         * Adds the provided player ui to the list and calls on it the initialization functions that
         * apply based on the current player state. The preparation step needs to be done since when UIs
         * are removed and re-added, the player will not call e.g. initPlayer again since the exoplayer
         * is already initialized, but we need to notify the newly built UI that the player is ready
         * nonetheless.
         * @param playerUi the player ui to prepare and add to the list; its [                 ]
         */
        fun addAndPrepare(playerUi: PlayerUi) {
            // make sure UIs know whether a service is connected or not
            if (playerUi.playerManager.getFragmentListener().isPresent) playerUi.onFragmentListenerSet()
            if (!playerUi.playerManager.exoPlayerIsNull()) {
                playerUi.initPlayer()
                if (playerUi.playerManager.playQueue != null) playerUi.initPlayback()
            }
            playerUis.add(playerUi)
        }

        /**
         * Destroys all matching player UIs and removes them from the list.
         * @param playerUiType the class of the player UI to destroy; the [                     ]
         * [Class.isInstance] method will be used, so even subclasses will be
         * destroyed and removed
         * @param <T>          the class type parameter
        </T> */
        fun <T> destroyAll(playerUiType: Class<T>) {
            playerUis.stream()
                .filter { obj: PlayerUi? -> playerUiType.isInstance(obj) }
                .forEach { playerUi: PlayerUi ->
                    playerUi.destroyPlayer()
                    playerUi.destroy()
                }
            playerUis.removeIf { obj: PlayerUi? -> playerUiType.isInstance(obj) }
        }

        /**
         * @param playerUiType the class of the player UI to return; the [                     ]
         * [Class.isInstance] method will be used, so even subclasses could
         * be returned
         * @param <T>          the class type parameter
         * @return the first player UI of the required type found in the list, or an empty [         ] otherwise
        </T> */
        fun <T> get(playerUiType: Class<T>): Optional<T> {
            return playerUis.stream()
                .filter { obj: PlayerUi? -> playerUiType.isInstance(obj) }
                .map { obj: PlayerUi? -> playerUiType.cast(obj) }
                .findFirst()
        }

        /**
         * Calls the provided consumer on all player UIs in the list, in order of addition.
         * @param consumer the consumer to call with player UIs
         */
        fun call(consumer: Consumer<PlayerUi>?) {
            playerUis.stream().forEachOrdered(consumer)
        }
    }

    interface Resolver<Source, Product> {
        fun resolve(source: Source): Product?
    }

    /**
     * This interface is just a shorthand for [Resolver] with [StreamInfo] as source and
     * [MediaSource] as product. It contains many static methods that can be used by classes
     * implementing this interface, and nothing else.
     */
    @UnstableApi interface PlaybackResolver : Resolver<StreamInfo, MediaSource> {

        class ResolverException : Exception {
            constructor(message: String?) : super(message)
            constructor(message: String?, cause: Throwable?) : super(message, cause)
        }

        @UnstableApi companion object {
            //region Cache key generation
            private fun commonCacheKeyOf(info: StreamInfo, stream: Stream, resolutionOrBitrateUnknown: Boolean): StringBuilder {
                // stream info service id
                val cacheKey = StringBuilder(info.serviceId)

                // stream info id
                cacheKey.append(" ")
                cacheKey.append(info.id)

                // stream id (even if unknown)
                cacheKey.append(" ")
                cacheKey.append(stream.id)

                // mediaFormat (if not null)
                val mediaFormat = stream.format
                if (mediaFormat != null) {
                    cacheKey.append(" ")
                    cacheKey.append(mediaFormat.getName())
                }

                // content (only if other information is missing)
                // If the media format and the resolution/bitrate are both missing, then we don't have
                // enough information to distinguish this stream from other streams.
                // So, only in that case, we use the content (i.e. url or manifest) to differentiate
                // between streams.
                // Note that if the content were used even when other information is present, then two
                // streams with the same stats but with different contents (e.g. because the url was
                // refreshed) will be considered different (i.e. with a different cacheKey), making the
                // cache useless.
                if (resolutionOrBitrateUnknown && mediaFormat == null) {
                    cacheKey.append(" ")
                    cacheKey.append(Objects.hash(stream.content, stream.manifestUrl))
                }

                return cacheKey
            }

            /**
             * Builds the cache key of a [video stream][VideoStream].
             *
             * A cache key is unique to the features of the provided video stream, and when possible
             * independent of *transient* parameters (such as the URL of the stream).
             * This ensures that there are no conflicts, but also that the cache is used as much as
             * possible: the same cache should be used for two streams which have the same features but
             * e.g. a different URL, since the URL might have been reloaded in the meantime, but the stream
             * actually referenced by the URL is still the same.
             *
             *
             * @param info        the [stream info][StreamInfo], to distinguish between streams with
             * the same features but coming from different stream infos
             * @param videoStream the [video stream][VideoStream] for which the cache key should be
             * created
             * @return a key to be used to store the cache of the provided [video stream][VideoStream]
             */
            @JvmStatic
            fun cacheKeyOf(info: StreamInfo, videoStream: VideoStream): String {
                val resolutionUnknown = videoStream.resolution == VideoStream.RESOLUTION_UNKNOWN
                val cacheKey = commonCacheKeyOf(info, videoStream, resolutionUnknown)
                // resolution (if known)
                if (!resolutionUnknown) {
                    cacheKey.append(" ")
                    cacheKey.append(videoStream.resolution)
                }
                // isVideoOnly
                cacheKey.append(" ")
                cacheKey.append(videoStream.isVideoOnly)
                return cacheKey.toString()
            }

            /**
             * Builds the cache key of an audio stream.
             *
             * A cache key is unique to the features of the provided [audio stream][AudioStream], and
             * when possible independent of *transient* parameters (such as the URL of the stream).
             * This ensures that there are no conflicts, but also that the cache is used as much as
             * possible: the same cache should be used for two streams which have the same features but
             * e.g. a different URL, since the URL might have been reloaded in the meantime, but the stream
             * actually referenced by the URL is still the same.
             *
             *
             * @param info        the [stream info][StreamInfo], to distinguish between streams with
             * the same features but coming from different stream infos
             * @param audioStream the [audio stream][AudioStream] for which the cache key should be
             * created
             * @return a key to be used to store the cache of the provided [audio stream][AudioStream]
             */
            @JvmStatic
            fun cacheKeyOf(info: StreamInfo, audioStream: AudioStream): String {
                val averageBitrateUnknown = audioStream.averageBitrate == AudioStream.UNKNOWN_BITRATE
                val cacheKey = commonCacheKeyOf(info, audioStream, averageBitrateUnknown)
                // averageBitrate (if known)
                if (!averageBitrateUnknown) {
                    cacheKey.append(" ")
                    cacheKey.append(audioStream.averageBitrate)
                }
                if (audioStream.audioTrackId != null) {
                    cacheKey.append(" ")
                    cacheKey.append(audioStream.audioTrackId)
                }
                if (audioStream.audioLocale != null) {
                    cacheKey.append(" ")
                    cacheKey.append(audioStream.audioLocale!!.isO3Language)
                }
                return cacheKey.toString()
            }

            /**
             * Use common base type [Stream] to handle [AudioStream] or [VideoStream]
             * transparently. For more info see [.cacheKeyOf] or
             * [.cacheKeyOf].
             *
             * @param info   the [stream info][StreamInfo], to distinguish between streams with
             * the same features but coming from different stream infos
             * @param stream the [Stream] ([AudioStream] or [VideoStream])
             * for which the cache key should be created
             * @return a key to be used to store the cache of the provided [Stream]
             */
            @JvmStatic
            fun cacheKeyOf(info: StreamInfo, stream: Stream?): String {
                if (stream is AudioStream) return cacheKeyOf(info, stream)
                else if (stream is VideoStream) return cacheKeyOf(info, stream)
                throw RuntimeException("no audio or video stream. That should never happen")
            }

            @JvmStatic
            fun maybeBuildLiveMediaSource(dataSource: PlayerDataSource, info: StreamInfo): MediaSource? {
                if (!isLiveStream(info.streamType)) return null
                try {
                    val tag = StreamInfoTag.of(info)
                    if (info.hlsUrl.isNotEmpty()) return buildLiveMediaSource(dataSource, info.hlsUrl, C.CONTENT_TYPE_HLS, tag)
                    else if (info.dashMpdUrl.isNotEmpty()) return buildLiveMediaSource(dataSource, info.dashMpdUrl, C.CONTENT_TYPE_DASH, tag)
                } catch (e: Exception) {
                    Log.w(TAG, "Error when generating live media source, falling back to standard sources", e)
                }
                return null
            }

            @Throws(ResolverException::class)
            fun buildLiveMediaSource(dataSource: PlayerDataSource, sourceUrl: String?, type: @C.ContentType Int, metadata: MediaItemTag?): MediaSource {
                val factory: MediaSource.Factory = when (type) {
                    C.CONTENT_TYPE_SS -> dataSource.liveSsMediaSourceFactory
                    C.CONTENT_TYPE_DASH -> dataSource.liveDashMediaSourceFactory
                    C.CONTENT_TYPE_HLS -> dataSource.liveHlsMediaSourceFactory
                    C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_RTSP -> throw ResolverException("Unsupported type: $type")
                    else -> throw ResolverException("Unsupported type: $type")
                }
                return factory.createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(sourceUrl))
                        .setLiveConfiguration(LiveConfiguration.Builder().setTargetOffsetMs(PlayerDataSource.LIVE_STREAM_EDGE_GAP_MILLIS.toLong()).build())
                        .build())
            }

            @JvmStatic
            @Throws(ResolverException::class)
            fun buildMediaSource(dataSource: PlayerDataSource, stream: Stream, streamInfo: StreamInfo, cacheKey: String, metadata: MediaItemTag): MediaSource {
                if (streamInfo.service === ServiceList.YouTube) return createYoutubeMediaSource(stream, streamInfo, dataSource, cacheKey, metadata)
                return when (val deliveryMethod = stream.deliveryMethod) {
                    DeliveryMethod.PROGRESSIVE_HTTP -> buildProgressiveMediaSource(dataSource, stream, cacheKey, metadata)
                    DeliveryMethod.DASH -> buildDashMediaSource(dataSource, stream, cacheKey, metadata)
                    DeliveryMethod.HLS -> buildHlsMediaSource(dataSource, stream, cacheKey, metadata)
                    DeliveryMethod.SS -> buildSSMediaSource(dataSource, stream, cacheKey, metadata)
                    else -> throw ResolverException("Unsupported delivery type: $deliveryMethod")
                }
            }

            @Throws(ResolverException::class)
            private fun buildProgressiveMediaSource(dataSource: PlayerDataSource, stream: Stream, cacheKey: String, metadata: MediaItemTag): ProgressiveMediaSource {
                if (!stream.isUrl) throw ResolverException("Non URI progressive contents are not supported")
                throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                return dataSource.progressiveMediaSourceFactory.createMediaSource(
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            @Throws(ResolverException::class)
            private fun buildDashMediaSource(dataSource: PlayerDataSource, stream: Stream, cacheKey: String, metadata: MediaItemTag): DashMediaSource {
                if (stream.isUrl) {
                    throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                    return dataSource.dashMediaSourceFactory.createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                }
                try {
                    return dataSource.dashMediaSourceFactory.createMediaSource(
                        createDashManifest(stream.content, stream),
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(manifestUrlToUri(stream.manifestUrl))
                            .setCustomCacheKey(cacheKey)
                            .build())
                } catch (e: IOException) {
                    throw ResolverException("Could not create a DASH media source/manifest from the manifest text", e)
                }
            }

            @Throws(IOException::class)
            private fun createDashManifest(manifestContent: String, stream: Stream): DashManifest {
                return DashManifestParser().parse(manifestUrlToUri(stream.manifestUrl),
                    ByteArrayInputStream(manifestContent.toByteArray(StandardCharsets.UTF_8)))
            }

            @Throws(ResolverException::class)
            private fun buildHlsMediaSource(dataSource: PlayerDataSource, stream: Stream, cacheKey: String, metadata: MediaItemTag): HlsMediaSource {
                if (stream.isUrl) {
                    throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                    return dataSource.getHlsMediaSourceFactory(null).createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                }

                val hlsDataSourceFactoryBuilder = NonUriHlsDataSourceFactory.Builder()
                hlsDataSourceFactoryBuilder.setPlaylistString(stream.content)

                return dataSource.getHlsMediaSourceFactory(hlsDataSourceFactoryBuilder)
                    .createMediaSource(MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(manifestUrlToUri(stream.manifestUrl))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            @Throws(ResolverException::class)
            private fun buildSSMediaSource(dataSource: PlayerDataSource, stream: Stream, cacheKey: String, metadata: MediaItemTag): SsMediaSource {
                if (stream.isUrl) {
                    throwResolverExceptionIfUrlNullOrEmpty(stream.content)
                    return dataSource.sSMediaSourceFactory.createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                }

                val manifestUri = manifestUrlToUri(stream.manifestUrl)
                val smoothStreamingManifest: SsManifest
                try {
                    val smoothStreamingManifestInput = ByteArrayInputStream(stream.content.toByteArray(StandardCharsets.UTF_8))
                    smoothStreamingManifest = SsManifestParser().parse(manifestUri, smoothStreamingManifestInput)
                } catch (e: IOException) {
                    throw ResolverException("Error when parsing manual SS manifest", e)
                }
                return dataSource.sSMediaSourceFactory.createMediaSource(
                    smoothStreamingManifest,
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(manifestUri)
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            @Throws(ResolverException::class)
            private fun createYoutubeMediaSource(stream: Stream, streamInfo: StreamInfo, dataSource: PlayerDataSource, cacheKey: String, metadata: MediaItemTag): MediaSource {
                if (!(stream is AudioStream || stream is VideoStream))
                    throw ResolverException("Generation of YouTube DASH manifest for " + stream.javaClass.simpleName + " is not supported")

                val streamType = streamInfo.streamType
                when (streamType) {
                    StreamType.VIDEO_STREAM -> return createYoutubeMediaSourceOfVideoStreamType(dataSource, stream, streamInfo, cacheKey, metadata)
                    StreamType.POST_LIVE_STREAM -> {
                        // If the content is not an URL, uses the DASH delivery method and if the stream type
                        // of the stream is a post live stream, it means that the content is an ended
                        // livestream so we need to generate the manifest corresponding to the content
                        // (which is the last segment of the stream)
                        try {
                            val itagItem = stream.getItagItem() ?: throw ResolverException("itagItem is null")
                            val manifestString = YoutubePostLiveStreamDvrDashManifestCreator
                                .fromPostLiveStreamDvrStreamingUrl(stream.content, itagItem, itagItem.getTargetDurationSec(), streamInfo.duration)
                            return buildYoutubeManualDashMediaSource(dataSource, createDashManifest(manifestString, stream), stream, cacheKey, metadata)
                        } catch (e: CreationException) {
                            throw ResolverException("Error when generating the DASH manifest of YouTube ended live stream", e)
                        } catch (e: IOException) {
                            throw ResolverException("Error when generating the DASH manifest of YouTube ended live stream", e)
                        } catch (e: NullPointerException) {
                            throw ResolverException("Error when generating the DASH manifest of YouTube ended live stream", e)
                        }
                    }
                    else -> throw ResolverException("DASH manifest generation of YouTube livestreams is not supported")
                }
            }

            @Throws(ResolverException::class)
            private fun createYoutubeMediaSourceOfVideoStreamType(dataSource: PlayerDataSource, stream: Stream, streamInfo: StreamInfo,
                                                                  cacheKey: String, metadata: MediaItemTag): MediaSource {
                when (val deliveryMethod = stream.deliveryMethod) {
                    DeliveryMethod.PROGRESSIVE_HTTP -> {
                        if ((stream is VideoStream && stream.isVideoOnly) || stream is AudioStream) {
                            try {
                                if (stream.getItagItem() == null) throw ResolverException("stream.itagItem is null")
                                val manifestString = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                                    stream.content, stream.getItagItem()!!, streamInfo.duration)
                                return buildYoutubeManualDashMediaSource(dataSource, createDashManifest(manifestString, stream), stream,
                                    cacheKey, metadata)
//                                return buildYoutubeDefaultMediaSource(dataSource, stream, cacheKey, metadata)
                            } catch (e: CreationException) {
                                Log.w(TAG, "Error when generating or parsing DASH manifest of YouTube progressive stream, falling back to a ProgressiveMediaSource.", e)
                                return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey, metadata)
                            } catch (e: IOException) {
                                Log.w(TAG,
                                    "Error when generating or parsing DASH manifest of YouTube progressive stream, falling back to a ProgressiveMediaSource.",
                                    e)
                                return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey, metadata)
                            } catch (e: NullPointerException) {
                                Log.w(TAG,
                                    "Error when generating or parsing DASH manifest of YouTube progressive stream, falling back to a ProgressiveMediaSource.",
                                    e)
                                return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey, metadata)
                            }
                        } else {
                            // Legacy progressive streams, subtitles are handled by VideoPlaybackResolver
                            // TODO: test for resolving legacy content with media3 1.4.0
                            Logd(TAG, "calling buildYoutubeDefaultMediaSource")
                            return buildYoutubeProgressiveMediaSource(dataSource, stream, cacheKey, metadata)
//                            return buildYoutubeDefaultMediaSource(dataSource, stream, cacheKey, metadata)
                        }
                    }
                    DeliveryMethod.DASH -> {
                        // If the content is not a URL, uses the DASH delivery method and if the stream
                        // type of the stream is a video stream, it means the content is an OTF stream
                        // so we need to generate the manifest corresponding to the content (which is
                        // the base URL of the OTF stream).
                        try {
                            if (stream.getItagItem() == null) throw ResolverException("stream.itagItem is null")
                            val manifestString = YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(stream.content, stream.getItagItem()!!, streamInfo.duration)
                            return buildYoutubeManualDashMediaSource(dataSource, createDashManifest(manifestString, stream), stream, cacheKey, metadata)
                        } catch (e: CreationException) {
                            Log.e(TAG, "Error when generating the DASH manifest of YouTube OTF stream", e)
                            throw ResolverException("Error when generating the DASH manifest of YouTube OTF stream", e)
                        } catch (e: IOException) {
                            Log.e(TAG, "Error when generating the DASH manifest of YouTube OTF stream", e)
                            throw ResolverException("Error when generating the DASH manifest of YouTube OTF stream", e)
                        } catch (e: NullPointerException) {
                            Log.e(TAG, "Error when generating the DASH manifest of YouTube OTF stream", e)
                            throw ResolverException("Error when generating the DASH manifest of YouTube OTF stream", e)
                        } catch (e: Exception) {
                            return dataSource.youtubeHlsMediaSourceFactory.createMediaSource(
                                MediaItem.Builder().setTag(metadata).setUri(Uri.parse(stream.content)).setCustomCacheKey(cacheKey).build())
                        }
                    }
                    DeliveryMethod.HLS -> return dataSource.youtubeHlsMediaSourceFactory.createMediaSource(
                        MediaItem.Builder()
                            .setTag(metadata)
                            .setUri(Uri.parse(stream.content))
                            .setCustomCacheKey(cacheKey)
                            .build())
                    else -> throw ResolverException("Unsupported delivery method for YouTube contents: $deliveryMethod")
                }
            }

            private fun buildYoutubeManualDashMediaSource(dataSource: PlayerDataSource, dashManifest: DashManifest, stream: Stream,
                                                          cacheKey: String, metadata: MediaItemTag): DashMediaSource {
                return dataSource.youtubeDashMediaSourceFactory.createMediaSource(dashManifest,
                    MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            private fun buildYoutubeProgressiveMediaSource(dataSource: PlayerDataSource, stream: Stream, cacheKey: String,
                                                           metadata: MediaItemTag): ProgressiveMediaSource {
                return dataSource.youtubeProgressiveMediaSourceFactory
                    .createMediaSource(MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            private fun buildYoutubeDefaultMediaSource(dataSource: PlayerDataSource, stream: Stream, cacheKey: String,
                                                           metadata: MediaItemTag): MediaSource {
                return dataSource.youtubeDefaultMediaSourceFactory
                    .createMediaSource(MediaItem.Builder()
                        .setTag(metadata)
                        .setUri(Uri.parse(stream.content))
                        .setCustomCacheKey(cacheKey)
                        .build())
            }

            private fun manifestUrlToUri(manifestUrl: String?): Uri {
                return Uri.parse(Objects.requireNonNullElse(manifestUrl, ""))
            }

            @Throws(ResolverException::class)
            private fun throwResolverExceptionIfUrlNullOrEmpty(url: String?) {
                if (url == null) throw ResolverException("Null stream URL")
                else if (url.isEmpty()) throw ResolverException("Empty stream URL")
            }
        }
    }

    @UnstableApi class PlayerDataSource(
            context: Context,
            transferListener: TransferListener?) {
        private val progressiveLoadIntervalBytes = getProgressiveLoadIntervalBytes(context)

        // Generic Data Source Factories (without or with cache)
        private val cachelessDataSourceFactory: DataSource.Factory
        private val cacheDataSourceFactory: CacheFactory

        // YouTube-specific Data Source Factories (with cache)
        // They use YoutubeHttpDataSource.Factory, with different parameters each
        private val ytHlsCacheDataSourceFactory: CacheFactory
        private val ytDashCacheDataSourceFactory: CacheFactory
        private val ytProgressiveDashCacheDataSourceFactory: CacheFactory

        val dashMediaSourceFactory: DashMediaSource.Factory
            get() = DashMediaSource.Factory(getDefaultDashChunkSourceFactory(cacheDataSourceFactory), cacheDataSourceFactory)

        val progressiveMediaSourceFactory: ProgressiveMediaSource.Factory
            get() = ProgressiveMediaSource.Factory(cacheDataSourceFactory).setContinueLoadingCheckIntervalBytes(progressiveLoadIntervalBytes)

        val sSMediaSourceFactory: SsMediaSource.Factory
            get() = SsMediaSource.Factory(DefaultSsChunkSource.Factory(cachelessDataSourceFactory), cachelessDataSourceFactory)

        val singleSampleMediaSourceFactory: SingleSampleMediaSource.Factory
            get() = SingleSampleMediaSource.Factory(cacheDataSourceFactory)

        val youtubeHlsMediaSourceFactory: HlsMediaSource.Factory
            get() = HlsMediaSource.Factory(ytHlsCacheDataSourceFactory)

        val youtubeDashMediaSourceFactory: DashMediaSource.Factory
            get() = DashMediaSource.Factory(getDefaultDashChunkSourceFactory(ytDashCacheDataSourceFactory), ytDashCacheDataSourceFactory)

        val youtubeProgressiveMediaSourceFactory: ProgressiveMediaSource.Factory
            get() = ProgressiveMediaSource.Factory(ytProgressiveDashCacheDataSourceFactory).setContinueLoadingCheckIntervalBytes(progressiveLoadIntervalBytes)

        val youtubeDefaultMediaSourceFactory: DefaultMediaSourceFactory
            get() = DefaultMediaSourceFactory(ytProgressiveDashCacheDataSourceFactory)

        init {
            // make sure the static cache was created: needed by CacheFactories below
            instantiateCacheIfNeeded(context)

            // generic data source factories use DefaultHttpDataSource.Factory
            cachelessDataSourceFactory = DefaultDataSource.Factory(context,
                DefaultHttpDataSource.Factory().setUserAgent(DownloaderImpl.USER_AGENT)).setTransferListener(transferListener)
            cacheDataSourceFactory = CacheFactory(context, transferListener!!, cache!!,
                DefaultHttpDataSource.Factory().setUserAgent(DownloaderImpl.USER_AGENT))

            // YouTube-specific data source factories use getYoutubeHttpDataSourceFactory()
            ytHlsCacheDataSourceFactory = CacheFactory(context, transferListener, cache!!,
                getYoutubeHttpDataSourceFactory(rangeParameterEnabled = false, rnParameterEnabled = false))
            ytDashCacheDataSourceFactory = CacheFactory(context, transferListener, cache!!,
                getYoutubeHttpDataSourceFactory(rangeParameterEnabled = true, rnParameterEnabled = true))
            ytProgressiveDashCacheDataSourceFactory = CacheFactory(context, transferListener, cache!!,
                getYoutubeHttpDataSourceFactory(rangeParameterEnabled = false, rnParameterEnabled = true))

            // set the maximum size to manifest creators
            YoutubeProgressiveDashManifestCreator.cache.setMaximumSize(MAX_MANIFEST_CACHE_SIZE)
            YoutubeOtfDashManifestCreator.cache.setMaximumSize(MAX_MANIFEST_CACHE_SIZE)
            YoutubePostLiveStreamDvrDashManifestCreator.cache.setMaximumSize(MAX_MANIFEST_CACHE_SIZE)
        }

        //region Live media source factories
        val liveSsMediaSourceFactory: SsMediaSource.Factory
            get() = sSMediaSourceFactory.setLivePresentationDelayMs(LIVE_STREAM_EDGE_GAP_MILLIS.toLong())

        val liveHlsMediaSourceFactory: HlsMediaSource.Factory
            get() = HlsMediaSource.Factory(cachelessDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setPlaylistTrackerFactory { dataSourceFactory: HlsDataSourceFactory?, loadErrorHandlingPolicy: LoadErrorHandlingPolicy?, playlistParserFactory: HlsPlaylistParserFactory? ->
                    DefaultHlsPlaylistTracker(dataSourceFactory!!, loadErrorHandlingPolicy!!, playlistParserFactory!!, PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT) }

        val liveDashMediaSourceFactory: DashMediaSource.Factory
            get() = DashMediaSource.Factory(getDefaultDashChunkSourceFactory(cachelessDataSourceFactory), cachelessDataSourceFactory)

        fun getHlsMediaSourceFactory(hlsDataSourceFactoryBuilder: NonUriHlsDataSourceFactory.Builder?): HlsMediaSource.Factory {
            if (hlsDataSourceFactoryBuilder != null) {
                hlsDataSourceFactoryBuilder.setDataSourceFactory(cacheDataSourceFactory)
                return HlsMediaSource.Factory(hlsDataSourceFactoryBuilder.build())
            }
            return HlsMediaSource.Factory(cacheDataSourceFactory)
        }

        @UnstableApi internal class CacheFactory(
                private val context: Context,
                private val transferListener: TransferListener,
                private val cache: SimpleCache,
                private val upstreamDataSourceFactory: DataSource.Factory) : DataSource.Factory {

            override fun createDataSource(): DataSource {
                val dataSource = DefaultDataSource.Factory(context, upstreamDataSourceFactory)
                    .setTransferListener(transferListener)
                    .createDataSource()

                val fileSource = FileDataSource()
                val dataSink = CacheDataSink(cache, preferredCacheSize)
                return CacheDataSource(cache, dataSource, fileSource, dataSink, CACHE_FLAGS, null)
            }
            companion object {
                private const val CACHE_FLAGS = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
            }
        }

        @UnstableApi companion object {
            val TAG: String = PlayerDataSource::class.java.simpleName

            const val LIVE_STREAM_EDGE_GAP_MILLIS: Int = 10000

            /**
             * An approximately 4.3 times greater value than the
             * [default][DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT]
             * to ensure that (very) low latency livestreams which got stuck for a moment don't crash too
             * early.
             */
            private const val PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT = 15.0

            /**
             * The maximum number of generated manifests per cache, in
             * [YoutubeProgressiveDashManifestCreator], [YoutubeOtfDashManifestCreator] and
             * [YoutubePostLiveStreamDvrDashManifestCreator].
             */
            private const val MAX_MANIFEST_CACHE_SIZE = 500

            /**
             * The folder name in which the ExoPlayer cache will be written.
             */
            private const val CACHE_FOLDER_NAME = "exoplayer"

            /**
             * The [SimpleCache] instance which will be used to build
             * [CacheFactory]).
             */
            private var cache: SimpleCache? = null

            private fun getDefaultDashChunkSourceFactory(dataSourceFactory: DataSource.Factory): DefaultDashChunkSource.Factory {
                return DefaultDashChunkSource.Factory(dataSourceFactory)
            }

            private fun getYoutubeHttpDataSourceFactory(rangeParameterEnabled: Boolean, rnParameterEnabled: Boolean): YoutubeHttpDataSource.Factory {
                return YoutubeHttpDataSource.Factory()
                    .setRangeParameterEnabled(rangeParameterEnabled)
                    .setRnParameterEnabled(rnParameterEnabled)
            }

            private fun instantiateCacheIfNeeded(context: Context) {
                if (cache == null) {
                    val cacheDir = File(context.externalCacheDir, CACHE_FOLDER_NAME)
                    Logd(TAG, "instantiateCacheIfNeeded: cacheDir = " + cacheDir.absolutePath)

                    if (!cacheDir.exists() && !cacheDir.mkdir()) Log.w(TAG, "instantiateCacheIfNeeded: could not create cache dir")

                    val evictor = LeastRecentlyUsedCacheEvictor(preferredCacheSize)
                    cache = SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(context))
                }
            }
        }
    }

    /**
     * A [HlsDataSourceFactory] which allows playback of non-URI media HLS playlists for
     *
     * If media requests are relative, the URI from which the manifest comes from (either the
     * manifest URI (preferred) or the master URI (if applicable)) must be returned, otherwise the
     * content will be not playable, as it will be an invalid URL, or it may be treat as something
     * unexpected, for instance as a file for
     *
     * See [.createDataSource] for changes and implementation details.
     */
    /**
     * Create a [NonUriHlsDataSourceFactory] instance.
     *
     * @param dataSourceFactory       the [DataSource.Factory] which will be used to build
     * non manifests [DataSource]s, which must not be null
     * @param playlistStringByteArray a byte array of the HLS playlist, which must not be null
     */
    @UnstableApi class NonUriHlsDataSourceFactory private constructor(
            private val dataSourceFactory: DataSource.Factory,
            private val playlistStringByteArray: ByteArray) : HlsDataSourceFactory {
        /**
         * Builder class of [NonUriHlsDataSourceFactory] instances.
         */
        class Builder {
            private var dataSourceFactory: DataSource.Factory? = null
            private var playlistString: String? = null

            /**
             * Set the [DataSource.Factory] which will be used to create non manifest contents
             * [DataSource]s.
             *
             * @param dataSourceFactoryForNonManifestContents the [DataSource.Factory] which will
             * be used to create non manifest contents
             * [DataSource]s, which cannot be null
             */
            fun setDataSourceFactory(dataSourceFactoryForNonManifestContents: DataSource.Factory) {
                this.dataSourceFactory = dataSourceFactoryForNonManifestContents
            }

            /**
             * Set the HLS playlist which will be used for manifests requests.
             *
             * @param hlsPlaylistString the string which correspond to the response of the HLS
             * manifest, which cannot be null or empty
             */
            fun setPlaylistString(hlsPlaylistString: String) {
                this.playlistString = hlsPlaylistString
            }

            /**
             * Create a new [NonUriHlsDataSourceFactory] with the given data source factory and
             * the given HLS playlist.
             *
             * @return a [NonUriHlsDataSourceFactory]
             * @throws IllegalArgumentException if the data source factory is null or if the HLS
             * playlist string set is null or empty
             */
            fun build(): NonUriHlsDataSourceFactory {
                requireNotNull(dataSourceFactory) { "No DataSource.Factory valid instance has been specified." }
                require(!playlistString.isNullOrEmpty()) { "No HLS valid playlist has been specified." }
                return NonUriHlsDataSourceFactory(dataSourceFactory!!, playlistString!!.toByteArray(StandardCharsets.UTF_8))
            }
        }

        /**
         * Create a [DataSource] for the given data type.
         *
         * [DataSource.Factory] passed to the
         * [the manifest type][C.DATA_TYPE_MANIFEST].
         *
         * This change allow playback of non-URI HLS contents, when the manifest is not a master
         * manifest/playlist (otherwise, endless loops should be encountered because the
         * [DataSource]s created for media playlists should use the master playlist response
         * instead).
         *
         * @param dataType the data type for which the [DataSource] will be used, which is one of
         * [C] `.DATA_TYPE_*` constants
         * @return a [DataSource] for the given data type
         */
        override fun createDataSource(dataType: Int): DataSource {
            // The manifest is already downloaded and provided with playlistStringByteArray, so we
            // don't need to download it again and we can use a ByteArrayDataSource instead
            if (dataType == C.DATA_TYPE_MANIFEST) return ByteArrayDataSource(playlistStringByteArray)

            return dataSourceFactory.createDataSource()
        }
    }

    /**
     * An [HttpDataSource] that uses Android's [HttpURLConnection], based on
     *
     * It adds more headers to `videoplayback` URLs, such as `Origin`, `Referer`
     * (only where it's relevant) and also more parameters, such as `rn` and replaces the use of
     * the `Range` header by the corresponding parameter (`range`), if enabled.
     *
     *
     * There are many unused methods in this class because everything was copied from [ ] with as little changes as possible.
     * SonarQube warnings were also suppressed for the same reason.
     */
    @UnstableApi class YoutubeHttpDataSource private constructor(
            private val connectTimeoutMillis: Int,
            private val readTimeoutMillis: Int,
            private val allowCrossProtocolRedirects: Boolean,
            private val rangeParameterEnabled: Boolean,
            private val rnParameterEnabled: Boolean,
            private val defaultRequestProperties: HttpDataSource.RequestProperties?,
            private val contentTypePredicate: Predicate<String>?,
            keepPostFor302Redirects: Boolean)
        : BaseDataSource(true), HttpDataSource {

        private val requestProperties = HttpDataSource.RequestProperties()
        private val keepPostFor302Redirects: Boolean

        private var dataSpec: DataSpec? = null
        private var connection: HttpURLConnection? = null
        private var inputStream: InputStream? = null
        private var opened = false
        private var responseCode = 0
        private var bytesToRead: Long = 0
        private var bytesRead: Long = 0

        private var requestNumber: Long

        init {
            this.keepPostFor302Redirects = keepPostFor302Redirects
            this.requestNumber = 0
        }

        override fun getUri(): Uri? {
            return if (connection == null) null else Uri.parse(connection!!.url.toString())
        }

        override fun getResponseCode(): Int {
            return if (connection == null || responseCode <= 0) -1 else responseCode
        }

        override fun getResponseHeaders(): Map<String, List<String>> {
            if (connection == null) return mapOf()

            // connection.getHeaderFields() always contains a null key with a value like
            // ["HTTP/1.1 200 OK"]. The response code is available from
            // HttpURLConnection#getResponseCode() and the HTTP version is fixed when establishing the
            // connection.
            // DataSource#getResponseHeaders() doesn't allow null keys in the returned map, so we need
            // to remove it.
            // connection.getHeaderFields() returns a special unmodifiable case-insensitive Map
            // so we can't just remove the null key or make a copy without the null key. Instead we
            // wrap it in a ForwardingMap subclass that ignores and filters out null keys in the read
            // methods.
//            return NullFilteringHeadersMap(connection!!.headerFields)
            return connection!!.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
        }

        override fun setRequestProperty(name: String, value: String) {
            Assertions.checkNotNull(name)
            Assertions.checkNotNull(value)
            requestProperties[name] = value
        }

        override fun clearRequestProperty(name: String) {
            Assertions.checkNotNull(name)
            requestProperties.remove(name)
        }

        override fun clearAllRequestProperties() {
            requestProperties.clear()
        }

        /**
         * Opens the source to read the specified data.
         */
        @Throws(HttpDataSource.HttpDataSourceException::class)
        override fun open(dataSpecParameter: DataSpec): Long {
            this.dataSpec = dataSpecParameter
            bytesRead = 0
            bytesToRead = 0
            transferInitializing(dataSpecParameter)

            val httpURLConnection: HttpURLConnection?
            val responseMessage: String
            try {
                this.connection = makeConnection(dataSpec!!)
                httpURLConnection = this.connection
                responseCode = httpURLConnection!!.responseCode
                responseMessage = httpURLConnection.responseMessage
            } catch (e: IOException) {
                closeConnectionQuietly()
                throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec!!, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }

            // Check for a valid response code.
            if (responseCode < 200 || responseCode > 299) {
                val headers = httpURLConnection.headerFields
                if (responseCode == 416) {
                    val documentSize = HttpUtil.getDocumentSize(httpURLConnection.getHeaderField(HttpHeaders.CONTENT_RANGE))
                    if (dataSpecParameter.position == documentSize) {
                        opened = true
                        transferStarted(dataSpecParameter)
                        return if (dataSpecParameter.length != C.LENGTH_UNSET.toLong()) dataSpecParameter.length else 0
                    }
                }

                val errorStream = httpURLConnection.errorStream
                val errorResponseBody = try {
                    if (errorStream != null) Util.toByteArray(errorStream) else Util.EMPTY_BYTE_ARRAY
                } catch (e: IOException) {
                    Util.EMPTY_BYTE_ARRAY
                }

                closeConnectionQuietly()
                val cause: IOException? = if (responseCode == 416) DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) else null
                throw HttpDataSource.InvalidResponseCodeException(responseCode, responseMessage, cause, headers, dataSpec!!, errorResponseBody)
            }

            // Check for a valid content type.
            val contentType = httpURLConnection.contentType
            if (contentTypePredicate != null && !contentTypePredicate.apply(contentType)) {
                closeConnectionQuietly()
                throw HttpDataSource.InvalidContentTypeException(contentType, dataSpecParameter)
            }
            // If we requested a range starting from a non-zero position and received a 200 rather
            // than a 206, then the server does not support partial requests. We'll need to
            // manually skip to the requested position.
            val bytesToSkip = if (!rangeParameterEnabled) {
                if (responseCode == 200 && dataSpecParameter.position != 0L) dataSpecParameter.position else 0
            } else 0

            // Determine the length of the data to be read, after skipping.
            val isCompressed = isCompressed(httpURLConnection)
            if (!isCompressed) {
                if (dataSpecParameter.length != C.LENGTH_UNSET.toLong()) bytesToRead = dataSpecParameter.length
                else {
                    val contentLength = HttpUtil.getContentLength(
                        httpURLConnection.getHeaderField(HttpHeaders.CONTENT_LENGTH),
                        httpURLConnection.getHeaderField(HttpHeaders.CONTENT_RANGE))
                    bytesToRead = if (contentLength != C.LENGTH_UNSET.toLong()) (contentLength - bytesToSkip) else C.LENGTH_UNSET.toLong()
                }
            } else {
                // Gzip is enabled. If the server opts to use gzip then the content length in the
                // response will be that of the compressed data, which isn't what we want. Always use
                // the dataSpec length in this case.
                bytesToRead = dataSpecParameter.length
            }

            try {
                inputStream = httpURLConnection.inputStream
                if (isCompressed) inputStream = GZIPInputStream(inputStream)
            } catch (e: IOException) {
                closeConnectionQuietly()
                throw HttpDataSource.HttpDataSourceException(e, dataSpec!!,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }

            opened = true
            transferStarted(dataSpecParameter)

            try {
                skipFully(bytesToSkip, dataSpec!!)
            } catch (e: IOException) {
                closeConnectionQuietly()
                if (e is HttpDataSource.HttpDataSourceException) throw e
                throw HttpDataSource.HttpDataSourceException(e, dataSpec!!, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }
            return bytesToRead
        }

        @Throws(HttpDataSource.HttpDataSourceException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            try {
                return readInternal(buffer, offset, length)
            } catch (e: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(e, Util.castNonNull(dataSpec), HttpDataSource.HttpDataSourceException.TYPE_READ)
            }
        }

        @Throws(HttpDataSource.HttpDataSourceException::class)
        override fun close() {
            try {
                val connectionInputStream = this.inputStream
                if (connectionInputStream != null) {
                    val bytesRemaining = if (bytesToRead == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else bytesToRead - bytesRead
                    maybeTerminateInputStream(connection, bytesRemaining)
                    try {
                        connectionInputStream.close()
                    } catch (e: IOException) {
                        throw HttpDataSource.HttpDataSourceException(e, Util.castNonNull(dataSpec),
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                            HttpDataSource.HttpDataSourceException.TYPE_CLOSE)
                    }
                }
            } finally {
                inputStream = null
                closeConnectionQuietly()
                if (opened) {
                    opened = false
                    transferEnded()
                }
            }
        }

        @Throws(IOException::class)
        private fun makeConnection(dataSpecToUse: DataSpec): HttpURLConnection {
            var url = URL(dataSpecToUse.uri.toString())
            var httpMethod: @DataSpec.HttpMethod Int = dataSpecToUse.httpMethod
            var httpBody = dataSpecToUse.httpBody
            val position = dataSpecToUse.position
            val length = dataSpecToUse.length
            val allowGzip = dataSpecToUse.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)

            // HttpURLConnection disallows cross-protocol redirects, but otherwise performs
            // redirection automatically. This is the behavior we want, so use it.
            if (!allowCrossProtocolRedirects && !keepPostFor302Redirects)
                return makeConnection(url, httpMethod, httpBody, position, length, allowGzip, true, dataSpecToUse.httpRequestHeaders)

            // We need to handle redirects ourselves to allow cross-protocol redirects or to keep the
            // POST request method for 302.
            var redirectCount = 0
            while (redirectCount++ <= MAX_REDIRECTS) {
                val httpURLConnection = makeConnection(url, httpMethod, httpBody, position, length, allowGzip, false, dataSpecToUse.httpRequestHeaders)
                val httpURLConnectionResponseCode = httpURLConnection.responseCode
                val location = httpURLConnection.getHeaderField("Location")
                when {
                    (httpMethod == DataSpec.HTTP_METHOD_GET || httpMethod == DataSpec.HTTP_METHOD_HEAD)
                            && (httpURLConnectionResponseCode == HttpURLConnection.HTTP_MULT_CHOICE || httpURLConnectionResponseCode == HttpURLConnection.HTTP_MOVED_PERM || httpURLConnectionResponseCode == HttpURLConnection.HTTP_MOVED_TEMP || httpURLConnectionResponseCode == HttpURLConnection.HTTP_SEE_OTHER || httpURLConnectionResponseCode == HTTP_STATUS_TEMPORARY_REDIRECT || httpURLConnectionResponseCode == HTTP_STATUS_PERMANENT_REDIRECT) -> {
                        httpURLConnection.disconnect()
                        url = handleRedirect(url, location, dataSpecToUse)
                    }
                    httpMethod == DataSpec.HTTP_METHOD_POST
                            && (httpURLConnectionResponseCode == HttpURLConnection.HTTP_MULT_CHOICE || httpURLConnectionResponseCode == HttpURLConnection.HTTP_MOVED_PERM || httpURLConnectionResponseCode == HttpURLConnection.HTTP_MOVED_TEMP || httpURLConnectionResponseCode == HttpURLConnection.HTTP_SEE_OTHER) -> {
                        httpURLConnection.disconnect()
                        val shouldKeepPost = (keepPostFor302Redirects && responseCode == HttpURLConnection.HTTP_MOVED_TEMP)
                        if (!shouldKeepPost) {
                            // POST request follows the redirect and is transformed into a GET request.
                            httpMethod = DataSpec.HTTP_METHOD_GET
                            httpBody = null
                        }
                        url = handleRedirect(url, location, dataSpecToUse)
                    }
                    else -> return httpURLConnection
                }
            }

            // If we get here we've been redirected more times than are permitted.
            throw HttpDataSource.HttpDataSourceException(
                NoRouteToHostException("Too many redirects: $redirectCount"),
                dataSpecToUse,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }

        /**
         * Configures a connection and opens it.
         *
         * @param url               The url to connect to.
         * @param httpMethod        The http method.
         * @param httpBody          The body data, or `null` if not required.
         * @param position          The byte offset of the requested data.
         * @param length            The length of the requested data, or [C.LENGTH_UNSET].
         * @param allowGzip         Whether to allow the use of gzip.
         * @param followRedirects   Whether to follow redirects.
         * @param requestParameters parameters (HTTP headers) to include in request.
         * @return the connection opened
         */
        @Throws(IOException::class)
        private fun makeConnection(url: URL, httpMethod: @DataSpec.HttpMethod Int, httpBody: ByteArray?, position: Long, length: Long,
                                   allowGzip: Boolean, followRedirects: Boolean, requestParameters: Map<String, String>): HttpURLConnection {
            // This is the method that contains breaking changes with respect to DefaultHttpDataSource!
            var requestUrl = url.toString()

            // Don't add the request number parameter if it has been already added (for instance in
            // DASH manifests) or if that's not a videoplayback URL
            val isVideoPlaybackUrl = url.path.startsWith("/videoplayback")
            if (isVideoPlaybackUrl && rnParameterEnabled && !requestUrl.contains(RN_PARAMETER)) {
                requestUrl += RN_PARAMETER + requestNumber
                ++requestNumber
            }

            if (rangeParameterEnabled && isVideoPlaybackUrl) {
                val rangeParameterBuilt = buildRangeParameter(position, length)
                if (rangeParameterBuilt != null) requestUrl += rangeParameterBuilt
            }

            val httpURLConnection = openConnection(URL(requestUrl))
            httpURLConnection.connectTimeout = connectTimeoutMillis
            httpURLConnection.readTimeout = readTimeoutMillis

            val requestHeaders: MutableMap<String, String> = HashMap()
            if (defaultRequestProperties != null) requestHeaders.putAll(defaultRequestProperties.snapshot)

            requestHeaders.putAll(requestProperties.snapshot)
            requestHeaders.putAll(requestParameters)

            for ((key, value) in requestHeaders) {
                httpURLConnection.setRequestProperty(key, value)
            }

            if (!rangeParameterEnabled) {
                val rangeHeader = HttpUtil.buildRangeRequestHeader(position, length)
                if (rangeHeader != null) httpURLConnection.setRequestProperty(HttpHeaders.RANGE, rangeHeader)
            }

            if (YoutubeParsingHelper.isWebStreamingUrl(requestUrl) || YoutubeParsingHelper.isTvHtml5SimplyEmbeddedPlayerStreamingUrl(requestUrl)) {
                httpURLConnection.setRequestProperty(HttpHeaders.ORIGIN, YOUTUBE_BASE_URL)
                httpURLConnection.setRequestProperty(HttpHeaders.REFERER, YOUTUBE_BASE_URL)
                httpURLConnection.setRequestProperty(HttpHeaders.SEC_FETCH_DEST, "empty")
                httpURLConnection.setRequestProperty(HttpHeaders.SEC_FETCH_MODE, "cors")
                httpURLConnection.setRequestProperty(HttpHeaders.SEC_FETCH_SITE, "cross-site")
            }

            httpURLConnection.setRequestProperty(HttpHeaders.TE, "trailers")

            val isAndroidStreamingUrl = YoutubeParsingHelper.isAndroidStreamingUrl(requestUrl)
            val isIosStreamingUrl = YoutubeParsingHelper.isIosStreamingUrl(requestUrl)
            when {
                // Improvement which may be done: find the content country used to request YouTube
                // contents to add it in the user agent instead of using the default
                isAndroidStreamingUrl -> httpURLConnection.setRequestProperty(HttpHeaders.USER_AGENT, YoutubeParsingHelper.getAndroidUserAgent(null))
                isIosStreamingUrl -> httpURLConnection.setRequestProperty(HttpHeaders.USER_AGENT, YoutubeParsingHelper.getIosUserAgent(null))
                // non-mobile user agent
                else -> httpURLConnection.setRequestProperty(HttpHeaders.USER_AGENT, DownloaderImpl.USER_AGENT)
            }

            httpURLConnection.setRequestProperty(HttpHeaders.ACCEPT_ENCODING, if (allowGzip) "gzip" else "identity")
            httpURLConnection.instanceFollowRedirects = followRedirects
            httpURLConnection.doOutput = httpBody != null

            // Mobile clients uses POST requests to fetch contents
            httpURLConnection.requestMethod = if (isAndroidStreamingUrl || isIosStreamingUrl) "POST" else DataSpec.getStringForHttpMethod(httpMethod)

            if (httpBody != null) {
                httpURLConnection.setFixedLengthStreamingMode(httpBody.size)
                httpURLConnection.connect()
                val os = httpURLConnection.outputStream
                os.write(httpBody)
                os.close()
            } else httpURLConnection.connect()

            return httpURLConnection
        }

        /**
         * Creates an [HttpURLConnection] that is connected with the `url`.
         *
         * @param url the [URL] to create an [HttpURLConnection]
         * @return an [HttpURLConnection] created with the `url`
         */
        @Throws(IOException::class)
        private fun openConnection(url: URL): HttpURLConnection {
            return url.openConnection() as HttpURLConnection
        }

        /**
         * Handles a redirect.
         *
         * @param originalUrl              The original URL.
         * @param location                 The Location header in the response. May be `null`.
         * @param dataSpecToHandleRedirect The [DataSpec].
         * @return The next URL.
         * @throws HttpDataSourceException If redirection isn't possible.
         */
        @Throws(HttpDataSource.HttpDataSourceException::class)
        private fun handleRedirect(originalUrl: URL, location: String?, dataSpecToHandleRedirect: DataSpec): URL {
            if (location == null) throw HttpDataSource.HttpDataSourceException("Null location redirect", dataSpecToHandleRedirect,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)

            // Form the new url.
            val url: URL
            try {
                url = URL(originalUrl, location)
            } catch (e: MalformedURLException) {
                throw HttpDataSource.HttpDataSourceException(e, dataSpecToHandleRedirect,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }

            // Check that the protocol of the new url is supported.
            val protocol = url.protocol
            if ("https" != protocol && "http" != protocol)
                throw HttpDataSource.HttpDataSourceException("Unsupported protocol redirect: $protocol", dataSpecToHandleRedirect,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)

            if (!allowCrossProtocolRedirects && protocol != originalUrl.protocol)
                throw HttpDataSource.HttpDataSourceException(
                    "Disallowed cross-protocol redirect (${originalUrl.protocol} to $protocol)",
                    dataSpecToHandleRedirect,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN)

            return url
        }

        /**
         * Attempts to skip the specified number of bytes in full.
         *
         * @param bytesToSkip   The number of bytes to skip.
         * @param dataSpecToUse The [DataSpec].
         * @throws IOException If the thread is interrupted during the operation, or if the data ended
         * before skipping the specified number of bytes.
         */
        @Throws(IOException::class)
        private fun skipFully(bytesToSkip: Long, dataSpecToUse: DataSpec) {
            if (bytesToSkip == 0L) return

            var bytesToSkip = bytesToSkip
            val skipBuffer = ByteArray(4096)
            while (bytesToSkip > 0) {
                val readLength = min(bytesToSkip.toDouble(), skipBuffer.size.toDouble()).toInt()
                val read = Util.castNonNull(inputStream).read(skipBuffer, 0, readLength)
                if (Thread.currentThread().isInterrupted) throw HttpDataSource.HttpDataSourceException(
                    InterruptedIOException(), dataSpecToUse,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
                if (read == -1) throw HttpDataSource.HttpDataSourceException(dataSpecToUse,
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
                bytesToSkip -= read.toLong()
                bytesTransferred(read)
            }
        }

        /**
         * Reads up to `length` bytes of data and stores them into `buffer`, starting at
         * index `offset`.
         *
         *
         *
         * This method blocks until at least one byte of data can be read, the end of the opened range
         * is detected, or an exception is thrown.
         *
         *
         * @param buffer     The buffer into which the read data should be stored.
         * @param offset     The start offset into `buffer` at which data should be written.
         * @param readLength The maximum number of bytes to read.
         * @return The number of bytes read, or [C.RESULT_END_OF_INPUT] if the end of the opened
         * range is reached.
         * @throws IOException If an error occurs reading from the source.
         */
        @Throws(IOException::class)
        private fun readInternal(buffer: ByteArray, offset: Int, readLength: Int): Int {
            var readLength = readLength
            if (readLength == 0) return 0

            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                val bytesRemaining = bytesToRead - bytesRead
                if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
                readLength = min(readLength.toDouble(), bytesRemaining.toDouble()).toInt()
            }

            val read = Util.castNonNull(inputStream).read(buffer, offset, readLength)
            if (read == -1) return C.RESULT_END_OF_INPUT

            bytesRead += read.toLong()
            bytesTransferred(read)
            return read
        }

        /**
         * Closes the current connection quietly, if there is one.
         */
        private fun closeConnectionQuietly() {
            if (connection != null) {
                try {
                    connection!!.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error while disconnecting", e)
                }
                connection = null
            }
        }

//        private class NullFilteringHeadersMap(private val headers: Map<String?, List<String>>) : ForwardingMap<String, List<String>>() {
//            override fun delegate(): Map<String?, List<String>> {
//                return headers
//            }
//
//            //  **** lint shows incorrect override nothing message: key: String? compiles
//            override fun containsKey(key: String?): Boolean {
//                return key != null && super.containsKey(key)
//            }
//
//            //  **** lint shows incorrect override nothing message: value: key: String? compiles
//            override fun get(key: String?): List<String>? {
//                return if (key == null) null else super.get(key)
//            }
//
//            override val keys: MutableSet<String>
//                get() = Sets.filter(super.keys) { obj: String? -> obj != null }
//
//            override val entries: MutableSet<MutableMap.MutableEntry<String, List<String>>>
//                get() = Sets.filter(super.entries) { entry: MutableMap.MutableEntry<String?, List<String>> -> entry.key != null }
//
//            override val size: Int
//                get() = super.size - (if (super.containsKey(null.toString())) 1 else 0)
//
//            override fun isEmpty(): Boolean {
//                return super.isEmpty() || (super.size == 1 && super.containsKey(null.toString()))
//            }
//
//            //  **** lint shows incorrect override nothing message: value: List<String>? compiles
//            override fun containsValue(value: List<String>): Boolean {
//                return super.standardContainsValue(value)
//            }
//
//            override fun equals(other: Any?): Boolean {
//                return other != null && super.standardEquals(other)
//            }
//
//            override fun hashCode(): Int {
//                return super.standardHashCode()
//            }
//        }

        /**
         * [DataSource.Factory] for [YoutubeHttpDataSource] instances.
         */
        class Factory : HttpDataSource.Factory {
            private val defaultRequestProperties = HttpDataSource.RequestProperties()

            private var transferListener: TransferListener? = null
            private var contentTypePredicate: Predicate<String>? = null
            private var connectTimeoutMs: Int
            private var readTimeoutMs: Int
            private var allowCrossProtocolRedirects = false
            private var keepPostFor302Redirects = false

            private var rangeParameterEnabled = false
            private var rnParameterEnabled = false

            /**
             * Creates an instance.
             */
            init {
                connectTimeoutMs = DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS
                readTimeoutMs = DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS
            }

            override fun setDefaultRequestProperties(defaultRequestPropertiesMap: Map<String, String>): Factory {
                defaultRequestProperties.clearAndSet(defaultRequestPropertiesMap)
                return this
            }

            /**
             * Sets the connect timeout, in milliseconds.
             * The default is [DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS].
             * @param connectTimeoutMsValue The connect timeout, in milliseconds, that will be used.
             * @return This factory.
             */
            fun setConnectTimeoutMs(connectTimeoutMsValue: Int): Factory {
                connectTimeoutMs = connectTimeoutMsValue
                return this
            }

            /**
             * Sets the read timeout, in milliseconds.
             * The default is [DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS].
             * @param readTimeoutMsValue The connect timeout, in milliseconds, that will be used.
             * @return This factory.
             */
            fun setReadTimeoutMs(readTimeoutMsValue: Int): Factory {
                readTimeoutMs = readTimeoutMsValue
                return this
            }

            /**
             * Sets whether to allow cross protocol redirects.
             *
             *
             * The default is `false`.
             *
             * @param allowCrossProtocolRedirectsValue Whether to allow cross protocol redirects.
             * @return This factory.
             */
            fun setAllowCrossProtocolRedirects(allowCrossProtocolRedirectsValue: Boolean): Factory {
                allowCrossProtocolRedirects = allowCrossProtocolRedirectsValue
                return this
            }

            /**
             * Sets whether the use of the `range` parameter instead of the `Range` header
             * to request ranges of streams is enabled.
             *
             * Note that it must be not enabled on streams which are using a [ ], as it will break playback
             * for them (some exceptions may be thrown).
             *
             * @param rangeParameterEnabledValue whether the use of the `range` parameter instead
             * of the `Range` header (must be only enabled when
             * non-`ProgressiveMediaSource`s)
             * @return This factory.
             */
            fun setRangeParameterEnabled(rangeParameterEnabledValue: Boolean): Factory {
                rangeParameterEnabled = rangeParameterEnabledValue
                return this
            }

            /**
             * Sets whether the use of the `rn`, which stands for request number, parameter is
             * enabled.
             *
             *
             *
             * Note that it should be not enabled on streams which are using `/` to delimit URLs
             * parameters, such as the streams of HLS manifests.
             *
             *
             * @param rnParameterEnabledValue whether the appending the `rn` parameter to
             * `videoplayback` URLs
             * @return This factory.
             */
            fun setRnParameterEnabled(rnParameterEnabledValue: Boolean): Factory {
                rnParameterEnabled = rnParameterEnabledValue
                return this
            }

            /**
             * Sets a content type [Predicate]. If a content type is rejected by the predicate
             * then a [HttpDataSource.InvalidContentTypeException] is thrown from
             * [YoutubeHttpDataSource.open].
             *
             * The default is `null`.
             * @param contentTypePredicateToSet The content type [Predicate], or `null` to
             * clear a predicate that was previously set.
             * @return This factory.
             */
            fun setContentTypePredicate(contentTypePredicateToSet: Predicate<String>?): Factory {
                this.contentTypePredicate = contentTypePredicateToSet
                return this
            }

            /**
             * Sets the [TransferListener] that will be used.
             * The default is `null`.
             * See [DataSource.addTransferListener].
             *
             * @param transferListenerToUse The listener that will be used.
             * @return This factory.
             */
            fun setTransferListener(transferListenerToUse: TransferListener?): Factory {
                this.transferListener = transferListenerToUse
                return this
            }

            /**
             * Sets whether we should keep the POST method and body when we have HTTP 302 redirects for
             * a POST request.
             *
             * @param keepPostFor302RedirectsValue Whether we should keep the POST method and body when
             * we have HTTP 302 redirects for a POST request.
             * @return This factory.
             */
            fun setKeepPostFor302Redirects(keepPostFor302RedirectsValue: Boolean): Factory {
                this.keepPostFor302Redirects = keepPostFor302RedirectsValue
                return this
            }

            override fun createDataSource(): YoutubeHttpDataSource {
                val dataSource = YoutubeHttpDataSource(
                    connectTimeoutMs,
                    readTimeoutMs,
                    allowCrossProtocolRedirects,
                    rangeParameterEnabled,
                    rnParameterEnabled,
                    defaultRequestProperties,
                    contentTypePredicate,
                    keepPostFor302Redirects)
                if (transferListener != null) dataSource.addTransferListener(transferListener!!)
                return dataSource
            }
        }

        @UnstableApi companion object {
            private val TAG: String = YoutubeHttpDataSource::class.java.simpleName
            private const val MAX_REDIRECTS = 20 // Same limit as okhttp.
            private const val HTTP_STATUS_TEMPORARY_REDIRECT = 307
            private const val HTTP_STATUS_PERMANENT_REDIRECT = 308
            private const val MAX_BYTES_TO_DRAIN: Long = 2048

            private const val RN_PARAMETER = "&rn="
            private const val YOUTUBE_BASE_URL = "https://www.youtube.com"

            /**
             * On platform API levels 19 and 20, okhttp's implementation of [InputStream.close] can
             * block for a long time if the stream has a lot of data remaining. Call this method before
             * closing the input stream to make a best effort to cause the input stream to encounter an
             * unexpected end of input, working around this issue. On other platform API levels, the method
             * does nothing.
             *
             * @param connection     The connection whose [InputStream] should be terminated.
             * @param bytesRemaining The number of bytes remaining to be read from the input stream if its
             * length is known. [C.LENGTH_UNSET] otherwise.
             */
            private fun maybeTerminateInputStream(connection: HttpURLConnection?, bytesRemaining: Long) {
                if (connection == null || Util.SDK_INT < 19 || Util.SDK_INT > 20) return
                try {
                    val inputStream = connection.inputStream
                    // If the input stream has already ended, do nothing. The socket may be re-used.
                    if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                        if (inputStream.read() == -1) return
                    } else if (bytesRemaining <= MAX_BYTES_TO_DRAIN) {
                        // There isn't much data left. Prefer to allow it to drain, which may allow the
                        // socket to be re-used.
                        return
                    }
                    val className = inputStream.javaClass.name
                    if (("com.android.okhttp.internal.http.HttpTransport\$ChunkedInputStream" == className)
                            || ("com.android.okhttp.internal.http.HttpTransport\$FixedLengthInputStream" == className)) {
                        val superclass = inputStream.javaClass.superclass
                        val unexpectedEndOfInput = Assertions.checkNotNull(superclass).getDeclaredMethod("unexpectedEndOfInput")
                        unexpectedEndOfInput.isAccessible = true
                        unexpectedEndOfInput.invoke(inputStream)
                    }
                } catch (e: Exception) {
                    // If an IOException then the connection didn't ever have an input stream, or it was
                    // closed already. If another type of exception then something went wrong, most likely
                    // the device isn't using okhttp.
                }
            }

            private fun isCompressed(connection: HttpURLConnection): Boolean {
                val contentEncoding = connection.getHeaderField("Content-Encoding")
                return "gzip".equals(contentEncoding, ignoreCase = true)
            }

            /**
             * Builds a `range` parameter for the given position and length.
             *
             * To fetch its contents, YouTube use range requests which append a `range` parameter
             * to videoplayback URLs instead of the `Range` header (even if the server respond
             * correctly when requesting a range of a ressouce with it).
             *
             * The parameter works in the same way as the header.
             *
             * @param position The request position.
             * @param length The request length, or [C.LENGTH_UNSET] if the request is unbounded.
             * @return The corresponding `range` parameter, or `null` if this parameter is
             * unnecessary because the whole resource is being requested.
             */
            private fun buildRangeParameter(position: Long, length: Long): String? {
                if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
                val rangeParameter = StringBuilder()
                rangeParameter.append("&range=")
                rangeParameter.append(position)
                rangeParameter.append("-")
                if (length != C.LENGTH_UNSET.toLong()) rangeParameter.append(position + length - 1)
                return rangeParameter.toString()
            }
        }
    }

    /**
     * This [MediaItemTag] object contains metadata for a resolved stream
     * that is ready for playback. This object guarantees the [StreamInfo]
     * is available and may provide the [Quality] of video stream used in
     * the [MediaItem].
     */
    class StreamInfoTag private constructor(
            private val streamInfo: StreamInfo,
            private val quality: MediaItemTag.Quality?,
            private val audioTrack: MediaItemTag.AudioTrack?,
            private val extras: Any?)
        : MediaItemTag {

        override val errors: List<Exception>
            get() = emptyList()

        override val serviceId: Int
            get() = streamInfo.serviceId

        override val title: String
            get() = streamInfo.name

        override val uploaderName: String
            get() = streamInfo.uploaderName

        override val durationSeconds: Long
            get() = streamInfo.duration

        override val streamUrl: String
            get() = streamInfo.url

        override val thumbnailUrl: String?
            get() = choosePreferredImage(streamInfo.thumbnails)

        override val uploaderUrl: String
            get() = streamInfo.uploaderUrl

        override val streamType: StreamType
            get() = streamInfo.streamType

        override val maybeStreamInfo: Optional<StreamInfo>
            get() = Optional.of(streamInfo)

        override val maybeQuality: Optional<MediaItemTag.Quality>
            get() = Optional.ofNullable(quality)

        override val maybeAudioTrack: Optional<MediaItemTag.AudioTrack>
            get() = Optional.ofNullable(audioTrack)

        override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
            return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
        }

        override fun <T> withExtras(extra: T): StreamInfoTag {
            return StreamInfoTag(streamInfo, quality, audioTrack, extra)
        }

        companion object {
            fun of(streamInfo: StreamInfo, sortedVideoStreams: List<VideoStream>, selectedVideoStreamIndex: Int, audioStreams: List<AudioStream>,
                   selectedAudioStreamIndex: Int): StreamInfoTag {
                val quality = MediaItemTag.Quality.of(sortedVideoStreams, selectedVideoStreamIndex)
                val audioTrack = MediaItemTag.AudioTrack.of(audioStreams, selectedAudioStreamIndex)
                return StreamInfoTag(streamInfo, quality, audioTrack, null)
            }

            fun of(streamInfo: StreamInfo, audioStreams: List<AudioStream>, selectedAudioStreamIndex: Int): StreamInfoTag {
                val audioTrack = MediaItemTag.AudioTrack.of(audioStreams, selectedAudioStreamIndex)
                return StreamInfoTag(streamInfo, null, audioTrack, null)
            }

            fun of(streamInfo: StreamInfo): StreamInfoTag {
                return StreamInfoTag(streamInfo, null, null, null)
            }
        }
    }

    class MediaSourceManager private constructor(
            listener: PlaybackListener,
            playQueue: PlayQueue,
            loadDebounceMillis: Long,
            playbackNearEndGapMillis: Long,
            progressUpdateIntervalMillis: Long) {

        private val TAG = "MediaSourceManager@" + hashCode()

        private val playbackListener: PlaybackListener
        private val playQueue: PlayQueue

        /**
         * Determines the gap time between the playback position and the playback duration which
         * the [.getEdgeIntervalSignal] begins to request loading.
         *
         * @see .progressUpdateIntervalMillis
         */
        private val playbackNearEndGapMillis: Long

        /**
         * Determines the interval which the [.getEdgeIntervalSignal] waits for between
         * each request for loading, once [.playbackNearEndGapMillis] has reached.
         */
        private val progressUpdateIntervalMillis: Long

        private val nearEndIntervalSignal: Observable<Long>

        /**
         * Process only the last load order when receiving a stream of load orders (lessens I/O).
         * The higher it is, the less loading occurs during rapid noncritical timeline changes.
         * Not recommended to go below 100ms.
         *
         * @see .loadDebounced
         */
        private val loadDebounceMillis: Long

        private val debouncedLoader: Disposable
        private val debouncedSignal: PublishSubject<Long>

        private var playQueueReactor: Subscription

        private val loaderReactor: CompositeDisposable
        private val loadingItems: MutableSet<PlayQueueItem?>

        private val isBlocked: AtomicBoolean

        private var playlist: ManagedMediaSourcePlaylist

        private val removeMediaSourceHandler = Handler()

        private val reactor: Subscriber<PlayQueueEvent>
            get() = object : Subscriber<PlayQueueEvent> {
                override fun onSubscribe(d: Subscription) {
                    playQueueReactor.cancel()
                    playQueueReactor = d
                    playQueueReactor.request(1)
                }
                override fun onNext(playQueueMessage: PlayQueueEvent) {
                    onPlayQueueChanged(playQueueMessage)
                }
                override fun onError(e: Throwable) {}
                override fun onComplete() {}
            }

        private val isPlayQueueReady: Boolean
            get() {
                val isWindowLoaded = playQueue.size() - playQueue.index > WINDOW_SIZE
                return playQueue.isComplete || isWindowLoaded
            }

        private val isPlaybackReady: Boolean
            get() {
                if (playlist.size() != playQueue.size()) return false

                val mediaSource = playlist[playQueue.index]
                val playQueueItem = playQueue.item
                if (mediaSource == null || playQueueItem == null) return false

                return mediaSource.isStreamEqual(playQueueItem)
            }

        private val edgeIntervalSignal: Observable<Long>
            get() = Observable.interval(progressUpdateIntervalMillis, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                .filter { playbackListener.isApproachingPlaybackEdge(playbackNearEndGapMillis) }


        constructor(listener: PlaybackListener, playQueue: PlayQueue) : this(listener, playQueue, 400L,
            TimeUnit.MILLISECONDS.convert(30, TimeUnit.SECONDS),
            TimeUnit.MILLISECONDS.convert(2, TimeUnit.SECONDS))

        init {
            requireNotNull(playQueue.broadcastReceiver) { "Play Queue has not been initialized." }
            require(playbackNearEndGapMillis >= progressUpdateIntervalMillis) {
                ("Playback end gap=[$playbackNearEndGapMillis ms] must be longer than update interval=[ $progressUpdateIntervalMillis ms] for them to be useful.")
            }

            this.playbackListener = listener
            this.playQueue = playQueue

            this.playbackNearEndGapMillis = playbackNearEndGapMillis
            this.progressUpdateIntervalMillis = progressUpdateIntervalMillis
            this.nearEndIntervalSignal = this.edgeIntervalSignal

            this.loadDebounceMillis = loadDebounceMillis
            this.debouncedSignal = PublishSubject.create()
            this.debouncedLoader = getDebouncedLoader()

            this.playQueueReactor = EmptySubscription.INSTANCE
            this.loaderReactor = CompositeDisposable()

            this.isBlocked = AtomicBoolean(false)

            this.playlist = ManagedMediaSourcePlaylist()

            this.loadingItems = Collections.synchronizedSet(ArraySet())

            playQueue.broadcastReceiver?.observeOn(AndroidSchedulers.mainThread())?.subscribe(reactor)
        }

        /**
         * Dispose the manager and releases all message buses and loaders.
         */
        fun dispose() {
            Logd(TAG, "close() called.")
            debouncedSignal.onComplete()
            debouncedLoader.dispose()

            playQueueReactor.cancel()
            loaderReactor.dispose()
        }

        private fun onPlayQueueChanged(event: PlayQueueEvent) {
            if (playQueue.isEmpty && playQueue.isComplete) {
                playbackListener.onPlaybackShutdown()
                return
            }

            when (event.type()) {
                PlayQueueEventType.INIT, PlayQueueEventType.ERROR -> {
                    maybeBlock()
                    populateSources()
                }
                PlayQueueEventType.APPEND -> populateSources()
                PlayQueueEventType.SELECT -> maybeRenewCurrentIndex()
                PlayQueueEventType.REMOVE -> {
                    val removeEvent = event as RemoveEvent
                    playlist.remove(removeEvent.removeIndex)
                }
                PlayQueueEventType.MOVE -> {
                    val moveEvent = event as MoveEvent
                    playlist.move(moveEvent.fromIndex, moveEvent.toIndex)
                }
                PlayQueueEventType.REORDER -> {
                    // Need to move to ensure the playing index from play queue matches that of
                    // the source timeline, and then window correction can take care of the rest
                    val reorderEvent = event as ReorderEvent
                    playlist.move(reorderEvent.fromSelectedIndex, reorderEvent.toSelectedIndex)
                }
                PlayQueueEventType.RECOVERY -> {}
                else -> {}
            }
            when (event.type()) {
                PlayQueueEventType.INIT, PlayQueueEventType.REORDER, PlayQueueEventType.ERROR, PlayQueueEventType.SELECT -> loadImmediate() // low frequency, critical events
                PlayQueueEventType.APPEND, PlayQueueEventType.REMOVE, PlayQueueEventType.MOVE, PlayQueueEventType.RECOVERY -> loadDebounced() // high frequency or noncritical events
                else -> loadDebounced()
            }
            when (event.type()) {
                PlayQueueEventType.APPEND, PlayQueueEventType.REMOVE, PlayQueueEventType.MOVE, PlayQueueEventType.REORDER -> playbackListener.onPlayQueueEdited()
                else -> {}
            }
            if (!isPlayQueueReady) {
                maybeBlock()
                playQueue.fetch()
            }
            playQueueReactor.request(1)
        }

        private fun maybeBlock() {
            Logd(TAG, "maybeBlock() called.")
            if (isBlocked.get()) return
            playbackListener.onPlaybackBlock()
            resetSources()
            isBlocked.set(true)
        }

        private fun maybeUnblock(): Boolean {
            Logd(TAG, "maybeUnblock() called.")
            if (isBlocked.get()) {
                isBlocked.set(false)
                playbackListener.onPlaybackUnblock(playlist.parentMediaSource)
                return true
            }
            return false
        }

        private fun maybeSync(wasBlocked: Boolean) {
            Logd(TAG, "maybeSync() called.")
            val currentItem = playQueue.item
            if (isBlocked.get() || currentItem == null) return
            playbackListener.onPlaybackSynchronize(currentItem, wasBlocked)
        }

        @Synchronized
        private fun maybeSynchronizePlayer() {
            if (isPlayQueueReady && isPlaybackReady) {
                val isBlockReleased = maybeUnblock()
                maybeSync(isBlockReleased)
            }
        }

        private fun getDebouncedLoader(): Disposable {
            return debouncedSignal.mergeWith(nearEndIntervalSignal)
                .debounce(loadDebounceMillis, TimeUnit.MILLISECONDS)
                .subscribeOn(Schedulers.single())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { loadImmediate() }
        }

        private fun loadDebounced() {
            debouncedSignal.onNext(System.currentTimeMillis())
        }

        private fun loadImmediate() {
            Logd(TAG, "MediaSource - loadImmediate() called")
            val itemsToLoad = getItemsToLoad(playQueue) ?: return

            // Evict the previous items being loaded to free up memory, before start loading new ones
            maybeClearLoaders()

            maybeLoadItem(itemsToLoad.center)
            for (item in itemsToLoad.neighbors) {
                maybeLoadItem(item)
            }
        }

        private fun maybeLoadItem(item: PlayQueueItem) {
            Logd(TAG, "maybeLoadItem() called.")
            if (playQueue.indexOf(item) >= playlist.size()) return

            if (!loadingItems.contains(item) && isCorrectionNeeded(item)) {
                Logd(TAG, "MediaSource - Loading=[${item.title}] with url=[${item.url}]")
                loadingItems.add(item)
                val loader = getLoadedMediaSource(item)
                    .observeOn(AndroidSchedulers.mainThread()) /* No exception handling since getLoadedMediaSource guarantees nonnull return */
                    .subscribe { mediaSource: ManagedMediaSource -> onMediaSourceReceived(item, mediaSource) }
                loaderReactor.add(loader)
            }
        }

        private fun getLoadedMediaSource(stream: PlayQueueItem): Single<ManagedMediaSource> {
            return stream.stream
                .map { streamInfo: StreamInfo ->
                    Optional.ofNullable(playbackListener.sourceOf(stream, streamInfo))
                        .flatMap<ManagedMediaSource> { source: MediaSource ->
                            MediaItemTag.from(source.mediaItem)
                                .map { tag: MediaItemTag? ->
                                    val serviceId = streamInfo.serviceId
                                    val expiration = (System.currentTimeMillis() + getCacheExpirationMillis(serviceId))
                                    LoadedMediaSource(source, tag!!, stream, expiration)
                                }
                        }
                        .orElseGet {
                            val message = ("Unable to resolve source from stream info. URL: ${stream.url}, audio count: ${streamInfo.audioStreams.size}, video count: ${streamInfo.videoOnlyStreams.size}, ${streamInfo.videoStreams.size}")
                            FailedMediaSource.of(stream, FailedMediaSource.MediaSourceResolutionException(message))
                        }
                }
                .onErrorReturn { throwable: Throwable? ->
                    if (throwable is ExtractionException) return@onErrorReturn FailedMediaSource.of(stream, FailedMediaSource.StreamInfoLoadException(throwable))

                    // Non-source related error expected here (e.g. network),
                    // should allow retry shortly after the error.
                    val allowRetryIn = TimeUnit.MILLISECONDS.convert(3, TimeUnit.SECONDS)
                    FailedMediaSource.of(stream, Exception(throwable), allowRetryIn)
                }
        }

        private fun onMediaSourceReceived(item: PlayQueueItem, mediaSource: ManagedMediaSource) {
            Logd(TAG, "MediaSource - Loaded=[${item.title}] with url=[${item.url}]")
            loadingItems.remove(item)

            val itemIndex = playQueue.indexOf(item)
            // Only update the playlist timeline for items at the current index or after.
            if (isCorrectionNeeded(item)) {
                Logd(TAG, "MediaSource - Updating index=[$itemIndex] with title=[${item.title}] at url=[${item.url}]")
                playlist.update(itemIndex, mediaSource, removeMediaSourceHandler) { this.maybeSynchronizePlayer() }
            }
        }

        /**
         * Checks if the corresponding MediaSource in
         * for a given [PlayQueueItem] needs replacement, either due to gapless playback
         * readiness or playlist desynchronization.
         *
         * If the given [PlayQueueItem] is currently being played and is already loaded,
         * then correction is not only needed if the playlist is desynchronized. Otherwise, the
         * check depends on the status (e.g. expiration or placeholder) of the
         * [ManagedMediaSource].
         *
         * @param item [PlayQueueItem] to check
         * @return whether a correction is needed
         */
        private fun isCorrectionNeeded(item: PlayQueueItem): Boolean {
            val index = playQueue.indexOf(item)
            val mediaSource = playlist[index]
            return mediaSource != null && mediaSource.shouldBeReplacedWith(item, index != playQueue.index)
        }

        /**
         * Checks if the current playing index contains an expired [ManagedMediaSource].
         * If so, the expired source is replaced by a dummy [ManagedMediaSource] and
         * [.loadImmediate] is called to reload the current item.
         * <br></br><br></br>
         * If not, then the media source at the current index is ready for playback, and
         * [.maybeSynchronizePlayer] is called.
         * <br></br><br></br>
         * Under both cases, [.maybeSync] will be called to ensure the listener
         * is up-to-date.
         */
        private fun maybeRenewCurrentIndex() {
            val currentIndex = playQueue.index
            val currentItem = playQueue.item
            val currentSource = playlist[currentIndex]
            if (currentItem == null || currentSource == null) return

            if (!currentSource.shouldBeReplacedWith(currentItem, true)) {
                maybeSynchronizePlayer()
                return
            }
            Logd(TAG, "MediaSource - Reloading currently playing, index=[$currentIndex], item=[${currentItem.title}]")
            playlist.invalidate(currentIndex, removeMediaSourceHandler) { this.loadImmediate() }
        }

        private fun maybeClearLoaders() {
            Logd(TAG, "MediaSource - maybeClearLoaders() called.")
            if (!loadingItems.contains(playQueue.item) && loaderReactor.size() > MAXIMUM_LOADER_SIZE) {
                loaderReactor.clear()
                loadingItems.clear()
            }
        }

        private fun resetSources() {
            Logd(TAG, "resetSources() called.")
            playlist = ManagedMediaSourcePlaylist()
        }

        private fun populateSources() {
            Logd(TAG, "populateSources() called.")
            while (playlist.size() < playQueue.size()) {
                playlist.expand()
            }
        }

        private class ItemsToLoad(val center: PlayQueueItem, val neighbors: Collection<PlayQueueItem>)

        interface ManagedMediaSource : MediaSource {
            /**
             * Determines whether or not this [ManagedMediaSource] can be replaced.
             *
             * @param newIdentity     a stream the [ManagedMediaSource] should encapsulate over, if
             * it is different from the existing stream in the
             * [ManagedMediaSource], then it should be replaced.
             * @param isInterruptable specifies if this [ManagedMediaSource] potentially
             * being played.
             * @return whether this could be replaces
             */
            fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean

            /**
             * Determines if the [PlayQueueItem] is the one the
             * [ManagedMediaSource] encapsulates over.
             *
             * @param stream play queue item to check
             * @return whether this source is for the specified stream
             */
            fun isStreamEqual(stream: PlayQueueItem): Boolean
        }

        @UnstableApi class FailedMediaSource(
                val stream: PlayQueueItem,
                val error: Exception,
                private val retryTimestamp: Long)
            : BaseMediaSource(), ManagedMediaSource {

            private val TAG = "FailedMediaSource@" + Integer.toHexString(hashCode())
            private val mediaItem = ExceptionTag.of(stream, listOf(error)).withExtras(this).asMediaItem()

            private fun canRetry(): Boolean {
                return System.currentTimeMillis() >= retryTimestamp
            }

            override fun getMediaItem(): MediaItem {
                return mediaItem
            }

            /**
             * Prepares the source with [Timeline] info on the silence playback when the error
             * is classed as [FailedMediaSourceException], for example, when the error is
             * [ExtractionException][ac.mdiq.vista.extractor.exceptions.ExtractionException].
             * These types of error are swallowed by [FailedMediaSource], and the underlying
             * exception is carried to the [MediaItem] metadata during playback.
             * <br></br><br></br>
             * If the exception is not known, e.g. [java.net.UnknownHostException] or some
             * other network issue, then no source info is refreshed and
             * [.maybeThrowSourceInfoRefreshError] be will triggered.
             * <br></br><br></br>
             * Note that this method is called only once until [.releaseSourceInternal] is called,
             * so if no action is done in here, playback will stall unless
             * [.maybeThrowSourceInfoRefreshError] is called.
             *
             * @param mediaTransferListener No data transfer listener needed, ignored here.
             */
            override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
                Log.e(TAG, "Loading failed source: ", error)
                if (error is FailedMediaSourceException) refreshSourceInfo(makeSilentMediaTimeline(SILENCE_DURATION_US, mediaItem))
            }

            /**
             * If the error is not known, e.g. network issue, then the exception is not swallowed here in
             * [FailedMediaSource]. The exception is then propagated to the player, which
             * [Player][ac.mdiq.vista.player.PlayerManager] can react to inside
             * [androidx.media3.common.Player.Listener.onPlayerError].
             *
             * @throws IOException An error which will always result in
             * [androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED].
             */
            @Throws(IOException::class)
            override fun maybeThrowSourceInfoRefreshError() {
                if (error !is FailedMediaSourceException) throw IOException(error)
            }

            /**
             * This method is only called if [.prepareSourceInternal]
             * refreshes the source info with no exception. All parameters are ignored as this
             * returns a static and reused piece of silent audio.
             *
             * @param id                The identifier of the period.
             * @param allocator         An [Allocator] from which to obtain media buffer allocations.
             * @param startPositionUs   The expected start position, in microseconds.
             * @return The common [MediaPeriod] holding the silence.
             */
            override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
                return SILENT_MEDIA
            }

            override fun releasePeriod(mediaPeriod: MediaPeriod) {
                /* Do Nothing (we want to keep re-using the Silent MediaPeriod) */
            }

            override fun releaseSourceInternal() {
                /* Do Nothing, no clean-up for processing/extra thread is needed by this MediaSource */
            }

            override fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean {
                return newIdentity != stream || canRetry()
            }

            override fun isStreamEqual(stream: PlayQueueItem): Boolean {
                return this.stream == stream
            }

            open class FailedMediaSourceException : Exception {
                internal constructor(message: String?) : super(message)
                internal constructor(cause: Throwable?) : super(cause)
            }

            class MediaSourceResolutionException(message: String?) : FailedMediaSourceException(message)

            class StreamInfoLoadException(cause: Throwable?) : FailedMediaSourceException(cause)

            /**
             * This [MediaItemTag] object is designed to contain metadata for a stream
             * that has failed to load. It supplies metadata from an underlying
             * [PlayQueueItem], which is used by the internal players to resolve actual
             * playback info.
             *
             * This [MediaItemTag] does not contain any [StreamInfo] that can be
             * used to start playback and can be detected by checking [ExceptionTag.getErrors]
             * when in generic form.
             */
            class ExceptionTag private constructor(private val item: PlayQueueItem,
                                                   override val errors: List<Exception>,
                                                   private val extras: Any?) : MediaItemTag {
                override val serviceId: Int
                    get() = item.serviceId

                override val title: String
                    get() = item.title

                override val uploaderName: String
                    get() = item.uploader

                override val durationSeconds: Long
                    get() = item.duration

                override val streamUrl: String
                    get() = item.url

                override val thumbnailUrl: String?
                    get() = choosePreferredImage(item.thumbnails)

                override val uploaderUrl: String
                    get() = item.uploaderUrl

                override val streamType: StreamType
                    get() = item.streamType

                override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
                    return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
                }

                override fun <T> withExtras(extra: T): MediaItemTag {
                    return ExceptionTag(item, errors, extra)
                }

                companion object {
                    fun of(playQueueItem: PlayQueueItem, errors: List<Exception>): ExceptionTag {
                        return ExceptionTag(playQueueItem, errors, null)
                    }
                }
            }

            companion object {
                /**
                 * Play 2 seconds of silenced audio when a stream fails to resolve due to a known issue,
                 * such as [ac.mdiq.vista.extractor.exceptions.ExtractionException].
                 *
                 * This silence duration allows user to react and have time to jump to a previous stream,
                 * while still provide a smooth playback experience. A duration lower than 1 second is
                 * not recommended, it may cause ExoPlayer to buffer for a while.
                 */
                val SILENCE_DURATION_US: Long = TimeUnit.SECONDS.toMicros(2)
                val SILENT_MEDIA: MediaPeriod = makeSilentMediaPeriod(SILENCE_DURATION_US)

                fun of(playQueueItem: PlayQueueItem, error: FailedMediaSourceException): FailedMediaSource {
                    return FailedMediaSource(playQueueItem, error, Long.MAX_VALUE)
                }

                fun of(playQueueItem: PlayQueueItem, error: Exception, retryWaitMillis: Long): FailedMediaSource {
                    return FailedMediaSource(playQueueItem, error, System.currentTimeMillis() + retryWaitMillis)
                }

                private fun makeSilentMediaTimeline(durationUs: Long, mediaItem: MediaItem): Timeline {
                    return SinglePeriodTimeline(durationUs, true, false, false, null, mediaItem)
                }

                private fun makeSilentMediaPeriod(durationUs: Long): MediaPeriod {
                    val mediaSource = SilenceMediaSource.Factory()
                        .setDurationUs(durationUs)
                        .createMediaSource()
                    val mediaPeriodId = MediaSource.MediaPeriodId(0)
                    val allocator = DefaultAllocator(false, 0)
                    return mediaSource.createPeriod(mediaPeriodId, allocator, 0)
//            return SilenceMediaSource.Factory()
//                .setDurationUs(durationUs)
//                .createMediaSource()
//                .createPeriod(null, null, 0)
                }
            }
        }

        @OptIn(UnstableApi::class)
        class LoadedMediaSource(
                source: MediaSource,
                tag: MediaItemTag,
                val stream: PlayQueueItem,
                private val expireTimestamp: Long)
            : WrappingMediaSource(source), ManagedMediaSource {

            private val mediaItem = tag.withExtras(this)!!.asMediaItem()

            private val isExpired: Boolean
                get() = System.currentTimeMillis() >= expireTimestamp

            override fun getMediaItem(): MediaItem {
                return mediaItem
            }

            override fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean {
                return newIdentity != stream || (isInterruptable && isExpired)
            }

            override fun isStreamEqual(stream: PlayQueueItem): Boolean {
                return this.stream == stream
            }
        }

        @UnstableApi class ManagedMediaSourcePlaylist {
            /*isPlaylistAtomic=*/
            val parentMediaSource: ConcatenatingMediaSource = ConcatenatingMediaSource( false, ShuffleOrder.UnshuffledShuffleOrder(0))

            fun size(): Int {
                return parentMediaSource.size
            }

            /**
             * Returns the [ManagedMediaSource] at the given index of the playlist.
             * If the index is invalid, then null is returned.
             *
             * @param index index of [ManagedMediaSource] to get from the playlist
             * @return the [ManagedMediaSource] at the given index of the playlist
             */
            operator fun get(index: Int): ManagedMediaSource? {
                if (index < 0 || index >= size()) return null

                return MediaItemTag
                    .from(parentMediaSource.getMediaSource(index).mediaItem)
                    .flatMap { tag -> tag.getMaybeExtras(ManagedMediaSource::class.java) }
                    .orElse(null)
            }

            /**
             * Expands the [ConcatenatingMediaSource] by appending it with a
             * [PlaceholderMediaSource].
             * @see .append
             */
            @Synchronized
            fun expand() {
                append(PlaceholderMediaSource.COPY)
            }

            /**
             * Appends a [ManagedMediaSource] to the end of [ConcatenatingMediaSource].
             *
             * @see ConcatenatingMediaSource.addMediaSource
             *
             * @param source [ManagedMediaSource] to append
             */
            @Synchronized
            fun append(source: ManagedMediaSource) {
                parentMediaSource.addMediaSource(source)
            }

            /**
             * Removes a [ManagedMediaSource] from [ConcatenatingMediaSource]
             * at the given index. If this index is out of bound, then the removal is ignored.
             *
             * @see ConcatenatingMediaSource.removeMediaSource
             * @param index of [ManagedMediaSource] to be removed
             */
            @Synchronized
            fun remove(index: Int) {
                if (index < 0 || index > parentMediaSource.size) return
                parentMediaSource.removeMediaSource(index)
            }

            /**
             * Moves a [ManagedMediaSource] in [ConcatenatingMediaSource]
             * from the given source index to the target index. If either index is out of bound,
             * then the call is ignored.
             *
             * @see ConcatenatingMediaSource.moveMediaSource
             * @param source original index of [ManagedMediaSource]
             * @param target new index of [ManagedMediaSource]
             */
            @Synchronized
            fun move(source: Int, target: Int) {
                if (source < 0 || target < 0) return
                if (source >= parentMediaSource.size || target >= parentMediaSource.size) return

                parentMediaSource.moveMediaSource(source, target)
            }

            /**
             * Invalidates the [ManagedMediaSource] at the given index by replacing it
             * with a [PlaceholderMediaSource].
             *
             * @see .update
             * @param index            index of [ManagedMediaSource] to invalidate
             * @param handler          the [Handler] to run `finalizingAction`
             * @param finalizingAction a [Runnable] which is executed immediately
             * after the media source has been removed from the playlist
             */
            @Synchronized
            fun invalidate(index: Int, handler: Handler?, finalizingAction: Runnable?) {
                if (get(index) === PlaceholderMediaSource.COPY) return
                update(index, PlaceholderMediaSource.COPY, handler, finalizingAction)
            }

            /**
             * Updates the [ManagedMediaSource] in [ConcatenatingMediaSource]
             * at the given index with a given [ManagedMediaSource].
             *
             * @see .update
             * @param index  index of [ManagedMediaSource] to update
             * @param source new [ManagedMediaSource] to use
             */
            @Synchronized
            fun update(index: Int, source: ManagedMediaSource) {
                update(index, source, null,  /*doNothing=*/null)
            }

            /**
             * Updates the [ManagedMediaSource] in [ConcatenatingMediaSource]
             * at the given index with a given [ManagedMediaSource]. If the index is out of bound,
             * then the replacement is ignored.
             *
             * @see ConcatenatingMediaSource.addMediaSource
             *
             * @see ConcatenatingMediaSource.removeMediaSource
             * @param index            index of [ManagedMediaSource] to update
             * @param source           new [ManagedMediaSource] to use
             * @param handler          the [Handler] to run `finalizingAction`
             * @param finalizingAction a [Runnable] which is executed immediately
             * after the media source has been removed from the playlist
             */
            @Synchronized
            fun update(index: Int, source: ManagedMediaSource, handler: Handler?, finalizingAction: Runnable?) {
                if (index < 0 || index >= parentMediaSource.size) return

                // Add and remove are sequential on the same thread, therefore here, the exoplayer
                // message queue must receive and process add before remove, effectively treating them
                // as atomic.

                // Since the finalizing action occurs strictly after the timeline has completed
                // all its changes on the playback thread, thus, it is possible, in the meantime,
                // other calls that modifies the playlist media source occur in between. This makes
                // it unsafe to call remove as the finalizing action of add.
                parentMediaSource.addMediaSource(index + 1, source)

                // Because of the above race condition, it is thus only safe to synchronize the player
                // in the finalizing action AFTER the removal is complete and the timeline has changed.
                parentMediaSource.removeMediaSource(index, handler!!, finalizingAction!!)
            }

            @UnstableApi internal class PlaceholderMediaSource private constructor() : CompositeMediaSource<Void?>(), ManagedMediaSource {
                override fun getMediaItem(): MediaItem {
                    return MEDIA_ITEM
                }

                override fun onChildSourceInfoRefreshed(id: Void?, mediaSource: MediaSource, timeline: Timeline) {
                    /* Do nothing, no timeline updates or error will stall playback */
                }

                override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod {
//                    TODO: dubious cast?
//                    return PlaceholderMediaSource() as MediaPeriod
                    val mediaSource = SilenceMediaSource.Factory()
//                        .setDurationUs(durationUs)
                        .createMediaSource()
                    return mediaSource.createPeriod(id, allocator, startPositionUs)
                }

                override fun releasePeriod(mediaPeriod: MediaPeriod) {}

                override fun shouldBeReplacedWith(newIdentity: PlayQueueItem, isInterruptable: Boolean): Boolean {
                    return true
                }

                override fun isStreamEqual(stream: PlayQueueItem): Boolean {
                    return false
                }

                /**
                 * This is a Placeholding [MediaItemTag], designed as a dummy metadata object for
                 * any stream that has not been resolved.
                 *
                 * This object cannot be instantiated and does not hold real metadata of any form.
                 */
                class PlaceholderTag private constructor(private val extras: Any?) : MediaItemTag {
                    override val errors: List<Exception>
                        get() = emptyList()

                    override val serviceId: Int
                        get() = NO_SERVICE_ID

                    override val title: String
                        get() = UNKNOWN_VALUE_INTERNAL

                    override val uploaderName: String
                        get() = UNKNOWN_VALUE_INTERNAL

                    override val streamUrl: String
                        get() = UNKNOWN_VALUE_INTERNAL

                    override val thumbnailUrl: String
                        get() = UNKNOWN_VALUE_INTERNAL

                    override val durationSeconds: Long
                        get() = 0

                    override val streamType: StreamType
                        get() = StreamType.NONE

                    override val uploaderUrl: String
                        get() = UNKNOWN_VALUE_INTERNAL

                    override fun <T> getMaybeExtras(type: Class<T>): Optional<T>? {
                        return Optional.ofNullable(extras).map { obj: Any? -> type.cast(obj) }
                    }

                    override fun <T> withExtras(extra: T): MediaItemTag {
                        return PlaceholderTag(extra)
                    }

                    companion object {
                        @JvmField
                        val EMPTY: PlaceholderTag = PlaceholderTag(null)
                        private const val UNKNOWN_VALUE_INTERNAL: String = "Placeholder"
                    }
                }

                companion object {
                    val COPY: PlaceholderMediaSource = PlaceholderMediaSource()
                    private val MEDIA_ITEM = PlaceholderTag.EMPTY.withExtras(COPY).asMediaItem()
                }
            }
        }

        companion object {
            /**
             * Determines how many streams before and after the current stream should be loaded.
             * The default value (1) ensures seamless playback under typical network settings.
             *
             * The streams after the current will be loaded into the playlist timeline while the
             * streams before will only be cached for future usage.
             *
             * @see .onMediaSourceReceived
             */
            private const val WINDOW_SIZE = 1

            /**
             * Determines the maximum number of disposables allowed in the [.loaderReactor].
             * Once exceeded, new calls to [.loadImmediate] will evict all disposables in the
             * [.loaderReactor] in order to load a new set of items.
             *
             * @see .loadImmediate
             * @see .maybeLoadItem
             */
            private const val MAXIMUM_LOADER_SIZE = WINDOW_SIZE * 2 + 1

            private fun getItemsToLoad(playQueue: PlayQueue): ItemsToLoad? {
                // The current item has higher priority
                val currentIndex = playQueue.index
                val currentItem = playQueue.getItem(currentIndex) ?: return null

                // The rest are just for seamless playback
                // Although timeline is not updated prior to the current index, these sources are still
                // loaded into the cache for faster retrieval at a potentially later time.
                val leftBound = max(0.0, (currentIndex - WINDOW_SIZE).toDouble()).toInt()
                val rightLimit = currentIndex + WINDOW_SIZE + 1
                val rightBound = min(playQueue.size().toDouble(), rightLimit.toDouble()).toInt()
                val neighbors: MutableSet<PlayQueueItem> = ArraySet(playQueue.streams.subList(leftBound, rightBound))

                // Do a round robin
                val excess = rightLimit - playQueue.size()
                if (excess >= 0) neighbors.addAll(playQueue.streams.subList(0, min(playQueue.size().toDouble(), excess.toDouble()).toInt()))

                neighbors.remove(currentItem)

                return ItemsToLoad(currentItem, neighbors)
            }
        }
    }

    companion object {
        const val DEBUG: Boolean = MainActivity.DEBUG
        val TAG: String = PlayerManager::class.java.simpleName

        const val STATE_PREFLIGHT: Int = -1
        const val STATE_BLOCKED: Int = 123
        const val STATE_PLAYING: Int = 124
        const val STATE_BUFFERING: Int = 125
        const val STATE_PAUSED: Int = 126
        const val STATE_PAUSED_SEEK: Int = 127
        const val STATE_COMPLETED: Int = 128

        const val REPEAT_MODE: String = "repeat_mode"
        const val PLAYBACK_QUALITY: String = "playback_quality"
        const val PLAY_QUEUE_KEY: String = "play_queue_key"
        const val ENQUEUE: String = "enqueue"
        const val ENQUEUE_NEXT: String = "enqueue_next"
        const val RESUME_PLAYBACK: String = "resume_playback"
        const val PLAY_WHEN_READY: String = "play_when_ready"
        const val PLAYER_TYPE: String = "player_type"
        const val IS_MUTED: String = "is_muted"

        const val PLAY_PREV_ACTIVATION_LIMIT_MILLIS: Int = 5000 // 5 seconds
        const val PROGRESS_LOOP_INTERVAL_MILLIS: Int = 1000 // 1 second

        const val RENDERER_UNAVAILABLE: Int = -1
        private const val PICASSO_PLAYER_THUMBNAIL_TAG = "PICASSO_PLAYER_THUMBNAIL_TAG"
    }
}
