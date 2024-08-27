package ac.mdiq.vista.util

import android.content.Context
import androidx.preference.PreferenceManager
import ac.mdiq.vista.R
import java.util.regex.Matcher
import java.util.regex.Pattern

object FilenameUtils {
    private const val CHARSET_MOST_SPECIAL = "[\\n\\r|?*<\":\\\\>/']+"
    private const val CHARSET_ONLY_LETTERS_AND_DIGITS = "[^\\w\\d]+"

    /**
     * #143 #44 #42 #22: make sure that the filename does not contain illegal chars.
     *
     * @param context the context to retrieve strings and preferences from
     * @param title   the title to create a filename from
     * @return the filename
     */
    @JvmStatic
    fun createFilename(context: Context, title: String): String {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        val charsetLd = context.getString(R.string.charset_letters_and_digits_value)
        val charsetMs = context.getString(R.string.charset_most_special_value)
        val defaultCharset = context.getString(R.string.default_file_charset_value)

        val replacementChar = sharedPreferences.getString(context.getString(R.string.settings_file_replacement_character_key), "_")
        var selectedCharset = sharedPreferences.getString(context.getString(R.string.settings_file_charset_key), null)

        if (selectedCharset.isNullOrEmpty()) selectedCharset = defaultCharset

        val charset = when (selectedCharset) {
            charsetLd -> CHARSET_ONLY_LETTERS_AND_DIGITS
            charsetMs -> CHARSET_MOST_SPECIAL
            else ->  selectedCharset /* Is the user using a custom charset? */
        }

        val pattern = Pattern.compile(charset)

        return createFilename(title, pattern, Matcher.quoteReplacement(replacementChar!!))
    }

    /**
     * Create a valid filename.
     *
     * @param title             the title to create a filename from
     * @param invalidCharacters patter matching invalid characters
     * @param replacementChar   the replacement
     * @return the filename
     */
    private fun createFilename(title: String, invalidCharacters: Pattern, replacementChar: String): String {
        return title.replace(invalidCharacters.pattern().toRegex(), replacementChar)
    }
}
