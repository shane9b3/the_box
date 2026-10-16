package app.thebox.external

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.thebox.core.common.BoxLog
import app.thebox.core.settings.state.ExternalPlayerApp
import app.thebox.player.api.ExternalHandOff
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The external-player hand-off (docs/design/m5-setup-settings.md §3.3.4, product §4.4). [installed] lists the apps that
 * answer `ACTION_VIEW` of video over http (the manifest's `<queries>` makes them visible on API 30+, §6.6); [launch]
 * starts the chosen one with the stream address, `Intent.EXTRA_TITLE` and the request headers as the extras the common
 * players read (`headers`: alternating names and values for MX Player / Just Player; `http-user-agent` for VLC; §9 F24
 * is unverified, so a player that ignores them simply plays without). The caller has already stopped our stream
 * (`PlaybackController.handOffExternal()`), so the provider's connection is free. The URL is never logged.
 */
@Singleton
class ExternalPlayerLauncher @Inject constructor(@ApplicationContext private val context: Context) {
    fun installed(): List<ExternalPlayerApp> = try {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(PROBE_URL), VIDEO_ANY)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { ri -> ri.activityInfo?.let { it.packageName to ri.loadLabel(pm).toString() } }
            .filter { (pkg, _) -> pkg != context.packageName }
            .distinctBy { it.first }
            .map { (pkg, label) -> ExternalPlayerApp(pkg, label) }
            .sortedBy { it.label.lowercase() }
    } catch (e: Exception) {
        BoxLog.w(TAG, "external players unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    /** False when the app is gone or refuses the intent (the caller shows the reason and the next step). */
    fun launch(handOff: ExternalHandOff, packageName: String): Boolean {
        val intent = intentFor(handOff, packageName)
        return try {
            context.startActivity(intent)
            BoxLog.i(TAG) { "handed off to $packageName (${app.thebox.core.common.Redact.host(handOff.url)})" }
            true
        } catch (e: ActivityNotFoundException) {
            BoxLog.w(TAG, "external player $packageName not found")
            false
        } catch (e: SecurityException) {
            BoxLog.w(TAG, "external player $packageName refused the intent")
            false
        }
    }

    companion object {
        private const val TAG = "ExternalPlayer"
        private const val PROBE_URL = "http://example.invalid/stream.ts"
        const val VIDEO_ANY = "video/*"
        const val HLS_MIME = "application/x-mpegURL"

        fun mimeOf(url: String): String = if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) HLS_MIME else VIDEO_ANY

        fun intentFor(handOff: ExternalHandOff, packageName: String): Intent {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(handOff.url), mimeOf(handOff.url))
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            handOff.title?.let { intent.putExtra(Intent.EXTRA_TITLE, it).putExtra("title", it) }
            if (handOff.headers.isNotEmpty()) {
                intent.putExtra("headers", handOff.headers.flatMap { (k, v) -> listOf(k, v) }.toTypedArray())
                handOff.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { intent.putExtra("http-user-agent", it.value) }
            }
            return intent
        }
    }
}
