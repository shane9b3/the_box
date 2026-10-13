package app.thebox.core.epg.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.thebox.core.epg.EpgBuildRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.EpgStore
import app.thebox.core.epg.EpgWhitelist
import app.thebox.core.epg.EpgWhitelistResolver
import app.thebox.core.epg.FreeSpace
import app.thebox.core.epg.testutil.EpgTestDb
import org.kxml2.io.KXmlParser
import java.io.File
import java.time.ZoneId

/** Shared setup for the epg.db tests: "now" is 2026-10-13T12:00Z, the window 1 day back, 7 ahead. */
internal object StoreFixtures {
    val NOW_MS: Long = EpgTestDb.minutes("2026-10-13T12:00:00Z") * 60_000L

    fun min(iso: String): Int = EpgTestDb.minutes(iso)

    /** "06:00" on 2026-10-13 (or "+1 06:00" for the next day) in epoch minutes. */
    fun at(hhmm: String): Int {
        val next = hhmm.startsWith("+1 ")
        val t = hhmm.removePrefix("+1 ")
        return min("2026-10-1${if (next) 4 else 3}T$t:00Z")
    }

    fun res(name: String): File = File(StoreFixtures::class.java.getResource("/xmltv/$name")!!.toURI())

    fun store(
        dir: File,
        freeSpace: FreeSpace = EpgTestDb.PLENTY_OF_SPACE,
        fts4: Boolean = false,
        clock: () -> Long = { NOW_MS },
    ): EpgStoreImpl = EpgStoreImpl(dir, BundledSQLiteDriver(), freeSpace, ::KXmlParser, clock, fts4)

    fun request(vararg sources: Pair<Long, File>, zone: ZoneId? = null, pastDays: Int = 1, futureDays: Int = 7, forceInPlace: Boolean = false) =
        EpgBuildRequest(
            sources = sources.map { EpgSourceFile(it.first, it.second, zone) },
            nowMs = NOW_MS,
            pastDays = pastDays,
            futureDays = futureDays,
            forceInPlace = forceInPlace,
        )

    val ALL = EpgWhitelistResolver { _, _ -> EpgWhitelist.ALL }

    fun ids(vararg ids: String) = EpgWhitelistResolver { _, _ -> EpgWhitelist { it in ids } }

    fun EpgStore.buildAll(vararg sources: Pair<Long, File>) = build(request(*sources), ALL)
}
