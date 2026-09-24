package com.caimeo.markovpaper.engine

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TileWfcNodeTest {
    @Test
    fun `positive overlap uses grid order rather than collapse order in the final frame`() {
        val set = TileSet(2, 1, 1, listOf(
            TileVariant("a", byteArrayOf(1, 1)), TileVariant("b", byteArrayOf(2, 2)),
            TileVariant("end", byteArrayOf(3, 3)),
        ), Array(6) { Array(3) { BooleanArray(3) { true } } })
        for (seed in 0L..7L) {
            val constraints = VoxelGrid(4, 1, 1).apply { this[3, 0, 0] = 1 }
            val output = VoxelGrid(5, 1, 1)
            val node = TileWfcNode(constraints, output, set, mapOf(
                0.toByte() to booleanArrayOf(true, true, false),
                1.toByte() to booleanArrayOf(false, false, true),
            ), Random(seed), strideX = 1)
            repeat(30) { node.advance() }
            assertEquals(3.toByte(), output[3, 0, 0], "rightmost tile owns overlap for seed $seed")
        }
    }

    @Test
    fun `periodic wfc propagates across the seam of the grid`() {
        val neighbors = Array(6) { Array(2) { BooleanArray(2) { true } } }
        for (direction in listOf(0, 2)) {
            for (variant in 0..1) neighbors[direction][variant][variant] = false
        }
        val set = TileSet(1, 1, 1, listOf(
            TileVariant("a", byteArrayOf(1), 10.0), TileVariant("b", byteArrayOf(2), 0.1),
        ), neighbors)
        val constraints = VoxelGrid(3, 1, 1).apply { this[0, 0, 0] = 1 }
        val fixed = mapOf(1.toByte() to booleanArrayOf(true, false))
        val openGrid = VoxelGrid(3, 1, 1)
        val open = TileWfcNode(constraints, openGrid, set, fixed, Random(1), shannon = true)
        repeat(20) { open.advance() }
        assertEquals(listOf<Byte>(1, 2, 1), openGrid.copyState().toList())
        val periodic = TileWfcNode(constraints, VoxelGrid(3, 1, 1), set, fixed, Random(1), periodic = true)
        assertFailsWith<WfcInitialContradictionException> { periodic.advance() }
    }

    @Test
    fun `unconstrained singleton tiles still need adjacency support`() {
        val set = TileSet(1, 1, 1, listOf(TileVariant("a", byteArrayOf(1))),
            Array(6) { Array(1) { booleanArrayOf(false) } })
        val node = TileWfcNode(VoxelGrid(2, 1, 1), VoxelGrid(2, 1, 1), set, emptyMap(), Random(1))
        assertFailsWith<WfcInitialContradictionException> { node.advance() }
    }

    @Test
    fun `plans a satisfiable collapse before replaying its animation`() {
        val horizontal = intArrayOf(0, 2, 1, 3)
        val vertical = intArrayOf(0, 1, 3, 2)
        val neighbors = Array(6) { Array(4) { BooleanArray(4) } }
        for (variant in 0 until 4) {
            neighbors[0][variant][horizontal[variant]] = true
            neighbors[2][horizontal[variant]][variant] = true
            neighbors[1][variant][vertical[variant]] = true
            neighbors[3][vertical[variant]][variant] = true
            neighbors[4][variant][variant] = true
            neighbors[5][variant][variant] = true
        }
        val tileSet = TileSet(
            sizeX = 1,
            sizeY = 1,
            sizeZ = 1,
            variants = List(4) { variant ->
                TileVariant("variant-$variant", byteArrayOf((variant + 1).toByte()))
            },
            neighbors = neighbors,
        )
        val output = VoxelGrid(2, 2, 1)
        val attemptSeeds = AttemptSeedRandom(0, 1)
        val node = TileWfcNode(
            constraints = VoxelGrid(2, 2, 1),
            output = output,
            tileSet = tileSet,
            allowedByValue = emptyMap(),
            random = attemptSeeds,
            maxAttempts = 2,
            planningObservationsPerAdvance = 1,
        )

        val firstPlanningFrame = node.advance()
        assertTrue(firstPlanningFrame != null && firstPlanningFrame.changes.isEmpty())
        assertEquals(List(4) { 0.toByte() }, output.copyState().toList())
        while (node.advance() != null) Unit

        assertEquals(2, attemptSeeds.nextLongCalls)
        assertEquals(List(4) { 1.toByte() }, output.copyState().toList())
    }

    private class AttemptSeedRandom(vararg seeds: Long) : Random(0) {
        private val seeds = seeds
        var nextLongCalls = 0
            private set

        override fun nextLong(): Long {
            val seed = seeds.getOrElse(nextLongCalls) {
                error("WFC requested more attempt seeds than the test supplied")
            }
            nextLongCalls++
            return seed
        }
    }

    @Test
    fun `three dimensional constraints propagate and materialize forced tiles`() {
        val neighbors = Array(6) { Array(2) { BooleanArray(2) { true } } }
        for (direction in listOf(4, 5)) {
            neighbors[direction][0].fill(false)
            neighbors[direction][1].fill(false)
            neighbors[direction][0][1] = true
            neighbors[direction][1][0] = true
        }
        val tileSet = TileSet(
            sizeX = 1,
            sizeY = 1,
            sizeZ = 1,
            variants = listOf(
                TileVariant("a", byteArrayOf(1)),
                TileVariant("b", byteArrayOf(2)),
            ),
            neighbors = neighbors,
        )
        val constraints = VoxelGrid(1, 1, 3).apply { this[0, 0, 0] = 1 }
        val output = VoxelGrid(1, 1, 3)
        val node = TileWfcNode(
            constraints = constraints,
            output = output,
            tileSet = tileSet,
            allowedByValue = mapOf(1.toByte() to booleanArrayOf(true, false)),
            random = Random(1),
        )

        while (node.advance() != null) Unit

        assertEquals(listOf<Byte>(1, 2, 1), output.copyState().toList())
    }

    @Test
    fun `tile stride can leave postprocessing space between cells`() {
        val tileSet = TileSet(
            sizeX = 1,
            sizeY = 1,
            sizeZ = 1,
            variants = listOf(TileVariant("solid", byteArrayOf(1))),
            neighbors = Array(6) { Array(1) { booleanArrayOf(true) } },
        )
        val output = VoxelGrid(3, 1, 1)
        val node = TileWfcNode(
            constraints = VoxelGrid(2, 1, 1),
            output = output,
            tileSet = tileSet,
            allowedByValue = emptyMap(),
            random = Random(1),
            strideX = 2,
        )

        while (node.advance() != null) Unit

        assertEquals(listOf<Byte>(1, 0, 1), output.copyState().toList())
    }
}
