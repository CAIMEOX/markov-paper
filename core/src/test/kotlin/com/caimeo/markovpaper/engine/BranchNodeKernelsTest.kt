package com.caimeo.markovpaper.engine

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BranchNodeKernelsTest {
    @Test
    fun `sequence exhausts each child before advancing`() {
        val grid = VoxelGrid(1, 1, 1)
        val sequence = SequenceNode(listOf(
            OneNode(grid, listOf(Rule.single(0, 1)), Random(1), maxSteps = 1),
            OneNode(grid, listOf(Rule.single(1, 2)), Random(2), maxSteps = 1),
        ))

        sequence.advance()
        assertEquals(1, grid[0, 0, 0].toInt())
        sequence.advance()
        assertEquals(2, grid[0, 0, 0].toInt())
        assertNull(sequence.advance())
    }

    @Test
    fun `markov retries children from the beginning every turn`() {
        val grid = VoxelGrid(1, 1, 1)
        val markov = MarkovNode(listOf(
            OneNode(grid, listOf(Rule.single(0, 1)), Random(1)),
            OneNode(grid, listOf(Rule.single(1, 0)), Random(2)),
        ))

        val states = List(3) {
            markov.advance()
            grid[0, 0, 0].toInt()
        }

        assertEquals(listOf(1, 0, 1), states)
    }
}
