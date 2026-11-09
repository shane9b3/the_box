package app.thebox.errors

import android.content.Context
import android.widget.Toast
import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.LiveSettingsApplier
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
import app.thebox.player.api.ExternalHandOff
import app.thebox.player.api.PlaybackController
import app.thebox.player.api.PlaybackState
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
 * - OPEN_EXTERNAL: (M5 integration) WP-A's `ExternalPlayerLauncher` hands the stream off when a player is chosen or
 *   exactly one is installed (§3.3.4). (Fix pass A5) Our stream stops only once the player is known to accept the
 *   intent; otherwise a toast gives the reason and Settings → Playback (the external-player row) opens, detached;
 * - OPEN_SETTINGS is the player machine's own (`PlayerEffects.openSettings(card.route)`).
 */
class ErrorRouteActionsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val refresh: RefreshScheduler,
    private val controller: PlaybackController,
    private val navigator: SettingsNavigator,
    private val launcher: app.thebox.external.ExternalPlayerLauncher,
    private val applier: LiveSettingsApplier,
    private val archive: ArchivePlayback,
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
            ErrorButton.OPEN_EXTERNAL -> when (handOff()) {
                ExternalOutcome.LAUNCHED -> Unit
                ExternalOutcome.NO_PLAYER -> { toast(app.thebox.ui.R.string.settings_ch_external_none); openPlaybackSettings() }
                ExternalOutcome.NOTHING -> toast(app.thebox.ui.R.string.settings_ch_external_nothing)
                ExternalOutcome.FAILED -> { toast(app.thebox.ui.R.string.settings_ch_external_failed); openPlaybackSettings() }
            }
            ErrorButton.OPEN_SETTINGS -> card.route?.let { navigator.openSettings(it, fromFull = true, detached = true) }
            // (M5 integration, B2, §3.14) One tap: "Always passthrough" off, applied by one re-prepare (the row is REPREPARE).
            // (Fix pass A1) On the card itself nothing plays to re-prepare: the change restarts playback like Retry.
            ErrorButton.BACK_TO_AUTO -> {
                store.update(app.thebox.core.settings.state.PlaybackDomain) { it.copy(audioOutput = app.thebox.core.playback.AudioOutputMode.AUTO) }
                try {
                    applyBackToAuto(controller, archive, applier, card.archiveFailed)
                } catch (e: RuntimeException) {
                    BoxLog.w(TAG, "re-prepare after Back to Auto failed: ${e.javaClass.simpleName}")
                }
                toast(app.thebox.ui.R.string.error_route_back_to_auto)
            }
            else -> Unit
        }
    }

    /** The direct hand-off (§3.3.4) to the chosen player, or the only one installed. */
    private fun handOff(): ExternalOutcome {
        val pkg = store.flow(app.thebox.core.settings.state.PlaybackDomain).value.externalPlayer
            ?: launcher.installed().singleOrNull()?.packageName
        return handOffExternal(pkg, controller, ::canOpen, launcher::launch)
    }

    private fun canOpen(handOff: ExternalHandOff, pkg: String): Boolean = try {
        app.thebox.external.ExternalPlayerLauncher.resolved(context.packageManager, handOff, pkg) != null
    } catch (e: RuntimeException) {
        BoxLog.w(TAG, "external player check failed: ${e.javaClass.simpleName}")
        false
    }

    private fun openPlaybackSettings() = navigator.openSettings(SettingsRoute.Section(SectionId.PLAYBACK), fromFull = true, detached = true)

    private fun toast(res: Int) {
        Toast.makeText(context, context.getString(res), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ErrorRoutes"

        /** The `network` domain (§4.1): WP-A's object (M5 integration). */
        val NetworkDomain: SettingsDomain<NetworkSettings> get() = app.thebox.core.settings.state.NetworkDomain
    }
}

/** (Fix pass A5) What the error card's external-player button did. */
internal enum class ExternalOutcome { LAUNCHED, NO_PLAYER, NOTHING, FAILED }

/**
 * (Fix pass A5) Stops our stream ([PlaybackController.handOffExternal]) only once [canOpen] says [pkg] accepts the
 * intent, so a player that cannot open the stream leaves playback (and the card) as it was.
 */
internal fun handOffExternal(
    pkg: String?,
    controller: PlaybackController,
    canOpen: (ExternalHandOff, String) -> Boolean,
    launch: (ExternalHandOff, String) -> Boolean,
): ExternalOutcome {
    if (pkg == null) return ExternalOutcome.NO_PLAYER
    val peek = controller.peekExternalHandOff() ?: return ExternalOutcome.NOTHING
    if (!canOpen(peek, pkg)) return ExternalOutcome.FAILED
    val handOff = controller.handOffExternal() ?: return ExternalOutcome.NOTHING
    return if (launch(handOff, pkg)) ExternalOutcome.LAUNCHED else ExternalOutcome.FAILED
}

/**
 * (Fix pass A1) Applies the Back to Auto change: while something plays, the usual one re-prepare ([applier]); on the
 * error card, a restart as RETRY does it (the archive session resumes an archive card, else the controller retries).
 */
internal fun applyBackToAuto(controller: PlaybackController, archive: ArchivePlayback, applier: LiveSettingsApplier, archiveFailed: Boolean) {
    val st = controller.state.value
    if (st !is PlaybackState.Error) {
        applier.apply()
        return
    }
    if ((st.target.isArchive || archiveFailed) && archive.status.value.phase != ArchivePhase.IDLE) archive.resume() else controller.retry()
}

/** WP-C's `:app` binding. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ErrorRoutesModule {
    @Binds abstract fun errorRouteActions(impl: ErrorRouteActionsImpl): ErrorRouteActions
}
