package app.thebox.data.testutil

import app.thebox.core.m3u.M3uPlaylistParser
import app.thebox.core.model.TimeBase
import app.thebox.core.refresh.PlaylistDiffer
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.xtream.XtreamAccountInfo
import app.thebox.core.xtream.XtreamCatalogDecoder
import app.thebox.core.xtream.XtreamCategory
import app.thebox.core.xtream.XtreamEpgListing
import app.thebox.data.db.LibraryDatabase
import app.thebox.data.epg.EpgRecovery
import app.thebox.data.net.ContentCopier
import app.thebox.data.repo.EpgMatchRepository
import app.thebox.data.repo.EpgRepository
import app.thebox.data.repo.EpgWriteLock
import app.thebox.data.repo.InMemoryDataSettings
import app.thebox.data.repo.PlaylistRepository
import app.thebox.data.repo.RefreshRepository
import app.thebox.data.work.ImportMutex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.InputStream

/** Repositories wired with fakes for A/B/C (§1.7, §5.3). */
class TestGraph(val db: LibraryDatabase) {
    val clock = FakeClock()
    val creds = FakeCredentialStore()
    val fetcher = FakeFetcher()
    val xtream = FakeXtreamApi()
    val timeBases = FakeTimeBaseFactory()
    val requester = FakeRequester()
    val store = FakeEpgStore()
    val settings = InMemoryDataSettings()
    val importMutex = ImportMutex()
    val writeLock = EpgWriteLock()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val cacheRoot: File = File(TestDb.context.cacheDir, "graph-${System.nanoTime()}").apply { mkdirs() }

    val playlists = PlaylistRepository(db.playlistDao(), db.xtreamAccountDao(), db.epgSourceDao(), creds, timeBases, clock)
    val matches = EpgMatchRepository(db, db.channelDao(), db.epgSourceDao(), db.epgMappingDao(), db.epgMatchDao(), settings, requester, clock)

    fun refresh(
        differ: PlaylistDiffer = FakeDiffer(),
        parser: M3uPlaylistParser = FakeM3uParser(),
        decoder: XtreamCatalogDecoder = FakeXtreamDecoder(),
    ) = RefreshRepository(
        db, db.playlistDao(), db.epgSourceDao(), db.refreshLogDao(), playlists, matches, store, parser, differ, decoder, xtream, fetcher,
        ContentCopier(TestDb.context, Dispatchers.IO), timeBases, importMutex, requester, File(cacheRoot, "playlists"), Dispatchers.IO, clock,
    )

    val epg: EpgRepository by lazy {
        EpgRepository(
            store, EpgRecovery(store, scope, Dispatchers.IO), db.epgSourceDao(), db.playlistDao(), db.channelDao(), db.epgMappingDao(),
            db.epgMatchDao(), db.refreshLogDao(), playlists, matches, fetcher, settings, importMutex, writeLock, requester,
            { Long.MAX_VALUE }, File(cacheRoot, "epg"), Dispatchers.IO, clock,
        )
    }
}

/** Xtream decoder stand-in: categories file = lines `id|name`, streams file = lines `streamId|name|categoryId`. */
class FakeXtreamDecoder : XtreamCatalogDecoder {
    override fun account(input: InputStream, measuredAtDeviceMs: Long): XtreamAccountInfo = error("unused")
    override fun categories(input: InputStream): List<XtreamCategory> =
        input.bufferedReader().readLines().filter { it.isNotBlank() }.map { val (id, name) = it.split('|'); XtreamCategory(id, name, null) }
    override fun liveChannels(input: InputStream, categories: List<XtreamCategory>): Sequence<ParsedChannel> {
        val byId = categories.associateBy { it.id }
        return input.bufferedReader().lineSequence().filter { it.isNotBlank() }.mapIndexed { i, line ->
            val (id, name, cat) = line.split('|')
            ParsedChannel(
                EntryKind.LIVE, name, "{{base}}/live/{{u}}/{{p}}/$id.{{ext}}", i, byId[cat]?.name, xtreamCategoryId = cat,
                xtreamStreamId = id.toLong(),
            )
        }
    }
    override fun epgListings(input: InputStream, timeBase: TimeBase): List<XtreamEpgListing> = emptyList()
}
