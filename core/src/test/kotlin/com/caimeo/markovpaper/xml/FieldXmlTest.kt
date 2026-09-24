package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldXmlTest {
    @Test
    fun `inversed field chooses the match farthest from its zero set`() {
        val model = MarkovXmlCompiler.compile(
            xml = """
                <one values="BUO" symmetry="()" steps="1">
                  <rule in="B" out="O"/>
                  <field for="O" from="U" on="B"/>
                </one>
            """.trimIndent(),
            sizeX = 5,
            sizeY = 1,
            sizeZ = 1,
            seed = 5,
            initialState = byteArrayOf(1, 0, 0, 0, 0),
        )

        model.node.advance()

        assertEquals(model.valueOf('O'), model.grid[4, 0, 0])
    }
}
