package com.caimeo.markovpaper.engine

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleNodeKernelsTest {
    @Test
    fun `all rejects overlapping outputs while parallel uses one snapshot`() {
        val rule = Rule(
            input = byteArrayOf(0, 0),
            output = byteArrayOf(1, 1),
            sizeX = 2,
            sizeY = 1,
            sizeZ = 1,
        )
        val allGrid = VoxelGrid(3, 1, 1)
        val parallelGrid = VoxelGrid(3, 1, 1)

        AllNode(allGrid, listOf(rule), Random(1)).advance()
        ParallelNode(parallelGrid, listOf(rule), Random(1)).advance()

        assertEquals(2, allGrid.copyState().count { it.toInt() == 1 })
        assertEquals(listOf<Byte>(1, 1, 1), parallelGrid.copyState().toList())
    }
}
