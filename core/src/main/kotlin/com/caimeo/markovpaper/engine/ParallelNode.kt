package com.caimeo.markovpaper.engine

import java.util.Random

class ParallelNode(
    private val grid: VoxelGrid,
    private val rules: List<Rule>,
    private val random: Random,
    private val maxSteps: Int = 0,
) : RewriteNode {
    private var counter = 0

    override fun advance(): StepDelta? {
        if (maxSteps > 0 && counter >= maxSteps) return null

        val staged = LinkedHashMap<Int, RuleWrite>()
        for (match in findMatches(grid, rules)) {
            if (random.nextDouble() > match.rule.probability) continue
            for (write in match.rule.writes(grid, match.x, match.y, match.z)) {
                staged[write.index] = write
            }
        }
        if (staged.isEmpty()) return null

        val changes = ArrayList<CellChange>()
        for (write in staged.values) {
            val previous = grid[write.x, write.y, write.z]
            if (previous == write.value) continue
            grid[write.x, write.y, write.z] = write.value
            changes += CellChange(
                index = write.index,
                x = write.x,
                y = write.y,
                z = write.z,
                before = previous,
                after = write.value,
            )
        }

        counter++
        return StepDelta(changes)
    }

    override fun reset() {
        counter = 0
    }
}
