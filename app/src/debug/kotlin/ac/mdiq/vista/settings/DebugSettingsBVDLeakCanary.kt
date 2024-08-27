package ac.mdiq.vista.settings

import android.content.Intent
import leakcanary.LeakCanary.newLeakDisplayActivityIntent
import ac.mdiq.vista.settings.fragment.DebugSettingsFragment.DebugSettingsBVDLeakCanaryAPI

/**
 * Build variant dependent (BVD) leak canary API implementation for the debug settings fragment.
 * This class is loaded via reflection by
 * [DebugSettingsFragment.DebugSettingsBVDLeakCanaryAPI].
 */
@Suppress("unused") // Class is used but loaded via reflection
class DebugSettingsBVDLeakCanary : DebugSettingsBVDLeakCanaryAPI {
    override val newLeakDisplayActivityIntent: Intent
        get() = newLeakDisplayActivityIntent()
}
