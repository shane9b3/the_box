package app.thebox.core.match

/**
 * EPG matcher (docs/design/m1-data-layer.md §3.6): `prepare(subjects)`, `matchSource(prepared, sourceId, channels,
 * overrides)`, `suggest(subject, channels, limit)`. Commit-0 stub (constructor only); WP-D owns this file and
 * defines the method signatures.
 */
@Suppress("unused")
class EpgMatcher(private val settings: MatchSettings)
