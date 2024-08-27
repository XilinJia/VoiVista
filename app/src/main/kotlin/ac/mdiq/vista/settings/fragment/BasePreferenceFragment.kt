package ac.mdiq.vista.settings.fragment

import ac.mdiq.vista.settings.SettingsResourceRegistry
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import ac.mdiq.vista.ui.activity.MainActivity
import ac.mdiq.vista.ui.util.ThemeHelper.setTitleToAppCompatActivity
import java.util.*

abstract class BasePreferenceFragment : PreferenceFragmentCompat() {
    protected val TAG: String = javaClass.simpleName + "@" + Integer.toHexString(hashCode())
    lateinit var defaultPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        defaultPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        super.onCreate(savedInstanceState)
    }

    protected fun addPreferencesFromResourceRegistry() {
        addPreferencesFromResource(SettingsResourceRegistry.instance.getPreferencesResId(this.javaClass))
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootView, savedInstanceState)
        setDivider(null)
        setTitleToAppCompatActivity(activity, preferenceScreen.title)
    }

    override fun onResume() {
        super.onResume()
        setTitleToAppCompatActivity(activity, preferenceScreen.title)
    }

    fun requirePreference(@StringRes resId: Int): Preference {
        val preference = findPreference<Preference>(getString(resId))
        Objects.requireNonNull(preference)
        return preference!!
    }

    companion object {
        @JvmField
        val DEBUG: Boolean = MainActivity.DEBUG
    }
}
