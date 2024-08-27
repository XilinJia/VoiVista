package ac.mdiq.vista.settings.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import ac.mdiq.vista.NewVersionWorker.Companion.enqueueNewVersionCheckingWork
import ac.mdiq.vista.R

class UpdateSettingsFragment : BasePreferenceFragment() {
    private val updatePreferenceChange = Preference.OnPreferenceChangeListener { p: Preference?, nVal: Any ->
        val checkForUpdates = nVal as Boolean
        defaultPreferences.edit().putBoolean(getString(R.string.update_app_key), checkForUpdates).apply()

        if (checkForUpdates) enqueueNewVersionCheckingWork(requireContext(), true)
        true
    }

    private val manualUpdateClick = Preference.OnPreferenceClickListener { preference: Preference? ->
        Toast.makeText(context, R.string.checking_updates_toast, Toast.LENGTH_SHORT).show()
        enqueueNewVersionCheckingWork(requireContext(), true)
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
        findPreference<Preference>(getString(R.string.update_app_key))?.setOnPreferenceChangeListener(updatePreferenceChange)
        findPreference<Preference>(getString(R.string.manual_update_key))?.setOnPreferenceClickListener(manualUpdateClick)
    }
}
