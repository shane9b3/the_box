package app.thebox.ui.parental

import app.thebox.core.common.BoxLog
import app.thebox.core.common.Outcome
import app.thebox.core.guide.GuideChannel
import app.thebox.core.guide.GuideSource
import app.thebox.core.guide.GuideSourceEntry
import app.thebox.core.parental.ParentalActions
import app.thebox.core.parental.ParentalAnswer
import app.thebox.core.parental.ParentalControls
import app.thebox.core.parental.ParentalEffects
import app.thebox.core.parental.ParentalHost
import app.thebox.core.parental.ParentalPinStore
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.PinCheck
import app.thebox.core.parental.RelockReason
import app.thebox.core.refresh.ChannelIdentity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parental effects over `ParentalPinStore` / `ParentalSession` / `ParentalControls` (docs/design/m5-setup-settings.md
 * §1.3a, §3.9). Main-safe: the PBKDF2 check and every write run on IO inside the store; answers come back on main.
 * A correct PIN unlocks the session before the answer is delivered, so the machine's next query already sees OPEN.
 * Digits are zeroed by the store on every path; nothing here logs a digit.
 */
@Singleton
class ParentalEffectsImpl @Inject constructor(
    private val pins: ParentalPinStore,
    private val session: ParentalSession,
    private val controls: ParentalControls,
) : ParentalEffects {
    /** App-lifetime main scope (the singleton outlives the activities). Tests replace it. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The PIN lockout's wall clock: the debug harness's offset is added (DebugOverrides, §6.5; 0 in release builds). */
    internal var clock: () -> Long = app.thebox.core.diag.DebugOverrides::nowMs

    override fun verifyPin(token: Long, digits: CharArray, answer: (PinCheck) -> Unit) {
        scope.launch {
            val check = try {
                pins.verify(digits, clock())
            } catch (e: CancellationException) {
                digits.fill('\u0000')
                throw e
            } catch (e: Exception) {
                digits.fill('\u0000')
                BoxLog.w(TAG, "PIN check failed: ${e.javaClass.simpleName}")
                PinCheck.Unavailable
            }
            if (check == PinCheck.Ok) session.unlock(clock())
            answer(check)
        }
    }

    override fun setChannelLocked(channel: GuideChannel, locked: Boolean) {
        launchSafely("lock channel") { controls.setChannelLocked(channel.identity, locked) }
    }

    override fun setGroupLocked(entry: GuideSourceEntry, locked: Boolean) {
        val g = entry.source as? GuideSource.Group ?: return
        launchSafely("lock group") { controls.setGroupLocked(g, locked) }
    }

    /** The guide machine's [ParentalActions] (PIN creation, the lockout on open, Open anyway), answering [host]. */
    fun actionsFor(host: ParentalHost): ParentalActions = object : ParentalActions {
        override fun storePin(digits: CharArray) {
            scope.launch {
                val ok = try {
                    pins.set(digits) is Outcome.Ok
                } catch (e: CancellationException) {
                    digits.fill('\u0000')
                    throw e
                } catch (e: Exception) {
                    digits.fill('\u0000')
                    BoxLog.w(TAG, "PIN not stored: ${e.javaClass.simpleName}")
                    false
                }
                // A new PIN: the user just proved it, so the session opens (and a PIN change relocks first).
                if (ok) { session.relock(RelockReason.PIN_CHANGED); session.unlock(clock()) }
                host.parentalAnswer(ParentalAnswer.PinStored(ok))
            }
        }

        override fun queryLockout() {
            launchSafely("lockout") { host.parentalAnswer(ParentalAnswer.LockedUntil(pins.lockedUntil(clock()))) }
        }

        override fun openAnyway(source: GuideSource) = controls.openAnyway(source)
        override fun openAnyway(identity: ChannelIdentity) = controls.openAnyway(identity)
    }

    private fun launchSafely(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BoxLog.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "ParentalEffects"
    }
}

/** WP-C's `:ui` bindings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ParentalUiModule {
    @Binds abstract fun parentalEffects(impl: ParentalEffectsImpl): ParentalEffects
}
