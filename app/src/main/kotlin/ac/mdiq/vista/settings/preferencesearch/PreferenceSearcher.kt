package ac.mdiq.vista.settings.preferencesearch

import android.text.TextUtils
import java.util.stream.Collectors

class PreferenceSearcher(private val configuration: PreferenceSearchConfiguration) {
    private val allEntries: MutableList<PreferenceSearchItem> = ArrayList()

    fun add(items: List<PreferenceSearchItem>) {
        allEntries.addAll(items)
    }

    fun searchFor(keyword: String): List<PreferenceSearchItem> {
        if (keyword.isEmpty()) return emptyList()
        return configuration.searcher.search(allEntries.stream(), keyword).collect(Collectors.toList())
    }

    fun clear() {
        allEntries.clear()
    }
}
