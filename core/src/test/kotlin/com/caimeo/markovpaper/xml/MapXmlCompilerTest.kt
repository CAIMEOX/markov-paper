package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals

class MapXmlCompilerTest {
    @Test
    fun `map switches to a scaled grid and continues with its child nodes`() {
        val model = MarkovXmlCompiler.compile(
            xml = """
                <sequence values="BW" origin="True" symmetry="()">
                  <map scale="2 1 1" values="BWR" symmetry="()">
                    <rule in="W" out="RR"/>
                    <one in="R" out="W" steps="1"/>
                  </map>
                </sequence>
            """.trimIndent(),
            sizeX = 1,
            sizeY = 1,
            sizeZ = 1,
            seed = 2,
        )

        model.node.advance()
        assertEquals(listOf<Byte>(2, 2), model.grid.copyState().toList())
        model.node.advance()
        assertEquals(1, model.grid.copyState().count { it.toInt() == 1 })
    }
}
