package app.thebox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.thebox.data.db.entity.CredentialEntity

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credential WHERE ref = :ref")
    suspend fun get(ref: String): CredentialEntity?

    @Upsert
    suspend fun upsert(credential: CredentialEntity)

    @Query("DELETE FROM credential WHERE ref = :ref")
    suspend fun delete(ref: String)

    /** Permanent key loss (§3.9): every row is undecryptable. */
    @Query("DELETE FROM credential")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM credential")
    suspend fun count(): Int
}
