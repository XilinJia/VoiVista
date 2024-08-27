package ac.mdiq.vista.ui.views

import android.R
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import ac.mdiq.vista.ui.util.VistaTextViewHelper.shareSelectedTextWithShareUtils

/**
 * An [AppCompatEditText] which uses [ShareUtils.shareText]
 * when sharing selected text by using the `Share` command of the floating actions.
 *
 * This class allows Vista to show Android share sheet instead of EMUI share sheet when sharing
 * text from [AppCompatEditText] on EMUI devices.
 *
 */
class VistaEditText : AppCompatEditText {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == R.id.shareText) {
            shareSelectedTextWithShareUtils(this)
            return true
        }
        return super.onTextContextMenuItem(id)
    }
}
