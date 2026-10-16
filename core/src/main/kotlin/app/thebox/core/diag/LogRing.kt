// Contract (M5 commit 0): docs/design/m5-setup-settings.md §1.3a (Application hook), §3.11.3. Changes go through the lead.
package app.thebox.core.diag

/**
 * The in-memory log ring (LOG_RING_LINES lines of ≤ 512 chars, redacted once, S13) that the log export reads.
 * `TheBoxApplication` calls [install] before anything logs (§1.3a); it wraps the current `BoxLog.sink` so the
 * `android.util.Log` sink keeps working. WP-E implements.
 */
interface LogRing {
    /** Installs the ring as a `BoxLog` sink next to the existing one; idempotent. */
    fun install()
    /** The ring's lines, oldest first (already redacted). */
    fun snapshot(): List<String>
}
