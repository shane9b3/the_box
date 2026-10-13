package app.thebox.core.identity

import app.thebox.core.refresh.DetectedCredentials
import app.thebox.core.refresh.KeyContext
import app.thebox.core.refresh.KeyRule
import app.thebox.core.source.EntryKind
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import app.thebox.core.text.NameNormalizer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StableKeyDeriverTest {
    private val d = StableKeyDeriverImpl()
    private val m3u = KeyContext(PlaylistType.M3U_URL, xtreamShaped = false)
    private val cred = DetectedCredentials("http://h:8080", "u@x", "p#w")
    private val shaped = KeyContext(PlaylistType.M3U_URL, xtreamShaped = true, credentials = cred)

    private fun ch(
        url: String = "http://h/x/y.m3u8", name: String = "BBC One HD", tvgId: String? = null, group: String? = null,
        kind: EntryKind = EntryKind.LIVE, sid: Long? = null,
    ) = ParsedChannel(kind = kind, name = name, url = url, position = 0, groupTitle = group, tvgId = tvgId, xtreamStreamId = sid)

    private fun key(c: ParsedChannel, ctx: KeyContext = m3u) = d.derive(c, ctx).let { it.rule to it.key.value }

    @Test fun `rule 1 - Xtream API stream id by kind`() {
        val x = KeyContext(PlaylistType.XTREAM, xtreamShaped = false)
        val tpl = "{{base}}/live/{{u}}/{{p}}/5.{{ext}}"
        assertEquals(KeyRule.XTREAM to "xc:5", key(ch(url = tpl, sid = 5, tvgId = "bbc1.uk"), x))
        assertEquals(KeyRule.XTREAM to "xc:6", key(ch(url = tpl, sid = 6, kind = EntryKind.RADIO), x))
        assertEquals(KeyRule.XTREAM to "xcm:7", key(ch(url = tpl, sid = 7, kind = EntryKind.MOVIE), x))
        assertEquals(KeyRule.XTREAM to "xce:8", key(ch(url = tpl, sid = 8, kind = EntryKind.SERIES), x))
    }

    @Test fun `rule 1 - M3U Xtream URL with the voted pair, long and short form`() {
        assertEquals(KeyRule.XTREAM to "xc:101", key(ch(url = "http://h:8080/live/u%40x/p%23w/101.ts", tvgId = "bbc1.uk"), shaped))
        assertEquals(KeyRule.XTREAM to "xc:102", key(ch(url = "http://h:8080/u%40x/p%23w/102"), shaped))
        assertEquals(KeyRule.XTREAM to "xcm:2001", key(ch(url = "http://h:8080/movie/u%40x/p%23w/2001.mkv", kind = EntryKind.MOVIE), shaped))
        assertEquals(KeyRule.XTREAM to "xce:3001", key(ch(url = "http://other:80/series/u%40x/p%23w/3001.mp4"), shaped))
    }

    @Test fun `B6 - long-form CDN path in a non-shaped list is not xc`() {
        val c = ch(url = "http://cdn/live/bbc/hd/1.m3u8", name = "BBC")
        assertEquals(KeyRule.URL, d.derive(c, m3u).rule)
        assertEquals("url:live/1.m3u8", d.derive(c, m3u).key.value)
    }

    @Test fun `B6 - a minority pair in a shaped list is not xc, and an M3U xtreamStreamId alone is ignored`() {
        assertEquals(KeyRule.URL, d.derive(ch(url = "http://h:8080/live/other/pass/101.ts"), shaped).rule)
        assertEquals(KeyRule.NAME_GROUP, d.derive(ch(url = "http://h/index.m3u8", sid = 9), m3u).rule)
    }

    @Test fun `rule 2 - tvg-id lower-cased with forKey(name)`() {
        assertEquals(KeyRule.TVG to "tvg:bbcone.uk|bbc one", key(ch(tvgId = " BBCOne.uk ", name = "BBC One FHD")))
        // Junk ids fall through even if a caller forgot to filter them.
        assertEquals(KeyRule.URL, d.derive(ch(tvgId = "none"), m3u).rule)
    }

    @Test fun `rule 3 - URL key, rule 4 - name and group`() {
        assertEquals(KeyRule.URL to "url:x/y.m3u8", key(ch()))
        assertEquals(KeyRule.NAME_GROUP to "ng:bbc one|uk sports hd", key(ch(url = "http://h/index.m3u8", group = "UK: Sports HD")))
        assertEquals(KeyRule.NAME_GROUP to "ng:bbc one|", key(ch(url = "http://h/index.m3u8")))
    }

    @Test fun `priority order - each rule only when the higher ones yield nothing`() {
        val all = ch(url = "http://h:8080/live/u%40x/p%23w/101.ts", tvgId = "bbc1.uk", group = "G")
        assertEquals(KeyRule.XTREAM, d.derive(all, shaped).rule)
        assertEquals(KeyRule.TVG, d.derive(all, m3u).rule)
        assertEquals(KeyRule.URL, d.derive(all.copy(tvgId = null), m3u).rule)
        assertEquals(KeyRule.NAME_GROUP, d.derive(all.copy(tvgId = null, url = "http://h/live"), m3u).rule)
    }

    @Test fun `B5 - groups keep quality tokens apart`() {
        val a = key(ch(url = "http://h/index.m3u8", group = "Sports HD"))
        val b = key(ch(url = "http://h/index.m3u8", group = "Sports SD"))
        assertNotEquals(a, b)
        assertNotEquals(GroupKeys.m3u("UK HD"), GroupKeys.m3u("UK SD"))
        assertEquals("g:uk hd", GroupKeys.m3u("UK | HD"))
    }

    @ParameterizedTest
    @CsvSource(
        "BBC One HD, bbc one", "BBC One FHD ᴴᴰ, bbc one", "UK: BBC One (Backup), uk bbc one", "HBO (East), hbo east",
        "HBO (West), hbo west", "Channel 4 +1, channel 4 +1", "Télé-Québec, tele quebec", "|DE| Das Erste 4K, de das erste", "HD, hd",
    )
    fun `forKey table of section 3_1 feeds the keys`(name: String, norm: String) {
        assertEquals(norm, NameNormalizer.forKey(name))
        assertEquals("tvg:x|$norm", d.derive(ch(name = name, tvgId = "X"), m3u).key.value)
    }

    @Test fun `group keys`() {
        assertEquals("g:", GroupKeys.m3u(null))
        assertEquals("g:", GroupKeys.m3u("  "))
        assertEquals("xcat:5", GroupKeys.xtream("5"))
        assertEquals("xcat:none", GroupKeys.xtream(null))
        assertEquals("xcat:none", GroupKeys.groupKey(ch(), PlaylistType.XTREAM))
        assertEquals(EntryKind.LIVE, GroupKeys.groupKind(EntryKind.RADIO))
        assertEquals(EntryKind.MOVIE, GroupKeys.groupKind(EntryKind.MOVIE))
    }
}
