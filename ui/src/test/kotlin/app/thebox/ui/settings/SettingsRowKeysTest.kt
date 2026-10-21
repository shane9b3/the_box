package app.thebox.ui.settings

import app.thebox.core.settings.PageModel
import app.thebox.core.settings.PagedList
import app.thebox.core.settings.RowId
import app.thebox.core.settings.RowKind
import app.thebox.core.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsRowKeysTest {
    private fun row(id: String) = app.thebox.core.settings.SettingsRow(RowId(id), id, kind = RowKind.Toggle(false))
    private fun page(rows: List<app.thebox.core.settings.SettingsRow> = emptyList(), list: PagedList) =
        PageModel(SettingsRoute.Root, "Channels", rows = rows, list = list, version = 1)

    @Test fun hugeUnloadedCountDoesNotRequireProportionalStorageOrTraversal() {
        val p = page(list = PagedList(count = 1_000_000_000, loaded = mapOf(0 to listOf(row("first")), 31_249_999 to listOf(row("last-page")))))
        val keys = SettingsRowKeys(p)
        assertEquals("r:first", keys.keyAt(0))
        assertEquals("i:500000000", keys.keyAt(500_000_000))
        assertEquals("r:last-page", keys.keyAt(999_999_968))
    }

    @Test fun duplicatesUseLogicalOrderAcrossFixedAndPagedRows() {
        val p = page(rows = listOf(row("fixed")), list = PagedList(100, loaded = linkedMapOf(
            1 to listOf(row("duplicate"), row("fixed")),
            0 to listOf(row("duplicate"), row("unique")),
        )))
        val keys = SettingsRowKeys(p)
        assertEquals("r:fixed", keys.keyAt(0))
        assertEquals("r:duplicate", keys.keyAt(1))
        assertEquals("i:33", keys.keyAt(33))
        assertEquals("i:34", keys.keyAt(34))
        assertNotEquals(keys.keyAt(0), keys.keyAt(34))
    }

    @Test fun identitiesSurvivePagingAndCannotCollideWithPlaceholders() {
        val p = page(list = PagedList(100, loaded = mapOf(0 to listOf(row("i:32")))))
        val list = requireNotNull(p.list)
        val before = SettingsRowKeys(p)
        val after = SettingsRowKeys(p.copy(list = list.copy(loaded = list.loaded + (1 to listOf(row("next"))))))
        assertEquals(before.keyAt(0), after.keyAt(0))
        assertNotEquals(before.keyAt(0), before.keyAt(32))
        assertEquals("r:next", after.keyAt(32))
    }
}
