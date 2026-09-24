package com.caimeo.markovpaper.engine

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathNodeTest {
    @Test
    fun `path colors substrate between start and finish without replacing endpoints`() {
        val grid = VoxelGrid(5, 1, 1)
        grid[0, 0, 0] = 1
        grid[4, 0, 0] = 2
        val node = PathNode(
            grid = grid,
            startMask = 1 shl 1,
            finishMask = 1 shl 2,
            substrateMask = 1 shl 0,
            color = 3,
            random = Random(1),
        )

        val delta = node.advance()

        assertEquals(listOf<Byte>(1, 3, 3, 3, 2), grid.copyState().toList())
        assertEquals(3, delta?.changes?.size)
        assertNull(node.advance())
    }

    @Test
    fun `path can connect another component after the first finish becomes reachable`() {
        val grid = VoxelGrid(7, 1, 1)
        grid[0, 0, 0] = 1
        grid[1, 0, 0] = 2
        grid[2, 0, 0] = 2
        grid[3, 0, 0] = 3
        grid[4, 0, 0] = 2
        grid[5, 0, 0] = 2
        grid[6, 0, 0] = 3
        val node = PathNode(
            grid = grid,
            startMask = 1 shl 1,
            finishMask = 1 shl 3,
            substrateMask = 1 shl 2,
            color = 4,
            random = Random(1),
        )

        node.advance()
        grid[3, 0, 0] = 1
        val secondConnection = node.advance()

        assertEquals(listOf<Byte>(1, 4, 4, 1, 4, 4, 3), grid.copyState().toList())
        assertEquals(2, secondConnection?.changes?.size)
    }

    @Test
    fun `path reports no rewrite when endpoints are already adjacent`() {
        val grid = VoxelGrid(2, 1, 1)
        grid[0, 0, 0] = 1
        grid[1, 0, 0] = 2
        val node = PathNode(
            grid = grid,
            startMask = 1 shl 1,
            finishMask = 1 shl 2,
            substrateMask = 1,
            color = 3,
            random = Random(1),
        )

        assertNull(node.advance())
    }
}
