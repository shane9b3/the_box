package app.thebox.errors

import android.content.Context
import android.widget.Toast
import app.thebox.core.common.BoxLog
import app.thebox.core.full.ErrorButton
import app.thebox.core.full.ErrorCardState
import app.thebox.core.full.ErrorRouteActions
import app.thebox.core.net.DnsChoice
import app.thebox.core.net.NetworkSettings
import app.thebox.core.settings.Domains
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsDomain
import app.thebox.core.settings.SettingsNavigator
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.SettingsStore
import app.thebox.data.repo.Trigger
import app.thebox.data.work.RefreshScheduler
import app.thebox.player.api.PlaybackController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/**
 * Error-card buttons that act outside the player (docs/design/m5-setup-settings.md §3.14). In `:app` because it needs
 * `:data` (settings, `RefreshScheduler`) and `:player`. Main thread, non-blocking:
 * - USE_SECURE_DNS: `network.dns = CLOUDFLARE`, then `retry()`, toast "Secure DNS is on (Cloudflare)…";
 * - REFRESH_PLAYLIST: `refreshNow(MANUAL)` for the card's playlist, toast;
 * - OPEN_EXTERNAL: Settings → Playback (the external-player row), detached, until WP-A's `ExternalPlayerLauncher`
 *   offers the direct hand-off (§3.3.4: "when a player is chosen or exactly one is installed");
 * - OPEN_SETTINGS is the player machine's own (`PlayerEffects.openSettings(card.route)`).
 */
class ErrorRouteActionsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val refresh: RefreshScheduler,
    private val controller: PlaybackController,
    private val navigator: SettingsNavigator,
) : ErrorRouteActions {
    override fun run(button: ErrorButton, card: ErrorCardState) {
        when (button) {
            ErrorButton.USE_SECURE_DNS -> {
                store.update(NetworkDomain) { it.copy(dns = DnsChoice.CLOUDFLARE) }
                controller.retry()
                toast(app.thebox.ui.R.string.error_route_secure_dns_on)
            }
            ErrorButton.REFRESH_PLAYLIST -> {
                val pid = card.channel?.identity?.playlistId ?: return
                try {
                    refresh.refreshNow(pid, Trigger.MANUAL)
                    toast(app.thebox.ui.R.string.error_route_refresh_started)
                } catch (e: RuntimeException) {
                    BoxLog.w(TAG, "refresh not scheduled: ${e.javaClass.simpleName}")
                }
            }
            ErrorButton.OPEN_EXTERNAL -> navigator.openSettings(SettingsRoute.Section(SectionId.PLAYBACK), fromFull = true, detached = true)
            ErrorButton.OPEN_SETTINGS -> card.route?.let { navigator.openSettings(it, fromFull = true, detached = true) }
            else -> Unit
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(context, context.getString(res), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ErrorRoutes"

        /**
         * The `network` domain (§4.1). WP-A owns the network rows and their domain object; this copy has the same name,
         * serializer and default, so both address one document. Replace with WP-A's object at integration.
         */
        val NetworkDomain: SettingsDomain<NetworkSettings> = SettingsDomain(Domains.NETWORK, NetworkSettings.serializer(), NetworkSettings())
    }
}

/** WP-C's `:app` binding. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ErrorRoutesModule {
    @Binds abstract fun errorRouteActions(impl: ErrorRouteActionsImpl): ErrorRouteActions
}
