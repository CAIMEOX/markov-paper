package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.GridView
import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.engine.StepDelta
import com.caimeo.markovpaper.engine.VoxelGrid

/** Actual active-stage coordinates and alphabet, not the projected animation frame. */
data class ModelState(val grid: GridView, val symbols: List<Char>)

class CompiledMarkovModel(
    grid: VoxelGrid,
    node: RewriteNode,
    val symbols: List<Char>,
    private val activeState: (() -> ModelState)? = null,
) {
    /** Read-only animation/output view in the prepared final dimensions and alphabet. */
    val grid: GridView = grid.readOnly()
    val state: ModelState get() = activeState?.invoke() ?: ModelState(this.grid, symbols)
    private val program = node

    /** Root completion is terminal; internal MJ branches reset for re-entry. */
    val node: RewriteNode = object : RewriteNode {
        private var finished = false
        private var failure: Exception? = null

        override fun advance(): StepDelta? {
            failure?.let { throw it }
            if (finished) return null
            return try {
                program.advance().also { if (it == null) finished = true }
            } catch (exception: Exception) {
                failure = exception
                throw exception
            }
        }

        /** Re-enters the current grids; use prepared.create(seed, input) for a fresh execution. */
        override fun reset() {
            program.reset()
            finished = false
            failure = null
        }
    }

    fun valueOf(symbol: Char): Byte {
        val index = symbols.indexOf(symbol)
        require(index >= 0) { "Unknown model symbol '$symbol'" }
        return index.toByte()
    }
}
