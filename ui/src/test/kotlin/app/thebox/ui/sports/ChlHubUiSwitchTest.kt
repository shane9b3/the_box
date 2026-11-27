package app.thebox.ui.sports

import app.thebox.core.sports.SportsConstants
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (CHL hub integration, seam P3 (c)) The panel's switch is the build's: `ChlHubUi.enabled` reads
 * `SportsConstants.CHL_HUB_ENABLED` (the constant `GameDetailRepositoryImpl.detailed` reads in `:data`) unless a test set
 * it, and no production source writes it, so the data and the panel can never disagree (data on and UI off, or the
 * reverse).
 */
class ChlHubUiSwitchTest {
    @Test fun theSwitchReadsTheBuildConstantAndATestOverrideClears() {
        assertEquals(SportsConstants.CHL_HUB_ENABLED, ChlHubUi.enabled)
        ChlHubUi.enabled = !SportsConstants.CHL_HUB_ENABLED
        try {
            assertEquals(!SportsConstants.CHL_HUB_ENABLED, ChlHubUi.enabled)
            assertEquals(!SportsConstants.CHL_HUB_ENABLED, ChlHubUi.detailed)
        } finally {
            ChlHubUi.enabled = SportsConstants.CHL_HUB_ENABLED
        }
        assertEquals(SportsConstants.CHL_HUB_ENABLED, ChlHubUi.enabled)
        assertEquals(SportsConstants.CHL_HUB_ENABLED, ChlHubUi.detailed)
        assertEquals(if (SportsConstants.CHL_HUB_ENABLED) HubSource.HOCKEYTECH else HubSource.NONE, ChlHubUi.source)
    }

    @Test fun noProductionSourceWritesTheSwitch() {
        // The unit tests run in the module directory (`ui/`); the app module is its sibling.
        val roots = listOf(File("src/main"), File("../app/src/main"), File("../data/src/main"))
        assertTrue("the :ui sources are found from ${File(".").absolutePath}", roots[0].isDirectory)
        val external = Regex("""ChlHubUi\s*\.\s*enabled\s*=(?!=)""")
        val inside = Regex("""\b(testOverride|enabled)\s*=(?!=)""")
        val offenders = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                val own = f.name == "ChlHubUi.kt"
                f.readLines().withIndex().filter { (_, line) ->
                    // ChlHubUi itself may assign the override only in the test-only setter.
                    if (own) inside.containsMatchIn(line) && !line.trim().startsWith("testOverride = value")
                    else external.containsMatchIn(line)
                }.map { "${f.path}:${it.index + 1}" }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
