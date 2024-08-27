package ac.mdiq.vista.settings

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.annotation.IdRes
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup.PreferencePositionCallback
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.jakewharton.rxbinding4.widget.textChanges
import icepick.Icepick
import icepick.State
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.SettingsLayoutBinding
import ac.mdiq.vista.settings.SettingsResourceRegistry.SettingRegistryEntry
import ac.mdiq.vista.settings.fragment.MainSettingsFragment
import ac.mdiq.vista.settings.preferencesearch.*
import ac.mdiq.vista.util.DeviceUtils.isTv
import ac.mdiq.vista.util.KeyboardUtil.hideKeyboard
import ac.mdiq.vista.util.KeyboardUtil.showKeyboard
import ac.mdiq.vista.util.Localization.assureCorrectAppLanguage
import ac.mdiq.vista.util.Logd
import ac.mdiq.vista.util.ReleaseVersionUtil.isReleaseApk
import ac.mdiq.vista.ui.util.ThemeHelper.getSettingsThemeStyle
import ac.mdiq.vista.ui.views.FocusOverlayView.Companion.setupFocusObserver
import ac.mdiq.vista.util.Localization.concatenateStrings
import org.xmlpull.v1.XmlPullParser
import java.util.ArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Function

/*
* Created by Christian Schabesberger on 31.08.15.
*
* Copyright (C) Christian Schabesberger 2015 <chris.schabesberger@mailbox.org>
* Copyright (C) 2024 Xilin Jia <https://github.com/XilinJia>
* SettingsActivity.kt is part of Vista.
*
* Vista is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* Vista is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Vista.  If not, see <http://www.gnu.org/licenses/>.
*/
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback, PreferenceSearchResultListener {
    private var searchFragment: PreferenceSearchFragment? = null

    private var menuSearchItem: MenuItem? = null

    private var searchContainer: View? = null
    private var searchEditText: EditText? = null

    // State
    @JvmField
    @State
    var searchText: String? = null

    @JvmField
    @State
    var wasSearchActive: Boolean = false

    override fun onCreate(savedInstanceBundle: Bundle?) {
        setTheme(getSettingsThemeStyle(this))
        assureCorrectAppLanguage(this)

        super.onCreate(savedInstanceBundle)
        Icepick.restoreInstanceState(this, savedInstanceBundle)
        val restored = savedInstanceBundle != null

        val settingsLayoutBinding = SettingsLayoutBinding.inflate(layoutInflater)
        setContentView(settingsLayoutBinding.root)
        initSearch(settingsLayoutBinding, restored)

        setSupportActionBar(settingsLayoutBinding.settingsToolbarLayout.toolbar)

        if (restored) {
            // Restore state
            if (this.wasSearchActive) {
                isSearchActive = true
                if (!this.searchText.isNullOrEmpty()) searchEditText!!.setText(this.searchText)
            }
        } else supportFragmentManager.beginTransaction().replace(R.id.settings_fragment_holder, MainSettingsFragment()).commit()

        if (isTv(this)) setupFocusObserver(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Icepick.saveInstanceState(this, outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setDisplayShowTitleEnabled(true)
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onBackPressed() {
        if (isSearchActive) {
            isSearchActive = false
//            return
        }
        super.onBackPressed()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == android.R.id.home) {
            // Check if the search is active and if so: Close it
            if (isSearchActive) {
                isSearchActive = false
                return true
            }
            if (supportFragmentManager.backStackEntryCount == 0) finish()
            else supportFragmentManager.popBackStack()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, preference: Preference): Boolean {
        showSettingsFragment(instantiateFragment(preference.fragment!!))
        return true
    }

    private fun instantiateFragment(className: String): Fragment {
        return supportFragmentManager.fragmentFactory.instantiate(this.classLoader, className)
    }

    private fun showSettingsFragment(fragment: Fragment?) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.animator.custom_fade_in, R.animator.custom_fade_out, R.animator.custom_fade_in, R.animator.custom_fade_out)
            .replace(FRAGMENT_HOLDER_ID, fragment!!)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroy() {
        setMenuSearchItem(null)
        searchFragment = null
        super.onDestroy()
    }

    private fun initSearch(settingsLayoutBinding: SettingsLayoutBinding, restored: Boolean) {
        searchContainer = settingsLayoutBinding.settingsToolbarLayout.toolbar.findViewById(R.id.toolbar_search_container)

        // Configure input field for search
        searchEditText = searchContainer!!.findViewById(R.id.toolbar_search_edit_text)
        searchEditText!!.textChanges() // Wait some time after the last input before actually searching
            .debounce(200, TimeUnit.MILLISECONDS)
            .subscribe { v: CharSequence? -> runOnUiThread { this.onSearchChanged() } }

        // Configure clear button
        searchContainer!!.findViewById<View>(R.id.toolbar_search_clear).setOnClickListener { ev: View? -> resetSearchText() }
        ensureSearchRepresentsApplicationState()

        // Build search configuration using SettingsResourceRegistry
        val config = PreferenceSearchConfiguration()
        // Build search items
        val searchContext = applicationContext
        assureCorrectAppLanguage(searchContext)
        val parser = PreferenceParser(searchContext, config)
        val searcher = PreferenceSearcher(config)

        // Find all searchable SettingsResourceRegistry fragments
        // Add it to the searcher
        SettingsResourceRegistry.instance.allEntries.stream()
            .filter { it?.isSearchable?:false }
            .map<Int>(Function<SettingRegistryEntry?, Int> { it?.preferencesResId })
            .map<List<PreferenceSearchItem>> { resId: Int? -> parser.parse(resId!!) }
            .forEach { items: List<PreferenceSearchItem> -> searcher.add(items) }

        if (restored) {
            searchFragment = supportFragmentManager.findFragmentByTag(PreferenceSearchFragment.NAME) as PreferenceSearchFragment?
            // Hide/Remove the search fragment otherwise we get an exception
            // when adding it (because it's already present)
            if (searchFragment != null) hideSearchFragment()
        }
        if (searchFragment == null) searchFragment = PreferenceSearchFragment()
        searchFragment!!.setSearcher(searcher)
    }

    /**
     * Ensures that the search shows the correct/available search results.
     * <br></br>
     * Some features are e.g. only available for debug builds, these should not
     * be found when searching inside a release.
     */
    private fun ensureSearchRepresentsApplicationState() {
        // Check if the update settings are available
        if (!isReleaseApk()) SettingsResourceRegistry.instance.getEntryByPreferencesResId(R.xml.update_settings)?.setSearchable(false)
        // Hide debug preferences in RELEASE build variant
        if (DEBUG) SettingsResourceRegistry.instance.getEntryByPreferencesResId(R.xml.debug_settings)?.setSearchable(true)
    }

    fun setMenuSearchItem(menuSearchItem: MenuItem?) {
        this.menuSearchItem = menuSearchItem

        // Ensure that the item is in the correct state when adding it. This is due to
        // Android's lifecycle (the Activity is recreated before the Fragment that registers this)
        menuSearchItem?.setVisible(!isSearchActive)
    }

    private fun hideSearchFragment() {
        supportFragmentManager.beginTransaction().remove(searchFragment!!).commit()
    }

    private fun resetSearchText() {
        searchEditText!!.setText("")
    }

    var isSearchActive: Boolean = false
        get() = searchContainer!!.visibility == View.VISIBLE
        set(active) {
            Logd(TAG, "setSearchActive called active=$active")
            // Ignore if search is already in correct state
            if (field == active) return

            wasSearchActive = active

            searchContainer!!.visibility = if (active) View.VISIBLE else View.GONE
            menuSearchItem?.setVisible(!active)

            if (active) {
                supportFragmentManager
                    .beginTransaction()
                    .add(FRAGMENT_HOLDER_ID, searchFragment!!, PreferenceSearchFragment.NAME)
                    .addToBackStack(PreferenceSearchFragment.NAME)
                    .commit()
                showKeyboard(this, searchEditText)
            } else if (searchFragment != null) {
                hideSearchFragment()
                supportFragmentManager.popBackStack(PreferenceSearchFragment.NAME, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                hideKeyboard(this, searchEditText)
            }
            resetSearchText()
        }

    private fun onSearchChanged() {
        if (!isSearchActive) return

        if (searchFragment != null) {
            searchText = searchEditText!!.text.toString()
            if (searchText != null) searchFragment!!.updateSearchResults(searchText!!)
        }
    }

    override fun onSearchResultClicked(result: PreferenceSearchItem) {
        Logd(TAG, "onSearchResultClicked called result=$result")

        // Hide the search
        isSearchActive = false

        // -- Highlight the result --
        // Find out which fragment class we need
        val targetedFragmentClass = SettingsResourceRegistry.instance.getFragmentClass(result.searchIndexItemResId)

        if (targetedFragmentClass == null) {
            // This should never happen
            Log.w(TAG, "Unable to locate fragment class for resId=${result.searchIndexItemResId}")
            return
        }

        // Check if the currentFragment is the one which contains the result
        var currentFragment = supportFragmentManager.findFragmentById(FRAGMENT_HOLDER_ID)
        if (targetedFragmentClass != currentFragment!!.javaClass) {
            // If it's not the correct one display the correct one
            currentFragment = instantiateFragment(targetedFragmentClass.name)
            showSettingsFragment(currentFragment)
        }

        // Run the highlighting
        if (currentFragment is PreferenceFragmentCompat) PreferenceSearchResultHighlighter.highlight(result, currentFragment)
    } //endregion

    object PreferenceSearchResultHighlighter {
        private const val TAG = "PrefSearchResHighlter"

        /**
         * Highlight the specified preference.
         * <br></br>
         * Note: This function is Thread independent (can be called from outside of the main thread).
         *
         * @param item The item to highlight
         * @param prefsFragment The fragment where the items is located on
         */
        fun highlight(item: PreferenceSearchItem, prefsFragment: PreferenceFragmentCompat) {
            Handler(Looper.getMainLooper()).post { doHighlight(item, prefsFragment) }
        }

        private fun doHighlight(item: PreferenceSearchItem, prefsFragment: PreferenceFragmentCompat) {
            val prefResult = prefsFragment.findPreference<Preference>(item.key)
            if (prefResult == null) {
                Log.w(TAG, "Preference '" + item.key + "' not found on '" + prefsFragment + "'")
                return
            }

            val recyclerView = prefsFragment.listView
            val adapter = recyclerView.adapter
            if (adapter is PreferencePositionCallback) {
                val position = (adapter as PreferencePositionCallback).getPreferenceAdapterPosition(prefResult)
                if (position != RecyclerView.NO_POSITION) {
                    recyclerView.scrollToPosition(position)
                    recyclerView.postDelayed({
                        val holder = recyclerView.findViewHolderForAdapterPosition(position)
                        if (holder != null) {
                            val background = holder.itemView.background
                            if (background is RippleDrawable) {
                                showRippleAnimation(background)
                                return@postDelayed
                            }
                        }
                        highlightFallback(prefsFragment, prefResult)
                    }, 200)
                    return
                }
            }
            highlightFallback(prefsFragment, prefResult)
        }

        /**
         * Alternative highlighting (shows an → arrow in front of the setting)if ripple does not work.
         *
         * @param prefsFragment
         * @param prefResult
         */
        private fun highlightFallback(prefsFragment: PreferenceFragmentCompat, prefResult: Preference) {
            // Get primary color from text for highlight icon
            val typedValue = TypedValue()
            val theme = prefsFragment.requireActivity().theme
            theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
            val arr = prefsFragment.requireActivity().obtainStyledAttributes(typedValue.data, intArrayOf(android.R.attr.textColorPrimary))
            val color = arr.getColor(0, -0x1ac6cb)
            arr.recycle()

            // Show highlight icon
            val oldIcon = prefResult.icon
            val oldSpaceReserved = prefResult.isIconSpaceReserved
            val highlightIcon = AppCompatResources.getDrawable(prefsFragment.requireContext(), R.drawable.ic_play_arrow)
            highlightIcon!!.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            prefResult.icon = highlightIcon

            prefsFragment.scrollToPreference(prefResult)
            Handler(Looper.getMainLooper()).postDelayed({ prefResult.icon = oldIcon; prefResult.isIconSpaceReserved = oldSpaceReserved }, 1000)
        }

        private fun showRippleAnimation(rippleDrawable: RippleDrawable) {
            rippleDrawable.setState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled))
            Handler(Looper.getMainLooper()).postDelayed({ rippleDrawable.setState(intArrayOf()) }, 1000)
        }
    }

    class PreferenceParser(private val context: Context, private val searchConfiguration: PreferenceSearchConfiguration) {
        private val allPreferences: Map<String, *> = PreferenceManager.getDefaultSharedPreferences(context).all
        fun parse(@XmlRes resId: Int): List<PreferenceSearchItem> {
            val results: MutableList<PreferenceSearchItem> = ArrayList()
            val xpp: XmlPullParser = context.resources.getXml(resId)

            try {
                xpp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                xpp.setFeature(XmlPullParser.FEATURE_REPORT_NAMESPACE_ATTRIBUTES, true)

                val breadcrumbs: MutableList<String?> = ArrayList()
                while (xpp.eventType != XmlPullParser.END_DOCUMENT) {
                    if (xpp.eventType == XmlPullParser.START_TAG) {
                        val result = parseSearchResult(xpp, concatenateStrings(" > ", breadcrumbs), resId)

                        if (!searchConfiguration.parserIgnoreElements.contains(xpp.name) && result.hasData()
                                && "true" != getAttribute(xpp, NS_SEARCH, "ignore")) results.add(result)

                        // This code adds breadcrumbs for certain containers (e.g. PreferenceScreen)
                        // Example: Video and Audio > Player
                        if (searchConfiguration.parserContainerElements.contains(xpp.name))
                            breadcrumbs.add(if (result.title == null) "" else result.title)

                    } else if (xpp.eventType == XmlPullParser.END_TAG && searchConfiguration.parserContainerElements.contains(xpp.name)) {
                        breadcrumbs.removeAt(breadcrumbs.size - 1)
                    }
                    xpp.next()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse resid=$resId", e)
            }
            return results
        }

        private fun getAttribute(xpp: XmlPullParser, attribute: String): String? {
            val nsSearchAttr = getAttribute(xpp, NS_SEARCH, attribute)
            if (nsSearchAttr != null) return nsSearchAttr
            return getAttribute(xpp, NS_ANDROID, attribute)
        }

        private fun getAttribute(xpp: XmlPullParser, namespace: String, attribute: String): String? {
            return xpp.getAttributeValue(namespace, attribute)
        }

        private fun parseSearchResult(xpp: XmlPullParser, breadcrumbs: String, @XmlRes searchIndexItemResId: Int): PreferenceSearchItem {
            val key = readString(getAttribute(xpp, "key"))
            val entries = readStringArray(getAttribute(xpp, "entries"))
            val entryValues = readStringArray(getAttribute(xpp, "entryValues"))

            return PreferenceSearchItem(key,
                tryFillInPreferenceValue(readString(getAttribute(xpp, "title")), key, entries.filterNotNull().toTypedArray(), entryValues),
                tryFillInPreferenceValue(readString(getAttribute(xpp, "summary")), key, entries.filterNotNull().toTypedArray(), entryValues),
                TextUtils.join(",", entries), breadcrumbs, searchIndexItemResId)
        }

        private fun readStringArray(s: String?): Array<String?> {
            if (s == null) return arrayOfNulls(0)

            if (s.startsWith("@")) {
                try {
                    return context.resources.getStringArray(s.substring(1).toInt())
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to readStringArray from '$s'", e)
                }
            }
            return arrayOfNulls(0)
        }

        private fun readString(s: String?): String {
            if (s == null) return ""

            if (s.startsWith("@")) {
                try {
                    return context.getString(s.substring(1).toInt())
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to readString from '$s'", e)
                }
            }
            return s
        }

        private fun tryFillInPreferenceValue(s: String?, key: String?, entries: Array<String>, entryValues: Array<String?>): String {
            if (s == null) return ""

            if (key == null) return s

            // Resolve value
            var prefValue = (allPreferences[key] as? String) ?: return s

            /*
             * Resolve ListPreference values
             *
             * entryValues = Values/Keys that are saved
             * entries     = Actual human readable names
             */
            if (entries.isNotEmpty() && entryValues.size == entries.size) {
                val entryIndex = listOf<String?>(*entryValues).indexOf(prefValue)
                if (entryIndex != -1) prefValue = entries[entryIndex]
            }
            return String.format(s, prefValue.toString())
        }

        companion object {
            private const val TAG = "PreferenceParser"

            private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"
            private const val NS_SEARCH = "http://schemas.android.com/apk/preferencesearch"
        }
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private val DEBUG = MainActivity.DEBUG

        @IdRes
        private val FRAGMENT_HOLDER_ID = R.id.settings_fragment_holder
    }
}
