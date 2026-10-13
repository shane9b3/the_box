package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Title-only channel search. A standalone FTS4 table (S6), NOT `contentEntity = ChannelEntity`: Room's
 * content-sync triggers fire on every channel UPDATE, so each ChannelTouch would rewrite FTS rows.
 * RoomMergeSink maintains it: insert on channel insert, delete+insert when the name changes, delete on
 * purge. rowid = channel.id.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=2"])
@Entity(tableName = "channel_fts")
data class ChannelFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val name: String,
)
