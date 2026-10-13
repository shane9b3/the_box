package app.thebox.core.identity

import app.thebox.core.m3u.M3u
import app.thebox.core.m3u.M3uWriter
import app.thebox.core.refresh.DetectedCredentials
import app.thebox.core.refresh.KeyContext
import app.thebox.core.source.EntryKind
import app.thebox.core.source.M3uHeader
import app.thebox.core.source.ParsedChannel
import app.thebox.core.source.PlaylistType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * Property: parse → serialise → parse is stable for stable keys, group keys and content hashes, over
 * randomly generated playlists with hostile names (commas, quotes, unicode, quality tokens, brackets).
 */
class KeyRoundTripPropertyTest {
    private val deriver = StableKeyDeriverImpl()

    @ParameterizedTest
    @ValueSource(longs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20])
    fun `round trip keeps keys and hashes`(seed: Long) {
        val rnd = Random(seed)
        val generated = List(150) { gen(rnd, it) }
        val first = M3u.parseText(M3uWriter.write(M3uHeader(), generated)).entries
        assertEquals(generated.size, first.size)
        val second = M3u.parseText(M3uWriter.write(M3uHeader(), first)).entries
        assertEquals(first, second, "parse(serialise(parse(x))) must equal parse(x)")

        for (ctx in contexts()) {
            val k0 = generated.map { deriver.derive(it, ctx).key }
            val k1 = first.map { deriver.derive(it, ctx).key }
            val k2 = second.map { deriver.derive(it, ctx).key }
            assertEquals(k0, k1, "keys of the generated entries survive a write/parse cycle ($ctx)")
            assertEquals(k1, k2)
        }
        val h1 = first.map { ContentHasher.contentHash(it, GroupKeys.m3u(it.groupTitle)) }
        val h2 = second.map { ContentHasher.contentHash(it, GroupKeys.m3u(it.groupTitle)) }
        assertEquals(h1, h2)
    }

    private fun contexts() = listOf(
        KeyContext(PlaylistType.M3U_URL, xtreamShaped = false),
        KeyContext(PlaylistType.M3U_URL, xtreamShaped = true, credentials = DetectedCredentials("http://xc.example:8080", "user", "pass")),
    )

    private val nameParts = listOf(
        "BBC One", "Sky Sports 1", "HBO (East)", "Channel 4 +1", "Télé-Québec", "ZDF", "News, UK", "Ciné+ Frisson", "TF1 HD", "FHD",
        "\"Quoted\" TV", "It's TV", "Первый канал", "日本テレビ", "4K", "[Backup]", "|DE|", "beIN Sports 2 (MENA)", "A&E", "100% Music",
    )
    private val groups = listOf("News", "Sports HD", "Sports SD", "UK: General", "Films; Action", "Ünïcödé", "18+ Adult", "Kids, Family")

    private fun gen(rnd: Random, i: Int): ParsedChannel {
        val name = buildString {
            append(nameParts[rnd.nextInt(nameParts.size)])
            if (rnd.nextBoolean()) append(' ').append(nameParts[rnd.nextInt(nameParts.size)])
            if (rnd.nextInt(4) == 0) append(' ').append(i)
        }
        val kind = EntryKind.entries[rnd.nextInt(EntryKind.entries.size)]
        val url = when (rnd.nextInt(6)) {
            0 -> "http://xc.example:8080/live/user/pass/${1000 + i}.ts"
            1 -> "http://xc.example:8080/user/pass/${2000 + i}"
            2 -> "http://cdn${rnd.nextInt(9)}.example.com/hls/ch$i/index.m3u8?token=${rnd.nextLong()}"
            3 -> "http://cdn.example.com/index.m3u8"
            4 -> "udp://@239.0.${i % 255}.1:1234"
            else -> "https://h.example.com/vod/movie$i.mkv"
        }
        val g = groups[rnd.nextInt(groups.size)]
        val groupParts = g.split(';').map { it.trim() }
        val tvgId = when (rnd.nextInt(4)) { 0 -> null; 1 -> "Id$i.uk@SD"; 2 -> "chan.$i"; else -> null }
        return ParsedChannel(
            kind = kind,
            name = name,
            url = url,
            position = i,
            groupTitle = if (rnd.nextInt(5) == 0) null else groupParts[0],
            extraGroups = if (groupParts.size > 1) groupParts.drop(1) else emptyList(),
            tvgId = tvgId,
            // An attribute value cannot hold both quote kinds (no escapes in M3U), so such names skip tvg-name.
            tvgName = if (rnd.nextBoolean() && !(name.contains('"') && name.contains('\''))) name else null,
            number = if (rnd.nextBoolean()) rnd.nextInt(1, 999_999) else null,
            epgShiftMinutes = if (rnd.nextInt(5) == 0) 60 * (rnd.nextInt(-12, 13)) else null,
            headers = if (rnd.nextInt(4) == 0) mapOf("User-Agent" to "Mozilla/5.0 (X; Y) & more", "Referer" to "https://r.example/?a=b") else emptyMap(),
            adult = rnd.nextInt(10) == 0,
            attributes = if (rnd.nextBoolean()) mapOf("tvg-language" to "en;fr", "x-custom" to "v, with comma") else emptyMap(),
        ).let { c -> if (c.groupTitle == null) c.copy(extraGroups = emptyList()) else c }
    }
}
