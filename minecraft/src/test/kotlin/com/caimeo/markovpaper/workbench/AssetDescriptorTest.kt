package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.AuthoredPalette
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.ControlKind
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.Vec3i
import kotlin.test.Test
import kotlin.test.assertEquals

class AssetDescriptorTest {
    @Test
    fun `descriptor exposes structural facts and authored jigsaw orientation`() {
        val asset = StructureAsset(
            id = AssetId("test:gallery-piece"),
            size = Extent3i(3, 2, 2),
            palettes = listOf(
                AuthoredPalette(
                    mapOf(
                        Vec3i(0, 0, 0) to AuthoredCell.Block(
                            BlockStateSpec("minecraft:stone")
                        ),
                        Vec3i(2, 0, 0) to AuthoredCell.AuthoredAir,
                        Vec3i(1, 1, 1) to AuthoredCell.Control(
                            kind = ControlKind.JIGSAW,
                            state = BlockStateSpec("minecraft:jigsaw[orientation=north_up]"),
                            orientation = "north_up",
                        ),
                    )
                )
            ),
            tags = emptySet(),
        )

        val descriptor = AssetDescriptors.describe(asset)

        assertEquals(asset.id, descriptor.id)
        assertEquals(asset.size, descriptor.size)
        assertEquals(1, descriptor.solidCells)
        assertEquals(1, descriptor.authoredAirCells)
        assertEquals(mapOf("minecraft:stone" to 1), descriptor.materialCounts)
        assertEquals(
            listOf(
                AssetControlMarker(
                    position = Vec3i(1, 1, 1),
                    kind = ControlKind.JIGSAW,
                    orientation = "north_up",
                )
            ),
            descriptor.controls,
        )
        assertEquals(1, descriptor.boundaries.getValue(BoundaryFace.WEST).solidCells)
        assertEquals(1, descriptor.boundaries.getValue(BoundaryFace.EAST).authoredAirCells)
    }
}
