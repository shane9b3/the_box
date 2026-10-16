package app.thebox.core.settings.fakes

import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.input.KeyContext
import app.thebox.core.input.KeyOverride
import app.thebox.core.input.Press
import app.thebox.core.parental.ParentalPolicy
import app.thebox.core.parental.ParentalSession
import app.thebox.core.parental.ParentalState
import app.thebox.core.parental.RelockReason
import app.thebox.core.playback.TuneGuard
import app.thebox.core.playback.TuneVerdict
import app.thebox.core.refresh.ChannelIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ParentalSession] whose state the test sets ([state] is a MutableStateFlow). [unlock]/[relock] flip `unlocked` (only
 * when a PIN is set) and are recorded; [stayUnlockedUntilMs] makes [onTick] relock with TIMEOUT.
 */
class FakeParentalSession(
    initial: ParentalState = ParentalState(pinSet = false, unlocked = false, policy = ParentalPolicy()),
) : ParentalSession {
    override val state = MutableStateFlow(initial)
    val relocks = ArrayList<RelockReason>()
    var unlocks = 0
    var stayUnlockedUntilMs = Long.MAX_VALUE

    override fun unlock(nowMs: Long) {
        unlocks++
        if (state.value.pinSet) state.value = state.value.copy(unlocked = true)
    }
    override fun relock(reason: RelockReason) {
        relocks += reason
        state.value = state.value.copy(unlocked = false, openedAnyway = if (reason == RelockReason.APP_STOPPED) emptySet() else state.value.openedAnyway)
    }
    override fun onTick(nowMs: Long) {
        if (state.value.unlocked && nowMs >= stayUnlockedUntilMs) relock(RelockReason.TIMEOUT)
    }
}

/** [TuneGuard] with per-identity verdicts ([verdicts]; default [fallback]); records every check. */
class FakeTuneGuard(var fallback: TuneVerdict = TuneVerdict.ALLOWED) : TuneGuard {
    val verdicts = HashMap<ChannelIdentity, TuneVerdict>()
    val checked = ArrayList<ChannelIdentity>()
    override fun check(identity: ChannelIdentity): TuneVerdict {
        checked += identity
        return verdicts[identity] ?: fallback
    }
}

/**
 * [KeyBindingStore] that keeps overrides in memory with the "later binding wins" rule applied naively (one override per
 * (context, keyCode, press)); WP-C's `BindingRules` are tested on their own. [nowMs] stamps nothing: the caller passes it.
 */
class FakeKeyBindingStore(contexts: Set<KeyContext> = setOf(KeyContext.GUIDE, KeyContext.PLAYER)) : KeyBindingStore {
    private val ctx = contexts
    private val _overrides = MutableStateFlow(KeyBindingOverrides())
    override val overrides: StateFlow<KeyBindingOverrides> = _overrides.asStateFlow()

    private fun put(o: KeyOverride) {
        val rest = _overrides.value.entries.filterNot { it.context == o.context && it.keyCode == o.keyCode && it.press == o.press }
        _overrides.value = KeyBindingOverrides(entries = rest + o)
    }

    override fun bind(action: BoxAction, key: CapturedKey, press: Press, nowMs: Long) {
        for (c in ctx) put(KeyOverride(c, key.keyCode, press, action.id, key.deviceName, nowMs))
    }
    override fun unbind(context: KeyContext, keyCode: Int, press: Press, nowMs: Long) = put(KeyOverride(context, keyCode, press, null, null, nowMs))
    override fun resetKey(keyCode: Int) { _overrides.value = KeyBindingOverrides(entries = _overrides.value.entries.filterNot { it.keyCode == keyCode }) }
    override fun resetAction(action: BoxAction) { _overrides.value = KeyBindingOverrides(entries = _overrides.value.entries.filterNot { it.action == action.id }) }
    override fun resetAll() { _overrides.value = KeyBindingOverrides() }
}
