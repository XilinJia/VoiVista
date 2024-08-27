package ac.mdiq.vista.settings.fragment

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.R
import ac.mdiq.vista.settings.SettingsActivity
import ac.mdiq.vista.util.ReleaseVersionUtil.isReleaseApk

class MainSettingsFragment : BasePreferenceFragment() {
    private var settingsActivity: SettingsActivity? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        setHasOptionsMenu(true) // Otherwise onCreateOptionsMenu is not called

        // Check if the app is updatable
        if (!isReleaseApk()) {
            preferenceScreen.removePreference(findPreference(getString(R.string.update_pref_screen_key))!!)
            defaultPreferences.edit().putBoolean(getString(R.string.update_app_key), false).apply()
        }

        // Hide debug preferences in RELEASE build variant
        if (!DEBUG) preferenceScreen.removePreference(findPreference(getString(R.string.debug_pref_screen_key))!!)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        // -- Link settings activity and register menu --
        settingsActivity = activity as SettingsActivity?

        inflater.inflate(R.menu.menu_settings_main_fragment, menu)

        val menuSearchItem = menu.getItem(0)

        settingsActivity!!.setMenuSearchItem(menuSearchItem)

        menuSearchItem.setOnMenuItemClickListener { ev: MenuItem? ->
            settingsActivity!!.isSearchActive = true
            true
        }
    }

    override fun onDestroy() {
        // Unlink activity so that we don't get memory problems
        if (settingsActivity != null) {
            settingsActivity!!.setMenuSearchItem(null)
            settingsActivity = null
        }
        super.onDestroy()
    }

    companion object {
        val DEBUG: Boolean = MainActivity.DEBUG
    }
}
