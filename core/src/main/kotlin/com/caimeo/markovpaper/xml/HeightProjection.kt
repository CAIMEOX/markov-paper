package com.caimeo.markovpaper.xml

import com.caimeo.markovpaper.engine.CellChange
import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.engine.StepDelta
import com.caimeo.markovpaper.engine.VoxelGrid

object HeightProjection {
    /** Columns contain either one symbol, or floor/lower-half/upper-half/ceiling symbols. */
    fun prepare(
        source: PreparedMarkovModel,
        height: Int,
        outputSymbols: List<Char>,
        columns: Map<Char, String>,
        maxCells: Long,
    ): PreparedMarkovModel {
        require(source.size.z == 1) { "Height projection requires a 2D final grid" }
        require(height > 0) { "Projection height must be positive" }
        require(outputSymbols.isNotEmpty() && outputSymbols.size <= 128 && outputSymbols.distinct().size == outputSymbols.size)
        require(columns.keys == source.symbols.toSet()) { "Projection columns must cover exactly the source alphabet ${source.symbols}" }
        require(columns.values.all { it.length in setOf(1, 4) && it.all(outputSymbols::contains) }) {
            "Each column needs one or four declared output symbols"
        }
        val size = GridSize(source.size.x, source.size.y, height)
        require(size.cellCount <= maxCells) { "Projected volume exceeds $maxCells voxels" }
        val profiles = columns.toMap()
        val symbols = outputSymbols.toList()
        return PreparedMarkovModel(size, symbols, source.inputSize, source.inputSymbols) { seed, input ->
            extrude(source.create(seed, input), height, symbols) { symbol, level ->
                val column = profiles.getValue(symbol)
                column[when {
                    column.length == 1 || level == 0 -> 0
                    level == height - 1 -> 3
                    level < height / 2 + height % 2 -> 1
                    else -> 2
                }]
            }
        }
    }

    fun extrude(
        source: CompiledMarkovModel,
        height: Int,
        outputSymbols: List<Char>,
        project: (sourceSymbol: Char, level: Int) -> Char,
    ): CompiledMarkovModel {
        require(source.grid.sizeZ == 1) { "Height projection requires a 2D source grid" }
        require(height > 0) { "Projection height must be positive" }
        require(outputSymbols.isNotEmpty() && outputSymbols.distinct().size == outputSymbols.size)
        val outputValues = outputSymbols.withIndex().associate { (index, symbol) ->
            symbol to index.toByte()
        }
        val output = VoxelGrid(source.grid.sizeX, source.grid.sizeY, height)

        fun projectedValue(sourceValue: Byte, level: Int): Byte {
            val sourceSymbol = source.symbols[sourceValue.toInt()]
            val outputSymbol = project(sourceSymbol, level)
            return requireNotNull(outputValues[outputSymbol]) {
                "Projection produced undeclared symbol '$outputSymbol'"
            }
        }

        fun projectColumn(x: Int, y: Int, changes: MutableList<CellChange>?) {
            val sourceValue = source.grid[x, y, 0]
            for (level in 0 until height) {
                val next = projectedValue(sourceValue, level)
                val previous = output[x, y, level]
                if (next == previous) continue
                output[x, y, level] = next
                changes?.add(
                    CellChange(
                        index = output.index(x, y, level),
                        x = x,
                        y = y,
                        z = level,
                        before = previous,
                        after = next,
                    )
                )
            }
        }

        for (y in 0 until source.grid.sizeY) {
            for (x in 0 until source.grid.sizeX) projectColumn(x, y, changes = null)
        }
        val node = object : RewriteNode {
            override fun advance(): StepDelta? {
                val sourceDelta = source.node.advance() ?: return null
                val changes = ArrayList<CellChange>()
                sourceDelta.changes
                    .map { it.x to it.y }
                    .distinct()
                    .forEach { (x, y) -> projectColumn(x, y, changes) }
                return StepDelta(changes)
            }

            override fun reset() {
                source.node.reset()
                for (y in 0 until source.grid.sizeY) {
                    for (x in 0 until source.grid.sizeX) projectColumn(x, y, changes = null)
                }
            }
        }
        return CompiledMarkovModel(output, node, outputSymbols, activeState = { source.state })
    }
}
