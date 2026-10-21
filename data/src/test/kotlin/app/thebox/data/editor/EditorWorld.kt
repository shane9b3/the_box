package app.thebox.data.editor

import app.thebox.core.common.CredentialFailure
import app.thebox.core.common.Outcome
import app.thebox.core.common.SourceError
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.WriteResult
import app.thebox.core.parental.LockoutPolicy
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.guide.exec
import app.thebox.data.parental.AttemptsStore
import app.thebox.data.parental.LockedChannelIndex
import app.thebox.data.parental.ParentalPinStoreImpl
import app.thebox.data.parental.ParentalSessionImpl
import app.thebox.data.parental.TuneGuardImpl
import app.thebox.data.security.KeyResetFlag
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newChannel
import app.thebox.data.testutil.newGroup
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.testutil.strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The channel editor's data graph on a real `library.db` (Robolectric, `AndroidSQLiteDriver`): the repository with the
 * real [ParentalSessionImpl] behind `ParentalControls`, one playlist [p], and builders keyed by stable key / group key.
 */
class EditorWorld : AutoCloseable {
    val db: LibraryDatabase = TestDb.create()
    val clock = FakeClock()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val settings = FakeSettingsStore()
    private val flag = object : KeyResetFlag {
        var lost = false
        override fun markPinLost() { lost = true }
        override fun pinLost() = lost
        override fun clearPinLost() { lost = false }
    }
    private val blobs = object : SecureBlobStore {
        val map = HashMap<String, ByteArray>()
        override suspend fun putBlob(ref: String, bytes: ByteArray): Outcome<Unit> { map[ref] = bytes.copyOf(); return Outcome.Ok(Unit) }
        override suspend fun getBlob(ref: String): Outcome<ByteArray> =
            map[ref]?.let { Outcome.Ok(it.copyOf()) } ?: Outcome.Err(SourceError.Credentials(CredentialFailure.MISSING))
        override suspend fun deleteBlob(ref: String) { map.remove(ref) }
    }
    private val attempts = object : AttemptsStore {
        var a = LockoutPolicy.Attempts()
        override fun read() = a
        override fun write(a: LockoutPolicy.Attempts) { this.a = a }
    }
    val pins = ParentalPinStoreImpl(blobs, attempts, { w -> LockoutPolicy.Clock(0, w, 1) }, flag, Dispatchers.Unconfined).also { it.iterations = 100 }
    val index = LockedChannelIndex(db, scope, Dispatchers.Unconfined)
    val session = ParentalSessionImpl(TestDb.context, settings, pins, flag, index, db, db.groupDao(), clock, scope, Dispatchers.Unconfined)
    val guard by lazy { TuneGuardImpl(index, session) }
    val repo = ChannelEditorRepositoryImpl(db, session, clock, Dispatchers.IO)
    val p: Long = runBlocking { db.newPlaylist("P") }
    val groupIds = HashMap<String, Long>()

    /** A PIN is set and the session is locked (LOCKED_AUTO): `refusesLowering()` is true. */
    fun lockSession() = runBlocking { pins.set(charArrayOf('2', '5', '8', '0')); session.refresh() }

    suspend fun group(key: String, name: String = key, sort: Int = groupIds.size, adult: Boolean = false, playlist: Long = p): Long {
        val id = db.newGroup(playlist, key, name, adult)
        db.exec("UPDATE channel_group SET sort_index = ? WHERE id = ?", sort, id)
        if (playlist == p) groupIds[key] = id
        return id
    }

    suspend fun channel(
        key: String, group: String?, sort: Int, name: String = key.uppercase(), number: Int? = null, adult: Boolean = false,
        missing: Long? = null, kind: Int = 0,
    ): Long = db.newChannel(p, key, name, groupId = group?.let { groupIds.getValue(it) }, sortIndex = sort, number = number, adult = adult, kind = kind, missingSince = missing)

    suspend fun keysOf(ids: LongArray): List<String> = ids.map { id -> db.strings("SELECT stable_key FROM channel WHERE id = ?", id).single() }

    suspend fun list(list: EditorList, filter: EditorFilter = EditorFilter.ALL, access: ParentalAccess = ParentalAccess.OPEN): List<String> =
        keysOf(repo.channels(p, list, filter, access).ids)

    suspend fun write(w: EditorWrite, access: ParentalAccess = ParentalAccess.OPEN): WriteResult = repo.write(w, access)

    /** A `channel_prefs` column as text ("null" for NULL, "none" without a row). */
    suspend fun pref(key: String, column: String): String =
        db.strings("SELECT $column FROM channel_prefs WHERE playlist_id = ? AND stable_key = ?", p, key).singleOrNull() ?: "none"

    suspend fun groupPref(key: String, column: String): String =
        db.strings("SELECT $column FROM group_prefs WHERE playlist_id = ? AND kind = 0 AND group_key = ?", p, key).singleOrNull() ?: "none"

    override fun close() {
        scope.cancel()
        db.close()
    }
}
