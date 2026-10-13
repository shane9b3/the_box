package app.thebox.core.identity

import app.thebox.core.common.Hash64
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.RawCatchup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ContentHasherTest {
    private val base = ParsedChannel(
        kind = EntryKind.LIVE, name = "BBC One", url = "http://h/{{u}}/{{p}}/1", position = 3, groupTitle = "UK",
        extraGroups = listOf("News"), tvgId = "bbc1.uk", tvgName = "BBC 1", logoUrl = "http://l/1.png", number = 101,
        epgShiftMinutes = 60, headers = mapOf("User-Agent" to "UA"), catchup = RawCatchup(type = "xc", days = 3),
        xtreamStreamId = 1, containerExtension = "ts", adult = false, durationSec = null,
        kodiProps = mapOf("a" to "b"), vlcOpts = mapOf("c" to "d"), attributes = mapOf("e" to "f"),
    )
    private fun h(c: ParsedChannel, g: String = "g:uk") = ContentHasher.contentHash(c, g)

    @Test fun `position is excluded`() {
        assertEquals(h(base), h(base.copy(position = 99)))
    }

    @Test fun `every content field changes the hash`() {
        val variants = listOf(
            base.copy(kind = EntryKind.RADIO), base.copy(name = "BBC Two"), base.copy(url = "http://h/x"), base.copy(groupTitle = "US"),
            base.copy(extraGroups = emptyList()), base.copy(extraGroups = listOf("News", "")), base.copy(logoUrl = null),
            base.copy(number = 102), base.copy(tvgId = null), base.copy(tvgName = "x"), base.copy(epgShiftMinutes = null),
            base.copy(headers = emptyMap()), base.copy(catchup = RawCatchup.NONE), base.copy(catchup = base.catchup.copy(source = "s")),
            base.copy(catchup = base.catchup.copy(correctionHours = 1.0)), base.copy(catchup = base.catchup.copy(timeshiftDays = 1)),
            base.copy(catchup = base.catchup.copy(tvgRecDays = 1)), base.copy(catchup = base.catchup.copy(catchupTimeSec = 1)),
            base.copy(catchup = base.catchup.copy(xtreamTvArchive = false)), base.copy(catchup = base.catchup.copy(xtreamArchiveDays = 2)),
            base.copy(xtreamStreamId = 2), base.copy(containerExtension = null), base.copy(adult = true), base.copy(durationSec = 0),
            base.copy(kodiProps = emptyMap()), base.copy(vlcOpts = emptyMap()), base.copy(attributes = mapOf("e" to "g")),
        )
        val hashes = variants.map { h(it) }.toSet() + h(base) + h(base, "g:us")
        assertEquals(variants.size + 2, hashes.size, "every variant must hash differently")
    }

    @Test fun `map order does not matter, field boundaries do`() {
        assertEquals(h(base.copy(headers = mapOf("A" to "1", "B" to "2"))), h(base.copy(headers = mapOf("B" to "2", "A" to "1"))))
        assertNotEquals(h(base.copy(name = "ab", tvgName = "c")), h(base.copy(name = "a", tvgName = "bc")))
    }

    @Test fun `the map shortcut equals Hash64 map`() {
        for (m in listOf(emptyMap(), mapOf("a" to "b"), mapOf("b" to "1", "a" to "2", "c" to ""))) {
            assertEquals(Hash64.map(12345L, m), ContentHasher.map(12345L, m))
        }
    }

    @Test fun `url and name hashes are Hash64 of the strings`() {
        assertEquals(Hash64.of("http://h/{{u}}/{{p}}/1"), ContentHasher.urlHash(base.url))
        assertEquals(Hash64.of("BBC One"), ContentHasher.nameHash(base.name))
    }

    @Test fun `golden value - the hash is persisted, changing it needs a migration`() {
        val simple = ParsedChannel(kind = EntryKind.LIVE, name = "A", url = "http://h/a", position = 0)
        assertEquals(GOLDEN, h(simple, "g:"), "ContentHasher output changed: channel.content_hash would churn on every refresh")
    }

    private companion object {
        // Recorded from the M1 implementation (2026-10-13).
        const val GOLDEN = -4801985720069152224L
    }
}
