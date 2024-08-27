package ac.mdiq.vista.ui.gesture

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import ac.mdiq.vista.R
import ac.mdiq.vista.databinding.PlaylistControlBinding
import ac.mdiq.vista.ui.holder.PlaylistControlViewHolder
import ac.mdiq.vista.player.PlayerType
import ac.mdiq.vista.ui.util.NavigationHelper.enqueueOnPlayer
import ac.mdiq.vista.ui.util.NavigationHelper.playOnBackgroundPlayer
import ac.mdiq.vista.ui.util.NavigationHelper.playOnMainPlayer
import ac.mdiq.vista.ui.util.NavigationHelper.playOnPopupPlayer

/**
 * Utility class for play buttons and their respective click listeners.
 */
object PlayButtonHelper {
    /**
     * Initialize [OnClickListener][android.view.View.OnClickListener]
     * and [OnLongClickListener][android.view.View.OnLongClickListener] for playlist control
     * buttons defined in [R.layout.playlist_control].
     *
     * @param activity The activity to use for the [Toast][android.widget.Toast].
     * @param playlistControlBinding The binding of the
     * [playlist control layout][R.layout.playlist_control].
     * @param fragment The fragment to get the play queue from.
     */
    @JvmStatic
    fun initPlaylistControlClickListener(activity: AppCompatActivity, playlistControlBinding: PlaylistControlBinding, fragment: PlaylistControlViewHolder) {
        // click listener
        playlistControlBinding.playlistCtrlPlayAllButton.setOnClickListener {
            if (fragment.playQueue != null) playOnMainPlayer(activity, fragment.playQueue!!)
            showHoldToAppendToastIfNeeded(activity)
        }
        playlistControlBinding.playlistCtrlPlayPopupButton.setOnClickListener {
            playOnPopupPlayer(activity, fragment.playQueue, false)
            showHoldToAppendToastIfNeeded(activity)
        }
        playlistControlBinding.playlistCtrlPlayBgButton.setOnClickListener {
            playOnBackgroundPlayer(activity, fragment.playQueue, false)
            showHoldToAppendToastIfNeeded(activity)
        }

        // long click listener
        playlistControlBinding.playlistCtrlPlayPopupButton.setOnLongClickListener {
            enqueueOnPlayer(activity, fragment.playQueue, PlayerType.POPUP)
            true
        }
        playlistControlBinding.playlistCtrlPlayBgButton.setOnLongClickListener {
            enqueueOnPlayer(activity, fragment.playQueue, PlayerType.AUDIO)
            true
        }
    }

    /**
     * Show the "hold to append" toast if the corresponding preference is enabled.
     * @param context The context to show the toast.
     */
    private fun showHoldToAppendToastIfNeeded(context: Context) {
        if (shouldShowHoldToAppendTip(context)) Toast.makeText(context, R.string.hold_to_append, Toast.LENGTH_SHORT).show()
    }

    /**
     * Check if the "hold to append" toast should be shown.
     * The tip is shown if the corresponding preference is enabled.
     * This is the default behaviour.
     * @param context The context to get the preference.
     * @return `true` if the tip should be shown, `false` otherwise.
     */
    @JvmStatic
    fun shouldShowHoldToAppendTip(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.show_hold_to_append_key), true)
    }
}
