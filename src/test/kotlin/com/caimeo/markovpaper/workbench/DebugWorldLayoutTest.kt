package com.caimeo.markovpaper.workbench

import com.caimeo.markovpaper.assemblage.AssetId
import com.caimeo.markovpaper.assemblage.Extent3i
import com.caimeo.markovpaper.assemblage.Vec3i
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DebugWorldLayoutTest {
    @Test
    fun `debug plots wrap rows and keep every declared extent disjoint`() {
        val descriptors = (1..41).map { index ->
            AssetDescriptor(
                id = AssetId("test:asset_$index"),
                size = Extent3i(22 - index % 3, 8, 18 + index % 4),
                solidCells = 1,
                authoredAirCells = 0,
                controls = emptyList(),
                materialCounts = mapOf("minecraft:stone" to 1),
                boundaries = BoundaryFace.entries.associateWith { BoundaryProfile(0, 0) },
            )
        }

        val layout = buildDebugWorldLayout(descriptors, columns = 20, baseY = 64)

        assertEquals(Vec3i(2, 64, 2), layout.plots.first().origin)
        assertEquals(64, layout.plots[20].origin.y)
        assertEquals(layout.plots.first().origin.x, layout.plots[20].origin.x)
        assertFalse(layout.plots.first().origin.z == layout.plots[20].origin.z)
        for (left in layout.plots.indices) for (right in left + 1 until layout.plots.size) {
            assertFalse(overlaps(layout.plots[left], layout.plots[right]))
        }
    }

    private fun overlaps(left: DebugPlot, right: DebugPlot): Boolean =
        left.origin.x < right.origin.x + right.descriptor.size.x &&
            right.origin.x < left.origin.x + left.descriptor.size.x &&
            left.origin.z < right.origin.z + right.descriptor.size.z &&
            right.origin.z < left.origin.z + left.descriptor.size.z
}
