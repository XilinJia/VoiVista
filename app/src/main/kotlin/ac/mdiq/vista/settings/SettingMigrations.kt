package ac.mdiq.vista.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import ac.mdiq.vista.R
import ac.mdiq.vista.util.error.ErrorInfo
import ac.mdiq.vista.util.error.ErrorUtil.Companion.openActivity
import ac.mdiq.vista.util.UserAction
import ac.mdiq.vista.util.DeviceUtils.isFireTv
import ac.mdiq.vista.util.Logd
import java.util.*

/**
 * In order to add a migration, follow these steps, given P is the previous version:<br></br>
 * - in the class body add a new `MIGRATION_P_P+1 = new Migration(P, P+1) { ... }` and put in
 * the `migrate()` method the code that need to be run when migrating from P to P+1<br></br>
 * - add `MIGRATION_P_P+1` at the end of [SettingMigrations.SETTING_MIGRATIONS]<br></br>
 * - increment [SettingMigrations.VERSION]'s value by 1 (so it should become P+1)
 */
object SettingMigrations {
    private val TAG = SettingMigrations::class.java.toString()
    private var sp: SharedPreferences? = null

    private val MIGRATION_0_1: Migration = object : Migration(0, 1) {
        override fun migrate(context: Context) {
            // We changed the content of the dialog which opens when sharing a link to Vista
            // by removing the "open detail page" option.
            // Therefore, show the dialog once again to ensure users need to choose again and are
            // aware of the changed dialog.
            val editor = sp!!.edit()
            editor.putString(context.getString(R.string.preferred_open_action_key), context.getString(R.string.always_ask_open_action_key))
            editor.apply()
        }
    }

    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(context: Context) {
            // The new application workflow introduced in #2907 allows minimizing videos
            // while playing to do other stuff within the app.
            // For an even better workflow, we minimize a stream when switching the app to play in
            // background.
            // Therefore, set default value to background, if it has not been changed yet.
            val minimizeOnExitKey = context.getString(R.string.minimize_on_exit_key)
            if (sp!!.getString(minimizeOnExitKey, "") == context.getString(R.string.minimize_on_exit_none_key)) {
                val editor = sp!!.edit()
                editor.putString(minimizeOnExitKey, context.getString(R.string.minimize_on_exit_background_key))
                editor.apply()
            }
        }
    }

    private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(context: Context) {
            // Storage Access Framework implementation was improved in #5415, allowing the modern
            // and standard way to access folders and files to be used consistently everywhere.
            // We reset the setting to its default value, i.e. "use SAF", since now there are no
            // more issues with SAF and users should use that one instead of the old
            // NoNonsenseFilePicker. Also, there's a bug on FireOS in which SAF open/close
            // dialogs cannot be confirmed with a remote (see #6455).
            sp!!.edit().putBoolean(context.getString(R.string.storage_use_saf), !isFireTv).apply()
        }
    }

    private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(context: Context) {
            // Pull request #3546 added support for choosing the type of search suggestions to
            // show, replacing the on-off switch used before, so migrate the previous user choice

            val showSearchSuggestionsKey = context.getString(R.string.show_search_suggestions_key)
            var addAllSearchSuggestionTypes = try {
                sp!!.getBoolean(showSearchSuggestionsKey, true)
            } catch (e: ClassCastException) {
                // just in case it was not a boolean for some reason, let's consider it a "true"
                true
            }

            val showSearchSuggestionsValueList: MutableSet<String> = HashSet()
            if (addAllSearchSuggestionTypes) {
                // if the preference was true, all suggestions will be shown, otherwise none
                val searchSuggestionsArray = context.resources.getStringArray(R.array.show_search_suggestions_value_list)
                showSearchSuggestionsValueList.addAll(searchSuggestionsArray)
//                Collections.addAll(showSearchSuggestionsValueList, searchSuggestionsArray)
            }

            sp!!.edit().putStringSet(showSearchSuggestionsKey, showSearchSuggestionsValueList).apply()
        }
    }

    private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(context: Context) {
            val brightness = sp!!.getBoolean("brightness_gesture_control", true)
            val volume = sp!!.getBoolean("volume_gesture_control", true)
            val editor = sp!!.edit()
            editor.putString(context.getString(R.string.right_gesture_control_key), context.getString(if (volume) R.string.volume_control_key else R.string.none_control_key))
            editor.putString(context.getString(R.string.left_gesture_control_key), context.getString(if (brightness) R.string.brightness_control_key else R.string.none_control_key))
            editor.apply()
        }
    }

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(context: Context) {
            val loadImages = sp!!.getBoolean("download_thumbnail_key", true)
            sp!!.edit().putString(context.getString(R.string.image_quality_key), context.getString(if (loadImages) R.string.image_quality_default else R.string.image_quality_none_key)).apply()
        }
    }

    /**
     * List of all implemented migrations.
     *
     *
     * **Append new migrations to the end of the list** to keep it sorted ascending.
     * If not sorted correctly, migrations which depend on each other, may fail.
     */
    private val SETTING_MIGRATIONS = arrayOf(
        MIGRATION_0_1,
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
    )

    /**
     * Version number for preferences. Must be incremented every time a migration is necessary.
     */
    private const val VERSION = 6


    fun runMigrationsIfNeeded(context: Context, isFirstRun: Boolean) {
        // setup migrations and check if there is something to do
        sp = PreferenceManager.getDefaultSharedPreferences(context)
        val lastPrefVersionKey = context.getString(R.string.last_used_preferences_version)
        val lastPrefVersion = sp?.getInt(lastPrefVersionKey, 0) ?: 0

        // no migration to run, already up to date
        if (isFirstRun) {
            sp?.edit()?.putInt(lastPrefVersionKey, VERSION)?.apply()
            return
        } else if (lastPrefVersion == VERSION) return


        // run migrations
        var currentVersion = lastPrefVersion
        for (currentMigration in SETTING_MIGRATIONS) {
            try {
                if (currentMigration.shouldMigrate(currentVersion)) {
                    Logd(TAG, "Migrating preferences from version $currentVersion to ${currentMigration.newVersion}")
                    currentMigration.migrate(context)
                    currentVersion = currentMigration.newVersion
                }
            } catch (e: Exception) {
                // save the version with the last successful migration and report the error
                sp?.edit()?.putInt(lastPrefVersionKey, currentVersion)?.apply()
                openActivity(context, ErrorInfo(e, UserAction.PREFERENCES_MIGRATION,
                    "Migrating preferences from version $lastPrefVersion to $VERSION. Error at $currentVersion => ${++currentVersion}"))
                return
            }
        }
        // store the current preferences version
        sp?.edit()?.putInt(lastPrefVersionKey, currentVersion)?.apply()
    }

    abstract class Migration protected constructor(val oldVersion: Int, val newVersion: Int) {
        /**
         * @param currentVersion current settings version
         * @return Returns whether this migration should be run.
         * A migration is necessary if the old version of this migration is lower than or equal to
         * the current settings version.
         */
        fun shouldMigrate(currentVersion: Int): Boolean {
            return oldVersion >= currentVersion
        }

        abstract fun migrate(context: Context)
    }
}
