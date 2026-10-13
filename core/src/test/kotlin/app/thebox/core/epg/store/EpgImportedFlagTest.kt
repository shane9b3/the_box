package app.thebox.core.epg.store

import app.thebox.core.epg.EpgChannelInfo
import app.thebox.core.epg.EpgChannelRef
import app.thebox.core.epg.EpgPatchRequest
import app.thebox.core.epg.EpgSourceFile
import app.thebox.core.epg.store.StoreFixtures.ids
import app.thebox.core.epg.store.StoreFixtures.request
import app.thebox.core.epg.store.StoreFixtures.store
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Code review M1 S-7: a whitelisted channel without programmes must still end up `imported = 1`, or
 * EpgMatchRepository.pendingRefs asks for an `epg-patch` (a full XMLTV re-parse) after every build and refresh.
 */
class EpgImportedFlagTest {
    @TempDir
    lateinit var tmp: File

    private fun xml(withProgramme: Boolean) = File(tmp, "g$withProgramme.xml").apply {
        writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="busy"><display-name>Busy</display-name></channel>
              <channel id="quiet"><display-name>Quiet</display-name></channel>
              <channel id="other"><display-name>Other</display-name></channel>
              <programme start="20261013110000 +0000" stop="20261013130000 +0000" channel="busy"><title>On air</title></programme>
              ${if (withProgramme) """<programme start="20261013110000 +0000" stop="20261013130000 +0000" channel="quiet"><title>Q</title></programme>""" else ""}
            </tv>
            """.trimIndent(),
        )
    }

    private fun channels(s: EpgStoreImpl): Map<String, EpgChannelInfo> {
        val out = HashMap<String, EpgChannelInfo>()
        s.openReader()!!.use { r -> r.forEachChannel(1L) { out[it.xmltvId] = it } }
        return out
    }

    @Test
    fun whitelistedChannelWithoutProgrammesIsImported() {
        val s = store(File(tmp, "epg"))
        s.build(request(1L to xml(false)), ids("busy", "quiet"))
        val c = channels(s)
        assertTrue(c.getValue("busy").imported)
        assertTrue(c.getValue("quiet").imported, "whitelisted, no programmes: still imported")
        assertEquals(0, c.getValue("quiet").programmeCount)
        assertFalse(c.getValue("other").imported, "not whitelisted")
    }

    @Test
    fun patchMarksRequestedChannelsImportedEvenWithoutProgrammes() {
        val s = store(File(tmp, "epg"))
        s.build(request(1L to xml(true)), ids("busy"))
        assertFalse(channels(s).getValue("quiet").imported)
        // The new file has no programme for "quiet": the patch must still settle it.
        s.patch(EpgPatchRequest(listOf(EpgSourceFile(1L, xml(false), null)), listOf(EpgChannelRef(1L, "quiet")), StoreFixtures.NOW_MS, 1, 7))
        val q = channels(s).getValue("quiet")
        assertTrue(q.imported)
        assertEquals(0, q.programmeCount)
    }
}
