package com.caimeo.markovpaper.engine

import java.lang.Math.floorMod
import java.util.Random

class ConvolutionNode(
    private val grid: VoxelGrid,
    private val rules: List<ConvolutionRule>,
    private val kernel: ConvolutionKernel,
    private val periodic: Boolean,
    private val random: Random,
    private val maxSteps: Int = 0,
) : RewriteNode {
    private var counter = 0

    override fun advance(): StepDelta? {
        if (maxSteps > 0 && counter >= maxSteps) return null

        val snapshot = grid.copyState()
        val next = snapshot.copyOf()
        for (z in 0 until grid.sizeZ) {
            for (y in 0 until grid.sizeY) {
                for (x in 0 until grid.sizeX) {
                    val index = grid.index(x, y, z)
                    val input = snapshot[index]
                    for (rule in rules) {
                        if (rule.input != input || rule.output == input) continue
                        val sum = rule.valuesMask?.let {
                            neighborCount(snapshot, x, y, z, it)
                        }
                        if (sum != null && sum !in requireNotNull(rule.sums)) continue
                        if (random.nextDouble() > rule.probability) continue
                        next[index] = rule.output
                        break
                    }
                }
            }
        }

        counter++
        val changes = ArrayList<CellChange>()
        for (z in 0 until grid.sizeZ) {
            for (y in 0 until grid.sizeY) {
                for (x in 0 until grid.sizeX) {
                    val index = grid.index(x, y, z)
                    if (snapshot[index] == next[index]) continue
                    grid[x, y, z] = next[index]
                    changes += CellChange(
                        index = index,
                        x = x,
                        y = y,
                        z = z,
                        before = snapshot[index],
                        after = next[index],
                    )
                }
            }
        }
        return if (changes.isEmpty()) null else StepDelta(changes)
    }

    override fun reset() {
        counter = 0
    }

    private fun neighborCount(
        snapshot: ByteArray,
        x: Int,
        y: Int,
        z: Int,
        valuesMask: Int,
    ): Int {
        var count = 0
        for (offset in kernel.offsets) {
            var sampleX = x + offset.x
            var sampleY = y + offset.y
            var sampleZ = z + offset.z
            if (periodic) {
                sampleX = floorMod(sampleX, grid.sizeX)
                sampleY = floorMod(sampleY, grid.sizeY)
                sampleZ = floorMod(sampleZ, grid.sizeZ)
            } else if (
                sampleX !in 0 until grid.sizeX ||
                sampleY !in 0 until grid.sizeY ||
                sampleZ !in 0 until grid.sizeZ
            ) {
                continue
            }

            val value = snapshot[grid.index(sampleX, sampleY, sampleZ)].toInt()
            if (valuesMask and (1 shl value) != 0) count++
        }
        return count
    }
}

data class ConvolutionRule(
    val input: Byte,
    val output: Byte,
    val valuesMask: Int? = null,
    val sums: Set<Int>? = null,
    val probability: Double = 1.0,
) {
    init {
        require((valuesMask == null) == (sums == null))
        require(probability in 0.0..1.0)
    }
}

enum class ConvolutionKernel(internal val offsets: List<Offset>) {
    VON_NEUMANN_2D(axisOffsets2D()),
    VON_NEUMANN_3D(axisOffsets3D()),
    MOORE_2D(neighborOffsets2D()),
    NO_CORNERS_3D(neighborOffsets3DWithoutCorners());

    companion object {
        fun fromXml(name: String, is3D: Boolean): ConvolutionKernel = when (name) {
            "VonNeumann" -> {
                if (is3D) VON_NEUMANN_3D else VON_NEUMANN_2D
            }
            "Moore" -> {
                require(!is3D) { "Moore is only defined for 2D grids" }
                MOORE_2D
            }
            "NoCorners" -> {
                require(is3D) { "NoCorners is only defined for 3D grids" }
                NO_CORNERS_3D
            }
            else -> error("Unknown convolution neighborhood '$name'")
        }
    }
}

internal data class Offset(val x: Int, val y: Int, val z: Int)

private fun axisOffsets2D(): List<Offset> = buildList {
    add(Offset(-1, 0, 0))
    add(Offset(1, 0, 0))
    add(Offset(0, -1, 0))
    add(Offset(0, 1, 0))
}

private fun axisOffsets3D(): List<Offset> = buildList {
    addAll(axisOffsets2D())
    add(Offset(0, 0, -1))
    add(Offset(0, 0, 1))
}

private fun neighborOffsets2D(): List<Offset> = buildList {
    for (y in -1..1) {
        for (x in -1..1) {
            if (x != 0 || y != 0) add(Offset(x, y, 0))
        }
    }
}

private fun neighborOffsets3DWithoutCorners(): List<Offset> = buildList {
    for (z in -1..1) {
        for (y in -1..1) {
            for (x in -1..1) {
                val nonZeroAxes = listOf(x, y, z).count { it != 0 }
                if (nonZeroAxes in 1..2) add(Offset(x, y, z))
            }
        }
    }
}
