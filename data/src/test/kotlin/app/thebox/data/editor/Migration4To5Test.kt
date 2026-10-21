package app.thebox.data.editor

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.WriteResult
import app.thebox.core.parental.ParentalAccess
import app.thebox.core.parental.ParentalControls
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * docs/design/m6-channel-editor.md §3.6, §6.2: a v4 library (user order, a hidden channel) opened through the production
 * builder migrates to v5 and serves the editor at once: lists keep the v4 behaviour, and a move works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration4To5Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var file: File

    @Before fun setUp() {
        file = context.getDatabasePath("m45-${System.nanoTime()}.db")
        file.parentFile?.mkdirs()
        file.delete()
    }

    @After fun tearDown() { context.deleteDatabase(file.name) }

    @Test
    fun aV4LibraryServesTheEditorAfterTheMigration() {
        val helper = MigrationTestHelper(instrumentation, file, AndroidSQLiteDriver(), LibraryDatabase::class)
        helper.createDatabase(4).apply {
            execSQL("INSERT INTO playlist(id, name, type, credential_ref, display_source, sort_index, created_at, uid) VALUES (1, 'P', 'M3U_URL', 'r', 'h', 0, 1, 'u1')")
            execSQL("INSERT INTO channel_group(id, playlist_id, kind, group_key, name, sort_index, is_adult) VALUES (10, 1, 0, 'g:news', 'News', 0, 0), (11, 1, 0, 'g:sport', 'Sport', 1, 0)")
            for ((i, k) in listOf("n1", "n2", "s1").withIndex()) {
                execSQL(
                    "INSERT INTO channel(playlist_id, stable_key, key_rule, kind, name, norm_name, sort_index, group_id, url, content_hash, first_seen_at, updated_at) " +
                        "VALUES (1, '$k', 'URL', 0, '$k', '$k', $i, ${if (k.startsWith("n")) 10 else 11}, 'u', 0, 1, 1)",
                )
            }
            execSQL("INSERT INTO channel_prefs(playlist_id, stable_key, hidden, custom_sort_index, watch_count, updated_at) VALUES (1, 'n2', 1, 0, 0, 1)")
            close()
        }
        val db = LibraryDatabase.build(context, Dispatchers.IO, AndroidSQLiteDriver(), file.name)
        try {
            runBlocking {
                val repo = ChannelEditorRepositoryImpl(db, ParentalControls.NONE, FakeClock(), Dispatchers.IO)
                suspend fun keys(l: EditorList, f: EditorFilter = EditorFilter.ALL) =
                    repo.channels(1, l, f, ParentalAccess.OPEN).ids.map { id -> db.strings("SELECT stable_key FROM channel WHERE id = ?", id).single() }
                assertEquals(listOf("n2", "n1"), keys(EditorList.Group("g:news")))
                assertEquals(listOf("n2"), keys(EditorList.Group("g:news"), EditorFilter.HIDDEN))
                assertEquals(WriteResult.Done(1), repo.write(EditorWrite.MoveChannels(1, ChannelTarget.Keys(setOf("s1")), "g:news"), ParentalAccess.OPEN))
                assertEquals("max + 1 in a custom-ordered group (n1 has no index)", listOf("n2", "s1", "n1"), keys(EditorList.Group("g:news")))
                assertEquals(emptyList<String>(), keys(EditorList.Group("g:sport")))
            }
        } finally {
            db.close()
        }
    }
}
