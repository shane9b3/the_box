package app.thebox.core.playback.resolve

// Commit-0 stub (docs/design/m2-playback-core.md §1.4/§1.5). Owner: WP-A. Replace the TODO bodies; keep the public names.
import app.thebox.core.playback.CredentialExpander

/** `{{base}}`, `{{ext}}`, then `{{u}}`/`{{p}}` with position-aware encoding (§3.2.4). */
class CredentialExpanderImpl : CredentialExpander {
    override fun expandNonSecret(template: String, base: String?, extension: String?): String = TODO("WP-A")
    override fun expandCredentials(template: String, username: String?, password: String?): String = TODO("WP-A")
    override fun expandHeaderValue(value: String, username: String?, password: String?): String = TODO("WP-A")
}
