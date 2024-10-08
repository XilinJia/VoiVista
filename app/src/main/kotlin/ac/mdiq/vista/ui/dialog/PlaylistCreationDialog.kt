package ac.mdiq.vista.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import ac.mdiq.vista.database.VoiVistaDatabase.getInstance
import ac.mdiq.vista.R
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.databinding.DialogEditTextBinding
import ac.mdiq.vista.manager.LocalPlaylistManager
import ac.mdiq.vista.ui.util.ThemeHelper.getDialogTheme

class PlaylistCreationDialog : PlaylistDialog() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        if (streamEntities == null) return super.onCreateDialog(savedInstanceState)

        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater)
        dialogBinding.root.context.setTheme(getDialogTheme(requireContext()))
        dialogBinding.dialogEditText.setHint(R.string.name)
        dialogBinding.dialogEditText.inputType = InputType.TYPE_CLASS_TEXT

        val dialogBuilder = AlertDialog.Builder(requireContext(),
            getDialogTheme(requireContext()))
            .setTitle(R.string.create_playlist)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _: DialogInterface?, _: Int ->
                val name = dialogBinding.dialogEditText.text.toString()
                val playlistManager = LocalPlaylistManager(getInstance(requireContext()))
                val successToast = Toast.makeText(activity, R.string.playlist_creation_success, Toast.LENGTH_SHORT)
                if (streamEntities != null) playlistManager.createPlaylist(name, streamEntities!!)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe { _: List<Long?>? -> successToast.show() }
            }
        return dialogBuilder.create()
    }

    companion object {
        /**
         * Create a new instance of [PlaylistCreationDialog].
         *
         * @param streamEntities    a list of [StreamEntity] to be added to playlists
         * @return a new instance of [PlaylistCreationDialog]
         */
        fun newInstance(streamEntities: List<StreamEntity>?): PlaylistCreationDialog {
            val dialog = PlaylistCreationDialog()
            dialog.streamEntities = streamEntities
            return dialog
        }
    }
}
