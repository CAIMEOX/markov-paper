package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SeaVillaModelTest {
    @Test
    fun `preview command seed reaches a visible building frame`() {
        val xml = requireNotNull(javaClass.getResource("/models/sea-villa.xml")).readText()

        val model = MarkovXmlCompiler.compile(xml, 10, 10, 4, seed = 29)
        val firstVisibleFrame = model.node.firstVisibleFrameWithin(maxSteps = 1_024)

        assertEquals(95, model.grid.sizeX)
        assertEquals(95, model.grid.sizeY)
        assertEquals(24, model.grid.sizeZ)
        assertNotNull(firstVisibleFrame, "sea-villa stayed visually blank for 1,024 rewrites")
    }
}
