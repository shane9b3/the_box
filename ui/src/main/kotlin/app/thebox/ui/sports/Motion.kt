package app.thebox.ui.sports

import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext

/**
 * (Goal animation, docs/design/m6-goal-animation.md §7, S8) True when the system animations are off: the calling
 * coroutine's [MotionDurationScale] is exactly 0. Call it from inside the effect that drives the animation: it is the
 * value Compose itself applies to `animateTo`, it follows the setting live, and it can never disagree with it.
 * (`SettingsUi`'s `animationsOff()` stays for the searching dots.)
 */
internal suspend fun motionOff(): Boolean = currentCoroutineContext()[MotionDurationScale]?.scaleFactor == 0f
