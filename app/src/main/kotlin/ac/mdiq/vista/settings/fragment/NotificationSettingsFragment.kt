package ac.mdiq.vista.settings.fragment

import android.os.Bundle

class NotificationSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()
    }
}
