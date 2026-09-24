package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ModernHouseModelTest {
    @Test
    fun `preview command seed reaches a visible building frame`() {
        val xml = requireNotNull(javaClass.getResource("/models/modern-house.xml")).readText()

        val model = MarkovXmlCompiler.compile(xml, 9, 9, 4, seed = 31)
        val firstVisibleFrame = model.node.firstVisibleFrameWithin(maxSteps = 1_024)

        assertEquals(51, model.grid.sizeX)
        assertEquals(51, model.grid.sizeY)
        assertEquals(24, model.grid.sizeZ)
        assertNotNull(firstVisibleFrame, "modern-house stayed visually blank for 1,024 rewrites")
    }
}
