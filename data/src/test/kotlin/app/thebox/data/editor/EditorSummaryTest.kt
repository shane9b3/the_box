package app.thebox.data.editor

import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorFilter
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.SelectionSummary
import app.thebox.core.parental.ParentalAccess
import app.thebox.data.guide.exec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** docs/design/m6-channel-editor.md §6.2 `EditorSummaryTest`: the menu's counts over Keys and AllOf, under each access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorSummaryTest {
    private lateinit var w: EditorWorld
    private val p get() = w.p

    @Before
    fun setUp() = runBlocking<Unit> {
        w = EditorWorld()
        w.group("g:news", "News", sort = 0)
        w.group("g:adult", "Adult", sort = 1, adult = true)
        w.channel("n1", "g:news", 0); w.channel("n2", "g:news", 1); w.channel("n3", "g:news", 2); w.channel("n4", "g:news", 3)
        w.channel("a1", "g:adult", 4); w.channel("a2", "g:adult", 5)
        w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("n1")), true))
        w.db.exec("UPDATE channel SET rule_hidden = 1 WHERE stable_key IN ('n1', 'n2')")
        w.write(EditorWrite.LockChannels(p, ChannelTarget.Keys(setOf("n3")), true))
        w.write(EditorWrite.Favorite(p, ChannelTarget.Keys(setOf("n3", "a1")), true))
    }

    @After fun tearDown() { w.close() }

    private fun s(total: Int, hidden: Int, rule: Int, locked: Int, byGroup: Int, fav: Int) = SelectionSummary(total, hidden, rule, locked, byGroup, fav, byGroup)

    @Test
    fun keys() = runBlocking {
        // n1 is hidden by the user and by a rule: it counts as user-hidden only.
        assertEquals(s(3, 1, 1, 1, 0, 1), w.repo.summarize(p, ChannelTarget.Keys(setOf("n1", "n2", "n3")), ParentalAccess.OPEN))
        assertEquals(s(2, 0, 0, 2, 2, 1), w.repo.summarize(p, ChannelTarget.Keys(setOf("a1", "a2", "nope")), ParentalAccess.LOCKED_AUTO))
    }

    @Test
    fun allOfUnderEachAccess() = runBlocking {
        val all = ChannelTarget.AllOf(EditorList.All, EditorFilter.ALL, emptySet())
        assertEquals(s(6, 1, 1, 3, 2, 2), w.repo.summarize(p, all, ParentalAccess.OPEN))
        assertEquals(s(5, 1, 1, 2, 2, 1), w.repo.summarize(p, all.copy(except = setOf("n3", "missing")), ParentalAccess.OPEN))
        assertEquals("no PIN filters nothing", s(6, 1, 1, 3, 2, 2), w.repo.summarize(p, all, ParentalAccess.FILTERED_NO_PIN))
        // Locked: the adult group and the explicitly locked n3 are not listed, so they are not targets.
        assertEquals(s(3, 1, 1, 0, 0, 0), w.repo.summarize(p, all, ParentalAccess.LOCKED_AUTO))
        // Manual only: adult detection does not lock, the explicit lock still does.
        assertEquals(s(5, 1, 1, 2, 2, 1), w.repo.summarize(p, all, ParentalAccess.LOCKED_MANUAL_ONLY))
        assertEquals(s(0, 0, 0, 0, 0, 0), w.repo.summarize(p, ChannelTarget.AllOf(EditorList.Group("g:adult"), EditorFilter.ALL, emptySet()), ParentalAccess.LOCKED_AUTO))
    }

    @Test
    fun allOfWithAFilter() = runBlocking {
        val hidden = ChannelTarget.AllOf(EditorList.Group("g:news"), EditorFilter.HIDDEN, emptySet())
        assertEquals(s(2, 1, 1, 0, 0, 0), w.repo.summarize(p, hidden, ParentalAccess.OPEN))
        val shown = ChannelTarget.AllOf(EditorList.Group("g:news"), EditorFilter.SHOWN, setOf("n4"))
        assertEquals(s(1, 0, 0, 1, 0, 1), w.repo.summarize(p, shown, ParentalAccess.OPEN))
    }
}
