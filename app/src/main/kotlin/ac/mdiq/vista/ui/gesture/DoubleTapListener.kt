package ac.mdiq.vista.ui.gesture

interface DoubleTapListener {
    fun onDoubleTapStarted(portion: DisplayPortion)

    fun onDoubleTapProgressDown(portion: DisplayPortion)

    fun onDoubleTapFinished()
}
