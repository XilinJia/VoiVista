package ac.mdiq.vista.ui.util

import android.text.Selection
import android.text.Spannable
import android.widget.TextView
import ac.mdiq.vista.ui.util.ShareUtils.shareText

object VistaTextViewHelper {
    /**
     * Share the selected text of [VoiVistaTextViews][VistaTextView] and
     * [VoiVistaEditTexts][VistaEditText] with [ShareUtils.shareText].
     *
     * This allows EMUI users to get the Android share sheet instead of the EMUI share sheet when
     * using the `Share` command of the popup menu which appears when selecting text.
     *
     * @param textView the [TextView] on which sharing the selected text. It should be a
     * [VistaTextView] or a [VistaEditText] (even if
     * [standard TextViews][TextView] are supported).
     */
    fun shareSelectedTextWithShareUtils(textView: TextView) {
        val textViewText = textView.text
        shareSelectedTextIfNotNullAndNotEmpty(textView, getSelectedText(textView, textViewText))
        if (textViewText is Spannable) Selection.setSelection(textViewText, textView.selectionEnd)
    }

    private fun getSelectedText(textView: TextView, text: CharSequence?): CharSequence? {
        if (!textView.hasSelection() || text == null) return null

        val start = textView.selectionStart
        val end = textView.selectionEnd
        return (if (start > end) text.subSequence(end, start)
        else text.subSequence(start, end)).toString()
    }

    private fun shareSelectedTextIfNotNullAndNotEmpty(textView: TextView, selectedText: CharSequence?) {
        if (!selectedText.isNullOrEmpty()) shareText(textView.context, "", selectedText.toString())
    }
}
