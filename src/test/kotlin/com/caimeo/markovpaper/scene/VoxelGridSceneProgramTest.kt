package com.caimeo.markovpaper.scene

import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.SceneContributionKind
import com.caimeo.markovpaper.assemblage.SceneProvenance
import com.caimeo.markovpaper.assemblage.Vec3i
import com.caimeo.markovpaper.engine.CellChange
import com.caimeo.markovpaper.engine.RewriteNode
import com.caimeo.markovpaper.engine.StepDelta
import com.caimeo.markovpaper.engine.VoxelGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoxelGridSceneProgramTest {
    @Test
    fun `z-up voxel delta becomes a y-up procedural Scene delta`() {
        val grid = VoxelGrid(2, 3, 4)
        val node = OneChangeNode(grid, x = 1, y = 2, z = 3, value = 1)
        val program = VoxelGridSceneProgram(
            model = "test-wfc",
            seed = 9,
            grid = grid,
            node = node,
            symbols = listOf('.', 'X'),
            palette = mapOf(
                0.toByte() to BlockStateSpec("minecraft:air"),
                1.toByte() to BlockStateSpec("minecraft:stone"),
            ),
            modelZIsUp = true,
        )

        assertEquals(Extent3i(2, 4, 3), program.initial.size)
        val change = program.advance()!!.changes.single()
        assertEquals(Vec3i(1, 3, 2), change.position)
        assertEquals(BlockStateSpec("minecraft:stone"), change.after)

        val selected = program.current.cells.getValue(change.position).selected!!
        val provenance = assertIs<SceneProvenance.Procedural>(selected.provenance)
        assertEquals(Vec3i(1, 2, 3), provenance.sourceCell)
        assertEquals('X', provenance.symbol)
        assertEquals(SceneContributionKind.PROCEDURAL_BLOCK, selected.kind)
    }

    @Test
    fun `procedural air kind follows BlockState semantics rather than value zero`() {
        val grid = VoxelGrid(1, 1, 1)
        val program = VoxelGridSceneProgram(
            model = "fill",
            seed = 3,
            grid = grid,
            node = CompletedNode,
            symbols = listOf('B'),
            palette = mapOf(0.toByte() to BlockStateSpec("minecraft:black_concrete")),
            modelZIsUp = false,
        )

        assertEquals(
            SceneContributionKind.PROCEDURAL_BLOCK,
            program.initial.cells.getValue(Vec3i(0, 0, 0)).selected!!.kind,
        )
    }

    @Test
    fun `large engine delta is converted over bounded advances`() {
        val grid = VoxelGrid(3, 1, 1)
        val program = VoxelGridSceneProgram(
            model = "batched",
            seed = 4,
            grid = grid,
            node = ThreeChangeNode(grid),
            symbols = listOf('.', 'X'),
            palette = mapOf(
                0.toByte() to BlockStateSpec("minecraft:air"),
                1.toByte() to BlockStateSpec("minecraft:stone"),
            ),
            modelZIsUp = false,
            changesPerAdvance = 2,
        )

        assertEquals(2, program.advance()!!.changes.size)
        assertEquals(1, program.advance()!!.changes.size)
        assertEquals(null, program.advance())
        assertTrue(program.current.cells.values.all {
            it.state == BlockStateSpec("minecraft:stone")
        })
    }

    private class OneChangeNode(
        private val grid: VoxelGrid,
        private val x: Int,
        private val y: Int,
        private val z: Int,
        private val value: Byte,
    ) : RewriteNode {
        private var done = false

        override fun advance(): StepDelta? {
            if (done) return null
            done = true
            val before = grid[x, y, z]
            grid[x, y, z] = value
            return StepDelta(
                listOf(
                    CellChange(
                        index = grid.index(x, y, z),
                        x = x,
                        y = y,
                        z = z,
                        before = before,
                        after = value,
                    )
                )
            )
        }

        override fun reset() {
            done = false
        }
    }

    private data object CompletedNode : RewriteNode {
        override fun advance(): StepDelta? = null

        override fun reset() = Unit
    }

    private class ThreeChangeNode(private val grid: VoxelGrid) : RewriteNode {
        private var done = false

        override fun advance(): StepDelta? {
            if (done) return null
            done = true
            return StepDelta(
                (0..2).map { x ->
                    val before = grid[x, 0, 0]
                    grid[x, 0, 0] = 1
                    CellChange(grid.index(x, 0, 0), x, 0, 0, before, 1)
                }
            )
        }

        override fun reset() {
            done = false
        }
    }
}
