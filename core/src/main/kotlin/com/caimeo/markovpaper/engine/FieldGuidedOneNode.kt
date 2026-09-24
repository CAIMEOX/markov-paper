package com.caimeo.markovpaper.engine

import java.util.ArrayDeque
import java.util.Random

data class FieldSpec(
    val forValue: Byte,
    val zeroMask: Int,
    val substrateMask: Int,
    val inversed: Boolean,
    val recompute: Boolean = false,
    val essential: Boolean = false,
)

class FieldGuidedOneNode(
    private val grid: VoxelGrid,
    private val rules: List<Rule>,
    fields: List<FieldSpec>,
    valueCount: Int,
    private val random: Random,
    private val maxSteps: Int = 0,
) : RewriteNode {
    private val fields = fields.associateBy(FieldSpec::forValue)
    private val potentials = Array(valueCount) {
        IntArray(grid.sizeX * grid.sizeY * grid.sizeZ)
    }
    private var counter = 0

    override fun advance(): StepDelta? {
        if (maxSteps > 0 && counter >= maxSteps) return null
        var computed = false
        var successful = false
        for ((value, field) in fields) {
            if (counter > 0 && !field.recompute) continue
            computed = true
            val success = computeField(field, potentials[value.toInt()])
            if (!success && field.essential) return null
            successful = successful || success
        }
        if (computed && !successful) return null

        var bestHeuristic = Int.MAX_VALUE
        val bestMatches = ArrayList<RuleMatch>()
        for (match in findMatches(grid, rules)) {
            val heuristic = deltaPotential(match) ?: continue
            when {
                heuristic < bestHeuristic -> {
                    bestHeuristic = heuristic
                    bestMatches.clear()
                    bestMatches += match
                }
                heuristic == bestHeuristic -> bestMatches += match
            }
        }
        if (bestMatches.isEmpty()) return null
        val selected = bestMatches[random.nextInt(bestMatches.size)]
        counter++
        return selected.rule.apply(grid, selected.x, selected.y, selected.z)
    }

    override fun reset() {
        counter = 0
    }

    private fun computeField(field: FieldSpec, potential: IntArray): Boolean {
        potential.fill(-1)
        val queue = ArrayDeque<Int>()
        val state = grid.copyState()
        for (index in state.indices) {
            if (field.zeroMask and (1 shl state[index].toInt()) != 0) {
                potential[index] = 0
                queue.add(index)
            }
        }
        if (queue.isEmpty()) return false

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % grid.sizeX
            val y = index / grid.sizeX % grid.sizeY
            val z = index / (grid.sizeX * grid.sizeY)
            for (offset in DIRECTIONS) {
                val neighborX = x + offset[0]
                val neighborY = y + offset[1]
                val neighborZ = z + offset[2]
                if (
                    neighborX !in 0 until grid.sizeX ||
                    neighborY !in 0 until grid.sizeY ||
                    neighborZ !in 0 until grid.sizeZ
                ) {
                    continue
                }
                val neighbor = grid.index(neighborX, neighborY, neighborZ)
                if (
                    potential[neighbor] < 0 &&
                    field.substrateMask and (1 shl state[neighbor].toInt()) != 0
                ) {
                    potential[neighbor] = potential[index] + 1
                    queue.add(neighbor)
                }
            }
        }
        return true
    }

    private fun deltaPotential(match: RuleMatch): Int? {
        var delta = 0
        val rule = match.rule
        for (z in 0 until rule.sizeZ) {
            for (y in 0 until rule.sizeY) {
                for (x in 0 until rule.sizeX) {
                    val patternIndex = x + y * rule.sizeX + z * rule.sizeX * rule.sizeY
                    val output = rule.outputValueAt(patternIndex)
                    if (output == Rule.UNCHANGED || rule.inputMaskAt(patternIndex) and
                        (1 shl output.toInt()) != 0
                    ) {
                        continue
                    }
                    val index = grid.index(match.x + x, match.y + y, match.z + z)
                    val nextPotential = potentials[output.toInt()][index]
                    if (nextPotential < 0) return null
                    val oldValue = grid[match.x + x, match.y + y, match.z + z]
                    val oldPotential = potentials[oldValue.toInt()][index]
                    delta += nextPotential - oldPotential
                    if (fields[oldValue]?.inversed == true) delta += 2 * oldPotential
                    if (fields[output]?.inversed == true) delta -= 2 * nextPotential
                }
            }
        }
        return delta
    }

    companion object {
        private val DIRECTIONS = arrayOf(
            intArrayOf(1, 0, 0),
            intArrayOf(-1, 0, 0),
            intArrayOf(0, 1, 0),
            intArrayOf(0, -1, 0),
            intArrayOf(0, 0, 1),
            intArrayOf(0, 0, -1),
        )
    }
}
