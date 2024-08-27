package ac.mdiq.vista.settings.fragment

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import ac.mdiq.vista.R
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.error.ErrorUtil.Companion.createNotification
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showUiErrorSnackbar
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.local.feed.notifications.NotificationWorker.Companion.runNow
import ac.mdiq.vista.util.image.PicassoHelper.setIndicatorsEnabled
import java.util.*

class DebugSettingsFragment : BasePreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResourceRegistry()

        val allowHeapDumpingPreference = findPreference<Preference>(getString(R.string.allow_heap_dumping_key))
        val showMemoryLeaksPreference = findPreference<Preference>(getString(R.string.show_memory_leaks_key))
        val showImageIndicatorsPreference = findPreference<Preference>(getString(R.string.show_image_indicators_key))
        val checkNewStreamsPreference = findPreference<Preference>(getString(R.string.check_new_streams_key))
        val crashTheAppPreference = findPreference<Preference>(getString(R.string.crash_the_app_key))
        val showErrorSnackbarPreference = findPreference<Preference>(getString(R.string.show_error_snackbar_key))
        val createErrorNotificationPreference = findPreference<Preference>(getString(R.string.create_error_notification_key))

        assert(allowHeapDumpingPreference != null)
        assert(showMemoryLeaksPreference != null)
        assert(showImageIndicatorsPreference != null)
        assert(checkNewStreamsPreference != null)
        assert(crashTheAppPreference != null)
        assert(showErrorSnackbarPreference != null)
        assert(createErrorNotificationPreference != null)
        val optBVLeakCanary = bVDLeakCanary

        allowHeapDumpingPreference!!.isEnabled = optBVLeakCanary.isPresent
        showMemoryLeaksPreference!!.isEnabled = optBVLeakCanary.isPresent

        if (optBVLeakCanary.isPresent) {
            val pdLeakCanary = optBVLeakCanary.get()
            showMemoryLeaksPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference: Preference? ->
                startActivity(pdLeakCanary.newLeakDisplayActivityIntent!!)
                true
            }
        } else {
            allowHeapDumpingPreference.setSummary(R.string.leak_canary_not_available)
            showMemoryLeaksPreference.setSummary(R.string.leak_canary_not_available)
        }

        showImageIndicatorsPreference!!.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference: Preference?, newValue: Any? ->
            setIndicatorsEnabled((newValue as Boolean?)!!)
            true
        }

        checkNewStreamsPreference!!.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference: Preference ->
            runNow(preference.context)
            true
        }

        crashTheAppPreference!!.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference: Preference? ->
            throw RuntimeException(DUMMY)
        }

        showErrorSnackbarPreference!!.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference: Preference? ->
            showUiErrorSnackbar(this@DebugSettingsFragment, DUMMY, RuntimeException(DUMMY))
            true
        }

        createErrorNotificationPreference!!.onPreferenceClickListener = Preference.OnPreferenceClickListener { preference: Preference? ->
            createNotification(requireContext(), ErrorInfo(RuntimeException(DUMMY), UserAction.UI_ERROR, DUMMY))
            true
        }
    }

    private val bVDLeakCanary: Optional<DebugSettingsBVDLeakCanaryAPI>
        /**
         * Tries to find the [DebugSettingsBVDLeakCanaryAPI.IMPL_CLASS] and loads it if available.
         * @return An [Optional] which is empty if the implementation class couldn't be loaded.
         */
        get() = try {
            // Try to find the implementation of the LeakCanary API
            Optional.of(Class.forName(DebugSettingsBVDLeakCanaryAPI.IMPL_CLASS)
                .getDeclaredConstructor()
                .newInstance() as DebugSettingsBVDLeakCanaryAPI)
        } catch (e: Exception) {
            Optional.empty()
        }

    /**
     * Build variant dependent (BVD) leak canary API for this fragment.
     * Why is LeakCanary not used directly? Because it can't be assured
     */
    interface DebugSettingsBVDLeakCanaryAPI {
        val newLeakDisplayActivityIntent: Intent?

        companion object {
            const val IMPL_CLASS: String = "ac.mdiq.vista.settings.DebugSettingsBVDLeakCanary"
        }
    }

    companion object {
        private const val DUMMY = "Dummy"
    }
}
