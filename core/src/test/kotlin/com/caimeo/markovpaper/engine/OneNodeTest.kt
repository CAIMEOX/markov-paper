package com.caimeo.markovpaper.engine

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OneNodeTest {
    @Test
    fun `one node rewrites random matches until none remain`() {
        val grid = VoxelGrid(sizeX = 2, sizeY = 1, sizeZ = 1)
        val node = OneNode(
            grid = grid,
            rules = listOf(Rule.single(from = 0, to = 1)),
            random = Random(7),
        )

        val first = node.advance()
        val second = node.advance()

        assertEquals(1, first?.changes?.size)
        assertEquals(1, second?.changes?.size)
        assertEquals(listOf<Byte>(1, 1), grid.copyState().toList())
        assertNull(node.advance())
    }
}
