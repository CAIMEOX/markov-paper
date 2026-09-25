package com.caimeo.markovpaper.scene

import com.caimeo.markovpaper.assemblage.AssemblageAddition
import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.AuthoredPalette
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.FrontierAssemblageOutcome
import com.caimeo.markovpaper.assemblage.FrontierAssemblagePlanner
import com.caimeo.markovpaper.assemblage.FrontierAssemblageRequest
import com.caimeo.markovpaper.assemblage.HorizontalRotation
import com.caimeo.markovpaper.assemblage.OrientedStructureAsset
import com.caimeo.markovpaper.assemblage.SceneCell
import com.caimeo.markovpaper.assemblage.SceneProvenance
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.StructureAssetTransforms
import com.caimeo.markovpaper.assemblage.Vec3i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MacroAssemblageProgramTest {
    @Test
    fun `instance deltas replay to the final sparse Scene without trace frames`() {
        val root = filledAsset("test:root", Extent3i(5, 4, 7), "minecraft:stone")
        val palette = listOf(
            addition(filledAsset("test:oak", Extent3i(4, 5, 6), "minecraft:oak_planks")),
            addition(filledAsset("test:brick", Extent3i(7, 3, 4), "minecraft:bricks")),
        )
        val plan = assertIs<FrontierAssemblageOutcome.Completed>(
            FrontierAssemblagePlanner.plan(
                FrontierAssemblageRequest(
                    root = root,
                    palette = palette,
                    targetInstances = 100,
                    seed = 91,
                )
            )
        ).plan
        val program = MacroAssemblageProgram(plan, workUnitsPerAdvance = 64)
        assertTrue(program.initial.cells.isEmpty())
        val replayed = program.initial.cells
            .mapValuesTo(LinkedHashMap()) { (_, cell) -> cell.state }
        var deltas = 0

        while (true) {
            val delta = program.advance() ?: break
            deltas++
            assertTrue(program.lastAdvanceWorkUnits in 1..64)
            assertEquals("macro-growth", delta.phase)
            for (change in delta.changes) {
                if (change.after == null) replayed.remove(change.position)
                else replayed[change.position] = change.after
            }
        }

        assertTrue(deltas > plan.instances.size)
        assertEquals(plan.instances.size, program.completedInstances)
        assertEquals(
            program.current.cells.mapValues { (_, cell) -> cell.state },
            replayed,
        )
        assertEquals(plan.bounds.size, program.current.size)
        assertTrue(program.current.cells.keys.all(program.current.size::contains))
        assertEquals(
            plan.instances.map { it.id }.toSet(),
            program.current.cells.values
                .flatMap(SceneCell::contributions)
                .mapNotNull { (it.provenance as? SceneProvenance.Authored)?.instance }
                .toSet(),
        )
        assertTrue(
            program.current.cells.values.any { cell ->
                cell.contributions.any { it.provenance is SceneProvenance.LocalCoherence }
            }
        )

        val singleUnit = MacroAssemblageProgram(plan, workUnitsPerAdvance = 1)
        val wide = MacroAssemblageProgram(plan, workUnitsPerAdvance = 2_048)
        drain(singleUnit)
        drain(wide)
        assertEquals(program.current, singleUnit.current)
        assertEquals(program.current, wide.current)
    }

    private fun drain(program: MacroAssemblageProgram) {
        while (program.advance() != null) {
            assertTrue(program.lastAdvanceWorkUnits in 1..2_048)
        }
    }

    private fun addition(asset: StructureAsset): AssemblageAddition = AssemblageAddition(
        HorizontalRotation.entries.map { rotation ->
            OrientedStructureAsset(
                rotation,
                StructureAssetTransforms.rotateY(asset, rotation),
            )
        }
    )

    private fun filledAsset(id: String, size: Extent3i, state: String): StructureAsset =
        StructureAsset(
            id = AssetId(id),
            size = size,
            palettes = listOf(
                AuthoredPalette(
                    buildMap {
                        for (x in 0 until size.x) {
                            for (y in 0 until size.y) {
                                for (z in 0 until size.z) {
                                    put(
                                        Vec3i(x, y, z),
                                        AuthoredCell.Block(BlockStateSpec(state)),
                                    )
                                }
                            }
                        }
                    }
                )
            ),
            tags = emptySet(),
        )
}
