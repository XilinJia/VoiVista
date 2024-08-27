package ac.mdiq.vista.database.history.dao

import ac.mdiq.vista.database.BasicDAO

interface HistoryDAO<T> : BasicDAO<T> {
    fun getLatestEntry(): T?
}
