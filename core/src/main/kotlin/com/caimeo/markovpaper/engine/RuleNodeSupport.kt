package com.caimeo.markovpaper.engine

import java.util.Random

internal data class RuleMatch(
    val rule: Rule,
    val x: Int,
    val y: Int,
    val z: Int,
)

internal fun findMatches(grid: VoxelGrid, rules: List<Rule>): MutableList<RuleMatch> {
    val matches = ArrayList<RuleMatch>()
    for (rule in rules) {
        for (z in 0..grid.sizeZ - rule.sizeZ) {
            for (y in 0..grid.sizeY - rule.sizeY) {
                for (x in 0..grid.sizeX - rule.sizeX) {
                    if (rule.matches(grid, x, y, z)) matches += RuleMatch(rule, x, y, z)
                }
            }
        }
    }
    return matches
}

internal fun <T> MutableList<T>.shuffleWith(random: Random) {
    for (index in lastIndex downTo 1) {
        val other = random.nextInt(index + 1)
        val value = this[index]
        this[index] = this[other]
        this[other] = value
    }
}
