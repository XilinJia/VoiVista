package ac.mdiq.vista.ui.dialog

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.Window
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import ac.mdiq.vista.database.VoiVistaDatabase.getInstance
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.manager.LocalPlaylistManager
import ac.mdiq.vista.player.PlayerManager
import ac.mdiq.vista.player.playqueue.PlayQueue
import ac.mdiq.vista.player.playqueue.PlayQueueItem
import ac.mdiq.vista.util.StateSaver.WriteRead
import ac.mdiq.vista.util.StateSaver.onDestroy
import ac.mdiq.vista.util.StateSaver.tryToRestore
import ac.mdiq.vista.util.StateSaver.tryToSave
import java.util.*
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.Stream

abstract class PlaylistDialog : DialogFragment(), WriteRead {
    @JvmField
    var onDismissListener: DialogInterface.OnDismissListener? = null

    var streamEntities: List<StreamEntity>? = null
        protected set

    private var savedState: ac.mdiq.vista.util.SavedState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedState = tryToRestore(savedInstanceState, this)
    }

    override fun onDestroy() {
        super.onDestroy()
        onDestroy(savedState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        //remove title
        val window = dialog.window
        window?.requestFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (onDismissListener != null) {
            onDismissListener!!.onDismiss(dialog)
        }
    }

    override fun generateSuffix(): String {
        val size = if (streamEntities == null) 0 else streamEntities!!.size
        return ".$size.list"
    }

    override fun writeTo(objectsToSave: Queue<Any>?) {
        objectsToSave!!.add(streamEntities)
    }

    override fun readFrom(savedObjects: Queue<Any>) {
        streamEntities = savedObjects.poll() as List<StreamEntity>
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (activity != null) {
            savedState = tryToSave(requireActivity().isChangingConfigurations, savedState, outState, this)
        }
    }

    companion object {
        /**
         * Creates a [PlaylistAppendDialog] when playlists exists,
         * otherwise a [PlaylistCreationDialog].
         *
         * @param context        context used for accessing the database
         * @param streamEntities used for crating the dialog
         * @param onExec         execution that should occur after a dialog got created, e.g. showing it
         * @return the disposable that was created
         */
        fun createCorrespondingDialog(context: Context?, streamEntities: List<StreamEntity>?, onExec: Consumer<PlaylistDialog>): Disposable {
            return LocalPlaylistManager(getInstance(context!!))
                .hasPlaylists()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { hasPlaylists: Boolean ->
                    onExec.accept(if (hasPlaylists) PlaylistAppendDialog.newInstance(streamEntities)
                    else PlaylistCreationDialog.newInstance(streamEntities))
                }
        }

        /**
         * Creates a [PlaylistAppendDialog] when playlists exists,
         * otherwise a [PlaylistCreationDialog]. If the player's play queue is null or empty, no
         * dialog will be created.
         *
         * @param playerManager          the player from which to extract the context and the play queue
         * @param fragmentManager the fragment manager to use to show the dialog
         * @return the disposable that was created
         */
        @JvmStatic
        fun showForPlayQueue(playerManager: PlayerManager, fragmentManager: FragmentManager): Disposable {
            val streamEntities = Stream.of(playerManager.playQueue)
                .filter { obj: PlayQueue? -> Objects.nonNull(obj) }
                .flatMap { playQueue: PlayQueue? -> playQueue!!.streams.stream() }
                .map { item: PlayQueueItem? -> StreamEntity(item!!) }
                .collect(Collectors.toList())
            if (streamEntities.isEmpty()) return Disposable.empty()

            return createCorrespondingDialog(playerManager.context, streamEntities) {
                dialog: PlaylistDialog -> dialog.show(fragmentManager, "PlaylistDialog") }
        }
    }
}
