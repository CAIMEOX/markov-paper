package com.caimeo.markovpaper.xml

import kotlin.test.Test
import kotlin.test.assertEquals

class ConvolutionXmlTest {
    @Test
    fun `moore convolution evaluates a generation from one snapshot`() {
        val model = MarkovXmlCompiler.compile(
            xml = """
                <convolution values="DA" neighborhood="Moore" steps="1">
                  <rule in="D" out="A" sum="3" values="A"/>
                  <rule in="A" out="D" sum="0,1,4..8" values="A"/>
                </convolution>
            """.trimIndent(),
            sizeX = 5,
            sizeY = 5,
            sizeZ = 1,
            seed = 1,
            initialState = ByteArray(25).apply { for (x in 1..3) this[x + 2 * 5] = 1 },
        )
        val alive = model.valueOf('A')

        model.node.advance()

        val aliveCells = buildSet {
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    if (model.grid[x, y, 0] == alive) add(x to y)
                }
            }
        }
        assertEquals(setOf(2 to 1, 2 to 2, 2 to 3), aliveCells)
    }

    @Test
    fun `three dimensional kernels distinguish faces from edges`() {
        fun compile(neighborhood: String) = MarkovXmlCompiler.compile(
            xml = """
                <convolution values="DA" neighborhood="$neighborhood" steps="1">
                  <rule in="D" out="A" sum="18" values="A"/>
                </convolution>
            """.trimIndent(),
            sizeX = 3,
            sizeY = 3,
            sizeZ = 3,
            seed = 1,
            initialState = ByteArray(27) { index ->
                val nonZeroAxes = listOf(index % 3 - 1, index / 3 % 3 - 1, index / 9 - 1).count { it != 0 }
                if (nonZeroAxes in 1..2) 1 else 0
            },
        )

        val vonNeumann = compile("VonNeumann")
        val noCorners = compile("NoCorners")
        for (model in listOf(vonNeumann, noCorners)) {
            model.node.advance()
        }

        assertEquals(vonNeumann.valueOf('D'), vonNeumann.grid[1, 1, 1])
        assertEquals(noCorners.valueOf('A'), noCorners.grid[1, 1, 1])
    }
}
