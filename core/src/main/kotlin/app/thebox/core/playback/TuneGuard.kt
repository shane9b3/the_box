// Contract (M5 commit 0): docs/design/m5-setup-settings.md §2.6 (B1). Verbatim; changes go through the lead.
package app.thebox.core.playback

/**
 * (reviewed 2026-10-16, B1) `core/.../playback/TuneGuard.kt`. Every tune funnels through it inside the controller path:
 * guide OK, channel list, number entry, CH± (the zap neighbour lookup), previous channel, reminders, search,
 * catch-up start and resumeAfterLifecycle. :data implements it from the §4.3 predicate (one indexed lookup per
 * identity, cached per ParentalState.queryKey). A refused target never starts a cycle.
 */
fun interface TuneGuard {
    fun check(identity: app.thebox.core.refresh.ChannelIdentity): TuneVerdict

    companion object {
        /** (commit 0) The behaviour before WP-C: every target is allowed (the M3 predicate still filters the lists). */
        val ALLOW_ALL: TuneGuard = TuneGuard { TuneVerdict.ALLOWED }
    }
}
enum class TuneVerdict { ALLOWED, NEEDS_PIN, NOT_PROTECTED }
