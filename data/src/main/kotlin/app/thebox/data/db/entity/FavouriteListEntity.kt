package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favourite_list")
data class FavouriteListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
