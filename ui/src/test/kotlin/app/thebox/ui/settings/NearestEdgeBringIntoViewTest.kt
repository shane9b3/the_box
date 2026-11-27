package app.thebox.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner finding 2026-11-26: the settings page must not slide while the focused option is already on screen. */
class NearestEdgeBringIntoViewTest {
    private fun d(offset: Float, size: Float, container: Float = 800f) = NearestEdgeBringIntoView.calculateScrollDistance(offset, size, container)

    @Test fun aVisibleOptionDoesNotScroll() {
        assertEquals(0f, d(0f, 60f), 0f)
        assertEquals(0f, d(300f, 60f), 0f)
        assertEquals(0f, d(740f, 60f), 0f)
    }

    @Test fun anOptionBelowScrollsJustIntoView() = assertEquals(40f, d(780f, 60f), 0f)

    @Test fun anOptionAboveScrollsJustIntoView() = assertEquals(-25f, d(-25f, 60f), 0f)

    @Test fun anItemCoveringTheViewportDoesNotScroll() = assertEquals(0f, d(-10f, 900f), 0f)
}
