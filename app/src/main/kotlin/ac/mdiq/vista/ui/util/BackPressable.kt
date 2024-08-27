package ac.mdiq.vista.ui.util

/**
 * Indicates that the current fragment can handle back presses.
 */
interface BackPressable {
    /**
     * A back press was delegated to this fragment.
     * @return if the back press was handled
     */
    fun onBackPressed(): Boolean
}
