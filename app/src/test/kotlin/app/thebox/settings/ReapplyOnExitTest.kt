package app.thebox.settings

import app.thebox.core.catchup.archive.ArchivePhase
import app.thebox.core.catchup.archive.ArchivePlayback
import app.thebox.core.catchup.archive.ArchiveStatus
import app.thebox.core.guide.GuideEvent
import app.thebox.core.input.BoxAction
import app.thebox.core.settings.SectionId
import app.thebox.core.settings.SettingsRoute
import app.thebox.core.settings.fakes.FakeSettingsStore
import app.thebox.core.settings.state.ExternalPlayerApp
import app.thebox.core.settings.state.SettingsMachineImpl
import app.thebox.core.settings.state.SettingsTexts
import app.thebox.core.settings.state.WpaPageEnv
import app.thebox.core.settings.state.WpaPages
import app.thebox.core.settings.tree.InterfaceRows
import app.thebox.core.settings.tree.PlaybackRows
import app.thebox.player.api.PlaybackController
import app.thebox.ui.settings.vm.SettingsEffectsImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * S9 / §5 item 3: REPREPARE-class changes are applied exactly once when Settings is left, by any route, and only when
 * such a row changed; LIVE-only visits cost zero controller calls; an archive session is re-requested at the playhead
 * instead of a live re-prepare (paused archive: left paused). Machine + effects + applier composed with fakes.
 */
class ReapplyOnExitTest {
    private class Rig(phase: ArchivePhase = ArchivePhase.IDLE) {
        var reapplies = 0
        val seeks = ArrayList<Pair<Long, Long>>()
        val status = MutableStateFlow(ArchiveStatus(phase = phase, wallMs = 50_000, atMs = 1_000, advancing = true))
        val controller: PlaybackController = proxy { name, _ -> if (name == "reapplySettings") reapplies++; null }
        val archive: ArchivePlayback = proxy { name, args ->
            when (name) {
                "getStatus" -> status
                "seekTo" -> { seeks += (args!![0] as Long) to (args[1] as Long); null }
                else -> null
            }
        }
        val applier = LiveSettingsApplierImpl(controller, archive) { 3_000 }
        val store = FakeSettingsStore()
        val env = object : WpaPageEnv {
            override val sdkInt = 30
            override val deviceLowEnd = false
            override fun system24h() = true
            override fun externalPlayers() = emptyList<ExternalPlayerApp>()
            override fun runningSoftwareAudioFallback() = true
            override fun restartNow() = Unit
            override fun keyOverrideCount() = 0
        }
        val scope = TestScope()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val effects = SettingsEffectsImpl(listOf(WpaPages(store, SettingsTexts.KEYS, env)), store, applier, SettingsTexts.KEYS, null, scope, dispatcher, dispatcher) { 0 }
        val machine = SettingsMachineImpl(effects, { 0 }).also { effects.machine = it }

        fun open(route: SettingsRoute, detached: Boolean = false) { machine.open(route, fromFull = false, detached = detached); scope.runCurrent() }
        fun act(a: BoxAction): Boolean = machine.act(GuideEvent.Act(a)).also { scope.runCurrent() }
        fun chooseNext(row: app.thebox.core.settings.RowId) {
            while (machine.ui.value.page?.let { SettingsMachineImpl.rowAt(it, machine.ui.value.cursor) }?.id != row) act(BoxAction.DOWN)
            act(BoxAction.SELECT); act(BoxAction.DOWN); act(BoxAction.SELECT)
        }
        fun toggle(row: app.thebox.core.settings.RowId) {
            while (machine.ui.value.page?.let { SettingsMachineImpl.rowAt(it, machine.ui.value.cursor) }?.id != row) act(BoxAction.DOWN)
            act(BoxAction.SELECT)
        }
    }

    @Test fun liveOnlyVisitCostsNothing() {
        val r = Rig()
        r.store.update(app.thebox.core.settings.state.GuideDomain) { it.copy(advancedSettings = true) }
        r.machine.dispatch(app.thebox.core.settings.SettingsEvent.Advanced(true))
        r.open(SettingsRoute.Section(SectionId.INTERFACE))
        r.chooseNext(InterfaceRows.CLOCK)
        r.toggle(InterfaceRows.LOGOS)
        r.open(SettingsRoute.Section(SectionId.REMOTE))
        r.chooseNext(app.thebox.core.settings.tree.RemoteRows.UPDOWN)
        while (r.act(BoxAction.BACK)) Unit
        r.machine.onLeave()
        r.scope.runCurrent()
        assertEquals(0, r.reapplies)
        assertEquals(1, r.store.flushes)
    }

    @Test fun audioOutputTunnelingAndBufferApplyOnceOnBackOut() {
        val r = Rig()
        r.store.update(app.thebox.core.settings.state.GuideDomain) { it.copy(advancedSettings = true) }
        r.machine.dispatch(app.thebox.core.settings.SettingsEvent.Advanced(true))
        r.open(SettingsRoute.Section(SectionId.PLAYBACK))
        r.chooseNext(PlaybackRows.BUFFER)
        r.chooseNext(PlaybackRows.AUDIO_OUTPUT)
        r.toggle(PlaybackRows.TUNNELING)
        assertEquals("nothing applies while browsing", 0, r.reapplies)
        while (r.act(BoxAction.BACK)) Unit
        r.machine.onLeave()
        r.machine.onLeave()
        assertEquals(1, r.reapplies)
    }

    @Test fun everyLeaveRouteAppliesOnce() {
        // The guide machine calls onLeave on every exit from SETTINGS (BACK at the root, the Guide key, FULL by a reminder
        // or the quick bar, a detached route's BACK): the settings machine reports once per visit whatever the route.
        for (leave in listOf("back", "guideKey", "modeSwitch", "detached")) {
            val r = Rig()
            if (leave == "detached") r.open(SettingsRoute.Section(SectionId.PLAYBACK), detached = true) else r.open(SettingsRoute.Section(SectionId.PLAYBACK))
            r.chooseNext(PlaybackRows.BUFFER)
            when (leave) {
                "back", "detached" -> { while (r.act(BoxAction.BACK)) Unit }
                "guideKey" -> assertEquals(false, r.act(BoxAction.OPEN_GUIDE))
                "modeSwitch" -> Unit
            }
            r.machine.onLeave()
            assertEquals(leave, 1, r.reapplies)
        }
    }

    @Test fun archiveIsReRequestedAtThePlayheadNotRePrepared() {
        val r = Rig(ArchivePhase.PLAYING)
        r.open(SettingsRoute.Section(SectionId.PLAYBACK))
        r.chooseNext(PlaybackRows.AUDIO_OUTPUT)
        r.machine.onLeave()
        assertEquals(0, r.reapplies)
        assertEquals(listOf(52_000L to 0L), r.seeks)
    }

    @Test fun pausedArchiveStaysPaused() {
        val r = Rig(ArchivePhase.PAUSED)
        r.open(SettingsRoute.Section(SectionId.PLAYBACK))
        r.chooseNext(PlaybackRows.BUFFER)
        r.machine.onLeave()
        assertEquals(0, r.reapplies)
        assertEquals(emptyList<Pair<Long, Long>>(), r.seeks)
    }

    private companion object {
        inline fun <reified T> proxy(crossinline handler: (String, Array<Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, m, args ->
                when (m.name) {
                    "toString" -> T::class.java.simpleName
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    else -> handler(m.name, args)
                }
            } as T
    }
}
