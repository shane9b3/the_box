package app.thebox.ui.diag

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.thebox.ui.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** U-L15 (M5 code review 2026-10-16): dead placeholder strings gone, next steps present, every Apache-2.0 dependency credited. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StringsReviewFixTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun placeholderStringsAreGoneAndTextsCarryASteps() {
        val names = R.string::class.java.fields.map { it.name }.toSet()
        for (dead in listOf("settings_title", "settings_version", "settings_coming", "settings_back")) assertFalse(dead, dead in names)
        assertTrue(context.getString(R.string.settings_page_not_available).contains("Back"))
        assertTrue(context.getString(R.string.about_update_value_failed).contains("OK"))
        for (id in listOf(R.string.settings_ch_external_none, R.string.settings_ch_external_failed, R.string.setup_ua_fallback_note)) {
            assertFalse(context.getString(id), context.getString(id).contains("Settings ›"))
        }
        val apache = context.getString(R.string.licences_apache)
        for (lib in listOf("Media3", "AndroidX", "Compose", "Kotlin", "Hilt", "OkHttp", "Tink", "ACRA", "Coil", "AndroidSVG")) assertTrue(lib, apache.contains(lib))
    }
}
