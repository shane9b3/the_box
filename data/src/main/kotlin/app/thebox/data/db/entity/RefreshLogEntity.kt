package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Refresh and import history; newest 30 rows per (target_type, target_id), trimmed with each insert. */
@Entity(
    tableName = "refresh_log",
    indices = [Index(value = ["target_type", "target_id", "started_at"], name = "index_refresh_log_target")],
)
data class RefreshLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** PLAYLIST | EPG_BUILD | EPG_PATCH */
    @ColumnInfo(name = "target_type") val targetType: String,
    /** playlist.id; null for EPG builds. */
    @ColumnInfo(name = "target_id") val targetId: Long?,
    /** PERIODIC | APP_START | MANUAL | ONBOARDING | MAPPING | PLAYLIST_CHANGED */
    val trigger: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    /** RefreshOutcome */
    val outcome: String,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    /** Redact.text(...) */
    @ColumnInfo(name = "error_detail") val errorDetail: String? = null,
    val bytes: Long? = null,
    /** RefreshReport | EpgImportReport JSON. */
    @ColumnInfo(name = "report_json") val reportJson: String? = null,
    /** S15: failures persist until acknowledged or until a later run of the same target succeeds (2026-11-02). */
    @ColumnInfo(defaultValue = "0") val acknowledged: Boolean = false,
) {
    companion object {
        const val TARGET_PLAYLIST = "PLAYLIST"
        const val TARGET_EPG_BUILD = "EPG_BUILD"
        const val TARGET_EPG_PATCH = "EPG_PATCH"
        const val KEEP_PER_TARGET = 30
    }
}
