package ac.mdiq.vista.ui.util

interface ViewContract<I> {
    fun showLoading()

    fun hideLoading()

    fun showEmptyState()

    fun handleResult(result: I)

    fun handleError()
}
