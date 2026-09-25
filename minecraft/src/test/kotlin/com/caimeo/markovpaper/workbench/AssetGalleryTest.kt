package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.AuthoredCell
import com.caimeo.markovpaper.assemblage.AuthoredPalette
import com.caimeo.markovpaper.assemblage.BlockStateSpec
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.StructureAsset
import com.caimeo.markovpaper.assemblage.Vec3i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AssetGalleryTest {
    @Test
    fun `gallery packs unequal asset extents without overlap`() {
        val assets = listOf(
            asset("test:a", Extent3i(3, 4, 5)),
            asset("test:b", Extent3i(8, 2, 2)),
            asset("test:c", Extent3i(2, 9, 4)),
            asset("test:d", Extent3i(6, 3, 7)),
            asset("test:e", Extent3i(4, 5, 3)),
            asset("test:f", Extent3i(5, 2, 8)),
        )

        val gallery = buildAssetGallery(assets, columns = 3, gap = 4)

        assertEquals(6, gallery.placements.size)
        for (leftIndex in gallery.placements.indices) {
            for (rightIndex in leftIndex + 1 until gallery.placements.size) {
                assertFalse(
                    overlaps(
                        gallery.placements[leftIndex],
                        gallery.placements[rightIndex],
                    )
                )
            }
        }
        assertEquals(assets.size, gallery.scene.cells.size)
    }

    private fun overlaps(left: GalleryPlacement, right: GalleryPlacement): Boolean =
        left.origin.x < right.origin.x + right.descriptor.size.x &&
            right.origin.x < left.origin.x + left.descriptor.size.x &&
            left.origin.y < right.origin.y + right.descriptor.size.y &&
            right.origin.y < left.origin.y + left.descriptor.size.y &&
            left.origin.z < right.origin.z + right.descriptor.size.z &&
            right.origin.z < left.origin.z + left.descriptor.size.z

    private fun asset(id: String, size: Extent3i): StructureAsset = StructureAsset(
        id = AssetId(id),
        size = size,
        palettes = listOf(
            AuthoredPalette(
                mapOf(
                    Vec3i(0, 0, 0) to AuthoredCell.Block(
                        BlockStateSpec("minecraft:stone")
                    )
                )
            )
        ),
        tags = emptySet(),
    )
}
