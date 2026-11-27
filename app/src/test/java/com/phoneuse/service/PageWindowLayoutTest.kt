package com.phoneuse.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageWindowLayoutTest {
    private val layout = PageWindowLayout<IntRange>()
    private val app = PageWindow(1, PageWindowRole.APPLICATION, 0..999)
    private val keyboard = PageWindow(2, PageWindowRole.KEYBOARD, 700..999)
    private val own = PageWindow(3, PageWindowRole.OWN_OVERLAY, 0..999)
    private val external = PageWindow(4, PageWindowRole.OTHER, 300..500)

    @Test fun keyboardShowingMovingAndHidingInvalidateEvenWhenTypingSuggestionsAreFiltered() {
        assertFalse(layout.update(listOf(app)))
        assertTrue(layout.update(listOf(keyboard, app)))
        assertEquals(setOf(keyboard.id), layout.ignoredContentWindows)
        assertFalse(layout.update(listOf(keyboard, app)))
        assertTrue(layout.update(listOf(keyboard.copy(region = 550..849), app)))
        assertTrue(layout.update(listOf(app)))
        assertTrue(layout.ignoredContentWindows.isEmpty())
    }

    @Test fun ownPassThroughIsQuietWhileForeignOverlaysChangingTheirShapeOrOrderInvalidate() {
        layout.update(listOf(app))
        assertFalse(layout.update(listOf(own, app)))
        assertFalse(layout.update(listOf(own.copy(region = 20..200), app)))
        assertTrue(layout.update(listOf(own, external, app)))
        assertEquals(setOf(own.id), layout.ignoredContentWindows)
        assertTrue(layout.update(listOf(own, external.copy(region = 300..600), app)))
        assertTrue(layout.update(listOf(app, external.copy(region = 300..600), own)))
        assertTrue(layout.update(listOf(app)))
    }

    @Test fun existingExternalCoverOrKeyboardBlocksOnlyThePointsInFrontOfAnApplication() {
        fun exposed(windows: List<PageWindow<IntRange>>, coordinate: Int) =
            layout.appExposedAt(windows) { coordinate in it }
        assertTrue(exposed(listOf(own, external, keyboard, app), 250))
        assertFalse(exposed(listOf(own, external, keyboard, app), 400))
        assertFalse(exposed(listOf(own, external, keyboard, app), 800))
        assertTrue(exposed(listOf(own, app, external), 400))
        assertFalse(exposed(listOf(own), 400))
        assertFalse(exposed(emptyList(), 400))
    }
}
