package com.caimeo.markovpaper.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MappingNodeTest {
    @Test
    fun `map applies a larger output pattern at scaled source coordinates`() {
        val source = VoxelGrid(2, 1, 1)
        source[0, 0, 0] = 1
        val output = VoxelGrid(4, 1, 1)
        val rule = MappingRule(
            input = intArrayOf(1 shl 1),
            inputSizeX = 1,
            inputSizeY = 1,
            inputSizeZ = 1,
            output = byteArrayOf(2, 3),
            outputSizeX = 2,
            outputSizeY = 1,
            outputSizeZ = 1,
        )
        val node = MappingNode(
            source = source,
            output = output,
            rules = listOf(rule),
            scaleX = Scale(2, 1),
            scaleY = Scale(1, 1),
            scaleZ = Scale(1, 1),
        )

        val delta = node.advance()

        assertEquals(listOf<Byte>(2, 3, 0, 0), output.copyState().toList())
        assertEquals(2, delta?.changes?.size)
        assertNull(node.advance())
    }
}
