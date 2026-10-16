package app.thebox.core.parental

/**
 * (M5 commit 0, docs/design/m5-setup-settings.md §2.12 item 9, S10) M3's debug switch (`GuideSettings.debugUnlocked`,
 * `thebox-debug://guide?unlocked=1`) as an access value: OPEN, else LOCKED_AUTO. Since WP-C the call sites feed the
 * session's access through `ui/.../parental/ParentalGuideLibrary` (which keeps a requested OPEN, i.e. the debug switch,
 * and replaces every other value with the session's); this mapping only carries the debug switch there. Kept while
 * `GuideEffectsImpl` (commit-0 skeleton) and `EpgMatchRepository` (WP-B) still call it.
 */
fun legacyParentalAccess(debugUnlocked: Boolean): ParentalAccess =
    if (debugUnlocked) ParentalAccess.OPEN else ParentalAccess.LOCKED_AUTO

/**
 * (M5 commit 0) M3's `unlocked` flag for [this] access, for the M1–M4 DAO signatures that still take a Boolean
 * (`ChannelDao.PARENTAL`: true = OPEN, false = LOCKED_AUTO). New code binds the ordinal (`ChannelDao.PARENTAL_ACCESS`).
 */
val ParentalAccess.legacyUnlocked: Boolean get() = this == ParentalAccess.OPEN
