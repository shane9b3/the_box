package app.thebox.data.backup

import android.content.Intent
import app.thebox.core.backup.BackupDomain
import app.thebox.core.backup.BackupReader
import app.thebox.core.common.Outcome
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.data.contracts.SecureBlobStore
import app.thebox.data.testutil.FakeClock
import app.thebox.data.testutil.FakeCredentialStore
import app.thebox.data.testutil.TestDb
import app.thebox.data.testutil.newPlaylist
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files

/** The weekly backup (docs/design/m5-setup-settings.md §3.10.5; §6.1 `AutoBackupWorkerTest`) and the F17 picker detection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoBackupTest {
    private lateinit var dir: File
    private val clock = FakeClock(1_800_000_000_000L)

    /** Backups as files in [root]; one foreign `.thebox` file the user put there. */
    private class DirStorage(val root: File) : BackupStorage {
        override fun downloadsWritable() = true
        override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): StoredBackup {
            val f = File(root, name)
            f.outputStream().use { body(it) }
            return StoredBackup(f.path, name, StoredBackup.Where.DOWNLOADS)
        }
        override fun list() = BackupListing(root.listFiles()!!.map { StoredBackup(it.path, it.name, StoredBackup.Where.DOWNLOADS) }, false)
        override fun copyTo(location: String, dest: File) = File(location).copyTo(dest, true).exists()
        override fun delete(location: String) { File(location).delete() }
    }

    @Before fun setUp() { dir = Files.createTempDirectory("auto").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun service(blobs: SecureBlobStore, settings: FakeSettingsStore, storage: BackupStorage) = runBlocking {
        val db = TestDb.create()
        db.newPlaylist("P")
        val env = object : BackupEnvironment { override val versionName = "t"; override val versionCode = 1L }
        val follow = object : RestoreFollowUp { override suspend fun afterRestore(newOrRelogin: List<Long>, affected: List<Long>) = Unit }
        BackupServiceImpl(db, FakeCredentialStore(), blobs, settings, ImportMutex(), storage, env, follow, clock, Dispatchers.IO, File(dir, "state"), 1_000)
    }

    @Test
    fun keepsTheNewestFourItWroteAndNeverTouchesOtherFiles() = runBlocking {
        val root = File(dir, "Download-TheBox").apply { mkdirs() }
        val foreign = File(root, "TheBox-auto-2020-01-01.thebox").apply { writeText("mine, not the app's") }
        val manual = File(root, "TheBox-backup-2026-01-01-1200.thebox").apply { writeText("manual") }
        val settings = FakeSettingsStore()
        val s = service(RestoreMergeTest.MapBlobs(), settings, DirStorage(root))
        val names = ArrayList<String>()
        repeat(6) { day ->
            val r = s.autoBackup(clock.now() + day * 86_400_000L) as Outcome.Ok
            names += r.value
        }
        val left = root.listFiles()!!.map { it.name }.toSet()
        assertEquals(names.takeLast(4).toSet() + foreign.name + manual.name, left)
        assertTrue(foreign.exists() && manual.exists())
        assertEquals(names.last(), settings.flow(BackupDomain).value.lastAutoFile)
    }

    @Test
    fun weeklyBackupIncludesLoginsOnlyWithAStoredPassphrase() = runBlocking {
        val root = File(dir, "d").apply { mkdirs() }
        val blobs = RestoreMergeTest.MapBlobs()
        val settings = FakeSettingsStore()
        val s = service(blobs, settings, DirStorage(root))
        val first = File(root, (s.autoBackup(clock.now()) as Outcome.Ok).value)
        assertFalse(header(first).includesLogins)

        assertTrue(s.setStoredPassphrase("correct horse".toCharArray()) is Outcome.Ok)
        assertTrue(settings.flow(BackupDomain).value.passphraseStored)
        val second = File(root, (s.autoBackup(clock.now() + 86_400_000L) as Outcome.Ok).value)
        assertTrue(header(second).includesLogins)
    }

    @Test
    fun afterAKeystoreResetWeeklyBackupsContinueWithoutLoginsAndSaySo() = runBlocking {
        val root = File(dir, "k").apply { mkdirs() }
        val blobs = RestoreMergeTest.MapBlobs()
        val settings = FakeSettingsStore()
        val s = service(blobs, settings, DirStorage(root))
        s.setStoredPassphrase("correct horse".toCharArray())
        blobs.blobs.clear() // what TinkCredentialStore.wipe() does to every row
        val r = s.autoBackup(clock.now()) as Outcome.Ok
        assertFalse(header(File(root, r.value)).includesLogins)
        assertFalse(settings.flow(BackupDomain).value.passphraseStored)
        assertTrue(s.passphraseLost)
        // Setting a new one clears the notice.
        s.setStoredPassphrase("another horse".toCharArray())
        assertFalse(s.passphraseLost)
    }

    @Test
    fun exportWithAPassphraseZeroesIt() = runBlocking {
        val s = service(RestoreMergeTest.MapBlobs(), FakeSettingsStore(), DirStorage(dir))
        val pass = "correct horse".toCharArray()
        s.export(ByteArrayOutputStream(), pass)
        assertTrue(pass.all { it == '\u0000' })
    }

    private fun header(f: File) = f.inputStream().use { BackupReader(it).readHeader() }

    // ---- F17 (SettingsDocumentsUiDetectionTest) ----------------------------------------------------------------

    @Test
    fun aStubResolverHidesTheFileTarget() {
        val stub = DocumentsUiDetector({ listOf("com.amazon.tv.nodocuments") })
        assertFalse(stub.available())
        assertFalse(DocumentsUiDetector({ emptyList() }).available())
    }

    @Test
    fun documentsUiByPackageShowsIt() {
        assertTrue(DocumentsUiDetector({ listOf("com.android.documentsui") }).available())
        assertTrue(DocumentsUiDetector({ listOf("x.stub", "com.google.android.documentsui") }).available())
        // A file manager the user picked once counts too.
        assertTrue(DocumentsUiDetector({ listOf("pl.solidexplorer2") }, { setOf("pl.solidexplorer2") }).available())
        // Both directions are needed.
        assertFalse(DocumentsUiDetector({ i -> if (i.action == Intent.ACTION_OPEN_DOCUMENT) listOf("com.android.documentsui") else listOf("x.stub") }).available())
    }
}
