package app.thebox.core.parental

/**
 * (M5 commit 0, docs/design/m5-setup-settings.md §2.12 item 9, S10) What the six `GuideLibrary`/`GuideSearch` call
 * sites pass until WP-C feeds them from `ParentalSession.state`: M3's debug switch (`GuideSettings.debugUnlocked`,
 * `thebox-debug://guide?unlocked=1`) as OPEN, else LOCKED_AUTO, which the commit-0 library maps to M3's
 * `unlocked = false`. Behaviour is unchanged. WP-C deletes this file when the call sites read the session.
 */
fun legacyParentalAccess(debugUnlocked: Boolean): ParentalAccess =
    if (debugUnlocked) ParentalAccess.OPEN else ParentalAccess.LOCKED_AUTO

/** (M5 commit 0) The M3 predicate's flag for [access] until WP-C's §4.3 predicate binds the ordinal. */
val ParentalAccess.legacyUnlocked: Boolean get() = this == ParentalAccess.OPEN
