package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkovXmlCompilerTest {
    @Test
    fun `original maze growth xml compiles and reaches a tree`() {
        val model = MarkovXmlCompiler.compile(
            xml = """<one values="BWA" in="WBB" out="WAW" origin="True"/>""",
            sizeX = 7,
            sizeY = 7,
            sizeZ = 7,
            seed = 123,
        )
        var steps = 0

        while (model.node.advance() != null && steps < 1_000) steps++

        val state = model.grid.copyState()
        val white = state.count { it == model.valueOf('W') }
        val accent = state.count { it == model.valueOf('A') }
        assertTrue(steps in 2 until 1_000)
        assertEquals(white - 1, accent)
    }

    @Test
    fun `sequence xml supports unions and unchanged outputs`() {
        val model = MarkovXmlCompiler.compile(
            xml = """
                <sequence values="BRG" origin="True" symmetry="()">
                  <union symbol="?" values="BR"/>
                  <all in="?R" out="G*" steps="1"/>
                  <one in="G" out="R" steps="1"/>
                </sequence>
            """.trimIndent(),
            sizeX = 3,
            sizeY = 1,
            sizeZ = 1,
            seed = 7,
        )

        model.node.advance()
        assertEquals(listOf<Byte>(2, 1, 0), model.grid.copyState().toList())
        model.node.advance()
        assertEquals(listOf<Byte>(1, 1, 0), model.grid.copyState().toList())
        assertNull(model.node.advance())
    }
}
