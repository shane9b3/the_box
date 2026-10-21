package app.thebox.ui.root

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.thebox.core.guide.GuideUiState
import app.thebox.core.guide.fakes.FakeGuideStateMachine
import app.thebox.ui.shelltest.FakeShellHost
import app.thebox.ui.theme.BoxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-xhdpi")
class StartupReadyTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun readinessWaitsForStartupDecisionAndIsOnlyReportedOnce() {
        val machine = FakeGuideStateMachine(GuideUiState(booting = true))
        val host = FakeShellHost(machine)
        var reports = 0
        rule.setContent { BoxTheme { AppRoot(host, onReady = { reports++ }, grid = { Spacer(it) }) } }
        rule.waitForIdle()
        assertEquals(0, reports)
        rule.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(booting = false) }
        rule.waitForIdle()
        assertEquals(1, reports)
        rule.runOnIdle { machine.mutableUi.value = machine.mutableUi.value.copy(sourceLabel = "Another group") }
        rule.waitForIdle()
        assertEquals(1, reports)
    }
}
