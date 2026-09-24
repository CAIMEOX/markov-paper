package com.caimeo.markovpaper.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class CubeSymmetryTest {
    @Test
    fun `cube rotations expand a directed line to six unique directions`() {
        val baseRule = Rule(
            input = byteArrayOf(1, 0, 0),
            output = byteArrayOf(1, 2, 1),
            sizeX = 3,
            sizeY = 1,
            sizeZ = 1,
        )

        val rotations = CubeSymmetry.rotations(baseRule)
        val endpoints = rotations.map { rule ->
            val grid = VoxelGrid(5, 5, 5)
            grid[2, 2, 2] = 1
            val (x, y, z) = findSingleMatch(rule, grid)
            val endpoint = rule.apply(grid, x, y, z).changes.single { it.after.toInt() == 1 }
            Triple(endpoint.x - 2, endpoint.y - 2, endpoint.z - 2)
        }.toSet()

        assertEquals(6, rotations.size)
        assertEquals(
            setOf(
                Triple(-2, 0, 0),
                Triple(2, 0, 0),
                Triple(0, -2, 0),
                Triple(0, 2, 0),
                Triple(0, 0, -2),
                Triple(0, 0, 2),
            ),
            endpoints,
        )
    }

    private fun findSingleMatch(rule: Rule, grid: VoxelGrid): Triple<Int, Int, Int> {
        val matches = buildList {
            for (z in 0..grid.sizeZ - rule.sizeZ) {
                for (y in 0..grid.sizeY - rule.sizeY) {
                    for (x in 0..grid.sizeX - rule.sizeX) {
                        if (rule.matches(grid, x, y, z)) add(Triple(x, y, z))
                    }
                }
            }
        }
        return matches.single()
    }
}
