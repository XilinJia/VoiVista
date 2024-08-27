package ac.mdiq.vista.settings.fragment

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import ac.mdiq.vista.R
import ac.mdiq.vista.database.VoiVistaDatabase
import ac.mdiq.vista.util.error.ErrorUtil.Companion.showUiErrorSnackbar
import ac.mdiq.vista.extractor.Vista
import ac.mdiq.vista.extractor.localization.ContentCountry
import ac.mdiq.vista.extractor.localization.Localization
import ac.mdiq.vista.giga.DownloaderImpl.Companion.instance
import ac.mdiq.vista.giga.io.NoFileManagerSafeGuard.launchSafe
import ac.mdiq.vista.giga.io.StoredFileHelper
import ac.mdiq.vista.giga.io.StoredFileHelper.Companion.getNewPicker
import ac.mdiq.vista.giga.io.StoredFileHelper.Companion.getPicker
import ac.mdiq.vista.settings.ContentSettingsManager
import ac.mdiq.vista.settings.VistaFileLocator
import ac.mdiq.vista.settings.VistaSettings
import ac.mdiq.vista.ui.util.NavigationHelper.restartApp
import ac.mdiq.vista.util.Localization.assureCorrectAppLanguage
import ac.mdiq.vista.util.Localization.getPreferredContentCountry
import ac.mdiq.vista.util.Localization.getPreferredLocalization
import ac.mdiq.vista.util.ZipHelper.isValidZipFile
import ac.mdiq.vista.util.image.ImageStrategy.setPreferredImageQuality
import ac.mdiq.vista.util.image.PicassoHelper.clearCache
import ac.mdiq.vista.util.image.PreferredImageQuality.Companion.fromPreferenceKey
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ContentSettingsFragment : BasePreferenceFragment() {
    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var manager: ContentSettingsManager? = null

    private var importExportDataPathKey: String? = null
    private var youtubeRestrictedModeEnabledKey: String? = null

    private var initialSelectedLocalization: Localization? = null
    private var initialSelectedContentCountry: ContentCountry? = null
    private var initialLanguage: String? = null
    private val requestImportPathLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.requestImportPathResult(result)
    }
    private val requestExportPathLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        this.requestExportPathResult(result)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val homeDir = ContextCompat.getDataDir(requireContext())
        Objects.requireNonNull(homeDir)
        manager = ContentSettingsManager(VistaFileLocator(homeDir!!))
        manager!!.deleteSettingsFile()

        importExportDataPathKey = getString(R.string.import_export_data_path)
        youtubeRestrictedModeEnabledKey = getString(R.string.youtube_restricted_mode_enabled)

        addPreferencesFromResourceRegistry()

        val importDataPreference = requirePreference(R.string.import_data)
        importDataPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            launchSafe(requestImportPathLauncher, getPicker(requireContext(), ZIP_MIME_TYPE, importExportDataUri), TAG, context)
            true
        }

        val exportDataPreference = requirePreference(R.string.export_data)
        exportDataPreference.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            launchSafe(requestExportPathLauncher, getNewPicker(requireContext(), "VoiVistaData-" + exportDateFormat.format(Date()) + ".zip",
                ZIP_MIME_TYPE, importExportDataUri), TAG, context)
            true
        }

        initialSelectedLocalization = getPreferredLocalization(requireContext())
        initialSelectedContentCountry = getPreferredContentCountry(requireContext())
        initialLanguage = defaultPreferences.getString(getString(R.string.app_language_key), "en")

        val imageQualityPreference = requirePreference(R.string.image_quality_key)
        imageQualityPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference: Preference, newValue: Any? ->
                setPreferredImageQuality(fromPreferenceKey(requireContext(), (newValue as String?)!!))
                try {
                    clearCache(preference.context)
                    Toast.makeText(preference.context, R.string.thumbnail_cache_wipe_complete_notice, Toast.LENGTH_SHORT).show()
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to clear Picasso cache", e)
                }
                true
            }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == youtubeRestrictedModeEnabledKey) {
            val context = context
            if (context != null) instance!!.updateYoutubeRestrictedModeCookies(context)
            else Log.w(TAG, "onPreferenceTreeClick: null context")
        }

        return super.onPreferenceTreeClick(preference)
    }

    override fun onDestroy() {
        super.onDestroy()

        val selectedLocalization = getPreferredLocalization(requireContext())
        val selectedContentCountry = getPreferredContentCountry(requireContext())
        val selectedLanguage = defaultPreferences.getString(getString(R.string.app_language_key), "en")

        if (selectedLocalization != initialSelectedLocalization || selectedContentCountry != initialSelectedContentCountry
                || selectedLanguage != initialLanguage) {
            Toast.makeText(requireContext(), R.string.localization_changes_requires_app_restart, Toast.LENGTH_LONG).show()
            Vista.setupLocalization(selectedLocalization, selectedContentCountry)
        }
    }

    private fun requestExportPathResult(result: ActivityResult) {
        assureCorrectAppLanguage(context!!)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // will be saved only on success
            val lastExportDataUri = result.data!!.data
            val file = StoredFileHelper(context, result.data!!.data!!, ZIP_MIME_TYPE)
            exportDatabase(file, lastExportDataUri)
        }
    }

    private fun requestImportPathResult(result: ActivityResult) {
        assureCorrectAppLanguage(context!!)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // will be saved only on success
            val lastImportDataUri = result.data!!.data
            val file = StoredFileHelper(context, result.data!!.data!!, ZIP_MIME_TYPE)
            AlertDialog.Builder(requireActivity())
                .setMessage(R.string.override_current_data)
                .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int -> importDatabase(file, lastImportDataUri) }
                .setNegativeButton(R.string.cancel) { d: DialogInterface, _: Int -> d.cancel() }
                .show()
        }
    }

    private fun exportDatabase(file: StoredFileHelper, exportDataUri: Uri?) {
        try {
            //checkpoint before export
            VoiVistaDatabase.checkpoint()
            val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            manager!!.exportDatabase(preferences, file)
            saveLastImportExportDataUri(exportDataUri) // save export path only on success
            Toast.makeText(context, R.string.export_complete_toast, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Exporting database", e)
        }
    }

    private fun importDatabase(file: StoredFileHelper, importDataUri: Uri?) {
        // check if file is supported
        if (!isValidZipFile(file)) {
            Toast.makeText(context, R.string.no_valid_zip_file, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (!manager!!.ensureDbDirectoryExists()) throw IOException("Could not create databases dir")
            if (!manager!!.extractDb(file)) Toast.makeText(context, R.string.could_not_import_all_files, Toast.LENGTH_LONG).show()

            // if settings file exist, ask if it should be imported.
            if (manager!!.extractSettings(file)) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.import_settings)
                    .setNegativeButton(R.string.cancel) { dialog: DialogInterface, _: Int ->
                        dialog.dismiss()
                        finishImport(importDataUri)
                    }
                    .setPositiveButton(R.string.ok) { dialog: DialogInterface, _: Int ->
                        dialog.dismiss()
                        val context = requireContext()
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        manager!!.loadSharedPreferences(prefs)
                        cleanImport(context, prefs)
                        finishImport(importDataUri)
                    }
                    .show()
            } else finishImport(importDataUri)
        } catch (e: Exception) {
            showUiErrorSnackbar(this, "Importing database", e)
        }
    }

    /**
     * Remove settings that are not supposed to be imported on different devices
     * and reset them to default values.
     * @param context the context used for the import
     * @param prefs the preferences used while running the import
     */
    private fun cleanImport(context: Context, prefs: SharedPreferences) {
        // Check if media tunnelling needs to be disabled automatically,
        // if it was disabled automatically in the imported preferences.
        val tunnelingKey = context.getString(R.string.disable_media_tunneling_key)
        val automaticTunnelingKey = context.getString(R.string.disabled_media_tunneling_automatically_key)
        // R.string.disable_media_tunneling_key should always be true
        // if R.string.disabled_media_tunneling_automatically_key equals 1,
        // but we double check here just to be sure and to avoid regressions
        // caused by possible later modification of the media tunneling functionality.
        // R.string.disabled_media_tunneling_automatically_key == 0:
        //     automatic value overridden by user in settings
        // R.string.disabled_media_tunneling_automatically_key == -1: not set
        val wasMediaTunnelingDisabledAutomatically = (prefs.getInt(automaticTunnelingKey, -1) == 1 && prefs.getBoolean(tunnelingKey, false))
        if (wasMediaTunnelingDisabledAutomatically) {
            prefs.edit().putInt(automaticTunnelingKey, -1).putBoolean(tunnelingKey, false).apply()
            VistaSettings.setMediaTunneling(context)
        }
    }

    /**
     * Save import path and restart system.
     *
     * @param importDataUri The import path to save
     */
    private fun finishImport(importDataUri: Uri?) {
        // save import path only on success
        saveLastImportExportDataUri(importDataUri)
        // restart app to properly load db
        restartApp(requireActivity())
    }

    private val importExportDataUri: Uri?
        get() {
            val path = defaultPreferences.getString(importExportDataPathKey, null)
            return if (path.isNullOrBlank()) null else Uri.parse(path)
        }

    private fun saveLastImportExportDataUri(importExportDataUri: Uri?) {
        val editor = defaultPreferences.edit().putString(importExportDataPathKey, importExportDataUri.toString())
        editor.apply()
    }

    companion object {
        private const val ZIP_MIME_TYPE = "application/zip"
    }
}
