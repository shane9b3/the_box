package app.thebox.data.parental

import app.thebox.core.input.KeyBindingStore
import app.thebox.core.parental.ParentalControls
import app.thebox.core.parental.ParentalPinStore
import app.thebox.core.parental.ParentalSession
import app.thebox.core.playback.TuneGuard
import app.thebox.data.input.KeyBindingStoreImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** WP-C's `:data` bindings (docs/design/m5-setup-settings.md §2.13). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ParentalModule {
    @Binds @Singleton abstract fun pinStore(impl: ParentalPinStoreImpl): ParentalPinStore
    @Binds @Singleton abstract fun session(impl: ParentalSessionImpl): ParentalSession
    @Binds @Singleton abstract fun controls(impl: ParentalSessionImpl): ParentalControls
    @Binds @Singleton abstract fun tuneGuard(impl: TuneGuardImpl): TuneGuard
    @Binds @Singleton abstract fun keyBindings(impl: KeyBindingStoreImpl): KeyBindingStore
    @Binds @Singleton abstract fun attempts(impl: PrefsAttemptsStore): AttemptsStore
    @Binds abstract fun lockoutClock(impl: AndroidLockoutClock): LockoutClock
}
