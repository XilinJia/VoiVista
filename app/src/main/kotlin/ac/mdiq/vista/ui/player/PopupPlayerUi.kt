package ac.mdiq.vista.ui.player

import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.PlayerBinding
import ac.mdiq.vista.databinding.PlayerPopupCloseOverlayBinding
import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.player.helper.PlayerHelper
import ac.mdiq.vista.ui.gesture.BasePlayerGestureListener
import ac.mdiq.vista.ui.gesture.DisplayPortion
import ac.mdiq.vista.ui.util.ktx.AnimationType
import ac.mdiq.vista.ui.util.ktx.animate
import ac.mdiq.vista.util.Logd
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.AnticipateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils
import androidx.core.view.isVisible
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import kotlin.math.*

@UnstableApi
class PopupPlayerUi(playerManager: PlayerManager, playerBinding: PlayerBinding) : VideoPlayerUi(playerManager, playerBinding) {
    var closeOverlayBinding: PlayerPopupCloseOverlayBinding? = null
        private set

    var isPopupClosing: Boolean = false
        private set

    //endregion
    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set

    // null if player is not popup
    var popupLayoutParams: WindowManager.LayoutParams? = null
        private set
    val windowManager: WindowManager? = ContextCompat.getSystemService(context, WindowManager::class.java)

    override fun setupAfterIntent() {
        super.setupAfterIntent()
        initPopup()
        initPopupCloseOverlay()
    }

    override fun buildGestureListener(): BasePlayerGestureListener {
        return PopupPlayerGestureListener(this)
    }

    @SuppressLint("RtlHardcoded")
    private fun initPopup() {
        Logd(TAG, "initPopup() called")
        // Popup is already added to windowManager
        if (popupHasParent()) return

        updateScreenSize()

        popupLayoutParams = retrievePopupLayoutParamsFromPrefs()
        binding.surfaceView.setHeights(popupLayoutParams!!.height, popupLayoutParams!!.height)

        checkPopupPositionBounds()

        binding.loadingPanel.minimumWidth = popupLayoutParams!!.width
        binding.loadingPanel.minimumHeight = popupLayoutParams!!.height

        windowManager!!.addView(binding.root, popupLayoutParams)
        setupVideoSurfaceIfNeeded() // now there is a parent, we can setup video surface

        // Popup doesn't have aspectRatio selector, using FIT automatically
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }

    @SuppressLint("RtlHardcoded")
    private fun initPopupCloseOverlay() {
        Logd(TAG, "initPopupCloseOverlay() called")
        // closeOverlayView is already added to windowManager
        if (closeOverlayBinding != null) return

        closeOverlayBinding = PlayerPopupCloseOverlayBinding.inflate(LayoutInflater.from(context))

        val closeOverlayLayoutParams = buildCloseOverlayLayoutParams()
        closeOverlayBinding!!.closeButton.visibility = View.GONE
        windowManager!!.addView(closeOverlayBinding!!.root, closeOverlayLayoutParams)
    }

    override fun setupElementsVisibility() {
        binding.fullScreenButton.visibility = View.VISIBLE
        binding.screenRotationButton.visibility = View.GONE
        binding.resizeTextView.visibility = View.GONE
        binding.root.findViewById<View>(R.id.metadataView).visibility = View.GONE
        binding.queueButton.visibility = View.GONE
        binding.segmentsButton.visibility = View.GONE
        binding.moreOptionsButton.visibility = View.GONE
        binding.topControls.orientation = LinearLayout.HORIZONTAL
        binding.primaryControls.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
        binding.secondaryControls.alpha = 1.0f
        binding.secondaryControls.visibility = View.VISIBLE
        binding.secondaryControls.translationY = 0f
        binding.share.visibility = View.GONE
        binding.playWithKodi.visibility = View.GONE
        binding.openInBrowser.visibility = View.GONE
        binding.switchMute.visibility = View.GONE
        binding.playerCloseButton.visibility = View.GONE
        binding.topControls.bringToFront()
        binding.topControls.isClickable = false
        binding.topControls.isFocusable = false
        binding.bottomControls.bringToFront()
        super.setupElementsVisibility()
    }

