package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.CellChange
import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.engine.StepDelta
import com.caimeo.markovpaper.engine.VoxelGrid

data class GridSize(val x: Int, val y: Int, val z: Int) {
    init { require(x > 0 && y > 0 && z > 0) { "Grid dimensions must be positive: $this" } }

    val cellCount: Long get() = Math.multiplyExact(Math.multiplyExact(x.toLong(), y.toLong()), z.toLong())
}

/** Preparation resolves resources and dimensions without allocating or running model grids. */
class PreparedMarkovModel(
    val size: GridSize,
    val symbols: List<Char>,
    val inputSize: GridSize = size,
    val inputSymbols: List<Char> = symbols,
    private val instantiate: (Long, ByteArray?) -> CompiledMarkovModel,
) {
    /** Input cells are copied, x-fastest, and replace the XML origin seed when supplied. */
    fun create(seed: Long, initialState: ByteArray? = null): CompiledMarkovModel {
        val input = initialState?.copyOf()
        if (input != null) {
            require(input.size.toLong() == inputSize.cellCount) { "Initial state must contain ${inputSize.cellCount} cells" }
            require(input.all { it.toInt() in inputSymbols.indices }) { "Initial state contains an unknown input symbol index" }
        }
        return instantiate(seed, input)
    }
}

/** Keeps every animation frame in the final coordinate system and alphabet. */
internal class ModelFrameNode(
    private val delegate: RewriteNode,
    private val target: VoxelGrid,
    private val symbols: List<Char>,
    private val resetActive: () -> Unit,
    private val active: () -> Pair<VoxelGrid, List<Char>>,
) : RewriteNode {
    private var displayed: VoxelGrid? = null
    private var finished = false

    init { refresh() }

    override fun advance(): StepDelta? {
        if (finished) return null
        val delta = delegate.advance()
        val (source, alphabet) = active()
        val changes = if (source !== displayed) refresh() else {
            val changes = LinkedHashMap<Int, CellChange>()
            delta?.changes?.forEach { project(source, alphabet, it.x, it.y, it.z, changes) }
            changes.values.filter { it.before != it.after }
        }
        if (delta == null) finished = true
        return if (delta == null && changes.isEmpty()) null else StepDelta(changes)
    }

    override fun reset() {
        delegate.reset()
        resetActive()
        finished = false
        displayed = null
    }

    private fun refresh(): List<CellChange> {
        val (source, alphabet) = active()
        val changes = LinkedHashMap<Int, CellChange>()
        for (z in 0 until source.sizeZ) for (y in 0 until source.sizeY) for (x in 0 until source.sizeX) {
            project(source, alphabet, x, y, z, changes)
        }
        displayed = source
        return changes.values.filter { it.before != it.after }
    }

    private fun project(
        source: VoxelGrid,
        alphabet: List<Char>,
        x: Int, y: Int, z: Int,
        changes: MutableMap<Int, CellChange>,
    ) {
        val value = source[x, y, z].toInt()
        val matching = symbols.indexOf(alphabet[value])
        val mapped = when {
            alphabet == symbols -> value
            value == 0 -> 0
            matching >= 0 -> matching
            symbols.size > 1 -> 1 + (value - 1) % (symbols.size - 1)
            else -> 0
        }.toByte()
        fun scaled(value: Int, to: Int, from: Int) = (value.toLong() * to / from).toInt()
        for (oz in scaled(z, target.sizeZ, source.sizeZ) until scaled(z + 1, target.sizeZ, source.sizeZ)) {
            for (oy in scaled(y, target.sizeY, source.sizeY) until scaled(y + 1, target.sizeY, source.sizeY)) {
                for (ox in scaled(x, target.sizeX, source.sizeX) until scaled(x + 1, target.sizeX, source.sizeX)) {
                    val previous = target[ox, oy, oz]
                    if (previous == mapped) continue
                    val index = target.index(ox, oy, oz)
                    target[ox, oy, oz] = mapped
                    changes[index] = CellChange(index, ox, oy, oz, changes[index]?.before ?: previous, mapped)
                }
            }
        }
    }
}
