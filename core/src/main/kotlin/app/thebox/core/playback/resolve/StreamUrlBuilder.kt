package app.thebox.core.playback.resolve

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.CredentialExpander
import app.thebox.core.playback.ResolveInput
import app.thebox.core.playback.ResolveOutcome
import app.thebox.core.playback.StreamUrlBuilder

/** §3.2 steps 2–7 (pure). */
class StreamUrlBuilderImpl(private val expander: CredentialExpander = CredentialExpanderImpl()) : StreamUrlBuilder {
    override fun build(input: ResolveInput): ResolveOutcome = TODO("WP-A")
}
