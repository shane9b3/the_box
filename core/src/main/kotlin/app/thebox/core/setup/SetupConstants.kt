package app.thebox.core.setup

/** Named constants of WP-B (docs/design/m5-setup-settings.md Appendix B, §3.4.5, §3.4.6, §3.6.3). */
object SetupConstants {
    /** Reserved tune id the probe's `LeaseKind.PROBE` lease is owned by (S12). Never a real tune id (those are ≥ 1). */
    const val PROBE_OWNER_ID: Long = -2L
    /** The probe reads at most this many bytes of the stream body. */
    const val PROBE_BYTES: Long = 262_144L
    /** The probe reads for at most this long once the body started. */
    const val PROBE_MS: Long = 3_000L
    /** Whole-call budget of the probe (connect + headers + [PROBE_MS] of body); an OkHttp call timeout. */
    const val PROBE_CALL_TIMEOUT_MS: Long = 8_000L
    /** Bytes the sniffer needs to see two TS sync bytes (0x47 at 0 and 188). */
    const val SNIFF_BYTES: Int = 189
    /** First run: Start watching goes to the groups review above this many groups (or with any adult group). */
    const val GROUPS_REVIEW_MIN_GROUPS: Int = 8
    /** (B3) The PIN offer appears at most this often per playlist uid (`SetupState.pinOfferedFor`). */
    const val PIN_OFFER_MAX_PER_PLAYLIST: Int = 1
    /** Provider message on the result card is cut to this many characters (§3.4.4). */
    const val PROVIDER_MESSAGE_MAX_CHARS: Int = 120
    /** Top unmatched channels on the matching report (§3.4.7). */
    const val MATCHING_REPORT_TOP: Int = 10
    /** Fix unmatched picker: EPG channel search and suggestion limits (§3.6.3). */
    const val PICKER_SEARCH_LIMIT: Int = 50
    const val PICKER_SUGGEST_LIMIT: Int = 10
    /** Debounce of the "Now: …" line while the picker cursor moves (§3.6.3). */
    const val PICKER_NOW_DEBOUNCE_MS: Long = 300L
    /** Account card: expiry turns amber under this many days (§3.3.1). */
    const val EXPIRY_WARN_DAYS: Int = 7

    /**
     * The VLC preset of the UA fallback (M2 D22, L5). Must equal `:player` `PlaybackSettings.userAgentFallbackPreset`
     * (`StreamProbeImplTest` checks it): `:data`, which persists it after a working probe, cannot see `:player`.
     */
    const val VLC_USER_AGENT: String = "VLC/3.0.20 LibVLC/3.0.20"

    /** "Browser" user agent of the playlist row (§3.3.1). */
    const val BROWSER_USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