    override fun setupElementsSize(resources: Resources) {
        setupElementsSize(0, 0, resources.getDimensionPixelSize(R.dimen.player_popup_controls_padding), resources.getDimensionPixelSize(R.dimen.player_popup_buttons_padding))
    }

    override fun removeViewFromParent() {
        // view was added by windowManager for popup player
        windowManager!!.removeViewImmediate(binding.root)
    }

    override fun destroy() {
        super.destroy()
        removePopupFromView()
    }

    override fun onBroadcastReceived(intent: Intent?) {
        super.onBroadcastReceived(intent)
        if (Intent.ACTION_CONFIGURATION_CHANGED == intent!!.action) {
            updateScreenSize()
            changePopupSize(popupLayoutParams!!.width)
            checkPopupPositionBounds()
        } else if (playerManager.isPlaying || playerManager.isLoading) {
            // Use only audio source when screen turns off while popup player is playing
            if (Intent.ACTION_SCREEN_OFF == intent.action) playerManager.useVideoSource(false)
            // Restore video source when screen turns on and user was watching video in popup
            else if (Intent.ACTION_SCREEN_ON == intent.action) playerManager.useVideoSource(true)
        }
    }

    /**
     * Check if [.popupLayoutParams]' position is within a arbitrary boundary
     * that goes from (0, 0) to (screenWidth, screenHeight).
     *
     * If it's out of these boundaries, [.popupLayoutParams]' position is changed
     * and `true` is returned to represent this change.
     *
     */
    fun checkPopupPositionBounds() {
        Logd(TAG, "checkPopupPositionBounds() called with: screenWidth = [$screenWidth], screenHeight = [$screenHeight]")
        if (popupLayoutParams == null) return

        popupLayoutParams!!.x = MathUtils.clamp(popupLayoutParams!!.x, 0, screenWidth - popupLayoutParams!!.width)
        popupLayoutParams!!.y = MathUtils.clamp(popupLayoutParams!!.y, 0, screenHeight - popupLayoutParams!!.height)
    }

    fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager!!.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val windowInsets = windowMetrics.windowInsets
            val insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            screenWidth = bounds.width() - (insets.left + insets.right)
            screenHeight = bounds.height() - (insets.top + insets.bottom)
        } else {
            val metrics = DisplayMetrics()
            windowManager!!.defaultDisplay.getMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        Logd(TAG, "updateScreenSize() called: screenWidth = [$screenWidth], screenHeight = [$screenHeight]")
    }

    /**
     * Changes the size of the popup based on the width.
     * @param width the new width, height is calculated with
     * [PlayerHelper.getMinimumVideoHeight]
     */
    fun changePopupSize(width: Int) {
        Logd(TAG, "changePopupSize() called with: width = [$width]")
        if (anyPopupViewIsNull()) return

        val minimumWidth = context.resources.getDimension(R.dimen.popup_minimum_width)
        val actualWidth = MathUtils.clamp(width, minimumWidth.toInt(), screenWidth)
        val actualHeight = PlayerHelper.getMinimumVideoHeight(width.toFloat()).toInt()

        Logd(TAG, "updatePopupSize() updated values:  width = [$actualWidth], height = [$actualHeight]")

        popupLayoutParams!!.width = actualWidth
        popupLayoutParams!!.height = actualHeight
        binding.surfaceView.setHeights(popupLayoutParams!!.height, popupLayoutParams!!.height)
        windowManager!!.updateViewLayout(binding.root, popupLayoutParams)
    }

    override fun calculateMaxEndScreenThumbnailHeight(bitmap: Bitmap): Float {
        // no need for the end screen thumbnail to be resized on popup player: it's only needed
        // for the main player so that it is enlarged correctly inside the fragment
        return bitmap.height.toFloat()
    }

    fun closePopup() {
        Logd(TAG, "closePopup() called, isPopupClosing = $isPopupClosing")
        if (isPopupClosing) return

        isPopupClosing = true

        playerManager.saveStreamProgressState()
        windowManager!!.removeView(binding.root)
        animatePopupOverlayAndFinishService()
    }

    private fun removePopupFromView() {
        // wrap in try-catch since it could sometimes generate errors randomly
        try {
            if (popupHasParent()) windowManager!!.removeView(binding.root)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to remove popup from window manager", e)
        }

        try {
            val closeOverlayHasParent = (closeOverlayBinding != null && closeOverlayBinding!!.root.parent != null)
            if (closeOverlayHasParent) windowManager!!.removeView(closeOverlayBinding!!.root)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to remove popup overlay from window manager", e)
        }
    }

    private fun animatePopupOverlayAndFinishService() {
        val targetTranslationY = (closeOverlayBinding!!.closeButton.rootView.height - closeOverlayBinding!!.closeButton.y).toInt()

        closeOverlayBinding!!.closeButton.animate().setListener(null).cancel()
        closeOverlayBinding!!.closeButton.animate()
            .setInterpolator(AnticipateInterpolator())
            .translationY(targetTranslationY.toFloat())
            .setDuration(400)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    end()
                }
                override fun onAnimationEnd(animation: Animator) {
                    end()
                }
                private fun end() {
                    windowManager!!.removeView(closeOverlayBinding!!.root)
                    closeOverlayBinding = null
                    playerManager.service.stopService()
                }
            }).start()
    }

    private fun changePopupWindowFlags(flags: Int) {
        Logd(TAG, "changePopupWindowFlags() called with: flags = [$flags]")
        if (!anyPopupViewIsNull()) {
            popupLayoutParams!!.flags = flags
            windowManager!!.updateViewLayout(binding.root, popupLayoutParams)
        }
    }

    override fun onPlaying() {
        super.onPlaying()
        changePopupWindowFlags(ONGOING_PLAYBACK_WINDOW_FLAGS)
    }

    override fun onPaused() {
        super.onPaused()
        changePopupWindowFlags(IDLE_WINDOW_FLAGS)
    }

    override fun onCompleted() {
        super.onCompleted()
        changePopupWindowFlags(IDLE_WINDOW_FLAGS)
    }

    override fun setupSubtitleView(captionScale: Float) {
        val captionRatio = (captionScale - 1.0f) / 5.0f + 1.0f
        binding.subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * captionRatio)
    }

    override fun onPlaybackSpeedClicked() {
        playbackSpeedPopupMenu?.show()
        isSomePopupMenuVisible = true
    }

    private fun distanceFromCloseButton(popupMotionEvent: MotionEvent): Int {
        val closeOverlayButtonX = (closeOverlayBinding!!.closeButton.left + closeOverlayBinding!!.closeButton.width / 2)
        val closeOverlayButtonY = (closeOverlayBinding!!.closeButton.top + closeOverlayBinding!!.closeButton.height / 2)

        val fingerX = popupLayoutParams!!.x + popupMotionEvent.x
        val fingerY = popupLayoutParams!!.y + popupMotionEvent.y

        return sqrt((closeOverlayButtonX - fingerX).pow(2.0f) + (closeOverlayButtonY - fingerY).pow(2.0f)).toInt()
    }

    private val closingRadius: Float
        get() {
            val buttonRadius = closeOverlayBinding!!.closeButton.width / 2
            // 20% wider than the button itself
            return buttonRadius * 1.2f
        }

    fun isInsideClosingRadius(popupMotionEvent: MotionEvent): Boolean {
        return distanceFromCloseButton(popupMotionEvent) <= closingRadius
    }

    /**
     * `screenWidth` and `screenHeight` must have been initialized.
     * @return the popup starting layout params
     */
    @SuppressLint("RtlHardcoded")
    fun retrievePopupLayoutParamsFromPrefs(): WindowManager.LayoutParams {
        val prefs = playerManager.prefs
        val context = playerManager.context

        val popupRememberSizeAndPos = prefs.getBoolean(context.getString(R.string.popup_remember_size_pos_key), true)
        val defaultSize = context.resources.getDimension(R.dimen.popup_default_width)
        val popupWidth = if (popupRememberSizeAndPos) prefs.getFloat(context.getString(R.string.popup_saved_width_key), defaultSize) else defaultSize
        val popupHeight = PlayerHelper.getMinimumVideoHeight(popupWidth)

        val params = WindowManager.LayoutParams(popupWidth.toInt(), popupHeight.toInt(), popupLayoutParamType(), IDLE_WINDOW_FLAGS, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.LEFT or Gravity.TOP
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        val centerX = (screenWidth / 2f - popupWidth / 2f).toInt()
        val centerY = (screenHeight / 2f - popupHeight / 2f).toInt()
        params.x = if (popupRememberSizeAndPos) prefs.getInt(context.getString(R.string.popup_saved_x_key), centerX) else centerX
        params.y = if (popupRememberSizeAndPos) prefs.getInt(context.getString(R.string.popup_saved_y_key), centerY) else centerY

        return params
    }

    fun savePopupPositionAndSizeToPrefs() {
        if (popupLayoutParams != null) {
            val context = playerManager.context
            playerManager.prefs.edit()
                .putFloat(context.getString(R.string.popup_saved_width_key), popupLayoutParams!!.width.toFloat())
                .putInt(context.getString(R.string.popup_saved_x_key), popupLayoutParams!!.x)
                .putInt(context.getString(R.string.popup_saved_y_key), popupLayoutParams!!.y)
                .apply()
        }
    }

    private fun popupHasParent(): Boolean {
        return binding.root.layoutParams is WindowManager.LayoutParams && binding.root.parent != null
    }

    private fun anyPopupViewIsNull(): Boolean {
        return popupLayoutParams == null || windowManager == null || binding.root.parent == null
    }

    class PopupPlayerGestureListener(private val playerUi: PopupPlayerUi) : BasePlayerGestureListener(playerUi) {
        private var isMoving = false

        private var initialPopupX: Int = -1
        private var initialPopupY: Int = -1
        private var isResizing = false

        // initial coordinates and distance between fingers
        private var initPointerDistance = -1.0
        private var initFirstPointerX = -1f
        private var initFirstPointerY = -1f
        private var initSecPointerX = -1f
        private var initSecPointerY = -1f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            super.onTouch(v, event)
            if (event.pointerCount == 2 && !isMoving && !isResizing) {
                Logd(TAG, "onTouch() 2 finger pointer detected, enabling resizing.")
                onPopupResizingStart()

                // record coordinates of fingers
                initFirstPointerX = event.getX(0)
                initFirstPointerY = event.getY(0)
                initSecPointerX = event.getX(1)
                initSecPointerY = event.getY(1)
                // record distance between fingers
                initPointerDistance = hypot(initFirstPointerX - initSecPointerX.toDouble(), initFirstPointerY - initSecPointerY.toDouble())
                isResizing = true
            }
            if (event.action == MotionEvent.ACTION_MOVE && !isMoving && isResizing) {
                Logd(TAG, "onTouch() ACTION_MOVE > v = [$v], e1.getRaw =[${event.rawX}, ${event.rawY}]")
                return handleMultiDrag(event)
            }
            if (event.action == MotionEvent.ACTION_UP) {
                Logd(TAG, "onTouch() ACTION_UP > v = [$v], e1.getRaw = [${event.rawX}, ${event.rawY}]")
                if (isMoving) {
                    isMoving = false
                    onScrollEnd(event)
                }
                if (isResizing) {
                    isResizing = false

                    initPointerDistance = (-1).toDouble()
                    initFirstPointerX = (-1).toFloat()
                    initFirstPointerY = (-1).toFloat()
                    initSecPointerX = (-1).toFloat()
                    initSecPointerY = (-1).toFloat()

                    onPopupResizingEnd()
                    playerManager.changeState(playerManager.currentState)
                }
                if (!playerUi.isPopupClosing) playerUi.savePopupPositionAndSizeToPrefs()
            }

            v.performClick()
            return true
        }

        override fun onScrollEnd(event: MotionEvent) {
            super.onScrollEnd(event)
            if (playerUi.isInsideClosingRadius(event)) playerUi.closePopup()
            else if (!playerUi.isPopupClosing) {
                playerUi.closeOverlayBinding?.closeButton?.animate(false, 200)
                binding.closingOverlay.animate(false, 200)
            }
        }

        private fun handleMultiDrag(event: MotionEvent): Boolean {
            if (initPointerDistance == -1.0 || event.pointerCount != 2) return false

            // get the movements of the fingers
            val firstPointerMove =
                hypot(event.getX(0) - initFirstPointerX.toDouble(), event.getY(0) - initFirstPointerY.toDouble())
            val secPointerMove =
                hypot(event.getX(1) - initSecPointerX.toDouble(), event.getY(1) - initSecPointerY.toDouble())

            // minimum threshold beyond which pinch gesture will work
            val minimumMove = ViewConfiguration.get(playerManager.context).scaledTouchSlop
            if (max(firstPointerMove, secPointerMove) <= minimumMove) return false

            // calculate current distance between the pointers
            val currentPointerDistance =
                hypot(event.getX(0) - event.getX(1).toDouble(), event.getY(0) - event.getY(1).toDouble())

            val popupWidth = playerUi.popupLayoutParams!!.width.toDouble()
            // change co-ordinates of popup so the center stays at the same position
            val newWidth = popupWidth * currentPointerDistance / initPointerDistance
            initPointerDistance = currentPointerDistance
            playerUi.popupLayoutParams!!.x += ((popupWidth - newWidth) / 2.0).toInt()

            playerUi.checkPopupPositionBounds()
            playerUi.updateScreenSize()
            playerUi.changePopupSize(min(playerUi.screenWidth.toDouble(), newWidth).toInt())
            return true
        }

        private fun onPopupResizingStart() {
            Logd(TAG, "onPopupResizingStart called")
            binding.loadingPanel.visibility = View.GONE
            playerUi.hideControls(0, 0)
            binding.fastSeekOverlay.animate(false, 0)
            binding.currentDisplaySeek.animate(false, 0, AnimationType.ALPHA, 0)
        }

        private fun onPopupResizingEnd() {
            Logd(TAG, "onPopupResizingEnd called")
        }

        override fun onLongPress(e: MotionEvent) {
            playerUi.updateScreenSize()
            playerUi.checkPopupPositionBounds()
            playerUi.changePopupSize(playerUi.screenWidth)
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            return if (playerManager.popupPlayerSelected()) {
                val absVelocityX = abs(velocityX)
                val absVelocityY = abs(velocityY)
                if (absVelocityX.coerceAtLeast(absVelocityY) > TOSS_FLING_VELOCITY) {
                    if (absVelocityX > TOSS_FLING_VELOCITY) playerUi.popupLayoutParams!!.x = velocityX.toInt()
                    if (absVelocityY > TOSS_FLING_VELOCITY) playerUi.popupLayoutParams!!.y = velocityY.toInt()
                    playerUi.checkPopupPositionBounds()
                    playerUi.windowManager?.updateViewLayout(binding.root, playerUi.popupLayoutParams)
                    return true
                }
                return false
            } else true
        }

        override fun onDownNotDoubleTapping(e: MotionEvent): Boolean {
            // Fix popup position when the user touch it, it may have the wrong one
            // because the soft input is visible (the draggable area is currently resized).
            playerUi.updateScreenSize()
            playerUi.checkPopupPositionBounds()
            playerUi.popupLayoutParams?.let {
                initialPopupX = it.x
                initialPopupY = it.y
            }
            return true // we want `super.onDown(e)` to be called
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Logd(TAG, "onSingleTapConfirmed() called with: e = [$e]")
            if (isDoubleTapping) return true
            if (playerManager.exoPlayerIsNull()) return false
            onSingleTap()
            return true
        }

        override fun onScroll(initialEvent: MotionEvent?, movingEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (initialEvent == null) return false
            if (isResizing) return super.onScroll(initialEvent, movingEvent, distanceX, distanceY)
            if (!isMoving) playerUi.closeOverlayBinding?.closeButton?.animate(true, 200)
            isMoving = true

            val diffX = (movingEvent.rawX - initialEvent.rawX)
            val posX = (initialPopupX + diffX).coerceIn(0f, (playerUi.screenWidth - playerUi.popupLayoutParams!!.width).toFloat().coerceAtLeast(0f))
            val diffY = (movingEvent.rawY - initialEvent.rawY)
            val posY = (initialPopupY + diffY).coerceIn(0f, (playerUi.screenHeight - playerUi.popupLayoutParams!!.height).toFloat().coerceAtLeast(0f))

            playerUi.popupLayoutParams!!.x = posX.toInt()
            playerUi.popupLayoutParams!!.y = posY.toInt()

            // -- Determine if the ClosingOverlayView (red X) has to be shown or hidden --
            val showClosingOverlayView: Boolean = playerUi.isInsideClosingRadius(movingEvent)
            // Check if an view is in expected state and if not animate it into the correct state
            if (binding.closingOverlay.isVisible != showClosingOverlayView) binding.closingOverlay.animate(showClosingOverlayView, 200)

            playerUi.windowManager?.updateViewLayout(binding.root, playerUi.popupLayoutParams)
            return true
        }

        override fun getDisplayPortion(e: MotionEvent): DisplayPortion {
            return when {
                e.x < playerUi.popupLayoutParams!!.width / 3.0 -> DisplayPortion.LEFT
                e.x > playerUi.popupLayoutParams!!.width * 2.0 / 3.0 -> DisplayPortion.RIGHT
                else -> DisplayPortion.MIDDLE
            }
        }

        override fun getDisplayHalfPortion(e: MotionEvent): DisplayPortion {
            return when {
                e.x < playerUi.popupLayoutParams!!.width / 2.0 -> DisplayPortion.LEFT_HALF
                else -> DisplayPortion.RIGHT_HALF
            }
        }

        companion object {
            private val TAG = PopupPlayerGestureListener::class.java.simpleName
//            private val DEBUG = MainActivity.DEBUG
            private const val TOSS_FLING_VELOCITY = 2500
        }
    }

    companion object {
        private val TAG: String = PopupPlayerUi::class.java.simpleName

        /**
         * Maximum opacity allowed for Android 12 and higher to allow touches on other apps when using
         * Vista's popup player.
         *
         *
         *
         * This value is hardcoded instead of being get dynamically with the method linked of the
         * constant documentation below, because it is not static and popup player layout parameters
         * are generated with static methods.
         *
         *
         * @see WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
         */
        private const val MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f

        const val IDLE_WINDOW_FLAGS: Int = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        const val ONGOING_PLAYBACK_WINDOW_FLAGS: Int = (IDLE_WINDOW_FLAGS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @SuppressLint("RtlHardcoded")
        fun buildCloseOverlayLayoutParams(): WindowManager.LayoutParams {
            val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)

            val closeOverlayLayoutParams = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                popupLayoutParamType(), flags, PixelFormat.TRANSLUCENT)

            // Setting maximum opacity allowed for touch events to other apps for Android 12 and
            // higher to prevent non interaction when using other apps with the popup player
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) closeOverlayLayoutParams.alpha = MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER

            closeOverlayLayoutParams.gravity = Gravity.LEFT or Gravity.TOP
            closeOverlayLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            return closeOverlayLayoutParams
        }

        fun popupLayoutParamType(): Int {
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_PHONE
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }
}
