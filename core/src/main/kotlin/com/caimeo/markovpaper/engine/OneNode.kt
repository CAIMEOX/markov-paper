package com.caimeo.markovpaper.engine

import java.util.Random

class OneNode(
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
        val selected = matches[random.nextInt(matches.size)]
        counter++
        return selected.rule.apply(grid, selected.x, selected.y, selected.z)
    }

    override fun reset() {
        counter = 0
    }
}
