package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLayoutTest {
    @Test
    fun rotatesPortraitIphoneIntoLandscapeAndPreservesAspect() {
        val layout = VideoLayoutCalculator.calculate(1170, 2532, 1280, 800)

        assertTrue(layout.rotateClockwise)
        assertEquals(1f, layout.scaleX, 0.0001f)
        assertEquals(0.7393f, layout.scaleY, 0.001f)
    }

    @Test
    fun letterboxesLandscapeVideoWithoutRotation() {
        val layout = VideoLayoutCalculator.calculate(1920, 1080, 1280, 800)

        assertFalse(layout.rotateClockwise)
        assertEquals(1f, layout.scaleX, 0.0001f)
        assertEquals(0.9f, layout.scaleY, 0.0001f)
    }

    @Test
    fun pillarboxesNarrowLandscapeVideoWithoutDistortion() {
        val layout = VideoLayoutCalculator.calculate(1280, 1024, 1280, 800)

        assertFalse(layout.rotateClockwise)
        assertEquals(0.78125f, layout.scaleX, 0.0001f)
        assertEquals(1f, layout.scaleY, 0.0001f)
    }
}
