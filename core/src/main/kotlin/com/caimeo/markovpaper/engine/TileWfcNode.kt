package com.caimeo.markovpaper.engine

import java.util.ArrayDeque
import java.util.Random
import kotlin.math.ln

data class TileVariant(
    val name: String,
    val voxels: ByteArray,
    val weight: Double = 1.0,
    val atom: TileAtom? = null,
)

sealed class WfcException(message: String) : IllegalStateException(message)

class WfcInitialContradictionException(message: String) : WfcException(message)

class WfcNoSolutionException(message: String) : WfcException(message)

class TileSet(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val variants: List<TileVariant>,
    val neighbors: Array<Array<BooleanArray>>,
) {
    val nonUniform: Boolean get() = variants.any { it.atom != null }
    init {
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0)
        require(variants.isNotEmpty())
        require(variants.all { it.voxels.size == sizeX * sizeY * sizeZ && it.weight.isFinite() && it.weight > 0.0 })
        require(variants.sumOf { it.weight }.isFinite()) { "Tile weights overflow their total" }
        require(neighbors.size == 6)
        require(neighbors.all { direction ->
            direction.size == variants.size && direction.all { it.size == variants.size }
        })
    }
}

class TileWfcNode(
    private val constraints: VoxelGrid,
    private val output: VoxelGrid,
    private val tileSet: TileSet,
    private val allowedByValue: Map<Byte, BooleanArray>,
    private val random: Random,
    private val strideX: Int = tileSet.sizeX,
    private val strideY: Int = tileSet.sizeY,
    private val strideZ: Int = tileSet.sizeZ,
    private val label: String = "tile",
    private val maxAttempts: Int = 1_000,
    private val planningObservationsPerAdvance: Int = 1,
    private val periodic: Boolean = false,
    private val shannon: Boolean = false,
) : RewriteNode {
    private var wave = emptyArray<BooleanArray>()
    private var rendered = BooleanArray(0)
    private var plannedChoices = emptyList<Choice>()
    private var planningAttempt: PlanningAttempt? = null
    private var attemptsStarted = 0
    private var nextChoice = 0
    private var planReady = false
    private var initialized = false
    private var complete = false

    init {
        require(strideX > 0 && strideY > 0 && strideZ > 0)
        require(!tileSet.nonUniform || !periodic && strideX == tileSet.sizeX &&
            strideY == tileSet.sizeY && strideZ == tileSet.sizeZ) {
            "NUT tilesets require finite grids with no overlap or stride gaps"
        }
        require(output.sizeX == (constraints.sizeX - 1) * strideX + tileSet.sizeX)
        require(output.sizeY == (constraints.sizeY - 1) * strideY + tileSet.sizeY)
        require(output.sizeZ == (constraints.sizeZ - 1) * strideZ + tileSet.sizeZ)
        require(allowedByValue.values.all { it.size == tileSet.variants.size })
        require(maxAttempts > 0)
        require(planningObservationsPerAdvance > 0)
    }

    override fun advance(): StepDelta? {
        if (complete) return null
        if (!initialized) initialize()
        if (!planReady) {
            advancePlanning()
            return StepDelta(emptyList())
        }

        if (nextChoice >= plannedChoices.size) {
            if (strideX < tileSet.sizeX || strideY < tileSet.sizeY || strideZ < tileSet.sizeZ) {
                // MJ's final tile raster is ordered by grid position, not by observation time.
                rendered.fill(false)
            }
            val finalChanges = renderForcedTiles()
            complete = true
            return if (finalChanges.isEmpty()) null else StepDelta(finalChanges)
        }

        val choice = plannedChoices[nextChoice++]
        check(wave[choice.cell][choice.variant]) {
            "$label WFC replay diverged at cell ${choice.cell}"
        }
        collapse(wave, choice)
        check(propagate(wave, ArrayDeque<Int>().apply { add(choice.cell) }) == null) {
            "$label WFC replay contradicted a prevalidated plan"
        }
        return StepDelta(renderForcedTiles())
    }

    override fun reset() {
        wave = emptyArray()
        rendered = BooleanArray(0)
        plannedChoices = emptyList()
        planningAttempt = null
        attemptsStarted = 0
        nextChoice = 0
        planReady = false
        initialized = false
        complete = false
    }

    private fun initialize() {
        val cellCount = constraints.sizeX * constraints.sizeY * constraints.sizeZ
        wave = Array(cellCount) { BooleanArray(tileSet.variants.size) { true } }
        rendered = BooleanArray(cellCount)
        // Even an unconstrained/single-variant tileset may have an unsupported edge.
        val changed = ArrayDeque<Int>().apply { repeat(cellCount) { add(it) } }
        for (z in 0 until constraints.sizeZ) {
            for (y in 0 until constraints.sizeY) {
                for (x in 0 until constraints.sizeX) {
                    val index = constraints.index(x, y, z)
                    val allowed = allowedByValue[constraints[x, y, z]]
                    for (variant in tileSet.variants.indices) {
                        val atom = tileSet.variants[variant].atom
                        if (allowed != null && !allowed[variant] || atom != null &&
                            !atom.fits(x, y, z, constraints.sizeX, constraints.sizeY, constraints.sizeZ)) {
                            wave[index][variant] = false
                        }
                    }
                    if (wave[index].none { it }) {
                        throw WfcInitialContradictionException(
                            "$label WFC constraint removed every tile at $x,$y,$z"
                        )
                    }
                }
            }
        }
        val contradiction = propagate(wave, changed)
        if (contradiction != null) {
            throw WfcInitialContradictionException(contradiction.message)
        }
        plannedChoices = emptyList()
        planningAttempt = null
        attemptsStarted = 0
        nextChoice = 0
        planReady = false
        initialized = true
    }

    private fun advancePlanning() {
        var observationsRemaining = planningObservationsPerAdvance
        while (observationsRemaining > 0 && !planReady) {
            val attempt = planningAttempt ?: startPlanningAttempt()
            val cell = nextCell(attempt.wave, attempt.random)
            if (cell < 0) {
                plannedChoices = attempt.choices.toList()
                planningAttempt = null
                nextChoice = 0
                planReady = true
                return
            }

            val choice = Choice(cell, chooseVariant(attempt.wave[cell], attempt.random))
            attempt.choices += choice
            collapse(attempt.wave, choice)
            observationsRemaining--
            val contradiction = propagate(
                attempt.wave,
                ArrayDeque<Int>().apply { add(cell) },
            )
            if (contradiction != null) planningAttempt = null
        }
    }

    private fun startPlanningAttempt(): PlanningAttempt {
        if (attemptsStarted >= maxAttempts) {
            throw WfcNoSolutionException(
                "$label WFC failed to find a satisfiable collapse in $maxAttempts attempts"
            )
        }
        attemptsStarted++
        return PlanningAttempt(
            wave = Array(wave.size) { index -> wave[index].copyOf() },
            choices = ArrayList(),
            random = Random(random.nextLong()),
        ).also { planningAttempt = it }
    }

    private fun collapse(state: Array<BooleanArray>, choice: Choice) {
        for (variant in state[choice.cell].indices) {
            state[choice.cell][variant] = variant == choice.variant
        }
    }

    private fun propagate(
        state: Array<BooleanArray>,
        queue: ArrayDeque<Int>,
    ): Contradiction? {
        while (queue.isNotEmpty()) {
            val sourceIndex = queue.removeFirst()
            val sourceX = sourceIndex % constraints.sizeX
            val sourceY = sourceIndex / constraints.sizeX % constraints.sizeY
            val sourceZ = sourceIndex / (constraints.sizeX * constraints.sizeY)
            for (direction in DIRECTIONS.indices) {
                val offset = DIRECTIONS[direction]
                var neighborX = sourceX + offset[0]
                var neighborY = sourceY + offset[1]
                var neighborZ = sourceZ + offset[2]
                if (
                    !periodic && (neighborX !in 0 until constraints.sizeX ||
                    neighborY !in 0 until constraints.sizeY ||
                    neighborZ !in 0 until constraints.sizeZ)
                ) {
                    continue
                }
                if (periodic) {
                    neighborX = Math.floorMod(neighborX, constraints.sizeX)
                    neighborY = Math.floorMod(neighborY, constraints.sizeY)
                    neighborZ = Math.floorMod(neighborZ, constraints.sizeZ)
                }

                val neighborIndex = constraints.index(neighborX, neighborY, neighborZ)
                var changed = false
                for (neighborVariant in state[neighborIndex].indices) {
                    if (!state[neighborIndex][neighborVariant]) continue
                    val supported = state[sourceIndex].indices.any { sourceVariant ->
                        state[sourceIndex][sourceVariant] &&
                            tileSet.neighbors[direction][sourceVariant][neighborVariant]
                    }
                    if (!supported) {
                        state[neighborIndex][neighborVariant] = false
                        changed = true
                    }
                }
                if (state[neighborIndex].none { it }) {
                    val sourceVariants = state[sourceIndex].activeVariantDescription()
                    return Contradiction(
                        "$label WFC contradiction from $sourceX,$sourceY,$sourceZ " +
                            "(value=${constraints[sourceX, sourceY, sourceZ]}, variants=$sourceVariants) " +
                            "toward direction $direction at $neighborX,$neighborY,$neighborZ " +
                            "(value=${constraints[neighborX, neighborY, neighborZ]}, no variants remain)"
                    )
                }
                if (changed) queue.add(neighborIndex)
            }
        }
        return null
    }

    private fun nextCell(state: Array<BooleanArray>, candidateRandom: Random): Int {
        var minimum = Double.POSITIVE_INFINITY
        val candidates = ArrayList<Int>()
        for (index in state.indices) {
            val remaining = state[index].count { it }
            if (remaining <= 1) continue
            val entropy = if (shannon) {
                val weights = state[index].indices.sumOf { variant ->
                    if (state[index][variant]) tileSet.variants[variant].weight else 0.0
                }
                var value = 0.0
                for (variant in state[index].indices) if (state[index][variant]) {
                    val probability = tileSet.variants[variant].weight / weights
                    if (probability > 0.0) value -= probability * ln(probability)
                }
                value
            } else remaining.toDouble()
            when {
                entropy < minimum -> {
                    minimum = entropy
                    candidates.clear()
                    candidates += index
                }
                entropy == minimum -> candidates += index
            }
        }
        return if (candidates.isEmpty()) -1 else candidates[candidateRandom.nextInt(candidates.size)]
    }

    private fun chooseVariant(allowed: BooleanArray, candidateRandom: Random): Int {
        val total = allowed.indices.sumOf { index ->
            if (allowed[index]) tileSet.variants[index].weight else 0.0
        }
        var threshold = candidateRandom.nextDouble() * total
        for (index in allowed.indices) {
            if (!allowed[index]) continue
            threshold -= tileSet.variants[index].weight
            if (threshold <= 0.0) return index
        }
        return allowed.indexOfLast { it }
    }

    private fun BooleanArray.activeVariantDescription(): String = indices
        .filter { this[it] }
        .joinToString(prefix = "[", postfix = "]") { index ->
            "$index:${tileSet.variants[index].name}"
        }

    private fun renderForcedTiles(): List<CellChange> {
        val changes = ArrayList<CellChange>()
        for (cellIndex in wave.indices) {
            if (rendered[cellIndex] || wave[cellIndex].count { it } != 1) continue
            val variantIndex = wave[cellIndex].indexOfFirst { it }
            val variant = tileSet.variants[variantIndex]
            val cellX = cellIndex % constraints.sizeX
            val cellY = cellIndex / constraints.sizeX % constraints.sizeY
            val cellZ = cellIndex / (constraints.sizeX * constraints.sizeY)
            for (z in 0 until tileSet.sizeZ) {
                for (y in 0 until tileSet.sizeY) {
                    for (x in 0 until tileSet.sizeX) {
                        val voxelIndex = x + y * tileSet.sizeX + z * tileSet.sizeX * tileSet.sizeY
                        val outputX = cellX * strideX + x
                        val outputY = cellY * strideY + y
                        val outputZ = cellZ * strideZ + z
                        val next = variant.voxels[voxelIndex]
                        val previous = output[outputX, outputY, outputZ]
                        if (previous == next) continue
                        output[outputX, outputY, outputZ] = next
                        changes += CellChange(
                            index = output.index(outputX, outputY, outputZ),
                            x = outputX,
                            y = outputY,
                            z = outputZ,
                            before = previous,
                            after = next,
                        )
                    }
                }
            }
            rendered[cellIndex] = true
        }
        return changes
    }

    companion object {
        private val DIRECTIONS = arrayOf(
            intArrayOf(1, 0, 0),
            intArrayOf(0, 1, 0),
            intArrayOf(-1, 0, 0),
            intArrayOf(0, -1, 0),
            intArrayOf(0, 0, 1),
            intArrayOf(0, 0, -1),
        )
    }

    private data class Choice(val cell: Int, val variant: Int)

    private data class Contradiction(val message: String)

    private data class PlanningAttempt(
        val wave: Array<BooleanArray>,
        val choices: MutableList<Choice>,
        val random: Random,
    )
}
