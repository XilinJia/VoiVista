package ac.mdiq.vista.ui.util

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.widget.Toast
import androidx.preference.PreferenceManager
import ac.mdiq.vista.R
import ac.mdiq.vista.ui.util.Tab.Companion.from
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter

class TabsManager private constructor(private val context: Context) {
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val savedTabsKey = context.getString(R.string.saved_tabs_key)
    private var savedTabsChangeListener: SavedTabsChangeListener? = null
    private var preferenceChangeListener: OnSharedPreferenceChangeListener? = null

    val tabs: List<Tab>
        get() {
            val savedJson = sharedPreferences.getString(savedTabsKey, null)
            try {
                return getTabsFromJson(savedJson)
            } catch (e: InvalidJsonException) {
                Toast.makeText(context, R.string.saved_tabs_invalid_json, Toast.LENGTH_SHORT).show()
                return defaultTabs
            }
        }

    fun saveTabs(tabList: List<Tab>?) {
        val jsonToSave = getJsonToSave(tabList)
        sharedPreferences.edit().putString(savedTabsKey, jsonToSave).apply()
    }

    fun resetTabs() {
        sharedPreferences.edit().remove(savedTabsKey).apply()
    }

    fun setSavedTabsListener(listener: SavedTabsChangeListener?) {
        if (preferenceChangeListener != null) sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        savedTabsChangeListener = listener
        preferenceChangeListener = getPreferenceChangeListener()
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun unsetSavedTabsListener() {
        if (preferenceChangeListener != null) sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        preferenceChangeListener = null
        savedTabsChangeListener = null
    }

    private fun getPreferenceChangeListener(): OnSharedPreferenceChangeListener {
        return OnSharedPreferenceChangeListener { _: SharedPreferences?, key: String? ->
            if (savedTabsKey == key && savedTabsChangeListener != null) savedTabsChangeListener!!.onTabsChanged()
        }
    }

    fun interface SavedTabsChangeListener {
        fun onTabsChanged()
    }

    companion object {
        @JvmStatic
        fun getManager(context: Context): TabsManager {
            return TabsManager(context)
        }

        private const val JSON_TABS_ARRAY_KEY = "tabs"

        val defaultTabs: List<Tab> = listOf(
            Tab.Type.DEFAULT_KIOSK.tab,
            Tab.Type.FEED.tab,
            Tab.Type.SUBSCRIPTIONS.tab,
            Tab.Type.BOOKMARKS.tab)

        /**
         * Try to reads the passed JSON and returns the list of tabs if no error were encountered.
         *
         * If the JSON is null or empty, or the list of tabs that it represents is empty, the
         * [fallback list][.getDefaultTabs] will be returned.
         *
         * Tabs with invalid ids (i.e. not in the [Tab.Type] enum) will be ignored.
         *
         * @param tabsJson a JSON string got from [.getJsonToSave].
         * @return a list of [tabs][Tab].
         * @throws InvalidJsonException if the JSON string is not valid
         */
        @JvmStatic
        @Throws(InvalidJsonException::class)
        fun getTabsFromJson(tabsJson: String?): List<Tab> {
            if (tabsJson.isNullOrEmpty()) return defaultTabs

            val returnTabs: MutableList<Tab> = ArrayList()

            val outerJsonObject: JsonObject
            try {
                outerJsonObject = JsonParser.`object`().from(tabsJson)

                if (!outerJsonObject.has(JSON_TABS_ARRAY_KEY)) throw InvalidJsonException("JSON doesn't contain \"$JSON_TABS_ARRAY_KEY\" array")
                val tabsArray = outerJsonObject.getArray(JSON_TABS_ARRAY_KEY)

                for (o in tabsArray) {
                    if (o !is JsonObject) continue
                    val tab = from(o)
                    if (tab != null) returnTabs.add(tab)
                }
            } catch (e: JsonParserException) {
                throw InvalidJsonException(e)
            }
            if (returnTabs.isEmpty()) return defaultTabs
            return returnTabs
        }

        /**
         * Get a JSON representation from a list of tabs.
         *
         * @param tabList a list of [tabs][Tab].
         * @return a JSON string representing the list of tabs
         */
        fun getJsonToSave(tabList: List<Tab>?): String {
            val jsonWriter = JsonWriter.string()
            jsonWriter.`object`()

            jsonWriter.array(JSON_TABS_ARRAY_KEY)
            if (tabList != null) {
                for (tab in tabList) {
                    tab.writeJsonOn(jsonWriter)
                }
            }
            jsonWriter.end()
            jsonWriter.end()
            return jsonWriter.done()
        }

        class InvalidJsonException : Exception {
            private constructor() : super()

            internal constructor(message: String) : super(message)

            constructor(cause: Throwable) : super(cause)
        }
    }
}
