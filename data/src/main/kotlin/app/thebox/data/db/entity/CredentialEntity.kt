package app.thebox.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** `credential`: AEAD(JSON(PlaylistSecret)) with associated data = [ref] (§2.1.2, §3.9). */
@Entity(tableName = "credential")
data class CredentialEntity(
    /** "cred-<uuid>" */
    @PrimaryKey val ref: String,
    val ciphertext: ByteArray,
    @ColumnInfo(name = "key_version", defaultValue = "1") val keyVersion: Int = 1,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is CredentialEntity && ref == other.ref && ciphertext.contentEquals(other.ciphertext) &&
            keyVersion == other.keyVersion && updatedAt == other.updatedAt

    override fun hashCode(): Int = ref.hashCode() * 31 + ciphertext.contentHashCode()
}
