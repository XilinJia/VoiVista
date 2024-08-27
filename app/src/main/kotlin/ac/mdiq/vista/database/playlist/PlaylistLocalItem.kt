package ac.mdiq.vista.database.playlist

import ac.mdiq.vista.database.LocalItem
import ac.mdiq.vista.database.playlist.model.PlaylistRemoteEntity
import java.util.stream.Collectors
import java.util.stream.Stream

interface PlaylistLocalItem : LocalItem {

    fun getOrderingName(): String?

    companion object {
        fun merge(localPlaylists: List<PlaylistMetadataEntry>, remotePlaylists: List<PlaylistRemoteEntity>): List<PlaylistLocalItem> {
            return Stream.concat(localPlaylists.stream(), remotePlaylists.stream())
                .sorted(Comparator.comparing({ obj: PlaylistLocalItem -> obj.getOrderingName() },
                    Comparator.nullsLast(java.lang.String.CASE_INSENSITIVE_ORDER)))
                .collect(Collectors.toList())
        }
    }
}
