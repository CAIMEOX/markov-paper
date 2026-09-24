package com.caimeo.markovpaper.engine

import java.util.ArrayDeque
import java.util.Random
import kotlin.math.sqrt

class PathNode(
    private val grid: VoxelGrid,
    private val startMask: Int,
    private val finishMask: Int,
    private val substrateMask: Int,
    private val color: Byte,
    private val random: Random,
    private val inertia: Boolean = false,
    private val longest: Boolean = false,
    private val edges: Boolean = false,
    private val vertices: Boolean = false,
) : RewriteNode {
    override fun advance(): StepDelta? {
        val generations = IntArray(grid.sizeX * grid.sizeY * grid.sizeZ) { -1 }
        val starts = ArrayList<BlockPoint3>()
        val frontier = ArrayDeque<Int>()
        for (z in 0 until grid.sizeZ) {
            for (y in 0 until grid.sizeY) {
                for (x in 0 until grid.sizeX) {
                    val index = grid.index(x, y, z)
                    val valueMask = 1 shl grid[x, y, z].toInt()
                    if (startMask and valueMask != 0) starts += BlockPoint3(x, y, z)
                    if (finishMask and valueMask != 0) {
                        generations[index] = 0
                        frontier.add(index)
                    }
                }
            }
        }
        if (starts.isEmpty() || frontier.isEmpty()) return null

        while (frontier.isNotEmpty()) {
            val index = frontier.removeFirst()
            val point = coordinates(index)
            val nextGeneration = generations[index] + 1
            for (offset in directions(point)) {
                val next = point + offset
                val nextIndex = grid.index(next.x, next.y, next.z)
                if (generations[nextIndex] >= 0) continue
                val valueMask = 1 shl grid[next.x, next.y, next.z].toInt()
                if (substrateMask and valueMask == 0 && startMask and valueMask == 0) continue
                generations[nextIndex] = nextGeneration
                if (substrateMask and valueMask != 0) frontier.add(nextIndex)
            }
        }

        val reachable = starts.filter { generations[grid.index(it.x, it.y, it.z)] > 0 }
        if (reachable.isEmpty()) return null
        val targetGeneration = if (longest) {
            reachable.maxOf { generations[grid.index(it.x, it.y, it.z)] }
        } else {
            reachable.minOf { generations[grid.index(it.x, it.y, it.z)] }
        }
        var pen = reachable.filter {
            generations[grid.index(it.x, it.y, it.z)] == targetGeneration
        }.random(random)
        var previousDirection = BlockPoint3(0, 0, 0)
        val changes = ArrayList<CellChange>()

        while (generations[grid.index(pen.x, pen.y, pen.z)] > 0) {
            val nextDirection = chooseDirection(pen, previousDirection, generations)
            pen += nextDirection
            previousDirection = nextDirection
            if (generations[grid.index(pen.x, pen.y, pen.z)] == 0) break
            val index = grid.index(pen.x, pen.y, pen.z)
            val previous = grid[pen.x, pen.y, pen.z]
            if (previous != color) {
                grid[pen.x, pen.y, pen.z] = color
                changes += CellChange(index, pen.x, pen.y, pen.z, previous, color)
            }
        }
        return if (changes.isEmpty()) null else StepDelta(changes)
    }

    override fun reset() = Unit

    private fun chooseDirection(
        point: BlockPoint3,
        previous: BlockPoint3,
        generations: IntArray,
    ): BlockPoint3 {
        val generation = generations[grid.index(point.x, point.y, point.z)]
        val candidates = directions(point).filter { offset ->
            val next = point + offset
            generations[grid.index(next.x, next.y, next.z)] == generation - 1
        }
        require(candidates.isNotEmpty()) { "Path potential has no descending neighbor" }
        if (!inertia || previous == BlockPoint3(0, 0, 0)) return candidates.random(random)
        return candidates.maxBy { candidate ->
            val dot = candidate.x * previous.x + candidate.y * previous.y + candidate.z * previous.z
            val lengths = sqrt(
                ((candidate.x * candidate.x + candidate.y * candidate.y + candidate.z * candidate.z) *
                    (previous.x * previous.x + previous.y * previous.y + previous.z * previous.z)).toDouble()
            )
            dot / lengths + 0.1 * random.nextDouble()
        }
    }

    private fun directions(point: BlockPoint3): List<BlockPoint3> = buildList {
        val zRange = if (grid.sizeZ == 1) 0..0 else -1..1
        for (z in zRange) {
            for (y in -1..1) {
                for (x in -1..1) {
                    val nonZero = listOf(x, y, z).count { it != 0 }
                    if (nonZero == 0 || nonZero == 2 && !edges || nonZero == 3 && !vertices) continue
                    val nextX = point.x + x
                    val nextY = point.y + y
                    val nextZ = point.z + z
                    if (
                        nextX in 0 until grid.sizeX &&
                        nextY in 0 until grid.sizeY &&
                        nextZ in 0 until grid.sizeZ
                    ) {
                        add(BlockPoint3(x, y, z))
                    }
                }
            }
        }
    }

    private fun coordinates(index: Int): BlockPoint3 = BlockPoint3(
        x = index % grid.sizeX,
        y = index / grid.sizeX % grid.sizeY,
        z = index / (grid.sizeX * grid.sizeY),
    )

    private data class BlockPoint3(val x: Int, val y: Int, val z: Int) {
        operator fun plus(other: BlockPoint3): BlockPoint3 =
            BlockPoint3(x + other.x, y + other.y, z + other.z)
    }
}

private fun <T> List<T>.random(random: Random): T = this[random.nextInt(size)]
