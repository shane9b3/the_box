package app.thebox.data.editor

import app.thebox.core.editor.ChannelSort
import app.thebox.core.editor.ChannelTarget
import app.thebox.core.editor.EditorList
import app.thebox.core.editor.EditorWrite
import app.thebox.core.editor.GroupSort
import app.thebox.core.editor.GroupTarget
import app.thebox.core.editor.WriteResult
import app.thebox.data.guide.exec
import app.thebox.data.testutil.strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** docs/design/m6-channel-editor.md §6.2 `EditorOrderWritesTest` (§3.5 order rules). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorOrderWritesTest {
    private lateinit var w: EditorWorld
    private val p get() = w.p
    private val g = "g:a"

    @Before fun setUp() = runBlocking<Unit> {
        w = EditorWorld()
        w.group(g, "A", sort = 0)
        w.group("g:b", "B", sort = 1)
    }

    @After fun tearDown() { w.close() }

    /** (key, custom_sort_index) of every channel_prefs row with an index, by index. */
    private suspend fun order(): List<String> =
        w.db.strings("SELECT stable_key || '=' || custom_sort_index FROM channel_prefs WHERE custom_sort_index IS NOT NULL ORDER BY custom_sort_index, stable_key")

    @Test
    fun denseRewriteCoversHiddenRuleHiddenMovedInAndMissingMembers() = runBlocking {
        w.channel("c1", g, 0); w.channel("c2", g, 1); w.channel("c3", g, 2); w.channel("c4", g, 3, missing = 5L); w.channel("c5", g, 4)
        w.channel("m", "g:b", 5)
        w.write(EditorWrite.HideChannels(p, ChannelTarget.Keys(setOf("c2")), true))
        w.db.exec("UPDATE channel SET rule_hidden = 1 WHERE stable_key = 'c3'")
        w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("m")), g))
        // The move materialised the provider order (c4 missing keeps its slot), m appended.
        assertEquals(listOf("c1=0", "c2=1", "c3=2", "c4=3", "c5=4", "m=5"), order())
        assertEquals(listOf("c1", "c2", "c3", "c5", "m"), w.list(EditorList.Group(g)))
        // c1 dropped after c5 in the visible list (4 visible rows remain after lifting c1, toIndex 3).
        assertEquals(WriteResult.Done(1), w.write(EditorWrite.ReorderChannels(p, g, listOf("c1"), 3)))
        assertEquals(listOf("c2", "c3", "c5", "c1", "m"), w.list(EditorList.Group(g)))
        assertEquals("the missing member keeps its slot, nothing collides", listOf("c2=0", "c3=1", "c5=2", "c4=3", "c1=4", "m=5"), order())
    }

    @Test
    fun blockSpliceFollowsEditorMoves() = runBlocking {
        for ((i, k) in listOf("a", "b", "c", "d", "e").withIndex()) w.channel(k, g, i)
        w.write(EditorWrite.ReorderChannels(p, g, listOf("b", "d"), 2))
        assertEquals(listOf("a", "c", "b", "d", "e"), w.list(EditorList.Group(g)))
        w.write(EditorWrite.ReorderChannels(p, g, listOf("e", "a"), 0))
        assertEquals("the block keeps its own order", listOf("e", "a", "c", "b", "d"), w.list(EditorList.Group(g)))
        w.write(EditorWrite.ReorderChannels(p, g, listOf("c"), 99))
        assertEquals("clamped", listOf("e", "a", "b", "d", "c"), w.list(EditorList.Group(g)))
    }

    @Test
    fun nameSortUsesACollator() = runBlocking {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        try {
            for ((i, n) in listOf("Général", "beta", "General", "Zed", "alpha").withIndex()) w.channel("k$i", g, i, name = n)
            w.write(EditorWrite.SortChannels(p, g, ChannelSort.NAME_AZ))
            val names = w.repo.channelRows(w.repo.channels(p, EditorList.Group(g), app.thebox.core.editor.EditorFilter.ALL, app.thebox.core.parental.ParentalAccess.OPEN).ids, 0, 5).map { it!!.name }
            assertEquals(listOf("alpha", "beta", "General", "Général", "Zed"), names)
            w.write(EditorWrite.SortChannels(p, g, ChannelSort.NAME_ZA))
            assertEquals(listOf("k3", "k0", "k2", "k1", "k4"), w.list(EditorList.Group(g)))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun numberSortUsesTheShownNumberAndProviderOrderClears() = runBlocking {
        w.channel("x", g, 0, number = 30); w.channel("y", g, 1, number = 10); w.channel("z", g, 2, number = 20)
        w.write(EditorWrite.SetNumber(p, "x", 5))
        w.write(EditorWrite.SortChannels(p, g, ChannelSort.NUMBER))
        assertEquals(listOf("x", "y", "z"), w.list(EditorList.Group(g)))
        w.write(EditorWrite.SortChannels(p, g, ChannelSort.NAME_ZA))
        assertEquals(listOf("z", "y", "x"), w.list(EditorList.Group(g)))
        w.write(EditorWrite.SortChannels(p, g, ChannelSort.PROVIDER))
        assertEquals(emptyList<String>(), order())
        assertEquals(listOf("x", "y", "z"), w.list(EditorList.Group(g)))
        val clash = w.write(EditorWrite.SetNumber(p, "y", 20)) as WriteResult.Done
        assertEquals("Z", clash.numberClash)
    }

    @Test
    fun movingIntoACustomOrderedTargetAppendsMaxPlusOne() = runBlocking {
        w.channel("a1", g, 0); w.channel("a2", g, 1); w.channel("b1", "g:b", 2); w.channel("b2", "g:b", 3)
        w.write(EditorWrite.ReorderChannels(p, g, listOf("a2"), 0)) // a2=0, a1=1
        w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("b2", "b1")), g))
        assertEquals(listOf("a2=0", "a1=1", "b1=2", "b2=3"), order())
        assertEquals("moved channels go to the end, in list order", listOf("a2", "a1", "b1", "b2"), w.list(EditorList.Group(g)))
    }

    @Test
    fun movingIntoAnUncustomisedTargetMaterialisesItThenAppends() = runBlocking {
        w.channel("a1", g, 4); w.channel("a2", g, 9); w.channel("b1", "g:b", 0)
        w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("b1")), g))
        assertEquals(listOf("a1=0", "a2=1", "b1=2"), order())
        assertEquals(listOf("a1", "a2", "b1"), w.list(EditorList.Group(g)))
        // Moving back home writes NULL group and NULL order.
        w.write(EditorWrite.MoveChannels(p, ChannelTarget.Keys(setOf("b1")), "g:b"))
        assertEquals("null" to "null", w.pref("b1", "custom_group_key") to w.pref("b1", "custom_sort_index"))
    }

    @Test
    fun groupOrderRewritesEveryGroupKeyHiddenIncluded() = runBlocking {
        w.group("g:c", "C", sort = 2); w.group("g:d", "d", sort = 3)
        w.write(EditorWrite.HideGroups(p, GroupTarget.Keys(setOf("g:b")), true))
        w.write(EditorWrite.ReorderGroups(p, listOf("g:d"), 0))
        assertEquals(listOf("g:d", g, "g:b", "g:c"), w.repo.groups(p, true, app.thebox.core.parental.ParentalAccess.OPEN).keys)
        assertEquals(
            listOf("g:d=0", "g:a=1", "g:b=2", "g:c=3"),
            w.db.strings("SELECT group_key || '=' || custom_sort_index FROM group_prefs ORDER BY custom_sort_index"),
        )
        w.write(EditorWrite.SortGroups(p, GroupSort.NAME_AZ))
        assertEquals(listOf(g, "g:b", "g:c", "g:d"), w.repo.groups(p, true, app.thebox.core.parental.ParentalAccess.OPEN).keys)
        w.write(EditorWrite.SortGroups(p, GroupSort.PROVIDER))
        assertEquals(listOf("null", "null", "null", "null"), w.db.strings("SELECT custom_sort_index FROM group_prefs ORDER BY group_key"))
        assertEquals("1", w.groupPref("g:b", "hidden"))
    }
}
