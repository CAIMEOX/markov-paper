package com.caimeo.markovpaper.assemblage

import kotlin.test.Test
import kotlin.test.assertEquals

class StructureAssetTransformTest {
    @Test
    fun `clockwise y rotation transforms extent coordinates and exact block state`() {
        val asset = StructureAsset(
            id = AssetId("minecraft:village/test"),
            size = Extent3i(2, 1, 3),
            palettes = listOf(
                AuthoredPalette(
                    mapOf(
                        Vec3i(0, 0, 0) to AuthoredCell.Block(
                            BlockStateSpec("minecraft:oak_stairs[facing=east]")
                        ),
                        Vec3i(1, 0, 2) to AuthoredCell.AuthoredAir,
                    )
                )
            ),
            tags = emptySet(),
        )
        val stateRotation = AuthoredCellRotation { cell, rotation ->
            when (cell) {
                is AuthoredCell.Block -> cell.copy(
                    state = BlockStateSpec(
                        cell.state.canonical.replace("facing=east", "facing=south")
                    )
                )
                else -> cell
            }
        }

        val rotated = StructureAssetTransforms.rotateY(
            asset,
            HorizontalRotation.CLOCKWISE_90,
            stateRotation,
        )

        assertEquals(Extent3i(3, 1, 2), rotated.size)
        assertEquals(
            AuthoredCell.Block(BlockStateSpec("minecraft:oak_stairs[facing=south]")),
            rotated.palettes.single()[Vec3i(2, 0, 0)],
        )
        assertEquals(
            AuthoredCell.AuthoredAir,
            rotated.palettes.single()[Vec3i(0, 0, 1)],
        )
    }
}
