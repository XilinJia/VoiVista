package ac.mdiq.vista.ui.holder

import ac.mdiq.vista.App.Companion.getApp
import ac.mdiq.vista.extractor.stream.StreamInfo
import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.player.PlayerService
import ac.mdiq.vista.player.PlayerService.LocalBinder
import ac.mdiq.vista.player.PlayerType
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.ui.player.PlayerServiceEventListener
import ac.mdiq.vista.ui.player.PlayerServiceExtendedEventListener
import ac.mdiq.vista.util.Logd
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class PlayerHolder private constructor() {
    private var listener: PlayerServiceExtendedEventListener? = null

    private val serviceConnection = PlayerServiceConnection()
    var isBound: Boolean = false
        private set
    private var playerService: PlayerService? = null
    private var playerManager: PlayerManager? = null

    /**
     * Returns the current [PlayerType] of the [PlayerService] service,
     * otherwise `null` if no service is running.
     * @return Current PlayerType
     */
    val type: PlayerType?
        get() {
            if (playerManager == null) return null
            return playerManager!!.playerType
        }

    val isPlaying: Boolean
        get() {
            if (playerManager == null) return false
            return playerManager!!.isPlaying
        }

    val isPlayerOpen: Boolean
        get() = playerManager != null

    /**
     * Use this method to only allow the user to manipulate the play queue (e.g. by enqueueing via
     * the stream long press menu) when there actually is a play queue to manipulate.
     * @return true only if the player is open and its play queue is ready (i.e. it is not null)
     */
    val isPlayQueueReady: Boolean
        get() = playerManager != null && playerManager!!.playQueue != null

    // player play queue might be null e.g. while player is starting
    val queueSize: Int
        get() {
            if (playerManager?.playQueue == null) return 0
            return playerManager!!.playQueue!!.size()
        }

    val queuePosition: Int
        get() {
            if (playerManager?.playQueue == null) return 0
            return playerManager!!.playQueue!!.index
        }

    private val commonContext: Context
        // helper to handle context in common place as using the same
        get() = getApp()

    private val internalListener: PlayerServiceEventListener = object : PlayerServiceEventListener {
        override fun onViewCreated() {
            listener?.onViewCreated()
        }
        override fun onFullscreenStateChanged(fullscreen: Boolean) {
            listener?.onFullscreenStateChanged(fullscreen)
        }
        override fun onScreenRotationButtonClicked() {
            listener?.onScreenRotationButtonClicked()
        }
        override fun onMoreOptionsLongClicked() {
            listener?.onMoreOptionsLongClicked()
        }
        override fun onPlayerError(error: PlaybackException?, isCatchableException: Boolean) {
            listener?.onPlayerError(error, isCatchableException)
        }
        override fun hideSystemUiIfNeeded() {
            listener?.hideSystemUiIfNeeded()
        }
        override fun onQueueUpdate(queue: PlayQueue?) {
            listener?.onQueueUpdate(queue)
        }
        override fun onPlaybackUpdate(state: Int, repeatMode: Int, shuffled: Boolean, parameters: PlaybackParameters?) {
            listener?.onPlaybackUpdate(state, repeatMode, shuffled, parameters)
        }
        override fun onProgressUpdate(currentProgress: Int, duration: Int, bufferPercent: Int) {
            listener?.onProgressUpdate(currentProgress, duration, bufferPercent)
        }
        override fun onMetadataUpdate(info: StreamInfo?, queue: PlayQueue?) {
            listener?.onMetadataUpdate(info, queue)
        }
        override fun onServiceStopped() {
            listener?.onServiceStopped()
            unbind(commonContext)
        }
    }

    fun setListener(newListener: PlayerServiceExtendedEventListener?) {
        listener = newListener
        if (listener == null) return
        // Force reload data from service
        if (playerManager != null) {
            listener!!.onServiceConnected(playerManager, playerService, false)
            startPlayerListener()
        }
    }

    fun startService(playAfterConnect: Boolean, newListener: PlayerServiceExtendedEventListener?) {
        val context = commonContext
        setListener(newListener)
        if (isBound) return

        // startService() can be called concurrently and it will give a random crashes
        // and NullPointerExceptions inside the service because the service will be
        // bound twice. Prevent it with unbinding first
        unbind(context)
        val intent = Intent(context, PlayerService::class.java)
//        intent.putExtra(PLAYER_TYPE, "mediaPlayback")
        ContextCompat.startForegroundService(context, intent)
        serviceConnection.doPlayAfterConnect(playAfterConnect)
        bind(context)
    }

    fun stopService() {
        val context = commonContext
        unbind(context)
        context.stopService(Intent(context, PlayerService::class.java))
    }

    private fun bind(context: Context) {
        Logd(TAG, "bind() called")
        val serviceIntent = Intent(context, PlayerService::class.java)
        isBound = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!isBound) context.unbindService(serviceConnection)
    }

    private fun unbind(context: Context) {
        Logd(TAG, "unbind() called")
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
            stopPlayerListener()
            playerService = null
            playerManager = null
            listener?.onServiceDisconnected()
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlayerListener() {
        playerManager?.setFragmentListener(internalListener)
    }

    @OptIn(UnstableApi::class)
    private fun stopPlayerListener() {
        playerManager?.removeFragmentListener(internalListener)
    }

    internal inner class PlayerServiceConnection : ServiceConnection {
        private var playAfterConnect = false

        fun doPlayAfterConnect(playAfterConnection: Boolean) {
            this.playAfterConnect = playAfterConnection
        }
        override fun onServiceDisconnected(compName: ComponentName) {
            Logd(TAG, "Player service is disconnected")
            val context: Context = commonContext
            unbind(context)
        }
        override fun onServiceConnected(compName: ComponentName, service: IBinder) {
            Logd(TAG, "Player service is connected")
            val localBinder = service as LocalBinder
            playerService = localBinder.service
            playerManager = localBinder.getPlayer()
            listener?.onServiceConnected(playerManager, playerService, playAfterConnect)
            startPlayerListener()
        }
    }

    companion object {
        @get:Synchronized
        var instance: PlayerHolder? = null
            get() {
                if (field == null) field = PlayerHolder()
                return field
            }
            private set

//        private const val DEBUG = MainActivity.DEBUG
        private val TAG: String = PlayerHolder::class.java.simpleName
    }
}
