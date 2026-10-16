package app.thebox.core.settings.state

import app.thebox.core.net.DnsChoice
import app.thebox.core.settings.EffectTiming
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.Visibility
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.tree.ChannelOptionsRows
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.core.settings.tree.SettingsTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every row WP-A builds has a [SettingsTree] spec, and the built row carries the spec's EffectTiming and visibility;
 * every WP-A spec is built by some page (all conditional rows shown); a row that is not LIVE states its effect
 * (docs/design/m5-setup-settings.md §2.1, §6.1). The lead ruling holds: RESTART only for `playback.ffmpeg`.
 */
class EffectTimingCoverageTest {
    @Test
    fun builtRowsMatchTheirSpecs() {
        val store = FakeSettingsStore()
        store.update(NetworkDomain) { it.copy(dns = DnsChoice.CUSTOM) }
        store.update(DataDomain) { it.copy(epgDailyAtHour = 2) }
        val env = FakePageEnv(running = false)
        val pages = WpaPages(store, SettingsTexts.KEYS, env)
        val built = WpaPages.SECTIONS.flatMap { pages.build(SettingsRoute.Section(it), advanced = true).rows } +
            FixturePages().build(SettingsRoute.Channel(SettingsWorld.ID)).rows
        for (r in built) {
            if (r.id == app.thebox.core.settings.tree.GuideRows.FIX_UNMATCHED && SettingsTree.spec(r.id) == null) continue // WP-B's spec
            val spec = assertNotNull(SettingsTree.spec(r.id), "no spec for ${r.id.key}")
            assertEquals(spec.effect, r.effect, r.id.key)
            assertEquals(spec.visibility, r.visibility, r.id.key)
            if (r.effect !in setOf(EffectTiming.LIVE, EffectTiming.NONE)) assertNotNull(r.description, "${r.id.key} states its effect")
        }
        val ids = built.map { it.id }.toSet()
        val wpA = SettingsTree.all.filter { it.owner == "A" }
        for (s in wpA) assertTrue(s.id in ids, "spec ${s.id.key} is never built")
        assertEquals(listOf(PlaybackRows.FFMPEG), wpA.filter { it.effect == EffectTiming.RESTART }.map { it.id })
        assertEquals(EffectTiming.REPREPARE, SettingsTree.spec(PlaybackRows.AUDIO_OUTPUT)?.effect, "F19 lead ruling")
        assertTrue(ChannelOptionsRows.rows.all { it.visibility == Visibility.SIMPLE })
    }
}
