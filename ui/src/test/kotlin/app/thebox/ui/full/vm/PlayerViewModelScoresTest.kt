package app.thebox.ui.full.vm

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.thebox.core.full.PlayerLayer
import app.thebox.core.full.PlayerStateMachine
import app.thebox.core.full.PlayerUiState
import app.thebox.core.full.fakes.FakeArchivePlayback
import app.thebox.core.full.fakes.FakePlayerSettings
import app.thebox.core.full.fakes.FakePlayerStateMachine
import app.thebox.core.full.fakes.FakeProgrammeFeed
import app.thebox.core.guide.GuideRowsCacheFactory
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.ScreenMode
import app.thebox.core.guide.feed.GuideRowsCacheImpl
import app.thebox.core.guide.fakes.FakeDeviceClassSource
import app.thebox.core.guide.fakes.FakeGuideLibrary
import app.thebox.core.guide.fakes.FakeGuideSettings
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.core.guide.fakes.FakeReminderBook
import app.thebox.core.guide.fakes.GuideFixtures
import app.thebox.core.sports.DayState
import app.thebox.core.sports.DemandOwner
import app.thebox.core.sports.FakeScoreRepository
import app.thebox.core.sports.RemoveReason
import app.thebox.core.sports.ScoreCorner
import app.thebox.core.sports.ScoreEvent
import app.thebox.core.sports.SportsFixtures
import app.thebox.ui.full.PlayerDebugProbe
import app.thebox.ui.root.ShellEvent
import app.thebox.ui.shelltest.FakePlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * (Score bug commit 0, docs/design/m6-score-bug.md §2.3, §2.6, §6, A-B2) The player VM's score wiring: facts to the
 * machine, events to toasts, FULL demand, the follow effect, and nothing at all without a repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerViewModelScoresTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val machine = FakePlayerStateMachine()
    private val probe = object : PlayerDebugProbe {
        override var current: PlayerStateMachine? = null
        override val archive = null
        override fun register(machine: PlayerStateMachine?) { current = machine }
    }

    private fun vm(repo: FakeScoreRepository?): PlayerViewModel {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(
                context, FakePlaybackController(), FakeArchivePlayback(), FakeProgrammeFeed(), FakeGuideLibrary(GuideFixtures.channels(3)),
                GuideRowsCacheFactory { lib, scope -> GuideRowsCacheImpl(lib, scope) }, FakePlayerSettings(), FakeGuideSettings(),
                FakeDeviceClassSource(), FakePlayerStateMachine.factory(machine), probe, FakeReminderBook(),
                scores = java.util.Optional.ofNullable(repo),
            ) as T
        }
        val vm = ViewModelProvider(store, factory)[PlayerViewModel::class.java]
        idle()
        return vm
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun withoutARepositoryNothingIsWired() {
        val vm = vm(null)
        assertNull(vm.scoreBug)
        assertTrue(machine.scoreFacts.isEmpty())
        vm.effects.followGame(null) // no crash, no effect
    }

    @Test
    fun factsReachTheMachineAndFollowUsesTheBoard() {
        val repo = FakeScoreRepository()
        val vm = vm(repo)
        assertSame(repo, vm.scoreBug)
        val game = SportsFixtures.game()
        repo.day.value = DayState.Board(SportsFixtures.board(game), fetchedAtMono = 1L, failing = false); idle()
        repo.prefs.value = repo.prefs.value.copy(corner = ScoreCorner.BOTTOM_LEFT, delaySec = 30); idle()
        val last = machine.scoreFacts.last()
        assertEquals(ScoreCorner.BOTTOM_LEFT, last.corner)
        assertEquals(30, last.delaySec)
        assertTrue(last.day is DayState.Board)

        vm.effects.followGame(game.id); idle()
        assertEquals(listOf(game), repo.followCalls)
        assertEquals(SportsFixtures.followedOf(game), machine.scoreFacts.last().game)
        vm.effects.scorePicker(true)
        vm.effects.retryScores()
        assertTrue(DemandOwner.SCORE_PICKER in repo.demandOn)
        assertEquals(1, repo.retries)
    }

    @Test
    fun fullWithoutScreenOffIsDemandAndEventsAreToasts() {
        val repo = FakeScoreRepository()
        val vm = vm(repo)
        val guide = FakeGuideStateMachine(GuideUiState(mode = ScreenMode.GUIDE))
        vm.attachGuide(guide)
        val out = ArrayList<String>()
        CoroutineScope(Dispatchers.Main.immediate).launch { vm.events.collect { e -> if (e is ShellEvent.Message) out += e.text } }
        idle()
        assertTrue(DemandOwner.FULL_WITH_FOLLOW !in repo.demandOn)
        guide.mutableUi.value = GuideUiState(mode = ScreenMode.FULL); idle()
        assertTrue(DemandOwner.FULL_WITH_FOLLOW in repo.demandOn)
        machine.mutableUi.value = PlayerUiState(layer = PlayerLayer.SCREEN_OFF); idle()
        assertTrue(DemandOwner.FULL_WITH_FOLLOW !in repo.demandOn)

        val f = SportsFixtures.followedOf(SportsFixtures.game())
        repo.emit(ScoreEvent.Removed(RemoveReason.POSTPONED, f)); idle()
        assertEquals("CGY – EDM was postponed. Follow another game in Sports.", out.last())
        repo.emit(ScoreEvent.Followed(f, first = false)); idle()
        assertEquals("Following CGY – EDM.", out.last())
    }
}
