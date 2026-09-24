package com.caimeo.markovpaper.engine

import java.util.Random
import kotlin.math.max

data class ObservationSpec(
    val value: Byte,
    val from: Byte,
    val toMask: Int,
)

class InferenceOneNode(
    private val grid: VoxelGrid,
    private val rules: List<Rule>,
    observations: List<ObservationSpec>,
    private val valueCount: Int,
    private val random: Random,
    private val maxSteps: Int = 0,
) : RewriteNode {
    private val observations = observations.associateBy(ObservationSpec::value)
    private val future = IntArray(grid.sizeX * grid.sizeY * grid.sizeZ)
    private val potentials = Array(valueCount) {
        IntArray(grid.sizeX * grid.sizeY * grid.sizeZ)
    }
    private var futureComputed = false
    private var counter = 0
    private var initialChanges = emptyList<CellChange>()

    override fun advance(): StepDelta? {
        if (maxSteps > 0 && counter >= maxSteps) return null
        if (!futureComputed) {
            if (!prepareFuture()) return null
            computeBackwardPotentials()
            futureComputed = true
        }
        if (goalReached()) {
            val changes = initialChanges
            initialChanges = emptyList()
            futureComputed = false
            return if (changes.isEmpty()) null else StepDelta(changes)
        }

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
        if (bestMatches.isEmpty()) {
            val changes = initialChanges
            initialChanges = emptyList()
            return if (changes.isEmpty()) null else StepDelta(changes)
        }

        val selected = bestMatches[random.nextInt(bestMatches.size)]
        val ruleChanges = selected.rule.apply(grid, selected.x, selected.y, selected.z).changes
        val changes = initialChanges + ruleChanges
        initialChanges = emptyList()
        counter++
        return StepDelta(changes)
    }

    override fun reset() {
        futureComputed = false
        counter = 0
        initialChanges = emptyList()
    }

    private fun prepareFuture(): Boolean {
        val present = HashSet<Byte>()
        val changes = ArrayList<CellChange>()
        for (z in 0 until grid.sizeZ) {
            for (y in 0 until grid.sizeY) {
                for (x in 0 until grid.sizeX) {
                    val index = grid.index(x, y, z)
                    val value = grid[x, y, z]
                    val observation = observations[value]
                    if (observation == null) {
                        future[index] = 1 shl value.toInt()
                        continue
                    }

                    present += value
                    future[index] = observation.toMask
                    if (value != observation.from) {
                        changes += CellChange(index, x, y, z, value, observation.from)
                    }
                }
            }
        }
        if (!present.containsAll(observations.keys)) return false
        for (change in changes) grid[change.x, change.y, change.z] = change.after
        initialChanges = changes
        return true
    }

    private fun computeBackwardPotentials() {
        for (value in 0 until valueCount) {
            for (index in future.indices) {
                potentials[value][index] = if (future[index] and (1 shl value) != 0) 0 else -1
            }
        }

        var changed: Boolean
        do {
            changed = false
            for (rule in rules) {
                for (z in 0..grid.sizeZ - rule.sizeZ) {
                    for (y in 0..grid.sizeY - rule.sizeY) {
                        for (x in 0..grid.sizeX - rule.sizeX) {
                            var level = 0
                            var reachable = true
                            for (localZ in 0 until rule.sizeZ) {
                                for (localY in 0 until rule.sizeY) {
                                    for (localX in 0 until rule.sizeX) {
                                        val patternIndex =
                                            localX + localY * rule.sizeX +
                                                localZ * rule.sizeX * rule.sizeY
                                        val output = rule.outputValueAt(patternIndex)
                                        if (output == Rule.UNCHANGED) continue
                                        val index = grid.index(x + localX, y + localY, z + localZ)
                                        val potential = potentials[output.toInt()][index]
                                        if (potential < 0) {
                                            reachable = false
                                            break
                                        }
                                        level = max(level, potential)
                                    }
                                    if (!reachable) break
                                }
                                if (!reachable) break
                            }
                            if (!reachable) continue

                            val candidate = level + 1
                            for (localZ in 0 until rule.sizeZ) {
                                for (localY in 0 until rule.sizeY) {
                                    for (localX in 0 until rule.sizeX) {
                                        val patternIndex =
                                            localX + localY * rule.sizeX +
                                                localZ * rule.sizeX * rule.sizeY
                                        val input = rule.exactInputValueAt(patternIndex) ?: continue
                                        val index = grid.index(x + localX, y + localY, z + localZ)
                                        val current = potentials[input.toInt()][index]
                                        if (current < 0 || candidate < current) {
                                            potentials[input.toInt()][index] = candidate
                                            changed = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } while (changed)
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
                    val gridIndex = grid.index(match.x + x, match.y + y, match.z + z)
                    val nextPotential = potentials[output.toInt()][gridIndex]
                    if (nextPotential < 0) return null
                    val currentValue = grid[match.x + x, match.y + y, match.z + z]
                    val currentPotential = potentials[currentValue.toInt()][gridIndex]
                    delta += nextPotential - currentPotential
                }
            }
        }
        return delta
    }

    private fun goalReached(): Boolean {
        val state = grid.copyState()
        return state.indices.all { index ->
            future[index] and (1 shl state[index].toInt()) != 0
        }
    }
}
