package com.caimeo.markovpaper.engine

import java.util.Random

class AllNode(
    private val grid: VoxelGrid,
    private val rules: List<Rule>,
    private val random: Random,
    private val maxSteps: Int = 0,
) : RewriteNode {
    private var counter = 0

    override fun advance(): StepDelta? {
        if (maxSteps > 0 && counter >= maxSteps) return null

        val matches = findMatches(grid, rules)
        if (matches.isEmpty()) return null
        matches.shuffleWith(random)

        val claimed = BooleanArray(grid.sizeX * grid.sizeY * grid.sizeZ)
        val changes = ArrayList<CellChange>()
        for (match in matches) {
            val writes = match.rule.writes(grid, match.x, match.y, match.z)
            if (writes.any { claimed[it.index] }) continue
            writes.forEach { claimed[it.index] = true }
            changes += match.rule.apply(grid, match.x, match.y, match.z).changes
        }

        counter++
        return StepDelta(changes)
    }

    override fun reset() {
        counter = 0
    }
}
