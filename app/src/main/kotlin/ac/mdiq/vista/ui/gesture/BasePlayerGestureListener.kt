package ac.mdiq.vista.ui.gesture

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.os.postDelayed
import ac.mdiq.vista.databinding.PlayerBinding
import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.ui.player.VideoPlayerUi
import ac.mdiq.vista.util.Logd

/**
 * Base gesture handling for [PlayerManager]
 *
 * This class contains the logic for the player gestures like View preparations
 * and provides some abstract methods to make it easier separating the logic from the UI.
 */
abstract class BasePlayerGestureListener(private val playerUi: VideoPlayerUi) :
    GestureDetector.SimpleOnGestureListener(), View.OnTouchListener {

    protected val playerManager: PlayerManager = playerUi.playerManager
    protected val binding: PlayerBinding = playerUi.binding

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        playerUi.gestureDetector?.onTouchEvent(event)
        return false
    }

    private fun onDoubleTap(event: MotionEvent, portion: DisplayPortion) {
        Logd(TAG, "onDoubleTap called with playerType = [${playerManager.playerType}], portion = [$portion]")
        if (playerUi.isSomePopupMenuVisible) playerUi.hideControls(0, 0)

        if (portion === DisplayPortion.LEFT || portion === DisplayPortion.RIGHT) startMultiDoubleTap(event)
        else if (portion === DisplayPortion.MIDDLE) playerManager.playPause()
    }

    protected fun onSingleTap() {
        if (playerUi.isControlsVisible) {
            playerUi.hideControls(150, 0)
            return
        }
        // -- Controls are not visible --

        // When player is completed show controls and don't hide them later
        if (playerManager.currentState == PlayerManager.STATE_COMPLETED) playerUi.showControls(0)
        else playerUi.showControlsThenHide()
    }

    open fun onScrollEnd(event: MotionEvent) {
        Logd(TAG, "onScrollEnd called with playerType = [${playerManager.playerType}]")
        if (playerUi.isControlsVisible && playerManager.currentState == PlayerManager.STATE_PLAYING)
            playerUi.hideControls(VideoPlayerUi.DEFAULT_CONTROLS_DURATION, VideoPlayerUi.DEFAULT_CONTROLS_HIDE_TIME)
    }

    override fun onDown(e: MotionEvent): Boolean {
        Logd(TAG, "onDown called with e = [$e]")
        if (isDoubleTapping && isDoubleTapEnabled) {
            doubleTapControls?.onDoubleTapProgressDown(getDisplayPortion(e))
            return true
        }
        if (onDownNotDoubleTapping(e)) return super.onDown(e)
        return true
    }

    /**
     * @return true if `super.onDown(e)` should be called, false otherwise
     */
    open fun onDownNotDoubleTapping(e: MotionEvent): Boolean {
        return false // do not call super.onDown(e) by default, overridden for popup player
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        Logd(TAG, "onDoubleTap called with e = [$e]")
        onDoubleTap(e, getDisplayPortion(e))
        return true
    }

    private var doubleTapControls: DoubleTapListener? = null

    private val isDoubleTapEnabled: Boolean
        get() = doubleTapDelay > 0

    var isDoubleTapping = false
        private set

    fun doubleTapControls(listener: DoubleTapListener) =
        apply {
            doubleTapControls = listener
        }

    private var doubleTapDelay = DOUBLE_TAP_DELAY
    private val doubleTapHandler: Handler = Handler(Looper.getMainLooper())

    private fun startMultiDoubleTap(e: MotionEvent) {
        if (!isDoubleTapping) {
            Logd(TAG, "startMultiDoubleTap called with e = [$e]")
            keepInDoubleTapMode()
            doubleTapControls?.onDoubleTapStarted(getDisplayPortion(e))
        }
    }

    fun keepInDoubleTapMode() {
        Logd(TAG, "keepInDoubleTapMode called")
        isDoubleTapping = true
        doubleTapHandler.removeCallbacksAndMessages(DOUBLE_TAP)
        doubleTapHandler.postDelayed(DOUBLE_TAP_DELAY, DOUBLE_TAP) {
            Logd(TAG, "doubleTapRunnable called")
            isDoubleTapping = false
            doubleTapControls?.onDoubleTapFinished()
        }
    }

    fun endMultiDoubleTap() {
        Logd(TAG, "endMultiDoubleTap called")
        isDoubleTapping = false
        doubleTapHandler.removeCallbacksAndMessages(DOUBLE_TAP)
        doubleTapControls?.onDoubleTapFinished()
    }

    abstract fun getDisplayPortion(e: MotionEvent): DisplayPortion

    // Currently needed for scrolling since there is no action more the middle portion
    abstract fun getDisplayHalfPortion(e: MotionEvent): DisplayPortion

    companion object {
        private const val TAG = "BasePlayerGestListener"

        private const val DOUBLE_TAP = "doubleTap"
        private const val DOUBLE_TAP_DELAY = 550L
    }
}
