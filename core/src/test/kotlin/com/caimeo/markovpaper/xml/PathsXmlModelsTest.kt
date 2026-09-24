package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathsXmlModelsTest {
    @Test
    fun `stairs model materializes an up or down path tile`() {
        val model = MarkovXmlCompiler.compile(
            requireNotNull(javaClass.getResource("/models/stairs3d.xml")).readText(), 3, 3, 3, 11,
        )
        var frames = 0

        while (model.node.advance() != null && frames < 1_000) frames++

        assertTrue(frames in 1 until 1_000)
        assertEquals(15, model.grid.sizeX)
        assertTrue(model.grid.copyState().any { it == model.valueOf('P') })
    }

    @Test
    fun `apartemazements runs tile wfc followed by building rules`() {
        val model = MarkovXmlCompiler.compile(
            requireNotNull(javaClass.getResource("/models/apartemazements.xml")).readText(), 3, 3, 3, 19,
        )
        var frames = 0
        var delta = model.node.advance()

        while (delta != null && frames < 5_000) {
            frames++
            delta = model.node.advance()
        }

        assertNull(delta)
        assertEquals(15, model.grid.sizeX)
        assertTrue(model.grid.copyState().toSet().size >= 3)
    }
}
