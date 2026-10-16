package app.thebox.data.input

import app.thebox.core.input.BindingRules
import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.DefaultKeyMap
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.input.KeyContext
import app.thebox.core.input.Press
import app.thebox.core.input.RemoteDomain
import app.thebox.core.settings.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Button-mapping overrides (docs/design/m5-setup-settings.md §2.7, §3.8): the `remote` settings domain
 * ([RemoteDomain], backed up), changed through [BindingRules] (reserved keys refused, later binding wins, cap 200).
 * Main-safe: `SettingsStore.update` applies in memory at once and persists on IO; `RemappedKeyMap` rebuilds its snapshot
 * off the main thread when [overrides] emits.
 */
@Singleton
class KeyBindingStoreImpl @Inject constructor(private val store: SettingsStore) : KeyBindingStore {
    private val defaults = DefaultKeyMap()

    override val overrides: StateFlow<KeyBindingOverrides> = store.flow(RemoteDomain)

    override fun bind(action: BoxAction, key: CapturedKey, press: Press, nowMs: Long) =
        store.update(RemoteDomain) { BindingRules.save(it, action, key, press, nowMs) }

    override fun unbind(context: KeyContext, keyCode: Int, press: Press, nowMs: Long) =
        store.update(RemoteDomain) { BindingRules.unbind(it, context, keyCode, press, nowMs) }

    override fun resetKey(keyCode: Int) = store.update(RemoteDomain) { BindingRules.resetKey(it, keyCode) }

    override fun resetAction(action: BoxAction) = store.update(RemoteDomain) { BindingRules.resetAction(it, action, defaults) }

    override fun resetAll() = store.update(RemoteDomain) { BindingRules.resetAll(it) }
}
