package com.caimeo.markovpaper.scene

import com.caimeo.markovpaper.assemblage.AssemblageAddition
import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.AuthoredPalette
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.HorizontalRotation
import com.caimeo.markovpaper.assemblage.OrientedStructureAsset
import com.caimeo.markovpaper.assemblage.SceneCell
import com.caimeo.markovpaper.assemblage.SceneContribution
import com.caimeo.markovpaper.assemblage.SceneContributionKind
import com.caimeo.markovpaper.assemblage.SceneProvenance
import com.caimeo.markovpaper.assemblage.SceneSnapshot
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.TraceSource
import com.caimeo.markovpaper.assemblage.Vec3i
import com.caimeo.markovpaper.xml.MarkovXmlCompiler
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HybridSceneProgramTest {
    @Test
    fun `WFC deltas finish before authored additions and final Scene preserves both sources`() {
        val prefix = OneDeltaProceduralProgram()
        val village = linearAsset("test:village", "minecraft:oak_planks")
        val hybrid = HybridSceneProgram(
            prefix = prefix,
            prefixSource = TraceSource.Procedural("test-wfc", 42),
            additions = listOf(
                AssemblageAddition(
                    listOf(OrientedStructureAsset(HorizontalRotation.NONE, village))
                )
            ),
            targetSolidOverlap = 0.5,
            seed = 42,
            maxSolidPairEvaluations = 100,
            changesPerAdvance = 1,
        )

        val phases = buildList {
            repeat(100) {
                val delta = hybrid.advance() ?: return@buildList
                add(delta.phase)
            }
        }

        assertTrue(phases.first() == "test-wfc")
        assertTrue(phases.indexOfFirst { it?.startsWith("assemblage:") == true } > 0)
        assertTrue(hybrid.current.cells.values.any { cell ->
            cell.contributions.any { it.provenance is SceneProvenance.Procedural }
        })
        assertTrue(hybrid.current.cells.values.any { village.id in it.sources })
    }

    @Test
    fun `bundled stairs WFC completes before grafting an authored fragment`() {
        val seed = 12L
        val model = MarkovXmlCompiler.compile(
            requireNotNull(javaClass.getResource("/models/stairs3d.xml")).readText(), 3, 3, 3, seed,
        )
        val prefix = VoxelGridSceneProgram(
            model = "stairs3d",
            seed = seed,
            grid = model.grid,
            node = model.node,
            symbols = model.symbols,
            palette = model.symbols.indices.associate { index ->
                index.toByte() to BlockStateSpec(
                    if (index == 0) "minecraft:air" else "minecraft:stone"
                )
            },
            modelZIsUp = true,
        )
        val village = linearAsset("test:village", "minecraft:oak_planks")
        val hybrid = HybridSceneProgram(
            prefix = prefix,
            prefixSource = TraceSource.Procedural("stairs3d", seed),
            additions = listOf(
                AssemblageAddition(
                    listOf(OrientedStructureAsset(HorizontalRotation.NONE, village))
                )
            ),
            targetSolidOverlap = 0.3,
            seed = seed,
            maxSolidPairEvaluations = 1_000_000,
            changesPerAdvance = 128,
        )

        var finalDelta: SceneProgramDelta? = null
        var advances = 0
        while (advances < 10_000) {
            val delta = hybrid.advance() ?: break
            finalDelta = delta
            advances++
        }

        assertNotNull(finalDelta)
        assertTrue(hybrid.advance() == null)
        assertTrue(hybrid.current.cells.values.any { village.id in it.sources })
        assertTrue(hybrid.current.cells.values.any { cell ->
            cell.contributions.any { it.provenance is SceneProvenance.Procedural }
        })
    }

    private class OneDeltaProceduralProgram : SceneProgram {
        private val stone = BlockStateSpec("minecraft:stone")
        private val air = BlockStateSpec("minecraft:air")
        private var done = false
        private var snapshot = scene(secondSolid = false)

        override val initial: SceneSnapshot = snapshot
        override val current: SceneSnapshot
            get() = snapshot

        override fun advance(): SceneProgramDelta? {
            if (done) return null
            done = true
            snapshot = scene(secondSolid = true)
            return SceneProgramDelta(
                phase = "test-wfc",
                changes = listOf(
                    SceneProgramChange(Vec3i(1, 0, 0), air, stone)
                ),
            )
        }

        private fun scene(secondSolid: Boolean): SceneSnapshot = SceneSnapshot(
            size = Extent3i(2, 1, 1),
            cells = (0..1).associate { x ->
                val position = Vec3i(x, 0, 0)
                val solid = x == 0 || secondSolid
                val state = if (solid) stone else air
                val contribution = SceneContribution(
                    provenance = SceneProvenance.Procedural("test-wfc", 42, if (solid) 'X' else '.', position),
                    kind = if (solid) {
                        SceneContributionKind.PROCEDURAL_BLOCK
                    } else {
                        SceneContributionKind.PROCEDURAL_AIR
                    },
                    state = state,
                )
                position to SceneCell(state, contribution, listOf(contribution))
            },
        )
    }

    private fun linearAsset(id: String, state: String): StructureAsset = StructureAsset(
        id = AssetId(id),
        size = Extent3i(2, 1, 1),
        palettes = listOf(
            AuthoredPalette(
                mapOf(
                    Vec3i(0, 0, 0) to AuthoredCell.Block(BlockStateSpec(state)),
                    Vec3i(1, 0, 0) to AuthoredCell.Block(BlockStateSpec(state)),
                )
            )
        ),
        tags = emptySet(),
    )
}
