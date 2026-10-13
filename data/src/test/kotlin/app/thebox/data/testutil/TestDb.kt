package app.thebox.data.testutil

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.source.PlaylistType
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.db.entity.ChannelEntity
import app.thebox.data.db.entity.PlaylistEntity
import app.thebox.data.di.WallClock
import app.thebox.data.repo.read
import app.thebox.data.repo.writeTransaction
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** library.db on Robolectric's framework SQLite through `AndroidSQLiteDriver` (§5.1), a fresh file per test. */
object TestDb {
    private val counter = AtomicInteger()

    val context: Context get() = ApplicationProvider.getApplicationContext()

    fun create(): LibraryDatabase {
        val name = "library-test-${counter.incrementAndGet()}-${System.nanoTime()}.db"
        context.getDatabasePath(name).delete()
        return LibraryDatabase.build(context, Dispatchers.IO, AndroidSQLiteDriver(), name)
    }
}

class FakeClock(start: Long = 1_760_000_000_000L) : WallClock {
    val time = AtomicLong(start)
    override fun now(): Long = time.get()
    fun advance(ms: Long) { time.addAndGet(ms) }
}

suspend fun LibraryDatabase.newPlaylist(
    name: String = "P",
    type: PlaylistType = PlaylistType.M3U_URL,
    ref: String = "cred-$name-${System.nanoTime()}",
    now: Long = 1L,
    configure: (PlaylistEntity) -> PlaylistEntity = { it },
): Long = playlistDao().insert(
    configure(PlaylistEntity(name = name, type = type, credentialRef = ref, displaySource = "host", sortIndex = 0, createdAt = now)),
)

suspend fun LibraryDatabase.newGroup(playlistId: Long, key: String, name: String = key, adult: Boolean = false, kind: Int = 0): Long {
    var id = 0L
    writeTransaction {
        usePrepared("INSERT INTO channel_group(playlist_id, kind, group_key, name, sort_index, is_adult) VALUES (?, ?, ?, ?, 0, ?)") { st ->
            st.bindLong(1, playlistId); st.bindLong(2, kind.toLong()); st.bindText(3, key); st.bindText(4, name); st.bindLong(5, if (adult) 1 else 0)
            st.step()
        }
        id = usePrepared("SELECT last_insert_rowid()") { st -> st.step(); st.getLong(0) }
    }
    return id
}

/** Inserts a channel row directly (plus its FTS row, like RoomMergeSink). */
suspend fun LibraryDatabase.newChannel(
    playlistId: Long,
    key: String,
    name: String = key,
    groupId: Long? = null,
    sortIndex: Int = 0,
    number: Int? = null,
    tvgId: String? = null,
    adult: Boolean = false,
    kind: Int = 0,
    missingSince: Long? = null,
): Long {
    val e = ChannelEntity(
        playlistId = playlistId, stableKey = key, keyRule = "URL", kind = kind, name = name, normName = name.lowercase(),
        number = number, sortIndex = sortIndex, groupId = groupId, tvgId = tvgId, url = "http://h/$key", isAdult = adult,
        contentHash = key.hashCode().toLong(), missingSince = missingSince, firstSeenAt = 1, updatedAt = 1,
    )
    var id = 0L
    writeTransaction {
        usePrepared(
            """INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, number, sort_index, group_id, tvg_id, url,
               is_adult, content_hash, missing_since, missing_count, first_seen_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,1,1)""",
        ) { st ->
            st.bindLong(1, e.playlistId); st.bindText(2, e.stableKey); st.bindText(3, e.keyRule); st.bindLong(4, e.kind.toLong())
            st.bindText(5, e.name); st.bindText(6, e.normName)
            if (number == null) st.bindNull(7) else st.bindLong(7, number.toLong())
            st.bindLong(8, sortIndex.toLong())
            if (groupId == null) st.bindNull(9) else st.bindLong(9, groupId)
            if (tvgId == null) st.bindNull(10) else st.bindText(10, tvgId)
            st.bindText(11, e.url); st.bindLong(12, if (adult) 1 else 0); st.bindLong(13, e.contentHash)
            if (missingSince == null) st.bindNull(14) else st.bindLong(14, missingSince)
            st.step()
        }
        id = usePrepared("SELECT last_insert_rowid()") { st -> st.step(); st.getLong(0) }
        usePrepared("INSERT INTO channel_fts(rowid, name) VALUES (?, ?)") { st -> st.bindLong(1, id); st.bindText(2, name); st.step() }
    }
    return id
}

suspend fun LibraryDatabase.scalar(sql: String, vararg args: Any?): Long = read {
    usePrepared(sql) { st ->
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                is String -> st.bindText(i + 1, a)
                else -> error("bad arg $a")
            }
        }
        if (st.step() && !st.isNull(0)) st.getLong(0) else 0L
    }
}

suspend fun LibraryDatabase.strings(sql: String, vararg args: Any?): List<String> = read {
    usePrepared(sql) { st ->
        args.forEachIndexed { i, a ->
            when (a) {
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                is String -> st.bindText(i + 1, a)
                else -> st.bindNull(i + 1)
            }
        }
        buildList { while (st.step()) add(if (st.isNull(0)) "null" else st.getText(0)) }
    }
}
