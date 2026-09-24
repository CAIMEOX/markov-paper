package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CarmaTowerModelTest {
    @Test
    fun `original carma tower compiles through map and starts fine-grid animation`() {
        val resource = requireNotNull(javaClass.getResource("/models/carma-tower.xml"))
        val model = MarkovXmlCompiler.compile(
            xml = resource.readText(),
            sizeX = 12,
            sizeY = 12,
            sizeZ = 18,
            seed = 23,
        )

        val firstFrame = model.node.advance()

        assertEquals(48, model.grid.sizeX)
        assertEquals(48, model.grid.sizeY)
        assertEquals(72, model.grid.sizeZ)
        assertNotNull(firstFrame)
    }
}
