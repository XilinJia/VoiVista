package ac.mdiq.vista.database.stream

import androidx.room.ColumnInfo
import androidx.room.Embedded
import ac.mdiq.vista.database.stream.model.StreamEntity
import ac.mdiq.vista.database.stream.model.StreamStateEntity

data class StreamWithState(
    @Embedded
    val stream: StreamEntity,
    @ColumnInfo(name = StreamStateEntity.STREAM_PROGRESS_MILLIS)
    val stateProgressMillis: Long?,
)
