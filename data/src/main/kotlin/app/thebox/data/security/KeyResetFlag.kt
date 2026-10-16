package app.thebox.data.security

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * (M5 commit 0, docs/design/m5-setup-settings.md §2.12 item 8, §3.9.6, S8) "The PIN was lost to a Keystore reset":
 * written by [TinkCredentialStore]'s permanent-loss wipe, read by WP-C's `ParentalSessionImpl` as
 * `ParentalState.pinLostToKeyReset` (content stays FILTERED_NO_PIN until a new PIN is set, which clears it).
 */
interface KeyResetFlag {
    fun markPinLost()
    fun pinLost(): Boolean
    fun clearPinLost()
}

/** SharedPreferences `parental_attempts` (§4.1: not backed up, not restored), key `pin_lost_to_key_reset`. */
@SuppressLint("ApplySharedPref")
@Singleton
class PrefsKeyResetFlag @Inject constructor(@ApplicationContext context: Context) : KeyResetFlag {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Synchronous (`commit`): the wipe runs on IO and the flag must survive a process kill right after it. */
    override fun markPinLost() {
        prefs.edit().putBoolean(KEY_PIN_LOST, true).commit()
    }

    override fun pinLost(): Boolean = prefs.getBoolean(KEY_PIN_LOST, false)

    override fun clearPinLost() {
        prefs.edit().remove(KEY_PIN_LOST).commit()
    }

    companion object {
        const val PREFS = "parental_attempts"
        const val KEY_PIN_LOST = "pin_lost_to_key_reset"
    }
}
