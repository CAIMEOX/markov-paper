package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals

class InferenceXmlTest {
    @Test
    fun `observations choose the rewrite that lowers backward potential`() {
        val model = MarkovXmlCompiler.compile(
            xml = """
                <one values="BRG" symmetry="()">
                  <rule in="RB" out="BR"/>
                  <rule in="BR" out="RB"/>
                  <observe value="G" from="B" to="R"/>
                  <observe value="R" to="B"/>
                  <observe value="B" to="B"/>
                </one>
            """.trimIndent(),
            sizeX = 5,
            sizeY = 1,
            sizeZ = 1,
            seed = 3,
            initialState = byteArrayOf(0, 0, 1, 0, 2),
        )

        model.node.advance()

        assertEquals(model.valueOf('R'), model.grid[3, 0, 0])
    }
}
