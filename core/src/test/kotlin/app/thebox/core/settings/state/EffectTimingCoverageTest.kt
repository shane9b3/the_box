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

    /**
     * C-S9 (M5 code review 2026-10-16): the same rule on every WP's built rows (WP-B's setup pages, WP-C's Parental and
     * button-mapping pages, WP-D's Channels and Backup pages; all conditional rows shown), and every registered spec of
     * WPs B, C and D is built by some page. WP-E's About is built in `:ui` (`DiagPagesTest` there).
     */
    @Test
    fun everyWpsBuiltRowsMatchTheirSpecs() {
        val real = RealPages(playlists = 2, pinSet = true)
        val routes = listOf(
            SettingsRoute.Section(app.thebox.core.settings.SectionId.PLAYLISTS), SettingsRoute.Playlist(1), SettingsRoute.PlaylistSource(1),
            SettingsRoute.Section(app.thebox.core.settings.SectionId.CHANNELS), SettingsRoute.Section(app.thebox.core.settings.SectionId.BACKUP),
            SettingsRoute.Section(app.thebox.core.settings.SectionId.PARENTAL), SettingsRoute.ButtonMapping,
            SettingsRoute.ActionBindings(app.thebox.core.input.BoxAction.ZAP_UP),
        )
        val wpa = WpaPages(FakeSettingsStore(), SettingsTexts.KEYS, FakePageEnv())
        val built = routes.flatMap { requireNotNull(real.build(it, advanced = true)) { "no builder for $it" }.rows } +
            wpa.build(SettingsRoute.Section(app.thebox.core.settings.SectionId.GUIDE), advanced = true).rows +
            // "Lock now" shows while the session is unlocked.
            app.thebox.core.settings.tree.ParentalRows.page(
                app.thebox.core.parental.ParentalState(pinSet = true, unlocked = true, policy = app.thebox.core.parental.ParentalPolicy()), real.rowTexts, 1, advanced = true,
            ).rows
        val problems = ArrayList<String>()
        for (r in built) {
            val spec = SettingsTree.spec(r.id) ?: continue // flow rows of a page are not settings
            if (spec.effect != r.effect) problems += "${r.id.key}: effect ${r.effect}, spec ${spec.effect}"
            if (spec.visibility != r.visibility) problems += "${r.id.key}: visibility ${r.visibility}, spec ${spec.visibility}"
            // A read-only Info line (the Xtream account's connection limit) has nothing to apply.
            if (r.effect !in setOf(EffectTiming.LIVE, EffectTiming.NONE) && r.description == null && r.kind !is app.thebox.core.settings.RowKind.Info) {
                problems += "${r.id.key} does not state its effect (${r.effect})"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        val ids = built.map { it.id }.toSet()
        val missing = SettingsTree.all.filter { it.owner in setOf("B", "C", "D") && it.id !in ids }.map { it.id.key }
        assertTrue(missing.isEmpty(), "specs never built: $missing")
    }
}
