package app.thebox.core.playback.errors

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.ErrorLadder
import app.thebox.core.playback.FailureSignal
import app.thebox.core.playback.LadderContext
import app.thebox.core.playback.LadderCounters
import app.thebox.core.playback.LadderDecision

/** The decision table of §4.3. */
class ErrorLadderImpl : ErrorLadder {
    override fun decide(signal: FailureSignal, ctx: LadderContext): Pair<LadderDecision, LadderCounters> = TODO("WP-A")
}
