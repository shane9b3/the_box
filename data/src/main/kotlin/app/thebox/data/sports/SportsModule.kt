package app.thebox.data.sports

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.thebox.core.common.BoxLog
import app.thebox.core.sports.DeviceAwake
import app.thebox.core.sports.League
import app.thebox.core.sports.ScoreRepository
import app.thebox.core.sports.ScoreSource
import app.thebox.data.di.ApiClient
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.MapKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * (docs/design/m6-score-bug.md §2.7, §9 WP-2) Binds the real [ScoreRepository], so `:ui`'s `Optional<ScoreRepository>`
 * (`SportsUiModule`'s `@BindsOptionalOf`) becomes present. [DeviceAwake] is optional here too: `:player` binds it in the
 * app; a graph without `:player` (`:data`'s instrumented tests) gets `DeviceAwake.ALWAYS`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SportsModule {
    @Binds abstract fun scoreRepository(impl: ScoreRepositoryImpl): ScoreRepository

    @BindsOptionalOf abstract fun deviceAwake(): DeviceAwake

    companion object {
        @Provides
        @Singleton
        @IntoMap
        @LeagueKey(League.NHL)
        fun nhlScoreSource(@ApiClient client: OkHttpClient, @ApplicationContext context: Context): ScoreSource =
            NhlScoreSource(client, ScoreRepositoryImpl.ELAPSED, endpoints = { SportsEndpoints.resolve(context) })

        /** (NFL §1.5, §7.1) ESPN's site scoreboard. */
        @Provides
        @Singleton
        @IntoMap
        @LeagueKey(League.NFL)
        fun nflScoreSource(@ApiClient client: OkHttpClient, @ApplicationContext context: Context): ScoreSource =
            EspnScoreSource(client, ScoreRepositoryImpl.ELAPSED, endpoints = { EspnEndpoints.resolve(context) })

        @Provides
        @Singleton
        fun signals(@ApplicationContext context: Context): SportsSignals = AndroidSportsSignals(context)
    }
}

/** (NFL §7.1) The map key of the per-league [ScoreSource]s (`ScoreRepositoryImpl` takes `Map<League, ScoreSource>`). */
@MapKey
annotation class LeagueKey(val value: League)

/**
 * [SportsSignals] from the platform (§2.3, §2.4): `ProcessLifecycleOwner` STARTED, and the default-network callback
 * (`onAvailable` / `onLost`; `VALIDATED` is not required, A-S12). Registered once, at injection.
 */
internal class AndroidSportsSignals(context: Context) : SportsSignals {
    private val _foreground = MutableStateFlow(false)
    private val _online = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = _foreground.asStateFlow()
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        Handler(Looper.getMainLooper()).post {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) { _foreground.value = true }
                    override fun onStop(owner: LifecycleOwner) { _foreground.value = false }
                })
            } catch (e: Exception) {
                BoxLog.w(TAG, "lifecycle observer not installed: ${e.javaClass.simpleName}")
            }
        }
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                // The default network can switch without a loss in between: only the loss of the current one is offline.
                var current: Network? = null
                _online.value = cm.activeNetwork != null
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(this) { current = network }
                        _online.value = true
                    }

                    override fun onLost(network: Network) {
                        synchronized(this) {
                            if (current != null && current != network) return
                            current = null
                        }
                        _online.value = false
                    }
                })
            }
        } catch (e: Exception) {
            BoxLog.w(TAG, "network callback not installed: ${e.javaClass.simpleName}")
            _online.value = true // never block the feature on a missing callback
        }
    }

    private companion object {
        const val TAG = "SportsSignals"
    }
}
