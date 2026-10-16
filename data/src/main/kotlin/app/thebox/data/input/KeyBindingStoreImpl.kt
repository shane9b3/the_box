package app.thebox.data.input

import app.thebox.core.input.BoxAction
import app.thebox.core.input.CapturedKey
import app.thebox.core.input.KeyBindingOverrides
import app.thebox.core.input.KeyBindingStore
import app.thebox.core.input.KeyContext
import app.thebox.core.input.Press
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Button-mapping overrides (docs/design/m5-setup-settings.md §2.7, §3.8; persisted in the `remote` settings domain).
 * **Commit-0 stub (WP-C replaces the body):** no overrides, every change ignored, so `DefaultKeyMap` stays in force.
 */
@Singleton
class KeyBindingStoreImpl @Inject constructor() : KeyBindingStore {
    private val _overrides = MutableStateFlow(KeyBindingOverrides())
    override val overrides: StateFlow<KeyBindingOverrides> = _overrides.asStateFlow()
    override fun bind(action: BoxAction, key: CapturedKey, press: Press, nowMs: Long) = Unit
    override fun unbind(context: KeyContext, keyCode: Int, press: Press, nowMs: Long) = Unit
    override fun resetKey(keyCode: Int) = Unit
    override fun resetAction(action: BoxAction) = Unit
    override fun resetAll() = Unit
}
