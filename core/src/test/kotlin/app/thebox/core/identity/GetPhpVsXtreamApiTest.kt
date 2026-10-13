package app.thebox.core.identity

import app.thebox.core.m3u.M3u
import app.thebox.core.refresh.FakeLibrary
import app.thebox.core.refresh.KeyContext
import app.thebox.core.source.EntryKind
import app.thebox.core.source.PlaylistType
import app.thebox.core.xtream.XtreamCatalogDecoderImpl
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §3.2.1 / §5.3: a `get.php` M3U export and the Xtream API login for the same line give the same keys,
 * so "upgrade to Xtream API" keeps favourites. Owned by WP-A; it skips itself until WP-C's decoder lands.
 */
class GetPhpVsXtreamApiTest {
    @Test fun `live keys of the M3U export equal the Xtream API keys`() {
        val m3uLib = FakeLibrary(PlaylistType.M3U_URL)
        m3uLib.refresh("m3u/xtream_get_php.m3u")
        val m3uLive = m3uLib.rows.values.filter { it.channel.channel.kind == EntryKind.LIVE }.map { it.key }.toSet()
        assertEquals((101..112).map { "xc:$it" }.toSet(), m3uLive)

        val decoder = XtreamCatalogDecoderImpl()
        val apiKeys = try {
            val cats = M3u.resourceBytes("m3u/xtream_get_php.live_categories.json").inputStream().use { decoder.categories(it) }
            M3u.resourceBytes("m3u/xtream_get_php.live_streams.json").inputStream().use { input ->
                val ctx = KeyContext(PlaylistType.XTREAM, xtreamShaped = false)
                decoder.liveChannels(input, cats).map { StableKeyDeriverImpl().derive(it, ctx).key.value }.toSet()
            }
        } catch (e: NotImplementedError) {
            assumeTrue(false, "WP-C XtreamCatalogDecoderImpl not merged yet: ${e.message}")
            return
        }
        assertEquals(m3uLive, apiKeys)
    }
}
